package io.descoped.tuple;

/**
 * The type code byte that starts every encoded element, as an unsigned value (0..255). See the package
 * documentation for what follows each code.
 */
final class TypeCode {

    static final int NULL = 0x00;
    static final int BYTES = 0x01;
    static final int STRING = 0x02;
    static final int NESTED = 0x05;
    static final int NEGATIVE_BIG_INTEGER = 0x0b;
    static final int INTEGER_ZERO = 0x14;
    static final int POSITIVE_BIG_INTEGER = 0x1d;
    static final int FLOAT = 0x20;
    static final int DOUBLE = 0x21;
    static final int FALSE = 0x26;
    static final int TRUE = 0x27;
    static final int UUID = 0x30;
    static final int VERSIONSTAMP = 0x33;

    /** Follows a {@code 0x00} that is content rather than a terminator. */
    static final int ESCAPE = 0xff;

    /** Integers of up to this many bytes use a code between 0x0c and 0x1c, which also gives the length. */
    static final int MAX_FIXED_INTEGER_BYTES = 8;

    /** Longer integers use a length byte, which caps them at 255 bytes. */
    static final int MAX_BIG_INTEGER_BYTES = 255;

    private TypeCode() {
    }

    /** Whether {@code code} is one of the fixed-length integer codes, 0x0c..0x1c. */
    static boolean isFixedInteger(int code) {
        return Math.abs(code - INTEGER_ZERO) <= MAX_FIXED_INTEGER_BYTES;
    }
}
