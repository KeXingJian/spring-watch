package com.springwatch.config;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

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

    @Bean
    public NewTopic metricsTopic() {
        NewTopic t = TopicBuilder.name(metricsTopic).partitions(metricsPartitions).replicas(replicationFactor).build();
        log.info("[spring-watch: Topic声明 - {} (partitions={}, replicas={})]", t.name(), metricsPartitions, replicationFactor);
        return t;
    }

    @Bean
    public NewTopic logsTopic() {
        NewTopic t = TopicBuilder.name(logsTopic).partitions(logsPartitions).replicas(replicationFactor).build();
        log.info("[spring-watch: Topic声明 - {} (partitions={}, replicas={})]", t.name(), logsPartitions, replicationFactor);
        return t;
    }

    @Bean
    public NewTopic heartbeatTopic() {
        NewTopic t = TopicBuilder.name(heartbeatTopic).partitions(heartbeatPartitions).replicas(replicationFactor).build();
        log.info("[spring-watch: Topic声明 - {} (partitions={}, replicas={})]", t.name(), heartbeatPartitions, replicationFactor);
        return t;
    }

    @Bean
    public NewTopic metricsDlqTopic() {
        NewTopic t = TopicBuilder.name(metricsTopic + ".DLQ").partitions(dlqPartitions).replicas(replicationFactor).build();
        log.info("[spring-watch: DLQ声明 - {} (partitions={}, replicas={})]", t.name(), dlqPartitions, replicationFactor);
        return t;
    }

    @Bean
    public NewTopic logsDlqTopic() {
        NewTopic t = TopicBuilder.name(logsTopic + ".DLQ").partitions(dlqPartitions).replicas(replicationFactor).build();
        log.info("[spring-watch: DLQ声明 - {} (partitions={}, replicas={})]", t.name(), dlqPartitions, replicationFactor);
        return t;
    }

    @Bean
    public NewTopic heartbeatDlqTopic() {
        NewTopic t = TopicBuilder.name(heartbeatTopic + ".DLQ").partitions(1).replicas(replicationFactor).build();
        log.info("[spring-watch: DLQ声明 - {} (partitions=1, replicas={})]", t.name(), replicationFactor);
        return t;
    }
}
