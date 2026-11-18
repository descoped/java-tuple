package io.descoped.tuple;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * RFC 9562 UUIDv7 with every free bit random: a 48-bit Unix timestamp in milliseconds, then 74 random bits from
 * {@link SecureRandom}. The most entropy a UUIDv7 can hold, and no state: safe to call from any thread.
 *
 * <p>The IDs sort by time to the millisecond; IDs made within the same millisecond sort in random order. When one
 * process must read its IDs back in exactly the order it made them, use {@link UuidV7Generator}.
 *
 * <p>The same as {@code UUID.ofEpochMillis} from JDK 26 on.
 */
public final class UuidV7 {

    /** The version bits, 0111, in the most significant half. */
    static final long VERSION_7 = 0x7000L;
    /** The RFC 9562 variant bits, 10, at the top of the least significant half. */
    static final long VARIANT = 0x8000_0000_0000_0000L;
    /** The 62 bits of the least significant half below the variant. */
    static final long RAND_B = 0x3fff_ffff_ffff_ffffL;

    private static final long RAND_A = 0xfffL;
    private static final SecureRandom RANDOM = new SecureRandom();

    private UuidV7() {
    }

    /**
     * A UUIDv7 for the current time.
     *
     * @return a new UUIDv7
     */
    public static UUID random() {
        return ofEpochMillis(System.currentTimeMillis());
    }

    /**
     * A UUIDv7 for a given time.
     *
     * @param millis Unix time in milliseconds, 0 to 2<sup>48</sup> - 1
     * @return a new UUIDv7
     * @throws IllegalArgumentException if {@code millis} is negative or does not fit 48 bits
     */
    public static UUID ofEpochMillis(long millis) {
        long msb = (TimeUuids.requireMillis(millis) << 16) | VERSION_7 | (RANDOM.nextLong() & RAND_A);
        long lsb = VARIANT | (RANDOM.nextLong() & RAND_B);
        return new UUID(msb, lsb);
    }
}
