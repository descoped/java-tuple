package io.descoped.tuple;

import java.math.BigInteger;
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
            writeString(string);
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

    /**
     * UTF-8, written straight into the buffer with the same 0x00 escaping and terminator as a byte string. An unpaired
     * surrogate has no UTF-8 form, so it is rejected rather than replaced.
     *
     * <p>No char takes more than 3 bytes (a surrogate pair takes 4 for 2 chars, an escaped 0x00 takes 2), so the
     * space is reserved once and the loop writes without further checks.
     */
    private void writeString(String string) {
        writeByte(TypeCode.STRING);
        ensureCapacity(3 * string.length() + 1);
        for (int i = 0; i < string.length(); i++) {
            char c = string.charAt(i);
            if (c == 0x00) {
                buffer[size++] = 0x00;
                buffer[size++] = (byte) TypeCode.ESCAPE;
            } else if (c < 0x80) {
                buffer[size++] = (byte) c;
            } else if (c < 0x800) {
                buffer[size++] = (byte) (0xc0 | (c >> 6));
                buffer[size++] = (byte) (0x80 | (c & 0x3f));
            } else if (!Character.isSurrogate(c)) {
                buffer[size++] = (byte) (0xe0 | (c >> 12));
                buffer[size++] = (byte) (0x80 | ((c >> 6) & 0x3f));
                buffer[size++] = (byte) (0x80 | (c & 0x3f));
            } else {
                int codePoint = string.codePointAt(i);
                if (!Character.isSupplementaryCodePoint(codePoint)) {
                    throw new IllegalArgumentException("String has an unpaired surrogate at index " + i
                            + " and cannot be encoded as UTF-8");
                }
                buffer[size++] = (byte) (0xf0 | (codePoint >> 18));
                buffer[size++] = (byte) (0x80 | ((codePoint >> 12) & 0x3f));
                buffer[size++] = (byte) (0x80 | ((codePoint >> 6) & 0x3f));
                buffer[size++] = (byte) (0x80 | (codePoint & 0x3f));
                i++; // the low surrogate is part of this code point
            }
        }
        buffer[size++] = 0x00;
    }

    /**
     * The nested elements, then 0x00. Only a null is written differently inside a nested tuple, so a tuple without
     * nulls is copied from its own packed bytes. With a null, or an incomplete versionstamp whose position must be
     * recorded, the elements are encoded again.
     */
    private void writeNested(Tuple tuple) {
        writeByte(TypeCode.NESTED);
        if (tuple.elements().contains(null) || tuple.hasIncompleteVersionstamp()) {
            for (Object element : tuple.elements()) {
                writeElement(element, true);
            }
        } else {
            writeBytes(tuple.packedBytes());
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
}
