package io.descoped.tuple;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Writes tuple elements in the tuple encoding described in the package documentation.
 *
 * <p>The elements must already be in canonical form (see {@link Tuple}): integers as {@code Long} or
 * {@code BigInteger}, nested tuples as {@code Tuple}.
 */
final class TupleEncoder {

    /** The encoded bytes, and the offset of the 12 versionstamp bytes for each incomplete versionstamp. */
    record Encoded(byte[] bytes, List<Integer> incompleteVersionstampOffsets) {
    }

    private byte[] buffer = new byte[32];
    private int size;
    private final List<Integer> incompleteVersionstampOffsets = new ArrayList<>();

    private TupleEncoder() {
    }

    static Encoded encode(List<?> elements) {
        TupleEncoder encoder = new TupleEncoder();
        for (Object element : elements) {
            encoder.writeElement(element, false);
        }
        return new Encoded(Arrays.copyOf(encoder.buffer, encoder.size), List.copyOf(encoder.incompleteVersionstampOffsets));
    }

    private void writeElement(Object element, boolean nested) {
        if (element == null) {
            writeNull(nested);
        } else if (element instanceof byte[] bytes) {
            writeEscaped(TypeCode.BYTES, bytes);
        } else if (element instanceof String string) {
            writeEscaped(TypeCode.STRING, encodeUtf8(string));
        } else if (element instanceof Long value) {
            writeInteger(value);
        } else if (element instanceof BigInteger value) {
            writeInteger(value);
        } else if (element instanceof Float value) {
            writeFloat(value);
        } else if (element instanceof Double value) {
            writeDouble(value);
        } else if (element instanceof Boolean value) {
            writeByte(value ? TypeCode.TRUE : TypeCode.FALSE);
        } else if (element instanceof UUID uuid) {
            writeUuid(uuid);
        } else if (element instanceof Versionstamp versionstamp) {
            writeVersionstamp(versionstamp);
        } else if (element instanceof Tuple tuple) {
            writeNested(tuple);
        } else {
            throw new IllegalArgumentException("Unsupported tuple element type: " + element.getClass().getName());
        }
    }

    /** A bare 0x00 ends a nested tuple, so inside one a null is written as 0x00 0xFF. */
    private void writeNull(boolean nested) {
        writeByte(TypeCode.NULL);
        if (nested) {
            writeByte(TypeCode.ESCAPE);
        }
    }

    /** Type code, the content with each 0x00 written as 0x00 0xFF, then a 0x00 terminator. */
    private void writeEscaped(int typeCode, byte[] content) {
        writeByte(typeCode);
        for (byte b : content) {
            writeByte(b);
            if (b == 0x00) {
                writeByte(TypeCode.ESCAPE);
            }
        }
        writeByte(0x00);
    }

    private void writeNested(Tuple tuple) {
        writeByte(TypeCode.NESTED);
        for (Object element : tuple.elements()) {
            writeElement(element, true);
        }
        writeByte(0x00);
    }

    /**
     * The type code is 0x14 plus or minus the number of bytes that follow. A negative value is stored as its
     * one's complement, so that more negative values sort first.
     */
    private void writeInteger(long value) {
        if (value == 0) {
            writeByte(TypeCode.INTEGER_ZERO);
            return;
        }
        int length = byteLength(value);
        if (value > 0) {
            writeByte(TypeCode.INTEGER_ZERO + length);
            writeBigEndian(value, length);
        } else {
            writeByte(TypeCode.INTEGER_ZERO - length);
            // The one's complement of a negative value has the same bits as the two's complement of (value - 1).
            writeBigEndian(value - 1, length);
        }
    }

    /** The bytes needed for the magnitude of {@code value}. Long.MIN_VALUE negates to itself and still needs 8. */
    private static int byteLength(long value) {
        long magnitude = value < 0 ? -value : value;
        return (Long.SIZE - Long.numberOfLeadingZeros(magnitude) + 7) / 8;
    }

