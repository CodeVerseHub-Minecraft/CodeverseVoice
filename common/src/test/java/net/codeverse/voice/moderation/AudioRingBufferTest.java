package net.codeverse.voice.moderation;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ring buffer holds people's speech in memory, so the property that
 * matters most is that it forgets. A buffer that grows without bound turns a
 * moderation feature into indefinite retention of everything said on the
 * server, which is the exact outcome the design is meant to avoid.
 */
class AudioRingBufferTest {

    private static byte[] frame(int marker) {
        return new byte[]{(byte) marker, 0x01, 0x02};
    }

    @Test
    void retainsRecentFramesInOrder() {
        AudioRingBuffer buffer = new AudioRingBuffer(200);
        buffer.add(frame(1), 1000L);
        buffer.add(frame(2), 1020L);
        buffer.add(frame(3), 1040L);

        List<AudioRingBuffer.Frame> frames = buffer.snapshot();
        assertEquals(3, frames.size());
        assertArrayEquals(frame(1), frames.get(0).opusData());
        assertArrayEquals(frame(3), frames.get(2).opusData());
    }

    @Test
    void evictsOldestOnceCapacityIsReached() {
        AudioRingBuffer buffer = new AudioRingBuffer(100);
        assertEquals(5, buffer.capacityFrames());

        for (int i = 0; i < 12; i++) {
            buffer.add(frame(i), 1000L + i * 20L);
        }

        assertEquals(5, buffer.size());
        List<AudioRingBuffer.Frame> frames = buffer.snapshot();
        assertArrayEquals(frame(7), frames.get(0).opusData());
        assertArrayEquals(frame(11), frames.get(4).opusData());
    }

    @Test
    void neverExceedsConfiguredRetention() {
        AudioRingBuffer buffer = new AudioRingBuffer(1000);
        for (int i = 0; i < 5000; i++) {
            buffer.add(frame(i % 127), i * 20L);
        }
        assertTrue(buffer.bufferedMillis() <= 1000L);
    }

    @Test
    void filtersToATimeWindow() {
        AudioRingBuffer buffer = new AudioRingBuffer(1000);
        buffer.add(frame(1), 1000L);
        buffer.add(frame(2), 2000L);
        buffer.add(frame(3), 3000L);

        assertEquals(2, buffer.snapshotSince(2000L).size());
        assertEquals(0, buffer.snapshotSince(9000L).size());
    }

    @Test
    void copiesFramesSoLaterMutationCannotCorruptTheBuffer() {
        AudioRingBuffer buffer = new AudioRingBuffer(200);
        byte[] original = frame(9);
        buffer.add(original, 1000L);
        original[0] = 0x7F;

        assertEquals(9, buffer.snapshot().get(0).opusData()[0]);
    }

    @Test
    void clearDiscardsEverything() {
        AudioRingBuffer buffer = new AudioRingBuffer(200);
        buffer.add(frame(1), 1000L);
        buffer.clear();
        assertTrue(buffer.isEmpty());
        assertEquals(0L, buffer.lastFrameAt());
    }

    @Test
    void ignoresEmptyFrames() {
        AudioRingBuffer buffer = new AudioRingBuffer(200);
        buffer.add(null, 1000L);
        buffer.add(new byte[0], 1000L);
        assertTrue(buffer.isEmpty());
    }

    @Test
    void rejectsRetentionShorterThanOneFrame() {
        assertThrows(IllegalArgumentException.class, () -> new AudioRingBuffer(10));
    }

    @Test
    void tracksTheMostRecentFrameTime() {
        AudioRingBuffer buffer = new AudioRingBuffer(200);
        buffer.add(frame(1), 5000L);
        buffer.add(frame(2), 5020L);
        assertEquals(5020L, buffer.lastFrameAt());
        assertFalse(buffer.isEmpty());
    }
}
