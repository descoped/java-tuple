package io.descoped.tuple.test;

import io.descoped.tuple.Range;
import io.descoped.tuple.Tuple;
import io.descoped.tuple.Versionstamp;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The public API of {@link Tuple}, {@link Versionstamp} and {@link Range}. */
class TupleTest {

    @Test
    void roundTripsEveryElementType() {
        UUID uuid = UUID.randomUUID();
        Versionstamp versionstamp = new Versionstamp(123_456_789L, 7, 1);
        Tuple tuple = Tuple.of(null, new byte[]{0, 1, 2}, "text", 42L, BigInteger.TEN.pow(40), 1.5f, 2.5, true,
                uuid, versionstamp, Tuple.of("nested", null));

        Tuple decoded = Tuple.fromBytes(tuple.pack());

        assertEquals(tuple, decoded);
        assertTrue(decoded.isNull(0));
        assertArrayEquals(new byte[]{0, 1, 2}, decoded.getBytes(1));
        assertEquals("text", decoded.getString(2));
        assertEquals(42L, decoded.getLong(3));
        assertEquals(BigInteger.TEN.pow(40), decoded.getBigInteger(4));
        assertEquals(1.5f, decoded.getFloat(5));
        assertEquals(2.5, decoded.getDouble(6));
        assertTrue(decoded.getBoolean(7));
        assertEquals(uuid, decoded.getUUID(8));
        assertEquals(versionstamp, decoded.getVersionstamp(9));
        assertEquals(Tuple.of("nested", null), decoded.getNestedTuple(10));
    }

    @Test
    void smallIntegersAndListsAreStoredInCanonicalForm() {
        Tuple tuple = Tuple.of(1, (short) 2, (byte) 3, BigInteger.valueOf(4), List.of("a", "b"));
        assertEquals(List.of(1L, 2L, 3L, 4L, Tuple.of("a", "b")), tuple.items());
        assertEquals(Tuple.of(1L, 2L, 3L, 4L, Tuple.of("a", "b")), tuple);
    }

    @Test
    void rejectsUnsupportedTypes() {
        assertThrows(IllegalArgumentException.class, () -> Tuple.of(new Object()));
        assertThrows(IllegalArgumentException.class, () -> Tuple.of('c'));
        assertThrows(NullPointerException.class, () -> Tuple.of((Object[]) null));
    }

    @Test
    void typedGettersExplainAWrongType() {
        Tuple tuple = Tuple.of("text", null, BigInteger.TWO.pow(70));
        ClassCastException wrongType = assertThrows(ClassCastException.class, () -> tuple.getLong(0));
        assertEquals("Tuple element 0 is a String, not a Long", wrongType.getMessage());
        assertThrows(NullPointerException.class, () -> tuple.getLong(1));
        assertNull(tuple.getString(1));
        assertThrows(ClassCastException.class, () -> tuple.getLong(2)); // too large for a long
        assertEquals(BigInteger.TWO.pow(70), tuple.getBigInteger(2));
    }

    @Test
    void byteArraysAreCopiedInAndOut() {
        byte[] bytes = {1, 2, 3};
        Tuple tuple = Tuple.of((Object) bytes);
        bytes[0] = 9;
        tuple.getBytes(0)[1] = 9;
        ((byte[]) tuple.get(0))[2] = 9;
        tuple.pack()[0] = 9;
        assertArrayEquals(new byte[]{1, 2, 3}, tuple.getBytes(0));
        assertEquals(Tuple.of((Object) new byte[]{1, 2, 3}), tuple);
    }

    @Test
    void itemsAreUnmodifiable() {
        Tuple tuple = Tuple.of("a", null);
        assertThrows(UnsupportedOperationException.class, () -> tuple.items().add("b"));
        assertEquals(Arrays.asList("a", null), tuple.items());
    }

    @Test
    void addAndAddAllMatchBuildingTheTupleAtOnce() {
        Tuple built = Tuple.of("a").add(1).add(null).addAll(Tuple.of(true, Tuple.of("x")));
        Tuple direct = Tuple.of("a", 1, null, true, Tuple.of("x"));
        assertEquals(direct, built);
        assertArrayEquals(direct.pack(), built.pack());
        assertEquals(direct.items(), built.items());
    }

