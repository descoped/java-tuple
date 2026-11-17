package io.descoped.tuple;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Reads tuple elements from the tuple encoding described in the package documentation.
 *
 * <p>Malformed input raises {@link IllegalArgumentException} with the offset of the problem.
 */
final class TupleDecoder {

    private static final BigInteger MAX_UNSIGNED_LONG = TupleEncoder.allOnes(Long.BYTES);
    private static final char REPLACEMENT_CHARACTER = '\uFFFD';
    private static final String TRUNCATED = "unexpected end of data";

    /** The decoded elements, and whether the input was the encoding an encoder would produce for them. */
    record Decoded(List<Object> elements, boolean canonical) {
    }

    private final byte[] bytes;
    private final int start;
    private final int end;
    private int position;
    private int depth;

    /** False once an integer is read that uses more bytes, or another code, than an encoder would write. */
    private boolean canonical = true;

    private TupleDecoder(byte[] bytes, int start, int end) {
        this.bytes = bytes;
        this.start = start;
        this.end = end;
        this.position = start;
    }

    static Decoded decode(byte[] bytes, int offset, int length) {
        Objects.checkFromIndexSize(offset, length, bytes.length);
        TupleDecoder decoder = new TupleDecoder(bytes, offset, offset + length);
        List<Object> elements = new ArrayList<>();
        while (decoder.position < decoder.end) {
            elements.add(decoder.readElement());
        }
        return new Decoded(elements, decoder.canonical);
    }

    private Object readElement() {
        int elementStart = position;
        int code = readByte();
        return switch (code) {
            case TypeCode.NULL -> null;
            case TypeCode.BYTES -> readByteString();
            case TypeCode.STRING -> readString(elementStart);
            case TypeCode.NESTED -> readNested(elementStart);
            case TypeCode.NEGATIVE_BIG_INTEGER -> readNegativeBigInteger();
            case TypeCode.POSITIVE_BIG_INTEGER -> readPositiveBigInteger();
            case TypeCode.FLOAT -> readFloat();
            case TypeCode.DOUBLE -> readDouble();
            case TypeCode.FALSE -> false;
            case TypeCode.TRUE -> true;
            case TypeCode.UUID -> readUuid();
            case TypeCode.VERSIONSTAMP -> Versionstamp.fromBytes(readBytes(Versionstamp.BYTES));
            default -> {
                if (!TypeCode.isFixedInteger(code)) {
                    throw error(elementStart, String.format("unknown type code 0x%02x", code));
                }
                yield readInteger(code);
            }
        };
    }

    /** Code 0x01: the bytes up to the terminator, with the escapes removed. */
    private byte[] readByteString() {
        int contentStart = position;
        int escapes = skipPastTerminator();
        return unescape(contentStart, position - 1, escapes);
    }

    /** Code 0x02: UTF-8 up to the terminator. A string without 0x00 bytes is decoded straight from the input. */
    private String readString(int elementStart) {
        int contentStart = position;
        int escapes = skipPastTerminator();
        int contentEnd = position - 1;
        if (escapes == 0) {
            return decodeUtf8(bytes, contentStart, contentEnd - contentStart, elementStart);
        }
        byte[] content = unescape(contentStart, contentEnd, escapes);
        return decodeUtf8(content, 0, content.length, elementStart);
    }

    /** Moves past the 0x00 that ends a byte or text string, counting the 0x00 0xFF escapes on the way. */
    private int skipPastTerminator() {
        int escapes = 0;
        while (true) {
            if (readByte() != 0x00) {
                continue;
            }
            if (!skipEscape()) {
                return escapes;
            }
            escapes++;
        }
    }

    /** The bytes from {@code from} to {@code to} with each 0x00 0xFF turned back into 0x00. */
    private byte[] unescape(int from, int to, int escapes) {
        if (escapes == 0) {
            return Arrays.copyOfRange(bytes, from, to);
        }
        byte[] content = new byte[to - from - escapes];
        int length = 0;
        int i = from;
        while (i < to) {
            content[length++] = bytes[i];
            i += bytes[i] == 0x00 ? 2 : 1; // after a 0x00, the 0xFF escape is not content
        }
        return content;
    }

