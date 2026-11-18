# Time-ordered keys with UUIDv7

You want keys that are unique without coordination, sort by the time they were made, and carry some metadata: a
tenant, a path, an entity type. This guide shows how to do that with a standard UUIDv7 and stay compliant with
[RFC 9562](https://www.rfc-editor.org/rfc/rfc9562), in tuple keys and in PostgreSQL 18.

The other two guides cover the alternatives: [UUIDv8](uuidv8-metadata.md), when the metadata must travel inside the
ID, and [versionstamps](foundationdb-versionstamp.md), when the data lives in FoundationDB.

## In short

- A UUIDv7 starts with a 48-bit millisecond timestamp, so UUIDv7s sort by time when compared byte by byte.
- Its other bits are for uniqueness. The RFC leaves no room for metadata in a v7.
- So the metadata goes **in front of the UUID in the key**, not inside it:

```java
byte[] key = Tuple.of("events", tenantId, "orders", uuid7).pack();
```

The key sorts by metadata first and by time within it, every metadata prefix can be scanned, and the UUID stays a
plain, compliant v7.

## 1. What a UUIDv7 holds

128 bits, written most significant first:

```
bits   0–47   unix_ts_ms  48   milliseconds since 1970, big-endian
bits  48–51   ver          4   fixed: 0111 (7)
bits  52–63   rand_a      12   for uniqueness or ordering, see below
bits  64–65   var          2   fixed: 10
bits  66–127  rand_b      62   for uniqueness or ordering, see below
```

The timestamp comes first and is big-endian, so comparing two UUIDs byte by byte, unsigned, compares their times
first. That is the "natural" order: no index on a separate time column is needed to sort by creation.

`ver` and `var` are fixed, which leaves `rand_a` and `rand_b`: 74 bits.

## 2. What the 74 bits may hold

RFC 9562 gives them exactly two jobs (§5.7, §6.2):

| Use                                                         | Where                             | RFC        |
|-------------------------------------------------------------|-----------------------------------|------------|
| Random bits, for uniqueness                                 | anywhere in `rand_a` and `rand_b` | §5.7, §6.9 |
| A counter that orders IDs within a millisecond (method 1)   | immediately after the timestamp   | §6.2       |
| The random bits, incremented as a counter (method 2)        | `rand_a` and `rand_b`             | §6.2       |
| Time more precise than the millisecond (method 3)           | up to 12 bits of `rand_a`         | §6.2       |

The three methods are optional; without them, IDs made in the same millisecond come out in random order.

Nothing else. The RFC sends the nearest cases elsewhere:
- An embedded node or machine ID: "Implementations that choose to leverage an embedded node id SHOULD utilize UUIDv8"
  (§6.4).
- Another timestamp source or epoch: "UUIDv8 MUST be used" (§6.1).

Application data is not provided for at all, and §6.12 asks readers to treat UUIDs as opaque. A system that reads a v7
is entitled to assume the bits are random. To carry metadata inside the ID, use [UUIDv8](uuidv8-metadata.md).

## 3. Where the metadata goes: the key

Even if the metadata fitted, it would sit behind the timestamp, where it does not help a query: finding "every ID with
tenant 42" would mean reading every key. Put the metadata in front of the UUID instead:

```java
Tuple orders = Tuple.of("events", tenantId, "orders");

byte[] key = orders.add(uuid7).pack();   // ("events", 42, "orders", <uuid7>)
Range all = orders.range();             // every key below ("events", 42, "orders")
```

A store that keeps keys in byte order (FoundationDB, a B-tree, an LSM tree) returns that range by tenant and path, and
within it by time, oldest first. In return for one extra key element:
- the metadata can be any length and any type, and stays readable;
- every prefix (`"events"`, `("events", 42)`, ...) is a range to scan;
- the UUID needs no custom bit layout.

PostgreSQL does the same with columns: a primary key of `(tenant_id, path, id)`, see [section 6](#6-postgresql-18).

## 4. Generating UUIDv7 on Java 17

The JDK creates v7 UUIDs from JDK 26 on, with `UUID.ofEpochMillis(long)`. On Java 17 you write the generator yourself.
There are two compliant forms:

| Form                          | `rand_a`           | `rand_b`  | Order within a millisecond  |
|-------------------------------|--------------------|-----------|-----------------------------|
| Random only (as the JDK does) | 12 random bits     | 62 random | random                      |
| Counter (method 1, below)     | 12-bit counter     | 62 random | creation order, per process |

The counter form costs nothing extra and keeps IDs from one process strictly increasing, so a process reads its own
writes back in the order it made them:

```java
import java.security.SecureRandom;
import java.util.UUID;

/** RFC 9562 UUIDv7: 48-bit Unix milliseconds, a 12-bit counter (method 1) and 62 random bits. */
public final class UuidV7 {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final long VERSION_7 = 0x7000L;                       // in the most significant long
    private static final long VARIANT = 0x8000_0000_0000_0000L;          // 10 in the top bits of the least significant long
    private static final long RAND_B = 0x3FFF_FFFF_FFFF_FFFFL;
    private static final int MAX_COUNTER = 0xFFF;

    private static long lastMillis = -1;
    private static int counter;

    private UuidV7() {
    }

    public static synchronized UUID next() {
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
        long msb = (lastMillis << 16) | VERSION_7 | counter;
        long lsb = VARIANT | (RANDOM.nextLong() & RAND_B);
        return new UUID(msb, lsb);
    }

    /** The Unix milliseconds in the first 48 bits. */
    public static long millis(UUID uuid) {
        return uuid.getMostSignificantBits() >>> 16;
    }

    /**
     * The smallest 128-bit value of a millisecond: every ID made at {@code millis} or later sorts at or after it.
     * A bound for range queries, never an ID: its version is 0.
     */
    public static UUID lowerBound(long millis) {
        return new UUID(millis << 16, 0L);
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

How it behaves:
- **Up to 4096 IDs per millisecond** are numbered by the counter. The 4097th waits for the next millisecond, so the
  timestamp is never ahead of the clock. (§6.2 also allows moving the timestamp ahead instead, which never blocks but
  lets IDs drift ahead of real time under load.)
- **If the clock goes back**, the generator keeps the last timestamp and counts on, so IDs never decrease. After 4096
  such IDs it waits for the clock to catch up.
- **Across processes** nothing is coordinated. Two IDs collide only when two processes make them in the same
  millisecond, with the same counter value, and draw the same 62 random bits: for two processes, a chance of 1 in 2⁶²
  (about 2 × 10⁻¹⁹) per such millisecond. The random-only form is similar: n IDs in one millisecond collide with a
  chance of about n² / 2⁷⁵.
- **Cost**: one `SecureRandom.nextLong()` per ID, about 140 ns on a laptop, the same as `UUID.randomUUID()`. Under
  heavy concurrency the lock and the shared `SecureRandom` become the limit; measure before tuning.

## 5. Tuple keys in Java

A UUID in a tuple is type code `0x30` followed by its 16 bytes big-endian, and packed tuples compare as unsigned bytes.
So within one prefix, tuple keys sort by the v7 timestamp. A time window is a range of keys, from the lower bound of
the start to the lower bound of the end:

```java
Tuple orders = Tuple.of("events", tenantId, "orders");

byte[] begin = orders.add(UuidV7.lowerBound(from.toEpochMilli())).pack();   // inclusive
byte[] end = orders.add(UuidV7.lowerBound(to.toEpochMilli())).pack();       // exclusive
```

`UUID.compareTo` compares the two halves as signed longs (JDK-7025832, kept for compatibility). For v7 IDs and these
bounds it happens to agree with the byte order until the year 6429, but not for UUIDs in general. When order matters,
compare packed tuples (`Tuple.compareTo`) or each half with `Long.compareUnsigned`.

## 6. PostgreSQL 18

PostgreSQL compares `uuid` values byte by byte, unsigned, so v7 IDs sort by time in indexes and `ORDER BY`, and new
rows go to the right edge of the B-tree instead of random pages. PostgreSQL 18 adds:
- `uuidv7()`, which makes v7 IDs in the database. It uses `rand_a` for sub-millisecond time (method 3). IDs made in
  Java and in the database interleave correctly by time.
- `uuid_extract_timestamp(id)` and `uuid_extract_version(id)`, which read the time and the version (7).

### Table

The metadata becomes the leading columns of the primary key, the relational form of the tuple prefix:

```sql
CREATE TABLE event (
    tenant_id  bigint NOT NULL,
    path       text   NOT NULL,
    id         uuid   NOT NULL DEFAULT uuidv7(),
    payload    jsonb,
    PRIMARY KEY (tenant_id, path, id)
);
```

Store UUIDs as `uuid`, never as `text`: §6.13 asks for the 128-bit binary form, text takes more than twice the space,
and its order depends on the collation.

### Writing

Leave `id` out to let the database make it, or make it in Java; the PostgreSQL JDBC driver sends a `java.util.UUID`
as `uuid`:

```java
try (PreparedStatement insert = connection.prepareStatement(
        "INSERT INTO event (tenant_id, path, id, payload) VALUES (?, ?, ?, ?::jsonb)")) {
    insert.setLong(1, tenantId);
    insert.setString(2, "orders");
    insert.setObject(3, UuidV7.next());
    insert.setString(4, json);
    insert.executeUpdate();
}
```

### Range queries

A time window needs the same lower bound as in Java. This function builds it from a timestamp:

```sql
-- The smallest uuid of a point in time, for range bounds (UuidV7.lowerBound in Java). Never an ID.
CREATE FUNCTION uuid_bound(at timestamptz) RETURNS uuid
    LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE
    RETURN (lpad(to_hex(floor(extract(epoch FROM at) * 1000)::bigint), 12, '0') || repeat('0', 20))::uuid;
```

```sql
-- Oldest first, and latest first
SELECT * FROM event WHERE tenant_id = 42 AND path = 'orders' ORDER BY id LIMIT 100;
SELECT * FROM event WHERE tenant_id = 42 AND path = 'orders' ORDER BY id DESC LIMIT 100;

-- One hour
SELECT * FROM event
WHERE tenant_id = 42 AND path = 'orders'
  AND id >= uuid_bound('2026-11-18 10:00Z') AND id < uuid_bound('2026-11-18 11:00Z')
ORDER BY id;

-- The next page after the last ID seen (keyset paging, no OFFSET)
SELECT * FROM event WHERE tenant_id = 42 AND path = 'orders' AND id > $1 ORDER BY id LIMIT 100;
```

All of them use the primary key. When the `LIMIT` is small compared with the rows that match, PostgreSQL reads the
index in order (backward for the latest) and stops early; when few rows match, it may fetch them all and sort them,
which is then just as cheap. The bounds are constants, so no function runs per row; avoid
`WHERE uuid_extract_timestamp(id) BETWEEN ...`, which cannot use the index.

From Java, the bounds come from `UuidV7.lowerBound`:

```java
try (PreparedStatement query = connection.prepareStatement(
        "SELECT id, payload FROM event WHERE tenant_id = ? AND path = ? AND id >= ? AND id < ? ORDER BY id")) {
    query.setLong(1, tenantId);
    query.setString(2, "orders");
    query.setObject(3, UuidV7.lowerBound(from.toEpochMilli()));
    query.setObject(4, UuidV7.lowerBound(to.toEpochMilli()));
    try (ResultSet rows = query.executeQuery()) {
        while (rows.next()) {
            UUID id = rows.getObject("id", UUID.class);
            // ...
        }
    }
}
```
