package com.xoxoac.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.Map;

/**
 * Reads config.yml "checks.<module-name>" settings:
 *
 * checks:
 *   AntiFly:
 *     enabled: true
 *     max-vl: 40
 *   AntiSpeed:
 *     enabled: true
 *     max-vl: 40
 *
 * Any module not listed falls back to the "checks.default" block, or hardcoded defaults
 * (enabled: true, max-vl: 40) if that's missing too.
 */
public final class CheckSettings {

    private final Plugin plugin;
    private final Map<String, Boolean> enabledCache = new HashMap<>();
    private final Map<String, Integer> maxVlCache = new HashMap<>();
    private boolean defaultEnabled = true;
    private int defaultMaxVl = 40;

    public CheckSettings(Plugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        enabledCache.clear();
        maxVlCache.clear();

        ConfigurationSection checks = plugin.getConfig().getConfigurationSection("checks");
        if (checks == null) return;

        ConfigurationSection defaults = checks.getConfigurationSection("default");
        if (defaults != null) {
            defaultEnabled = defaults.getBoolean("enabled", true);
            defaultMaxVl = defaults.getInt("max-vl", 40);
        }

        for (String moduleName : checks.getKeys(false)) {
            if (moduleName.equals("default")) continue;
            ConfigurationSection module = checks.getConfigurationSection(moduleName);
            if (module == null) continue;
            enabledCache.put(moduleName, module.getBoolean("enabled", defaultEnabled));
            maxVlCache.put(moduleName, module.getInt("max-vl", defaultMaxVl));
        }
    }

    public boolean isEnabled(String moduleName) {
        return enabledCache.getOrDefault(moduleName, defaultEnabled);
    }

    public int maxVl(String moduleName) {
        return maxVlCache.getOrDefault(moduleName, defaultMaxVl);
    }

    /** Sets and persists an "enabled" toggle for a module (used by /xoxo module <name> <on|off>). */
    public void setEnabled(String moduleName, boolean enabled) {
        plugin.getConfig().set("checks." + moduleName + ".enabled", enabled);
        enabledCache.put(moduleName, enabled);
        plugin.saveConfig();
    }

    /** Sets and persists a max-vl threshold for a module. */
    public void setMaxVl(String moduleName, int maxVl) {
        plugin.getConfig().set("checks." + moduleName + ".max-vl", maxVl);
        maxVlCache.put(moduleName, maxVl);
        plugin.saveConfig();
    }
}
