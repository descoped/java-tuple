package io.descoped.tuple;

import java.util.Arrays;
import java.util.Objects;

/**
 * A key range: {@code begin} inclusive, {@code end} exclusive, compared as unsigned bytes. The arrays are copied
 * on the way in and out, so a range cannot be changed after it is made.
 *
 * @param begin the first key in the range
 * @param end   the first key after the range
 */
public record Range(byte[] begin, byte[] end) {

    /**
     * Copies both arrays.
     *
     * @param begin the first key in the range
     * @param end   the first key after the range
     * @throws NullPointerException if either is null
     */
    public Range {
        begin = Objects.requireNonNull(begin, "begin").clone();
        end = Objects.requireNonNull(end, "end").clone();
    }

    /**
     * The first key in the range.
     *
     * @return a copy of the begin key
     */
    @Override
    public byte[] begin() {
        return begin.clone();
    }

    /**
     * The first key after the range.
     *
     * @return a copy of the end key
     */
    @Override
    public byte[] end() {
        return end.clone();
    }

    /**
     * Whether {@code key} falls within this range.
     *
     * @param key the key to test
     * @return {@code true} if {@code begin <= key < end} as unsigned bytes
     */
    public boolean contains(byte[] key) {
        return Arrays.compareUnsigned(begin, key) <= 0 && Arrays.compareUnsigned(key, end) < 0;
    }

    /**
     * Ranges are equal when their begin and end keys hold the same bytes.
     *
     * @param other the object to compare with
     * @return {@code true} if {@code other} is a range with the same keys
     */
    @Override
    public boolean equals(Object other) {
        return other instanceof Range range && Arrays.equals(begin, range.begin) && Arrays.equals(end, range.end);
    }

    /**
     * A hash of both keys, consistent with {@link #equals(Object)}.
     *
     * @return the hash code
     */
    @Override
    public int hashCode() {
        return 31 * Arrays.hashCode(begin) + Arrays.hashCode(end);
    }

    /**
     * A readable form of both keys.
     *
     * @return for example {@code Range[b"\x15\x01\x00", b"\x15\x01\xff")}
     */
    @Override
    public String toString() {
        return "Range[b\"" + ByteFormat.printable(begin) + "\", b\"" + ByteFormat.printable(end) + "\")";
    }
}
