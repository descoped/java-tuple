package io.descoped.tuple.test;

import io.descoped.tuple.TimeUuids;
import io.descoped.tuple.Tuple;
import io.descoped.tuple.UuidV7;
import io.descoped.tuple.UuidV7Generator;
import io.descoped.tuple.UuidV8Generator;

import org.junit.jupiter.api.Test;

import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The UUIDv7 and UUIDv8 factories and the helpers for time-ordered UUIDs. */
class TimeUuidTest {

    private static final long T = 1_794_988_874_228L; // 2026-11-18

    // --- UUIDv7 with all 74 free bits random ---

    @Test
    void randomV7HasTheLayoutOfRfc9562() {
        UUID uuid = UuidV7.ofEpochMillis(T);
        assertEquals(7, uuid.version());
        assertEquals(2, uuid.variant());
        assertEquals(T, TimeUuids.millis(uuid));
        assertTrue(Math.abs(TimeUuids.millis(UuidV7.random()) - System.currentTimeMillis()) < 60_000);
    }

    @Test
    void randomV7UsesAll74FreeBits() {
        long randA = 0;
        long randB = 0;
        for (int i = 0; i < 1000; i++) {
            UUID uuid = UuidV7.ofEpochMillis(T);
            randA |= uuid.getMostSignificantBits() & 0xfff;
            randB |= uuid.getLeastSignificantBits() & 0x3fff_ffff_ffff_ffffL;
        }
        assertEquals(0xfff, randA, "every rand_a bit is set at least once");
        assertEquals(0x3fff_ffff_ffff_ffffL, randB, "every rand_b bit is set at least once");
    }

    @Test
    void randomV7RejectsTimesOutside48Bits() {
        assertThrows(IllegalArgumentException.class, () -> UuidV7.ofEpochMillis(-1));
        assertThrows(IllegalArgumentException.class, () -> UuidV7.ofEpochMillis(1L << 48));
        assertEquals((1L << 48) - 1, TimeUuids.millis(UuidV7.ofEpochMillis((1L << 48) - 1)));
    }

    // --- UUIDv7 with a counter and 62 random bits ---

    @Test
    void generatorCountsWithinAMillisecond() {
        UuidV7Generator generator = new UuidV7Generator(fixedClock(T), new SecureRandom());
        List<UUID> ids = generate(generator, 100);
        for (int i = 0; i < ids.size(); i++) {
            UUID uuid = ids.get(i);
            assertEquals(7, uuid.version());
            assertEquals(2, uuid.variant());
            assertEquals(T, TimeUuids.millis(uuid));
            assertEquals(i, uuid.getMostSignificantBits() & 0xfff, "the counter");
        }
        assertStrictlyIncreasing(ids);
    }

    @Test
    void generatorWaitsForTheNextMillisecondWhenTheCounterIsUsedUp() {
        AtomicLong calls = new AtomicLong();
        InstantSource clock = () -> Instant.ofEpochMilli(calls.incrementAndGet() <= 4096 + 10 ? T : T + 1);
        UuidV7Generator generator = new UuidV7Generator(clock, new SecureRandom());

        List<UUID> ids = generate(generator, 4097);

        assertEquals(T, TimeUuids.millis(ids.get(4095)));
        assertEquals(0xfff, ids.get(4095).getMostSignificantBits() & 0xfff);
        assertEquals(T + 1, TimeUuids.millis(ids.get(4096)), "the 4097th waits for the clock");
        assertEquals(0, ids.get(4096).getMostSignificantBits() & 0xfff);
        assertStrictlyIncreasing(ids);
    }

    @Test
    void generatorNeverGoesBackWithTheClock() {
        AtomicLong now = new AtomicLong(T);
        UuidV7Generator generator = new UuidV7Generator(() -> Instant.ofEpochMilli(now.get()), new SecureRandom());
        UUID before = generator.next();
        now.set(T - 5_000);
        UUID after = generator.next();
        assertEquals(T, TimeUuids.millis(after), "keeps the last timestamp");
        assertTrue(TimeUuids.compare(before, after) < 0);
    }