    @Test
    void ordersByElements() {
        List<Tuple> expected = List.of(
                Tuple.of((Object) null),
                Tuple.of((Object) new byte[]{0}),
                Tuple.of("a"),
                Tuple.of("a", 1),
                Tuple.of("b"),
                Tuple.of(Tuple.of(1)),
                Tuple.of(BigInteger.TWO.pow(64).negate()),
                Tuple.of(Long.MIN_VALUE),
                Tuple.of(-1),
                Tuple.of(0),
                Tuple.of(1),
                Tuple.of(Long.MAX_VALUE),
                Tuple.of(BigInteger.TWO.pow(64)),
                Tuple.of(Float.NEGATIVE_INFINITY),
                Tuple.of(-1f),
                Tuple.of(1f),
                Tuple.of(-1.0),
                Tuple.of(1.0),
                Tuple.of(false),
                Tuple.of(true),
                Tuple.of(new UUID(0, 0)),
                Tuple.of(new Versionstamp(1, 0, 0)));
        List<Tuple> sorted = expected.stream().sorted().toList();
        assertEquals(expected, sorted);
    }

    @Test
    void equalTuplesHaveEqualHashCodes() {
        assertEquals(Tuple.of("a", 1).hashCode(), Tuple.of("a", 1L).hashCode());
        assertNotEquals(Tuple.of(0.0), Tuple.of(-0.0));
    }

    @Test
    void buildingRejectsNestingDeeperThanTheLimit() {
        Tuple tuple = Tuple.empty();
        for (int level = 0; level < Tuple.MAX_NESTING_DEPTH; level++) {
            tuple = Tuple.of(tuple);
        }
        Tuple deepest = tuple;
        assertEquals(deepest, Tuple.fromBytes(deepest.pack()));
        assertThrows(IllegalArgumentException.class, () -> Tuple.of(deepest));
        assertThrows(IllegalArgumentException.class, () -> Tuple.empty().add(deepest));
    }

    @Test
    void rangeCoversLongerTuplesWithTheSamePrefix() {
        Range range = Tuple.of("user", 42).range();
        assertTrue(range.contains(Tuple.of("user", 42, "name").pack()));
        assertTrue(range.contains(Tuple.of("user", 42, null).pack()));
        assertFalse(range.contains(Tuple.of("user", 42).pack()));
        assertFalse(range.contains(Tuple.of("user", 43).pack()));
    }

    @Test
    void packWithAPrefix() {
        byte[] prefix = {(byte) 0xfe};
        byte[] packed = Tuple.of("a").pack(prefix);
        assertEquals((byte) 0xfe, packed[0]);
        assertEquals(Tuple.of("a"), Tuple.fromBytes(packed, 1, packed.length - 1));
    }

    @Test
    void incompleteVersionstampMustBePackedWithItsOffset() {
        Tuple tuple = Tuple.of("event", Tuple.of(Versionstamp.incomplete(5)));
        assertTrue(tuple.hasIncompleteVersionstamp());
        assertThrows(IllegalStateException.class, tuple::pack);
        assertThrows(IllegalStateException.class, tuple::range);

        byte[] prefix = {1, 2};
        byte[] packed = tuple.packWithVersionstamp(prefix);

        int offset = ByteBuffer.wrap(packed, packed.length - 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
        // prefix (2) + "event" string (7) + nested code (1) + versionstamp code (1)
        assertEquals(11, offset);
        byte[] stamp = Arrays.copyOfRange(packed, offset, offset + Versionstamp.BYTES);
        assertEquals(Versionstamp.incomplete(5), Versionstamp.fromBytes(stamp));
    }

    @Test
    void packWithVersionstampNeedsExactlyOne() {
        assertThrows(IllegalArgumentException.class, () -> Tuple.of("a").packWithVersionstamp(new byte[0]));
        Tuple two = Tuple.of(Versionstamp.incomplete(1), Versionstamp.incomplete(2));
        assertThrows(IllegalArgumentException.class, () -> two.packWithVersionstamp(new byte[0]));
    }

    @Test
    void versionstampBytesRoundTrip() {
        Versionstamp versionstamp = new Versionstamp(-2L, 65535, 65535);
        assertEquals(versionstamp, Versionstamp.fromBytes(versionstamp.toBytes()));
        assertTrue(versionstamp.isComplete());
        assertFalse(Versionstamp.incomplete(0).isComplete());
        assertTrue(versionstamp.compareTo(Versionstamp.incomplete(0)) < 0);
        assertThrows(IllegalArgumentException.class, () -> new Versionstamp(1, 65536, 0));
        assertThrows(IllegalArgumentException.class, () -> new Versionstamp(1, 0, -1));
    }

    @Test
    void readableToString() {
        Tuple tuple = Tuple.of("say \"hi\"", 42, new byte[]{0, 'A', (byte) 0xff}, null, 1.5f, Tuple.of(true));
        assertEquals("(\"say \\\"hi\\\"\", 42, b\"\\x00A\\xff\", null, 1.5f, (true))", tuple.toString());
        assertEquals("Range[b\"\\x15\\x01\\x00\", b\"\\x15\\x01\\xff\")", Tuple.of(1).range().toString());
    }
}
