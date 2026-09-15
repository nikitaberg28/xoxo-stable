package com.xoxoac.modules.combat;

import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class RotationLockA implements CombatCheck {

    // Deliberately PvP-only (see CombatCheck#appliesToMobs default) — standing still and
    // clicking through a mob farm/spawner queue without adjusting the camera between hits is
    // completely ordinary farming, not a killaura signature. This only makes sense as evidence
    // against a moving, evading player target.

    // THE BUG THIS REPLACED: comparing player.getLocation().getYaw()/getPitch() between two
    // consecutive hits treats "no rotation change since the last hit" as suspicious. But look
    // rotation and attack are two SEPARATE network packets that don't arrive/update in lockstep
    // — if a player attacks fast (which is completely normal, not just for Bedrock/Geyser
    // players whose position+look translation already runs on its own cadence), several hits in
    // a row can land in server ticks where the server simply hasn't yet processed a new look
    // packet, even though the player was visibly moving their camera on screen the whole time.
    // That's exactly why "dyaw=0.0000 deg" was showing up for real, actively-turning players —
    // it wasn't measuring whether they turned, only whether a look packet happened to land
    // between those two specific hit ticks.
    //
    // THE FIX: track rotation over a TIME WINDOW instead of hit-to-hit. Each hit appends a
    // (timestamp, yaw, pitch) sample to a short rolling history and compares the CURRENT
    // rotation against the OLDEST sample still inside ROTATION_WINDOW_MS — i.e. "has this
    // player's camera moved at all across their last several hits within this window", not
    // "did it move since the literal previous hit". This is robust to hit-vs-look packet
    // ordering/timing (a real player mid-fight is hitting often enough that the window fills
    // with genuine samples) and still catches the real signature (a bot whose camera truly
    // never moves while repeatedly attacking).
    private static final long ROTATION_WINDOW_MS = 500L;
    private static final float STILL_THRESHOLD_DEG = 0.05f;

    private final Map<UUID, java.util.Deque<float[]>> rotationHistory = new HashMap<>();
    private final Map<UUID, Integer> violationBuffer = new HashMap<>();

    @Override
    public String name() {
        return "RotationLockA";
    }

    private void recordRotation(Player player, long nowMs) {
        UUID uuid = player.getUniqueId();
        java.util.Deque<float[]> history = rotationHistory.computeIfAbsent(uuid, k -> new java.util.ArrayDeque<>());
        history.addLast(new float[]{nowMs, player.getLocation().getYaw(), player.getLocation().getPitch()});
        while (!history.isEmpty() && nowMs - history.peekFirst()[0] > ROTATION_WINDOW_MS) {
            history.pollFirst();
        }
    }

    @Override
    public String check(Player player, LivingEntity target, CombatContext context) {
        UUID uuid = player.getUniqueId();
        recordRotation(player, context.timestampMs());

        java.util.Deque<float[]> history = rotationHistory.get(uuid);
        if (history == null || history.isEmpty()) return null;

        float[] oldest = history.peekFirst();
        float currentYaw = player.getLocation().getYaw();
        float currentPitch = player.getLocation().getPitch();

        float deltaYaw = Math.abs(currentYaw - oldest[1]);
        if (deltaYaw > 180) deltaYaw = 360 - deltaYaw;
        float deltaPitch = Math.abs(currentPitch - oldest[2]);

        // Not enough elapsed history yet to judge a "hasn't moved the camera" claim fairly —
        // skip rather than risk a false read on a very first, very fast exchange of hits.
        long spanMs = context.timestampMs() - (long) oldest[0];
        if (spanMs < ROTATION_WINDOW_MS - 50) {
            return null;
        }

        if (deltaYaw < STILL_THRESHOLD_DEG && deltaPitch < STILL_THRESHOLD_DEG) {
            int vl = violationBuffer.merge(uuid, 1, Integer::sum);
            if (vl >= 3) {
                violationBuffer.put(uuid, 0);
                return "No camera movement across " + vl + " hits over " + ROTATION_WINDOW_MS
                        + "ms (dyaw=" + String.format("%.4f", deltaYaw) + " deg)";
            }
        } else {
            violationBuffer.computeIfPresent(uuid, (key, value) -> Math.max(0, value - 1));
        }

        return null;
    }
}