    @Test
    void generatorWaitsForAClockThatWentBackToCatchUp() {
        // The first ID at T, then the clock is 3 ms behind until the 4100th reading, then past T
        AtomicLong readings = new AtomicLong();
        InstantSource clock = () -> {
            long reading = readings.incrementAndGet();
            return Instant.ofEpochMilli(reading == 1 ? T : reading < 4100 ? T - 3 : T + 1);
        };
        UuidV7Generator generator = new UuidV7Generator(clock, new SecureRandom());

        List<UUID> ids = generate(generator, 4097);

        assertEquals(T, TimeUuids.millis(ids.get(4095)), "counts on at the last timestamp");
        assertEquals(T + 1, TimeUuids.millis(ids.get(4096)), "then waits until the clock has caught up");
        assertStrictlyIncreasing(ids);
    }

    @Test
    void generatorRejectsAClockOutside48Bits() {
        UuidV7Generator generator = new UuidV7Generator(fixedClock(-1), new SecureRandom());
        assertThrows(IllegalStateException.class, generator::next);
    }

    @Test
    void generatorIsSafeAcrossThreads() throws InterruptedException {
        UuidV7Generator generator = new UuidV7Generator();
        Set<UUID> ids = java.util.concurrent.ConcurrentHashMap.newKeySet();
        List<Thread> threads = new ArrayList<>();
        for (int t = 0; t < 8; t++) {
            Thread thread = new Thread(() -> {
                for (int i = 0; i < 10_000; i++) {
                    ids.add(generator.next());
                }
            });
            threads.add(thread);
            thread.start();
        }
        for (Thread thread : threads) {
            thread.join();
        }
        assertEquals(80_000, ids.size());
    }

    // --- UUIDv8 with a counter, a process number, a tag and 32 random bits ---

    @Test
    void v8CarriesProcessNumberAndTag() {
        UuidV8Generator generator = new UuidV8Generator(10, 1023, fixedClock(T), new SecureRandom());
        assertEquals((1 << 20) - 1, generator.maxTag());
        UUID uuid = generator.next(generator.maxTag());
        assertEquals(8, uuid.version());
        assertEquals(2, uuid.variant());
        assertEquals(T, TimeUuids.millis(uuid));
        assertEquals(1023, generator.processNumber(uuid));
        assertEquals(generator.maxTag(), generator.tag(uuid));

        UUID small = generator.next(7);
        assertEquals(1023, generator.processNumber(small));
        assertEquals(7, generator.tag(small));
        assertEquals(1, small.getMostSignificantBits() & 0xfff, "the second ID of the millisecond");
    }

    @Test
    void v8ProcessNumberMayTakeAllThirtyBits() {
        UuidV8Generator generator = new UuidV8Generator(30, (1 << 30) - 1, fixedClock(T), new SecureRandom());
        assertEquals(0, generator.maxTag());
        UUID uuid = generator.next(0);
        assertEquals((1 << 30) - 1, generator.processNumber(uuid));
        assertEquals(0, generator.tag(uuid));
    }

    @Test
    void v8RejectsWhatDoesNotFit() {
        assertThrows(IllegalArgumentException.class, () -> new UuidV8Generator(0, 0));
        assertThrows(IllegalArgumentException.class, () -> new UuidV8Generator(31, 0));
        assertThrows(IllegalArgumentException.class, () -> new UuidV8Generator(10, 1024));
        assertThrows(IllegalArgumentException.class, () -> new UuidV8Generator(10, -1));
        UuidV8Generator generator = new UuidV8Generator(10, 1);
        assertThrows(IllegalArgumentException.class, () -> generator.next(1 << 20));
        assertThrows(IllegalArgumentException.class, () -> generator.next(-1));
        UUID v7 = UuidV7.random();
        assertThrows(IllegalArgumentException.class, () -> generator.tag(v7));
    }

    @Test
    void processNumbersKeepGeneratorsApartWhatEverTheRandomBits() throws NoSuchAlgorithmException {
        // Two generators with the same clock and the same random stream: only the process number tells them apart
        UuidV8Generator first = new UuidV8Generator(10, 1, fixedClock(T), seeded(42));
        UuidV8Generator second = new UuidV8Generator(10, 2, fixedClock(T), seeded(42));
        UuidV8Generator sameNumber = new UuidV8Generator(10, 1, fixedClock(T), seeded(42));
        for (int i = 0; i < 100; i++) {
            UUID a = first.next(5);
            assertNotEquals(a, second.next(5));
            assertEquals(a, sameNumber.next(5), "a reused process number leaves only the random bits");
        }
    }

    // --- Order: UUIDv7, UUIDv8 and the lower bounds in tuple keys ---

