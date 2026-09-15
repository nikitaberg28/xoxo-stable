package com.xoxoac.listeners;

import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.DiggingAction;
import com.github.retrooper.packetevents.protocol.player.InteractionHand;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientAnimation;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerDigging;
import com.xoxoac.modules.ViolationManager;
import com.xoxoac.modules.combat.AutoClickerA;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Single, authoritative source of "click rate" data for AutoClickerA, read directly off the raw
 * ANIMATION packet via PacketEvents rather than Bukkit's PlayerAnimationEvent, so that Geyser's
 * not-always-immediate translation of a Bedrock attack into a Java ANIMATION packet doesn't cause
 * clicks to be missed or duplicated. This is the ONLY place that feeds AutoClickerA — CombatChecks
 * only clears it on quit — so a physical click is never counted twice.
 */
public final class PacketClickListener implements PacketListener {

    private final AutoClickerA autoClickerModule;
    private final ViolationManager violationManager;
    private final Map<UUID, Long> lastMiningTime = new HashMap<>();
    private final Plugin plugin;

    public PacketClickListener(
            Plugin plugin,
            AutoClickerA autoClickerModule,
            ViolationManager violationManager
    ) {
        this.plugin = plugin;
        this.autoClickerModule = autoClickerModule;
        this.violationManager = violationManager;
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        Player player = (Player) event.getPlayer();
        if (player == null) return;
        if (violationManager.isExcepted(player)) return;

        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();

        // 1. Track block-breaking so a dig doesn't get misread as a rapid click burst.
        if (event.getPacketType() == PacketType.Play.Client.PLAYER_DIGGING) {
            WrapperPlayClientPlayerDigging digging = new WrapperPlayClientPlayerDigging(event);
            DiggingAction action = digging.getAction();

            if (action == DiggingAction.START_DIGGING) {
                lastMiningTime.put(uuid, now);
                autoClickerModule.clear(player);
            }
            return;
        }

        // 2. The actual left-click / arm-swing packet.
        if (event.getPacketType() == PacketType.Play.Client.ANIMATION) {
            WrapperPlayClientAnimation animation = new WrapperPlayClientAnimation(event);

            if (animation.getHand() == InteractionHand.MAIN_HAND) {

                // Ignore held-down mining swings for a short window after starting to dig.
                if (lastMiningTime.containsKey(uuid) && (now - lastMiningTime.get(uuid) < 250L)) {
                    return;
                }

                String alert = autoClickerModule.handleSwing(player, now);
                if (alert != null) {
                    // AutoClickerA always punishes immediately once past the CPS ceiling — see
                    // punishment.overrides.AntiCheat-cps in config.yml, which is always a kick,
                    // never a ban, since click-speed alone is too easy to false-positive on.
                    Bukkit.getScheduler().runTask(plugin, () ->
                            violationManager.punishNow(player, autoClickerModule.name(), "AntiCheat-cps", alert));
                }
            }
        }
    }

    public void handleQuit(UUID uuid) {
        lastMiningTime.remove(uuid);
    }
}
