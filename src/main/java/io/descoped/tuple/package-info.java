/**
 * Order-preserving binary encoding of tuples, byte-compatible with the
 * <a href="https://github.com/apple/foundationdb/blob/main/design/tuple.md">FoundationDB tuple layer</a>.
 *
 * <p>A packed {@link io.descoped.tuple.Tuple} sorts, as unsigned bytes, in the same order as its elements. That makes
 * it a good key in any ordered key-value store. Start with {@link io.descoped.tuple.Tuple#of(Object...)} and
 * {@link io.descoped.tuple.Tuple#fromBytes(byte[])}.
 *
 * <h2>Wire format</h2>
 * Each element starts with a one-byte type code. The bytes that follow depend on that code:
 *
 * <table>
 *   <caption>Type codes</caption>
 *   <tr><th>Code</th><th>Java type</th><th>Encoding after the code byte</th></tr>
 *   <tr><td>{@code 0x00}</td><td>{@code null}</td><td>Nothing. Inside a nested tuple, null is written as
 *       {@code 0x00 0xFF} because a bare {@code 0x00} ends the nested tuple.</td></tr>
 *   <tr><td>{@code 0x01}</td><td>{@code byte[]}</td><td>The bytes with every {@code 0x00} written as
 *       {@code 0x00 0xFF}, then a {@code 0x00} terminator.</td></tr>
 *   <tr><td>{@code 0x02}</td><td>{@link java.lang.String}</td><td>UTF-8, escaped and terminated like
 *       {@code byte[]}.</td></tr>
 *   <tr><td>{@code 0x05}</td><td>{@link io.descoped.tuple.Tuple}</td><td>The nested elements, then
 *       {@code 0x00}.</td></tr>
 *   <tr><td>{@code 0x0B}</td><td>{@link java.math.BigInteger}</td><td>Negative, 9 to 255 bytes: the length with
 *       its bits flipped, then the value in one's complement.</td></tr>
 *   <tr><td>{@code 0x0C}..{@code 0x13}</td><td>{@link java.lang.Long}</td><td>Negative, 8 down to 1 bytes, in
 *       big-endian one's complement ({@code -1} is {@code 0x13 0xFE}).</td></tr>
 *   <tr><td>{@code 0x14}</td><td>{@link java.lang.Long}</td><td>Zero. Nothing follows.</td></tr>
 *   <tr><td>{@code 0x15}..{@code 0x1C}</td><td>{@link java.lang.Long}</td><td>Positive, 1 to 8 bytes,
 *       big-endian.</td></tr>
 *   <tr><td>{@code 0x1D}</td><td>{@link java.math.BigInteger}</td><td>Positive, 9 to 255 bytes: the length,
 *       then the value.</td></tr>
 *   <tr><td>{@code 0x20}</td><td>{@link java.lang.Float}</td><td>4 bytes of IEEE 754, see below.</td></tr>
 *   <tr><td>{@code 0x21}</td><td>{@link java.lang.Double}</td><td>8 bytes of IEEE 754, see below.</td></tr>
 *   <tr><td>{@code 0x26}, {@code 0x27}</td><td>{@link java.lang.Boolean}</td><td>{@code false} and
 *       {@code true}. Nothing follows.</td></tr>
 *   <tr><td>{@code 0x30}</td><td>{@link java.util.UUID}</td><td>16 bytes, big-endian.</td></tr>
 *   <tr><td>{@code 0x33}</td><td>{@link io.descoped.tuple.Versionstamp}</td><td>12 bytes: 8-byte commit
 *       version, 2-byte batch version, 2-byte user version.</td></tr>
 * </table>
 *
 * <p>Integers decode as {@link java.lang.Long} when the value fits in a long and as {@link java.math.BigInteger}
 * otherwise. {@code Integer}, {@code Short} and {@code Byte} are accepted on input and stored as {@code Long}.
 *
 * <p>Floating point values keep their raw IEEE 754 bits, NaN payloads included. They are adjusted so that byte
 * order matches numeric order: for a negative number every bit is flipped, for a positive number only the sign
 * bit is.
 */
package io.descoped.tuple;
