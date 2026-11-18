# Time-ordered IDs that carry metadata: UUIDv8

Sometimes the metadata has to be **inside** the ID: the ID travels on its own, in a URL, a message or another system,
and must be routed or checked there without a lookup. RFC 9562 provides UUIDv8 for that. This guide shows a v8 layout
that keeps v7's time order, how to generate it on Java 17, and how to store and query it in tuple keys and in
PostgreSQL 18.

If the ID only lives in your own keys, you do not need this: put the metadata in the key in front of a plain v7
([UUIDv7](uuidv7-metadata.md)), where it can be any length and every prefix can be scanned. For data in FoundationDB,
see [versionstamps](foundationdb-versionstamp.md).

## In short

- A v8 fixes only its version and variant bits; the other 122 bits are yours.
- Keep v7's 48-bit timestamp at the front, so v8 IDs sort by time, and alongside v7 IDs.
- Follow it with a counter, a 30-bit **tag** for the metadata, and random bits.
- Uniqueness is now your job. If more than one process makes IDs with the same tag, give each process a number of its
  own and put it in the tag.

## 1. What the RFC gives

```
bits   0–47   custom_a  48
bits  48–51   ver        4   fixed: 1000 (8)
bits  52–63   custom_b  12
bits  64–65   var        2   fixed: 10
bits  66–127  custom_c  62
```

UUIDv8 (§5.8) is the RFC's format for "experimental or vendor-specific" layouts, and it is where the RFC sends an
embedded node ID (§6.4) and any other timestamp source (§6.1). It comes with one condition: a v8's "uniqueness will be
implementation specific and MUST NOT be assumed". The layout has to provide it.

## 2. A layout ordered by time

```
bits   0–47   unix_ts_ms  48   milliseconds since 1970, big-endian   (as in v7)
bits  48–51   ver          4   1000
bits  52–63   counter     12   per millisecond, from 0               (as v7's method 1)
bits  64–65   var          2   10
bits  66–95   tag         30   your metadata
bits  96–127  random      32
```

Each field has one job:
- The **timestamp** comes first and is big-endian, so comparing IDs byte by byte compares their times first. It is
  where v7 has it, so v7 and v8 IDs in the same column or key space sort together by time.
- The **counter** numbers the IDs one process makes within a millisecond, up to 4096. They are unique and in creation
  order.
- The **tag** is the metadata, 30 bits: up to about a billion values.
- The **random** bits separate IDs from different processes, and make IDs hard to guess.

Other splits work the same way: fewer tag bits leave more random bits. Decide the split once, write it down next to
the code, and never change it for IDs already issued; nothing in an ID says which split made it.

## 3. What a tag can hold

Thirty bits hold a number, not a path: a tenant or shard number, an entity type, a schema version, or a hash of a path.

- A hash cannot be turned back into the path, and different paths share hashes sooner than expected: with 30 bits,
  two of about 38 000 paths share one with even odds. Use a hash to route or to check, never to identify.
- Anyone who sees the ID can read the tag and the time. Never put a secret there.
- The tag does not group IDs: they sort by time first. To scan by tag, keep it in a column or key element of its own as
  well (section 6 does), or put it in front of the ID in the key, as the [UUIDv7 guide](uuidv7-metadata.md) does with
  all metadata.

## 4. Uniqueness

**Within one process** the counter guarantees it: no two IDs share a millisecond and a counter value.

**Across processes** only the 32 random bits separate two IDs with the same millisecond, counter and tag. That is less
than it sounds. Two processes that each make one ID per millisecond with the same tag both use counter 0 every
millisecond, and collide with a chance of 1 in 2³² each time: about once in 50 days, around seven times a year.

The fix is the RFC's node ID (§6.4): give every process a number of its own and put it in the tag. With 10 bits for
the process and 20 for the metadata:

```java
int tag = (processNumber << 20) | tenantNumber;   // processNumber 0..1023, tenantNumber 0..1048575
```

Now two processes can never make the same ID, and the random bits are only there to make IDs hard to guess. Process
numbers can come from configuration, a deployment ordinal (such as a Kubernetes StatefulSet index) or a lease in the
database.

## 5. Generating UUIDv8 on Java 17

