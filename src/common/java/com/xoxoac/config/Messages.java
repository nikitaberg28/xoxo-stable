package com.xoxoac.config;

import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.Map;

/**
 * Loads every player-facing / staff-facing string from config.yml under "messages:" so the
 * server owner can retranslate or restyle everything without touching Java code.
 *
 * Style contract (see config.yml "messages.prefix"):
 *   &f[&9LC&f] &fMessage text
 * &9 (blue) is reserved for accent words, &f (white) for the rest, per house style.
 */
public final class Messages {

    private final Plugin plugin;
    private final Map<String, String> cache = new HashMap<>();
    private String prefix;

    public Messages(Plugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        cache.clear();
        FileConfiguration config = plugin.getConfig();
        prefix = colorize(config.getString("messages.prefix", "&f[&9LC&f] "));

        var section = config.getConfigurationSection("messages");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                if (key.equals("prefix")) continue;
                Object value = section.get(key);
                if (value instanceof String s) {
                    cache.put(key, colorize(s));
                } else if (value != null) {
                    // nested lists/maps (e.g. cheater-list template lines) are read directly by callers
                    // that need list semantics; skip here.
                }
            }
        }
    }

    /** Returns the raw (colorized, but un-prefixed) message for a key, formatting with args via String.format. */
    public String raw(String key, Object... args) {
        String template = cache.getOrDefault(key, ChatColor.RED + "Missing message: " + key);
        try {
            return String.format(template, args);
        } catch (Exception e) {
            return template;
        }
    }

    /** Returns the message prefixed with the standard "&f[&9LC&f] " house prefix. */
    public String prefixed(String key, Object... args) {
        return prefix + raw(key, args);
    }

    public String prefix() {
        return prefix;
    }

    public java.util.List<String> rawList(String key) {
        java.util.List<String> list = plugin.getConfig().getStringList("messages." + key);
        java.util.List<String> colored = new java.util.ArrayList<>(list.size());
        for (String s : list) colored.add(colorize(s));
        return colored;
    }

    public void send(Player player, String key, Object... args) {
        player.sendMessage(prefixed(key, args));
    }

    private String colorize(String s) {
        return s == null ? "" : ChatColor.translateAlternateColorCodes('&', s);
    }
}
