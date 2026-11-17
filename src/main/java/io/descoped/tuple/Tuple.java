package io.descoped.tuple;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * An immutable, ordered list of elements that packs to bytes whose unsigned order is the order of the elements.
 * The encoding is byte-compatible with the FoundationDB tuple layer; see the package documentation.
 *
 * <pre>{@code
 * byte[] key = Tuple.of("user", 42L, uuid).pack();
 * Tuple decoded = Tuple.fromBytes(key);
 * String kind = decoded.getString(0);
 * long id = decoded.getLong(1);
 * }</pre>
 *
 * <p>Elements may be {@code null}, {@code byte[]}, {@link String}, {@link Long} (or {@code Integer},
 * {@code Short}, {@code Byte}), {@link BigInteger}, {@link Float}, {@link Double}, {@link Boolean}, {@link UUID},
 * {@link Versionstamp}, or a nested {@code Tuple} (a {@link List} is also accepted as a nested tuple).
 *
 * <p>Two tuples are equal when they pack to the same bytes, and they compare by those bytes.
 * A tuple decoded from bytes keeps them as they came, even a valid encoding that an encoder would not write, such
 * as an integer with more bytes than it needs; such a tuple equals only tuples with the same bytes, and it is encoded
 * afresh when nested in another tuple.
 * Byte arrays are copied on the way in and out, so a tuple cannot be changed after it is made.
 */
public final class Tuple implements Comparable<Tuple>, Iterable<Object> {

    /**
     * The deepest nesting allowed: a chain of this many tuples, each inside the next, within a tuple. Real keys nest
     * a few levels. Deeper tuples are rejected when built and when decoded, so this library never writes bytes that
     * it will not read back.
     */
    public static final int MAX_NESTING_DEPTH = 1000;

    private static final Tuple EMPTY = new Tuple(Collections.emptyList(), new byte[0], true);

    /** Canonical element types only (see {@link #canonicalize(Object)}). Unmodifiable, and may contain null. */
    private final List<Object> elements;

    /** The encoding of {@link #elements}. */
    private final byte[] packed;

    private final boolean hasIncompleteVersionstamp;

    /** How many levels of nested tuples lie below this one: 0 when it holds no nested tuple. */
    private final int nestingDepth;

    /** False for bytes decoded as they came that an encoder would have written differently. */
    private final boolean packedCanonical;

    private Tuple(List<Object> elements, byte[] packed, boolean packedCanonical) {
        this.elements = elements;
        this.packed = packed;
        this.packedCanonical = packedCanonical;
        this.hasIncompleteVersionstamp = holdsIncompleteVersionstamp(elements);
        this.nestingDepth = nestingDepthOf(elements);
        if (nestingDepth > MAX_NESTING_DEPTH) {
            throw new IllegalArgumentException("Tuples nested more than " + MAX_NESTING_DEPTH + " levels deep");
        }
    }

    // --- Creating tuples ---

    /**
     * The tuple with no elements.
     *
     * @return the empty tuple, which packs to no bytes
     */
    public static Tuple empty() {
        return EMPTY;
    }

    /**
     * A tuple of the given elements.
     *
     * @param elements the elements, of the types listed in the class documentation; for a tuple holding a single
     *                 null, write {@code Tuple.of((Object) null)}
     * @return the tuple
     * @throws IllegalArgumentException if an element has an unsupported type, a string holds an unpaired surrogate,
     *                                  an integer exceeds 255 bytes, or tuples nest deeper than
     *                                  {@link #MAX_NESTING_DEPTH}
     */
    public static Tuple of(Object... elements) {
        Objects.requireNonNull(elements, "elements is a null array; use Tuple.of((Object) null) for a tuple holding null");
        return fromItems(Arrays.asList(elements));
    }

