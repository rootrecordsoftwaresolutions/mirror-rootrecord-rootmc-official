package com.rootrecord.minecraft.rootmcofficial;

import com.rootrecord.minecraft.common.RootDiscordSupport;
import com.rootrecord.minecraft.common.RootRecordFolders;
import com.rootrecord.minecraft.common.config.RootMcDatabaseConfig;
import com.rootrecord.minecraft.common.config.RootRecordYamlConfig;
import com.rootrecord.minecraft.common.connection.RootMcCoreConnection;
import com.rootrecord.minecraft.rootmcofficial.command.OfficialCommand;
import com.rootrecord.minecraft.rootmcofficial.config.OfficialConfig;
import com.rootrecord.minecraft.rootmcofficial.sync.OfficialHourlyLogRelay;
import com.rootrecord.minecraft.rootmcofficial.listener.OfficialJoinListener;
import com.rootrecord.minecraft.rootmcofficial.sync.ProgressionSyncService;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.plugin.java.JavaPlugin;
import com.rootrecord.minecraft.common.bstats.Metrics;
import com.rootrecord.minecraft.common.bstats.RootBStats;

public final class RootMcOfficialPlugin extends JavaPlugin {

    private Metrics metrics;

    private RootRecordYamlConfig yaml;
    private OfficialConfig config;
    private ProgressionSyncService sync;
    private BukkitTask syncTask;
    private OfficialHourlyLogRelay hourlyLogRelay;

    @Override
    public void onEnable() {
        metrics = RootBStats.start(this);
        RootRecordFolders.ensureDir(this);
        RootMcDatabaseConfig.ensureDefaults(this);
        if (getServer().getPluginManager().getPlugin("Root-Core") == null) {
            var repair = RootMcCoreConnection.ensureAndRepair(this);
            getLogger().warning(
                    "Root-Core not present — connection fallback (databaseOk="
                            + repair.databaseOk()
                            + ").");
        }

        yaml = new RootRecordYamlConfig(this, RootRecordFolders.ROOTMC_OFFICIAL_CONFIG, "rootmc-official.yml");
        yaml.load();
        config = OfficialConfig.from(this, yaml.config());

        OfficialCommand cmd = new OfficialCommand(this);
        var root = getCommand("rootmcofficial");
        if (root != null) {
            root.setExecutor(cmd);
            root.setTabCompleter(cmd);
        }

        if (!config.enabled()) {
            getLogger().info("RootMC-Official disabled (rootmc-official.yml enabled: false).");
            return;
        }
        RootDiscordSupport.warnIfMissing(this, "hourly server-log upload");
        hourlyLogRelay = new OfficialHourlyLogRelay(this);
        hourlyLogRelay.reload(yaml.config());
        if (!config.ready()) {
            getLogger().warning(
                    "RootMC-Official enabled but peer/local MySQL incomplete — sync idle until configured.");
            return;
        }

        sync = new ProgressionSyncService(this, config);
        getServer().getPluginManager().registerEvents(new OfficialJoinListener(this), this);
        startSyncTask();
        getLogger().info(
                "RootMC-Official enabled — role="
                        + config.role()
                        + " peer="
                        + config.peer().host()
                        + "/"
                        + config.peer().database()
                        + " interval="
                        + config.intervalSeconds()
                        + "s");
    }

    @Override
    public void onDisable() {
        RootBStats.shutdown(metrics);
        if (syncTask != null) {
            syncTask.cancel();
            syncTask = null;
        }
        if (hourlyLogRelay != null) {
            hourlyLogRelay.stop();
            hourlyLogRelay = null;
        }
    }

    public void reloadAll() {
        if (yaml != null) {
            yaml.reload();
        }
        config = OfficialConfig.from(this, yaml.config());
        if (hourlyLogRelay != null) {
            hourlyLogRelay.stop();
        }
        if (config.enabled()) {
            if (hourlyLogRelay == null) {
                hourlyLogRelay = new OfficialHourlyLogRelay(this);
            }
            hourlyLogRelay.reload(yaml.config());
        } else {
            hourlyLogRelay = null;
        }
        if (sync == null) {
            sync = new ProgressionSyncService(this, config);
        } else {
            sync.reload(config);
        }
        startSyncTask();
    }

    private void startSyncTask() {
        if (syncTask != null) {
            syncTask.cancel();
            syncTask = null;
        }
        if (config == null || !config.ready() || sync == null) {
            return;
        }
        long ticks = config.intervalSeconds() * 20L;
        syncTask = getServer().getScheduler().runTaskTimerAsynchronously(this, () -> sync.syncNow(null), 100L, ticks);
    }

    public OfficialConfig config() {
        return config;
    }

    public ProgressionSyncService sync() {
        return sync;
    }
}