    /**
     * Code 0x05: elements up to a 0x00 terminator, where 0x00 0xFF stands for a null element. The nested tuple's own
     * packed form is the same bytes with each such null written as a bare 0x00, so it is copied rather than encoded
     * again, unless the input was not canonical: then it is encoded afresh.
     */
    private Tuple readNested(int elementStart) {
        // Each level is a nested call: unchecked depth would end in StackOverflowError, not a clear error.
        if (++depth > Tuple.MAX_NESTING_DEPTH) {
            throw error(elementStart, "tuples nested more than " + Tuple.MAX_NESTING_DEPTH + " levels deep");
        }
        boolean canonicalSoFar = canonical;
        canonical = true;
        int contentStart = position;
        List<Object> elements = new ArrayList<>();
        List<Integer> nullEscapes = new ArrayList<>();
        boolean ended = false;
        while (!ended) {
            if (peekByte() != TypeCode.NULL) {
                elements.add(readElement());
            } else {
                position++;
                if (skipEscape()) {
                    nullEscapes.add(position - 1); // 0x00 0xFF: a null element
                    elements.add(null);
                } else {
                    ended = true; // a bare 0x00: the end of the nested tuple
                }
            }
        }
        int contentEnd = position - 1;
        depth--;
        boolean nestedCanonical = canonical;
        canonical = canonicalSoFar && nestedCanonical;
        byte[] packed = nestedCanonical
                ? copyWithout(contentStart, contentEnd, nullEscapes)
                : TupleEncoder.encode(elements).bytes();
        return Tuple.fromDecoded(elements, packed, true);
    }

    /** The bytes from {@code from} to {@code to}, leaving out those at the given positions, in ascending order. */
    private byte[] copyWithout(int from, int to, List<Integer> skipped) {
        byte[] result = new byte[to - from - skipped.size()];
        int length = 0;
        int next = from;
        for (int skip : skipped) {
            System.arraycopy(bytes, next, result, length, skip - next);
            length += skip - next;
            next = skip + 1;
        }
        System.arraycopy(bytes, next, result, length, to - next);
        return result;
    }

    /**
     * Codes 0x0c..0x1c: the distance from 0x14 is the number of bytes, the direction is the sign. A negative value
     * is stored as its one's complement, so value = stored - (2^(8 * length) - 1).
     */
    private Number readInteger(int code) {
        int length = Math.abs(code - TypeCode.INTEGER_ZERO);
        Number value = readFixedInteger(length, code < TypeCode.INTEGER_ZERO);
        noteIfNotMinimal(value, length, false);
        return value;
    }

    private Number readFixedInteger(int length, boolean negative) {
        long stored = readBigEndian(length);

        if (length < Long.BYTES) {
            long allOnes = (1L << (8 * length)) - 1;
            return negative ? stored - allOnes : stored;
        }

        // Eight bytes: stored holds the raw 64 bits, and the value can fall just outside the range of long.
        if (!negative) {
            return stored >= 0 ? (Number) stored : unsigned(stored);
        }
        // stored - (2^64 - 1) is stored + 1 in 64-bit arithmetic. It is exact when the result is >= Long.MIN_VALUE.
        boolean fitsInLong = stored < 0 || stored == Long.MAX_VALUE;
        return fitsInLong ? (Number) (stored + 1) : unsigned(stored).subtract(MAX_UNSIGNED_LONG);
    }

    /** Code 0x1d: a length byte, then the value. */
    private Number readPositiveBigInteger() {
        int length = readByte();
        Number value = narrow(new BigInteger(1, readBytes(length)));
        noteIfNotMinimal(value, length, true);
        return value;
    }

