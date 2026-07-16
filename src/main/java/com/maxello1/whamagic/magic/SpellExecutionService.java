package com.maxello1.whamagic.magic;

import com.maxello1.whamagic.config.WhaServerConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Map;

/** Executes the current bounded elemental prototypes from compiled spell parameters. */
public final class SpellExecutionService {
    static final double MAX_TARGET_DISTANCE = 12.0;
    static final double MAX_ENTITY_QUERY_RADIUS = 8.0;
    static final int MAX_ENTITIES_PER_CAST = 128;
    static final int MAX_PARTICLES_PER_EFFECT = 400;
    static final int MAX_EFFECT_DURATION_TICKS = 400;
    static final int MAX_EFFECT_AMPLIFIER = 3;
    static final int MAX_IGNITION_SECONDS = 20;
    static final int MAX_EARTH_RADIUS = 4;
    static final int MAX_EARTH_COLUMN_HEIGHT = 8;

    private static final Map<ElementType, ElementEffect> EFFECTS = Map.of(
            ElementType.WATER, SpellExecutionService::executeWater,
            ElementType.FIRE, SpellExecutionService::executeFire,
            ElementType.WIND, SpellExecutionService::executeWind,
            ElementType.EARTH, SpellExecutionService::executeEarth);

    private SpellExecutionService() {}

    record WaterPlan(
            double targetDistance,
            double queryRadius,
            int columnHeight,
            int splashParticlesPerLayer,
            int bubbleParticlesPerLayer,
            int cloudParticlesPerLayer,
            int levitationTicks,
            int waterBreathingTicks) {}

    record FirePlan(
            double targetDistance,
            double queryRadius,
            int flameParticles,
            int smokeParticles,
            int ignitionSeconds) {}

    record WindPlan(
            double targetDistance,
            double queryRadius,
            int cloudParticles,
            double convergenceForce,
            double pushForce,
            int levitationTicks,
            int levitationAmplifier) {}

    record EarthPlan(
            double targetDistance,
            int radius,
            int columnHeight,
            int particlesPerBlock,
            int maxBlocks) {}

    public static void execute(Level level, Player player, SpellIr spell) {
        if (!(level instanceof ServerLevel serverLevel) || spell == null) return;

        List<ElementType> elements = spell.elements();
        ElementType primaryElement = elements.isEmpty() ? null : elements.getFirst();
        ManifestationModifier modifier = ManifestationModifier.NONE;
        for (SignSemantic semantic : spell.signSemantics()) {
            ManifestationModifier candidate =
                    ManifestationModifier.fromString(semantic.manifestation());
            if (candidate != ManifestationModifier.NONE) modifier = candidate;
        }

        if (elements.size() > 1) {
            serverLevel.sendParticles(
                    ParticleTypes.ENCHANT,
                    player.getX(), player.getY() + 1, player.getZ(),
                    50, 0.5, 0.5, 0.5, 0.1);
            serverLevel.sendParticles(
                    ParticleTypes.SPLASH,
                    player.getX(), player.getY() + 1, player.getZ(),
                    50, 0.5, 0.5, 0.5, 0.1);
            serverLevel.sendParticles(
                    ParticleTypes.CLOUD,
                    player.getX(), player.getY() + 1, player.getZ(),
                    20, 0.5, 0.5, 0.5, 0.1);
            return;
        }

        ElementEffect effect = primaryElement == null ? null : EFFECTS.get(primaryElement);
        if (effect != null) {
            effect.apply(serverLevel, player, spell.parameters(), modifier);
        } else {
            serverLevel.sendParticles(
                    ParticleTypes.ENCHANT,
                    player.getX(), player.getY() + 1, player.getZ(),
                    50, 0.5, 0.5, 0.5, 0.1);
        }
    }

