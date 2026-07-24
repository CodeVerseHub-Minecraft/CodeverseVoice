package net.codeverse.voice.lang;

import net.codeverse.api.voice.VoiceAccess;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoiceDenialTest {

    @Test
    void everyAccessOutcomeHasADistinctMessageKey() {
        Set<String> keys = new HashSet<>();
        for (VoiceAccess access : VoiceAccess.values()) {
            String key = VoiceDenial.messageKey(access);
            assertTrue(key != null && !key.isBlank(), access + " has no message key");
            assertTrue(keys.add(key), "duplicate message key " + key);
        }
        assertEquals(VoiceAccess.values().length, keys.size());
    }

    @Test
    void onlyAllowedPermitsSpeech() {
        assertTrue(VoiceAccess.ALLOWED.allowed());
        for (VoiceAccess access : VoiceAccess.values()) {
            if (access != VoiceAccess.ALLOWED) {
                org.junit.jupiter.api.Assertions.assertFalse(access.allowed(),
                        access + " must not permit speech");
            }
        }
    }
}
