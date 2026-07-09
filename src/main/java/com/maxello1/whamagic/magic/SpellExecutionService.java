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

import java.util.List;

public class SpellExecutionService {
    public static void execute(Level level, Player player, SpellIr spell) {
        if (!(level instanceof ServerLevel serverLevel)) return;
        
        String element = spell.element() != null ? spell.element().toLowerCase() : "";
        String manifestation = spell.manifestation() != null ? spell.manifestation().toLowerCase() : "";
        
        if (element.equals("water") && manifestation.equals("column")) {
            executeWaterColumn(serverLevel, player, spell.power());
        } else if (element.equals("fire")) {
            executeFire(serverLevel, player, spell.power());
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
}
