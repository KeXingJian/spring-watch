package com.springwatch.config;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.kafka.config.TopicBuilder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;


@Slf4j
@Configuration
public class KafkaTopicConfig {


    private final String metricsTopic = "monitor-metrics"  ;

    private final String logsTopic = "monitor-logs";

    private final String heartbeatTopic = "monitor-heartbeat";

    @Value("${spring.kafka.topics.metrics-partitions:3}")
    private int metricsPartitions;

    @Value("${spring.kafka.topics.logs-partitions:3}")
    private int logsPartitions;

    @Value("${spring.kafka.topics.heartbeat-partitions:1}")
    private int heartbeatPartitions;

    @Value("${spring.kafka.topics.dlq-partitions:1}")
    private int dlqPartitions;

    @Value("${spring.kafka.topics.replication-factor:1}")
    private short replicationFactor;

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    // ==================== 快路径:NewTopic bean 让 KafkaAdmin 处理 ====================

    @Bean
    public NewTopic metricsTopic() {
        return build(metricsTopic, metricsPartitions);
    }

    @Bean
    public NewTopic logsTopic() {
        return build(logsTopic, logsPartitions);
    }

    @Bean
    public NewTopic heartbeatTopic() {
        return build(heartbeatTopic, heartbeatPartitions);
    }

    @Bean
    public NewTopic metricsDlqTopic() {
        return build(metricsTopic + ".DLQ", dlqPartitions);
    }

    @Bean
    public NewTopic logsDlqTopic() {
        return build(logsTopic + ".DLQ", dlqPartitions);
    }

    @Bean
    public NewTopic heartbeatDlqTopic() {
        return build(heartbeatTopic + ".DLQ", 1);
    }

    private NewTopic build(String name, int partitions) {
        NewTopic t = TopicBuilder.name(name).partitions(partitions).replicas(replicationFactor).build();
        log.info("[spring-watch: Topic声明 - {} (partitions={}, replicas={})]", t.name(), partitions, replicationFactor);
        return t;
    }


    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        List<NewTopic> expected = List.of(
                build(metricsTopic, metricsPartitions),
                build(logsTopic, logsPartitions),
                build(heartbeatTopic, heartbeatPartitions),
                build(metricsTopic + ".DLQ", dlqPartitions),
                build(logsTopic + ".DLQ", dlqPartitions),
                build(heartbeatTopic + ".DLQ", 1)
        );

        Properties props = new Properties();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(AdminClientConfig.CLIENT_ID_CONFIG, "spring-watch-topic-bootstrap");
        props.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 5000);
        props.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 10000);

        try (AdminClient admin = AdminClient.create(props)) {
            var listed = admin.listTopics().names().get(8, TimeUnit.SECONDS);
            Map<String, Integer> existingPartitions = new HashMap<>();
            List<String> toDescribe = listed.stream()
                    .filter(n -> n.equals(metricsTopic) || n.equals(metricsTopic + ".DLQ")
                            || n.equals(logsTopic) || n.equals(logsTopic + ".DLQ")
                            || n.equals(heartbeatTopic) || n.equals(heartbeatTopic + ".DLQ"))
                    .toList();
            if (!toDescribe.isEmpty()) {
                var descs = admin.describeTopics(toDescribe).allTopicNames().get(8, TimeUnit.SECONDS);
                descs.forEach((name, desc) -> existingPartitions.put(name, desc.partitions().size()));
            }

            // 2) 区分:不存在 vs 已存在但 partition 数对 vs 已存在但 partition 数不对
            List<NewTopic> toCreate = new ArrayList<>();
            int mismatchedCount = 0;
            for (NewTopic nt : expected) {
                if (!existingPartitions.containsKey(nt.name())) {
                    toCreate.add(nt);
                    log.warn("[spring-watch: 兜底创建 topic - name={}, partitions={}, replicas={}]",
                            nt.name(), nt.numPartitions(), nt.replicationFactor());
                } else {
                    int actual = existingPartitions.get(nt.name());
                    int expectedPartitions = nt.numPartitions();
                    if (actual == expectedPartitions) {
                        log.debug("[spring-watch: topic 已存在且 partition 数对 - name={}, partitions={}]",
                                nt.name(), actual);
                    } else {
                        mismatchedCount++;
                        // Kafka 协议:partition 数只能加不能减,且不能通过 AdminClient 改
                        // 必须用户手动 `kafka-topics.sh --delete` 后让本方法下次启动重建
                        //   --delete 需在 broker 配 delete.topic.enable=true(默认 true)
                        log.error("[spring-watch: topic 已存在但 partition 数不符 - name={}, actual={}, expected={}]." +
                                        " Kafka 协议禁止缩 partition,需手动 `kafka-topics.sh --bootstrap-server localhost:9092 --delete --topic {}` 后重启 spring-watch",
                                nt.name(), actual, expectedPartitions, nt.name());
                    }
                }
            }

            // 3) 批量创建不存在的 topic
            if (!toCreate.isEmpty()) {
                try {
                    admin.createTopics(toCreate).all().get(8, TimeUnit.SECONDS);
                    log.info("[spring-watch: 兜底创建 topic 完成 - count={}, names={}]",
                            toCreate.size(),
                            toCreate.stream().map(NewTopic::name).toList());
                } catch (ExecutionException ee) {
                    // 部分 topic 可能因 broker auto-create 抢先创建(TOPIC_ALREADY_EXISTS)而不报错
                    // 这里把 TOPIC_ALREADY_EXISTS 单独识别,其他错误才打 ERROR
                    if (ee.getCause() instanceof org.apache.kafka.common.errors.TopicExistsException) {
                        log.info("[spring-watch: 兜底创建 topic —— 已被 broker 抢先 auto-create,跳过 - count={}]", toCreate.size());
                    } else {
                        throw ee;
                    }
                }
            } else {
                log.info("[spring-watch: 兜底检查完成 - 所有 topic 已存在,无需创建 mismatched={}]", mismatchedCount);
            }
            if (mismatchedCount > 0) {
                log.error("[spring-watch: ❌ {} 个 topic partition 数不符,Kafka 协议禁止缩,需手动 --delete + 重启", mismatchedCount);
            }
        } catch (Throwable t) {
            // 兜底失败不抛,spring-boot 不因此启动失败,后续 KafkaPartitionUtilizationGauge 也会报 unknown topic
            log.error("[spring-watch: 兜底创建 topic 失败 - error={}]", t.getMessage(), t);
        }
    }
}
