package com.rootrecord.minecraft.rootmcofficial.sync;

import com.rootrecord.minecraft.common.RootRecordFolders;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.utils.FileUpload;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
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

/** Captures server logs continuously and uploads one file per hour to Discord. */
public final class OfficialHourlyLogRelay {

    private static final DateTimeFormatter TS = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
    private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneOffset.UTC);

    private final JavaPlugin plugin;
    private final Object fileLock = new Object();

    private RelayConfig config;
    private Path activeLogPath;
    private BukkitTask uploadTask;
    private Handler handler;
    private JDA jda;

    public OfficialHourlyLogRelay(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void reload(FileConfiguration cfg) {
        stop();
        this.config = RelayConfig.from(plugin, cfg);
        if (!config.enabled) {
            plugin.getLogger().info("RootMC-Official hourly log relay disabled.");
            return;
        }
        if (config.botToken.isBlank() || config.guildId.isBlank() || config.channelId.isBlank()) {
            plugin.getLogger().warning(
                    "RootMC-Official hourly log relay missing bot-token/guild-id/channel-id; check plugins/RootMC/cloud.yml discord.* (and optional server-log-sync.channel-id).");
            return;
        }
        try {
            RootRecordFolders.ensureDir(plugin);
            activeLogPath = RootRecordFolders.configFile(plugin, "rootmc-official-hourly.log").toPath();
            Files.createDirectories(activeLogPath.getParent());
            Files.writeString(activeLogPath, "", StandardCharsets.UTF_8);

            attachHandler();
            jda = JDABuilder.createDefault(config.botToken).build().awaitReady();
            uploadTask = plugin.getServer().getScheduler().runTaskTimerAsynchronously(
                    plugin,
                    this::uploadAndRotateSafe,
                    config.intervalMinutes * 60L * 20L,
                    config.intervalMinutes * 60L * 20L);
            plugin.getLogger().info(
                    "RootMC-Official hourly log relay enabled → channel "
                            + config.channelId + " every " + config.intervalMinutes + "m.");
        } catch (Exception ex) {
            plugin.getLogger().log(Level.WARNING, "Failed to start RootMC-Official hourly log relay.", ex);
            stop();
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
        if (jda != null) {
            jda.shutdownNow();
            jda = null;
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
        if (jda == null || activeLogPath == null) {
            return;
        }
        TextChannel channel = jda.getTextChannelById(config.channelId);
        if (channel == null || !channel.getGuild().getId().equals(config.guildId)) {
            plugin.getLogger().warning("Hourly log relay channel/guild mismatch; skipping upload.");
            return;
        }

        Path batchFile;
        synchronized (fileLock) {
            if (!Files.exists(activeLogPath) || Files.size(activeLogPath) <= 0L) {
                return;
            }
            batchFile = activeLogPath.resolveSibling("rootmc-server-log-" + FILE_TS.format(Instant.now()) + ".log");
            Files.move(activeLogPath, batchFile, StandardCopyOption.REPLACE_EXISTING);
            Files.writeString(activeLogPath, "", StandardCharsets.UTF_8);
        }

        String label = config.serverTag.isBlank() ? "RootMC" : config.serverTag;
        String content = "[" + label + "] server logs for the last hour";
        channel.sendMessage(content)
                .addFiles(FileUpload.fromData(batchFile.toFile()))
                .queue(
                        ok -> {
                            try {
                                Files.deleteIfExists(batchFile);
                            } catch (IOException ignored) {
                                // keep if delete fails
                            }
                        },
                        err -> plugin.getLogger().warning(
                                "Failed to send hourly log file to Discord: " + err.getMessage()));
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

    private record RelayConfig(
            boolean enabled,
            int intervalMinutes,
            String channelId,
            String serverTag,
            String botToken,
            String guildId) {
        static RelayConfig from(JavaPlugin plugin, FileConfiguration cfg) {
            com.rootrecord.minecraft.common.config.RootMcDiscordConfig.DiscordSettings discord =
                    com.rootrecord.minecraft.common.config.RootMcDiscordConfig.resolve(plugin);
            String fromOfficial = cfg.getString("server-log-sync.channel-id", "");
            String channelId = fromOfficial != null && !fromOfficial.isBlank()
                    ? fromOfficial.trim()
                    : discord.serverLogsChannelId();
            return new RelayConfig(
                    cfg.getBoolean("server-log-sync.enabled", false),
                    Math.max(1, cfg.getInt("server-log-sync.interval-minutes", 60)),
                    channelId,
                    cfg.getString("server-log-sync.server-tag", "").trim(),
                    discord.botToken(),
                    discord.guildId());
        }
    }
}