    /**
     * A tuple of the given items, in iteration order.
     *
     * @param items the elements, of the types listed in the class documentation
     * @return the tuple
     * @throws IllegalArgumentException if an element has an unsupported type, a string holds an unpaired surrogate,
     *                                  an integer exceeds 255 bytes, or tuples nest deeper than
     *                                  {@link #MAX_NESTING_DEPTH}
     */
    public static Tuple fromItems(Iterable<?> items) {
        List<Object> canonical = new ArrayList<>();
        for (Object item : items) {
            canonical.add(canonicalize(item));
        }
        return new Tuple(Collections.unmodifiableList(canonical), TupleEncoder.encode(canonical).bytes(), true);
    }

    /**
     * Decodes a packed tuple.
     *
     * @param bytes the packed tuple
     * @return the decoded tuple
     * @throws IllegalArgumentException if the bytes are not a valid encoding, or nest tuples deeper than
     *                                  {@link #MAX_NESTING_DEPTH}; the message gives the offset
     */
    public static Tuple fromBytes(byte[] bytes) {
        return fromBytes(bytes, 0, bytes.length);
    }

    /**
     * Decodes a packed tuple from part of an array.
     *
     * @param bytes  the array holding the packed tuple
     * @param offset where the packed tuple starts
     * @param length how many bytes it takes
     * @return the decoded tuple
     * @throws IllegalArgumentException  if the bytes are not a valid encoding, or nest tuples deeper than
     *                                   {@link #MAX_NESTING_DEPTH}; the message gives the offset
     * @throws IndexOutOfBoundsException if the range lies outside {@code bytes}
     */
    public static Tuple fromBytes(byte[] bytes, int offset, int length) {
        TupleDecoder.Decoded decoded = TupleDecoder.decode(bytes, offset, length);
        return fromDecoded(decoded.elements(), Arrays.copyOfRange(bytes, offset, offset + length), decoded.canonical());
    }

    /** For the decoder: canonical elements, the packed bytes it read them from, and whether those are canonical. */
    static Tuple fromDecoded(List<Object> elements, byte[] packed, boolean packedCanonical) {
        return new Tuple(Collections.unmodifiableList(elements), packed, packedCanonical);
    }

    /**
     * Gives each supported element type one representation, so that packing and the typed getters have fewer
     * cases: smaller integers widen to Long, a BigInteger that fits in a long becomes a Long, a List becomes a
     * Tuple. Byte arrays are copied.
     */
    private static Object canonicalize(Object item) {
        if (item == null
                || item instanceof String
                || item instanceof Long
                || item instanceof Float
                || item instanceof Double
                || item instanceof Boolean
                || item instanceof UUID
                || item instanceof Versionstamp
                || item instanceof Tuple) {
            return item;
        }
        if (item instanceof byte[] bytes) {
            return bytes.clone();
        }
        if (item instanceof Integer || item instanceof Short || item instanceof Byte) {
            return ((Number) item).longValue();
        }
        if (item instanceof BigInteger value) {
            return value.bitLength() < Long.SIZE ? (Object) value.longValue() : value;
        }
        if (item instanceof List<?> list) {
            return fromItems(list);
        }
        throw new IllegalArgumentException("Unsupported tuple element type: " + item.getClass().getName());
    }

    // --- Building on a tuple ---

    /**
     * A new tuple with one more element at the end. This tuple is unchanged.
     *
     * @param item the element to append, of a type listed in the class documentation
     * @return the longer tuple
     * @throws IllegalArgumentException if the element has an unsupported type or cannot be encoded, or the result
     *                                  would nest deeper than {@link #MAX_NESTING_DEPTH}
     */
    public Tuple add(Object item) {
        return addAll(fromItems(Collections.singletonList(item)));
    }

    /**
     * A new tuple with the elements of {@code other} at the end. This tuple is unchanged.
     *
     * @param other the tuple whose elements to append
     * @return the longer tuple
     */
    public Tuple addAll(Tuple other) {
        List<Object> joined = new ArrayList<>(elements.size() + other.elements.size());
        joined.addAll(elements);
        joined.addAll(other.elements);
        // At the top level, elements are encoded independently, so the packed bytes simply concatenate.
        return new Tuple(Collections.unmodifiableList(joined), concat(packed, other.packed),
                packedCanonical && other.packedCanonical);
    }

