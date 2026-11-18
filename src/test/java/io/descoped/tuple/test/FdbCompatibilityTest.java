package io.descoped.tuple.test;

import io.descoped.tuple.Range;
import io.descoped.tuple.Tuple;
import io.descoped.tuple.Versionstamp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks byte-for-byte compatibility with the FoundationDB Java client ({@code org.foundationdb:fdb-java}), which
 * is the reference implementation: both encoders must produce the same bytes, each decoder must read the other's
 * output to the same values, and both must sort tuples in the same order.
 */
class FdbCompatibilityTest {

    private static final HexFormat HEX = HexFormat.of();
    private static final long SEED = 20261117L;

    static Stream<Object> edgeCases() {
        BigInteger twoTo64 = BigInteger.ONE.shiftLeft(64);
        return Stream.of(
                null, "", "a\u0000b", "æøå😀", new byte[0], new byte[]{0, 0, (byte) 0xff},
                0L, 1L, -1L, 255L, 256L, -255L, -256L, 65535L, -65536L,
                (long) Integer.MAX_VALUE, (long) Integer.MIN_VALUE, Long.MAX_VALUE, Long.MIN_VALUE,
                Long.MAX_VALUE - 1, Long.MIN_VALUE + 1,
                BigInteger.ONE.shiftLeft(63), twoTo64.subtract(BigInteger.ONE), twoTo64,
                BigInteger.ONE.shiftLeft(63).negate().subtract(BigInteger.ONE),
                twoTo64.negate().add(BigInteger.ONE), twoTo64.negate(),
                BigInteger.ONE.shiftLeft(8 * 255).subtract(BigInteger.ONE),
                BigInteger.ONE.shiftLeft(8 * 255).subtract(BigInteger.ONE).negate(),
                0f, -0f, Float.MIN_VALUE, -Float.MIN_VALUE, Float.MAX_VALUE, Float.NEGATIVE_INFINITY, Float.NaN,
                0.0, -0.0, Double.MIN_VALUE, Double.MAX_VALUE, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY,
                Double.NaN, true, false, new UUID(0, 0), new UUID(-1, -1), new UUID(Long.MIN_VALUE, 1),
                new Versionstamp(0, 0, 0), new Versionstamp(-2L, 0xffff, 0xffff),
                Tuple.empty(), Tuple.of((Object) null), Tuple.of(Tuple.of(null, Tuple.empty()), "x"));
    }

    @ParameterizedTest
    @MethodSource("edgeCases")
    void edgeCaseMatchesTheReference(Object element) {
        assertCompatible(Tuple.of(element));
    }

    @Test
    void randomTuplesMatchTheReference() {
        Random random = new Random(SEED);
        for (int i = 0; i < 5_000; i++) {
            assertCompatible(randomTuple(random, 0));
        }
    }

    @Test
    void sortsInTheSameOrderAsTheReference() {
        Random random = new Random(SEED + 1);
        List<Tuple> tuples = IntStream.range(0, 2_000).mapToObj(i -> randomTuple(random, 0)).toList();

        List<String> ours = tuples.stream().sorted().map(t -> HEX.formatHex(t.pack())).toList();
        List<String> reference = tuples.stream()
                .map(FdbCompatibilityTest::toReference)
                .sorted(Comparator.naturalOrder())
                .map(t -> HEX.formatHex(t.pack()))
                .toList();

        assertEquals(reference, ours);
    }

    /** Valid encodings that no encoder writes: integers with more bytes, or another code, than they need. */
    static Stream<String> nonCanonicalIntegers() {
        return Stream.of("160005", "1500", "13ff", "12ff00", "1d0107", "0bfef8", "1d09000100000000000000",
                "1c0000000000000005", "0cffffffffffffff00");
    }

