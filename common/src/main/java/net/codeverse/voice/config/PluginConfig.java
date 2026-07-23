package net.codeverse.voice.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Every tunable in the plugin, loaded from config.json.
 *
 * Nothing is compiled in. The file is written with defaults on first start and
 * merged forward on upgrade, so a new release never silently drops settings an
 * operator has already changed.
 *
 * The same file is read by both the Paper and the Velocity module. Sections one
 * platform does not use are ignored rather than rejected, so a single config
 * can be copied between servers without editing.
 */
public final class PluginConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    public Storage storage = new Storage();
    public Redis redis = new Redis();
    public Identity identity = new Identity();
    public Access access = new Access();
    public Notifications notifications = new Notifications();
    public Gui gui = new Gui();
    public Placeholders placeholders = new Placeholders();
    public Recording recording = new Recording();
    public Monitoring monitoring = new Monitoring();
    public Ranges ranges = new Ranges();
    public Groups groups = new Groups();
    public Presets presets = new Presets();
    public Language language = new Language();

    public static final class Storage {
        public String jdbcUrl = "jdbc:mysql://127.0.0.1:3306/network?useSSL=false&characterEncoding=utf8";
        public String username = "network";
        public String password = "";
        /**
         * Named explicitly because a driver shaded into a plugin jar is never
         * auto discovered: DriverManager resolves through the system class
         * loader, which cannot see the plugin class loader.
         */
        public String driverClassName = "com.mysql.cj.jdbc.Driver";
        public int maximumPoolSize = 6;
        public int minimumIdle = 1;
        public long connectionTimeoutMillis = 10000;
        public String tablePrefix = "codeverse_";
    }

    public static final class Redis {
        /**
         * Propagates moderation actions between servers. Voice runs on one
         * server, but restrictions are issued from wherever staff happen to be
         * and placeholders are read on every server that displays them.
         */
        public boolean enabled = true;
        public String uri = "redis://127.0.0.1:6379/0";
        public String channel = "codeverse:voice";
    }

    public static final class Identity {
        /**
         * Reads the accounts table written by the authentication plugin so a
         * restriction is keyed to the person rather than to one of their
         * accounts. When the table is absent the plugin falls back to the
         * Minecraft uuid and says so loudly, because that fallback makes
         * evasion by account switching possible again.
         */
        public boolean useAuthPluginIdentities = true;
        public String accountsTable = "codeverse_accounts";
        public long cacheSeconds = 300;
    }

    public static final class Access {
        /** Required to speak at all. Deny it to the cracked group. */
        public String speakPermission = "codeverse.voice.speak";
        /**
         * Enforced independently of permissions. When true, an account whose
         * stored trust tier is not listed below is denied regardless of what
         * permissions it holds, so a mistyped group cannot hand voice to an
         * unverified account.
         */
        public boolean requireVerifiedOrigin = true;
        public List<String> trustedTiers = List.of("PREMIUM", "BEDROCK", "DISCORD_LINKED");
        /** Tells a restricted speaker why they cannot be heard. */
        public boolean notifyOnDenial = true;
        /** CHAT, ACTION_BAR, TITLE or NONE. */
        public String denialNoticeStyle = "ACTION_BAR";
        public long denialNoticeCooldownSeconds = 30;
    }

    /**
     * Who hears about what. Every message the plugin sends can be disabled
     * independently, because a network that is loud about moderation and one
     * that is quiet about it are both defensible choices.
     */
    public static final class Notifications {
        public boolean notifyTargetOnBan = true;
        public boolean notifyTargetOnUnban = true;
        /** Tells someone their restriction lapsed rather than leaving them to guess. */
        public boolean notifyTargetOnExpiry = true;
        public boolean notifyStaffOnBan = true;
        public boolean notifyStaffOnUnban = true;
        public String staffPermission = "codeverse.voice.notify";
        /** Announces restrictions to everyone. Off by default; public shaming is rarely wanted. */
        public boolean broadcastBans = false;
        public boolean broadcastUnbans = false;
        public boolean playSounds = true;
        public String targetSound = "BLOCK_NOTE_BLOCK_BASS";
        public String staffSound = "BLOCK_NOTE_BLOCK_HAT";
        public float soundVolume = 0.7f;
        public float soundPitch = 1.0f;
        /** Reminds a restricted player how long is left each time they join. */
        public boolean remindOnJoin = true;
    }

    /**
     * The inventory menus. Typing a full command mid incident is slower than
     * clicking, so the menu exists for speed; every action it offers is also
     * reachable by command for people who prefer typing.
     */
    public static final class Gui {
        public boolean enabled = true;
        public int rows = 5;
        /**
         * One click restriction lengths offered in the menu. Each entry must
         * parse as a duration, or be the literal perm.
         */
        public List<String> quickDurations = List.of("10m", "30m", "1h", "6h", "1d", "7d", "30d", "perm");
        /** Reason applied by a one click restriction when none is typed. */
        public String defaultReason = "Voice chat misuse";
        public boolean fillEmptySlots = true;
        public String fillMaterial = "GRAY_STAINED_GLASS_PANE";
        public boolean closeAfterAction = true;
        /** Requires a second click before anything irreversible happens. */
        public boolean confirmDestructiveActions = true;
        public boolean playClickSound = true;
        public String clickSound = "UI_BUTTON_CLICK";
        /** How many past restrictions the history menu shows at once. */
        public int historyPageSize = 28;
    }

    /** PlaceholderAPI output, so a lobby scoreboard can show voice status. */
    public static final class Placeholders {
        public boolean enabled = true;
        public String mutedYes = "<red>Restricted</red>";
        public String mutedNo = "<green>Active</green>";
        public String noRestriction = "none";
        public String permanentLabel = "permanent";
        /** Shown while a lookup is still in flight, so a scoreboard never blanks. */
        public String loadingLabel = "...";
    }

    public static final class Recording {
        public boolean enabled = true;
        /**
         * How far back a capture can reach. Audio older than this is discarded
         * continuously and never touches disk.
         */
        public int bufferSeconds = 30;
        /** Nothing is written until staff explicitly capture an incident. */
        public String outputDirectory = "recordings";
        /**
         * Captured evidence is deleted after this many days. Recorded speech is
         * personal data under the GDPR and the Swiss FADP, so an unbounded
         * archive is a liability rather than an asset. Set to 0 to disable
         * automatic deletion only if a documented retention policy exists.
         */
        public int retentionDays = 30;
        /**
         * Shown to every person when they connect to voice. Notice obligations
         * are far cheaper to meet at the start than to retrofit.
         */
        public boolean noticeOnConnect = true;
        public int mp3Bitrate = 64;
        public long maximumDirectoryMegabytes = 2048;
        /** Warns staff once stored captures pass this share of the budget. */
        public int warnAtPercentOfBudget = 80;
    }

    public static final class Monitoring {
        public boolean enabled = true;
        public String permission = "codeverse.voice.monitor";
        /**
         * Monitoring ends by itself. A listening session that has to be
         * remembered to be stopped will eventually be forgotten, which turns a
         * moderation tool into indefinite surveillance.
         */
        public int automaticStopSeconds = 120;
        public int warningBeforeStopSeconds = 15;
        public int maximumExtensions = 3;
        /** Other staff are told when monitoring starts, so it is never silent. */
        public boolean announceToStaff = true;
        public String staffAnnouncementPermission = "codeverse.voice.monitor.notify";
        /** Shows remaining time on the action bar while a session runs. */
        public boolean showCountdown = true;
    }

    public static final class Ranges {
        public boolean enabled = false;
        /**
         * Overrides proximity distance per permission. The first entry whose
         * permission the speaker holds wins, so order from most to least
         * privileged.
         */
        public List<RangeRule> rules = List.of(
                new RangeRule("codeverse.voice.range.staff", 96.0f),
                new RangeRule("codeverse.voice.range.extended", 64.0f));
    }

    public static final class RangeRule {
        public String permission;
        public float distance;

        public RangeRule() {
        }

        public RangeRule(String permission, float distance) {
            this.permission = permission;
            this.distance = distance;
        }
    }

    public static final class Groups {
        public boolean requirePermissionToCreate = false;
        public String createPermission = "codeverse.voice.group.create";
        public boolean requirePermissionToJoin = false;
        public String joinPermission = "codeverse.voice.group.join";
        /** Case insensitive substrings that cannot appear in a group name. */
        public List<String> blockedNameFragments = List.of();
        public int maximumNameLength = 32;
    }

    /**
     * Named reasons staff pick from, so wording stays consistent between
     * moderators and a restriction can be matched to a rule later. Free text
     * reasons remain available for anything the list does not cover.
     */
    public static final class Presets {
        public boolean enabled = true;
        public List<ReasonPreset> reasons = List.of(
                new ReasonPreset("spam", "Voice chat spam", "1h", "NOTE_BLOCK"),
                new ReasonPreset("slurs", "Hate speech in voice chat", "perm", "REDSTONE_BLOCK"),
                new ReasonPreset("harassment", "Harassment in voice chat", "7d", "IRON_SWORD"),
                new ReasonPreset("loud", "Deliberately loud or distorted audio", "1d", "BELL"),
                new ReasonPreset("music", "Playing music or media without consent", "6h", "JUKEBOX"));
    }

    public static final class ReasonPreset {
        public String id;
        public String reason;
        public String duration;
        public String icon;

        public ReasonPreset() {
        }

        public ReasonPreset(String id, String reason, String duration, String icon) {
            this.id = id;
            this.reason = reason;
            this.duration = duration;
            this.icon = icon;
        }
    }

    public static final class Language {
        public String defaultLocale = "en";
        public boolean usePlayerLocale = true;
    }

    /**
     * Loads config.json, creating it from defaults when absent and adding keys
     * introduced by a newer version while preserving existing values.
     */
    public static PluginConfig load(Path directory) throws IOException {
        Files.createDirectories(directory);
        Path file = directory.resolve("config.json");

        PluginConfig config;
        if (Files.exists(file)) {
            String existing = Files.readString(file, StandardCharsets.UTF_8);
            config = GSON.fromJson(existing, PluginConfig.class);
            if (config == null) {
                throw new IOException("config.json is not valid JSON");
            }
        } else {
            config = new PluginConfig();
        }

        config.validate();
        Files.writeString(file, GSON.toJson(config), StandardCharsets.UTF_8);
        return config;
    }

    /** Rejects values that would quietly weaken moderation or break retention. */
    public void validate() {
        if (recording.bufferSeconds < 1) {
            throw new IllegalStateException("recording.bufferSeconds must be at least 1");
        }
        if (recording.bufferSeconds > 300) {
            throw new IllegalStateException(
                    "recording.bufferSeconds above 300 keeps a large amount of speech in memory for every "
                            + "speaker at all times, which is both costly and hard to justify under data "
                            + "protection rules");
        }
        if (recording.retentionDays < 0) {
            throw new IllegalStateException("recording.retentionDays cannot be negative");
        }
        if (recording.mp3Bitrate < 16 || recording.mp3Bitrate > 320) {
            throw new IllegalStateException("recording.mp3Bitrate must be between 16 and 320");
        }
        if (recording.warnAtPercentOfBudget < 1 || recording.warnAtPercentOfBudget > 100) {
            throw new IllegalStateException("recording.warnAtPercentOfBudget must be between 1 and 100");
        }
        if (monitoring.automaticStopSeconds < 10) {
            throw new IllegalStateException(
                    "monitoring.automaticStopSeconds below 10 is not a usable moderation window");
        }
        if (monitoring.warningBeforeStopSeconds >= monitoring.automaticStopSeconds) {
            throw new IllegalStateException(
                    "monitoring.warningBeforeStopSeconds must be shorter than automaticStopSeconds");
        }
        if (monitoring.maximumExtensions < 0) {
            throw new IllegalStateException("monitoring.maximumExtensions cannot be negative");
        }
        if (access.speakPermission == null || access.speakPermission.isBlank()) {
            throw new IllegalStateException("access.speakPermission cannot be blank");
        }
        if (access.trustedTiers == null || access.trustedTiers.isEmpty()) {
            throw new IllegalStateException(
                    "access.trustedTiers cannot be empty while access.requireVerifiedOrigin is in use, "
                            + "otherwise every account would be treated as untrusted");
        }
        String style = access.denialNoticeStyle == null ? "" : access.denialNoticeStyle.toUpperCase(Locale.ROOT);
        if (!List.of("CHAT", "ACTION_BAR", "TITLE", "NONE").contains(style)) {
            throw new IllegalStateException(
                    "access.denialNoticeStyle must be CHAT, ACTION_BAR, TITLE or NONE, got "
                            + access.denialNoticeStyle);
        }
        if (gui.rows < 1 || gui.rows > 6) {
            throw new IllegalStateException("gui.rows must be between 1 and 6, got " + gui.rows);
        }
        if (gui.historyPageSize < 1 || gui.historyPageSize > 45) {
            throw new IllegalStateException("gui.historyPageSize must be between 1 and 45");
        }
        if (gui.quickDurations == null || gui.quickDurations.isEmpty()) {
            throw new IllegalStateException("gui.quickDurations cannot be empty while the menu is enabled");
        }
        if (groups.maximumNameLength < 1) {
            throw new IllegalStateException("groups.maximumNameLength must be at least 1");
        }
        if (ranges.rules != null) {
            for (RangeRule rule : ranges.rules) {
                if (rule.permission == null || rule.permission.isBlank()) {
                    throw new IllegalStateException("every ranges.rules entry needs a permission");
                }
                if (rule.distance <= 0f) {
                    throw new IllegalStateException("ranges.rules distance must be positive for " + rule.permission);
                }
            }
        }
        if (presets.reasons != null) {
            for (ReasonPreset preset : presets.reasons) {
                if (preset.id == null || preset.id.isBlank()) {
                    throw new IllegalStateException("every presets.reasons entry needs an id");
                }
                if (preset.reason == null || preset.reason.isBlank()) {
                    throw new IllegalStateException("preset '" + preset.id + "' needs a reason");
                }
                if (preset.duration == null || preset.duration.isBlank()) {
                    throw new IllegalStateException("preset '" + preset.id + "' needs a duration");
                }
            }
        }
    }
}
