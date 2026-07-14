package com.springwatch.inflight;

public record InflightRecord(
    String topic,
    int partitionId,
    String key,
    Object payload,
    long timestampMs,
    long offset
) {
    public InflightRecord {
        if (topic == null || topic.isEmpty()) {
            throw new IllegalArgumentException("topic must not be empty");
        }
        if (partitionId < 0) {
            throw new IllegalArgumentException("partitionId must be >= 0");
        }
        if (payload == null) {
            throw new IllegalArgumentException("payload must not be null");
        }
    }
}
