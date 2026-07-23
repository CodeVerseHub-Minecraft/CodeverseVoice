package net.codeverse.voice.sync;

import io.lettuce.core.RedisClient;
import io.lettuce.core.pubsub.RedisPubSubAdapter;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import net.codeverse.voice.config.PluginConfig;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * Propagates moderation actions between servers over Redis.
 *
 * Voice itself runs on one server, but the decision to restrict someone is
 * made wherever staff happen to be. Without this, a mute issued from the lobby
 * would not apply until the voice server happened to reload its cache.
 *
 * Messages are deliberately thin: an action and an identity, never the reason
 * or the audio. Receivers drop their cached decision and read the authoritative
 * row from the database, so a lost or duplicated message cannot leave two
 * servers disagreeing about whether someone is restricted.
 *
 * The instance id in each message lets a publisher ignore its own broadcast.
 */
public final class VoiceSync implements AutoCloseable {

    private static final String SEPARATOR = ":";

    private final PluginConfig.Redis settings;
    private final UUID instanceId = UUID.randomUUID();
    private RedisClient client;
    private StatefulRedisPubSubConnection<String, String> subscriber;
    private StatefulRedisPubSubConnection<String, String> publisher;
    private volatile boolean healthy;

    public VoiceSync(PluginConfig.Redis settings) {
        this.settings = settings;
    }

    /**
     * Connects and begins listening.
     *
     * @param onInvalidate called with the identity whose cached decision is stale
     * @return true when connected, false when disabled or unreachable
     */
    public boolean start(Consumer<UUID> onInvalidate, Runnable onInvalidateAll) {
        if (!settings.enabled) {
            return false;
        }
        try {
            client = RedisClient.create(settings.uri);
            subscriber = client.connectPubSub();
            publisher = client.connectPubSub();

            subscriber.addListener(new RedisPubSubAdapter<>() {
                @Override
                public void message(String channel, String message) {
                    handle(message, onInvalidate, onInvalidateAll);
                }
            });
            subscriber.sync().subscribe(settings.channel);
            healthy = true;
            return true;
        } catch (RuntimeException failure) {
            healthy = false;
            closeQuietly();
            return false;
        }
    }

    private void handle(String message, Consumer<UUID> onInvalidate, Runnable onInvalidateAll) {
        String[] parts = message.split(SEPARATOR, 3);
        if (parts.length < 2) {
            return;
        }
        // Our own broadcast comes back to us; acting on it would be harmless
        // but wasteful, so it is skipped.
        if (instanceId.toString().equals(parts[0])) {
            return;
        }
        String action = parts[1];
        if ("all".equals(action)) {
            onInvalidateAll.run();
            return;
        }
        if (parts.length < 3) {
            return;
        }
        try {
            onInvalidate.accept(UUID.fromString(parts[2]));
        } catch (IllegalArgumentException malformed) {
            // A malformed identity is dropped rather than propagated.
        }
    }

    /** Tells other servers that an identity's restriction changed. */
    public void publishInvalidate(UUID internalId) {
        publish(instanceId + SEPARATOR + "invalidate" + SEPARATOR + internalId);
    }

    /** Tells other servers to drop every cached decision. */
    public void publishInvalidateAll() {
        publish(instanceId + SEPARATOR + "all");
    }

    private void publish(String message) {
        if (!healthy || publisher == null) {
            return;
        }
        try {
            publisher.sync().publish(settings.channel, message);
        } catch (RuntimeException failure) {
            // A failed publish degrades to per server caches expiring on their
            // own. Losing propagation must not fail the moderation action that
            // triggered it.
            healthy = false;
        }
    }

    public boolean isHealthy() {
        return healthy && subscriber != null && subscriber.isOpen();
    }

    private void closeQuietly() {
        if (subscriber != null) {
            subscriber.close();
            subscriber = null;
        }
        if (publisher != null) {
            publisher.close();
            publisher = null;
        }
        if (client != null) {
            client.shutdown();
            client = null;
        }
    }

    @Override
    public void close() {
        healthy = false;
        closeQuietly();
    }
}
