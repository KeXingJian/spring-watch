package com.springwatch.agent.metric;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 业务标签的不可变 key-value 视图。
 * <p>
 * 标签顺序在 compareTo/equals/hashCode 中一致,作为 ConcurrentHashMap 的 key
 * 查找 O(1)。
 */
public final class Labels implements Comparable<Labels> {

    public static final Labels EMPTY = new Labels(new String[0], new String[0]);

    private final String[] names;
    private final String[] values;
    private final int hash;

    public Labels(String[] names, String[] values) {
        if (names == null || values == null || names.length != values.length) {
            throw new IllegalArgumentException("names and values must be non-null and same length");
        }
        this.names = names;
        this.values = values;
        this.hash = Objects.hash((Object[]) names) ^ Objects.hash((Object[]) values);
    }

    public static Labels of(String name, String value) {
        return new Labels(new String[]{name}, new String[]{value});
    }

    public static Labels of(String n1, String v1, String n2, String v2) {
        return new Labels(new String[]{n1, n2}, new String[]{v1, v2});
    }

    public static Labels of(String n1, String v1, String n2, String v2, String n3, String v3) {
        return new Labels(new String[]{n1, n2, n3}, new String[]{v1, v2, v3});
    }

    public static Labels of(String[] names, String[] values) {
        return new Labels(names, values);
    }

    public static Labels empty() {
        return EMPTY;
    }

    public List<String> names() {
        return Collections.unmodifiableList(Arrays.asList(names));
    }

    public List<String> values() {
        return Collections.unmodifiableList(Arrays.asList(values));
    }

    public String get(String name) {
        for (int i = 0; i < names.length; i++) {
            if (names[i].equals(name)) return values[i];
        }
        return null;
    }

    public int size() {
        return names.length;
    }

    @Override
    public int hashCode() {
        return hash;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Labels)) return false;
        Labels that = (Labels) o;
        if (hash != that.hash) return false;
        return Arrays.equals(names, that.names) && Arrays.equals(values, that.values);
    }

    @Override
    public int compareTo(Labels o) {
        int n = Math.min(names.length, o.names.length);
        for (int i = 0; i < n; i++) {
            int c = names[i].compareTo(o.names[i]);
            if (c != 0) return c;
            c = values[i].compareTo(o.values[i]);
            if (c != 0) return c;
        }
        return Integer.compare(names.length, o.names.length);
    }

    public String render() {
        if (names.length == 0) return "";
        StringBuilder sb = new StringBuilder(64);
        for (int i = 0; i < names.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(names[i]).append('=').append(escape(values[i]));
        }
        return sb.toString();
    }

    private static String escape(String v) {
        if (v == null) return "";
        StringBuilder sb = new StringBuilder(v.length() + 2);
        sb.append('"');
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c == '"' || c == '\\') sb.append('\\');
            if (c == '\n') sb.append("\\n");
            else if (c == '\\') sb.append("\\\\");
            else sb.append(c);
        }
        sb.append('"');
        return sb.toString();
    }
}