    // --- Reading elements ---

    /**
     * The number of elements.
     *
     * @return the number of elements, counting nested tuples as one
     */
    public int size() {
        return elements.size();
    }

    /**
     * Whether this tuple has no elements.
     *
     * @return {@code true} for the empty tuple
     */
    public boolean isEmpty() {
        return elements.isEmpty();
    }

    /**
     * The element at {@code index}, in its canonical type: integers as {@code Long} or {@code BigInteger}, nested
     * tuples as {@code Tuple}, byte arrays as a copy.
     *
     * @param index the position, from 0
     * @return the element, which may be {@code null}
     * @throws IndexOutOfBoundsException if there is no such element
     */
    public Object get(int index) {
        return copyIfArray(elements.get(index));
    }

    /**
     * Whether the element at {@code index} is null.
     *
     * @param index the position, from 0
     * @return {@code true} if the element is null
     * @throws IndexOutOfBoundsException if there is no such element
     */
    public boolean isNull(int index) {
        return elements.get(index) == null;
    }

    /**
     * The string at {@code index}.
     *
     * @param index the position, from 0
     * @return the string, or {@code null} if the element is null
     * @throws ClassCastException        if the element is not a string
     * @throws IndexOutOfBoundsException if there is no such element
     */
    public String getString(int index) {
        return getAs(index, String.class);
    }

    /**
     * The byte array at {@code index}, as a copy.
     *
     * @param index the position, from 0
     * @return the bytes, or {@code null} if the element is null
     * @throws ClassCastException        if the element is not a byte array
     * @throws IndexOutOfBoundsException if there is no such element
     */
    public byte[] getBytes(int index) {
        byte[] bytes = getAs(index, byte[].class);
        return bytes == null ? null : bytes.clone();
    }

    /**
     * The integer at {@code index}. For integers outside the range of long, use {@link #getBigInteger(int)}.
     *
     * @param index the position, from 0
     * @return the integer
     * @throws ClassCastException        if the element is not an integer, or does not fit in a long
     * @throws NullPointerException      if the element is null
     * @throws IndexOutOfBoundsException if there is no such element
     */
    public long getLong(int index) {
        return getRequired(index, Long.class);
    }

    /**
     * The integer at {@code index}, of any size.
     *
     * @param index the position, from 0
     * @return the integer, or {@code null} if the element is null
     * @throws ClassCastException        if the element is not an integer
     * @throws IndexOutOfBoundsException if there is no such element
     */
    public BigInteger getBigInteger(int index) {
        Object element = elements.get(index);
        if (element instanceof Long value) {
            return BigInteger.valueOf(value);
        }
        return getAs(index, BigInteger.class);
    }

    /**
     * The float at {@code index}. A double is not converted.
     *
     * @param index the position, from 0
     * @return the float
     * @throws ClassCastException        if the element is not a float
     * @throws NullPointerException      if the element is null
     * @throws IndexOutOfBoundsException if there is no such element
     */
    public float getFloat(int index) {
        return getRequired(index, Float.class);
    }

    /**
     * The double at {@code index}. A float is not converted.
     *
     * @param index the position, from 0
     * @return the double
     * @throws ClassCastException        if the element is not a double
     * @throws NullPointerException      if the element is null
     * @throws IndexOutOfBoundsException if there is no such element
     */
    public double getDouble(int index) {
        return getRequired(index, Double.class);
    }

    /**
     * The boolean at {@code index}.
     *
     * @param index the position, from 0
     * @return the boolean
     * @throws ClassCastException        if the element is not a boolean
     * @throws NullPointerException      if the element is null
     * @throws IndexOutOfBoundsException if there is no such element
     */
    public boolean getBoolean(int index) {
        return getRequired(index, Boolean.class);
    }

