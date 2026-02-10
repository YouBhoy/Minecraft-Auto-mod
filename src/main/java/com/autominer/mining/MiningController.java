package com.autominer.mining;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

public class MiningController {
    
    private enum State {
        IDLE,
        MOVING,
        ROTATING,
        BREAKING,
        WAITING,
        PILLARING,
        BRIDGING,
        CLEANUP_SCAFFOLD
    }
    
    private State state = State.IDLE;
    private List<BlockPos> blocksToMine = new ArrayList<>();
    private List<BlockPos> deferredBlocks = new ArrayList<>();
    private boolean miningDeferredBlocks = false;
    private int currentBlockIndex = 0;
    private BlockPos currentTarget = null;      // The block we're currently mining
    private BlockPos queueTarget = null;        // The target from the mining queue
    private boolean targetLocked = false;       // Don't switch targets while rotating/breaking
    
    // Start position for linear mining
    private BlockPos startPos = null;
    
    // Perimeter bounds for checking blocking blocks
    private int perimeterMinX, perimeterMinY, perimeterMinZ;
    private int perimeterMaxX, perimeterMaxY, perimeterMaxZ;
    
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
    private float lockedYaw = 0;  // Yaw to use when looking steeply up/down
    private boolean yawLocked = false;
    
    // Movement tracking
    private int stuckTicks = 0;
    private Vec3d lastPosition = null;
    
    // Pillaring/Bridging tracking
    private int pillarHeight = 0;
    private int maxPillarHeight = 0;
    private BlockPos bridgeTarget = null;
    private int placementCooldown = 0;
    private Set<BlockPos> placedBlocks = new HashSet<>();
    private int swapCooldown = 0;  // Wait after swapping items
    
    // Scaffold blocks (common building blocks)
    private static final Set<Block> SCAFFOLD_BLOCKS = Set.of(
        Blocks.COBBLESTONE, Blocks.STONE, Blocks.DIRT, Blocks.NETHERRACK,
        Blocks.COBBLED_DEEPSLATE, Blocks.GRANITE, Blocks.DIORITE, Blocks.ANDESITE,
        Blocks.SANDSTONE, Blocks.OAK_PLANKS, Blocks.SPRUCE_PLANKS, Blocks.BIRCH_PLANKS,
        Blocks.JUNGLE_PLANKS, Blocks.ACACIA_PLANKS, Blocks.DARK_OAK_PLANKS,
        Blocks.MANGROVE_PLANKS, Blocks.CHERRY_PLANKS, Blocks.BAMBOO_PLANKS,
        Blocks.TUFF, Blocks.CALCITE, Blocks.SMOOTH_BASALT, Blocks.END_STONE
    );
    
    // Constants
    private static final double VANILLA_REACH_DISTANCE = 4.5;
    private static final double EXTENDED_REACH_DISTANCE = 15.0;
    private static final float ROTATION_SPEED = 25.0f;  // Faster rotation
    private static final int MIN_DELAY_TICKS = 0;
    private static final int MAX_DELAY_TICKS = 1;
    private static final int STUCK_THRESHOLD = 10;
    private static final int ROTATION_SETTLE_TICKS = 1;  // Reduced from 3
    private static final int MAX_PILLAR_HEIGHT_DEFAULT = 20;
    private static final int PLACEMENT_COOLDOWN_TICKS = 4;

    private double reachDistance = VANILLA_REACH_DISTANCE;
    
    public void start(BlockPos pos1, BlockPos pos2) {
        blocksToMine.clear();
        deferredBlocks.clear();
        miningDeferredBlocks = false;
        currentBlockIndex = 0;
        
        // Store start position - mining will proceed FROM pos1 TOWARDS pos2
        startPos = pos1;
        
        int minX = Math.min(pos1.getX(), pos2.getX());
        int minY = Math.min(pos1.getY(), pos2.getY());
        int minZ = Math.min(pos1.getZ(), pos2.getZ());
        int maxX = Math.max(pos1.getX(), pos2.getX());
        int maxY = Math.max(pos1.getY(), pos2.getY());
        int maxZ = Math.max(pos1.getZ(), pos2.getZ());
        
        // Store perimeter bounds for blocking block checks
        perimeterMinX = minX;
        perimeterMinY = minY;
        perimeterMinZ = minZ;
        perimeterMaxX = maxX;
        perimeterMaxY = maxY;
        perimeterMaxZ = maxZ;
        
        // VERTICAL SLICE PATTERN: Mine column by column, standing in front of each slice
        // Determine the primary direction (which axis has the most distance from pos1 to pos2)
        int xDist = Math.abs(pos2.getX() - pos1.getX());
        int zDist = Math.abs(pos2.getZ() - pos1.getZ());
        
        // Primary axis is the one we walk along, secondary is the width of each slice
        boolean walkAlongZ = zDist >= xDist;
        
        if (walkAlongZ) {
            // Walk along Z axis, mine X columns at each Z position
            boolean zForward = pos1.getZ() <= pos2.getZ();
            int zStart = pos1.getZ();
            int zEnd = pos2.getZ();
            int zStep = zForward ? 1 : -1;
            
            int sliceIndex = 0;
            for (int z = zStart; zForward ? (z <= zEnd) : (z >= zEnd); z += zStep) {
                // Alternate X direction for snake pattern
                boolean reverseX = (sliceIndex % 2 == 1);
                int xStart = reverseX ? maxX : minX;
                int xEnd = reverseX ? minX : maxX;
                int xStep = reverseX ? -1 : 1;
                
                for (int x = xStart; reverseX ? (x >= xEnd) : (x <= xEnd); x += xStep) {
                    // Mine from top to bottom at each (x, z) position
                    for (int y = maxY; y >= minY; y--) {
                        blocksToMine.add(new BlockPos(x, y, z));
                    }
                }
                sliceIndex++;
            }
        } else {
            // Walk along X axis, mine Z columns at each X position
            boolean xForward = pos1.getX() <= pos2.getX();
            int xStart = pos1.getX();
            int xEnd = pos2.getX();
            int xStep = xForward ? 1 : -1;
            
            int sliceIndex = 0;
            for (int x = xStart; xForward ? (x <= xEnd) : (x >= xEnd); x += xStep) {
                // Alternate Z direction for snake pattern
                boolean reverseZ = (sliceIndex % 2 == 1);
                int zStart = reverseZ ? maxZ : minZ;
                int zEnd = reverseZ ? minZ : maxZ;
                int zStep = reverseZ ? -1 : 1;
                
                for (int z = zStart; reverseZ ? (z >= zEnd) : (z <= zEnd); z += zStep) {
                    // Mine from top to bottom at each (x, z) position
                    for (int y = maxY; y >= minY; y--) {
                        blocksToMine.add(new BlockPos(x, y, z));
                    }
                }
                sliceIndex++;
            }
        }
        
        state = State.IDLE;
        stuckTicks = 0;
        lastPosition = null;
        rotationTicks = 0;
        findNextBlock(MinecraftClient.getInstance());
    }

