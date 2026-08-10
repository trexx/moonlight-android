package com.limelight.binding.video;

/**
 * A small fixed-capacity ring of decoded output buffer indices, awaiting presentation on a vsync.
 *
 * <p>Used in {@code FRAME_PACING_BALANCED} only. It replaces a {@code LinkedBlockingQueue<Integer>}
 * for two reasons, one of which matters much more than the other.
 *
 * <h2>The reason that matters: the eviction race</h2>
 * The original code evicted with two separate operations:
 *
 * <pre>
 *     if (outputBufferQueue.size() == OUTPUT_BUFFER_QUEUE_LIMIT) {
 *         videoDecoder.releaseOutputBuffer(outputBufferQueue.take(), false);
 *     }
 * </pre>
 *
 * The renderer thread is the only producer, so if the Choreographer thread drained both entries
 * between the {@code size()} and the {@code take()} — two vsyncs presenting one frame each, which
 * is ordinary at 60 FPS on a 60 Hz display — {@code take()} blocked forever waiting for a producer
 * that was the blocked thread itself. That stalled the decoder and deadlocked any concurrent codec
 * recovery, because the blocked thread never reached its {@code doCodecRecoveryIfRequired()} call.
 *
 * <p>{@link #offerEvictingOldest} is a single call that evicts and inserts together, so there is no
 * window between the two for anything to change. The race is not merely fixed, it is no longer
 * expressible.
 *
 * <h2>The reason that matters less: allocation</h2>
 * The old queue allocated a node per frame, plus an {@code Integer} box — about 40 bytes together,
 * so roughly 2.4 KB/s at 60 FPS. That is instantly-dead TLAB garbage and costs essentially nothing
 * to collect, since ART's collector charges by surviving bytes rather than allocated ones. Storing
 * {@code int} in a pre-allocated array removes it, but do not mistake that for the point of this
 * class.
 *
 * <h2>Why this is synchronised rather than lock-free</h2>
 * A single-producer/single-consumer ring with {@code volatile} head and tail would be the textbook
 * approach and needs no locks — but it does not apply here, because <b>both</b> threads remove from
 * the head. The consumer removes to present, and the producer removes to evict. Eviction has to
 * happen producer-side: the consumer may not run for a long time when the stream rate and the
 * display rate are far apart, and buffers held meanwhile starve the decoder.
 *
 * <p>Two removers means head cannot be owned by one thread, which rules out the plain SPSC pattern.
 * The alternatives are a CAS loop on head — where reading a slot before winning the CAS opens a
 * window for the producer to overwrite it — or dropping the newest frame instead of the oldest,
 * which would raise latency and defeats the purpose of the queue. A monitor is the honest choice.
 * It is uncontended in the common case, which on ARM is a thin-lock CAS of a few nanoseconds
 * against a 16,667 µs frame budget, and it is correct.
 *
 * <h2>Invariant</h2>
 * {@link #clear()} is called from the codec recovery barrier, which runs only once every
 * codec-touching thread has quiesced. It is safe regardless thanks to the monitor, but the buffers
 * it discards are only reclaimable because the codec is about to be flushed or reset.
 */
class OutputBufferRing {

    /** Returned when there is nothing to take. Real buffer indices are never negative. */
    static final int EMPTY = -1;

    private final int[] indices;

    private final int mask;
    private final int capacity;

    private int head;
    private int count;

    /**
     * @param requestedCapacity maximum frames held before the oldest is evicted. Rounded up to a
     *                          power of two so the wrap is a mask rather than a division.
     */
    OutputBufferRing(int requestedCapacity) {
        int cap = Integer.highestOneBit(Math.max(1, requestedCapacity));
        if (cap < requestedCapacity) {
            cap <<= 1;
        }

        this.capacity = cap;
        this.mask = cap - 1;
        this.indices = new int[cap];
    }

    /**
     * Inserts a buffer, evicting the oldest if the ring is already full.
     *
     * <p>Never blocks. The eviction and the insertion are one operation, which is what makes the
     * old check-then-take race impossible.
     *
     * @param bufferIndex the decoder output buffer index to hold
     * @return the evicted buffer index, which the caller <b>must</b> release back to the codec, or
     *         {@link #EMPTY} if nothing was evicted
     */
    synchronized int offerEvictingOldest(int bufferIndex) {
        int evicted = EMPTY;

        if (count == capacity) {
            evicted = indices[head];
            head = (head + 1) & mask;
            count--;
        }

        int tail = (head + count) & mask;
        indices[tail] = bufferIndex;
        count++;

        return evicted;
    }

    /**
     * Removes and returns the oldest buffer. Never blocks.
     *
     * @return the buffer index, or {@link #EMPTY} if the ring is empty
     */
    synchronized int poll() {
        if (count == 0) {
            return EMPTY;
        }

        int bufferIndex = indices[head];
        head = (head + 1) & mask;
        count--;

        return bufferIndex;
    }

    /** @return how many buffers are currently held */
    synchronized int size() {
        return count;
    }

    /**
     * Discards every held buffer without releasing it.
     *
     * <p>Only correct when the codec is about to be flushed, restarted or reset, since that is what
     * reclaims the buffers being dropped here. Called from the codec recovery barrier.
     */
    synchronized void clear() {
        head = 0;
        count = 0;
    }
}