    /**
     * The UUID at {@code index}.
     *
     * @param index the position, from 0
     * @return the UUID, or {@code null} if the element is null
     * @throws ClassCastException        if the element is not a UUID
     * @throws IndexOutOfBoundsException if there is no such element
     */
    public UUID getUUID(int index) {
        return getAs(index, UUID.class);
    }

    /**
     * The versionstamp at {@code index}.
     *
     * @param index the position, from 0
     * @return the versionstamp, or {@code null} if the element is null
     * @throws ClassCastException        if the element is not a versionstamp
     * @throws IndexOutOfBoundsException if there is no such element
     */
    public Versionstamp getVersionstamp(int index) {
        return getAs(index, Versionstamp.class);
    }

    /**
     * The nested tuple at {@code index}.
     *
     * @param index the position, from 0
     * @return the nested tuple, or {@code null} if the element is null
     * @throws ClassCastException        if the element is not a tuple
     * @throws IndexOutOfBoundsException if there is no such element
     */
    public Tuple getNestedTuple(int index) {
        return getAs(index, Tuple.class);
    }

    /**
     * The elements, as {@link #get(int)} returns them.
     *
     * @return an unmodifiable list, which may hold nulls
     */
    public List<Object> items() {
        return elements.stream().map(Tuple::copyIfArray).toList();
    }

    /**
     * The elements, as {@link #get(int)} returns them.
     *
     * @return a sequential stream of the elements, which may hold nulls
     */
    public Stream<Object> stream() {
        return elements.stream().map(Tuple::copyIfArray);
    }

    /**
     * The elements, as {@link #get(int)} returns them.
     *
     * @return an iterator that does not support removal
     */
    @Override
    public Iterator<Object> iterator() {
        return items().iterator();
    }

    /** The elements as stored, without copying, for the encoder. */
    List<Object> elements() {
        return elements;
    }

    /** The packed bytes as stored, without copying, for the encoder. */
    byte[] packedBytes() {
        return packed;
    }

    /** Whether {@link #packedBytes()} is what an encoder writes for the elements. */
    boolean isPackedCanonical() {
        return packedCanonical;
    }

    // --- Packing ---

    /**
     * The encoded bytes, whose unsigned order is the order of the tuples.
     *
     * @return a new array holding the packed tuple
     * @throws IllegalStateException if the tuple holds an incomplete versionstamp; use
     *                               {@link #packWithVersionstamp(byte[])}
     */
    public byte[] pack() {
        requireNoIncompleteVersionstamp();
        return packed.clone();
    }

    /**
     * The encoded bytes after a prefix, such as a subspace's key prefix.
     *
     * @param prefix the bytes to put first
     * @return a new array holding the prefix and then the packed tuple
     * @throws IllegalStateException if the tuple holds an incomplete versionstamp; use
     *                               {@link #packWithVersionstamp(byte[])}
     */
    public byte[] pack(byte[] prefix) {
        requireNoIncompleteVersionstamp();
        return concat(prefix, packed);
    }

    /**
     * Packs a tuple that holds exactly one incomplete {@link Versionstamp}, for use as a key or value with
     * FoundationDB's versionstamp mutations. The result is {@code prefix}, the encoded bytes, and then the offset
     * of the versionstamp within the result as a 4-byte little-endian integer (the format of API version 520 and
     * later).
     *
     * @param prefix the bytes to put first
     * @return a new array holding the prefix, the packed tuple and the versionstamp offset
     * @throws IllegalArgumentException if the tuple does not hold exactly one incomplete versionstamp
     */
    public byte[] packWithVersionstamp(byte[] prefix) {
        TupleEncoder.Encoded encoded = TupleEncoder.encode(elements);
        List<Integer> offsets = encoded.incompleteVersionstampOffsets();
        if (offsets.size() != 1) {
            throw new IllegalArgumentException("Expected exactly one incomplete versionstamp, found " + offsets.size());
        }
        return ByteBuffer.allocate(prefix.length + encoded.bytes().length + Integer.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN)
                .put(prefix)
                .put(encoded.bytes())
                .putInt(prefix.length + offsets.get(0))
                .array();
    }

