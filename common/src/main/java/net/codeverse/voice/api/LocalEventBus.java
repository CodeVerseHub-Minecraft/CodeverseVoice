package net.codeverse.voice.api;

import net.codeverse.api.event.CodeverseEvent;
import net.codeverse.api.event.EventBus;
import org.slf4j.Logger;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * An in process event bus for the backend provider.
 *
 * The API ships the contract but no implementation, on purpose: whoever
 * provides the API on a given server provides the bus, so it runs on that
 * plugin's threads and shuts down with it. This is the backend's copy, the
 * counterpart to the one the authentication plugin runs on the proxy.
 *
 * Delivery is in process only. Nothing here crosses to another server, which
 * is why every event it publishes is marked local. Cross server propagation of
 * moderation actions is carried separately over Redis, keyed to cache
 * invalidation rather than to this bus, so a lift on one server clears the
 * cache on another without either server needing to receive the other's
 * events.
 *
 * A listener that throws is removed rather than retried, so one broken consumer
 * cannot suppress delivery to every other or fill the log on every publish.
 */
public final class LocalEventBus implements EventBus {

    private final List<Registration> registrations = new CopyOnWriteArrayList<>();
    private final Logger logger;

    public LocalEventBus(Logger logger) {
        this.logger = logger;
    }

    @Override
    public <T extends CodeverseEvent> Subscription subscribe(Object plugin, Class<T> type, Consumer<? super T> listener) {
        if (plugin == null || type == null || listener == null) {
            throw new IllegalArgumentException("plugin, type and listener are all required");
        }
        Registration registration = new Registration(plugin, type, listener);
        registrations.add(registration);
        return registration;
    }

    @Override
    public void unsubscribeAll(Object plugin) {
        registrations.removeIf(registration -> {
            if (registration.plugin == plugin) {
                registration.active.set(false);
                return true;
            }
            return false;
        });
    }

    /** Delivers an event to every matching listener. Called by the providing plugin only. */
    public void publish(CodeverseEvent event) {
        for (Registration registration : registrations) {
            if (!registration.active.get() || !registration.type.isInstance(event)) {
                continue;
            }
            try {
                registration.accept(event);
            } catch (Throwable failure) {
                registration.active.set(false);
                registrations.remove(registration);
                logger.error("Event listener from {} threw handling {} and was unsubscribed",
                        registration.plugin.getClass().getName(),
                        event.getClass().getSimpleName(),
                        failure);
            }
        }
    }

    int registrationCount() {
        return registrations.size();
    }

    private final class Registration implements Subscription {

        private final Object plugin;
        private final Class<? extends CodeverseEvent> type;
        private final Consumer<? super CodeverseEvent> listener;
        private final AtomicBoolean active = new AtomicBoolean(true);

        @SuppressWarnings("unchecked")
        private <T extends CodeverseEvent> Registration(Object plugin, Class<T> type, Consumer<? super T> listener) {
            this.plugin = plugin;
            this.type = type;
            this.listener = (Consumer<? super CodeverseEvent>) listener;
        }

        private void accept(CodeverseEvent event) {
            listener.accept(event);
        }

        @Override
        public boolean isActive() {
            return active.get();
        }

        @Override
        public void close() {
            active.set(false);
            registrations.remove(this);
        }
    }
}
