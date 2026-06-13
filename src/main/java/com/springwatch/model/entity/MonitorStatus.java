package com.springwatch.model.entity;

public final class MonitorStatus {

    public static final String ACTIVE = "active";
    public static final String INACTIVE = "inactive";
    public static final String PAUSED = "paused";

    private MonitorStatus() {
    }

    public static boolean isPaused(String status) {
        return PAUSED.equals(status);
    }

    public static boolean isInactive(String status) {
        return INACTIVE.equals(status);
    }

    public static boolean isActive(String status) {
        return ACTIVE.equals(status);
    }
}
