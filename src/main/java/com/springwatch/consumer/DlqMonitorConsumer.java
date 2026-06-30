package com.springwatch.consumer;

import com.springwatch.model.entity.DlqMessage;
import com.springwatch.repository.DlqMessageRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class DlqMonitorConsumer {

    private static final int STACKTRACE_MAX_BYTES = 8 * 1024;

    private final DlqMessageRepository dlqMessageRepository;
    private final MeterRegistry meterRegistry;

    private Counter persistedCounter;
    private Counter persistFailCounter;

    @PostConstruct
    void initMetrics() {
        this.persistedCounter = Counter.builder("spring.watch.consumer.dlq.persisted")
                .description("DLQ 消息落库成功条数")
                .register(meterRegistry);
        this.persistFailCounter = Counter.builder("spring.watch.consumer.dlq.persist_fail")
                .description("DLQ 消息落库失败条数")
                .register(meterRegistry);
    }

    @KafkaListener(
            topics = {"monitor-metrics.DLQ", "monitor-logs.DLQ", "monitor-heartbeat.DLQ"},
            groupId = "spring-watch-dlq-monitor",
            containerFactory = "batchFactory"
    )
    @Transactional
    public void onDlq(List<ConsumerRecord<String, String>> records) {
        // 空批次直接跳过
        if (records == null || records.isEmpty()) {
            return;
        }
        // 转换为本批落库实体
        List<DlqMessage> entities = new ArrayList<>(records.size());
        for (ConsumerRecord<String, String> rec : records) {
            entities.add(toEntity(rec));
        }
        try {
            // 批量入库
            dlqMessageRepository.saveAll(entities);
            persistedCounter.increment(entities.size());
            log.warn("[spring-watch: DLQ批处理 落库成功 - count={}, topics={}]",
                    entities.size(), summarizeTopics(records));
        } catch (Exception e) {
            // 落库失败不影响 Kafka 提交，避免毒消息阻塞消费；仅计数+告警
            persistFailCounter.increment(entities.size());
            log.error("[spring-watch: DLQ批处理 落库失败 - count={}, error={}]",
                    entities.size(), e.getMessage(), e);
        }
    }

    private DlqMessage toEntity(ConsumerRecord<String, String> rec) {
        // 优先从 DLT header 取原始时间戳，缺失时回退到当前记录的 timestamp
        Long origTsMillis = headerLong(rec, KafkaHeaders.DLT_ORIGINAL_TIMESTAMP,
                rec.timestamp() > 0 ? rec.timestamp() : null);
        return DlqMessage.builder()
                .sourceTopic(headerString(rec, KafkaHeaders.DLT_ORIGINAL_TOPIC, rec.topic()))
                .originalPartition(headerInt(rec, rec.partition()))
                .originalOffset(headerLong(rec, KafkaHeaders.DLT_ORIGINAL_OFFSET, rec.offset()))
                .originalTimestamp(origTsMillis != null ? Instant.ofEpochMilli(origTsMillis) : null)
                .payload(truncate(rec.value(), 64 * 1024)) // 消息体最大 64KB
                .key(rec.key() != null && rec.key().length() > 256 ? rec.key().substring(0, 256) : rec.key()) // key 截断 256
                .errorFqcn(headerString(rec, KafkaHeaders.DLT_EXCEPTION_FQCN, null))
                .errorCauseFqcn(headerString(rec, KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN, null))
                .errorMessage(headerString(rec, KafkaHeaders.DLT_EXCEPTION_MESSAGE, null))
                .errorStacktrace(truncate(headerString(rec, KafkaHeaders.DLT_EXCEPTION_STACKTRACE, null),
                        STACKTRACE_MAX_BYTES))
                .replayed(false) // 初始为未重放
                .createdAt(Instant.now())
                .build();
    }

    private static String headerString(ConsumerRecord<?, ?> rec, String name, String defaultValue) {
        Header h = rec.headers().lastHeader(name);
        if (h == null || h.value() == null) {
            return defaultValue;
        }
        return new String(h.value(), StandardCharsets.UTF_8);
    }

    private static Integer headerInt(ConsumerRecord<?, ?> rec, int defaultValue) {
        Header h = rec.headers().lastHeader(KafkaHeaders.DLT_ORIGINAL_PARTITION);
        if (h == null || h.value() == null) {
            // 负数视为未提供，避免污染库
            return defaultValue >= 0 ? defaultValue : null;
        }
        try {
            // Kafka header 数值通常以 4 字节大端二进制存储
            return java.nio.ByteBuffer.wrap(h.value()).getInt();
        } catch (Exception e) {
            try {
                // 兜底：按 UTF-8 文本解析
                return Integer.parseInt(new String(h.value(), StandardCharsets.UTF_8));
            } catch (Exception ex) {
                return defaultValue >= 0 ? defaultValue : null;
            }
        }
    }

    private static Long headerLong(ConsumerRecord<?, ?> rec, String name, Long defaultValue) {
        Header h = rec.headers().lastHeader(name);
        if (h == null || h.value() == null) {
            return defaultValue;
        }
        try {
            // Kafka header 数值通常以 8 字节大端二进制存储
            return java.nio.ByteBuffer.wrap(h.value()).getLong();
        } catch (Exception e) {
            try {
                // 兜底：按 UTF-8 文本解析
                return Long.parseLong(new String(h.value(), StandardCharsets.UTF_8));
            } catch (Exception ex) {
                return defaultValue;
            }
        }
    }

    private static String truncate(String s, int maxBytes) {
        if (s == null) {
            return null;
        }
        // 按 UTF-8 字节数截断，避免把多字节字符从中间劈开
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) {
            return s;
        }
        return new String(bytes, 0, maxBytes, StandardCharsets.UTF_8) + "...[truncated]";
    }

    private static String summarizeTopics(List<ConsumerRecord<String, String>> records) {
        // 汇总本批涉及的 topic，用于日志/告警观察
        return records.stream().map(ConsumerRecord::topic).distinct().toList().toString();
    }
}
