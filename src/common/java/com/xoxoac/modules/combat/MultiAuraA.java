package com.xoxoac.modules.combat;

import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class MultiAuraA implements CombatCheck {

    // Deliberately PvP-only (see CombatCheck#appliesToMobs default) — this exists to catch
    // killaura swapping between multiple PLAYER targets rapidly. Hitting 3+ different mobs in
    // 1.5s is completely ordinary AoE/farm combat (a mob farm, a crowd of hostiles at night) and
    // must never trip this, which is exactly the false-positive the server owner's own farm-mob
    // reproduction test surfaced.
    private static final long KA_WINDOW_MS = 1500L;
    private static final int KA_TARGETS = 3;

    private final Map<UUID, Deque<long[]>> hitLog = new HashMap<>();

    @Override
    public String name() {
        return "MultiAuraA";
    }

    @Override
    public String check(Player player, LivingEntity target, CombatContext context) {
        Deque<long[]> hits = hitLog.computeIfAbsent(player.getUniqueId(), key -> new ArrayDeque<>());
        hits.removeIf(hit -> context.timestampMs() - hit[1] > KA_WINDOW_MS);
        hits.addLast(new long[]{target.getEntityId(), context.timestampMs()});

        long distinct = hits.stream().mapToLong(hit -> hit[0]).distinct().count();
        if (distinct >= KA_TARGETS) {
            hits.clear();
            return "Hit " + distinct + " distinct targets in " + KA_WINDOW_MS + "ms";
        }

        return null;
    }
}
