package com.springwatch.ingest;

import com.springwatch.model.event.LogEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

@Slf4j
@Component
public class LogSanitizer {

    private static final Pattern PHONE = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");
    private static final Pattern ID_CARD = Pattern.compile("(?<!\\d)\\d{17}[\\dXx](?!\\d)");
    private static final Pattern BANK_CARD = Pattern.compile("(?<!\\d)\\d{16,19}(?!\\d)");
    private static final Pattern EMAIL = Pattern.compile(
            "\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b");
    private static final Pattern PASSWORD_KV = Pattern.compile(
            "(password|passwd|pwd|secret|token)([\"']?\\s*[:=]\\s*[\"']?)([^\\s,;'\"}]+)",
            Pattern.CASE_INSENSITIVE);

    /**
     * kxj: 敏感脱敏-手机号/身份证/银行卡/邮箱/密码键值 → 占位符
     */
    public void mask(LogEvent event) {
        if (event == null) {
            return;
        }
        event.setMessage(mask(event.getMessage()));
        event.setThrowable(mask(event.getThrowable()));
    }

    private String mask(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        s = ID_CARD.matcher(s).replaceAll("<ID>");
        s = BANK_CARD.matcher(s).replaceAll("<CARD>");
        s = PHONE.matcher(s).replaceAll("<PHONE>");
        s = EMAIL.matcher(s).replaceAll("<EMAIL>");
        s = PASSWORD_KV.matcher(s).replaceAll("$1$2<SECRET>");
        return s;
    }
}
