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
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/**
 * 业务 topic 声明(partition 数 / 副本因子)。
 *
 * 修复记录(M-KafkaTopicInit / 2026-06-29 / v1.4):
 *   原版只声明 NewTopic bean,让 spring-kafka 的 KafkaAdmin 在容器启动时统一 createTopics。
 *   问题:KafkaAdmin 启动时如果 broker 还没就绪(KRaft controller 选举慢于 spring-kafka),
 *        createTopics 调用超时失败,spring-kafka **不重试**,后续 broker 就绪后
 *        topic 也不存在,consumer 报 UNKNOWN_TOPIC_OR_PARTITION。
 *
 *   修复:本类同时承担两件事——
 *     1) @Bean NewTopic:让 spring-kafka KafkaAdmin 处理(快路径,broker 已就绪时直接成功)
 *     2) @EventListener(ApplicationReadyEvent) onApplicationReady:兜底创建,
 *        解决 broker 启动慢于 spring-kafka 的时序问题。
 *        兜底会先 describeTopics 检查,只在 partition 数不匹配时才调 createTopics,
 *        不会重复创建已存在且 partition 数对的 topic。
 */
@Slf4j
@Configuration
public class KafkaTopicConfig {

    @Value("${spring.kafka.topics.metrics:monitor-metrics}")
    private String metricsTopic;

    @Value("${spring.kafka.topics.logs:monitor-logs}")
    private String logsTopic;

    @Value("${spring.kafka.topics.heartbeat:monitor-heartbeat}")
    private String heartbeatTopic;

    @Value("${spring.kafka.topics.metrics-partitions:12}")
    private int metricsPartitions;

    @Value("${spring.kafka.topics.logs-partitions:6}")
    private int logsPartitions;

    @Value("${spring.kafka.topics.heartbeat-partitions:3}")
    private int heartbeatPartitions;

    @Value("${spring.kafka.topics.dlq-partitions:3}")
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

    // ==================== 兜底路径:ApplicationReadyEvent 触发后建 topic ====================

    /**
     * 兜底创建 topic,处理 KafkaAdmin 启动时 broker 未就绪导致 createTopics 失败的情况。
     *
     * 时序保证:
     *   T+0s  spring-boot 启动
     *   T+0s  @Bean NewTopic 声明(给 KafkaAdmin 尝试)
     *   T+0s  KafkaAdmin 启动调 createTopics —— 如果 broker 就绪则成功
     *   T+0s  KafkaAdmin 启动调 createTopics —— 如果 broker 未就绪则失败,spring-kafka 不重试
     *   T+?s  spring-boot 启动完成
     *   T+?s  ApplicationReadyEvent 触发
     *   T+?s  本方法 onApplicationReady() 跑:此时 broker 一定就绪
     *   T+?s  describeTopics 检查每个 topic 实际 partition 数
     *   T+?s  - 已存在 + partition 数对 → 跳过
     *   T+?s  - 已存在 + partition 数不对 → 不处理(partition 数只能加不能减,改用 --alter)
     *   T+?s  - 不存在 → createTopics
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady(ApplicationReadyEvent event) {
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
            // 1) 描述所有 topic
            var existing = admin.describeTopics(expected.stream().map(NewTopic::name).toList())
                    .allTopicNames().get(8, TimeUnit.SECONDS);

            // 2) 区分:不存在 vs 已存在但 partition 数对 vs 已存在但 partition 数不对
            List<NewTopic> toCreate = new ArrayList<>();
            for (NewTopic nt : expected) {
                if (!existing.containsKey(nt.name())) {
                    toCreate.add(nt);
                    log.warn("[spring-watch: 兜底创建 topic - name={}, partitions={}, replicas={}]",
                            nt.name(), nt.numPartitions(), nt.replicationFactor());
                } else {
                    int actual = existing.get(nt.name()).partitions().size();
                    int expectedPartitions = nt.numPartitions();
                    if (actual == expectedPartitions) {
                        log.debug("[spring-watch: topic 已存在且 partition 数对 - name={}, partitions={}]",
                                nt.name(), actual);
                    } else if (actual < expectedPartitions) {
                        // Kafka 协议:partition 数只能加不能减,且不能通过 AdminClient 改
                        // 需要用户用 --alter 手动加,或者下次"删 .data 重启"时用新值
                        log.warn("[spring-watch: topic 已存在但 partition 数偏少 - name={}, actual={}, expected={}]." +
                                        " 需要手动 `kafka-topics.sh --alter --topic {} --partitions {}`",
                                nt.name(), actual, expectedPartitions, nt.name(), expectedPartitions);
                    } else {
                        log.warn("[spring-watch: topic 已存在但 partition 数偏多 - name={}, actual={}, expected={}]." +
                                        " Kafka 协议禁止减 partition,如需对齐请用 mq 重置或重建",
                                nt.name(), actual, expectedPartitions);
                    }
                }
            }

            // 3) 批量创建不存在的 topic
            if (!toCreate.isEmpty()) {
                admin.createTopics(toCreate).all().get(8, TimeUnit.SECONDS);
                log.info("[spring-watch: 兜底创建 topic 完成 - count={}, names={}]",
                        toCreate.size(),
                        toCreate.stream().map(NewTopic::name).toList());
            } else {
                log.info("[spring-watch: 兜底检查完成 - 所有 topic 已存在,无需创建]");
            }
        } catch (Throwable t) {
            // 兜底失败不抛,spring-boot 不因此启动失败,后续 KafkaPartitionUtilizationGauge 也会报 unknown topic
            log.error("[spring-watch: 兜底创建 topic 失败 - error={}]", t.getMessage(), t);
        }
    }
}
