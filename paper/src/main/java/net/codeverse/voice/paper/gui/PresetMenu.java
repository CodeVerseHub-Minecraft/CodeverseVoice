package net.codeverse.voice.paper.gui;

import net.codeverse.voice.config.PluginConfig;
import net.codeverse.voice.lang.LangManager;
import net.codeverse.voice.util.DurationParser;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Named reasons with a matching length attached.
 *
 * Consistency is the point. When every moderator writes their own wording, a
 * player's history becomes impossible to read at a glance and appeals turn into
 * arguments about what was meant. A short list of agreed reasons removes that.
 */
public final class PresetMenu extends Menu {

    private final LangManager lang;
    private final PlayerMenu.Actions actions;
    private final UUID targetMinecraftId;
    private final String targetName;
    private final Locale viewerLocale;

    public PresetMenu(PluginConfig config,
                      LangManager lang,
                      PlayerMenu.Actions actions,
                      UUID targetMinecraftId,
                      String targetName,
                      Locale viewerLocale) {
        super(config);
        this.lang = lang;
        this.actions = actions;
        this.targetMinecraftId = targetMinecraftId;
        this.targetName = targetName;
        this.viewerLocale = viewerLocale;
    }

    @Override
    protected Component title() {
        return lang.get("gui.presets.title", viewerLocale, "name", targetName);
    }

    @Override
    protected int rows() {
        int needed = (config().presets.reasons.size() / 9) + 2;
        return Math.min(6, Math.max(3, needed));
    }

    @Override
    protected void build() {
        List<PluginConfig.ReasonPreset> presets = config().presets.reasons;
        int slot = 10;

        for (PluginConfig.ReasonPreset preset : presets) {
            if (slot % 9 == 8) {
                slot += 2;
            }
            if (slot >= (rows() - 1) * 9) {
                break;
            }
            String label = DurationParser.isPermanent(preset.duration)
                    ? lang.raw("duration.permanent", viewerLocale)
                    : DurationParser.parse(preset.duration).map(DurationParser::format).orElse(preset.duration);

            set(slot, icon(material(preset.icon, Material.PAPER),
                            lang.get("gui.presets.entry", viewerLocale, "reason", preset.reason),
                            List.of(
                                    lang.get("gui.presets.entry-duration", viewerLocale, "duration", label),
                                    lang.get("gui.presets.entry-hint", viewerLocale))),
                    (viewer, click) -> {
                        actions.ban(viewer, targetMinecraftId, targetName, preset.duration, preset.reason);
                        if (config().gui.closeAfterAction) {
                            viewer.closeInventory();
                        }
                    });
            slot++;
        }

        set((rows() - 1) * 9 + 8, icon(Material.BARRIER, lang.get("gui.close", viewerLocale), List.of()),
                (viewer, click) -> viewer.closeInventory());
    }
}
