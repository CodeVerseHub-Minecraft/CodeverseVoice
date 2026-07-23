package net.codeverse.voice.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginConfigTest {

    @Test
    void writesDefaultsOnFirstRun(@TempDir Path directory) throws IOException {
        PluginConfig config = PluginConfig.load(directory);
        assertTrue(Files.exists(directory.resolve("config.json")));
        assertEquals(30, config.recording.bufferSeconds);
        assertEquals(30, config.recording.retentionDays);
        assertTrue(config.access.requireVerifiedOrigin);
    }

    @Test
    void preservesExistingValuesAcrossReload(@TempDir Path directory) throws IOException {
        PluginConfig first = PluginConfig.load(directory);
        first.recording.retentionDays = 7;
        Files.writeString(directory.resolve("config.json"),
                new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(first),
                StandardCharsets.UTF_8);

        PluginConfig reloaded = PluginConfig.load(directory);
        assertEquals(7, reloaded.recording.retentionDays);
    }

    @Test
    void rejectsUnboundedRecordingBuffer() {
        PluginConfig config = new PluginConfig();
        config.recording.bufferSeconds = 3600;
        assertThrows(IllegalStateException.class, config::validate);
    }

    @Test
    void rejectsNegativeRetention() {
        PluginConfig config = new PluginConfig();
        config.recording.retentionDays = -1;
        assertThrows(IllegalStateException.class, config::validate);
    }

    @Test
    void rejectsMonitoringWindowsThatCannotWarnBeforeStopping() {
        PluginConfig config = new PluginConfig();
        config.monitoring.automaticStopSeconds = 60;
        config.monitoring.warningBeforeStopSeconds = 60;
        assertThrows(IllegalStateException.class, config::validate);
    }

    @Test
    void rejectsEmptyTrustedTierList() {
        PluginConfig config = new PluginConfig();
        config.access.trustedTiers = java.util.List.of();
        assertThrows(IllegalStateException.class, config::validate);
    }

    @Test
    void rejectsRangeRulesWithoutAPermission() {
        PluginConfig config = new PluginConfig();
        config.ranges.rules = java.util.List.of(new PluginConfig.RangeRule("", 64f));
        assertThrows(IllegalStateException.class, config::validate);
    }

    @Test
    void rejectsMalformedJson(@TempDir Path directory) throws IOException {
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("config.json"), "{ not json", StandardCharsets.UTF_8);
        assertThrows(Exception.class, () -> PluginConfig.load(directory));
    }
}
