package com.xoxoac.core;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Detects whether an online player is connecting through GeyserMC (Bedrock Edition) using
 * Geyser's own API (GeyserApi#isBedrockPlayer(UUID)) via reflection, so xoxo-AntiCheat does
 * NOT need a hard compile/runtime dependency on Geyser — if Geyser isn't present (or its API
 * shape changes), this silently reports "not Bedrock" for everyone and every check just runs
 * as it did before.
 *
 * This is the server's whole player base (Java login is disabled), but keeping the plugin
 * decoupled from Geyser's API means it degrades safely instead of failing to load if Geyser
 * is ever restarted, updated, or removed independently of this plugin.
 */
public final class BedrockPlayers {

    private static boolean attemptedInit = false;
    private static boolean available = false;
    private static Object geyserApiInstance;
    private static Method isBedrockPlayerMethod;

    private BedrockPlayers() {
    }

    public static boolean isBedrock(Player player) {
        return isBedrock(player.getUniqueId());
    }

    public static boolean isBedrock(UUID uuid) {
        ensureInit();
        if (!available) return false;
        try {
            Object result = isBedrockPlayerMethod.invoke(geyserApiInstance, uuid);
            return result instanceof Boolean b && b;
        } catch (Exception e) {
            // If Geyser's API ever throws/changes shape at runtime, fail open (treat as unknown /
            // not-Bedrock) rather than breaking the check pipeline for everyone.
            return false;
        }
    }

    private static synchronized void ensureInit() {
        if (attemptedInit) return;
        attemptedInit = true;
        try {
            Class<?> geyserImplClass = Class.forName("org.geysermc.geyser.api.GeyserApi");
            Method apiMethod = geyserImplClass.getMethod("api");
            geyserApiInstance = apiMethod.invoke(null);
            isBedrockPlayerMethod = geyserImplClass.getMethod("isBedrockPlayer", UUID.class);
            available = true;
        } catch (Throwable t) {
            available = false;
            Logger logger = Bukkit.getLogger();
            logger.info("[xoxo-AC] GeyserMC API not detected — Bedrock-specific check tolerances "
                    + "are disabled and all players will be treated as Java clients.");
        }
    }
}
