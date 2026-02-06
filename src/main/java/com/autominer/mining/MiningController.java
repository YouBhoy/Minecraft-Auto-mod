package com.autominer.mining;

import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class MiningController {
    
    private enum State {
        IDLE,
        MOVING,
        ROTATING,
        BREAKING,
        WAITING
    }
    
    private State state = State.IDLE;
    private List<BlockPos> blocksToMine = new ArrayList<>();
    private int currentBlockIndex = 0;
    private BlockPos currentTarget = null;
    
    // Timing for anti-cheat
    private long lastActionTime = 0;
    private int waitTicks = 0;
    private final Random random = new Random();
    
    // Breaking progress
    private float breakingProgress = 0;
    private BlockPos breakingBlock = null;
    
    // Rotation tracking
    private float targetYaw = 0;
    private float targetPitch = 0;
    
    // Constants
    private static final double REACH_DISTANCE = 4.5;
    private static final float ROTATION_SPEED = 15.0f;
    private static final int MIN_DELAY_TICKS = 2;
    private static final int MAX_DELAY_TICKS = 5;
    
    public void start(BlockPos pos1, BlockPos pos2) {
        blocksToMine.clear();
        currentBlockIndex = 0;
        
        int minX = Math.min(pos1.getX(), pos2.getX());
        int minY = Math.min(pos1.getY(), pos2.getY());
        int minZ = Math.min(pos1.getZ(), pos2.getZ());
        int maxX = Math.max(pos1.getX(), pos2.getX());
        int maxY = Math.max(pos1.getY(), pos2.getY());
        int maxZ = Math.max(pos1.getZ(), pos2.getZ());
        
        // Add blocks from top to bottom (safer for mining)
        for (int y = maxY; y >= minY; y--) {
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    blocksToMine.add(new BlockPos(x, y, z));
                }
            }
        }
        
        state = State.IDLE;
        findNextBlock(MinecraftClient.getInstance());
    }
    
    public void stop() {
        state = State.IDLE;
        blocksToMine.clear();
        currentBlockIndex = 0;
        currentTarget = null;
        breakingBlock = null;
        breakingProgress = 0;
        
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.interactionManager != null) {
            client.interactionManager.cancelBlockBreaking();
        }
    }
    
    public boolean isMining() {
        return state != State.IDLE || !blocksToMine.isEmpty();
    }
    
    public int getRemainingBlocks() {
        return blocksToMine.size() - currentBlockIndex;
    }
    
    public void tick(MinecraftClient client) {
        if (state == State.IDLE && blocksToMine.isEmpty()) return;
        if (client.player == null || client.world == null) return;
        
        switch (state) {
            case IDLE:
                findNextBlock(client);
                break;
            case MOVING:
                handleMoving(client);
                break;
            case ROTATING:
                handleRotating(client);
                break;
            case BREAKING:
                handleBreaking(client);
                break;
            case WAITING:
                handleWaiting(client);
                break;
        }
    }
    
    private void findNextBlock(MinecraftClient client) {
        while (currentBlockIndex < blocksToMine.size()) {
            BlockPos pos = blocksToMine.get(currentBlockIndex);
            BlockState blockState = client.world.getBlockState(pos);
            
            // Skip air and unbreakable blocks
            if (!blockState.isAir() && blockState.getHardness(client.world, pos) >= 0) {
                currentTarget = pos;
                state = State.MOVING;
                return;
            }
            currentBlockIndex++;
        }
        
        // All done
        stop();
        showActionBarMessage(client, "§aMining complete!");
    }
    
    private void handleMoving(MinecraftClient client) {
        if (currentTarget == null) {
            state = State.IDLE;
            return;
        }
        
        ClientPlayerEntity player = client.player;
        Vec3d playerPos = new Vec3d(player.getX(), player.getY(), player.getZ());
        Vec3d targetCenter = Vec3d.ofCenter(currentTarget);
        double distance = playerPos.distanceTo(targetCenter);
        
        if (distance <= REACH_DISTANCE) {
            // Close enough, start rotating
            state = State.ROTATING;
            calculateTargetRotation(client);
            return;
        }
        
        // Need to move closer - calculate direction
        double dx = targetCenter.x - playerPos.x;
        double dz = targetCenter.z - playerPos.z;
        float yaw = (float) (Math.atan2(-dx, dz) * 180.0 / Math.PI);
        
        // Set player movement
        player.setYaw(yaw);
        player.setSprinting(false);
        
        // Simulate forward movement by setting velocity
        double speed = 0.15;
        double motionX = -Math.sin(Math.toRadians(yaw)) * speed;
        double motionZ = Math.cos(Math.toRadians(yaw)) * speed;
        player.setVelocity(motionX, player.getVelocity().y, motionZ);
    }
    
    private void handleRotating(MinecraftClient client) {
        if (currentTarget == null) {
            state = State.IDLE;
            return;
        }
        
        ClientPlayerEntity player = client.player;
        float currentYaw = player.getYaw();
        float currentPitch = player.getPitch();
        
        // Calculate rotation delta
        float yawDiff = targetYaw - currentYaw;
        float pitchDiff = targetPitch - currentPitch;
        
        // Normalize yaw difference
        while (yawDiff > 180) yawDiff -= 360;
        while (yawDiff < -180) yawDiff += 360;
        
        // Smooth rotation
        float newYaw = currentYaw;
        float newPitch = currentPitch;
        
        if (Math.abs(yawDiff) > 1.0f) {
            newYaw = currentYaw + Math.signum(yawDiff) * Math.min(Math.abs(yawDiff), ROTATION_SPEED);
        } else {
            newYaw = targetYaw;
        }
        
        if (Math.abs(pitchDiff) > 1.0f) {
            newPitch = currentPitch + Math.signum(pitchDiff) * Math.min(Math.abs(pitchDiff), ROTATION_SPEED);
        } else {
            newPitch = targetPitch;
        }
        
        player.setYaw(newYaw);
        player.setPitch(newPitch);
        
        // Check if rotation is complete
        if (Math.abs(targetYaw - newYaw) <= 1.0f && Math.abs(targetPitch - newPitch) <= 1.0f) {
            state = State.BREAKING;
            breakingProgress = 0;
            breakingBlock = currentTarget;
        }
    }
    
    private void handleBreaking(MinecraftClient client) {
        if (currentTarget == null || client.interactionManager == null) {
            state = State.IDLE;
            return;
        }
        
        ClientWorld world = client.world;
        BlockState blockState = world.getBlockState(currentTarget);
        
        // Check if block is already broken
        if (blockState.isAir()) {
            currentBlockIndex++;
            state = State.WAITING;
            waitTicks = MIN_DELAY_TICKS + random.nextInt(MAX_DELAY_TICKS - MIN_DELAY_TICKS + 1);
            return;
        }
        
        // Select best tool
        selectBestTool(client, blockState);
        
        // Start or continue breaking
        ClientPlayerInteractionManager im = client.interactionManager;
        
        if (breakingBlock == null || !breakingBlock.equals(currentTarget)) {
            breakingBlock = currentTarget;
            breakingProgress = 0;
        }
        
        // Attack the block
        im.updateBlockBreakingProgress(currentTarget, Direction.UP);
        client.player.swingHand(Hand.MAIN_HAND);
    }
    
    private void handleWaiting(MinecraftClient client) {
        waitTicks--;
        if (waitTicks <= 0) {
            state = State.IDLE;
        }
    }
    
    private void calculateTargetRotation(MinecraftClient client) {
        if (currentTarget == null || client.player == null) return;
        
        Vec3d playerEyes = client.player.getEyePos();
        Vec3d targetCenter = Vec3d.ofCenter(currentTarget);
        
        double dx = targetCenter.x - playerEyes.x;
        double dy = targetCenter.y - playerEyes.y;
        double dz = targetCenter.z - playerEyes.z;
        
        double horizontalDist = Math.sqrt(dx * dx + dz * dz);
        
        targetYaw = (float) (Math.atan2(-dx, dz) * 180.0 / Math.PI);
        targetPitch = (float) (Math.atan2(-dy, horizontalDist) * 180.0 / Math.PI);
    }
    
    private void selectBestTool(MinecraftClient client, BlockState blockState) {
        if (client.player == null) return;
        
        var inventory = client.player.getInventory();
        int bestSlot = -1;
        float bestSpeed = 1.0f;
        
        // Check hotbar for best tool
        for (int i = 0; i < 9; i++) {
            var stack = inventory.getStack(i);
            if (stack.isEmpty()) continue;
            
            float speed = stack.getMiningSpeedMultiplier(blockState);
            if (speed > bestSpeed) {
                bestSpeed = speed;
                bestSlot = i;
            }
        }
        
        // Switch to best tool
        if (bestSlot != -1 && bestSlot != inventory.getSelectedSlot()) {
            inventory.setSelectedSlot(bestSlot);
        }
    }
    
    private void showActionBarMessage(MinecraftClient client, String message) {
        if (client.player != null) {
            client.player.sendMessage(Text.literal(message), true);
        }
    }
}
