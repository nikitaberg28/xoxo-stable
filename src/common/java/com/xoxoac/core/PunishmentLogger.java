package com.xoxoac.core;

import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * Writes one JSON-lines file per calendar day under plugins/xoxo-AntiCheat/logs/, e.g.
 * logs/28_07_2026.json — so punishments (kicks, bans, autobans) always have a durable, reviewable
 * record even with console alerting turned off (see ViolationManager's console-logging toggle,
 * which now defaults OFF — this file is the permanent record either way).
 *
 * Format is JSON Lines (one compact JSON object per line) rather than a single JSON array, since
 * that lets the file be appended to safely without re-parsing/rewriting the whole day's log on
 * every punishment, and remains trivially parseable by any JSON-lines-aware tool or a simple
 * per-line JSON.parse in a script.
 */
public final class PunishmentLogger {

    private final Plugin plugin;
    private final Path logsDir;
    private static final DateTimeFormatter FILE_DATE_FORMAT = DateTimeFormatter.ofPattern("dd_MM_yyyy");
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    public PunishmentLogger(Plugin plugin) {
        this.plugin = plugin;
        this.logsDir = plugin.getDataFolder().toPath().resolve("logs");
    }

    /**
     * Appends one punishment record as a JSON line to today's file. Safe to call from any thread
     * (uses simple synchronized file append) but in practice is always called from the main
     * thread via ViolationManager, which already runs on it.
     */
    public synchronized void logPunishment(String playerName, UUID uuid, String module, String details,
                                            String punishmentCommand, int violationLevel, int maxViolationLevel) {
        try {
            Files.createDirectories(logsDir);
            Path file = logsDir.resolve(LocalDate.now().format(FILE_DATE_FORMAT) + ".json");

            String json = "{"
                    + "\"timestamp\":\"" + LocalDateTime.now().format(TIMESTAMP_FORMAT) + "\","
                    + "\"player\":\"" + escape(playerName) + "\","
                    + "\"uuid\":\"" + uuid + "\","
                    + "\"module\":\"" + escape(module) + "\","
                    + "\"details\":\"" + escape(details) + "\","
                    + "\"vl\":" + violationLevel + ","
                    + "\"maxVl\":" + maxViolationLevel + ","
                    + "\"punishmentCommand\":\"" + escape(punishmentCommand) + "\""
                    + "}";

            try (Writer writer = Files.newBufferedWriter(
                    file, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                writer.write(json);
                writer.write(System.lineSeparator());
            }
        } catch (IOException e) {
            plugin.getLogger().warning("[xoxo-AC] Failed to write punishment log: " + e.getMessage());
        }
    }

    private String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }
}
