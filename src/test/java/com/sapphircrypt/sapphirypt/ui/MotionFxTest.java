package com.sapphircrypt.sapphirypt.ui;

import javafx.animation.Interpolator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MotionFxTest {

    @Test
    void easeOutExpoEndpointsAndFastCurve() {
        Interpolator curve = MotionFx.EASE_OUT_EXPO;
        assertEquals(0.0, curve.interpolate(0.0, 1.0, 0.0), 1e-6);
        assertEquals(1.0, curve.interpolate(0.0, 1.0, 1.0), 1e-6);

        double prev = 0.0;
        for (int i = 1; i <= 20; i++) {
            double value = curve.interpolate(0.0, 1.0, i / 20.0);
            assertTrue(value >= prev, "EASE_OUT_EXPO should stay monotonically non-decreasing");
            prev = value;
        }
        assertTrue(curve.interpolate(0.0, 1.0, 0.45) > 0.75,
                "EASE_OUT_EXPO should reach >75% before midpoint");
        assertEquals(10.0, curve.interpolate(10.0, 110.0, 0.0), 1e-6);
        assertEquals(110.0, curve.interpolate(10.0, 110.0, 1.0), 1e-6);
    }

    @Test
    void easeInOutEndpointsAndMonotonicity() {
        Interpolator curve = MotionFx.EASE_IN_OUT;
        assertEquals(0.0, curve.interpolate(0.0, 1.0, 0.0), 1e-6);
        assertEquals(1.0, curve.interpolate(0.0, 1.0, 1.0), 1e-6);

        double prev = 0.0;
        for (int i = 1; i <= 20; i++) {
            double value = curve.interpolate(0.0, 1.0, i / 20.0);
            assertTrue(value >= prev, "EASE_IN_OUT should stay monotonically non-decreasing");
            prev = value;
        }
    }

    @Test
    void easeOutBackEndpointsAndControlledOvershoot() {
        Interpolator curve = MotionFx.EASE_OUT_BACK;
        assertEquals(0.0, curve.interpolate(0.0, 1.0, 0.0), 1e-6);
        assertEquals(1.0, curve.interpolate(0.0, 1.0, 1.0), 1e-6);

        double peak = 0.0;
        for (int i = 1; i < 20; i++) {
            double value = curve.interpolate(0.0, 1.0, i / 20.0);
            peak = Math.max(peak, value);
        }
        assertTrue(peak > 1.0 && peak < 1.15,
                "EASE_OUT_BACK should produce subtle spring overshoot near 1.045, got " + peak);
    }
}
