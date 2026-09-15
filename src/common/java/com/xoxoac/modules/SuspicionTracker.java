package com.xoxoac.modules;

import java.util.*;

/**
 * Tracks a rolling suspicion score per player, driven by flags coming through ViolationManager.
 *
 * Unlike a naive "count every flag", this uses an exponential-decay score per module so that:
 *  - a handful of one-off / borderline flags (e.g. a single Jesus flag from lag) barely move the needle
 *  - sustained, repeated flags on the same module push the score up quickly
 *  - the score decays over time if the player stops triggering checks (so it reflects *current*
 *    behaviour, not lifetime history)
 *
 * The score is an approximate 0-100 "suspect %" used only for /xoxo check ordering and display —
 * it is NOT used for punishment decisions (that's still VL/max-vl in ViolationManager).
 */
public final class SuspicionTracker {

    private static final double DECAY_PER_MINUTE = 0.85; // score *= this every minute of inactivity
    private static final double SCORE_CAP = 100.0;

    private final Map<UUID, PlayerSuspicion> byPlayer = new HashMap<>();

    public void recordFlag(UUID uuid, String playerName, String module) {
        PlayerSuspicion suspicion = byPlayer.computeIfAbsent(uuid, k -> new PlayerSuspicion(playerName));
        suspicion.playerName = playerName;
        suspicion.decay();

        // Diminishing returns per additional hit on the same module in a short window,
        // so ten flags on one module don't linearly imply 10x the certainty.
        double moduleCount = suspicion.moduleHits.merge(module, 1.0, Double::sum);
        double weight = 6.0 / (1.0 + Math.log(1 + moduleCount));
        suspicion.score = Math.min(SCORE_CAP, suspicion.score + weight);
        suspicion.lastUpdateMs = System.currentTimeMillis();
    }

    public List<SuspicionEntry> topSuspects(int limit) {
        List<SuspicionEntry> entries = new ArrayList<>();
        for (Map.Entry<UUID, PlayerSuspicion> e : byPlayer.entrySet()) {
            PlayerSuspicion s = e.getValue();
            s.decay();
            if (s.score < 1.0) continue;
            entries.add(new SuspicionEntry(
                    e.getKey(),
                    s.playerName,
                    s.score,
                    topModules(s, 3)
            ));
        }
        entries.sort((a, b) -> Double.compare(b.suspicionPercent(), a.suspicionPercent()));
        return entries.size() > limit ? entries.subList(0, limit) : entries;
    }

    private List<String> topModules(PlayerSuspicion s, int limit) {
        return s.moduleHits.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(limit)
                .map(e -> e.getKey() + " (x" + e.getValue().intValue() + ")")
                .toList();
    }

    private static final class PlayerSuspicion {
        String playerName;
        double score;
        long lastUpdateMs = System.currentTimeMillis();
        final Map<String, Double> moduleHits = new HashMap<>();

        PlayerSuspicion(String playerName) {
            this.playerName = playerName;
        }

        void decay() {
            long now = System.currentTimeMillis();
            double minutesElapsed = (now - lastUpdateMs) / 60000.0;
            if (minutesElapsed <= 0) return;
            double factor = Math.pow(DECAY_PER_MINUTE, minutesElapsed);
            score *= factor;
            moduleHits.replaceAll((k, v) -> v * factor);
            moduleHits.entrySet().removeIf(e -> e.getValue() < 0.05);
            lastUpdateMs = now;
        }
    }

    public record SuspicionEntry(UUID uuid, String playerName, double score, List<String> topModules) {
        public double suspicionPercent() {
            return Math.min(99.9, score);
        }
    }
}