    static WaterPlan waterPlan(SpellParameters parameters) {
        SpellParameters safe = safeParameters(parameters);
        int height = clampInt(
                Math.round(5.0 * (0.55 * safe.sizeScale()
                        + 0.45 * safe.powerMultiplier())),
                2, 15);
        int splash = Math.min(20, Math.max(2, 240 / height));
        int bubbles = Math.min(15, Math.max(1, 120 / height));
        int clouds = Math.min(2, Math.max(1, 30 / height));
        return new WaterPlan(
                targetDistance(safe),
                clamp(1.5 * safe.radiusMultiplier(), 0.75, 6.0),
                height,
                splash,
                bubbles,
                clouds,
                scaledTicks(60, safe.durationMultiplier(), 20),
                scaledTicks(100, safe.durationMultiplier(), 20));
    }

    static FirePlan firePlan(SpellParameters parameters) {
        SpellParameters safe = safeParameters(parameters);
        int flames = clampInt(
                Math.round(50.0 * safe.powerMultiplier()),
                10, 300);
        int smoke = clampInt(
                Math.round(15.0 * Math.sqrt(safe.radiusMultiplier())),
                5, MAX_PARTICLES_PER_EFFECT - flames);
        return new FirePlan(
                targetDistance(safe),
                clamp(1.5 * safe.radiusMultiplier(), 0.5, 6.0),
                flames,
                smoke,
                clampInt(Math.round(5.0 * safe.durationMultiplier()),
                        1, MAX_IGNITION_SECONDS));
    }

    static WindPlan windPlan(SpellParameters parameters) {
        SpellParameters safe = safeParameters(parameters);
        return new WindPlan(
                targetDistance(safe),
                clamp(2.0 * safe.radiusMultiplier(), 0.75, MAX_ENTITY_QUERY_RADIUS),
                clampInt(Math.round(40.0 * safe.powerMultiplier()),
                        10, MAX_PARTICLES_PER_EFFECT),
                clamp(0.5 * safe.forceMultiplier(), 0.05, 2.0),
                clamp(1.5 * safe.forceMultiplier(), 0.10, 4.0),
                scaledTicks(40, safe.durationMultiplier(), 10),
                clampInt(Math.round(safe.forceMultiplier()),
                        0, MAX_EFFECT_AMPLIFIER));
    }

    static EarthPlan earthPlan(SpellParameters parameters) {
        return earthPlan(
                parameters,
                WhaServerConfig.INSTANCE.earthMagic.maxBlocksPerCast);
    }

    static EarthPlan earthPlan(
            SpellParameters parameters,
            int configuredMaxBlocks) {
        SpellParameters safe = safeParameters(parameters);
        int radius = clampInt(
                Math.round(Math.max(safe.sizeScale(), safe.radiusMultiplier())),
                1, MAX_EARTH_RADIUS);
        int height = clampInt(
                Math.round(2.0 * safe.powerMultiplier()),
                1, MAX_EARTH_COLUMN_HEIGHT);
        return new EarthPlan(
                targetDistance(safe),
                radius,
                height,
                5,
                earthBlockLimit(configuredMaxBlocks));
    }

