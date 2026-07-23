package net.codeverse.voice.paper.util;

import net.kyori.adventure.key.Key;
import org.bukkit.Registry;
import org.bukkit.Sound;

import java.util.Locale;
import java.util.Optional;

/**
 * Resolves configured sound names.
 *
 * Sound.valueOf is deprecated for removal on current Paper, because sounds are
 * a registry rather than an enum. Looking them up through the registry also
 * means a config can name a sound added by a datapack or a newer game version
 * without this plugin needing to know about it.
 *
 * Accepts both the historical enum style, BLOCK_NOTE_BLOCK_BASS, and the
 * namespaced form, minecraft:block.note_block.bass.
 */
public final class Sounds {

    private Sounds() {
    }

    public static Optional<Sound> resolve(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        String trimmed = name.trim();
        try {
            Key key = trimmed.contains(":") || trimmed.contains(".")
                    ? Key.key(trimmed.toLowerCase(Locale.ROOT))
                    : Key.key("minecraft", trimmed.toLowerCase(Locale.ROOT).replace('_', '.'));
            Sound resolved = Registry.SOUNDS.get(key);
            return Optional.ofNullable(resolved);
        } catch (RuntimeException malformed) {
            return Optional.empty();
        }
    }
}
