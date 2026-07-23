package net.codeverse.voice.paper.voicechat;

import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.events.CreateGroupEvent;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.JoinGroupEvent;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import de.maxhenkel.voicechat.api.events.PlayerConnectedEvent;
import de.maxhenkel.voicechat.api.events.PlayerDisconnectedEvent;
import de.maxhenkel.voicechat.api.events.VoiceDistanceEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStartedEvent;
import net.codeverse.voice.config.PluginConfig;
import net.codeverse.voice.lang.LangManager;
import net.codeverse.voice.model.VoiceState;
import net.codeverse.voice.paper.moderation.MonitorService;
import net.codeverse.voice.paper.moderation.RecordingService;
import net.codeverse.voice.moderation.VoiceBanService;
import net.codeverse.voice.paper.notify.NotificationService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.slf4j.Logger;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bridges Simple Voice Chat's event system to the moderation services.
 *
 * The microphone handler runs roughly fifty times a second for every speaker,
 * so everything it touches is either cached or in memory. No database or Redis
 * call happens on that path.
 */
public final class CodeverseVoicechatPlugin implements VoicechatPlugin {

    public static final String PLUGIN_ID = "codeverse-voice";

    private final PluginConfig config;
    private final VoiceBanService bans;
    private final RecordingService recordings;
    private final MonitorService monitors;
    private final NotificationService notifications;
    private final LangManager lang;
    private final Logger logger;

    /** Last time each speaker was told why they cannot talk. */
    private final Map<UUID, Long> denialNotices = new ConcurrentHashMap<>();

    private volatile VoicechatServerApi serverApi;

    public CodeverseVoicechatPlugin(PluginConfig config,
                                    VoiceBanService bans,
                                    RecordingService recordings,
                                    MonitorService monitors,
                                    NotificationService notifications,
                                    LangManager lang,
                                    Logger logger) {
        this.config = config;
        this.bans = bans;
        this.recordings = recordings;
        this.monitors = monitors;
        this.notifications = notifications;
        this.lang = lang;
        this.logger = logger;
    }

    @Override
    public String getPluginId() {
        return PLUGIN_ID;
    }

    @Override
    public void initialize(VoicechatApi api) {
        recordings.attachApi(api);
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(VoicechatServerStartedEvent.class, this::onServerStarted);
        registration.registerEvent(MicrophonePacketEvent.class, this::onMicrophonePacket);
        registration.registerEvent(VoiceDistanceEvent.class, this::onVoiceDistance);
        registration.registerEvent(PlayerConnectedEvent.class, this::onPlayerConnected);
        registration.registerEvent(PlayerDisconnectedEvent.class, this::onPlayerDisconnected);
        registration.registerEvent(CreateGroupEvent.class, this::onCreateGroup);
        registration.registerEvent(JoinGroupEvent.class, this::onJoinGroup);
    }

    private void onServerStarted(VoicechatServerStartedEvent event) {
        serverApi = event.getVoicechat();
        monitors.attachApi(serverApi);
        logger.info("Voice chat server started, moderation active");
    }

    public VoicechatServerApi serverApi() {
        return serverApi;
    }

    /**
     * The enforcement point. A cancelled packet never reaches any listener, so
     * a restriction holds regardless of what the speaker's client believes
     * about its own mute state.
     */
    private void onMicrophonePacket(MicrophonePacketEvent event) {
        VoicechatConnection sender = event.getSenderConnection();
        if (sender == null) {
            return;
        }
        UUID speakerId = sender.getPlayer().getUuid();

        VoiceState state = bans.evaluate(speakerId, permission -> hasPermission(speakerId, permission));
        if (!state.allowed()) {
            event.cancel();
            notifyDenied(speakerId, state);
            return;
        }

        byte[] audio = event.getPacket().getOpusEncodedData();
        if (audio != null && audio.length > 0) {
            recordings.record(speakerId, audio);
        }
    }

