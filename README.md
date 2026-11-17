# java-tuple

Order-preserving binary keys for Java: pack a tuple of values into bytes whose unsigned order is the order of the
values, and unpack them again. The encoding is byte-for-byte compatible with the
[FoundationDB tuple layer](https://github.com/apple/foundationdb/blob/main/design/tuple.md), so keys written here can be
read by any FoundationDB client and the other way round.

- Java 17 or later, a JPMS module (`io.descoped.tuple`) that also works on the class path.
- No runtime dependencies.
- Immutable, thread-safe values.

## Use

```java
import io.descoped.tuple.Tuple;

byte[] key = Tuple.of("user", 42, userId).pack();

Tuple tuple = Tuple.fromBytes(key);
String kind = tuple.getString(0);   // "user"
long id = tuple.getLong(1);         // 42
UUID uuid = tuple.getUUID(2);
```

Packed tuples sort like their elements, so a prefix scan finds everything below a tuple:

```java
Range users = Tuple.of("user").range();   // every key that starts with ("user", ...)
users.contains(key);                      // true
```

`add` and `addAll` return a new tuple. `pack(prefix)` puts a subspace prefix first.

### Element types

| Java type                                        | Type code     | Decodes as                                 |
|--------------------------------------------------|---------------|--------------------------------------------|
| `null`                                           | `0x00`        | `null`                                     |
| `byte[]`                                         | `0x01`        | `byte[]` (copied in and out)               |
| `String`                                         | `0x02`        | `String` (UTF-8 on the wire)               |
| `Tuple`, or a `List`                             | `0x05`        | `Tuple`                                    |
| `Long`, `Integer`, `Short`, `Byte`, `BigInteger` | `0x0b`–`0x1d` | `Long` if it fits, otherwise `BigInteger`  |
| `Float`                                          | `0x20`        | `Float`                                    |
| `Double`                                         | `0x21`        | `Double`                                   |
| `Boolean`                                        | `0x26`/`0x27` | `Boolean`                                  |
| `UUID`                                           | `0x30`        | `UUID`                                     |
| `Versionstamp`                                   | `0x33`        | `Versionstamp`                             |

Integers can have up to 255 bytes of magnitude. Floating-point values keep their exact bits, NaN payloads included,
so `0.0` and `-0.0` are different keys. A string with an unpaired surrogate is rejected rather than quietly changed.

Two tuples are equal when they pack to the same bytes, and `compareTo` compares those bytes as unsigned. Typed getters
check the type: `getLong` on a string, or on an integer too large for a long, throws `ClassCastException` with the
index and both types. Use `getBigInteger` for integers of any size.

### Versionstamps

An incomplete versionstamp is a placeholder that FoundationDB fills in at commit time. A tuple that holds one cannot be
packed with `pack()`; `packWithVersionstamp(prefix)` packs it and appends the placeholder's offset as a 4-byte
little-endian integer, the format `SET_VERSIONSTAMPED_KEY` expects from API version 520 on.

```java
byte[] key = Tuple.of("event", Versionstamp.incomplete(0)).packWithVersionstamp(subspacePrefix);
```

### Malformed input

`Tuple.fromBytes` throws `IllegalArgumentException` for anything that is not a valid encoding, with the offset of the
problem: a missing terminator, a truncated value, an unknown type code, or a string that is not UTF-8. It never returns
part of a tuple.

## Compatibility

The tests compare every encoding with the FoundationDB Java client (`org.foundationdb:fdb-java`, test scope only):
the specification's own examples, edge cases at every integer length and floating-point boundary, and thousands of
random tuples. Both encoders must produce the same bytes, each decoder must read the other's bytes to the same values
and types, and both must sort tuples the same way.

Not supported, as in the FoundationDB Java client: the reserved type codes (decimal, 64-bit identifier, 80-bit
versionstamp, length-prefixed bytes) and user types `0x40`–`0x4f`.

## Build

```sh
./mvnw verify                    # compile with -Xlint:all -Werror, tests on the module path (JUnit 6)
./mvnw verify javadoc:javadoc    # plus Javadoc with doclint, warnings fail the build
src/it/consumer/run.sh           # after package: a separate named module uses the jar, module path and class path
```

## License

Apache License 2.0.
