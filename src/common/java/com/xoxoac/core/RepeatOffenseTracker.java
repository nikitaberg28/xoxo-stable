package com.xoxoac.core;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Tracks how many times a player has been kicked for FlyA/SpeedA within the last 24 hours, so
 * ViolationManager can escalate the 3rd such kick in a rolling 24h window into an IP ban instead
 * of "just" another kick. Bedrock movement checks are noisy enough that a single flag isn't
 * proof of cheating, but three separate kicks in one day is a much stronger signal.
 *
 * Deliberately a ROLLING 24-HOUR window, not a calendar day or an all-time counter — per the
 * server owner's own reasoning, a player who happens to get flagged once late one week and once
 * early the next isn't the same case as three kicks in one evening, so old entries fall out of
 * the window on their own as time passes rather than needing an explicit daily reset.
 *
 * Persisted to disk (repeat-kicks.yml) so a server restart doesn't wipe an in-progress escalation
 * — an in-memory-only counter would let a player reset their count for free by causing a crash or
 * simply waiting for a routine restart.
 */
public final class RepeatOffenseTracker {

    private static final long WINDOW_MS = 24L * 60 * 60 * 1000;

    private final Plugin plugin;
    private final File file;
    private final Map<UUID, List<Long>> kicksByPlayer = new HashMap<>();

    public RepeatOffenseTracker(Plugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "repeat-kicks.yml");
        load();
    }

    /**
     * Records a new FlyA/SpeedA kick for this player and returns true if this is now their 3rd
     * (or later) kick within the trailing 24 hours — i.e. the caller should escalate to a ban
     * instead of just kicking again.
     */
    public synchronized boolean recordKickAndCheckEscalation(UUID uuid) {
        long now = Instant.now().toEpochMilli();
        List<Long> timestamps = kicksByPlayer.computeIfAbsent(uuid, k -> new ArrayList<>());
        timestamps.removeIf(ts -> now - ts > WINDOW_MS);
        timestamps.add(now);
        save();
        return timestamps.size() >= 3;
    }

    /** Clears a player's kick history — used after an escalation fires, so the count restarts. */
    public synchronized void reset(UUID uuid) {
        kicksByPlayer.remove(uuid);
        save();
    }

    private void load() {
        if (!file.exists()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        for (String key : yaml.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(key);
                List<Long> timestamps = new ArrayList<>(yaml.getLongList(key));
                long now = Instant.now().toEpochMilli();
                timestamps.removeIf(ts -> now - ts > WINDOW_MS);
                if (!timestamps.isEmpty()) {
                    kicksByPlayer.put(uuid, timestamps);
                }
            } catch (IllegalArgumentException ignored) {
                // Skip malformed/legacy keys rather than failing the whole load.
            }
        }
    }

    private void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<UUID, List<Long>> entry : kicksByPlayer.entrySet()) {
            if (!entry.getValue().isEmpty()) {
                yaml.set(entry.getKey().toString(), entry.getValue());
            }
        }
        try {
            plugin.getDataFolder().mkdirs();
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("[xoxo-AC] Failed to save repeat-kicks.yml: " + e.getMessage());
        }
    }
}