    private static void executeWater(
            ServerLevel level,
            Player player,
            SpellParameters parameters,
            ManifestationModifier modifier) {
        WaterPlan plan = waterPlan(parameters);
        BlockPos targetPos = targetPos(player, plan.targetDistance(), 1.0);

        for (int index = 0; index < plan.columnHeight(); index++) {
            BlockPos position = targetPos.above(index);
            level.sendParticles(
                    ParticleTypes.SPLASH,
                    position.getX() + 0.5, position.getY() + 0.5, position.getZ() + 0.5,
                    plan.splashParticlesPerLayer(), 0.3, 0.3, 0.3, 0.1);
            level.sendParticles(
                    ParticleTypes.BUBBLE_COLUMN_UP,
                    position.getX() + 0.5, position.getY() + 0.5, position.getZ() + 0.5,
                    plan.bubbleParticlesPerLayer(), 0.3, 0.3, 0.3, 0.1);
            level.sendParticles(
                    ParticleTypes.CLOUD,
                    position.getX() + 0.5, position.getY() + 0.5, position.getZ() + 0.5,
                    plan.cloudParticlesPerLayer(), 0.2, 0.2, 0.2, 0.05);
        }

        AABB columnBox = new AABB(targetPos)
                .expandTowards(0, plan.columnHeight(), 0)
                .inflate(plan.queryRadius());
        List<LivingEntity> entities = level.getEntitiesOfClass(
                LivingEntity.class, columnBox);
        int processed = 0;
        for (LivingEntity entity : entities) {
            if (processed++ >= MAX_ENTITIES_PER_CAST) break;
            if (modifier == ManifestationModifier.LEVITATION
                    || modifier == ManifestationModifier.COLUMN) {
                entity.addEffect(new MobEffectInstance(
                        MobEffects.LEVITATION, plan.levitationTicks(), 2));
            }
            entity.addEffect(new MobEffectInstance(
                    MobEffects.WATER_BREATHING, plan.waterBreathingTicks(), 0));
        }
    }

    private static void executeFire(
            ServerLevel level,
            Player player,
            SpellParameters parameters,
            ManifestationModifier modifier) {
        FirePlan plan = firePlan(parameters);
        BlockPos targetPos = targetPos(player, plan.targetDistance(), 1.0);

        level.sendParticles(
                ParticleTypes.FLAME,
                targetPos.getX() + 0.5, targetPos.getY() + 0.5, targetPos.getZ() + 0.5,
                plan.flameParticles(), 0.5, 0.5, 0.5, 0.1);
        level.sendParticles(
                ParticleTypes.CAMPFIRE_COSY_SMOKE,
                targetPos.getX() + 0.5, targetPos.getY() + 0.5, targetPos.getZ() + 0.5,
                plan.smokeParticles(), 0.5, 0.5, 0.5, 0.05);

        List<LivingEntity> entities = level.getEntitiesOfClass(
                LivingEntity.class,
                new AABB(targetPos).inflate(plan.queryRadius()));
        int processed = 0;
        for (LivingEntity entity : entities) {
            if (processed++ >= MAX_ENTITIES_PER_CAST) break;
            entity.igniteForSeconds(plan.ignitionSeconds());
        }
    }

    private static void executeWind(
            ServerLevel level,
            Player player,
            SpellParameters parameters,
            ManifestationModifier modifier) {
        WindPlan plan = windPlan(parameters);
        BlockPos targetPos = targetPos(player, plan.targetDistance(), 1.0);

        level.sendParticles(
                ParticleTypes.CLOUD,
                targetPos.getX() + 0.5, targetPos.getY() + 0.5, targetPos.getZ() + 0.5,
                plan.cloudParticles(), 1.0, 1.0, 1.0, 0.2);

        List<LivingEntity> entities = level.getEntitiesOfClass(
                LivingEntity.class,
                new AABB(targetPos).inflate(plan.queryRadius()));
        int processed = 0;
        for (LivingEntity entity : entities) {
            if (processed++ >= MAX_ENTITIES_PER_CAST) break;
            if (modifier == ManifestationModifier.LEVITATION) {
                entity.addEffect(new MobEffectInstance(
                        MobEffects.LEVITATION,
                        plan.levitationTicks(),
                        plan.levitationAmplifier()));
            } else if (modifier == ManifestationModifier.CONVERGENCE) {
                Vec3 direction = new Vec3(
                        targetPos.getX() + 0.5 - entity.getX(),
                        targetPos.getY() + 0.5 - entity.getY(),
                        targetPos.getZ() + 0.5 - entity.getZ()).normalize();
                entity.setDeltaMovement(entity.getDeltaMovement().add(
                        direction.scale(plan.convergenceForce())));
            } else {
                Vec3 direction = new Vec3(
                        entity.getX() - player.getX(),
                        entity.getY() - player.getY(),
                        entity.getZ() - player.getZ()).normalize();
                entity.setDeltaMovement(entity.getDeltaMovement().add(
                        direction.scale(plan.pushForce())));
            }
        }
    }

