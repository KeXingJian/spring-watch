package com.springwatch.ingest;

import com.springwatch.model.event.LogEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

@Slf4j
@Component
public class LogFingerprinter {

    private static final Pattern NUMBER = Pattern.compile("\\b\\d+\\b");
    private static final Pattern UUID = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern TIMESTAMP = Pattern.compile(
            "\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}([.,]\\d+)?(Z|[+-]\\d{2}:?\\d{2})?");
    private static final Pattern HEX_LONG = Pattern.compile("\\b0x[0-9a-fA-F]+\\b");

    private static final int PATTERN_MAX_LEN = 200;

    /**
     * kxj: 指纹生成-message+throwable首行归一化后SHA-1
     * 归一化: 时间戳/UUID/16进制/数字 → 占位符,使同模式日志hash稳定
     */
    public String fingerprint(LogEvent event) {
        if (event == null) {
            return null;
        }
        StringBuilder raw = new StringBuilder();
        if (event.getLevel() != null) {
            raw.append(event.getLevel()).append('|');
        }
        if (event.getLogger() != null) {
            raw.append(event.getLogger()).append('|');
        }
        if (event.getMessage() != null) {
            raw.append(event.getMessage()).append('\n');
        }
        if (event.getThrowable() != null) {
            int newline = event.getThrowable().indexOf('\n');
            raw.append(newline > 0 ? event.getThrowable().substring(0, newline) : event.getThrowable());
        }
        String normalized = normalize(raw.toString());
        return sha1Hex(normalized);
    }

    /**
     * kxj: 模式名-保留原始message+异常首行截断,供前端展示
     */
    public String patternName(LogEvent event) {
        if (event == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        if (event.getMessage() != null) {
            sb.append(event.getMessage());
        }
        if (event.getThrowable() != null) {
            int nl = event.getThrowable().indexOf('\n');
            String first = nl > 0 ? event.getThrowable().substring(0, nl) : event.getThrowable();
            if (sb.length() > 0) {
                sb.append(" | ");
            }
            sb.append(first);
        }
        String s = sb.toString();
        return s.length() > PATTERN_MAX_LEN ? s.substring(0, PATTERN_MAX_LEN) + "..." : s;
    }

    private String normalize(String s) {
        s = TIMESTAMP.matcher(s).replaceAll("<TS>");
        s = UUID.matcher(s).replaceAll("<UUID>");
        s = HEX_LONG.matcher(s).replaceAll("<HEX>");
        s = NUMBER.matcher(s).replaceAll("<N>");
        return s;
    }

    private String sha1Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] bytes = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            log.warn("[spring-watch: LogFingerprinter SHA-1 不可用 - error={}]", e.getMessage());
            return Integer.toHexString(input.hashCode());
        }
    }
}
