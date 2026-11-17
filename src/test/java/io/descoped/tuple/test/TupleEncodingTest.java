package io.descoped.tuple.test;

import io.descoped.tuple.Range;
import io.descoped.tuple.Tuple;
import io.descoped.tuple.Versionstamp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exact bytes, mostly the examples in the FoundationDB tuple specification (design/tuple.md). */
class TupleEncodingTest {

    private static final HexFormat HEX = HexFormat.of();

    static Stream<Arguments> specification() {
        return Stream.of(
                Arguments.of(Tuple.of((Object) null), "00"),
                Arguments.of(Tuple.of((Object) "foo\u0000bar".getBytes()), "01666f6f00ff62617200"),
                Arguments.of(Tuple.of("hello"), "0268656c6c6f00"),
                Arguments.of(Tuple.of("å"), "02c3a500"),
                Arguments.of(Tuple.of(Tuple.empty()), "0500"),
                Arguments.of(Tuple.of(Tuple.of((Object) null)), "0500ff00"),
                // pack(((b"foo\x00bar", None, ()),)) from the specification
                Arguments.of(Tuple.of(Tuple.fromItems(Arrays.asList("foo\u0000bar".getBytes(), null, Tuple.empty()))),
                        "0501666f6f00ff6261720000ff050000"),
                Arguments.of(Tuple.of(0), "14"),
                Arguments.of(Tuple.of(1), "1501"),
                Arguments.of(Tuple.of(255), "15ff"),
                Arguments.of(Tuple.of(256), "160100"),
                Arguments.of(Tuple.of(-1), "13fe"),
                Arguments.of(Tuple.of(-255), "1300"),
                Arguments.of(Tuple.of(-256), "12feff"),
                Arguments.of(Tuple.of(-5551212), "11ab4b93"),
                Arguments.of(Tuple.of(Long.MAX_VALUE), "1c7fffffffffffffff"),
                Arguments.of(Tuple.of(Long.MIN_VALUE), "0c7fffffffffffffff"),
                Arguments.of(Tuple.of(BigInteger.ONE.shiftLeft(64)), "1d09010000000000000000"),
                Arguments.of(Tuple.of(BigInteger.ONE.shiftLeft(64).negate()), "0bf6feffffffffffffffff"),
                Arguments.of(Tuple.of(-42f), "203dd7ffff"),
                Arguments.of(Tuple.of(42f), "20c2280000"),
                Arguments.of(Tuple.of(0.0), "218000000000000000"),
                Arguments.of(Tuple.of(-0.0), "217fffffffffffffff"),
                Arguments.of(Tuple.of(false), "26"),
                Arguments.of(Tuple.of(true), "27"),
                Arguments.of(Tuple.of(UUID.fromString("00112233-4455-6677-8899-aabbccddeeff")), "3000112233445566778899aabbccddeeff"),
                Arguments.of(Tuple.of(new Versionstamp(1, 2, 3)), "33000000000000000100020003"),
                Arguments.of(Tuple.of("a", 1, null), "0261001501" + "00")
        );
    }

    @ParameterizedTest
    @MethodSource("specification")
    void packsToTheSpecifiedBytes(Tuple tuple, String hex) {
        assertEquals(hex, HEX.formatHex(tuple.pack()));
    }

    @ParameterizedTest
    @MethodSource("specification")
    void decodesTheSpecifiedBytes(Tuple tuple, String hex) {
        Tuple decoded = Tuple.fromBytes(HEX.parseHex(hex));
        assertEquals(tuple, decoded);
        assertEquals(tuple.toString(), decoded.toString());
    }

    @Test
    void decodesNonMinimalIntegersToTheSameValue() {
        // 5 written with two bytes instead of one: valid input, even though an encoder never produces it
        assertEquals(5L, Tuple.fromBytes(HEX.parseHex("160005")).getLong(0));
        // a small value written with the arbitrary-precision code
        assertEquals(7L, Tuple.fromBytes(HEX.parseHex("1d0107")).get(0));
    }

    @Test
    void integersDecodeAsLongWhenTheyFitAndBigIntegerOtherwise() {
        BigInteger twoTo63 = BigInteger.ONE.shiftLeft(63);
        assertEquals(Long.MAX_VALUE, Tuple.fromBytes(Tuple.of(Long.MAX_VALUE).pack()).get(0));
        assertEquals(Long.MIN_VALUE, Tuple.fromBytes(Tuple.of(Long.MIN_VALUE).pack()).get(0));
        assertEquals(twoTo63, Tuple.fromBytes(Tuple.of(twoTo63).pack()).get(0));
        assertEquals(twoTo63.negate().subtract(BigInteger.ONE),
                Tuple.fromBytes(Tuple.of(twoTo63.negate().subtract(BigInteger.ONE)).pack()).get(0));
    }

    @Test
    void largestEncodableIntegersRoundTrip() {
        BigInteger largest = BigInteger.ONE.shiftLeft(8 * 255).subtract(BigInteger.ONE);
        assertEquals(largest, Tuple.fromBytes(Tuple.of(largest).pack()).getBigInteger(0));
        assertEquals(largest.negate(), Tuple.fromBytes(Tuple.of(largest.negate()).pack()).getBigInteger(0));
        assertThrows(IllegalArgumentException.class, () -> Tuple.of(largest.add(BigInteger.ONE)));
    }

