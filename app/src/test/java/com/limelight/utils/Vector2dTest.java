package com.limelight.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for the stick vector's magnitude, which every deadzone test compares against.
 *
 * <p>The maths moved from {@code Math.pow} to plain multiplies on the input path; these pin the
 * result to the exact values the old expression produced for the cases a stick actually hits.
 */
class Vector2dTest {

    @Test
    void magnitudeIsEuclideanLength() {
        Vector2d v = new Vector2d();
        v.initialize(3, 4);
        assertEquals(5.0, v.getMagnitude(), 0.0);
        assertEquals(3, v.getX(), 0.0f);
        assertEquals(4, v.getY(), 0.0f);
    }

    @Test
    void negativeComponentsDoNotAffectMagnitude() {
        Vector2d v = new Vector2d();
        v.initialize(-0.6f, -0.8f);
        assertEquals(1.0, v.getMagnitude(), 1e-7);
    }

    @Test
    void zeroVectorHasZeroMagnitude() {
        Vector2d v = new Vector2d();
        assertEquals(0.0, v.getMagnitude(), 0.0);
        v.initialize(1, 1);
        v.initialize(0, 0);
        assertEquals(0.0, v.getMagnitude(), 0.0);
    }

    @Test
    void scalarMultiplyScalesComponentsAndMagnitude() {
        Vector2d v = new Vector2d();
        v.initialize(6, 8);
        v.scalarMultiply(0.5);
        assertEquals(3, v.getX(), 0.0f);
        assertEquals(4, v.getY(), 0.0f);
        assertEquals(5.0, v.getMagnitude(), 0.0);
    }
}
