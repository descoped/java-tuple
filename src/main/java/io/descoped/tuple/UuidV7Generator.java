package io.descoped.tuple;

import java.security.SecureRandom;
import java.time.InstantSource;
import java.util.Objects;
import java.util.UUID;

/**
 * RFC 9562 UUIDv7 in creation order: a 48-bit Unix timestamp in milliseconds, a 12-bit counter (method 1 of RFC 9562
 * section 6.2), then 62 random bits from {@link SecureRandom}.
 *
 * <p>Every UUID a generator returns sorts after the one before, even within a millisecond and when the clock goes
 * back. Up to 4096 UUIDs per millisecond; the next one waits for the clock to reach the following millisecond, so the
 * timestamp is never ahead of the clock. Use one generator per process. Thread-safe.
 *
 * <p>The counter costs 12 random bits: 62 remain, against 74 in {@link UuidV7#random()}. Generators in different
 * processes are kept apart by those 62 bits alone.
 */
public final class UuidV7Generator {

    private final MillisecondCounter counter;
    private final SecureRandom random;

    /** A generator on the system clock and a new {@link SecureRandom}. */
    public UuidV7Generator() {
        this(InstantSource.system(), new SecureRandom());
    }

    /**
     * A generator on the given clock and randomness, for tests and for clocks other than the system's.
     *
     * @param clock  the source of the time
     * @param random the source of the random bits
     */
    public UuidV7Generator(InstantSource clock, SecureRandom random) {
        this.counter = new MillisecondCounter(Objects.requireNonNull(clock, "clock"));
        this.random = Objects.requireNonNull(random, "random");
    }

    /**
     * The next UUIDv7.
     *
     * @return a UUIDv7 that sorts after every UUID this generator returned before
     * @throws IllegalStateException if the clock is before 1970 or after the year 10889
     */
    public synchronized UUID next() {
        long msb = counter.next() | UuidV7.VERSION_7;
        long lsb = UuidV7.VARIANT | (random.nextLong() & UuidV7.RAND_B);
        return new UUID(msb, lsb);
    }
}
