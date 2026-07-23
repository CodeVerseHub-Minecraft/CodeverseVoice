package net.codeverse.voice.paper.moderation;

import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.mp3.Mp3Encoder;
import de.maxhenkel.voicechat.api.opus.OpusDecoder;
import net.codeverse.voice.config.PluginConfig;
import net.codeverse.voice.moderation.AudioRingBuffer;
import net.codeverse.voice.storage.VoiceBanRepository;

import javax.sound.sampled.AudioFormat;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Keeps a short rolling buffer of recent speech and writes an excerpt to disk
 * only when staff explicitly capture an incident.
 *
 * Two properties are deliberate and should survive any future change.
 *
 * Nothing reaches disk without an explicit capture. The rolling buffer exists
 * entirely in memory and older audio is discarded continuously, so the plugin
 * is not an archive of everything anyone has ever said.
 *
 * Every capture carries an expiry. Recorded speech is personal data under the
 * GDPR and the Swiss FADP, and an evidence file with no deletion date is a
 * liability that outlives its usefulness. The retention window is enforced by a
 * sweep rather than left to whoever remembers to clean up.
 */
public final class RecordingService {

    /** Simple Voice Chat produces 48 kHz mono 16 bit audio. */
    private static final AudioFormat FORMAT = new AudioFormat(48000f, 16, 1, true, false);

    private static final DateTimeFormatter FILE_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private final PluginConfig.Recording settings;
    private final VoiceBanRepository repository;
    private final Path directory;
    private final Map<UUID, AudioRingBuffer> buffers = new ConcurrentHashMap<>();
    private volatile VoicechatApi api;

    public RecordingService(PluginConfig.Recording settings, VoiceBanRepository repository, Path dataDirectory)
            throws IOException {
        this.settings = settings;
        this.repository = repository;
        this.directory = dataDirectory.resolve(settings.outputDirectory);
        if (settings.enabled) {
            Files.createDirectories(directory);
        }
    }

    public void attachApi(VoicechatApi api) {
        this.api = api;
    }

    public boolean isEnabled() {
        return settings.enabled;
    }

    /** Buffers one frame of speech. Called on the voice thread, so it stays cheap. */
    public void record(UUID speakerMinecraftId, byte[] opusFrame) {
        if (!settings.enabled) {
            return;
        }
        buffers.computeIfAbsent(speakerMinecraftId,
                        key -> new AudioRingBuffer(settings.bufferSeconds * 1000))
                .add(opusFrame, System.currentTimeMillis());
    }

    /** Discards a speaker's buffered audio when their session ends. */
    public void forget(UUID speakerMinecraftId) {
        AudioRingBuffer buffer = buffers.remove(speakerMinecraftId);
        if (buffer != null) {
            buffer.clear();
        }
    }

    public void forgetAll() {
        buffers.values().forEach(AudioRingBuffer::clear);
        buffers.clear();
    }

    public long bufferedMillis(UUID speakerMinecraftId) {
        AudioRingBuffer buffer = buffers.get(speakerMinecraftId);
        return buffer == null ? 0L : buffer.bufferedMillis();
    }

    /**
     * Writes the buffered audio for one speaker to an mp3 and records the
     * capture, including its deletion date.
     *
     * @return the file name written, or empty when there was nothing buffered
     */
    public Optional<String> capture(UUID speakerMinecraftId, UUID speakerInternalId, UUID capturedBy, String note)
            throws IOException, SQLException {
        if (!settings.enabled) {
            return Optional.empty();
        }
        VoicechatApi localApi = api;
        if (localApi == null) {
            throw new IllegalStateException("voice chat api is not available yet");
        }
        AudioRingBuffer buffer = buffers.get(speakerMinecraftId);
        if (buffer == null || buffer.isEmpty()) {
            return Optional.empty();
        }

        List<AudioRingBuffer.Frame> frames = buffer.snapshot();
        String fileName = FILE_STAMP.format(Instant.now()) + "-" + speakerMinecraftId + ".mp3";
        Path target = directory.resolve(fileName);

        OpusDecoder decoder = localApi.createDecoder();
        try (OutputStream output = Files.newOutputStream(target)) {
            Mp3Encoder encoder = localApi.createMp3Encoder(FORMAT, settings.mp3Bitrate, 1, output);
            try {
                for (AudioRingBuffer.Frame frame : frames) {
                    short[] pcm = decoder.decode(frame.opusData());
                    if (pcm != null && pcm.length > 0) {
                        encoder.encode(pcm);
                    }
                }
            } finally {
                encoder.close();
            }
        } finally {
            decoder.close();
        }

        int durationMillis = frames.size() * AudioRingBuffer.FRAME_MILLIS;
        long expiresAt = settings.retentionDays <= 0
                ? 0L
                : System.currentTimeMillis() + settings.retentionDays * 86_400_000L;

        repository.recordCapture(speakerInternalId, capturedBy, System.currentTimeMillis(),
                durationMillis, fileName, note, expiresAt);
        repository.audit(capturedBy, speakerInternalId, "CAPTURE",
                durationMillis + "ms to " + fileName);

        return Optional.of(fileName);
    }

    /**
     * Deletes captures whose retention window has passed, then removes their
     * rows. Files first: a leftover row pointing at a deleted file is a
     * harmless gap in the log, while a leftover file with no row is data nobody
     * knows they are still holding.
     */
    public int purgeExpiredCaptures() {
        if (!settings.enabled || settings.retentionDays <= 0) {
            return 0;
        }
        int deleted = 0;
        try {
            for (String fileName : repository.expiredCaptureFiles()) {
                Path path = directory.resolve(fileName);
                try {
                    if (Files.deleteIfExists(path)) {
                        deleted++;
                    }
                } catch (IOException ignored) {
                    // Left for the next sweep rather than failing the whole run.
                }
            }
            repository.deleteExpiredCaptureRows();
        } catch (SQLException failure) {
            return deleted;
        }
        return deleted;
    }

    /** Total size of stored captures, used to warn before the disk fills. */
    public long directoryMegabytes() {
        if (!settings.enabled) {
            return 0L;
        }
        try (Stream<Path> files = Files.walk(directory)) {
            long bytes = files.filter(Files::isRegularFile).mapToLong(path -> {
                try {
                    return Files.size(path);
                } catch (IOException unreadable) {
                    return 0L;
                }
            }).sum();
            return bytes / (1024L * 1024L);
        } catch (IOException failure) {
            return 0L;
        }
    }

    public boolean isOverDiskBudget() {
        return settings.maximumDirectoryMegabytes > 0
                && directoryMegabytes() > settings.maximumDirectoryMegabytes;
    }

    public Path recordingDirectory() {
        return directory;
    }
}
