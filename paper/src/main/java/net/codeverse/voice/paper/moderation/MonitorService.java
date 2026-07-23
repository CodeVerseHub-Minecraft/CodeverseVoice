package net.codeverse.voice.paper.moderation;

import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.audiolistener.AudioListener;
import net.codeverse.voice.config.PluginConfig;
import net.codeverse.voice.storage.VoiceBanRepository;

import java.sql.SQLException;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Lets a staff member hear what a reported player hears, for a bounded time.
 *
 * Three constraints are structural rather than configurable in spirit, and
 * removing any of them turns a moderation tool into surveillance.
 *
 * A session always ends by itself. Anything that must be remembered to be
 * stopped will eventually be forgotten while still running.
 *
 * Every session is written to the audit trail when it starts and when it ends.
 * Without a record there is no way to distinguish moderation from a staff
 * member listening to someone they have a personal interest in.
 *
 * Other staff are told when a session begins. Oversight that only exists in a
 * database nobody reads is not oversight.
 */
public final class MonitorService {

    private final PluginConfig.Monitoring settings;
    private final VoiceBanRepository repository;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private volatile VoicechatServerApi api;

    public MonitorService(PluginConfig.Monitoring settings, VoiceBanRepository repository) {
        this.settings = settings;
        this.repository = repository;
    }

    public void attachApi(VoicechatServerApi api) {
        this.api = api;
    }

    public boolean isEnabled() {
        return settings.enabled;
    }

    public boolean isMonitoring(UUID staffMinecraftId) {
        return sessions.containsKey(staffMinecraftId);
    }

    public Optional<Session> session(UUID staffMinecraftId) {
        return Optional.ofNullable(sessions.get(staffMinecraftId));
    }

    public int activeSessionCount() {
        return sessions.size();
    }

    /**
     * Starts listening to everything the target would hear.
     *
     * @param onPacket receives each sound packet the target would receive
     * @return the session, or empty when the api is unavailable or a session
     *         for this staff member already exists
     */
    public Optional<Session> start(UUID staffMinecraftId,
                                   UUID staffInternalId,
                                   UUID targetMinecraftId,
                                   UUID targetInternalId,
                                   String targetName,
                                   Consumer<de.maxhenkel.voicechat.api.packets.SoundPacket> onPacket) {
        if (!settings.enabled) {
            return Optional.empty();
        }
        VoicechatServerApi localApi = api;
        if (localApi == null || sessions.containsKey(staffMinecraftId)) {
            return Optional.empty();
        }

        AudioListener listener = localApi.playerAudioListenerBuilder()
                .setPlayer(targetMinecraftId)
                .setPacketListener(onPacket::accept)
                .build();

        if (!localApi.registerAudioListener(listener)) {
            return Optional.empty();
        }

        long now = System.currentTimeMillis();
        Session session = new Session(
                staffMinecraftId,
                staffInternalId,
                targetMinecraftId,
                targetInternalId,
                targetName,
                listener.getListenerId(),
                now,
                now + settings.automaticStopSeconds * 1000L);
        sessions.put(staffMinecraftId, session);

        try {
            repository.audit(staffInternalId, targetInternalId, "MONITOR_START",
                    "listening to " + targetName + " for up to " + settings.automaticStopSeconds + "s");
        } catch (SQLException failure) {
            // The session continues, because failing to log must not deny
            // moderation, but the caller is told so it can surface the gap.
            session.markAuditFailed();
        }

        return Optional.of(session);
    }

    /** Ends a session and unregisters the listener. Safe to call when none exists. */
    public Optional<Session> stop(UUID staffMinecraftId, String reason) {
        Session session = sessions.remove(staffMinecraftId);
        if (session == null) {
            return Optional.empty();
        }
        VoicechatServerApi localApi = api;
        if (localApi != null) {
            localApi.unregisterAudioListener(session.listenerId());
        }
        try {
            repository.audit(session.staffInternalId(), session.targetInternalId(), "MONITOR_STOP",
                    reason + " after " + (System.currentTimeMillis() - session.startedAt()) + "ms");
        } catch (SQLException ignored) {
            // Already stopped; a missing stop record is recoverable from the
            // start record and the next sweep.
        }
        return Optional.of(session);
    }