    /** Code 0x0b: a length byte with its bits flipped, then the value in one's complement. */
    private Number readNegativeBigInteger() {
        int length = readByte() ^ 0xff;
        BigInteger stored = new BigInteger(1, readBytes(length));
        Number value = narrow(stored.subtract(TupleEncoder.allOnes(length)));
        noteIfNotMinimal(value, length, true);
        return value;
    }

    /**
     * An integer is canonical when it uses as few bytes as its magnitude needs, with a fixed-length code up to 8 bytes
     * and a length-prefixed code beyond. Anything else (5 written in two bytes, say) is valid input, but it is not what
     * an encoder writes.
     */
    private void noteIfNotMinimal(Number value, int length, boolean lengthPrefixed) {
        int minimal = value instanceof Long longValue
                ? TupleEncoder.byteLength(longValue)
                : (((BigInteger) value).abs().bitLength() + 7) / 8;
        boolean minimalIsLengthPrefixed = minimal > TypeCode.MAX_FIXED_INTEGER_BYTES;
        if (length != minimal || lengthPrefixed != minimalIsLengthPrefixed) {
            canonical = false;
        }
    }

    /** Undoes the adjustment in {@link TupleEncoder}: a set sign bit marks a value that was positive. */
    private float readFloat() {
        int stored = (int) readBigEndian(Float.BYTES);
        return Float.intBitsToFloat(stored < 0 ? stored ^ Integer.MIN_VALUE : ~stored);
    }

    private double readDouble() {
        long stored = readBigEndian(Double.BYTES);
        return Double.longBitsToDouble(stored < 0 ? stored ^ Long.MIN_VALUE : ~stored);
    }

    private UUID readUuid() {
        long mostSignificant = readBigEndian(Long.BYTES);
        long leastSignificant = readBigEndian(Long.BYTES);
        return new UUID(mostSignificant, leastSignificant);
    }

    // --- Byte input ---

    private int readByte() {
        if (position >= end) {
            throw error(position, TRUNCATED);
        }
        return bytes[position++] & 0xff;
    }

    private int peekByte() {
        if (position >= end) {
            throw error(position, TRUNCATED);
        }
        return bytes[position] & 0xff;
    }

    /** Skips a 0xFF escape if it is next, and says whether it did. */
    private boolean skipEscape() {
        if (position < end && (bytes[position] & 0xff) == TypeCode.ESCAPE) {
            position++;
            return true;
        }
        return false;
    }

    private byte[] readBytes(int count) {
        if (count > end - position) {
            throw error(position, TRUNCATED);
        }
        byte[] result = Arrays.copyOfRange(bytes, position, position + count);
        position += count;
        return result;
    }

    /** {@code byteCount} bytes, most significant first, as the low bytes of a long. */
    private long readBigEndian(int byteCount) {
        long value = 0;
        for (int i = 0; i < byteCount; i++) {
            value = (value << 8) | readByte();
        }
        return value;
    }

    /**
     * Strict UTF-8. {@code new String} is fast but turns malformed input into U+FFFD, so only when a U+FFFD shows up
     * are the bytes checked strictly, to tell a genuine U+FFFD from a replacement.
     */
    private String decodeUtf8(byte[] utf8, int offset, int length, int elementStart) {
        String text = new String(utf8, offset, length, StandardCharsets.UTF_8);
        if (text.indexOf(REPLACEMENT_CHARACTER) >= 0) {
            try {
                StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(utf8, offset, length));
            } catch (CharacterCodingException e) {
                throw error(elementStart, "string is not valid UTF-8");
            }
        }
        return text;
    }

    private IllegalArgumentException error(int at, String problem) {
        return new IllegalArgumentException("Invalid tuple encoding at offset " + (at - start) + ": " + problem);
    }

    /** A 64-bit pattern read as an unsigned number. */
    private static BigInteger unsigned(long bits) {
        return new BigInteger(Long.toUnsignedString(bits));
    }

    /** Integers decode as Long whenever they fit, whatever encoding was used. */
    private static Number narrow(BigInteger value) {
        return value.bitLength() < Long.SIZE ? (Number) value.longValue() : value;
    }
}