    public void setExtendedReach(boolean enabled) {
        reachDistance = enabled ? EXTENDED_REACH_DISTANCE : VANILLA_REACH_DISTANCE;
    }

    public double getReachDistance() {
        return reachDistance;
    }
    
    public void stop() {
        state = State.IDLE;
        blocksToMine.clear();
        deferredBlocks.clear();
        miningDeferredBlocks = false;
        currentBlockIndex = 0;
        currentTarget = null;
        queueTarget = null;
        targetLocked = false;
        breakingBlock = null;
        breakingProgress = 0;
        stuckTicks = 0;
        lastPosition = null;
        rotationTicks = 0;
        startPos = null;
        pillarHeight = 0;
        maxPillarHeight = 0;
        bridgeTarget = null;
        placementCooldown = 0;
        placedBlocks.clear();
        yawLocked = false;
        
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
        
        // Decrement placement cooldown
        if (placementCooldown > 0) placementCooldown--;
        if (swapCooldown > 0) swapCooldown--;
        
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
            case PILLARING:
                handlePillaring(client);
                break;
            case BRIDGING:
                handleBridging(client);
                break;
            case CLEANUP_SCAFFOLD:
                handleCleanupScaffold(client);
                break;
        }
    }
    
    private void findNextBlock(MinecraftClient client) {
        // Find the next valid block from the queue
        while (currentBlockIndex < blocksToMine.size()) {
            BlockPos pos = blocksToMine.get(currentBlockIndex);

            // Defer mining of scaffold blocks we placed ourselves
            if (!miningDeferredBlocks && placedBlocks.contains(pos)) {
                deferredBlocks.add(pos);
                currentBlockIndex++;
                continue;
            }

            BlockState blockState = client.world.getBlockState(pos);
            
            // Skip air and unbreakable blocks
            if (!blockState.isAir() && blockState.getHardness(client.world, pos) >= 0) {
                queueTarget = pos;
                currentTarget = pos;
                targetLocked = false;
                state = State.MOVING;
                stuckTicks = 0;
                return;
            }
            currentBlockIndex++;
        }

        // If we deferred blocks, mine them after all normal blocks are done
        if (!deferredBlocks.isEmpty()) {
            blocksToMine = deferredBlocks;
            deferredBlocks = new ArrayList<>();
            currentBlockIndex = 0;
            miningDeferredBlocks = true;
            findNextBlock(client);
            return;
        }
        
        // All done
        if (!placedBlocks.isEmpty()) {
            state = State.CLEANUP_SCAFFOLD;
            showActionBarMessage(client, "§bCleaning scaffold...");
            return;
        }
        stop();
        showActionBarMessage(client, "§aMining complete!");
    }
    
    private void handleMoving(MinecraftClient client) {
        if (queueTarget == null) {
            state = State.IDLE;
            return;
        }
        
        ClientPlayerEntity player = client.player;
        Vec3d playerPos = new Vec3d(player.getX(), player.getY(), player.getZ());
        Vec3d playerEyes = player.getEyePos();
        Vec3d queueTargetCenter = Vec3d.ofCenter(queueTarget);
        
        // First check: Is the queue target visible but out of reach?
        double distToQueueTarget = playerEyes.distanceTo(queueTargetCenter);
        double horizontalDistToQueue = Math.sqrt(
            Math.pow(queueTarget.getX() + 0.5 - playerPos.x, 2) +
            Math.pow(queueTarget.getZ() + 0.5 - playerPos.z, 2)
        );
        double verticalDistToQueue = queueTarget.getY() - playerEyes.y;
        
        // Find the closest mineable block within reach FIRST
        BlockPos closestBlock = findClosestReachableBlock(client, player);
        
        if (closestBlock != null) {
            // Found a block we can mine - lock onto it
            currentTarget = closestBlock;
            targetLocked = true;
            state = State.ROTATING;
            stuckTicks = 0;
            return;
        }
        
        // No block in reach - check if we should pillar
        // Only pillar if target is significantly above us (more than 2 blocks) AND we're close horizontally
        if (verticalDistToQueue > 2.0 && horizontalDistToQueue < 2.0) {
            if (tryPillarUp(client, player)) {
                return;
            }
        }
        
        // No block in reach - move towards queue target
        // BUT don't spin head looking at unreachable blocks - just face movement direction
        double dx = queueTargetCenter.x - playerPos.x;
        double dz = queueTargetCenter.z - playerPos.z;
        float yaw = (float) (Math.atan2(-dx, dz) * 180.0 / Math.PI);
        
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
        
        // Set player yaw for movement direction (horizontal only - don't look up at unreachable blocks)
        player.setYaw(yaw);
        // Keep pitch level when moving, don't tilt head up/down at unreachable targets
        if (Math.abs(player.getPitch()) > 30) {
            player.setPitch(player.getPitch() * 0.9f);  // Gradually level out
        }
        player.setSprinting(true);
        
        // Check if we need to jump
        boolean shouldJump = shouldJump(client, player, yaw);
        
        // Simulate forward movement
        double speed = player.isSprinting() ? 0.2 : 0.13;
        double motionX = -Math.sin(Math.toRadians(yaw)) * speed;
        double motionZ = Math.cos(Math.toRadians(yaw)) * speed;
        
        // Apply jump if needed
        double motionY = player.getVelocity().y;
        if (shouldJump && player.isOnGround()) {
            motionY = 0.42; // Standard jump velocity
        }
        
        player.setVelocity(motionX, motionY, motionZ);
        
        // If stuck, try advanced navigation sooner
        if (stuckTicks > STUCK_THRESHOLD) {
            if (tryAdvancedNavigation(client, player)) {
                stuckTicks = 0;
                return;
            }
        }
        
        // If still stuck after even longer, skip block
        if (stuckTicks > STUCK_THRESHOLD * 4) {
            showActionBarMessage(client, "§eCan't reach block, skipping...");
            currentBlockIndex++;
            queueTarget = null;
            currentTarget = null;
            targetLocked = false;
            state = State.IDLE;
            stuckTicks = 0;
        }
    }
    
    private boolean tryPillarUp(MinecraftClient client, ClientPlayerEntity player) {
        int scaffoldSlot = findScaffoldBlock(client);
        if (scaffoldSlot == -1) {
            return false;
        }
        
        Vec3d playerEyes = player.getEyePos();
        double verticalDist = queueTarget.getY() - playerEyes.y;
        
        // Calculate pillar height from eye level
        maxPillarHeight = (int) Math.ceil(verticalDist) + 2;
        maxPillarHeight = Math.min(maxPillarHeight, MAX_PILLAR_HEIGHT_DEFAULT);
        pillarHeight = 0;
        state = State.PILLARING;
        showActionBarMessage(client, "§bPillaring up...");
        return true;
    }
    
    private BlockPos findClosestReachableBlock(MinecraftClient client, ClientPlayerEntity player) {
        Vec3d playerEyes = player.getEyePos();
        ClientWorld world = client.world;
        BlockPos closest = null;
        double closestDist = Double.MAX_VALUE;
        
        // Check the queue target
        if (queueTarget != null) {
            BlockState state = world.getBlockState(queueTarget);
            if (!state.isAir() && state.getHardness(world, queueTarget) >= 0) {
                double dist = playerEyes.distanceTo(Vec3d.ofCenter(queueTarget));
                if (dist <= reachDistance) {
                    closest = queueTarget;
                    closestDist = dist;
                }
            }
        }
        
        // Check for blocking blocks in front (within perimeter)
        float yaw = player.getYaw();
        for (double checkDist = 0.5; checkDist <= 2.5; checkDist += 0.5) {
            double frontX = player.getX() - Math.sin(Math.toRadians(yaw)) * checkDist;
            double frontZ = player.getZ() + Math.cos(Math.toRadians(yaw)) * checkDist;
            
            for (int yOffset = 0; yOffset <= 2; yOffset++) {
                BlockPos checkPos = new BlockPos(
                    (int) Math.floor(frontX), 
                    (int) Math.floor(player.getY()) + yOffset, 
                    (int) Math.floor(frontZ)
                );
                
                if (isInPerimeter(checkPos)) {
                    BlockState blockState = world.getBlockState(checkPos);
                    if (!blockState.isAir() && blockState.getHardness(world, checkPos) >= 0) {
                        double dist = playerEyes.distanceTo(Vec3d.ofCenter(checkPos));
                        if (dist <= reachDistance && dist < closestDist) {
                            closest = checkPos;
                            closestDist = dist;
                        }
                    }
                }
            }
        }
        
        return closest;
    }
    
    private boolean tryAdvancedNavigation(MinecraftClient client, ClientPlayerEntity player) {
        if (queueTarget == null) return false;
        
        Vec3d playerPos = new Vec3d(player.getX(), player.getY(), player.getZ());
        Vec3d playerEyes = player.getEyePos();
        double targetY = queueTarget.getY();
        double horizontalDist = Math.sqrt(
            Math.pow(queueTarget.getX() + 0.5 - playerPos.x, 2) +
            Math.pow(queueTarget.getZ() + 0.5 - playerPos.z, 2)
        );
        
        // Check if we have scaffold blocks
        int scaffoldSlot = findScaffoldBlock(client);
        if (scaffoldSlot == -1) {
            return false; // No blocks to build with
        }
        
        // Need to pillar up (target is significantly above eye level)
        // Use eye position for more accurate check
        double verticalDistFromEyes = targetY - playerEyes.y;
        
        // Only pillar if target is more than 2 blocks above eyes AND we're close horizontally
        // Don't pillar if we already have scaffold nearby
        if (verticalDistFromEyes > 2.0 && horizontalDist < 2.0) {
            // Check if we already have a scaffold block near the target height we can use
            boolean hasNearbyScaffold = false;
            for (BlockPos placed : placedBlocks) {
                if (Math.abs(placed.getY() - targetY) <= 2 && 
                    Math.abs(placed.getX() - queueTarget.getX()) <= 3 &&
                    Math.abs(placed.getZ() - queueTarget.getZ()) <= 3) {
                    hasNearbyScaffold = true;
                    break;
                }
            }
            
            if (!hasNearbyScaffold) {
                maxPillarHeight = (int) Math.ceil(verticalDistFromEyes) + 2;
                maxPillarHeight = Math.min(maxPillarHeight, MAX_PILLAR_HEIGHT_DEFAULT);
                pillarHeight = 0;
                state = State.PILLARING;
                showActionBarMessage(client, "§bPillaring up...");
                return true;
            }
        }
        
        // Need to bridge (there's a gap in front)
        float yaw = player.getYaw();
        double checkDist = 1.5;
        double frontX = playerPos.x - Math.sin(Math.toRadians(yaw)) * checkDist;
        double frontZ = playerPos.z + Math.cos(Math.toRadians(yaw)) * checkDist;
        BlockPos inFront = new BlockPos((int) Math.floor(frontX), (int) Math.floor(playerPos.y), (int) Math.floor(frontZ));
        BlockPos belowInFront = inFront.down();
        
        ClientWorld world = client.world;
        boolean gapInFront = world.getBlockState(belowInFront).isAir() && 
                            world.getBlockState(inFront).isAir();
        
        if (gapInFront && horizontalDist > 1.5) {
            bridgeTarget = queueTarget;
            state = State.BRIDGING;
            showActionBarMessage(client, "§bBridging across...");
            return true;
        }
        
        return false;
    }
    
    private void handlePillaring(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        if (player == null || queueTarget == null) {
            state = State.IDLE;
            return;
        }
        
        Vec3d playerEyes = player.getEyePos();
        Vec3d targetCenter = Vec3d.ofCenter(queueTarget);
        double distance = playerEyes.distanceTo(targetCenter);
        
        // Check if target is now within reach
        if (distance <= reachDistance) {
            showActionBarMessage(client, "§aDone pillaring, target reachable!");
            // Go directly to finding the block, skip movement phase
            BlockPos closestBlock = findClosestReachableBlock(client, player);
            if (closestBlock != null) {
                currentTarget = closestBlock;
                targetLocked = true;
                state = State.ROTATING;
            } else {
                state = State.MOVING;
            }
            pillarHeight = 0;
            maxPillarHeight = 0;
            stuckTicks = 0;
            return;
        }
        
        // Reached max pillar height
        if (pillarHeight >= maxPillarHeight) {
            showActionBarMessage(client, "§cCan't reach target (max height)");
            // Skip this block
            currentBlockIndex++;
            queueTarget = null;
            currentTarget = null;
            state = State.IDLE;
            pillarHeight = 0;
            maxPillarHeight = 0;
            stuckTicks = 0;
            return;
        }
        
        // Wait for swap cooldown
        if (swapCooldown > 0) {
            // Still jump during swap cooldown to keep momentum
            if (player.isOnGround()) {
                player.jump();
            }
            return;
        }
        
        // Find and select scaffold block
        int scaffoldSlot = findScaffoldBlock(client);
        if (scaffoldSlot == -1) {
            showActionBarMessage(client, "§cNo blocks to build with!");
            state = State.MOVING;
            return;
        }
        
        // Switch to scaffold block if needed
        if (player.getInventory().getSelectedSlot() != scaffoldSlot) {
            player.getInventory().setSelectedSlot(scaffoldSlot);
            swapCooldown = 2;
            return;
        }
        
        // Check if we have a valid block in hand
        ItemStack heldItem = player.getMainHandStack();
        if (heldItem.isEmpty() || !(heldItem.getItem() instanceof BlockItem)) {
            swapCooldown = 2;
            return;
        }
        
        // Always look straight down while pillaring
        player.setPitch(90.0f);
        
        // Get the block position directly below player's feet
        BlockPos feetPos = player.getBlockPos();
        BlockPos belowFeet = feetPos.down();
        
        // Always try to jump when on ground
        if (player.isOnGround()) {
            player.jump();
        }
        
        // Decrement placement cooldown
        if (placementCooldown > 0) {
            return;
        }
        
        // When in the air, try to place block below
        if (!player.isOnGround() && player.getVelocity().y > -0.8) {
            // Check if there's air below us where we can place
            if (client.world.getBlockState(belowFeet).isAir()) {
                // Find a solid block to place against
                BlockPos placeAgainst = belowFeet.down();
                
                if (!client.world.getBlockState(placeAgainst).isAir()) {
                    // Place on top of the block below
                    Vec3d hitVec = new Vec3d(placeAgainst.getX() + 0.5, placeAgainst.getY() + 1.0, placeAgainst.getZ() + 0.5);
                    BlockHitResult hitResult = new BlockHitResult(hitVec, Direction.UP, placeAgainst, false);
                    
                    ActionResult result = client.interactionManager.interactBlock(player, Hand.MAIN_HAND, hitResult);
                    if (result.isAccepted()) {
                        placedBlocks.add(belowFeet);
                        pillarHeight++;
                        placementCooldown = 3;
                        player.swingHand(Hand.MAIN_HAND);
                        showActionBarMessage(client, "§aPillaring: " + pillarHeight + "/" + maxPillarHeight);
                    }
                }
            }
        }
    }
    
    private void handleBridging(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        if (player == null || queueTarget == null) {
            state = State.IDLE;
            return;
        }
        
        Vec3d playerPos = new Vec3d(player.getX(), player.getY(), player.getZ());
        Vec3d targetCenter = Vec3d.ofCenter(queueTarget);
        double distance = player.getEyePos().distanceTo(targetCenter);
        
        // Check if we can now reach the target
        if (distance <= reachDistance && canSeeBlock(client, queueTarget)) {
            state = State.MOVING;
            player.setSneaking(false);
            bridgeTarget = null;
            stuckTicks = 0;
            return;
        }
        
        // Wait for cooldown
        if (placementCooldown > 0) {
            return;
        }
        
        // Find and select scaffold block
        int scaffoldSlot = findScaffoldBlock(client);
        if (scaffoldSlot == -1) {
            showActionBarMessage(client, "§cNo blocks to build with!");
            state = State.MOVING;
            player.setSneaking(false);
            return;
        }
        
        player.getInventory().setSelectedSlot(scaffoldSlot);
        
        // Calculate direction to target
        double dx = targetCenter.x - playerPos.x;
        double dz = targetCenter.z - playerPos.z;
        float yaw = (float) (Math.atan2(-dx, dz) * 180.0 / Math.PI);
        
        // Look down and slightly forward
        player.setYaw(yaw);
        player.setPitch(75.0f);
        
        // Check if there's air in front where we need to place
        BlockPos placePos = new BlockPos(
            (int) Math.floor(playerPos.x - Math.sin(Math.toRadians(yaw)) * 1.0),
            (int) Math.floor(playerPos.y) - 1,
            (int) Math.floor(playerPos.z + Math.cos(Math.toRadians(yaw)) * 1.0)
        );
        
        ClientWorld world = client.world;
        
        // Sneak to avoid falling
        player.setSneaking(true);
        
        if (world.getBlockState(placePos).isAir()) {
            // Try to place a block
            if (placeBlock(client, placePos)) {
                placementCooldown = PLACEMENT_COOLDOWN_TICKS;
                player.swingHand(Hand.MAIN_HAND);
            }
        }
        
        // Move forward slowly
        double speed = 0.08;
        double motionX = -Math.sin(Math.toRadians(yaw)) * speed;
        double motionZ = Math.cos(Math.toRadians(yaw)) * speed;
        player.setVelocity(motionX, player.getVelocity().y, motionZ);
        
        // Safety: if we've bridged too far or are falling, stop
        if (playerPos.y < queueTarget.getY() - 5) {
            state = State.MOVING;
            player.setSneaking(false);
            bridgeTarget = null;
        }
    }
    
    private int findScaffoldBlock(MinecraftClient client) {
        if (client.player == null || client.interactionManager == null) return -1;
        
        var inventory = client.player.getInventory();
        
        // FIRST: Check hotbar for preferred scaffold blocks (no swap needed)
        for (int i = 0; i < 9; i++) {
            ItemStack stack = inventory.getStack(i);
            if (stack.isEmpty()) continue;
            
            if (stack.getItem() instanceof BlockItem blockItem) {
                Block block = blockItem.getBlock();
                if (SCAFFOLD_BLOCKS.contains(block)) {
                    return i;
                }
            }
        }
        
        // SECOND: Check hotbar for any solid block
        for (int i = 0; i < 9; i++) {
            ItemStack stack = inventory.getStack(i);
            if (stack.isEmpty()) continue;
            
            if (stack.getItem() instanceof BlockItem blockItem) {
                Block block = blockItem.getBlock();
                if (block.getDefaultState().isSolidBlock(client.world, BlockPos.ORIGIN) &&
                    !block.getDefaultState().hasBlockEntity()) {
                    return i;
                }
            }
        }
        
        // THIRD: Check main inventory and swap if found
        int inventorySlot = -1;
        
        // Look for preferred blocks first
        for (int i = 9; i < 36; i++) {
            ItemStack stack = inventory.getStack(i);
            if (stack.isEmpty()) continue;
            
            if (stack.getItem() instanceof BlockItem blockItem) {
                Block block = blockItem.getBlock();
                if (SCAFFOLD_BLOCKS.contains(block)) {
                    inventorySlot = i;
                    break;
                }
            }
        }
        
        // Then any solid block
        if (inventorySlot == -1) {
            for (int i = 9; i < 36; i++) {
                ItemStack stack = inventory.getStack(i);
                if (stack.isEmpty()) continue;
                
                if (stack.getItem() instanceof BlockItem blockItem) {
                    Block block = blockItem.getBlock();
                    if (block.getDefaultState().isSolidBlock(client.world, BlockPos.ORIGIN) &&
                        !block.getDefaultState().hasBlockEntity()) {
                        inventorySlot = i;
                        break;
                    }
                }
            }
        }
        
        if (inventorySlot == -1) return -1; // Nothing found anywhere
        
        // Swap from main inventory to current hotbar slot
        int targetHotbarSlot = inventory.getSelectedSlot();
        
        client.interactionManager.clickSlot(
            client.player.currentScreenHandler.syncId,
            inventorySlot,
            targetHotbarSlot,
            net.minecraft.screen.slot.SlotActionType.SWAP,
            client.player
        );
        
        // Set swap cooldown so we wait for the item to arrive
        swapCooldown = 3;
        
        // Return the hotbar slot (item should be there after cooldown)
        return targetHotbarSlot;
    }
    
    private boolean placeBlock(MinecraftClient client, BlockPos pos) {
        if (client.interactionManager == null || client.player == null) return false;
        
        ClientWorld world = client.world;
        
        // Find an adjacent solid block to place against
        for (Direction dir : Direction.values()) {
            BlockPos adjacentPos = pos.offset(dir);
            BlockState adjacentState = world.getBlockState(adjacentPos);
            
            if (!adjacentState.isAir() && adjacentState.isSolidBlock(world, adjacentPos)) {
                // Place against this block
                Direction placeDir = dir.getOpposite();
                Vec3d hitVec = Vec3d.ofCenter(adjacentPos).add(
                    placeDir.getOffsetX() * 0.5,
                    placeDir.getOffsetY() * 0.5,
                    placeDir.getOffsetZ() * 0.5
                );
                
                BlockHitResult hitResult = new BlockHitResult(
                    hitVec,
                    placeDir,
                    adjacentPos,
                    false
                );
                
                ActionResult result = client.interactionManager.interactBlock(
                    client.player,
                    Hand.MAIN_HAND,
                    hitResult
                );
                
                if (result.isAccepted()) {
                    placedBlocks.add(pos);
                    return true;
                }
            }
        }
        
        return false;
    }
    
    // Simpler placement method - places at the position directly below the player
    private boolean placeBlockSimple(MinecraftClient client, BlockPos pos) {
        if (client.interactionManager == null || client.player == null) return false;
        
        ClientWorld world = client.world;
        ClientPlayerEntity player = client.player;
        
        // Check if we're holding a block
        ItemStack heldItem = player.getMainHandStack();
        if (heldItem.isEmpty() || !(heldItem.getItem() instanceof BlockItem)) {
            return false;
        }
        
        // Priority: try to place against the block directly below (most common for pillaring)
        BlockPos below = pos.down();
        if (!world.getBlockState(below).isAir()) {
            // Place on top of the block below us
            Vec3d hitVec = new Vec3d(below.getX() + 0.5, below.getY() + 1.0, below.getZ() + 0.5);
            BlockHitResult hitResult = new BlockHitResult(hitVec, Direction.UP, below, false);
            
            ActionResult result = client.interactionManager.interactBlock(player, Hand.MAIN_HAND, hitResult);
            if (result.isAccepted()) {
                placedBlocks.add(pos);
                return true;
            }
        }
        
        // Fallback: try all other directions
        for (Direction dir : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP}) {
            BlockPos adjacentPos = pos.offset(dir);
            BlockState adjacentState = world.getBlockState(adjacentPos);
            
            if (!adjacentState.isAir()) {
                Direction placeDir = dir.getOpposite();
                Vec3d hitVec = Vec3d.ofCenter(adjacentPos).add(
                    placeDir.getOffsetX() * 0.5,
                    placeDir.getOffsetY() * 0.5,
                    placeDir.getOffsetZ() * 0.5
                );
                
                BlockHitResult hitResult = new BlockHitResult(hitVec, placeDir, adjacentPos, false);
                ActionResult result = client.interactionManager.interactBlock(player, Hand.MAIN_HAND, hitResult);
                
                if (result.isAccepted()) {
                    placedBlocks.add(pos);
                    return true;
                }
            }
        }
        
        return false;
    }
    
    private boolean isInPerimeter(BlockPos pos) {
        return pos.getX() >= perimeterMinX && pos.getX() <= perimeterMaxX &&
               pos.getY() >= perimeterMinY && pos.getY() <= perimeterMaxY &&
               pos.getZ() >= perimeterMinZ && pos.getZ() <= perimeterMaxZ;
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
        return dist <= reachDistance + 0.5;
    }
    
    private void handleRotating(MinecraftClient client) {
        if (currentTarget == null || !targetLocked) {
            state = State.IDLE;
            return;
        }
        
        // Check if target block is still valid (not already broken)
        BlockState blockState = client.world.getBlockState(currentTarget);
        if (blockState.isAir()) {
            // Block was broken by something else, find next
            targetLocked = false;
            if (currentTarget.equals(queueTarget)) {
                currentBlockIndex++;
                queueTarget = null;
            }
            currentTarget = null;
            state = State.IDLE;
            return;
        }
        
        ClientPlayerEntity player = client.player;
        Vec3d playerEyes = player.getEyePos();
        Vec3d targetCenter = Vec3d.ofCenter(currentTarget);
        
        double dx = targetCenter.x - playerEyes.x;
        double dy = targetCenter.y - playerEyes.y;
        double dz = targetCenter.z - playerEyes.z;
        double horizontalDist = Math.sqrt(dx * dx + dz * dz);
        
        // Calculate required pitch
        float requiredPitch;
        if (horizontalDist < 0.5) {
            // Block is almost directly above/below - use fixed pitch
            requiredPitch = dy > 0 ? -85.0f : 85.0f;
        } else {
            requiredPitch = (float) (-Math.atan2(dy, horizontalDist) * 180.0 / Math.PI);
            requiredPitch = Math.max(-85.0f, Math.min(85.0f, requiredPitch));
        }
        
        // Determine if this is a "steep" angle (looking mostly up or down)
        boolean isSteepAngle = Math.abs(requiredPitch) > 45.0f;
        
        float currentYaw = player.getYaw();
        float currentPitch = player.getPitch();
        float newYaw = currentYaw;
        float newPitch = currentPitch;
        
        if (isSteepAngle) {
            // STEEP ANGLE: Don't touch yaw at all, only adjust pitch
            // This completely prevents spinning when looking up/down
            newYaw = currentYaw;  // Keep current yaw
            
            // Move pitch toward target
            float pitchDiff = requiredPitch - currentPitch;
            if (Math.abs(pitchDiff) > 1.0f) {
                newPitch = currentPitch + Math.signum(pitchDiff) * Math.min(Math.abs(pitchDiff), ROTATION_SPEED);
            } else {
                newPitch = requiredPitch;
            }
        } else {
            // NORMAL ANGLE: Adjust both yaw and pitch
            float requiredYaw = (float) (Math.atan2(-dx, dz) * 180.0 / Math.PI);
            
            float yawDiff = requiredYaw - currentYaw;
            while (yawDiff > 180) yawDiff -= 360;
            while (yawDiff < -180) yawDiff += 360;
            
            if (Math.abs(yawDiff) > 1.0f) {
                newYaw = currentYaw + Math.signum(yawDiff) * Math.min(Math.abs(yawDiff), ROTATION_SPEED);
            } else {
                newYaw = requiredYaw;
            }
            
            float pitchDiff = requiredPitch - currentPitch;
            if (Math.abs(pitchDiff) > 1.0f) {
                newPitch = currentPitch + Math.signum(pitchDiff) * Math.min(Math.abs(pitchDiff), ROTATION_SPEED);
            } else {
                newPitch = requiredPitch;
            }
        }
        
        player.setYaw(newYaw);
        player.setPitch(newPitch);
        
        // Check if rotation is complete
        boolean pitchOk = Math.abs(requiredPitch - newPitch) <= 3.0f;
        // For steep angles, yaw is always "ok" since we're not adjusting it
        boolean yawOk = isSteepAngle || Math.abs(newYaw - player.getYaw()) <= 3.0f;

        if (pitchOk) {
            rotationTicks++;
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
        if (currentTarget == null || client.interactionManager == null || !targetLocked) {
            state = State.IDLE;
            return;
        }
        
        ClientWorld world = client.world;
        BlockState blockState = world.getBlockState(currentTarget);
        
        // Check if block is already broken
        if (blockState.isAir()) {
            // If this was the queue target, advance the index
            if (currentTarget.equals(queueTarget)) {
                currentBlockIndex++;
                queueTarget = null;
            }
            currentTarget = null;
            targetLocked = false;
            state = State.WAITING;
            waitTicks = MIN_DELAY_TICKS + random.nextInt(MAX_DELAY_TICKS - MIN_DELAY_TICKS + 1);
            return;
        }
        
        // DON'T adjust rotation during breaking - just maintain current view
        // This prevents spinning while mining
        
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
            // DON'T clean up scaffold immediately - keep it for subsequent blocks
            // Only clean up when mining is done or player moves far away
            if (shouldCleanupScaffold(client)) {
                state = State.CLEANUP_SCAFFOLD;
            } else {
                state = State.IDLE;
            }
        }
    }
    
    private boolean shouldCleanupScaffold(MinecraftClient client) {
        // Only clean up scaffold when mining is stopped or queue is empty
        // This prevents rebuilding the same pillar over and over
        ClientPlayerEntity player = client.player;
        if (player == null) return false;
        
        // Don't cleanup while actively mining - still have blocks in queue
        if (currentBlockIndex < blocksToMine.size()) {
            return false;
        }
        
        // Check if we're standing on a scaffold block or there's one nearby we can reach
        for (BlockPos placed : placedBlocks) {
            double dist = player.getEyePos().distanceTo(Vec3d.ofCenter(placed));
            if (dist <= reachDistance) {
                return true;
            }
        }
        return false;
    }
    
    private void handleCleanupScaffold(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        if (player == null) {
            state = State.IDLE;
            return;
        }
        
        // Find the closest scaffold block we can reach
        BlockPos closest = null;
        double closestDist = Double.MAX_VALUE;
        Vec3d playerEyes = player.getEyePos();
        
        // Create a copy to iterate (avoid concurrent modification)
        List<BlockPos> toCheck = new ArrayList<>(placedBlocks);
        
        for (BlockPos placed : toCheck) {
            // Check if block still exists
            BlockState blockState = client.world.getBlockState(placed);
            if (blockState.isAir()) {
                placedBlocks.remove(placed);
                continue;
            }
            
            double dist = playerEyes.distanceTo(Vec3d.ofCenter(placed));
            if (dist <= reachDistance && dist < closestDist) {
                closest = placed;
                closestDist = dist;
            }
        }
        
        if (closest == null) {
            // No reachable scaffold blocks - done cleaning or need to move
            if (placedBlocks.isEmpty()) {
                stop();
                showActionBarMessage(client, "§aMining complete!");
            } else {
                // Can't reach remaining blocks, just continue mining
                state = State.IDLE;
            }
            return;
        }
        
        // Mine the scaffold block
        currentTarget = closest;
        targetLocked = true;

        selectBestTool(client, client.world.getBlockState(closest));
        
        // Look at it
        calculateTargetRotation(client);
        player.setYaw(targetYaw);
        player.setPitch(targetPitch);
        
        // Break it
        Direction face = getBlockFace(client, closest);
        client.interactionManager.updateBlockBreakingProgress(closest, face);
        player.swingHand(Hand.MAIN_HAND);
        
        // Check if broken
        if (client.world.getBlockState(closest).isAir()) {
            placedBlocks.remove(closest);
            currentTarget = null;
            targetLocked = false;
            showActionBarMessage(client, "§aScaffold cleaned: " + placedBlocks.size() + " remaining");
            if (placedBlocks.isEmpty()) {
                stop();
                showActionBarMessage(client, "§aMining complete!");
            }
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
        double totalDist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        
        // Calculate pitch first
        if (horizontalDist < 0.001) {
            // Almost directly above or below - use a stable pitch
            targetPitch = dy > 0 ? -89.0f : 89.0f;
        } else {
            targetPitch = (float) (-Math.atan2(dy, horizontalDist) * 180.0 / Math.PI);
        }
        
        // Clamp pitch to valid range
        targetPitch = Math.max(-89.0f, Math.min(89.0f, targetPitch));
        
        // Handle yaw - when looking steeply up/down, yaw becomes unstable
        // Lock yaw when pitch is beyond ±60 degrees to prevent spinning
        float pitchThresholdForYawLock = 60.0f;
        
        if (Math.abs(targetPitch) > pitchThresholdForYawLock) {
            // Steep angle - lock yaw to prevent spinning
            if (!yawLocked) {
                // First time entering steep angle - lock current calculated yaw
                if (horizontalDist > 0.1) {
                    lockedYaw = (float) (Math.atan2(-dx, dz) * 180.0 / Math.PI);
                } else {
                    // Use player's current yaw if horizontal distance is too small
                    lockedYaw = client.player.getYaw();
                }
                yawLocked = true;
            }
            targetYaw = lockedYaw;
        } else {
            // Normal angle - calculate yaw normally
            yawLocked = false;
            targetYaw = (float) (Math.atan2(-dx, dz) * 180.0 / Math.PI);
        }
    }
    
    private void maintainRotation(MinecraftClient client) {
        if (currentTarget == null || client.player == null) return;
        
        ClientPlayerEntity player = client.player;
        float currentYaw = player.getYaw();
        float currentPitch = player.getPitch();
        
        // Recalculate target
        calculateTargetRotation(client);
        
        // Calculate differences
        float yawDiff = targetYaw - currentYaw;
        float pitchDiff = targetPitch - currentPitch;
        
        // Normalize yaw
        while (yawDiff > 180) yawDiff -= 360;
        while (yawDiff < -180) yawDiff += 360;
        
        // Only adjust if significantly off (reduces jitter)
        float pitchThreshold = 3.0f;
        
        // When looking steeply up/down, use much higher yaw threshold to prevent spinning
        float yawThreshold = Math.abs(currentPitch) > 60.0f ? 15.0f : 3.0f;
        
        if (Math.abs(yawDiff) > yawThreshold) {
            // Slower yaw adjustment when looking up/down
            float yawSpeed = Math.abs(currentPitch) > 60.0f ? ROTATION_SPEED * 0.2f : ROTATION_SPEED * 0.5f;
            float adjustment = Math.signum(yawDiff) * Math.min(Math.abs(yawDiff), yawSpeed);
            player.setYaw(currentYaw + adjustment);
        }
        
        if (Math.abs(pitchDiff) > pitchThreshold) {
            float adjustment = Math.signum(pitchDiff) * Math.min(Math.abs(pitchDiff), ROTATION_SPEED * 0.5f);
            player.setPitch(currentPitch + adjustment);
        }
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
        if (client.player == null || client.interactionManager == null) return;
        
        var inventory = client.player.getInventory();
        int bestSlot = -1;
        float bestSpeed = 1.0f;
        
        // Check ENTIRE inventory for best tool (hotbar 0-8, main inventory 9-35)
        for (int i = 0; i < 36; i++) {
            var stack = inventory.getStack(i);
            if (stack.isEmpty()) continue;
            
            float speed = stack.getMiningSpeedMultiplier(blockState);
            if (speed > bestSpeed) {
                bestSpeed = speed;
                bestSlot = i;
            }
        }
        
        if (bestSlot == -1) return; // No tool found
        
        if (bestSlot < 9) {
            // Tool is in hotbar - just select it
            if (bestSlot != inventory.getSelectedSlot()) {
                inventory.setSelectedSlot(bestSlot);
            }
        } else {
            // Tool is in main inventory - need to swap it to hotbar
            int targetHotbarSlot = inventory.getSelectedSlot();
            
            // Convert inventory slot to screen handler slot index
            // In player inventory screen: hotbar is 36-44, main inventory is 9-35
            // But in the default screen handler, main inventory slots are offset
            int screenSlot = bestSlot; // For main inventory (9-35), the screen slot is the same
            
            // Use SWAP action to swap the inventory slot with current hotbar slot
            client.interactionManager.clickSlot(
                client.player.currentScreenHandler.syncId,
                screenSlot,
                targetHotbarSlot,
                net.minecraft.screen.slot.SlotActionType.SWAP,
                client.player
            );
        }
    }
    
    private void showActionBarMessage(MinecraftClient client, String message) {
        if (client.player != null) {
            client.player.sendMessage(Text.literal(message), true);
        }
    }
}
