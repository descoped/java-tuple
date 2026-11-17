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

    private final byte[] bytes;
    private final int start;
    private final int end;
    private int position;

    private TupleDecoder(byte[] bytes, int start, int end) {
        this.bytes = bytes;
        this.start = start;
        this.end = end;
        this.position = start;
    }

    static List<Object> decode(byte[] bytes, int offset, int length) {
        Objects.checkFromIndexSize(offset, length, bytes.length);
        TupleDecoder decoder = new TupleDecoder(bytes, offset, offset + length);
        List<Object> elements = new ArrayList<>();
        while (decoder.position < decoder.end) {
            elements.add(decoder.readElement());
        }
        return elements;
    }

    private Object readElement() {
        int elementStart = position;
        int code = readByte();
        return switch (code) {
            case TypeCode.NULL -> null;
            case TypeCode.BYTES -> readEscaped();
            case TypeCode.STRING -> decodeUtf8(readEscaped(), elementStart);
            case TypeCode.NESTED -> readNested();
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

    /** Content up to an unescaped 0x00 terminator, with each 0x00 0xFF turned back into 0x00. */
    private byte[] readEscaped() {
        int contentStart = position;
        int escapes = 0;
        while (true) {
            if (readByte() != 0x00) {
                continue;
            }
            if (!skipEscape()) {
                break;
            }
            escapes++;
        }
        int contentEnd = position - 1; // the terminator
        byte[] content = new byte[contentEnd - contentStart - escapes];
        int to = 0;
        for (int from = contentStart; from < contentEnd; from++) {
            content[to++] = bytes[from];
            if (bytes[from] == 0x00) {
                from++; // the 0xFF that follows is not content
            }
        }
        return content;
    }

    /** Elements up to a 0x00 terminator. A 0x00 0xFF in place of an element is a null. */
    private Tuple readNested() {
        List<Object> elements = new ArrayList<>();
        while (true) {
            if (peekByte() != TypeCode.NULL) {
                elements.add(readElement());
                continue;
            }
            position++;
            if (!skipEscape()) {
                return Tuple.fromCanonical(elements);
            }
            elements.add(null);
        }
    }

    /**
     * Codes 0x0c..0x1c: the distance from 0x14 is the number of bytes, the direction is the sign. A negative value
     * is stored as its one's complement, so value = stored - (2^(8 * length) - 1).
     */
    private Number readInteger(int code) {
        int length = Math.abs(code - TypeCode.INTEGER_ZERO);
        boolean negative = code < TypeCode.INTEGER_ZERO;
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
        return narrow(new BigInteger(1, readBytes(length)));
    }

    /** Code 0x0b: a length byte with its bits flipped, then the value in one's complement. */
    private Number readNegativeBigInteger() {
        int length = readByte() ^ 0xff;
        BigInteger stored = new BigInteger(1, readBytes(length));
        return narrow(stored.subtract(TupleEncoder.allOnes(length)));
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
            throw error(position, "unexpected end of data");
        }
        return bytes[position++] & 0xff;
    }

    private int peekByte() {
        if (position >= end) {
            throw error(position, "unexpected end of data");
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
            throw error(position, "unexpected end of data");
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

    private String decodeUtf8(byte[] utf8, int elementStart) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(utf8))
                    .toString();
        } catch (CharacterCodingException e) {
            throw error(elementStart, "string is not valid UTF-8");
        }
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
