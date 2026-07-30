package com.rootrecord.minecraft.rootmcofficial.sync;

import com.rootrecord.minecraft.common.RootDiscordApi;
import com.rootrecord.minecraft.common.RootRecordFolders;
import com.rootrecord.minecraft.common.ShadedServiceBridge;
import com.rootrecord.minecraft.common.config.RootMcDiscordConfig;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Captures server logs continuously and uploads one file per hour via Root-Discord.
 * Gated by {@code server-log-sync.enabled} (default true).
 */
public final class OfficialHourlyLogRelay {

    private static final DateTimeFormatter TS = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
    private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneOffset.UTC);
    /** Discord attachment soft limit — keep under ~8 MiB. */
    private static final long MAX_UPLOAD_BYTES = 7L * 1024L * 1024L;

    private final JavaPlugin plugin;
    private final Object fileLock = new Object();

    private RelayConfig config;
    private Path activeLogPath;
    private BukkitTask uploadTask;
    private Handler handler;

    public OfficialHourlyLogRelay(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void reload(FileConfiguration cfg) {
        stop();
        this.config = RelayConfig.from(plugin, cfg);
        if (!config.enabled) {
            plugin.getLogger().info("RootMC-Official hourly Discord log relay disabled (server-log-sync.enabled: false).");
            return;
        }
        try {
            RootRecordFolders.ensureDir(plugin);
            activeLogPath = RootRecordFolders.configFile(plugin, "rootmc-official-hourly.log").toPath();
            Files.createDirectories(activeLogPath.getParent());
            Files.writeString(activeLogPath, "", StandardCharsets.UTF_8);

            warnBootPrereqs();

            attachHandler();
            long delayTicks = config.intervalMinutes * 60L * 20L;
            uploadTask = plugin.getServer().getScheduler().runTaskTimerAsynchronously(
                    plugin,
                    this::uploadAndRotateSafe,
                    delayTicks,
                    delayTicks);
            plugin.getLogger().info(
                    "RootMC-Official hourly Discord log relay enabled every " + config.intervalMinutes
                            + "m (uploads via Root-Discord → cloud.yml discord.channels.server-logs).");
        } catch (Exception ex) {
            plugin.getLogger().log(Level.WARNING, "Failed to start RootMC-Official hourly log relay.", ex);
            stop();
        }
    }

    private void warnBootPrereqs() {
        RootDiscordApi discord = ShadedServiceBridge.resolveDiscord(plugin);
        if (discord == null) {
            plugin.getLogger().warning(
                    "Hourly log relay is ENABLED but Root-Discord is not installed — uploads will fail until Root-Discord is present.");
            return;
        }
        if (!discord.isReady()) {
            plugin.getLogger().warning(
                    "Hourly log relay is ENABLED but Root-Discord is not ready yet (check cloud.yml discord.bot-token / guild-id).");
        }
        RootMcDiscordConfig.DiscordSettings settings = RootMcDiscordConfig.resolve(plugin);
        if (settings == null || settings.serverLogsChannelId() == null || settings.serverLogsChannelId().isBlank()) {
            plugin.getLogger().warning(
                    "Hourly log relay is ENABLED but discord.channels.server-logs is blank in plugins/RootMC/cloud.yml — uploads will be skipped.");
        }
    }

    public void stop() {
        if (uploadTask != null) {
            uploadTask.cancel();
            uploadTask = null;
        }
        if (handler != null) {
            Logger.getLogger("").removeHandler(handler);
            handler = null;
        }
    }

    private void attachHandler() {
        handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record == null || activeLogPath == null) {
                    return;
                }
                String line = formatRecord(record);
                synchronized (fileLock) {
                    try {
                        Files.writeString(activeLogPath, line, StandardCharsets.UTF_8,
                                java.nio.file.StandardOpenOption.CREATE,
                                java.nio.file.StandardOpenOption.APPEND);
                    } catch (IOException ignored) {
                        // Keep silent to avoid recursive logging loops.
                    }
                }
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        Logger.getLogger("").addHandler(handler);
    }

    private void uploadAndRotateSafe() {
        try {
            uploadAndRotate();
        } catch (Exception ex) {
            plugin.getLogger().log(Level.WARNING, "Hourly log upload failed.", ex);
        }
    }

    private void uploadAndRotate() throws IOException {
        if (activeLogPath == null) {
            return;
        }
        RootDiscordApi discord = ShadedServiceBridge.resolveDiscord(plugin);
        if (discord == null) {
            plugin.getLogger().warning(
                    "Hourly log upload skipped: Root-Discord not installed (server-log-sync.enabled is true).");
            return;
        }
        if (!discord.isReady()) {
            plugin.getLogger().warning(
                    "Hourly log upload skipped: Root-Discord not ready (bot-token / guild / login).");
            return;
        }

        Path batchFile;
        synchronized (fileLock) {
            if (!Files.exists(activeLogPath) || Files.size(activeLogPath) <= 0L) {
                plugin.getLogger().info("Hourly log upload skipped: no lines captured this interval.");
                return;
            }
            long size = Files.size(activeLogPath);
            batchFile = activeLogPath.resolveSibling("rootmc-server-log-" + FILE_TS.format(Instant.now()) + ".log");
            if (size > MAX_UPLOAD_BYTES) {
                // Keep only the last MAX_UPLOAD_BYTES for Discord attachment limits.
                byte[] all = Files.readAllBytes(activeLogPath);
                int start = (int) (all.length - MAX_UPLOAD_BYTES);
                Files.write(batchFile, java.util.Arrays.copyOfRange(all, start, all.length));
                plugin.getLogger().warning(
                        "Hourly log batch truncated to last " + MAX_UPLOAD_BYTES
                                + " bytes for Discord upload (was " + size + ").");
            } else {
                Files.move(activeLogPath, batchFile, StandardCopyOption.REPLACE_EXISTING);
            }
            Files.writeString(activeLogPath, "", StandardCharsets.UTF_8);
        }

        String label = config.serverTag.isBlank() ? "RootMC" : config.serverTag;
        String content = "[" + label + "] server logs for the last hour";
        discord.uploadServerLog(batchFile.toFile(), content);
        plugin.getLogger().info("Hourly log batch queued to Discord (" + batchFile.getFileName() + ").");
    }

    private String formatRecord(LogRecord record) {
        StringBuilder out = new StringBuilder(256);
        out.append('[')
                .append(TS.format(Instant.ofEpochMilli(record.getMillis()).atOffset(ZoneOffset.UTC)))
                .append("] [")
                .append(record.getLevel().getName())
                .append("] [")
                .append(record.getLoggerName() == null ? "server" : record.getLoggerName())
                .append("] ")
                .append(formatMessage(record))
                .append('\n');
        if (record.getThrown() != null) {
            StringWriter sw = new StringWriter();
            record.getThrown().printStackTrace(new PrintWriter(sw));
            out.append(sw).append('\n');
        }
        return out.toString();
    }

    private String formatMessage(LogRecord record) {
        try {
            return java.text.MessageFormat.format(record.getMessage(), record.getParameters());
        } catch (Exception ignored) {
            return record.getMessage() == null ? "" : record.getMessage();
        }
    }

    private record RelayConfig(boolean enabled, int intervalMinutes, String serverTag) {
        static RelayConfig from(JavaPlugin plugin, FileConfiguration cfg) {
            String tag = cfg.getString("server-log-sync.server-tag", "");
            if (tag == null) {
                tag = "";
            }
            tag = tag.trim();
            return new RelayConfig(
                    cfg.getBoolean("server-log-sync.enabled", true),
                    Math.max(1, cfg.getInt("server-log-sync.interval-minutes", 60)),
                    tag);
        }
    }
}
