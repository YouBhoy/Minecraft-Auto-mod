package com.autominer.combat;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.SwordItem;
import net.minecraft.item.AxeItem;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.Comparator;
import java.util.List;

public class CombatController {
    
    private boolean enabled = true;
    private boolean activeWhileMining = true;
    
    // Configuration
    private double attackRange = 3.5;
    private double detectionRange = 8.0;
    private int attackCooldown = 0;
    private static final int ATTACK_COOLDOWN_TICKS = 10;
    
    // State
    private LivingEntity currentTarget = null;
    private int originalHotbarSlot = -1;
    private boolean inCombat = false;
    
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (!enabled) {
            reset();
        }
    }
    
    public boolean isEnabled() {
        return enabled;
    }
    
    public void setActiveWhileMining(boolean active) {
        this.activeWhileMining = active;
    }
    
    public boolean isActiveWhileMining() {
        return activeWhileMining;
    }
    
    public void setAttackRange(double range) {
        this.attackRange = Math.max(1.0, Math.min(6.0, range));
    }
    
    public double getAttackRange() {
        return attackRange;
    }
    
    public void setDetectionRange(double range) {
        this.detectionRange = Math.max(3.0, Math.min(20.0, range));
    }
    
    public double getDetectionRange() {
        return detectionRange;
    }
    
    public boolean isInCombat() {
        return inCombat;
    }
    
    public void tick(MinecraftClient client, boolean isMining) {
        if (!enabled) return;
        if (!activeWhileMining && isMining) return;
        if (client.player == null || client.world == null) return;
        
        // Decrement cooldown
        if (attackCooldown > 0) {
            attackCooldown--;
        }
        
        ClientPlayerEntity player = client.player;
        
        // Find hostile mobs nearby
        LivingEntity nearestThreat = findNearestThreat(client);
        
        if (nearestThreat != null) {
            if (!inCombat) {
                // Entering combat
                inCombat = true;
                originalHotbarSlot = player.getInventory().selectedSlot;
                showMessage(client, "§c⚔ Combat mode!");
            }
            
            currentTarget = nearestThreat;
            handleCombat(client);
        } else {
            if (inCombat) {
                // Exiting combat
                inCombat = false;
                if (originalHotbarSlot >= 0 && originalHotbarSlot < 9) {
                    player.getInventory().selectedSlot = originalHotbarSlot;
                }
                originalHotbarSlot = -1;
                currentTarget = null;
                showMessage(client, "§aCombat ended");
            }
        }
    }
    
    private LivingEntity findNearestThreat(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        Vec3d playerPos = player.getPos();
        
        Box searchBox = new Box(
            playerPos.x - detectionRange, playerPos.y - detectionRange, playerPos.z - detectionRange,
            playerPos.x + detectionRange, playerPos.y + detectionRange, playerPos.z + detectionRange
        );
        
        List<Entity> entities = client.world.getOtherEntities(player, searchBox, entity -> {
            if (!(entity instanceof LivingEntity living)) return false;
            if (!living.isAlive()) return false;
            if (entity instanceof PlayerEntity) return false;
            
            // Check if hostile
            if (entity instanceof HostileEntity) return true;
            
            // Check if attacking player
            if (living.getAttacking() == player || living.getAttacker() == player) return true;
            
            return false;
        });
        
        if (entities.isEmpty()) return null;
        
        // Find closest threat
        return entities.stream()
            .map(e -> (LivingEntity) e)
            .min(Comparator.comparingDouble(mob -> mob.squaredDistanceTo(player)))
            .orElse(null);
    }
    
    private void handleCombat(MinecraftClient client) {
        if (currentTarget == null || !currentTarget.isAlive()) {
            currentTarget = null;
            return;
        }
        
        ClientPlayerEntity player = client.player;
        double distance = player.distanceTo(currentTarget);
        
        // Switch to weapon
        selectBestWeapon(client);
        
        // Look at target
        lookAtEntity(player, currentTarget);
        
        // Attack if in range and cooldown ready
        if (distance <= attackRange && attackCooldown <= 0 && player.getAttackCooldownProgress(0.5f) >= 0.9f) {
            client.interactionManager.attackEntity(player, currentTarget);
            player.swingHand(Hand.MAIN_HAND);
            attackCooldown = ATTACK_COOLDOWN_TICKS;
        }
        
        // Move towards if out of range
        if (distance > attackRange - 0.5) {
            moveTowards(player, currentTarget);
        }
    }
    
    private void selectBestWeapon(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        var inventory = player.getInventory();
        
        int bestSlot = -1;
        float bestDamage = 0.0f;
        
        // Check hotbar for weapons
        for (int i = 0; i < 9; i++) {
            ItemStack stack = inventory.getStack(i);
            if (stack.isEmpty()) continue;
            
            float damage = 0.0f;
            if (stack.getItem() instanceof SwordItem sword) {
                damage = sword.getAttackDamage() + 4.0f; // Prefer swords
            } else if (stack.getItem() instanceof AxeItem axe) {
                damage = axe.getAttackDamage();
            }
            
            if (damage > bestDamage) {
                bestDamage = damage;
                bestSlot = i;
            }
        }
        
        if (bestSlot >= 0 && inventory.selectedSlot != bestSlot) {
            inventory.selectedSlot = bestSlot;
        }
    }
    
    private void lookAtEntity(ClientPlayerEntity player, LivingEntity target) {
        Vec3d playerEyes = player.getEyePos();
        Vec3d targetPos = target.getPos().add(0, target.getEyeHeight(target.getPose()) * 0.9, 0);
        
        double dx = targetPos.x - playerEyes.x;
        double dy = targetPos.y - playerEyes.y;
        double dz = targetPos.z - playerEyes.z;
        
        double horizontalDist = Math.sqrt(dx * dx + dz * dz);
        
        float yaw = (float) (Math.atan2(-dx, dz) * 180.0 / Math.PI);
        float pitch = (float) (-Math.atan2(dy, horizontalDist) * 180.0 / Math.PI);
        
        player.setYaw(yaw);
        player.setPitch(pitch);
    }
    
    private void moveTowards(ClientPlayerEntity player, LivingEntity target) {
        Vec3d playerPos = player.getPos();
        Vec3d targetPos = target.getPos();
        
        double dx = targetPos.x - playerPos.x;
        double dz = targetPos.z - playerPos.z;
        
        double distance = Math.sqrt(dx * dx + dz * dz);
        if (distance < 0.1) return;
        
        // Normalize and scale
        double speed = 0.13;
        double motionX = (dx / distance) * speed;
        double motionZ = (dz / distance) * speed;
        
        player.setVelocity(motionX, player.getVelocity().y, motionZ);
        player.setSprinting(true);
    }
    
    private void reset() {
        currentTarget = null;
        inCombat = false;
        originalHotbarSlot = -1;
        attackCooldown = 0;
    }
    
    private void showMessage(MinecraftClient client, String message) {
        if (client.player != null) {
            client.player.sendMessage(Text.literal(message), true);
        }
    }
}