    /**
     * Ends sessions that have reached their limit, and reports which are close
     * enough to warrant a warning so the caller can prompt for an extension.
     */
    public Sweep sweep() {
        long now = System.currentTimeMillis();
        java.util.List<Session> expired = new java.util.ArrayList<>();
        java.util.List<Session> warning = new java.util.ArrayList<>();

        for (Session session : sessions.values()) {
            if (now >= session.expiresAt()) {
                expired.add(session);
            } else if (!session.warned()
                    && now >= session.expiresAt() - settings.warningBeforeStopSeconds * 1000L) {
                session.markWarned();
                warning.add(session);
            }
        }
        for (Session session : expired) {
            stop(session.staffMinecraftId(), "time limit reached");
        }
        return new Sweep(expired, warning);
    }

    /**
     * Extends a running session, up to the configured number of times.
     *
     * The cap exists so that a session cannot be kept alive indefinitely by
     * repeatedly extending it, which would reintroduce exactly the open ended
     * listening the automatic stop is there to prevent.
     */
    public Optional<Session> extend(UUID staffMinecraftId) {
        Session session = sessions.get(staffMinecraftId);
        if (session == null) {
            return Optional.empty();
        }
        if (session.extensions() >= settings.maximumExtensions) {
            return Optional.empty();
        }
        session.extend(settings.automaticStopSeconds * 1000L);
        return Optional.of(session);
    }

    /** Snapshot of running sessions, used for the countdown display. */
    public java.util.Collection<Session> activeSessions() {
        return java.util.List.copyOf(sessions.values());
    }

    /** Ends every session, used on shutdown so no listener outlives the plugin. */
    public void stopAll() {
        for (UUID staffId : java.util.Set.copyOf(sessions.keySet())) {
            stop(staffId, "plugin shutting down");
        }
    }

    public record Sweep(java.util.List<Session> stopped, java.util.List<Session> warned) {
    }

    /** One staff member listening to one target. */
    public static final class Session {
        private final UUID staffMinecraftId;
        private final UUID staffInternalId;
        private final UUID targetMinecraftId;
        private final UUID targetInternalId;
        private final String targetName;
        private final UUID listenerId;
        private final long startedAt;
        private volatile long expiresAt;
        private volatile boolean warned;
        private volatile boolean auditFailed;
        private volatile int extensions;

        Session(UUID staffMinecraftId, UUID staffInternalId, UUID targetMinecraftId, UUID targetInternalId,
                String targetName, UUID listenerId, long startedAt, long expiresAt) {
            this.staffMinecraftId = staffMinecraftId;
            this.staffInternalId = staffInternalId;
            this.targetMinecraftId = targetMinecraftId;
            this.targetInternalId = targetInternalId;
            this.targetName = targetName;
            this.listenerId = listenerId;
            this.startedAt = startedAt;
            this.expiresAt = expiresAt;
        }

        public UUID staffMinecraftId() {
            return staffMinecraftId;
        }

        public UUID staffInternalId() {
            return staffInternalId;
        }

        public UUID targetMinecraftId() {
            return targetMinecraftId;
        }

        public UUID targetInternalId() {
            return targetInternalId;
        }

        public String targetName() {
            return targetName;
        }

        public UUID listenerId() {
            return listenerId;
        }

        public long startedAt() {
            return startedAt;
        }

        public long expiresAt() {
            return expiresAt;
        }

        public long remainingMillis() {
            return Math.max(0L, expiresAt - System.currentTimeMillis());
        }

        public boolean warned() {
            return warned;
        }

        void markWarned() {
            this.warned = true;
        }

        void extend(long millis) {
            this.expiresAt = System.currentTimeMillis() + millis;
            this.warned = false;
            this.extensions++;
        }

        public int extensions() {
            return extensions;
        }

        public boolean auditFailed() {
            return auditFailed;
        }

        void markAuditFailed() {
            this.auditFailed = true;
        }
    }
}
