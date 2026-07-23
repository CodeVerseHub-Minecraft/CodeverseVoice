package net.codeverse.voice.moderation;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * A fixed duration ring buffer of recent audio frames for one speaker.
 *
 * Voice incidents are reported after they happen, never during, so a
 * moderation system that only records on demand captures the aftermath and
 * misses the offence. This keeps the most recent few seconds in memory at all
 * times and discards everything older, which means a report can reach back to
 * what was actually said.
 *
 * Nothing here is written to disk. Persistence happens only when a capture is
 * explicitly requested, which is what keeps continuous retention of everyone's
 * audio out of the design.
 *
 * Frames are stored still Opus encoded. Decoding every frame from every
 * speaker continuously would cost far more than it saves, and the decode is
 * only needed for the small fraction that is ever captured.
 */
public final class AudioRingBuffer {

    /** Simple Voice Chat sends 20 millisecond frames. */
    public static final int FRAME_MILLIS = 20;

    private final int capacityFrames;
    private final Deque<Frame> frames = new ArrayDeque<>();
    private long lastFrameAt;

    /**
     * @param retentionMillis how far back the buffer reaches
     */
    public AudioRingBuffer(int retentionMillis) {
        if (retentionMillis < FRAME_MILLIS) {
            throw new IllegalArgumentException(
                    "retention must be at least one frame (" + FRAME_MILLIS + "ms), got " + retentionMillis);
        }
        this.capacityFrames = retentionMillis / FRAME_MILLIS;
    }

    /**
     * Appends a frame, evicting the oldest once capacity is reached. The
     * timestamp is supplied rather than read from the clock so that the buffer
     * is deterministic under test.
     */
    public synchronized void add(byte[] opusFrame, long timestampMillis) {
        if (opusFrame == null || opusFrame.length == 0) {
            return;
        }
        while (frames.size() >= capacityFrames) {
            frames.pollFirst();
        }
        frames.addLast(new Frame(opusFrame.clone(), timestampMillis));
        lastFrameAt = timestampMillis;
    }

    /** Snapshot of the buffered frames, oldest first. */
    public synchronized List<Frame> snapshot() {
        return new ArrayList<>(frames);
    }

    /** Snapshot limited to frames captured within the given window. */
    public synchronized List<Frame> snapshotSince(long earliestTimestamp) {
        List<Frame> out = new ArrayList<>();
        for (Frame frame : frames) {
            if (frame.timestamp() >= earliestTimestamp) {
                out.add(frame);
            }
        }
        return out;
    }

    public synchronized int size() {
        return frames.size();
    }

    public synchronized boolean isEmpty() {
        return frames.isEmpty();
    }

    public synchronized long lastFrameAt() {
        return lastFrameAt;
    }

    /** Duration of buffered audio in milliseconds. */
    public synchronized long bufferedMillis() {
        return (long) frames.size() * FRAME_MILLIS;
    }

    public int capacityFrames() {
        return capacityFrames;
    }

    /**
     * Discards everything held. Called when a speaker disconnects so their
     * audio does not linger in memory beyond their session.
     */
    public synchronized void clear() {
        frames.clear();
        lastFrameAt = 0L;
    }

    public record Frame(byte[] opusData, long timestamp) {
    }
}
