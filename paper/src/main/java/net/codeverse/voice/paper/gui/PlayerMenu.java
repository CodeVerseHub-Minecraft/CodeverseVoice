package net.codeverse.voice.paper.gui;

import net.codeverse.voice.config.PluginConfig;
import net.codeverse.voice.lang.LangManager;
import net.codeverse.voice.model.VoiceBan;
import net.codeverse.voice.util.DurationParser;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * The moderation menu for one player.
 *
 * Exists because typing a full command while an incident is happening is slower
 * than clicking, and slow moderation is the kind people stop doing. Every
 * action here is also available as a command, so nobody is forced through the
 * menu and automation is still possible.
 */
public final class PlayerMenu extends Menu {

    /** Callbacks the menu triggers. Implemented by the plugin so the menu stays free of storage concerns. */
    public interface Actions {
        void ban(Player staff, UUID targetMinecraftId, String targetName, String duration, String reason);

        void unban(Player staff, UUID targetMinecraftId, String targetName);

        void monitor(Player staff, UUID targetMinecraftId, String targetName);

        void capture(Player staff, UUID targetMinecraftId, String targetName);

        void openHistory(Player staff, UUID targetMinecraftId, String targetName);

        void openPresets(Player staff, UUID targetMinecraftId, String targetName);
    }

    private final LangManager lang;
    private final Actions actions;
    private final UUID targetMinecraftId;
    private final String targetName;
    private final Optional<VoiceBan> activeBan;
    private final String tier;
    private final Locale viewerLocale;
    private boolean confirmingUnban;

    public PlayerMenu(PluginConfig config,
                      LangManager lang,
                      Actions actions,
                      UUID targetMinecraftId,
                      String targetName,
                      Optional<VoiceBan> activeBan,
                      String tier,
                      Locale viewerLocale) {
        super(config);
        this.lang = lang;
        this.actions = actions;
        this.targetMinecraftId = targetMinecraftId;
        this.targetName = targetName;
        this.activeBan = activeBan;
        this.tier = tier;
        this.viewerLocale = viewerLocale;
    }

    @Override
    protected Component title() {
        return lang.get("gui.player.title", viewerLocale, "name", targetName);
    }

    @Override
    protected void build() {
        set(4, head());

        if (activeBan.isPresent() && activeBan.get().isEnforceable(System.currentTimeMillis())) {
            buildRestrictedView();
        } else {
            buildQuickDurations();
        }

        int lastRow = (rows() - 1) * 9;

        if (config().presets.enabled) {
            set(lastRow + 2, icon(Material.WRITABLE_BOOK,
                            lang.get("gui.player.presets", viewerLocale),
                            List.of(lang.get("gui.player.presets-lore", viewerLocale))),
                    (viewer, click) -> actions.openPresets(viewer, targetMinecraftId, targetName));
        }

        set(lastRow + 3, icon(Material.BOOK,
                        lang.get("gui.player.history", viewerLocale),
                        List.of(lang.get("gui.player.history-lore", viewerLocale))),
                (viewer, click) -> actions.openHistory(viewer, targetMinecraftId, targetName));

        if (config().monitoring.enabled) {
            set(lastRow + 5, icon(Material.ENDER_EYE,
                            lang.get("gui.player.monitor", viewerLocale),
                            List.of(lang.get("gui.player.monitor-lore", viewerLocale,
                                    "seconds", String.valueOf(config().monitoring.automaticStopSeconds)))),
                    (viewer, click) -> {
                        actions.monitor(viewer, targetMinecraftId, targetName);
                        closeIfConfigured(viewer);
                    });
        }

        if (config().recording.enabled) {
            set(lastRow + 6, icon(Material.MUSIC_DISC_CAT,
                            lang.get("gui.player.capture", viewerLocale),
                            List.of(lang.get("gui.player.capture-lore", viewerLocale,
                                    "seconds", String.valueOf(config().recording.bufferSeconds)))),
                    (viewer, click) -> {
                        actions.capture(viewer, targetMinecraftId, targetName);
                        closeIfConfigured(viewer);
                    });
        }

        set(lastRow + 8, icon(Material.BARRIER,
                        lang.get("gui.close", viewerLocale), List.of()),
                (viewer, click) -> viewer.closeInventory());
    }

