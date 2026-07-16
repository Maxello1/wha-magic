package com.maxello1.whamagic.magic;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpellExecutionServiceTest {
    @Test
    void fireRadiusAndDurationRespondToParameters() {
        SpellExecutionService.FirePlan baseline =
                SpellExecutionService.firePlan(parameters(1, 1, 1, 1, 1, 1));
        SpellExecutionService.FirePlan scaled =
                SpellExecutionService.firePlan(parameters(1, 1, 2, 2, 1, 1));

        assertTrue(scaled.queryRadius() > baseline.queryRadius());
        assertTrue(scaled.ignitionSeconds() > baseline.ignitionSeconds());
    }

    @Test
    void windForceRespondsToForceMultiplier() {
        SpellExecutionService.WindPlan baseline =
                SpellExecutionService.windPlan(parameters(1, 1, 1, 1, 1, 1));
        SpellExecutionService.WindPlan forceful =
                SpellExecutionService.windPlan(parameters(1, 1, 1, 1, 1, 2));

        assertTrue(forceful.pushForce() > baseline.pushForce());
        assertTrue(forceful.convergenceForce() > baseline.convergenceForce());
    }

    @Test
    void waterRangeAndHeightRespondToParameters() {
        SpellExecutionService.WaterPlan baseline =
                SpellExecutionService.waterPlan(parameters(1, 1, 1, 1, 1, 1));
        SpellExecutionService.WaterPlan scaled =
                SpellExecutionService.waterPlan(parameters(2, 2, 1, 1, 2, 1));

        assertTrue(scaled.targetDistance() > baseline.targetDistance());
        assertTrue(scaled.columnHeight() > baseline.columnHeight());
    }

    @Test
    void earthAreaAndBlockLimitStayBounded() {
        SpellExecutionService.EarthPlan extreme =
                SpellExecutionService.earthPlan(
                        parameters(100, 100, 100, 100, 100, 100),
                        48);

        assertEquals(SpellExecutionService.MAX_EARTH_RADIUS, extreme.radius());
        assertEquals(SpellExecutionService.MAX_EARTH_COLUMN_HEIGHT, extreme.columnHeight());
        assertEquals(48, extreme.maxBlocks());
        assertEquals(256, SpellExecutionService.earthPlan(
                parameters(100, 100, 100, 100, 100, 100),
                50_000).maxBlocks());
        assertEquals(1, SpellExecutionService.earthBlockLimit(-20));
        assertEquals(48, SpellExecutionService.earthBlockLimit(48));
        assertEquals(256, SpellExecutionService.earthBlockLimit(50_000));
    }

    @Test
    void particlesQueriesAndEffectsCannotBypassCaps() {
        SpellParameters extreme = parameters(100, 100, 100, 100, 100, 100);
        SpellExecutionService.WaterPlan water = SpellExecutionService.waterPlan(extreme);
        SpellExecutionService.FirePlan fire = SpellExecutionService.firePlan(extreme);
        SpellExecutionService.WindPlan wind = SpellExecutionService.windPlan(extreme);

        int waterParticles = water.columnHeight()
                * (water.splashParticlesPerLayer()
                + water.bubbleParticlesPerLayer()
                + water.cloudParticlesPerLayer());
        assertTrue(waterParticles <= SpellExecutionService.MAX_PARTICLES_PER_EFFECT);
        assertTrue(fire.flameParticles() + fire.smokeParticles()
                <= SpellExecutionService.MAX_PARTICLES_PER_EFFECT);
        assertTrue(wind.cloudParticles()
                <= SpellExecutionService.MAX_PARTICLES_PER_EFFECT);
        assertTrue(water.queryRadius() <= SpellExecutionService.MAX_ENTITY_QUERY_RADIUS);
        assertTrue(fire.queryRadius() <= SpellExecutionService.MAX_ENTITY_QUERY_RADIUS);
        assertTrue(wind.queryRadius() <= SpellExecutionService.MAX_ENTITY_QUERY_RADIUS);
        assertTrue(water.levitationTicks()
                <= SpellExecutionService.MAX_EFFECT_DURATION_TICKS);
        assertTrue(water.waterBreathingTicks()
                <= SpellExecutionService.MAX_EFFECT_DURATION_TICKS);
        assertTrue(wind.levitationTicks()
                <= SpellExecutionService.MAX_EFFECT_DURATION_TICKS);
        assertTrue(wind.levitationAmplifier()
                <= SpellExecutionService.MAX_EFFECT_AMPLIFIER);
        assertTrue(fire.ignitionSeconds()
                <= SpellExecutionService.MAX_IGNITION_SECONDS);
    }

    private static SpellParameters parameters(
            double size,
            double power,
            double radius,
            double duration,
            double range,
            double force) {
        return new SpellParameters(
                size,
                SealSizeTier.GRAND,
                1.0,
                power,
                range,
                radius,
                duration,
                1.0,
                force,
                1.0);
    }
}