    @Test
    void v7AndV8SortTogetherByTimeInTupleKeys() {
        UuidV7Generator v7 = new UuidV7Generator(fixedClock(T), new SecureRandom());
        UuidV8Generator v8 = new UuidV8Generator(10, 3, fixedClock(T + 1), new SecureRandom());
        List<UUID> inTimeOrder = List.of(UuidV7.ofEpochMillis(T - 1), v7.next(), v7.next(), v8.next(9), v8.next(1),
                UuidV7.ofEpochMillis(T + 2));
        List<byte[]> keys = inTimeOrder.stream().map(uuid -> Tuple.of("events", uuid).pack()).toList();
        for (int i = 1; i < keys.size(); i++) {
            assertTrue(Arrays.compareUnsigned(keys.get(i - 1), keys.get(i)) < 0, "key " + i);
        }
    }

    @Test
    void lowerBoundSeparatesMilliseconds() {
        UUID bound = TimeUuids.lowerBound(T);
        assertEquals(0, bound.version(), "a bound is not an ID");
        assertFalse(TimeUuids.isTimeOrdered(bound));
        assertEquals(bound, TimeUuids.lowerBound(Instant.ofEpochMilli(T).plusNanos(999_999)));

        UuidV8Generator v8 = new UuidV8Generator(1, 0, fixedClock(T), new SecureRandom());
        UuidV7Generator before = new UuidV7Generator(fixedClock(T - 1), new SecureRandom());
        for (int i = 0; i < 4096; i++) {
            UUID earlier = i % 2 == 0 ? UuidV7.ofEpochMillis(T - 1) : before.next();
            UUID atT = i % 2 == 0 ? UuidV7.ofEpochMillis(T) : v8.next(i % 2);
            assertTrue(packed(earlier).compareTo(packed(bound)) < 0);
            assertTrue(packed(bound).compareTo(packed(atT)) < 0);
        }
        assertThrows(IllegalArgumentException.class, () -> TimeUuids.lowerBound(-1));
        assertThrows(IllegalArgumentException.class, () -> TimeUuids.lowerBound(1L << 48));
        assertThrows(IllegalArgumentException.class, () -> TimeUuids.lowerBound(Instant.MAX));
        Instant beforeEpoch = Instant.EPOCH.minusMillis(1);
        assertThrows(IllegalArgumentException.class, () -> TimeUuids.lowerBound(beforeEpoch));
    }

    @Test
    void compareIsTheOrderOfTupleKeys() {
        Random random = new Random(7);
        for (int i = 0; i < 10_000; i++) {
            UUID a = new UUID(random.nextLong(), random.nextLong());
            UUID b = random.nextBoolean() ? new UUID(a.getMostSignificantBits(), random.nextLong())
                    : new UUID(random.nextLong(), random.nextLong());
            assertEquals(Integer.signum(packed(a).compareTo(packed(b))), Integer.signum(TimeUuids.compare(a, b)));
        }
    }

    @Test
    void millisAcceptsOnlyVersion7And8() {
        UUID v4 = UUID.randomUUID();
        assertFalse(TimeUuids.isTimeOrdered(v4));
        assertThrows(IllegalArgumentException.class, () -> TimeUuids.millis(v4));
        assertTrue(TimeUuids.isTimeOrdered(UuidV7.random()));
        assertTrue(TimeUuids.isTimeOrdered(new UuidV8Generator(1, 1).next(0)));
    }

    private static Tuple packed(UUID uuid) {
        return Tuple.of(uuid);
    }

    private static InstantSource fixedClock(long millis) {
        return () -> Instant.ofEpochMilli(millis);
    }

    private static SecureRandom seeded(long seed) throws NoSuchAlgorithmException {
        SecureRandom random = SecureRandom.getInstance("SHA1PRNG");
        random.setSeed(seed); // before the first use, so the stream is deterministic
        return random;
    }

    private static List<UUID> generate(UuidV7Generator generator, int count) {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ids.add(generator.next());
        }
        assertEquals(count, new HashSet<>(ids).size());
        return ids;
    }

    private static void assertStrictlyIncreasing(List<UUID> ids) {
        for (int i = 1; i < ids.size(); i++) {
            assertTrue(TimeUuids.compare(ids.get(i - 1), ids.get(i)) < 0, "ID " + i);
        }
    }
}
