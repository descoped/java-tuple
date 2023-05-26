package io.descoped.tuple;

import java.math.BigInteger;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

class Constants {

    static final byte nil = 0x00;
    static final Charset UTF8 = StandardCharsets.UTF_8;
    static final BigInteger LONG_MIN_VALUE = BigInteger.valueOf(Long.MIN_VALUE);
    static final BigInteger LONG_MAX_VALUE = BigInteger.valueOf(Long.MAX_VALUE);
    static final int UUID_BYTES = 2 * Long.BYTES;
//        static final IterableComparator iterableComparator = new IterableComparator();

    static final byte BYTES_CODE = 0x01;
    static final byte STRING_CODE = 0x02;
    static final byte NESTED_CODE = 0x05;
    static final byte INT_ZERO_CODE = 0x14; // 20
    static final byte POS_INT_END = 0x1d;   // 29
    static final byte NEG_INT_START = 0x0b; // 11
    static final byte FLOAT_CODE = 0x20;    // 32
    static final byte DOUBLE_CODE = 0x21;   // 33
    static final byte FALSE_CODE = 0x26;    // 38
    static final byte TRUE_CODE = 0x27;     // 39
    static final byte UUID_CODE = 0x30;     // 64
    static final byte VERSIONSTAMP_CODE = 0x33;

    static final byte[] NULL_ARR = new byte[]{nil};
    static final byte[] NULL_ESCAPED_ARR = new byte[]{nil, (byte) 0xFF};

}
