package com.springwatch.agent.metric;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LabelsTest {

    @Test
    void equalsHashCodeConsistent() {
        Labels a = Labels.of("k1", "v1", "k2", "v2");
        Labels b = Labels.of("k1", "v1", "k2", "v2");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void differentValuesNotEqual() {
        Labels a = Labels.of("k", "v1");
        Labels b = Labels.of("k", "v2");
        assertNotEquals(a, b);
    }

    @Test
    void compareToOrdersByNameThenValue() {
        Labels a = Labels.of("a", "1");
        Labels b = Labels.of("a", "2");
        Labels c = Labels.of("b", "0");
        assertTrue(a.compareTo(b) < 0);
        assertTrue(b.compareTo(c) < 0);
        assertEquals(0, a.compareTo(Labels.of("a", "1")));
    }

    @Test
    void renderFormatsKeysValues() {
        Labels l = Labels.of("method", "UserService#get", "code", "200");
        assertEquals("method=\"UserService#get\",code=\"200\"", l.render());
    }

    @Test
    void emptyRendersEmpty() {
        assertEquals("", Labels.EMPTY.render());
    }

    @Test
    void getReturnsValueForKnownKey() {
        Labels l = Labels.of("a", "1", "b", "2");
        assertEquals("1", l.get("a"));
        assertEquals("2", l.get("b"));
        assertNull(l.get("missing"));
    }

    @Test
    void mismatchedArraysRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new Labels(new String[]{"a"}, new String[]{"1", "2"}));
    }

    @Test
    void sizeReflectsLabelCount() {
        assertEquals(0, Labels.EMPTY.size());
        assertEquals(2, Labels.of("a", "1", "b", "2").size());
    }
}
