package com.xoxoac.modules;

import com.xoxoac.config.CheckSettings;
import com.xoxoac.config.Messages;
import com.xoxoac.config.Punishments;
import com.xoxoac.core.PunishmentLogger;
import com.xoxoac.core.RepeatOffenseTracker;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;

public class ViolationManager {

    // FlyA/SpeedA/ReachA are comparatively noisy — network staleness (ping-dependent target
    // position drift, confirmed by repeated false-positive reports up to and including an
    // instant IP ban on completely legitimate PvP) means a single flag only ever kicks for any
    // of these three. Getting kicked for one of them 3 separate times within a rolling 24h
    // window escalates to an IP ban instead — see punishment.overrides.AntiCheat-repeat-movement
    // in config.yml. ReachA specifically USED TO skip straight to an instant ban on its own the
    // very first time it hit max-vl, on the theory that reach was the single strongest signal in
    // the combat suite — that theory turned out to be wrong in practice: ping compensation (see
    // ReachA.java) reduces false positives but cannot eliminate them, and an instant, no-grace
    // ban on a check that's provably capable of firing on 100% legitimate PvP is not an
    // acceptable risk. ReachA now goes through the exact same 3-strikes-per-24h path as
    // FlyA/SpeedA rather than having its own separate always-ban branch.
    private static final Set<String> REPEAT_ESCALATION_MODULES = Set.of("FlyA", "SpeedA", "ReachA");
    private static final String REPEAT_ESCALATION_OVERRIDE_KEY = "AntiCheat-repeat-movement";

    private final Plugin plugin;
    private final Messages messages;
    private final CheckSettings checkSettings;
    private final Punishments punishments;
    private final PunishmentLogger punishmentLogger;
    private final RepeatOffenseTracker repeatOffenseTracker;
    private final SuspicionTracker suspicionTracker = new SuspicionTracker();

    private final Map<UUID, Map<String, Integer>> violations  = new HashMap<>();
    // Staff members who have silenced their own alert feed
    private final Set<UUID>                       mutedAlerts = new HashSet<>();
    // Global switch for writing flags to the server console log at all. Independent of chat
    // alerts to online staff — /xoxo alert still controls each staff member's own chat feed.
    // Defaults OFF: routine flags stay out of the console entirely unless explicitly turned on
    // with /xoxo alert console — actual punishments are always recorded to the daily JSON log
    // regardless (see PunishmentLogger), so nothing is lost by keeping the console quiet.
    private boolean consoleLoggingEnabled = false;

    public ViolationManager(Plugin plugin, Messages messages, CheckSettings checkSettings,
                             Punishments punishments, PunishmentLogger punishmentLogger) {
        this.plugin = plugin;
        this.messages = messages;
        this.checkSettings = checkSettings;
        this.punishments = punishments;
        this.punishmentLogger = punishmentLogger;
        this.repeatOffenseTracker = new RepeatOffenseTracker(plugin);
        // NOTE: does NOT schedule the decay task here. ViolationManager is constructed in
        // onLoad() (so other onLoad-time wiring, like PacketEvents listener registration, can
        // depend on it), but Bukkit's scheduler refuses runTaskTimer() calls while the plugin is
        // still disabled — see startDecayTask(), which must be called from onEnable() instead.
    }

    /** Starts the per-second VL decay task. Must be called from onEnable(), not onLoad(). */
    public void startDecayTask() {
        startViolationDecayTask();
    }

    // ── Console Logging Toggle ────────────────────────────────────────────────

    /** Toggles whether flags are written to the console log at all. Returns the new state. */
    public boolean toggleConsoleLogging() {
        consoleLoggingEnabled = !consoleLoggingEnabled;
        return consoleLoggingEnabled;
    }

    public boolean isConsoleLoggingEnabled() {
        return consoleLoggingEnabled;
    }

    // ── Bypass check (LuckPerms-driven) ───────────────────────────────────────
    // Bypass is granted purely through the "xoxoac.bypass" permission node (assign via
    // LuckPerms) — there is no more in-memory/config-driven exceptions list to manage through
    // commands. A player with this permission is fully invisible to every check.

    public static final String PERM_BYPASS = "xoxoac.bypass";

    // Soft-bypass: unlike PERM_BYPASS, this does NOT hide the player from checks. Flags,
    // violation-level tracking, staff alerts and the punishment log all still fire normally —
    // this only suppresses the automatic kick/ban action once a module hits max-vl. Intended for
    // trusted staff (e.g. helpers) who you don't want auto-kicked/banned on a false positive,
    // while still keeping full visibility in case something actually looks wrong. Grant via
    // LuckPerms like any other node.
    public static final String PERM_SOFT_BYPASS = "xoxoac.softbypass";

    public boolean isExcepted(Player player) {
        return player.hasPermission(PERM_BYPASS);
    }

    public boolean isPunishmentExempt(Player player) {
        return player.hasPermission(PERM_SOFT_BYPASS);
    }

    // ── Alert Mute API ────────────────────────────────────────────────────────

    /** Toggles alert muting for the given staff UUID. Returns true if now muted. */
    public boolean toggleAlertMute(UUID uuid) {
        if (mutedAlerts.contains(uuid)) {
            mutedAlerts.remove(uuid);
            return false;
        }
        mutedAlerts.add(uuid);
        return true;
    }

    public boolean isAlertMuted(UUID uuid) { return mutedAlerts.contains(uuid); }

    // ── Violation Query API ───────────────────────────────────────────────────

