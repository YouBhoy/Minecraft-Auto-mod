package com.autominer;

import com.autominer.mining.MiningController;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.option.KeyBinding.Category;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import org.lwjgl.glfw.GLFW;

public class AutoMinerClient implements ClientModInitializer {
    
    public static final String MOD_ID = "auto-miner";
    
    // Keybindings
    private static KeyBinding keyPos1;
    private static KeyBinding keyPos2;
    private static KeyBinding keyToggle;
    private static KeyBinding keyClear;
    private static KeyBinding keyReachToggle;
    
    // Selection positions
    public static BlockPos pos1 = null;
    public static BlockPos pos2 = null;
    
    // Mining controller
    public static MiningController miningController;

    private static boolean extendedReachEnabled = false;
    
    @Override
    public void onInitializeClient() {
        // Register keybindings using the gameplay category
        keyPos1 = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.autominer.pos1",
            InputUtil.Type.KEYSYM,
            GLFW.GLFW_KEY_R,
            Category.GAMEPLAY
        ));
        
        keyPos2 = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.autominer.pos2",
            InputUtil.Type.KEYSYM,
            GLFW.GLFW_KEY_T,
            Category.GAMEPLAY
        ));
        
        keyToggle = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.autominer.toggle",
            InputUtil.Type.KEYSYM,
            GLFW.GLFW_KEY_G,
            Category.GAMEPLAY
        ));
        
        keyClear = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.autominer.clear",
            InputUtil.Type.KEYSYM,
            GLFW.GLFW_KEY_H,
            Category.GAMEPLAY
        ));

        keyReachToggle = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.autominer.reach_toggle",
            InputUtil.Type.KEYSYM,
            GLFW.GLFW_KEY_J,
            Category.GAMEPLAY
        ));
        
        // Initialize mining controller
        miningController = new MiningController();
        miningController.setExtendedReach(extendedReachEnabled);
        
        // Register tick event
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
    }
    
    private void onTick(MinecraftClient client) {
        if (client.player == null || client.world == null) return;
        
        // Handle keybinds
        while (keyPos1.wasPressed()) {
            setPosition1(client);
        }
        
        while (keyPos2.wasPressed()) {
            setPosition2(client);
        }
        
        while (keyToggle.wasPressed()) {
            toggleMining(client);
        }
        
        while (keyClear.wasPressed()) {
            clearSelection(client);
        }

        while (keyReachToggle.wasPressed()) {
            toggleReach(client);
        }
        
        // Tick the mining controller
        miningController.tick(client);
    }
    
    private void setPosition1(MinecraftClient client) {
        BlockPos target = getLookedAtBlock(client);
        if (target != null) {
            pos1 = target;
            showActionBarMessage(client, "§aPosition 1 set: " + formatPos(pos1));
        } else {
            showActionBarMessage(client, "§cLook at a block to set position");
        }
    }
    
    private void setPosition2(MinecraftClient client) {
        BlockPos target = getLookedAtBlock(client);
        if (target != null) {
            pos2 = target;
            showActionBarMessage(client, "§aPosition 2 set: " + formatPos(pos2));
        } else {
            showActionBarMessage(client, "§cLook at a block to set position");
        }
    }
    
    private void toggleMining(MinecraftClient client) {
        if (pos1 == null || pos2 == null) {
            showActionBarMessage(client, "§cSet both positions first (R and T)");
            return;
        }
        
        if (miningController.isMining()) {
            miningController.stop();
            showActionBarMessage(client, "§eMining stopped");
        } else {
            miningController.start(pos1, pos2);
            int blockCount = miningController.getRemainingBlocks();
            showActionBarMessage(client, "§aMining started: " + blockCount + " blocks");
        }
    }
    
    private void clearSelection(MinecraftClient client) {
        pos1 = null;
        pos2 = null;
        miningController.stop();
        showActionBarMessage(client, "§eSelection cleared");
    }

    private void toggleReach(MinecraftClient client) {
        extendedReachEnabled = !extendedReachEnabled;
        miningController.setExtendedReach(extendedReachEnabled);
        String label = extendedReachEnabled ? "Extended" : "Vanilla";
        showActionBarMessage(client, "§bReach: " + label + " (" + miningController.getReachDistance() + ")");
    }
    
    private BlockPos getLookedAtBlock(MinecraftClient client) {
        if (client.crosshairTarget != null && client.crosshairTarget.getType() == HitResult.Type.BLOCK) {
            return ((BlockHitResult) client.crosshairTarget).getBlockPos();
        }
        return null;
    }
    
    private void showActionBarMessage(MinecraftClient client, String message) {
        if (client.player != null) {
            client.player.sendMessage(Text.literal(message), true);
        }
    }
    
    private String formatPos(BlockPos pos) {
        return pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
    }
    
    public static boolean isMining() {
        return miningController != null && miningController.isMining();
    }
}
