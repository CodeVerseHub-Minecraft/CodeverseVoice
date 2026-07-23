package net.codeverse.voice.lang;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the bundled translations.
 *
 * Every message reaches players through MiniMessage, so a malformed tag in a
 * translation is not a cosmetic problem: it throws while rendering, which means
 * a player who happens to have that locale gets an error instead of being told
 * why they cannot speak. Contributed translations are exactly where that
 * mistake enters, so it is checked here rather than discovered in production.
 *
 * This also pins down the behaviour of the angle bracket argument names used in
 * usage strings, such as the word player inside brackets. Those look like tags
 * and are not, so the test asserts that MiniMessage leaves them as readable
 * text rather than swallowing them.
 */
class LangFilesTest {

    private static final Gson GSON = new Gson();
    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final List<String> BUNDLED = List.of("en", "de");

    private static Map<String, String> load(String locale) {
        try (InputStream stream = LangFilesTest.class.getResourceAsStream("/lang/" + locale + ".json")) {
            assertNotNull(stream, "missing bundled lang file for " + locale);
            JsonObject root = GSON.fromJson(new InputStreamReader(stream, StandardCharsets.UTF_8), JsonObject.class);
            Map<String, String> flat = new HashMap<>();
            flatten(root, "", flat);
            return flat;
        } catch (Exception failure) {
            throw new IllegalStateException("could not read lang file " + locale, failure);
        }
    }

    private static void flatten(JsonObject object, String prefix, Map<String, String> out) {
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            String key = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            JsonElement value = entry.getValue();
            if (value.isJsonObject()) {
                flatten(value.getAsJsonObject(), key, out);
            } else if (value.isJsonArray()) {
                StringBuilder joined = new StringBuilder();
                for (int i = 0; i < value.getAsJsonArray().size(); i++) {
                    if (i > 0) {
                        joined.append('\n');
                    }
                    joined.append(value.getAsJsonArray().get(i).getAsString());
                }
                out.put(key, joined.toString());
            } else {
                out.put(key, value.getAsString());
            }
        }
    }

    @Test
    void everyBundledStringParsesAsMiniMessage() {
        List<String> failures = new ArrayList<>();
        for (String locale : BUNDLED) {
            for (Map.Entry<String, String> entry : load(locale).entrySet()) {
                try {
                    Component rendered = MINI.deserialize(entry.getValue());
                    assertNotNull(rendered);
                } catch (RuntimeException malformed) {
                    failures.add(locale + " / " + entry.getKey() + ": " + malformed.getMessage());
                }
            }
        }
        assertTrue(failures.isEmpty(), "malformed MiniMessage found: " + failures);
    }

    @Test
    void argumentNamesInUsageStringsSurviveRendering() {
        // Usage text contains angle bracket words that are not MiniMessage tags.
        // If MiniMessage ever started consuming unknown tags, every usage
        // message would render as an unreadable fragment, so this pins the
        // behaviour the message catalogue relies on.
        String usage = load("en").get("command.ban.usage");
        assertNotNull(usage, "command.ban.usage is missing");

        String plain = PlainTextComponentSerializer.plainText().serialize(MINI.deserialize(usage));
        assertTrue(plain.contains("player"), "argument name was lost while rendering: " + plain);
        assertTrue(plain.contains("duration"), "argument name was lost while rendering: " + plain);
        assertTrue(plain.contains("reason"), "argument name was lost while rendering: " + plain);
    }

    @Test
    void translationsCoverExactlyTheSameKeys() {
        Set<String> english = new TreeSet<>(load("en").keySet());
        for (String locale : BUNDLED) {
            if (locale.equals("en")) {
                continue;
            }
            Set<String> other = new TreeSet<>(load(locale).keySet());
            Set<String> missing = new TreeSet<>(english);
            missing.removeAll(other);
            Set<String> extra = new TreeSet<>(other);
            extra.removeAll(english);
            assertTrue(missing.isEmpty(), locale + " is missing keys: " + missing);
            assertTrue(extra.isEmpty(), locale + " has keys not present in English: " + extra);
        }
    }

    @Test
    void everyVoiceStateHasAMessage() {
        Map<String, String> english = load("en");
        for (net.codeverse.voice.model.VoiceState state : net.codeverse.voice.model.VoiceState.values()) {
            assertTrue(english.containsKey(state.messageKey()),
                    "no message defined for " + state + " (key " + state.messageKey() + ")");
        }
    }

    @Test
    void noStringIsEmpty() {
        for (String locale : BUNDLED) {
            for (Map.Entry<String, String> entry : load(locale).entrySet()) {
                assertTrue(!entry.getValue().isBlank(),
                        locale + " / " + entry.getKey() + " is blank");
            }
        }
        assertEquals(BUNDLED.size(), BUNDLED.stream().distinct().count());
    }
}
