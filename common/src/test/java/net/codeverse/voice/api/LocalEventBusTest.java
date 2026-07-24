package net.codeverse.voice.api;

import net.codeverse.api.event.CodeverseEvent;
import net.codeverse.api.event.EventBus;
import net.codeverse.api.event.VoiceRestrictionEvent;
import net.codeverse.api.voice.VoiceRestriction;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalEventBusTest {

    private final LocalEventBus bus = new LocalEventBus(LoggerFactory.getLogger("test"));

    private static VoiceRestrictionEvent applied() {
        UUID id = UUID.randomUUID();
        VoiceRestriction restriction = new VoiceRestriction(
                id, "spam", Optional.empty(), Instant.now(), Optional.empty(), true);
        return new VoiceRestrictionEvent(id, VoiceRestrictionEvent.Type.APPLIED, restriction, Instant.now(), true);
    }

    @Test
    void deliversToMatchingSubscribersOnly() {
        List<CodeverseEvent> voiceSeen = new CopyOnWriteArrayList<>();
        AtomicInteger otherSeen = new AtomicInteger();
        bus.subscribe(this, VoiceRestrictionEvent.class, voiceSeen::add);
        bus.subscribe(this, net.codeverse.api.event.IdentityLinkedEvent.class, e -> otherSeen.incrementAndGet());

        bus.publish(applied());

        assertEquals(1, voiceSeen.size());
        assertEquals(0, otherSeen.get());
    }

    @Test
    void closingASubscriptionStopsDelivery() {
        AtomicInteger count = new AtomicInteger();
        EventBus.Subscription subscription = bus.subscribe(
                this, VoiceRestrictionEvent.class, e -> count.incrementAndGet());

        bus.publish(applied());
        subscription.close();
        bus.publish(applied());

        assertEquals(1, count.get());
        assertFalse(subscription.isActive());
    }

    @Test
    void bulkUnsubscribeDropsEveryListenerFromAPlugin() {
        Object plugin = new Object();
        AtomicInteger count = new AtomicInteger();
        bus.subscribe(plugin, VoiceRestrictionEvent.class, e -> count.incrementAndGet());
        bus.subscribe(plugin, VoiceRestrictionEvent.class, e -> count.incrementAndGet());

        bus.unsubscribeAll(plugin);
        bus.publish(applied());

        assertEquals(0, count.get());
        assertEquals(0, bus.registrationCount());
    }

    @Test
    void aThrowingListenerIsRemovedAndTheRestStillReceive() {
        AtomicInteger healthy = new AtomicInteger();
        bus.subscribe(this, VoiceRestrictionEvent.class, e -> {
            throw new IllegalStateException("deliberate");
        });
        bus.subscribe(this, VoiceRestrictionEvent.class, e -> healthy.incrementAndGet());

        bus.publish(applied());
        bus.publish(applied());

        assertEquals(2, healthy.get());
        assertEquals(1, bus.registrationCount());
    }

    @Test
    void deliversTheEventUnalteredIncludingItsOrigin() {
        // The bus does not stamp origin: the publisher decides whether an
        // event is local or arrived from elsewhere, and the bus delivers it
        // faithfully. This pins that it does not rewrite the flag in passing.
        VoiceRestrictionEvent published = applied();
        List<VoiceRestrictionEvent> seen = new CopyOnWriteArrayList<>();
        bus.subscribe(this, VoiceRestrictionEvent.class, seen::add);
        bus.publish(published);
        assertEquals(published.remote(), seen.get(0).remote());
        assertEquals(published.internalId(), seen.get(0).internalId());
    }
}
