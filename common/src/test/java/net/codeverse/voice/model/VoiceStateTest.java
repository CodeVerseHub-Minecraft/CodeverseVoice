package net.codeverse.voice.model;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoiceStateTest {

    @Test
    void onlyAllowedPermitsSpeech() {
        assertTrue(VoiceState.ALLOWED.allowed());
        for (VoiceState state : VoiceState.values()) {
            if (state != VoiceState.ALLOWED) {
                assertFalse(state.allowed(), state + " must not permit speech");
            }
        }
    }

    @Test
    void unknownIdentityIsDeniedRatherThanPermitted() {
        assertFalse(VoiceState.UNKNOWN_IDENTITY.allowed());
    }

    @Test
    void everyStateHasADistinctMessageKey() {
        Set<String> keys = new HashSet<>();
        for (VoiceState state : VoiceState.values()) {
            String key = state.messageKey();
            assertTrue(key != null && !key.isBlank(), state + " has no message key");
            assertTrue(keys.add(key), "duplicate message key " + key);
        }
        assertEquals(VoiceState.values().length, keys.size());
    }
}
