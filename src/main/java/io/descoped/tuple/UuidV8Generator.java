package io.descoped.tuple;

import java.security.SecureRandom;
import java.time.InstantSource;
import java.util.Objects;
import java.util.UUID;

/**
 * RFC 9562 UUIDv8 that carries a process number and a tag, in time order. The layout keeps the UUIDv7 timestamp, so
 * these IDs sort by time, and together with UUIDv7s:
 *
 * <pre>
 * bits   0–47   Unix time in milliseconds   48
 * bits  48–51   version 1000                 4
 * bits  52–63   counter                     12   per millisecond, as in {@link UuidV7Generator}
 * bits  64–65   variant 10                   2
 * bits  66–95   process number, then tag    30   split by {@code processBits}
 * bits  96–127  random                      32
 * </pre>
 *
 * <p>The counter keeps the IDs of one generator unique, and the process number keeps generators apart: as long as no
 * two running generators share a process number, no two IDs can be equal. The random bits are not needed for
 * uniqueness then; they make IDs harder to guess and are a last line of defence if a process number is reused.
 *
 * <p>Use one generator per process. The split between process number and tag is part of the format: choose it once
 * and never change it for IDs already issued. Thread-safe.
 *
 * <p>If the clock goes back, the generator keeps its last timestamp and counts on, so IDs never decrease. After 4096
 * such IDs it waits, holding its lock, until the clock has caught up: a clock stepped back by a minute can stall a busy
 * generator, and every thread that uses it, for up to that minute.
 */
public final class UuidV8Generator {

    /** The bits for the process number and the tag together. */
    public static final int PROCESS_AND_TAG_BITS = 30;

    private static final long VERSION_8 = 0x8000L;
    private static final int RANDOM_BITS = 32;

    private final int tagBits;
    private final int processNumber;
    private final MillisecondCounter counter;
    private final SecureRandom random;

    /**
     * A generator on the system clock and a new {@link SecureRandom}.
     *
     * @param processBits   the bits for the process number, 1 to 30; the tag gets the rest
     * @param processNumber this process's number, unique among the generators running at the same time
     * @throws IllegalArgumentException if {@code processBits} is not 1 to 30, or the number does not fit it
     */
    public UuidV8Generator(int processBits, int processNumber) {
        this(processBits, processNumber, InstantSource.system(), new SecureRandom());
    }

    /**
     * A generator on the given clock and randomness, for tests and for clocks other than the system's.
     *
     * @param processBits   the bits for the process number, 1 to 30; the tag gets the rest
     * @param processNumber this process's number, unique among the generators running at the same time
     * @param clock         the source of the time
     * @param random        the source of the random bits
     * @throws IllegalArgumentException if {@code processBits} is not 1 to 30, or the number does not fit it
     */
    public UuidV8Generator(int processBits, int processNumber, InstantSource clock, SecureRandom random) {
        if (processBits < 1 || processBits > PROCESS_AND_TAG_BITS) {
            throw new IllegalArgumentException("processBits must be 1.." + PROCESS_AND_TAG_BITS + ", got " + processBits);
        }
        requireInRange("processNumber", processNumber, maxValue(processBits));
        this.tagBits = PROCESS_AND_TAG_BITS - processBits;
        this.processNumber = processNumber;
        this.counter = new MillisecondCounter(Objects.requireNonNull(clock, "clock"));
        this.random = Objects.requireNonNull(random, "random");
    }

    /**
     * The next UUIDv8.
     *
     * @param tag the metadata to carry, 0 to {@link #maxTag()}
     * @return a UUIDv8 that sorts after every UUID this generator returned before
     * @throws IllegalArgumentException if the tag does not fit
     * @throws IllegalStateException    if the clock is before 1970 or after the year 10889
     */
    public synchronized UUID next(int tag) {
        requireInRange("tag", tag, maxTag());
        long msb = counter.next() | VERSION_8;
        long processAndTag = ((long) processNumber << tagBits) | tag;
        long lsb = UuidV7.VARIANT | (processAndTag << RANDOM_BITS) | Integer.toUnsignedLong(random.nextInt());
        return new UUID(msb, lsb);
    }

    /**
     * The largest tag this layout holds.
     *
     * @return 2<sup>tag bits</sup> - 1, or 0 when the process number takes all 30 bits
     */
    public int maxTag() {
        return maxValue(tagBits);
    }

    /**
     * The process number of an ID made with this layout.
     *
     * @param uuid a UUIDv8 made by a generator with the same {@code processBits}
     * @return its process number
     * @throws IllegalArgumentException if {@code uuid} is not a UUIDv8
     */
    public int processNumber(UUID uuid) {
        return (int) (processAndTag(uuid) >>> tagBits);
    }

    /**
     * The tag of an ID made with this layout.
     *
     * @param uuid a UUIDv8 made by a generator with the same {@code processBits}
     * @return its tag
     * @throws IllegalArgumentException if {@code uuid} is not a UUIDv8
     */
    public int tag(UUID uuid) {
        return (int) (processAndTag(uuid) & maxTag());
    }

    private static long processAndTag(UUID uuid) {
        if (uuid.version() != 8 || uuid.variant() != 2) {
            throw new IllegalArgumentException("Not an RFC 9562 UUIDv8: " + uuid);
        }
        return (uuid.getLeastSignificantBits() >>> RANDOM_BITS) & maxValue(PROCESS_AND_TAG_BITS);
    }

    private static int maxValue(int bits) {
        return (1 << bits) - 1;
    }

    private static void requireInRange(String name, int value, int max) {
        if (value < 0 || value > max) {
            throw new IllegalArgumentException(name + " must be 0.." + max + ", got " + value);
        }
    }
}