    /**
     * Whether this tuple, or a tuple nested in it, holds an incomplete versionstamp.
     *
     * @return {@code true} if it does, in which case only {@link #packWithVersionstamp(byte[])} can pack it
     */
    public boolean hasIncompleteVersionstamp() {
        return hasIncompleteVersionstamp;
    }

    /**
     * The range of keys that extend this tuple with more elements: every packed tuple that starts with this one,
     * excluding this tuple itself.
     *
     * @return the range from the packed tuple followed by {@code 0x00}, to the packed tuple followed by {@code 0xFF}
     * @throws IllegalStateException if the tuple holds an incomplete versionstamp
     */
    public Range range() {
        requireNoIncompleteVersionstamp();
        return new Range(concat(packed, new byte[]{0x00}), concat(packed, new byte[]{(byte) 0xff}));
    }

    // --- Object methods ---

    /**
     * Compares the packed bytes as unsigned, which is the order of the elements.
     *
     * @param other the tuple to compare with
     * @return negative, zero or positive as this tuple sorts before, with or after {@code other}
     */
    @Override
    public int compareTo(Tuple other) {
        return Arrays.compareUnsigned(packed, other.packed);
    }

    /**
     * Tuples are equal when they pack to the same bytes.
     *
     * @param other the object to compare with
     * @return {@code true} if {@code other} is a tuple with the same encoding
     */
    @Override
    public boolean equals(Object other) {
        return other instanceof Tuple tuple && Arrays.equals(packed, tuple.packed);
    }

    /**
     * A hash of the packed bytes, consistent with {@link #equals(Object)}.
     *
     * @return the hash code
     */
    @Override
    public int hashCode() {
        return Arrays.hashCode(packed);
    }

    /**
     * A readable form, for example {@code ("user", 42, b"\x00\x01", null, (1.5, true))}.
     *
     * @return the elements in parentheses
     */
    @Override
    public String toString() {
        return elements.stream().map(Tuple::format).collect(Collectors.joining(", ", "(", ")"));
    }

    // --- Helpers ---

    private <T> T getAs(int index, Class<T> type) {
        Object element = elements.get(index);
        if (element == null || type.isInstance(element)) {
            return type.cast(element);
        }
        throw new ClassCastException("Tuple element " + index + " is a " + element.getClass().getSimpleName()
                + ", not a " + type.getSimpleName());
    }

    private <T> T getRequired(int index, Class<T> type) {
        T value = getAs(index, type);
        if (value == null) {
            throw new NullPointerException("Tuple element " + index + " is null");
        }
        return value;
    }

    private void requireNoIncompleteVersionstamp() {
        if (hasIncompleteVersionstamp) {
            throw new IllegalStateException("Tuple holds an incomplete versionstamp; use packWithVersionstamp");
        }
    }

    private static int nestingDepthOf(List<Object> elements) {
        int deepest = 0;
        for (Object element : elements) {
            if (element instanceof Tuple tuple) {
                deepest = Math.max(deepest, tuple.nestingDepth + 1);
            }
        }
        return deepest;
    }

    private static boolean holdsIncompleteVersionstamp(List<Object> elements) {
        for (Object element : elements) {
            if (element instanceof Versionstamp versionstamp && !versionstamp.isComplete()
                    || element instanceof Tuple tuple && tuple.hasIncompleteVersionstamp) {
                return true;
            }
        }
        return false;
    }

    private static Object copyIfArray(Object element) {
        return element instanceof byte[] bytes ? bytes.clone() : element;
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] result = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

    private static String format(Object element) {
        if (element instanceof String string) {
            return '"' + string.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
        }
        if (element instanceof byte[] bytes) {
            return "b\"" + ByteFormat.printable(bytes) + '"';
        }
        if (element instanceof Float value) {
            return value + "f";
        }
        return String.valueOf(element);
    }
}
