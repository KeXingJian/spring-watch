package com.springwatch.agent.sql;

/**
 * SQL digest 归一化工具(数字/字符串字面量 → ?,保留骨架 + 关键字大写)。
 * <p>
 * 借鉴 OTel {@code SqlQueryAnalyzer} 的"按字面量归一"思路,但不用 JFlex,
 * 极简实现,够 P1/P2 digest 限额使用。
 */
public final class SqlDigest {

    private SqlDigest() {
    }

    public static String digest(String sql) {
        if (sql == null || sql.isEmpty()) return null;
        StringBuilder sb = new StringBuilder(sql.length());
        boolean inString = false;
        boolean lastIsPlaceholder = false;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '\'') {
                inString = !inString;
                if (!inString) {
                    sb.append('?');
                    lastIsPlaceholder = true;
                } else {
                    lastIsPlaceholder = false;
                }
                continue;
            }
            if (inString) {
                continue;
            }
            if (Character.isDigit(c)) {
                if (!lastIsPlaceholder) {
                    sb.append('?');
                    lastIsPlaceholder = true;
                }
                continue;
            }
            lastIsPlaceholder = false;
            if (Character.isWhitespace(c)) {
                if (sb.length() > 0 && sb.charAt(sb.length() - 1) != ' ') {
                    sb.append(' ');
                }
                continue;
            }
            sb.append(Character.toUpperCase(c));
        }
        String d = sb.toString().trim();
        return d.isEmpty() ? null : d;
    }
}
