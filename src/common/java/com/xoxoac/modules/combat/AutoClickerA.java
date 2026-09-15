package com.xoxoac.modules.combat;

import com.xoxoac.config.ModuleTuning;
import org.bukkit.entity.Player;

import java.util.*;

/**
 * Simple, single-mode click-speed check. There is no separate soft/hard tier — Bedrock players
 * routinely produce fast, fairly regular clicking on legitimate hardware, so a variance/std-dev
 * "is this too regular" heuristic produced too many false positives for this playerbase. The
 * only signal kept is raw CPS against a single configurable ceiling
 * (tuning.combat.autoclicker-max-cps, default 20), with a short consecutive-second buffer so one
 * lag spike doesn't trigger a punishment. Every trip past the ceiling is punished the same way
 * (see punishment.overrides.AntiCheat-cps in config.yml — always a kick, never a ban).
 */
public final class AutoClickerA {

    private final ModuleTuning tuning;
    private static final long WINDOW_MS = 1000L;

    private final Map<UUID, Deque<Long>> clicks = new HashMap<>();
    // Tracks, per player, the last 1-second bucket in which they were over the limit and how many
    // consecutive buckets that streak covers — so "buffer" really means consecutive SECONDS over
    // the limit, not consecutive clicks (a fast clicker would otherwise blow through a
    // click-counted buffer in a fraction of a second, defeating its purpose as a lag-spike guard).
    private final Map<UUID, Long> lastOverLimitBucket = new HashMap<>();
    private final Map<UUID, Integer> overLimitStreak = new HashMap<>();

    public AutoClickerA(ModuleTuning tuning) {
        this.tuning = tuning;
    }

    public String name() {
        return "AutoClickerA";
    }

    /**
     * Records a click and returns non-null once the player has been over the CPS limit for
     * enough consecutive ONE-SECOND buckets in a row to warrant a punishment. The caller
     * (PacketClickListener) routes a non-null result through ViolationManager.punishNow with the
     * "AntiCheat-cps" override, which config.yml always maps to a kick.
     */
    public String handleSwing(Player player, long now) {
        if (!tuning.autoClickerEnabled()) return null;

        UUID id = player.getUniqueId();

        Deque<Long> list = clicks.computeIfAbsent(id, k -> new ArrayDeque<>());
        list.addLast(now);

        while (!list.isEmpty() && now - list.peekFirst() > WINDOW_MS) {
            list.pollFirst();
        }

        int cps = list.size();
        int maxCps = tuning.autoClickerMaxCps();

        if (cps <= maxCps) {
            lastOverLimitBucket.remove(id);
            overLimitStreak.remove(id);
            return null;
        }

        long bucket = now / WINDOW_MS;
        Long lastBucket = lastOverLimitBucket.get(id);

        int streak;
        if (lastBucket != null && bucket == lastBucket) {
            // Same second as the last over-limit reading — don't double-count within one bucket.
            streak = overLimitStreak.getOrDefault(id, 1);
        } else if (lastBucket != null && bucket == lastBucket + 1) {
            // The very next second, still over the limit — extend the streak.
            streak = overLimitStreak.merge(id, 1, Integer::sum);
        } else {
            // First over-limit second, or a gap since the last one — streak restarts at 1.
            streak = 1;
            overLimitStreak.put(id, streak);
        }
        lastOverLimitBucket.put(id, bucket);

        if (streak >= tuning.autoClickerBufferToPunish()) {
            lastOverLimitBucket.remove(id);
            overLimitStreak.remove(id);
            return "CPS=" + cps + " (limit " + maxCps + ")";
        }
        return null;
    }

    public void clear(Player player) {
        UUID id = player.getUniqueId();
        clicks.remove(id);
        lastOverLimitBucket.remove(id);
        overLimitStreak.remove(id);
    }

    /** Current clicks-in-last-second reading for this player, without mutating any state. */
    public int currentCps(Player player) {
        Deque<Long> list = clicks.get(player.getUniqueId());
        if (list == null) return 0;
        long now = System.currentTimeMillis();
        int count = 0;
        for (long t : list) {
            if (now - t <= WINDOW_MS) count++;
        }
        return count;
    }
}