    /** Applies a per permission proximity range when configured. */
    private void onVoiceDistance(VoiceDistanceEvent event) {
        if (!config.ranges.enabled || config.ranges.rules == null) {
            return;
        }
        VoicechatConnection sender = event.getSenderConnection();
        if (sender == null) {
            return;
        }
        UUID speakerId = sender.getPlayer().getUuid();
        for (PluginConfig.RangeRule rule : config.ranges.rules) {
            if (hasPermission(speakerId, rule.permission)) {
                event.setDistance(rule.distance);
                return;
            }
        }
    }

    private void onPlayerConnected(PlayerConnectedEvent event) {
        VoicechatConnection connection = event.getConnection();
        if (connection == null) {
            return;
        }
        UUID playerId = connection.getPlayer().getUuid();

        if (config.recording.enabled && config.recording.noticeOnConnect) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null) {
                player.sendMessage(lang.get("voice.recording-notice", localeOf(player)));
            }
        }

        VoiceState state = bans.evaluate(playerId, permission -> hasPermission(playerId, permission));
        if (!state.allowed()) {
            notifyDenied(playerId, state);
        }
    }

    private void onPlayerDisconnected(PlayerDisconnectedEvent event) {
        UUID playerId = event.getPlayerUuid();
        if (playerId == null) {
            return;
        }
        // Buffered audio is dropped with the session rather than lingering in
        // memory for someone who is no longer connected.
        recordings.forget(playerId);
        denialNotices.remove(playerId);
        monitors.stop(playerId, "staff member disconnected");
    }

    private void onCreateGroup(CreateGroupEvent event) {
        VoicechatConnection connection = event.getConnection();
        if (connection == null || event.getGroup() == null) {
            return;
        }
        UUID playerId = connection.getPlayer().getUuid();

        if (config.groups.requirePermissionToCreate && !hasPermission(playerId, config.groups.createPermission)) {
            event.cancel();
            sendMessage(playerId, "group.denied.create");
            return;
        }
        String name = event.getGroup().getName();
        if (name == null) {
            return;
        }
        if (name.length() > config.groups.maximumNameLength) {
            event.cancel();
            sendMessage(playerId, "group.denied.name-too-long");
            return;
        }
        String lowered = name.toLowerCase(Locale.ROOT);
        for (String blocked : config.groups.blockedNameFragments) {
            if (!blocked.isBlank() && lowered.contains(blocked.toLowerCase(Locale.ROOT))) {
                event.cancel();
                sendMessage(playerId, "group.denied.name-blocked");
                return;
            }
        }
    }

    private void onJoinGroup(JoinGroupEvent event) {
        VoicechatConnection connection = event.getConnection();
        if (connection == null) {
            return;
        }
        UUID playerId = connection.getPlayer().getUuid();

        // A restricted speaker may still listen, but joining a group while
        // restricted invites confusion about why nobody can hear them.
        VoiceState state = bans.evaluate(playerId, permission -> hasPermission(playerId, permission));
        if (state == VoiceState.BANNED) {
            event.cancel();
            sendMessage(playerId, "group.denied.restricted");
            return;
        }
        if (config.groups.requirePermissionToJoin && !hasPermission(playerId, config.groups.joinPermission)) {
            event.cancel();
            sendMessage(playerId, "group.denied.join");
        }
    }

    /** Rate limited so a restricted speaker is told once, not fifty times a second. */
    private void notifyDenied(UUID playerId, VoiceState state) {
        long now = System.currentTimeMillis();
        long cooldown = config.access.denialNoticeCooldownSeconds * 1000L;
        Long last = denialNotices.get(playerId);
        if (last != null && now - last < cooldown) {
            return;
        }
        denialNotices.put(playerId, now);
        Player player = Bukkit.getPlayer(playerId);
        if (player != null) {
            notifications.sendDenial(player, state.messageKey());
        }
    }

    private void sendMessage(UUID playerId, String key) {
        Player player = Bukkit.getPlayer(playerId);
        if (player != null) {
            player.sendMessage(lang.get(key, localeOf(player)));
        }
    }

    private boolean hasPermission(UUID playerId, String permission) {
        Player player = Bukkit.getPlayer(playerId);
        return player != null && player.hasPermission(permission);
    }

    private static Locale localeOf(Player player) {
        try {
            return player.locale();
        } catch (RuntimeException unavailable) {
            return null;
        }
    }
}
