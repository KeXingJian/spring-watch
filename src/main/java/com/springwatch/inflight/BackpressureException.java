package com.springwatch.inflight;

public class BackpressureException extends RuntimeException {

    private final String topic;
    private final int partitionId;
    private final Reason reason;

    public BackpressureException(String topic, int partitionId, Reason reason, String message) {
        super(message);
        this.topic = topic;
        this.partitionId = partitionId;
        this.reason = reason;
    }

    public String getTopic() { return topic; }
    public int getPartitionId() { return partitionId; }
    public Reason getReason() { return reason; }

    public enum Reason {
        IN_FLIGHT_FULL
    }
}