    /**
     * Like the reference, a decoded tuple keeps non-canonical bytes as they came at the top level. Nested in another
     * tuple, it is encoded afresh. Two deliberate differences from fdb-java (8.0.0 and 8.0.1), for input no encoder
     * writes:
     * <ul>
     *   <li>When nesting such a tuple, the reference pads its fresh encoding with zero bytes up to the original
     *       length, which reads back as extra nulls ({@code 160005} nested comes back as {@code [[5], null]}).
     *       Here the nested bytes are the reference's encoding of the same values written canonically.</li>
     *   <li>The reference decodes a small value under a length-prefixed code as BigInteger; here it is a Long, as
     *       every integer that fits is. The values are compared as numbers.</li>
     * </ul>
     */
    @ParameterizedTest
    @MethodSource("nonCanonicalIntegers")
    void nonCanonicalInputIsHandledLikeTheReference(String hex) {
        byte[] input = HEX.parseHex(hex);
        Tuple ours = Tuple.fromBytes(input);
        com.apple.foundationdb.tuple.Tuple reference = com.apple.foundationdb.tuple.Tuple.fromBytes(input);

        assertEquals(HEX.formatHex(reference.pack()), HEX.formatHex(ours.pack()), "top level keeps the input");
        assertEquals(new BigInteger(reference.getItems().get(0).toString()), ours.getBigInteger(0), "value");

        String canonicalNested = HEX.formatHex(com.apple.foundationdb.tuple.Tuple
                .from(com.apple.foundationdb.tuple.Tuple.fromList(reference.getItems())).pack());
        assertEquals(canonicalNested, HEX.formatHex(Tuple.of(ours).pack()), "nested is encoded afresh");

        Tuple oursNested = Tuple.fromBytes(HEX.parseHex("05" + hex + "00"));
        assertEquals("05" + hex + "00", HEX.formatHex(oursNested.pack()), "top level keeps the nested input");
        assertEquals(canonicalNested.substring(2, canonicalNested.length() - 2),
                HEX.formatHex(oursNested.getNestedTuple(0).pack()), "decoded nested tuple packs canonically");
    }

    @Test
    void rangeMatchesTheReference() {
        Random random = new Random(SEED + 2);
        for (int i = 0; i < 500; i++) {
            Tuple tuple = randomTuple(random, 0);
            com.apple.foundationdb.Range reference = toReference(tuple).range();
            Range ours = tuple.range();
            assertArrayEquals(reference.begin, ours.begin(), tuple.toString());
            assertArrayEquals(reference.end, ours.end(), tuple.toString());
        }
    }

    /**
     * The reference's packWithVersionstamp needs the native FoundationDB library, so the result is checked piece by
     * piece instead: the offset points at the placeholder, the reference decodes the tuple, and with the placeholder
     * filled in, the bytes are what the reference packs for the completed tuple.
     */
    @Test
    void packWithVersionstampAgreesWithTheReference() {
        Random random = new Random(SEED + 3);
        for (int i = 0; i < 500; i++) {
            int userVersion = random.nextInt(0x10000);
            Object stamp = random.nextBoolean()
                    ? Versionstamp.incomplete(userVersion)
                    : Tuple.of("in", Versionstamp.incomplete(userVersion), null);
            Tuple tuple = randomTuple(random, 1).add(stamp).addAll(randomTuple(random, 1));
            byte[] prefix = new byte[random.nextInt(4)];
            random.nextBytes(prefix);

            byte[] packed = tuple.packWithVersionstamp(prefix);
            int offset = ByteBuffer.wrap(packed, packed.length - 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
            byte[] body = Arrays.copyOfRange(packed, prefix.length, packed.length - 4);
            assertArrayEquals(prefix, Arrays.copyOf(packed, prefix.length));
            assertEquals(Versionstamp.incomplete(userVersion),
                    Versionstamp.fromBytes(Arrays.copyOfRange(packed, offset, offset + Versionstamp.BYTES)));
            assertTrue(com.apple.foundationdb.tuple.Tuple.fromBytes(body).hasIncompleteVersionstamp());

            Versionstamp complete = new Versionstamp(random.nextLong() & Long.MAX_VALUE, random.nextInt(0x10000), userVersion);
            System.arraycopy(complete.toBytes(), 0, body, offset - prefix.length, Versionstamp.BYTES);
            Tuple completed = Tuple.fromBytes(body);
            assertEquals(HEX.formatHex(toReference(completed).pack()), HEX.formatHex(body), tuple.toString());
        }
    }

    /** Both encoders agree on the bytes, and each decoder reads the same values from them. */
    private static void assertCompatible(Tuple tuple) {
        com.apple.foundationdb.tuple.Tuple reference = toReference(tuple);
        byte[] packed = tuple.pack();
        String context = tuple.toString();

        assertEquals(HEX.formatHex(reference.pack()), HEX.formatHex(packed), context);

        Tuple decodedByUs = Tuple.fromBytes(packed);
        assertEquals(tuple, decodedByUs, context);
        assertSameItems(com.apple.foundationdb.tuple.Tuple.fromBytes(packed).getItems(), decodedByUs.items(), context);
    }

    /** Same values, and the same Java types: Long versus BigInteger in particular. */
    private static void assertSameItems(List<Object> reference, List<Object> ours, String context) {
        assertEquals(reference.size(), ours.size(), context);
        for (int i = 0; i < ours.size(); i++) {
            Object expected = reference.get(i);
            Object actual = ours.get(i);
            if (expected instanceof List<?> nested) { // the reference decodes a nested tuple as a List
                assertInstanceOf(Tuple.class, actual, context);
                assertSameItems(new ArrayList<>(nested), ((Tuple) actual).items(), context);
            } else if (expected instanceof com.apple.foundationdb.tuple.Versionstamp versionstamp) {
                assertArrayEquals(versionstamp.getBytes(), ((Versionstamp) actual).toBytes(), context);
            } else if (expected instanceof byte[] bytes) {
                assertArrayEquals(bytes, (byte[]) actual, context);
            } else {
                assertEquals(expected == null ? null : expected.getClass(), actual == null ? null : actual.getClass(), context);
                assertEquals(expected, actual, context);
            }
        }
    }

    private static com.apple.foundationdb.tuple.Tuple toReference(Tuple tuple) {
        List<Object> items = new ArrayList<>();
        for (Object element : tuple) {
            if (element instanceof Tuple nested) {
                items.add(toReference(nested));
            } else if (element instanceof Versionstamp versionstamp) {
                items.add(com.apple.foundationdb.tuple.Versionstamp.fromBytes(versionstamp.toBytes()));
            } else {
                items.add(element);
            }
        }
        return com.apple.foundationdb.tuple.Tuple.fromList(items);
    }

    // --- Random tuples ---

    private static Tuple randomTuple(Random random, int depth) {
        int size = random.nextInt(depth == 0 ? 6 : 4);
        List<Object> elements = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            elements.add(randomElement(random, depth));
        }
        return Tuple.fromItems(elements);
    }

