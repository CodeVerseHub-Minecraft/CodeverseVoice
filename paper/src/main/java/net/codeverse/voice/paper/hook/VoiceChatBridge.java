package net.codeverse.voice.paper.hook;

import de.maxhenkel.voicechat.api.BukkitVoicechatService;
import net.codeverse.voice.config.PluginConfig;
import net.codeverse.voice.lang.LangManager;
import net.codeverse.voice.moderation.VoiceBanService;
import net.codeverse.voice.paper.moderation.MonitorService;
import net.codeverse.voice.paper.moderation.RecordingService;
import net.codeverse.voice.paper.notify.NotificationService;
import net.codeverse.voice.paper.voicechat.CodeverseVoicechatPlugin;
import net.codeverse.voice.storage.VoiceBanRepository;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

/**
 * The only class in the plugin that names a Simple Voice Chat type.
 *
 * Loaded exclusively through {@link #install}, which the caller invokes only
 * after confirming Simple Voice Chat is installed. Because the JVM resolves a
 * class at the first instruction that uses it, never reaching that call means
 * this class is never loaded, and a lobby without voice chat starts normally.
 */
public final class VoiceChatBridge implements VoiceHooks {

    private final PluginConfig config;
    private final LangManager lang;
    private final Logger logger;
    private final RecordingService recordings;
    private final MonitorService monitors;

    private VoiceChatBridge(PluginConfig config,
                            LangManager lang,
                            Logger logger,
                            RecordingService recordings,
                            MonitorService monitors) {
        this.config = config;
        this.lang = lang;
        this.logger = logger;
        this.recordings = recordings;
        this.monitors = monitors;
    }

    /**
     * Builds the bridge and registers with Simple Voice Chat.
     *
     * @return the live hooks, or {@link VoiceHooks#ABSENT} when the service is
     *         unavailable despite the plugin being installed
     */
    public static VoiceHooks install(Plugin plugin,
                                     PluginConfig config,
                                     VoiceBanRepository repository,
                                     VoiceBanService bans,
                                     NotificationService notifications,
                                     LangManager lang,
                                     Path dataDirectory,
                                     Logger logger) throws IOException {
        BukkitVoicechatService service =
                plugin.getServer().getServicesManager().load(BukkitVoicechatService.class);
        if (service == null) {
            logger.warn("Simple Voice Chat is installed but its service is unavailable, so enforcement is "
                    + "inactive. Restrictions are still recorded and shown.");
            return VoiceHooks.ABSENT;
        }

        RecordingService recordings = new RecordingService(config.recording, repository, dataDirectory);
        MonitorService monitors = new MonitorService(config.monitoring, repository);

        service.registerPlugin(new CodeverseVoicechatPlugin(
                config, bans, recordings, monitors, notifications, lang, logger));

        return new VoiceChatBridge(config, lang, logger, recordings, monitors);
    }

    @Override
    public boolean isPresent() {
        return true;
    }

    @Override
    public boolean recordingEnabled() {
        return recordings.isEnabled();
    }

    @Override
    public boolean monitoringEnabled() {
        return monitors.isEnabled();
    }

    @Override
    public long recordingMegabytes() {
        return recordings.directoryMegabytes();
    }

    @Override
    public int activeMonitorCount() {
        return monitors.activeSessionCount();
    }

    @Override
    public boolean isMonitoring(UUID staffMinecraftId) {
        return monitors.isMonitoring(staffMinecraftId);
    }

    @Override
    public Optional<String> capture(UUID targetMinecraftId, UUID targetInternalId, UUID capturedBy, String note) {
        try {
            return recordings.capture(targetMinecraftId, targetInternalId, capturedBy, note);
        } catch (Exception failure) {
            logger.error("Failed to capture voice audio", failure);
            return Optional.empty();
        }
    }

    @Override
    public MonitorOutcome startMonitor(UUID staffMinecraftId, UUID staffInternalId,
                                       UUID targetMinecraftId, UUID targetInternalId, String targetName) {
        Optional<MonitorService.Session> session = monitors.start(
                staffMinecraftId, staffInternalId, targetMinecraftId, targetInternalId, targetName, packet -> {
                });
        if (session.isEmpty()) {
            return MonitorOutcome.ALREADY_ACTIVE;
        }
        return session.get().auditFailed() ? MonitorOutcome.STARTED_WITHOUT_AUDIT : MonitorOutcome.STARTED;
    }

    @Override
    public boolean stopMonitor(UUID staffMinecraftId, String reason) {
        return monitors.stop(staffMinecraftId, reason).isPresent();
    }

    @Override
    public MonitorOutcome extendMonitor(UUID staffMinecraftId) {
        if (monitors.extend(staffMinecraftId).isPresent()) {
            return MonitorOutcome.STARTED;
        }
        return monitors.isMonitoring(staffMinecraftId)
                ? MonitorOutcome.EXTENSION_LIMIT
                : MonitorOutcome.NOT_MONITORING;
    }

    @Override
    public void forgetSession(UUID minecraftId) {
        recordings.forget(minecraftId);
        monitors.stop(minecraftId, "player disconnected");
    }

    /** Ends lapsed sessions, warns those close to the limit, and shows the countdown. */
    @Override
    public void tick() {
        MonitorService.Sweep sweep = monitors.sweep();
        for (MonitorService.Session session : sweep.warned()) {
            Player staff = Bukkit.getPlayer(session.staffMinecraftId());
            if (staff != null) {
                staff.sendMessage(lang.get("monitor.ending-soon", staff.locale(),
                        "seconds", String.valueOf(config.monitoring.warningBeforeStopSeconds)));
            }
        }
        for (MonitorService.Session session : sweep.stopped()) {
            Player staff = Bukkit.getPlayer(session.staffMinecraftId());
            if (staff != null) {
                staff.sendMessage(lang.get("monitor.auto-stopped", staff.locale()));
            }
        }
        if (!config.monitoring.showCountdown) {
            return;
        }
        for (MonitorService.Session session : monitors.activeSessions()) {
            Player staff = Bukkit.getPlayer(session.staffMinecraftId());
            if (staff != null) {
                staff.sendActionBar(lang.get("monitor.countdown", staff.locale(),
                        "name", session.targetName(),
                        "seconds", String.valueOf(session.remainingMillis() / 1000L)));
            }
        }
    }

    @Override
    public void shutdown() {
        monitors.stopAll();
        recordings.forgetAll();
    }

    public int purgeExpiredCaptures() {
        return recordings.purgeExpiredCaptures();
    }
}
