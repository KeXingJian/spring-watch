package com.springwatch.agent.sql;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SqlDigestTest {

    @Test
    void literalNumbersNormalized() {
        assertEquals("SELECT * FROM ORDERS WHERE ID = ?",
                SqlDigest.digest("SELECT * FROM orders WHERE id = 42"));
    }

    @Test
    void stringLiteralsNormalized() {
        assertEquals("SELECT * FROM USERS WHERE NAME = ?",
                SqlDigest.digest("SELECT * FROM users WHERE name = 'alice'"));
    }

    @Test
    void keywordUppercase() {
        assertEquals("SELECT * FROM X WHERE Y = ?",
                SqlDigest.digest("select * from x where y = '1'"));
    }

    @Test
    void multiplePlaceholders() {
        assertEquals("INSERT INTO T VALUES (?, ?, ?)",
                SqlDigest.digest("INSERT INTO t VALUES (1, 'a', 99)"));
    }

    @Test
    void nullAndEmpty() {
        assertNull(SqlDigest.digest(null));
        assertNull(SqlDigest.digest(""));
        assertNull(SqlDigest.digest("   "));
    }

    @Test
    void whitespaceSeparatedNumbersEachBecomePlaceholder() {
        assertEquals("SELECT ? ? ?",
                SqlDigest.digest("SELECT 12 34 56"));
    }

    @Test
    void quotedStringWithSpacesKeptAsPlaceholder() {
        assertEquals("SELECT * FROM T WHERE A = ? AND B = ?",
                SqlDigest.digest("select * from t where a = 'hello world' and b = 7"));
    }
}
