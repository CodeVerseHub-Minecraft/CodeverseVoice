package net.codeverse.voice.model;

/**
 * Why a person is or is not permitted to speak.
 *
 * Returned instead of a boolean so the caller can tell the person which of
 * several restrictions applies. Answering "you are muted" when someone is
 * actually blocked by trust tier sends them to staff with the wrong question.
 */
public enum VoiceState {

    /** Permitted to speak. */
    ALLOWED,

    /** An active voice ban applies. */
    BANNED,

    /** Trust tier is not eligible, typically an unverified cracked account. */
    UNTRUSTED,

    /** Lacks the permission node required to use voice at all. */
    NO_PERMISSION,

    /**
     * Identity could not be resolved, so no decision could be made safely.
     * Treated as denied, matching the fail closed stance the network's
     * authentication layer takes: an unknown identity is never granted a
     * capability it might not be entitled to.
     */
    UNKNOWN_IDENTITY;

    public boolean allowed() {
        return this == ALLOWED;
    }

    /** Message key used to explain the state to the affected person. */
    public String messageKey() {
        return switch (this) {
            case ALLOWED -> "voice.allowed";
            case BANNED -> "voice.denied.banned";
            case UNTRUSTED -> "voice.denied.untrusted";
            case NO_PERMISSION -> "voice.denied.no-permission";
            case UNKNOWN_IDENTITY -> "voice.denied.unknown-identity";
        };
    }
}