```java
import java.security.SecureRandom;
import java.util.UUID;

/** RFC 9562 UUIDv8: 48-bit Unix milliseconds, a 12-bit counter, a 30-bit tag and 32 random bits. */
public final class UuidV8 {

    public static final int MAX_TAG = 0x3FFF_FFFF;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final long VERSION_8 = 0x8000L;                       // in the most significant long
    private static final long VARIANT = 0x8000_0000_0000_0000L;          // 10 in the top bits of the least significant long
    private static final int MAX_COUNTER = 0xFFF;

    private static long lastMillis = -1;
    private static int counter;

    private UuidV8() {
    }

    public static synchronized UUID next(int tag) {
        if (tag < 0 || tag > MAX_TAG) {
            throw new IllegalArgumentException("tag must be 0.." + MAX_TAG + ", got " + tag);
        }
        long now = System.currentTimeMillis();
        if (now > lastMillis) {
            lastMillis = now;
            counter = 0;
        } else if (counter < MAX_COUNTER) {
            counter++;                                // same millisecond, or the clock went back
        } else {
            lastMillis = waitForNextMillisecond();    // 4096 used: rollover, §6.2
            counter = 0;
        }
        long msb = (lastMillis << 16) | VERSION_8 | counter;
        long lsb = VARIANT | ((long) tag << 32) | (RANDOM.nextInt() & 0xFFFF_FFFFL);
        return new UUID(msb, lsb);
    }

    /** The Unix milliseconds in the first 48 bits. */
    public static long millis(UUID uuid) {
        return uuid.getMostSignificantBits() >>> 16;
    }

    /** The tag of an ID made by {@link #next(int)}. */
    public static int tag(UUID uuid) {
        requireVersion8(uuid);
        return (int) ((uuid.getLeastSignificantBits() >>> 32) & MAX_TAG);
    }

    /**
     * The smallest 128-bit value of a millisecond: every ID made at {@code millis} or later sorts at or after it.
     * A bound for range queries, never an ID: its version is 0.
     */
    public static UUID lowerBound(long millis) {
        return new UUID(millis << 16, 0L);
    }

    private static void requireVersion8(UUID uuid) {
        if (uuid.version() != 8 || uuid.variant() != 2) {
            throw new IllegalArgumentException("not an RFC 9562 version 8 UUID: " + uuid);
        }
    }

    private static long waitForNextMillisecond() {
        long now;
        do {
            Thread.onSpinWait();
            now = System.currentTimeMillis();
        } while (now <= lastMillis);
        return now;
    }
}
```

The JDK has no v8 factory in any version, by design: the bits belong to the application. `new UUID(msb, lsb)` accepts
any 128 bits on every Java version, and returns 8 from `version()` and 2 from `variant()` for these IDs.

How it behaves:
- **Up to 4096 IDs per millisecond** are numbered by the counter. The 4097th waits for the next millisecond, so the
  timestamp is never ahead of the clock. (Moving the timestamp ahead instead never blocks, but beyond 4096 IDs per
  millisecond the IDs drift ahead of real time.)
- **If the clock goes back**, the generator keeps the last timestamp and counts on, so IDs never decrease.
- **Cost**: one 32-bit `SecureRandom` read per ID, about 70 ns on a laptop, against about 140 ns for
  `UUID.randomUUID()`, which reads 128 bits. The bit shifting costs nothing measurable. Under heavy concurrency the
  lock and the shared `SecureRandom` become the limit; one generator per thread, each with its own process number in
  the tag, removes both.

## 6. Tuple keys in Java

In a tuple a v8 is a `UUID` like any other: type code `0x30` followed by its 16 bytes big-endian. Packed tuples compare
as unsigned bytes, so within one prefix the keys sort by time, and a time window is a range from the lower bound of
the start to the lower bound of the end:

```java
Tuple events = Tuple.of("events");
byte[] key = events.add(UuidV8.next(tag)).pack();

byte[] begin = events.add(UuidV8.lowerBound(from.toEpochMilli())).pack();   // inclusive
byte[] end = events.add(UuidV8.lowerBound(to.toEpochMilli())).pack();       // exclusive
```

`UUID.compareTo` compares the two halves as signed longs (JDK-7025832, kept for compatibility). For these IDs and
bounds it happens to agree with the byte order until the year 6429, but not for UUIDs in general. When order matters,
compare packed tuples (`Tuple.compareTo`) or each half with `Long.compareUnsigned`.

## 7. PostgreSQL 18

