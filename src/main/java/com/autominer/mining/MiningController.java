package com.autominer.mining;

import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
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
    
    // Start position for linear mining
    private BlockPos startPos = null;
    
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
    private int rotationTicks = 0;
    
    // Movement tracking
    private int stuckTicks = 0;
    private Vec3d lastPosition = null;
    
    // Constants
    private static final double REACH_DISTANCE = 4.5;
    private static final float ROTATION_SPEED = 15.0f;
    private static final int MIN_DELAY_TICKS = 1;
    private static final int MAX_DELAY_TICKS = 3;
    private static final int STUCK_THRESHOLD = 10;
    private static final int ROTATION_SETTLE_TICKS = 3;
    
    public void start(BlockPos pos1, BlockPos pos2) {
        blocksToMine.clear();
        currentBlockIndex = 0;
        
        // Store start position - mining will proceed FROM pos1 TOWARDS pos2
        startPos = pos1;
        
        int minX = Math.min(pos1.getX(), pos2.getX());
        int minY = Math.min(pos1.getY(), pos2.getY());
        int minZ = Math.min(pos1.getZ(), pos2.getZ());
        int maxX = Math.max(pos1.getX(), pos2.getX());
        int maxY = Math.max(pos1.getY(), pos2.getY());
        int maxZ = Math.max(pos1.getZ(), pos2.getZ());
        
        // Determine if pos1 is at min or max for each axis to set direction
        boolean xForward = pos1.getX() <= pos2.getX();
        boolean yDown = pos1.getY() >= pos2.getY(); // Usually mine top to bottom
        boolean zForward = pos1.getZ() <= pos2.getZ();
        
        // LINEAR SNAKE PATTERN: Mine layer by layer, row by row, in a consistent pattern
        // Start from pos1's Y level and work towards pos2's Y level
        int yStart = pos1.getY();
        int yEnd = pos2.getY();
        int yStep = yDown ? -1 : 1;
        
        for (int y = yStart; yDown ? (y >= yEnd) : (y <= yEnd); y += yStep) {
            // Determine X direction based on current layer (snake pattern)
            int layerIndex = Math.abs(y - yStart);
            boolean reverseX = (layerIndex % 2 == 1);
            
            int xStart = xForward ? minX : maxX;
            int xEnd = xForward ? maxX : minX;
            int xStep = xForward ? 1 : -1;
            
            // Reverse X direction for snake pattern on alternating layers
            if (reverseX) {
                xStart = xForward ? maxX : minX;
                xEnd = xForward ? minX : maxX;
                xStep = -xStep;
            }
            
            int rowIndex = 0;
            for (int x = xStart; xForward != reverseX ? (x <= xEnd) : (x >= xEnd); x += xStep) {
                // Determine Z direction based on current row (snake pattern within layer)
                boolean reverseZ = (rowIndex % 2 == 1);
                
                int zStart = zForward ? minZ : maxZ;
                int zEnd = zForward ? maxZ : minZ;
                int zStep = zForward ? 1 : -1;
                
                // Reverse Z direction for snake pattern on alternating rows
                if (reverseZ) {
                    zStart = zForward ? maxZ : minZ;
                    zEnd = zForward ? minZ : maxZ;
                    zStep = -zStep;
                }
                
                for (int z = zStart; zForward != reverseZ ? (z <= zEnd) : (z >= zEnd); z += zStep) {
                    blocksToMine.add(new BlockPos(x, y, z));
                }
                rowIndex++;
            }
        }
        
        state = State.IDLE;
        stuckTicks = 0;
        lastPosition = null;
        rotationTicks = 0;
        findNextBlock(MinecraftClient.getInstance());
    }
    
    public void stop() {
        state = State.IDLE;
        blocksToMine.clear();
        currentBlockIndex = 0;
        currentTarget = null;
        breakingBlock = null;
        breakingProgress = 0;
        stuckTicks = 0;
        lastPosition = null;
        rotationTicks = 0;
        startPos = null;
        
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
                stuckTicks = 0;
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
        
        // Check if close enough to mine
        if (distance <= REACH_DISTANCE && canSeeBlock(client, currentTarget)) {
            // Close enough and can see target, start rotating
            state = State.ROTATING;
            calculateTargetRotation(client);
            stuckTicks = 0;
            return;
        }
        
        // Stuck detection
        if (lastPosition != null) {
            double moved = playerPos.distanceTo(lastPosition);
            if (moved < 0.01) {
                stuckTicks++;
            } else {
                stuckTicks = 0;
            }
        }
        lastPosition = playerPos;
        
        // Need to move closer - calculate direction
        double dx = targetCenter.x - playerPos.x;
        double dz = targetCenter.z - playerPos.z;
        float yaw = (float) (Math.atan2(-dx, dz) * 180.0 / Math.PI);
        
        // Set player yaw for movement direction
        player.setYaw(yaw);
        player.setSprinting(false);
        
        // Check if we need to jump
        boolean shouldJump = shouldJump(client, player, yaw);
        
        // Simulate forward movement
        double speed = 0.13;
        double motionX = -Math.sin(Math.toRadians(yaw)) * speed;
        double motionZ = Math.cos(Math.toRadians(yaw)) * speed;
        
        // Apply jump if needed
        double motionY = player.getVelocity().y;
        if (shouldJump && player.isOnGround()) {
            motionY = 0.42; // Standard jump velocity
        }
        
        player.setVelocity(motionX, motionY, motionZ);
        
        // If stuck for too long, try jumping or skip block
        if (stuckTicks > STUCK_THRESHOLD * 3) {
            // Skip this block - can't reach it
            currentBlockIndex++;
            state = State.IDLE;
            stuckTicks = 0;
        }
    }
    
    private boolean shouldJump(MinecraftClient client, ClientPlayerEntity player, float yaw) {
        // Check block in front of player at feet and head level
        double checkDist = 0.8;
        double frontX = player.getX() - Math.sin(Math.toRadians(yaw)) * checkDist;
        double frontZ = player.getZ() + Math.cos(Math.toRadians(yaw)) * checkDist;
        
        BlockPos feetPos = new BlockPos((int) Math.floor(frontX), (int) Math.floor(player.getY()), (int) Math.floor(frontZ));
        BlockPos headPos = feetPos.up();
        
        ClientWorld world = client.world;
        BlockState feetBlock = world.getBlockState(feetPos);
        BlockState headBlock = world.getBlockState(headPos);
        BlockState aboveHeadBlock = world.getBlockState(headPos.up());
        
        // Jump if there's a solid block at feet level but space above
        boolean blockAtFeet = !feetBlock.isAir() && feetBlock.isSolidBlock(world, feetPos);
        boolean spaceAbove = headBlock.isAir() || !headBlock.isSolidBlock(world, headPos);
        boolean spaceAboveHead = aboveHeadBlock.isAir() || !aboveHeadBlock.isSolidBlock(world, headPos.up());
        
        // Also jump if target is above us
        boolean targetAbove = currentTarget != null && currentTarget.getY() > player.getY() + 0.5;
        
        // Also jump if stuck
        boolean isStuck = stuckTicks > STUCK_THRESHOLD;
        
        return (blockAtFeet && spaceAbove && spaceAboveHead) || (targetAbove && isStuck) || (isStuck && player.isOnGround());
    }
    
    private boolean canSeeBlock(MinecraftClient client, BlockPos target) {
        // Check if there's line of sight to the block
        Vec3d eyePos = client.player.getEyePos();
        Vec3d targetCenter = Vec3d.ofCenter(target);
        
        // Simple distance check - more sophisticated raycast could be added
        double dist = eyePos.distanceTo(targetCenter);
        return dist <= REACH_DISTANCE + 0.5;
    }
    
    private void handleRotating(MinecraftClient client) {
        if (currentTarget == null) {
            state = State.IDLE;
            return;
        }
        
        ClientPlayerEntity player = client.player;
        float currentYaw = player.getYaw();
        float currentPitch = player.getPitch();
        
        // Recalculate target rotation each tick for accuracy
        calculateTargetRotation(client);
        
        // Calculate rotation delta
        float yawDiff = targetYaw - currentYaw;
        float pitchDiff = targetPitch - currentPitch;
        
        // Normalize yaw difference
        while (yawDiff > 180) yawDiff -= 360;
        while (yawDiff < -180) yawDiff += 360;
        
        // Smooth rotation
        float newYaw = currentYaw;
        float newPitch = currentPitch;
        
        if (Math.abs(yawDiff) > 0.5f) {
            newYaw = currentYaw + Math.signum(yawDiff) * Math.min(Math.abs(yawDiff), ROTATION_SPEED);
        } else {
            newYaw = targetYaw;
        }
        
        if (Math.abs(pitchDiff) > 0.5f) {
            newPitch = currentPitch + Math.signum(pitchDiff) * Math.min(Math.abs(pitchDiff), ROTATION_SPEED);
        } else {
            newPitch = targetPitch;
        }
        
        player.setYaw(newYaw);
        player.setPitch(newPitch);
        
        // Check if rotation is complete (close enough)
        if (Math.abs(targetYaw - newYaw) <= 2.0f && Math.abs(targetPitch - newPitch) <= 2.0f) {
            rotationTicks++;
            // Wait a few ticks for rotation to settle before mining
            if (rotationTicks >= ROTATION_SETTLE_TICKS) {
                state = State.BREAKING;
                breakingProgress = 0;
                breakingBlock = currentTarget;
                rotationTicks = 0;
            }
        } else {
            rotationTicks = 0;
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
        
        // Keep looking at the target block while breaking
        calculateTargetRotation(client);
        client.player.setYaw(targetYaw);
        client.player.setPitch(targetPitch);
        
        // Select best tool
        selectBestTool(client, blockState);
        
        // Find the best face to mine from
        Direction face = getBlockFace(client, currentTarget);
        
        // Mine the target block directly (don't rely on crosshairTarget)
        ClientPlayerInteractionManager im = client.interactionManager;
        im.updateBlockBreakingProgress(currentTarget, face);
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
        
        // Clamp pitch to valid range
        targetPitch = Math.max(-90.0f, Math.min(90.0f, targetPitch));
    }
    
    private Direction getBlockFace(MinecraftClient client, BlockPos target) {
        // Calculate which face of the block is closest to the player
        Vec3d playerPos = client.player.getEyePos();
        Vec3d blockCenter = Vec3d.ofCenter(target);
        
        double dx = playerPos.x - blockCenter.x;
        double dy = playerPos.y - blockCenter.y;
        double dz = playerPos.z - blockCenter.z;
        
        double absX = Math.abs(dx);
        double absY = Math.abs(dy);
        double absZ = Math.abs(dz);
        
        // Return the face that points most towards the player
        if (absY >= absX && absY >= absZ) {
            return dy > 0 ? Direction.UP : Direction.DOWN;
        } else if (absX >= absZ) {
            return dx > 0 ? Direction.EAST : Direction.WEST;
        } else {
            return dz > 0 ? Direction.SOUTH : Direction.NORTH;
        }
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
