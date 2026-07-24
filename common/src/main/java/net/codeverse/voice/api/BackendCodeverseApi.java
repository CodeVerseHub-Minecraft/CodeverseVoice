package net.codeverse.voice.api;

import net.codeverse.api.CodeverseApi;
import net.codeverse.api.event.EventBus;
import net.codeverse.api.identity.IdentityService;
import net.codeverse.api.link.LinkService;
import net.codeverse.api.voice.VoiceService;

import java.util.Optional;

/**
 * The API as a backend provides it.
 *
 * On the proxy CodeverseAuth is the provider and this class is never used. A
 * backend has no CodeverseAuth, so the voice plugin becomes the provider for
 * the servers it runs on, exposing identity resolution backed by the shared
 * database and the voice service it enforces with.
 *
 * Linking is absent and correctly so: issuing and redeeming link codes is the
 * proxy's job, and a backend that pretended to offer it would be answering a
 * question it has no authority over. A consumer on a backend asking for link
 * gets an empty optional to handle, which is the honest answer.
 */
public final class BackendCodeverseApi implements CodeverseApi {

    private static final String API_VERSION = "0.2";

    private final IdentityService identity;
    private final VoiceService voice;
    private final EventBus events;

    public BackendCodeverseApi(IdentityService identity, VoiceService voice, EventBus events) {
        this.identity = identity;
        this.voice = voice;
        this.events = events;
    }

    @Override
    public Optional<IdentityService> identity() {
        return Optional.of(identity);
    }

    @Override
    public Optional<VoiceService> voice() {
        return Optional.of(voice);
    }

    @Override
    public Optional<LinkService> link() {
        return Optional.empty();
    }

    @Override
    public EventBus events() {
        return events;
    }

    @Override
    public String apiVersion() {
        return API_VERSION;
    }
}