PostgreSQL compares `uuid` values byte by byte, unsigned, whatever their version. v8 IDs with this layout therefore
sort by time in indexes and `ORDER BY`, interleaved correctly with v7 IDs from `uuidv7()`. `uuid_extract_version(id)`
returns 8.

What PostgreSQL does not do is read a v8: `uuid_extract_timestamp(id)` handles only v1 and v7 and returns `NULL` for
v8. Three small functions fill the gap.

### Functions

```sql
-- The Unix milliseconds in the first 48 bits.
CREATE FUNCTION uuid8_millis(id uuid) RETURNS bigint
    LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE
    RETURN ('x' || lpad(substr(replace(id::text, '-', ''), 1, 12), 16, '0'))::bit(64)::bigint;

-- The 30-bit tag: bits 66–95, the third 32-bit word without the variant bits.
CREATE FUNCTION uuid8_tag(id uuid) RETURNS integer
    LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE
    RETURN ('x' || substr(replace(id::text, '-', ''), 17, 8))::bit(32)::integer & 1073741823;

-- The smallest uuid of a point in time, for range bounds (UuidV8.lowerBound in Java). Never an ID.
-- The same function as in the UUIDv7 guide: it works for both.
CREATE FUNCTION uuid_bound(at timestamptz) RETURNS uuid
    LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE
    RETURN (lpad(to_hex(floor(extract(epoch FROM at) * 1000)::bigint), 12, '0') || repeat('0', 20))::uuid;
```

### Table

The ID is the primary key. The tag is a generated column, computed from the ID, so the two can never disagree, and an
index on `(tag, id)` serves queries by tag and time:

```sql
CREATE TABLE event (
    id       uuid    PRIMARY KEY,
    tag      integer GENERATED ALWAYS AS (uuid8_tag(id)) STORED,
    payload  jsonb   NOT NULL
);

CREATE INDEX event_tag_id ON event (tag, id);
```

Store UUIDs as `uuid`, never as `text`: §6.13 asks for the 128-bit binary form, text takes more than twice the space,
and its order depends on the collation.

### Writing

The ID is made in Java; the PostgreSQL JDBC driver sends a `java.util.UUID` as `uuid`, and PostgreSQL fills in `tag`:

```java
try (PreparedStatement insert = connection.prepareStatement(
        "INSERT INTO event (id, payload) VALUES (?, ?::jsonb)")) {
    insert.setObject(1, UuidV8.next(tag));
    insert.setString(2, json);
    insert.executeUpdate();
}
```

### Range queries

A time window is a range of IDs, from the bound of the start to the bound of the end, end excluded:

```sql
-- Everything in one hour: the primary key
SELECT id, payload FROM event
WHERE id >= uuid_bound('2026-11-18 10:00Z') AND id < uuid_bound('2026-11-18 11:00Z')
ORDER BY id;

-- One tag in one hour: the (tag, id) index
SELECT id, payload FROM event
WHERE tag = 42
  AND id >= uuid_bound('2026-11-18 10:00Z') AND id < uuid_bound('2026-11-18 11:00Z')
ORDER BY id;

-- The latest 100 of one tag
SELECT id, payload FROM event WHERE tag = 42 ORDER BY id DESC LIMIT 100;

-- The next page after the last ID seen (keyset paging, no OFFSET)
SELECT id, payload FROM event WHERE tag = 42 AND id > $1 ORDER BY id LIMIT 100;
```

Each uses an index: the primary key for time alone, `(tag, id)` with a tag. When the `LIMIT` is small compared with the
rows that match, PostgreSQL reads the index in time order (backward for the latest) and stops early; when few rows
match, it may fetch them all and sort them, which is then just as cheap. The bounds are constants, so no function
runs per row; avoid `WHERE uuid8_millis(id) BETWEEN ...`, which cannot use the index. To show the time of a row, use
`to_timestamp(uuid8_millis(id) / 1000.0)`.

From Java, the bounds come from `UuidV8.lowerBound`:

```java
try (PreparedStatement query = connection.prepareStatement(
        "SELECT id, payload FROM event WHERE tag = ? AND id >= ? AND id < ? ORDER BY id")) {
    query.setInt(1, tag);
    query.setObject(2, UuidV8.lowerBound(from.toEpochMilli()));
    query.setObject(3, UuidV8.lowerBound(to.toEpochMilli()));
    try (ResultSet rows = query.executeQuery()) {
        while (rows.next()) {
            UUID id = rows.getObject("id", UUID.class);
            // ...
        }
    }
}
```