    /** When the target is already restricted, the menu offers to lift it rather than stack another. */
    private void buildRestrictedView() {
        VoiceBan ban = activeBan.orElseThrow();
        long now = System.currentTimeMillis();
        String remaining = ban.isPermanent()
                ? lang.raw("duration.permanent", viewerLocale)
                : DurationParser.format(ban.remainingMillis(now));

        set(22, icon(Material.RED_CONCRETE,
                lang.get("gui.player.currently-restricted", viewerLocale),
                List.of(
                        lang.get("gui.player.restriction-reason", viewerLocale, "reason", ban.reason()),
                        lang.get("gui.player.restriction-remaining", viewerLocale, "remaining", remaining))));

        Material unbanMaterial = confirmingUnban ? Material.LIME_CONCRETE : Material.LIME_DYE;
        Component unbanName = confirmingUnban
                ? lang.get("gui.player.unban-confirm", viewerLocale)
                : lang.get("gui.player.unban", viewerLocale);

        set(31, icon(unbanMaterial, unbanName,
                        List.of(lang.get("gui.player.unban-lore", viewerLocale))),
                (viewer, click) -> {
                    if (config().gui.confirmDestructiveActions && !confirmingUnban) {
                        confirmingUnban = true;
                        refresh();
                        return;
                    }
                    actions.unban(viewer, targetMinecraftId, targetName);
                    closeIfConfigured(viewer);
                });
    }

    /** One click restriction lengths, laid out across the middle rows. */
    private void buildQuickDurations() {
        List<String> durations = config().gui.quickDurations;
        int[] slots = layoutSlots(durations.size());

        for (int i = 0; i < durations.size() && i < slots.length; i++) {
            String duration = durations.get(i);
            boolean permanent = DurationParser.isPermanent(duration);
            String label = permanent
                    ? lang.raw("duration.permanent", viewerLocale)
                    : DurationParser.parse(duration).map(DurationParser::format).orElse(duration);

            set(slots[i], icon(
                            permanent ? Material.NETHERITE_BLOCK : Material.CLOCK,
                            lang.get("gui.player.ban-for", viewerLocale, "duration", label),
                            List.of(lang.get("gui.player.ban-for-lore", viewerLocale,
                                    "reason", config().gui.defaultReason))),
                    (viewer, click) -> {
                        actions.ban(viewer, targetMinecraftId, targetName, duration, config().gui.defaultReason);
                        closeIfConfigured(viewer);
                    });
        }
    }

    /** Centres the duration buttons on the second row and continues onto the third. */
    private int[] layoutSlots(int count) {
        int[] row2 = {10, 11, 12, 13, 14, 15, 16};
        int[] row3 = {19, 20, 21, 22, 23, 24, 25};
        int[] combined = new int[row2.length + row3.length];
        System.arraycopy(row2, 0, combined, 0, row2.length);
        System.arraycopy(row3, 0, combined, row2.length, row3.length);
        return combined;
    }

    private ItemStack head() {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        List<Component> lore = new ArrayList<>();
        lore.add(lang.get("gui.player.tier", viewerLocale,
                "tier", tier == null ? lang.raw("gui.unknown", viewerLocale) : tier));
        if (activeBan.isPresent() && activeBan.get().isEnforceable(System.currentTimeMillis())) {
            lore.add(lang.get("gui.player.status-restricted", viewerLocale));
        } else {
            lore.add(lang.get("gui.player.status-active", viewerLocale));
        }

        if (item.getItemMeta() instanceof SkullMeta meta) {
            meta.setOwningPlayer(org.bukkit.Bukkit.getOfflinePlayer(targetMinecraftId));
            meta.displayName(lang.get("gui.player.head", viewerLocale, "name", targetName)
                    .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
            List<Component> cleaned = new ArrayList<>(lore.size());
            for (Component line : lore) {
                cleaned.add(line.decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
            }
            meta.lore(cleaned);
            item.setItemMeta(meta);
        }
        return item;
    }

    private void closeIfConfigured(Player viewer) {
        if (config().gui.closeAfterAction) {
            viewer.closeInventory();
        }
    }
}
