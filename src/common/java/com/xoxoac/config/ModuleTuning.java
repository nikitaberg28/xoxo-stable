package com.xoxoac.config;

import org.bukkit.plugin.Plugin;

/**
 * Config-driven values for the main tunable numbers across check modules — the ones actually
 * worth adjusting without a rebuild (speed caps, buffer sizes, reach distances, packet-rate
 * limits). This deliberately does NOT expose every constant in every check class; internal
 * bookkeeping values (grace-tick counts for water-exit, elytra, knockback, etc.) stay hardcoded
 * since they're implementation details, not tuning knobs a server owner would reasonably want to
 * change. Read from config.yml under "tuning:".
 */
public final class ModuleTuning {

    private final Plugin plugin;

    // Movement
    private double speedMaxBpt;
    private double speedSprintMaxBpt;
    private double speedSprintJumpMaxBpt;
    private int speedSprintJumpGraceTicks;
    private double speedPotionBonusPerLevel;
    private double flyMaxRiptideY;
    private int flyAirTicksBeforeFlag;
    private int movementBufferToFlag;
    private int boostGraceTicks;
    private int boostRecentActionTicks;

    // Combat
    private double reachMaxBlocks;
    private double reachMaxBlocksSpear;
    private double reachLeniency;
    private double aimAngleMinDot;
    // Single CPS ceiling for AutoClickerA. Crossing it (with a short buffer to absorb one lag
    // spike) always results in a kick — see Punishments/ViolationManager. There is no separate
    // soft/hard mode anymore: Bedrock players click fast enough on legitimate hardware that a
    // two-tier system produced too many false positives, so this is intentionally a single,
    // forgiving-but-consistent limit.
    private int autoClickerMaxCps;
    private int autoClickerBufferToPunish;
    private boolean autoClickerEnabled;

    public ModuleTuning(Plugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        var c = plugin.getConfig();

        speedMaxBpt              = c.getDouble("tuning.movement.speed-max-bpt", 0.42);
        speedSprintMaxBpt        = c.getDouble("tuning.movement.speed-sprint-max-bpt", 0.66);
        speedSprintJumpMaxBpt    = c.getDouble("tuning.movement.speed-sprint-jump-max-bpt", 1.65);
        speedSprintJumpGraceTicks = c.getInt("tuning.movement.speed-sprint-jump-grace-ticks", 8);
        speedPotionBonusPerLevel = c.getDouble("tuning.movement.speed-potion-bonus-per-level", 0.12);
        flyMaxRiptideY           = c.getDouble("tuning.movement.fly-max-riptide-y", 21.5);
        flyAirTicksBeforeFlag    = c.getInt("tuning.movement.fly-air-ticks-before-flag", 8);
        movementBufferToFlag     = c.getInt("tuning.movement.buffer-to-flag", 3);
        boostGraceTicks          = c.getInt("tuning.movement.boost-grace-ticks", 30);
        boostRecentActionTicks   = c.getInt("tuning.movement.boost-recent-action-ticks", 6);

        reachMaxBlocks           = c.getDouble("tuning.combat.reach-max-blocks", 3.0);
        reachMaxBlocksSpear      = c.getDouble("tuning.combat.reach-max-blocks-spear", 4.5);
        reachLeniency            = c.getDouble("tuning.combat.reach-leniency", 0.70);
        aimAngleMinDot           = c.getDouble("tuning.combat.aim-angle-min-dot", 0.19);
        autoClickerMaxCps        = c.getInt("tuning.combat.autoclicker-max-cps", 20);
        autoClickerBufferToPunish = c.getInt("tuning.combat.autoclicker-buffer-to-punish", 3);
        autoClickerEnabled       = c.getBoolean("tuning.combat.autoclicker-enabled", true);
    }

    public double speedMaxBpt() { return speedMaxBpt; }
    public double speedSprintMaxBpt() { return speedSprintMaxBpt; }
    public double speedSprintJumpMaxBpt() { return speedSprintJumpMaxBpt; }
    public int speedSprintJumpGraceTicks() { return speedSprintJumpGraceTicks; }
    public double speedPotionBonusPerLevel() { return speedPotionBonusPerLevel; }
    public double flyMaxRiptideY() { return flyMaxRiptideY; }
    public int flyAirTicksBeforeFlag() { return flyAirTicksBeforeFlag; }
    public int movementBufferToFlag() { return movementBufferToFlag; }
    public int boostGraceTicks() { return boostGraceTicks; }
    public int boostRecentActionTicks() { return boostRecentActionTicks; }

    public double reachMaxBlocks() { return reachMaxBlocks; }
    public double reachMaxBlocksSpear() { return reachMaxBlocksSpear; }
    public double reachLeniency() { return reachLeniency; }
    public double aimAngleMinDot() { return aimAngleMinDot; }
    public int autoClickerMaxCps() { return autoClickerMaxCps; }
    public int autoClickerBufferToPunish() { return autoClickerBufferToPunish; }
    public boolean autoClickerEnabled() { return autoClickerEnabled; }
}
