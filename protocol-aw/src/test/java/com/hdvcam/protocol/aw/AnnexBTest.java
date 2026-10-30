package com.hdvcam.protocol.aw;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class AnnexBTest {
    @Test public void acceptsThreeByteStartCodeWithOnlyNalHeader() {
        assertTrue(AnnexB.containsPlausibleNal(new byte[]{0, 0, 1, 0x67}));
    }

    @Test public void acceptsFourByteStartCode() {
        assertTrue(AnnexB.containsPlausibleNal(new byte[]{0, 0, 0, 1, 0x65, 0x11}));
    }

    @Test public void rejectsMissingStartCodeAndInvalidNalTypes() {
        assertFalse(AnnexB.containsPlausibleNal(new byte[]{0, 0, 1}));
        assertFalse(AnnexB.containsPlausibleNal(new byte[]{0, 0, 1, 0}));
        assertFalse(AnnexB.containsPlausibleNal(new byte[]{0, 0, 1, 0x78}));
        assertFalse(AnnexB.containsPlausibleNal(null));
    }
}
