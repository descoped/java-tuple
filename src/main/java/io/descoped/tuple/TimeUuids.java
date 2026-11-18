package io.descoped.tuple;

import java.time.Instant;
import java.util.UUID;

/**
 * Helpers for time-ordered UUIDs: UUIDv7, and UUIDv8 with the layout of {@link UuidV8Generator}. Both start with a
 * 48-bit Unix timestamp in milliseconds, so they sort by time when compared as unsigned bytes, which is how tuple keys
 * and PostgreSQL compare them.
 *
 * <p>See {@code docs/uuid-generators.md} for the layouts and the trade-offs.
 */
public final class TimeUuids {

    /** The largest Unix time in milliseconds that fits the 48-bit timestamp, in the year 10889. */
    static final long MAX_MILLIS = (1L << 48) - 1;

    private static final int VERSION_7 = 7;
    private static final int VERSION_8 = 8;
    private static final int RFC_9562_VARIANT = 2;

    private TimeUuids() {
    }

    /**
     * The Unix time in milliseconds at the start of a UUIDv7 or a UUIDv8.
     *
     * @param uuid a UUIDv7 or UUIDv8
     * @return the 48-bit timestamp
     * @throws IllegalArgumentException if {@code uuid} is not a UUIDv7 or UUIDv8 with the RFC 9562 variant
     */
    public static long millis(UUID uuid) {
        if (!isTimeOrdered(uuid)) {
            throw new IllegalArgumentException("Not a UUIDv7 or UUIDv8: " + uuid);
        }
        return uuid.getMostSignificantBits() >>> 16;
    }

    /**
     * Whether a UUID is a UUIDv7 or UUIDv8 with the RFC 9562 variant, the versions that put the time first.
     *
     * <p>A UUIDv8 from another source may hold anything; this checks the version, not the layout.
     *
     * @param uuid the UUID to check
     * @return {@code true} for a UUIDv7 or UUIDv8
     */
    public static boolean isTimeOrdered(UUID uuid) {
        return uuid.variant() == RFC_9562_VARIANT && (uuid.version() == VERSION_7 || uuid.version() == VERSION_8);
    }

    /**
     * The smallest UUID of a millisecond: every UUIDv7 or UUIDv8 made at {@code millis} or later sorts at or after it,
     * every one made before sorts before it. Use it as a range bound, inclusive at the start and exclusive at the end.
     * It is never an ID: its version is 0.
     *
     * @param millis Unix time in milliseconds
     * @return the bound
     * @throws IllegalArgumentException if {@code millis} is before 1970 or does not fit 48 bits
     */
    public static UUID lowerBound(long millis) {
        return new UUID(requireMillis(millis) << 16, 0L);
    }

    /**
     * The smallest UUID of the millisecond that holds {@code instant}. See {@link #lowerBound(long)}.
     *
     * @param instant the point in time, truncated to the millisecond
     * @return the bound
     * @throws IllegalArgumentException if the instant is before 1970 or after the year 10889
     */
    public static UUID lowerBound(Instant instant) {
        long millis;
        try {
            millis = instant.toEpochMilli();
        } catch (ArithmeticException tooFarFromEpoch) {
            throw new IllegalArgumentException("Outside the 48-bit Unix time of a UUID: " + instant, tooFarFromEpoch);
        }
        return lowerBound(millis);
    }

    /**
     * Compares two UUIDs as 16 unsigned bytes: the order of tuple keys and of PostgreSQL. {@link UUID#compareTo}
     * compares signed longs instead, which differs for UUIDs in general.
     *
     * @param a the first UUID
     * @param b the second UUID
     * @return negative, zero or positive as {@code a} sorts before, with or after {@code b}
     */
    public static int compare(UUID a, UUID b) {
        int byHigh = Long.compareUnsigned(a.getMostSignificantBits(), b.getMostSignificantBits());
        return byHigh == 0 ? Long.compareUnsigned(a.getLeastSignificantBits(), b.getLeastSignificantBits()) : byHigh;
    }

    static long requireMillis(long millis) {
        if (millis < 0 || millis > MAX_MILLIS) {
            throw new IllegalArgumentException("A 48-bit Unix time in milliseconds is 0.." + MAX_MILLIS + ", got " + millis);
        }
        return millis;
    }
}