    @Test
    void floatingPointKeepsItsExactBits() {
        float nanWithPayload = Float.intBitsToFloat(0x7fc0_0123);
        Tuple decoded = Tuple.fromBytes(Tuple.of(nanWithPayload, -0.0, Double.NEGATIVE_INFINITY).pack());
        assertEquals(0x7fc0_0123, Float.floatToRawIntBits(decoded.getFloat(0)));
        assertEquals(Double.doubleToRawLongBits(-0.0), Double.doubleToRawLongBits(decoded.getDouble(1)));
        assertEquals(Double.NEGATIVE_INFINITY, decoded.getDouble(2));
    }

    @Test
    void stringsKeepSupplementaryCharacters() {
        String text = "nul\u0000 æøå 😀";
        assertEquals(text, Tuple.fromBytes(Tuple.of(text).pack()).getString(0));
    }

    @Test
    void rejectsMalformedInput() {
        assertInvalid("02616263", "end of data");      // string without terminator
        assertInvalid("05", "end of data");            // nested tuple without terminator
        assertInvalid("1501ff", "unknown type code");  // 0xff is not a type code
        assertInvalid("40", "unknown type code");      // user type codes are not supported
        assertInvalid("16ff", "end of data");          // integer shorter than its code says
        assertInvalid("30001122", "end of data");      // truncated UUID
        assertInvalid("02ff00", "UTF-8");              // 0xff never appears in UTF-8
    }

    @Test
    void rejectsNestingDeeperThanTheLimit() {
        assertEquals(Tuple.empty(), deepest(Tuple.fromBytes(nested(1000)), 1000));
        assertInvalid(HEX.formatHex(nested(1001)), "nested more than 1000 levels");
        byte[] unterminated = new byte[100_000];
        Arrays.fill(unterminated, (byte) 0x05);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> Tuple.fromBytes(unterminated));
        assertTrue(error.getMessage().contains("nested more than"), error.getMessage());
    }

    /** {@code depth} empty tuples, each nested in the next: 0x05 repeated, then 0x00 repeated. */
    private static byte[] nested(int depth) {
        byte[] bytes = new byte[2 * depth];
        Arrays.fill(bytes, 0, depth, (byte) 0x05);
        return bytes;
    }

    private static Tuple deepest(Tuple tuple, int levels) {
        Tuple current = tuple;
        for (int i = 0; i < levels; i++) {
            current = current.getNestedTuple(0);
        }
        return current;
    }

    @Test
    void rejectsAStringWithAnUnpairedSurrogate() {
        assertThrows(IllegalArgumentException.class, () -> Tuple.of("broken \uD83D"));
        assertThrows(IllegalArgumentException.class, () -> Tuple.of("broken \uDE00 low"));
        assertThrows(IllegalArgumentException.class, () -> Tuple.of("\uDE00\uD83D")); // reversed pair
    }

    @Test
    void encodesUtf8LikeTheJdk() {
        String text = "a\u007f\u0080߿ࠀ￿𐀀􏿿";
        String expected = "02" + HEX.formatHex(text.getBytes(StandardCharsets.UTF_8)) + "00";
        assertEquals(expected, HEX.formatHex(Tuple.of(text).pack()));
    }

    @Test
    void keepsAGenuineReplacementCharacter() {
        assertEquals("a�b", Tuple.fromBytes(Tuple.of("a�b").pack()).getString(0));
    }

    @Test
    void rejectsUtf8ThatTheJdkWouldQuietlyReplace() {
        assertInvalid("02c0af00", "UTF-8");     // overlong encoding of '/'
        assertInvalid("02eda08000", "UTF-8");   // a surrogate encoded as UTF-8
        assertInvalid("02e282", "end of data"); // truncated before its terminator
        assertInvalid("02e28200", "UTF-8");     // truncated sequence
    }

    @Test
    void decodedNestedTupleIsTheSameAsOneBuiltDirectly() {
        Tuple inner = Tuple.of(null, "a\u0000b", Tuple.of(null, 1), null);
        Tuple decoded = Tuple.fromBytes(Tuple.of("x", inner, null).pack()).getNestedTuple(1);
        assertEquals(HEX.formatHex(inner.pack()), HEX.formatHex(decoded.pack()));
        assertEquals(inner.items(), decoded.items());
    }

    @Test
    void errorsReportTheOffsetWithinTheSlice() {
        byte[] bytes = HEX.parseHex("aaaa" + "1501" + "40");
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> Tuple.fromBytes(bytes, 2, 3));
        assertTrue(error.getMessage().contains("offset 2"), error.getMessage());
    }

    private static void assertInvalid(String hex, String expectedProblem) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> Tuple.fromBytes(HEX.parseHex(hex)));
        assertTrue(error.getMessage().contains(expectedProblem), error.getMessage());
    }

    @Test
    void decodesASliceOfALargerArray() {
        byte[] packed = Tuple.of("x", 7).pack();
        byte[] padded = new byte[packed.length + 4];
        System.arraycopy(packed, 0, padded, 2, packed.length);
        assertArrayEquals(packed, Tuple.fromBytes(padded, 2, packed.length).pack());
    }
}
