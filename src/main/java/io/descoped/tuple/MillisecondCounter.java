package io.descoped.tuple;

import java.time.InstantSource;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/**
 * The timestamp and counter of a time-ordered UUID generator: the first 64 bits of a UUIDv7 made by RFC 9562 method 1,
 * and of the UUIDv8 layout of {@link UuidV8Generator}, with the four version bits left 0.
 *
 * <p>Successive values strictly increase. Within one millisecond the 12-bit counter numbers them; when it is used up,
 * the counter waits for the clock to reach the next millisecond, so the timestamp is never ahead of the clock. If the
 * clock goes back, the counter keeps the last timestamp and counts on; once the counter is used up, it sleeps until the
 * clock has caught up.
 *
 * <p>Not thread-safe: the generators that use it synchronize.
 */
final class MillisecondCounter {

    private static final int MAX_COUNTER = 0xfff;

    private final InstantSource clock;
    private long lastMillis = -1;
    private int counter;

    MillisecondCounter(InstantSource clock) {
        this.clock = clock;
    }

    /**
     * The next timestamp and counter, laid out as the most significant half of the UUID.
     *
     * @return the 48-bit timestamp in bits 63..16, the 12-bit counter in bits 11..0, and 0 in the version bits
     * @throws IllegalStateException if the clock is before 1970 or after the year 10889
     */
    long next() {
        long now = currentMillis();
        if (now > lastMillis) {
            lastMillis = now;
            counter = 0;
        } else if (counter < MAX_COUNTER) {
            counter++; // the same millisecond, or the clock went back
        } else {
            lastMillis = waitForMillisAfter(lastMillis);
            counter = 0;
        }
        return (lastMillis << 16) | counter;
    }

    private long waitForMillisAfter(long millis) {
        long now = currentMillis();
        while (now <= millis) {
            long behind = millis - now; // 0 when the counter ran out within the current millisecond
            if (behind > 0) {
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(behind)); // the clock went back: sleep, don't spin
            } else {
                Thread.onSpinWait();
            }
            now = currentMillis();
        }
        return now;
    }

    private long currentMillis() {
        long millis = clock.millis();
        if (millis < 0 || millis > TimeUuids.MAX_MILLIS) {
            throw new IllegalStateException("The clock is outside the 48-bit Unix time of a UUID: " + millis);
        }
        return millis;
    }
}
