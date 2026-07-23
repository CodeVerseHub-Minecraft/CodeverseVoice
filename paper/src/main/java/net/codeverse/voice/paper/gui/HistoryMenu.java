package net.codeverse.voice.paper.gui;

import net.codeverse.voice.config.PluginConfig;
import net.codeverse.voice.lang.LangManager;
import net.codeverse.voice.model.VoiceBan;
import net.codeverse.voice.util.DurationParser;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A player's past restrictions, newest first.
 *
 * Lifted and expired entries are shown alongside active ones because a
 * moderation history that only lists what is currently in force tells you
 * nothing about whether someone is a repeat problem.
 */
public final class HistoryMenu extends Menu {

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC);

    private final LangManager lang;
    private final String targetName;
    private final List<VoiceBan> history;
    private final Locale viewerLocale;

    public HistoryMenu(PluginConfig config,
                       LangManager lang,
                       String targetName,
                       List<VoiceBan> history,
                       Locale viewerLocale) {
        super(config);
        this.lang = lang;
        this.targetName = targetName;
        this.history = history;
        this.viewerLocale = viewerLocale;
    }

    @Override
    protected Component title() {
        return lang.get("gui.history.title", viewerLocale, "name", targetName);
    }

    @Override
    protected int rows() {
        int needed = (Math.min(history.size(), config().gui.historyPageSize) / 7) + 2;
        return Math.min(6, Math.max(3, needed));
    }

    @Override
    protected void build() {
        if (history.isEmpty()) {
            set(13, icon(Material.LIME_DYE,
                    lang.get("gui.history.empty", viewerLocale, "name", targetName), List.of()));
        }

        long now = System.currentTimeMillis();
        int slot = 10;
        int shown = 0;

        for (VoiceBan entry : history) {
            if (shown >= config().gui.historyPageSize || slot >= (rows() - 1) * 9) {
                break;
            }
            if (slot % 9 == 8) {
                slot += 2;
                continue;
            }
            boolean enforceable = entry.isEnforceable(now);
            List<Component> lore = new ArrayList<>();
            lore.add(lang.get("gui.history.reason", viewerLocale, "reason", entry.reason()));
            lore.add(lang.get("gui.history.issued", viewerLocale,
                    "when", STAMP.format(Instant.ofEpochMilli(entry.issuedAt()))));
            lore.add(entry.isPermanent()
                    ? lang.get("gui.history.permanent", viewerLocale)
                    : lang.get("gui.history.expires", viewerLocale,
                            "when", STAMP.format(Instant.ofEpochMilli(entry.expiresAt()))));
            lore.add(enforceable
                    ? lang.get("gui.history.state-active", viewerLocale)
                    : lang.get("gui.history.state-inactive", viewerLocale));

            set(slot, icon(enforceable ? Material.RED_DYE : Material.GRAY_DYE,
                    lang.get("gui.history.entry", viewerLocale, "index", String.valueOf(shown + 1)), lore));
            slot++;
            shown++;
        }

        set((rows() - 1) * 9 + 8, icon(Material.BARRIER, lang.get("gui.close", viewerLocale), List.of()),
                (viewer, click) -> viewer.closeInventory());
    }
}