    private static void executeEarth(
            ServerLevel level,
            Player player,
            SpellParameters parameters,
            ManifestationModifier modifier) {
        EarthPlan plan = earthPlan(parameters);
        BlockPos targetPos = targetPos(player, plan.targetDistance(), 0.0);
        int blocksChanged = 0;
        int maxBlocks = plan.maxBlocks();
        boolean allowPermanent =
                WhaServerConfig.INSTANCE.earthMagic.allowPermanentBlockChanges;
        int particlesSent = 0;

        earthArea:
        for (int x = -plan.radius(); x <= plan.radius(); x++) {
            for (int z = -plan.radius(); z <= plan.radius(); z++) {
                if (blocksChanged >= maxBlocks) break earthArea;

                BlockPos position = targetPos.offset(x, 0, z);
                BlockState state = level.getBlockState(position);
                if (state.isAir() || state.canBeReplaced()) {
                    if (allowPermanent) {
                        level.setBlockAndUpdate(
                                position, Blocks.DIRT.defaultBlockState());
                    }
                    int particleCount = Math.min(
                            plan.particlesPerBlock(),
                            MAX_PARTICLES_PER_EFFECT - particlesSent);
                    if (particleCount > 0) {
                        level.sendParticles(
                                new BlockParticleOption(
                                        ParticleTypes.BLOCK,
                                        Blocks.DIRT.defaultBlockState()),
                                position.getX() + 0.5,
                                position.getY() + 1.0,
                                position.getZ() + 0.5,
                                particleCount,
                                0.3, 0.3, 0.3, 0.05);
                        particlesSent += particleCount;
                    }
                    blocksChanged++;
                }

                if (modifier == ManifestationModifier.COLUMN
                        && blocksChanged < maxBlocks) {
                    for (int y = 1; y <= plan.columnHeight(); y++) {
                        if (blocksChanged >= maxBlocks) break;
                        BlockPos columnPosition = position.above(y);
                        BlockState columnState = level.getBlockState(columnPosition);
                        if (columnState.isAir() || columnState.canBeReplaced()) {
                            if (allowPermanent) {
                                level.setBlockAndUpdate(
                                        columnPosition,
                                        Blocks.DIRT.defaultBlockState());
                            }
                            blocksChanged++;
                        }
                    }
                }
            }
        }
    }

    private static BlockPos targetPos(
            Player player,
            double targetDistance,
            double verticalOffset) {
        Vec3 look = player.getLookAngle();
        return BlockPos.containing(
                player.getX() + look.x * targetDistance,
                player.getY() + verticalOffset,
                player.getZ() + look.z * targetDistance);
    }

    private static SpellParameters safeParameters(SpellParameters parameters) {
        return parameters == null ? SpellParameters.NEUTRAL : parameters;
    }

    static int earthBlockLimit(int configuredLimit) {
        return clampInt(configuredLimit, 1, 256);
    }

    private static double targetDistance(SpellParameters parameters) {
        return clamp(3.0 * parameters.rangeMultiplier(), 1.0, MAX_TARGET_DISTANCE);
    }

    private static int scaledTicks(
            int baseline,
            double multiplier,
            int minimum) {
        return clampInt(
                Math.round(baseline * multiplier),
                minimum,
                MAX_EFFECT_DURATION_TICKS);
    }

    private static int clampInt(long value, int minimum, int maximum) {
        return (int) Math.max(minimum, Math.min(maximum, value));
    }

    private static double clamp(double value, double minimum, double maximum) {
        if (!Double.isFinite(value)) return minimum;
        return Math.max(minimum, Math.min(maximum, value));
    }
}