    /** Returns an unmodifiable snapshot of the player's per-module VL map. */
    public Map<String, Integer> getViolationMap(UUID uuid) {
        return Collections.unmodifiableMap(
                violations.getOrDefault(uuid, Collections.emptyMap())
        );
    }

    public SuspicionTracker suspicionTracker() {
        return suspicionTracker;
    }

    // ── Flagging ──────────────────────────────────────────────────────────────

    public void flag(Player player, String module, String details) {
        UUID uuid = player.getUniqueId();
        if (isExcepted(player)) return;
        if (!checkSettings.isEnabled(module)) return;

        violations.putIfAbsent(uuid, new HashMap<>());
        int vl = violations.get(uuid).merge(module, 1, Integer::sum);
        int maxVl = checkSettings.maxVl(module);

        suspicionTracker.recordFlag(uuid, player.getName(), module);

        // Every single failed check gets its own individual line — not a VL/threshold summary,
        // and not something that only shows up once a punishment fires. This is deliberately
        // fired on every flag, independent of whether this flag also happens to cross max-vl
        // below — the two are separate concerns (live visibility vs. punishment).
        announceFlag(player, module, vl, details);

        if (vl >= maxVl) {
            violations.get(uuid).remove(module);

            if (isPunishmentExempt(player)) {
                // Soft-bypass: still record that this WOULD have been punished (for staff
                // auditing / catching a compromised or genuinely cheating account) but don't
                // actually run the kick/ban command.
                punishmentLogger.logPunishment(player.getName(), uuid, module,
                        details + " (soft-bypass: punishment suppressed)", "NONE (xoxoac.softbypass)", vl, maxVl);
                return;
            }

            if (REPEAT_ESCALATION_MODULES.contains(module)) {
                boolean escalate = repeatOffenseTracker.recordKickAndCheckEscalation(uuid);
                if (escalate) {
                    repeatOffenseTracker.reset(uuid);
                    String punishmentCommand = punishments.execute(player, REPEAT_ESCALATION_OVERRIDE_KEY);
                    punishmentLogger.logPunishment(player.getName(), uuid, module,
                            details + " (3rd kick within 24h)", punishmentCommand, vl, maxVl);
                    return;
                }
                // Not yet the 3rd kick in 24h — falls through to the normal default punishment
                // (kick) below.
            }

            String punishmentCommand = punishments.execute(player, module);
            // Actual punishments are ALWAYS recorded to the daily JSON log, independent of the
            // console-logging toggle above — this is the durable proof/audit trail, not routine
            // flag noise.
            punishmentLogger.logPunishment(player.getName(), uuid, module, details, punishmentCommand, vl, maxVl);
        }
    }

    /**
     * Flags a module using a specific punishment override key instead of the module's own name
     * (e.g. the CPS>limit autoban path, which needs its own "punishment.overrides.AntiCheat-cps"
     * command rather than the generic kick). This does NOT go through the normal max-vl gate —
     * the caller decides exactly when to call it.
     */
    public void punishNow(Player player, String reasonModule, String overrideKey, String details) {
        announceFlag(player, reasonModule, -1, details);

        if (isPunishmentExempt(player)) {
            punishmentLogger.logPunishment(player.getName(), player.getUniqueId(), reasonModule,
                    details + " (soft-bypass: punishment suppressed)", "NONE (xoxoac.softbypass)", -1, -1);
            return;
        }

        String punishmentCommand = punishments.execute(player, overrideKey);
        punishmentLogger.logPunishment(player.getName(), player.getUniqueId(), reasonModule, details, punishmentCommand, -1, -1);
    }

    /**
     * Delivers one flag line to whichever destinations are currently subscribed:
     *   - console: only if toggled on via "/xoxo alert" run FROM the console (consoleLoggingEnabled)
     *   - each online staff member's chat: only if THAT staff member has their own feed on
     *     (i.e. NOT in mutedAlerts — chat alerts are opt-OUT per player once you have the
     *     xoxoac.helper permission, toggled individually with "/xoxo alert" run as a player)
     * These two destinations are intentionally independent — toggling one must never gate the
     * other, which was the bug in the previous version (chat alerts silently required the
     * console toggle to also be on).
     */
    private void announceFlag(Player player, String module, int vl, String details) {
        // vlSuffix is assembled at runtime (it's not a config.yml template), so its own "&7"
        // color code needs translating explicitly — Messages.colorize() only ever processes the
        // static templates loaded from config.yml, not dynamic arguments passed into
        // String.format() at call time. Skipping this was the literal "&7x1" showing up in chat.
        String vlSuffix = vl >= 0
                ? org.bukkit.ChatColor.translateAlternateColorCodes('&', " &7x" + vl)
                : "";
        String line = messages.prefixed("alert-line", player.getName(), module, vlSuffix, details);

        if (consoleLoggingEnabled) {
            // Same line staff see in chat, just with colour codes stripped for the console log.
            plugin.getLogger().info(org.bukkit.ChatColor.stripColor(line));
        }

        for (Player staff : Bukkit.getOnlinePlayers()) {
            if (isStaff(staff) && !mutedAlerts.contains(staff.getUniqueId())) {
                staff.sendMessage(line);
            }
        }
    }

    private boolean isStaff(Player player) {
        return player.isOp() || player.hasPermission("xoxoac.helper");
    }

    // ── Violation Decay ───────────────────────────────────────────────────────

    private void startViolationDecayTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                for (Map<String, Integer> pv : violations.values()) {
                    // Decrement each module VL by 1 per second, floor at 0.
                    pv.replaceAll((mod, vl) -> Math.max(0, vl - 1));
                }
            }
        }.runTaskTimer(plugin, 20L, 20L);
    }
}
