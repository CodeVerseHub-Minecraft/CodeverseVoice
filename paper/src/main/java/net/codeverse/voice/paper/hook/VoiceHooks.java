package net.codeverse.voice.paper.hook;

import java.util.Optional;
import java.util.UUID;

/**
 * Everything the rest of the plugin needs from the voice chat integration,
 * expressed without naming a single Simple Voice Chat type.
 *
 * This boundary exists for a concrete reason rather than as tidiness. The same
 * plugin runs on servers that host voice and on lobbies that only display
 * status, and on the latter Simple Voice Chat is not installed at all. If any
 * class the plugin loads at startup mentions an SVC type directly, the JVM
 * cannot resolve it and the whole plugin fails to load, taking the placeholders
 * and commands with it.
 *
 * Keeping every SVC reference behind this interface means the implementation is
 * only ever loaded once Simple Voice Chat is known to be present.
 */
public interface VoiceHooks {

    /** A no op used on servers that do not host voice chat. */
    VoiceHooks ABSENT = new VoiceHooks() {
    };

    default boolean isPresent() {
        return false;
    }

    default boolean recordingEnabled() {
        return false;
    }

    default boolean monitoringEnabled() {
        return false;
    }

    default long recordingMegabytes() {
        return 0L;
    }

    default int activeMonitorCount() {
        return 0;
    }

    default boolean isMonitoring(UUID staffMinecraftId) {
        return false;
    }

    /** @return the file name written, or empty when nothing was buffered */
    default Optional<String> capture(UUID targetMinecraftId, UUID targetInternalId, UUID capturedBy, String note) {
        return Optional.empty();
    }

    /** @return outcome of trying to begin a monitoring session */
    default MonitorOutcome startMonitor(UUID staffMinecraftId, UUID staffInternalId,
                                        UUID targetMinecraftId, UUID targetInternalId, String targetName) {
        return MonitorOutcome.UNAVAILABLE;
    }

    default boolean stopMonitor(UUID staffMinecraftId, String reason) {
        return false;
    }

    default MonitorOutcome extendMonitor(UUID staffMinecraftId) {
        return MonitorOutcome.UNAVAILABLE;
    }

    /** Drops buffered audio and any session belonging to someone who left. */
    default void forgetSession(UUID minecraftId) {
    }

    default void shutdown() {
    }

    /** Runs the periodic monitoring sweep, returning messages the caller should deliver. */
    default void tick() {
    }

    enum MonitorOutcome {
        STARTED,
        STARTED_WITHOUT_AUDIT,
        ALREADY_ACTIVE,
        EXTENSION_LIMIT,
        NOT_MONITORING,
        UNAVAILABLE
    }
}
