package com.maxello1.whamagic.magic;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.block.Blocks;

import java.util.List;

public class SpellExecutionService {
    public static void execute(Level level, Player player, SpellIr spell) {
        if (!(level instanceof ServerLevel serverLevel)) return;
        
        String element = spell.element() != null ? spell.element().toLowerCase() : "";
        
        String manifestation = "";
        double power = 1.0;
        
        if (spell.signSemantics() != null) {
            for (SignSemantic sem : spell.signSemantics()) {
                if (!"none".equals(sem.manifestation())) {
                    manifestation = sem.manifestation();
                }
            }
        }
        
        if (spell.signCounts() != null) {
            for (Integer count : spell.signCounts().values()) {
                if (count > 1) {
                    power = count;
                }
            }
        }
        
        if (element.equals("water")) {
            executeWaterColumn(serverLevel, player, power);
        } else if (element.equals("fire")) {
            executeFire(serverLevel, player, power);
        } else if (element.equals("wind")) {
            executeWind(serverLevel, player, power, manifestation);
        } else if (element.equals("earth")) {
            executeEarth(serverLevel, player, power, manifestation);
        } else {
            // Generic fallback execution
            serverLevel.sendParticles(ParticleTypes.ENCHANT, player.getX(), player.getY() + 1, player.getZ(), 50, 0.5, 0.5, 0.5, 0.1);
        }
    }
    
    private static void executeWaterColumn(ServerLevel level, Player player, double power) {
        Vec3 look = player.getLookAngle();
        BlockPos targetPos = BlockPos.containing(player.getX() + look.x * 3, player.getY() + 1, player.getZ() + look.z * 3);
        
        int height = (int) Math.round(5 * power);
        if (height < 2) height = 2;
        if (height > 15) height = 15;
        
        // Spawn particles
        for (int i = 0; i < height; i++) {
            BlockPos p = targetPos.above(i);
            level.sendParticles(ParticleTypes.SPLASH, p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5, 30, 0.3, 0.3, 0.3, 0.1);
            level.sendParticles(ParticleTypes.BUBBLE_COLUMN_UP, p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5, 15, 0.3, 0.3, 0.3, 0.1);
            level.sendParticles(ParticleTypes.CLOUD, p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5, 2, 0.2, 0.2, 0.2, 0.05);
        }
        
        // Apply levitation and water breathing to entities in the column
        AABB columnBox = new AABB(targetPos).expandTowards(0, height, 0).inflate(1.5);
        List<LivingEntity> entities = level.getEntitiesOfClass(LivingEntity.class, columnBox);
        for (LivingEntity e : entities) {
            e.addEffect(new MobEffectInstance(MobEffects.LEVITATION, 60, 2));
            e.addEffect(new MobEffectInstance(MobEffects.WATER_BREATHING, 100, 0));
        }
    }
    
    private static void executeFire(ServerLevel level, Player player, double power) {
        Vec3 look = player.getLookAngle();
        BlockPos targetPos = BlockPos.containing(player.getX() + look.x * 3, player.getY() + 1, player.getZ() + look.z * 3);
        
        level.sendParticles(ParticleTypes.FLAME, targetPos.getX() + 0.5, targetPos.getY() + 0.5, targetPos.getZ() + 0.5, (int)(50 * power), 0.5, 0.5, 0.5, 0.1);
        level.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, targetPos.getX() + 0.5, targetPos.getY() + 0.5, targetPos.getZ() + 0.5, 15, 0.5, 0.5, 0.5, 0.05);
        
        AABB fireBox = new AABB(targetPos).inflate(1.5 * power);
        List<LivingEntity> entities = level.getEntitiesOfClass(LivingEntity.class, fireBox);
        for (LivingEntity e : entities) {
            e.igniteForSeconds(5);
        }
    }

    private static void executeWind(ServerLevel level, Player player, double power, String manifestation) {
        Vec3 look = player.getLookAngle();
        BlockPos targetPos = BlockPos.containing(player.getX() + look.x * 3, player.getY() + 1, player.getZ() + look.z * 3);
        
        level.sendParticles(ParticleTypes.CLOUD, targetPos.getX() + 0.5, targetPos.getY() + 0.5, targetPos.getZ() + 0.5, (int)(40 * power), 1.0, 1.0, 1.0, 0.2);
        
        AABB windBox = new AABB(targetPos).inflate(2.0 * power);
        List<LivingEntity> entities = level.getEntitiesOfClass(LivingEntity.class, windBox);
        for (LivingEntity e : entities) {
            if (manifestation.equals("levitation")) {
                e.addEffect(new MobEffectInstance(MobEffects.LEVITATION, 40, (int)(2 * power)));
            } else if (manifestation.equals("convergence")) {
                // Pull entities to center
                Vec3 dir = new Vec3(targetPos.getX() + 0.5 - e.getX(), targetPos.getY() + 0.5 - e.getY(), targetPos.getZ() + 0.5 - e.getZ()).normalize();
                e.setDeltaMovement(e.getDeltaMovement().add(dir.scale(power * 0.5)));
            } else {
                // Default wind burst pushes away
                Vec3 dir = new Vec3(e.getX() - player.getX(), e.getY() - player.getY(), e.getZ() - player.getZ()).normalize();
                e.setDeltaMovement(e.getDeltaMovement().add(dir.scale(power * 1.5)));
            }
        }
    }

    private static void executeEarth(ServerLevel level, Player player, double power, String manifestation) {
        Vec3 look = player.getLookAngle();
        BlockPos targetPos = BlockPos.containing(player.getX() + look.x * 3, player.getY(), player.getZ() + look.z * 3);
        
        int size = (int) Math.round(power);
        if (size < 1) size = 1;
        
        for (int x = -size; x <= size; x++) {
            for (int z = -size; z <= size; z++) {
                BlockPos p = targetPos.offset(x, 0, z);
                // Create a small mound of dirt for earth magic MVP
                if (level.getBlockState(p).isAir() || level.getBlockState(p).canBeReplaced()) {
                    level.setBlockAndUpdate(p, Blocks.DIRT.defaultBlockState());
                    level.sendParticles(new net.minecraft.core.particles.BlockParticleOption(ParticleTypes.BLOCK, Blocks.DIRT.defaultBlockState()), p.getX() + 0.5, p.getY() + 1.0, p.getZ() + 0.5, 5, 0.3, 0.3, 0.3, 0.05);
                }
                if (manifestation.equals("column")) {
                    // Earth pillar
                    for (int y = 1; y <= size * 2; y++) {
                        BlockPos py = p.above(y);
                        if (level.getBlockState(py).isAir() || level.getBlockState(py).canBeReplaced()) {
                            level.setBlockAndUpdate(py, Blocks.DIRT.defaultBlockState());
                        }
                    }
                }
            }
        }
    }
}
