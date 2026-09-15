package com.xoxoac.config;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;

/**
 * Executes the punishment for a maxed-out module as one or more CONSOLE commands read from
 * config.yml, instead of the old hardcoded player.ban(uuid, ...) call. This means the server
 * owner fully controls the punishment (kick, ban, custom punishment plugin command, etc.)
 * without needing a Java rebuild, and nothing gets saved to config keyed by player UUID anymore.
 *
 * config.yml:
 *   punishment:
 *     default:
 *       - "kick {player} Подозрение в читах"
 *     overrides:
 *       AntiSpeed-cps:
 *         - "ban-ip {player} ИП 3.4 Софт"
 *
 * {player} is replaced with the exact player name. All commands run as CONSOLE on the main thread.
 */
public final class Punishments {

    private final Plugin plugin;

    public Punishments(Plugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Runs the punishment configured for "punishment.overrides.<key>", or the default if absent.
     * Returns the resolved command string(s) (joined with "; ") for logging purposes — the
     * caller (ViolationManager) records this in the daily JSON punishment log.
     */
    public String execute(Player player, String overrideKey) {
        List<String> templates = plugin.getConfig().getStringList("punishment.overrides." + overrideKey);
        if (templates.isEmpty()) {
            templates = plugin.getConfig().getStringList("punishment.default");
        }
        return runCommands(player, templates);
    }

    /** Runs the default punishment ("punishment.default") for a maxed-VL module. */
    public String executeDefault(Player player) {
        return runCommands(player, plugin.getConfig().getStringList("punishment.default"));
    }

    private String runCommands(Player player, List<String> templates) {
        if (templates == null || templates.isEmpty()) return "";

        List<String> resolved = new ArrayList<>(templates.size());
        for (String template : templates) {
            resolved.add(template.replace("{player}", player.getName()));
        }

        Runnable task = () -> {
            for (String command : resolved) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
            }
        };

        if (Bukkit.isPrimaryThread()) {
            task.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }

        return String.join("; ", resolved);
    }
}