    /**
     * An integer beyond the range of long. A magnitude of up to 8 bytes still uses the fixed-length codes. A longer
     * one uses 0x1d (positive) or 0x0b (negative) followed by a length byte. For a negative value that length byte
     * has its bits flipped, so that longer, more negative values sort first.
     */
    private void writeInteger(BigInteger value) {
        int length = (value.abs().bitLength() + 7) / 8;
        if (length > TypeCode.MAX_BIG_INTEGER_BYTES) {
            throw new IllegalArgumentException("Integer is too large to encode: its magnitude exceeds 255 bytes");
        }
        boolean negative = value.signum() < 0;
        if (length <= TypeCode.MAX_FIXED_INTEGER_BYTES) {
            writeByte(negative ? TypeCode.INTEGER_ZERO - length : TypeCode.INTEGER_ZERO + length);
        } else if (negative) {
            writeByte(TypeCode.NEGATIVE_BIG_INTEGER);
            writeByte(length ^ 0xff);
        } else {
            writeByte(TypeCode.POSITIVE_BIG_INTEGER);
            writeByte(length);
        }
        BigInteger stored = negative ? value.add(allOnes(length)) : value;
        writeUnsigned(stored, length);
    }

    /** IEEE 754 bits, adjusted so byte order is numeric order: negative values flip all bits, others the sign bit. */
    private void writeFloat(float value) {
        int bits = Float.floatToRawIntBits(value);
        writeByte(TypeCode.FLOAT);
        writeBigEndian(bits < 0 ? ~bits : bits ^ Integer.MIN_VALUE, Float.BYTES);
    }

    /** Same adjustment as {@link #writeFloat(float)}, with 64 bits. */
    private void writeDouble(double value) {
        long bits = Double.doubleToRawLongBits(value);
        writeByte(TypeCode.DOUBLE);
        writeBigEndian(bits < 0 ? ~bits : bits ^ Long.MIN_VALUE, Double.BYTES);
    }

    private void writeUuid(UUID uuid) {
        writeByte(TypeCode.UUID);
        writeBigEndian(uuid.getMostSignificantBits(), Long.BYTES);
        writeBigEndian(uuid.getLeastSignificantBits(), Long.BYTES);
    }

    private void writeVersionstamp(Versionstamp versionstamp) {
        writeByte(TypeCode.VERSIONSTAMP);
        if (!versionstamp.isComplete()) {
            incompleteVersionstampOffsets.add(size);
        }
        writeBytes(versionstamp.toBytes());
    }

    // --- Byte output ---

    private void writeByte(int b) {
        ensureCapacity(1);
        buffer[size++] = (byte) b;
    }

    private void writeBytes(byte[] bytes) {
        ensureCapacity(bytes.length);
        System.arraycopy(bytes, 0, buffer, size, bytes.length);
        size += bytes.length;
    }

    /** The low {@code byteCount} bytes of {@code value}, most significant first. */
    private void writeBigEndian(long value, int byteCount) {
        for (int shift = 8 * (byteCount - 1); shift >= 0; shift -= 8) {
            writeByte((int) (value >>> shift));
        }
    }

    /** A non-negative value as exactly {@code length} big-endian bytes. */
    private void writeUnsigned(BigInteger value, int length) {
        byte[] twosComplement = value.toByteArray(); // may start with a 0x00 sign byte, or be shorter than length
        for (int i = twosComplement.length; i < length; i++) {
            writeByte(0x00);
        }
        int signBytes = Math.max(0, twosComplement.length - length);
        writeBytes(Arrays.copyOfRange(twosComplement, signBytes, twosComplement.length));
    }

    private void ensureCapacity(int extra) {
        if (size + extra > buffer.length) {
            buffer = Arrays.copyOf(buffer, Math.max(2 * buffer.length, size + extra));
        }
    }

    /** 2^(8 * byteCount) - 1: the value that turns a negative number into its one's complement. */
    static BigInteger allOnes(int byteCount) {
        return BigInteger.ONE.shiftLeft(8 * byteCount).subtract(BigInteger.ONE);
    }

    /** Strict UTF-8. {@link String#getBytes} would quietly replace an unpaired surrogate with '?'. */
    private static byte[] encodeUtf8(String string) {
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(string));
            byte[] bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
            return bytes;
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("String contains an unpaired surrogate and cannot be encoded as UTF-8", e);
        }
    }
}
