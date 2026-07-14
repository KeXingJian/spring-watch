package com.springwatch.inflight;

import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Slf4j
public record Partition(String topic, int partitionId, InflightBuffer buffer) {

    public boolean offer(Object payload) {
        return buffer.offer(payload);
    }

    public List<Object> drain(int max) {
        return buffer.drain(max);
    }

    public int pending() {
        return buffer.size();
    }

    public int capacity() {
        return buffer.capacity();
    }

}
