package net.codeverse.voice.lang;

import net.codeverse.api.voice.VoiceAccess;

/**
 * The message shown to someone who was denied, keyed by why.
 *
 * VoiceAccess is a shared API enum and carries no message keys, deliberately:
 * the API describes the decision, not how a particular plugin phrases it. This
 * mapping is that phrasing, kept in one place so a new denial reason cannot be
 * added without a translator noticing the missing key, which the language
 * parity test enforces.
 */
public final class VoiceDenial {

    private VoiceDenial() {
    }

    public static String messageKey(VoiceAccess access) {
        return switch (access) {
            case ALLOWED -> "voice.allowed";
            case RESTRICTED -> "voice.denied.banned";
            case UNTRUSTED -> "voice.denied.untrusted";
            case NO_PERMISSION -> "voice.denied.no-permission";
            case UNKNOWN_IDENTITY -> "voice.denied.unknown-identity";
        };
    }
}
