package com.xoxoac.core;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.logging.Logger;

/**
 * Verbose, config-gated per-tick tracer for diagnosing false-positive movement/combat flags.
 * Disabled by default (debug.movement-trace: false in config.yml) since it logs every single
 * tick for the target and would flood the console otherwise.
 *
 * Enable with: /xoxo debug <player> — toggles tracing for that player's UUID only, so you can
 * reproduce a specific false-positive (e.g. spear-Lunge dash) and read back exactly what every
 * relevant signal (isRiptiding, isHandRaised, active item, boosted flag, deltaY/deltaXZ,
 * airTicks) was doing on the ticks immediately before and after the flag fired.
 */
public final class MovementTracer {

    private final Plugin plugin;
    private final Logger logger;
    private final java.util.Set<java.util.UUID> tracedPlayers = new java.util.HashSet<>();

    public MovementTracer(Plugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
    }

    public boolean toggle(java.util.UUID uuid) {
        if (tracedPlayers.contains(uuid)) {
            tracedPlayers.remove(uuid);
            return false;
        }
        tracedPlayers.add(uuid);
        return true;
    }

    public boolean isTraced(java.util.UUID uuid) {
        return tracedPlayers.contains(uuid);
    }

    public void trace(Player player, String context, double deltaY, double deltaXZ,
                       boolean boosted, int airTicks, int graceRemaining) {
        if (!tracedPlayers.contains(player.getUniqueId())) return;

        Object activeItem = player.getActiveItem() != null ? player.getActiveItem().getType() : "AIR";
        logger.info(String.format(
                "[TRACE:%s] %s | riptide=%b handRaised=%b activeItem=%s | dY=%.3f dXZ=%.3f | "
                        + "boosted=%b airTicks=%d graceLeft=%d",
                player.getName(), context,
                player.isRiptiding(), player.isHandRaised(), activeItem,
                deltaY, deltaXZ, boosted, airTicks, graceRemaining
        ));
    }
}