    private static Object randomElement(Random random, int depth) {
        switch (random.nextInt(depth < 3 ? 12 : 11)) {
            case 0:
                return null;
            case 1:
                return randomBytes(random);
            case 2:
                return randomString(random);
            case 3:
                return random.nextLong() >> random.nextInt(64); // all magnitudes
            case 4:
                return randomBigInteger(random);
            case 5:
                return Float.intBitsToFloat(random.nextInt());
            case 6:
                return Double.longBitsToDouble(random.nextLong());
            case 7:
                return random.nextBoolean();
            case 8:
                return new UUID(random.nextLong(), random.nextLong());
            case 9:
                return new Versionstamp(random.nextLong(), random.nextInt(0x10000), random.nextInt(0x10000));
            case 10:
                return (long) random.nextInt(512) - 256; // small values, many sharing a length
            default:
                return randomTuple(random, depth + 1);
        }
    }

    private static byte[] randomBytes(Random random) {
        byte[] bytes = new byte[random.nextInt(8)];
        random.nextBytes(bytes);
        for (int i = 0; i < bytes.length; i++) {
            if (random.nextInt(4) == 0) {
                bytes[i] = 0; // plenty of bytes that need escaping
            }
        }
        return bytes;
    }

    private static final List<String> CHARACTERS = List.of("a", "b", "\u0000", "\u00e6", "\u00f8", "\u4e2d", "\uD83D\uDE00");

    private static String randomString(Random random) {
        StringBuilder text = new StringBuilder();
        int length = random.nextInt(6);
        for (int i = 0; i < length; i++) {
            text.append(CHARACTERS.get(random.nextInt(CHARACTERS.size())));
        }
        return text.toString();
    }

    private static BigInteger randomBigInteger(Random random) {
        BigInteger magnitude = new BigInteger(random.nextInt(8 * 40) + 1, random);
        BigInteger value = random.nextBoolean() ? magnitude : magnitude.negate();
        return random.nextInt(4) == 0 ? value.add(BigInteger.ONE.shiftLeft(63)) : value; // values near 2^63 too
    }

    @Test
    void readsTheReferenceEncodingOfMixedTuples() {
        com.apple.foundationdb.tuple.Tuple reference = com.apple.foundationdb.tuple.Tuple.from(
                "key", 1, -1L, BigInteger.TEN.pow(30), 3.25f, Arrays.asList("x", null), new byte[]{0});
        Tuple ours = Tuple.fromBytes(reference.pack());
        assertSameItems(com.apple.foundationdb.tuple.Tuple.fromBytes(reference.pack()).getItems(), ours.items(), ours.toString());
    }
}
