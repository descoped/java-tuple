# Time-ordered keys with UUIDv7

## Context

Records need IDs that are unique without coordination between writers, and that sort by the time they were made, so
that "the latest", "the oldest" and "everything in this hour" are cheap. They are usually also found by metadata: a
tenant, a kind of record, a path. UUIDv7 ([RFC 9562](https://www.rfc-editor.org/rfc/rfc9562)) is the standard ID for
the first part. The question is where the metadata goes.

## Goal

After this guide you can:
- explain what a UUIDv7 holds, and why it cannot hold metadata;
- build keys with the metadata in front of a UUIDv7, so they sort by metadata and then by time;
- make UUIDv7s on the client with java-tuple;
- store them in PostgreSQL 18 and query by time window, newest first, and page by page.

It assumes the principles in the [guides overview](README.md). For metadata that must travel inside the ID, see
[UUIDv8](uuidv8-metadata.md); for hierarchical paths, [path keys](path-keys.md).

## In short

- A UUIDv7 starts with a 48-bit timestamp in milliseconds, so UUIDv7s sort by time when compared byte by byte.
- Its other bits are for uniqueness; RFC 9562 leaves no room for metadata in a UUIDv7.
- So the metadata goes **in front of the UUID in the key**, and the UUID stays a plain, compliant UUIDv7:

```java
byte[] key = Tuple.of("events", tenantId, "orders", UuidV7.random()).pack();
```

The key sorts by metadata first and by time within it, and every metadata prefix is a range to scan.

## 1. What a UUIDv7 holds

128 bits, most significant first:

```
bits   0–47   unix_ts_ms  48   milliseconds since 1970, big-endian
bits  48–51   ver          4   fixed: 0111 (7)
bits  52–63   rand_a      12   for uniqueness or ordering, see section 2
bits  64–65   var          2   fixed: 10
bits  66–127  rand_b      62   for uniqueness or ordering, see section 2
```

The timestamp comes first and is big-endian, so comparing two UUIDs as unsigned bytes compares their times first. No
separate time column is needed to sort by creation. `ver` and `var` are fixed, which leaves `rand_a` and `rand_b`:
74 bits.

## 2. What the 74 bits may hold

RFC 9562 gives them two jobs: uniqueness and, optionally, order within a millisecond (§5.7, §6.2).

| Use                                                       | Where                             | RFC        |
|-----------------------------------------------------------|-----------------------------------|------------|
| Random bits, for uniqueness                               | anywhere in `rand_a` and `rand_b` | §5.7, §6.9 |
| A counter that orders IDs within a millisecond (method 1) | immediately after the timestamp   | §6.2       |
| The random bits, incremented as a counter (method 2)      | `rand_a` and `rand_b`             | §6.2       |
| Time more precise than the millisecond (method 3)         | up to 12 bits of `rand_a`         | §6.2       |

Without one of the three methods, IDs made in the same millisecond sort in random order.

Nothing else belongs there. The RFC sends the nearest cases to UUIDv8: an embedded node or machine ID "SHOULD utilize
UUIDv8" (§6.4), and another timestamp source or epoch means "UUIDv8 MUST be used" (§6.1). Application data is not
provided for at all, and §6.12 asks readers to treat UUIDs as opaque: a system that reads a UUIDv7 may assume the bits
are random.

## 3. Where the metadata goes: the key

Even if metadata fitted, it would sit behind the timestamp, where it cannot help a query: finding every ID of tenant 42
would mean reading every key. In front of the UUID, it can:

```java
Tuple orders = Tuple.of("events", tenantId, "orders");

byte[] key = orders.add(UuidV7.random()).pack();   // ("events", 42, "orders", <uuid7>)
Range all = orders.range();                        // every key under ("events", 42, "orders")
```

A store that keeps keys in byte order returns that range by tenant and kind, and within it by time, oldest first. In
return for one more key element:
- the metadata can be any length and any type, and stays readable;
- every prefix (`("events")`, `("events", 42)`, ...) is a range to scan;
- the UUID needs no custom bit layout, and the metadata can change without changing the ID.

In PostgreSQL the same idea takes the form of columns: a primary key of `(tenant_id, kind, id)` (section 6).

## 4. Making UUIDv7s

From JDK 26 on, the JDK makes random UUIDv7s itself, with `UUID.ofEpochMillis(long)`; use it there instead of
`UuidV7.random()` ([the JDK's own UUIDv7](uuid-generators.md#2-the-jdks-own-uuidv7-from-jdk-26)). On JDK 17 to 25,
java-tuple has two compliant factories:

| Factory                  | `rand_a`                  | `rand_b`       | Order within a millisecond     |
|--------------------------|---------------------------|----------------|--------------------------------|
| `UuidV7.random()`        | 12 random bits            | 62 random bits | random                         |
| `UuidV7Generator.next()` | 12-bit counter (method 1) | 62 random bits | creation order, per generator  |

```java
UUID id = UuidV7.random();                         // the default: no state, 74 random bits

UuidV7Generator generator = new UuidV7Generator(); // one per process
UUID next = generator.next();                      // always sorts after the one before
```

Use `UuidV7.random()` unless a process must read its own IDs back in exactly the order it made them; then use one
`UuidV7Generator` per process. Make the IDs on the client: they are known before the write and can be used in other
systems at once. Both factories are unique without coordination: at a million IDs per second, a collision is expected
about once in a million years. [UUID generators](uuid-generators.md) has the odds for other loads, the precautions,
and a checklist for writing a generator of your own.

## 5. Time windows in tuple keys

A UUID in a tuple is type code `0x30` followed by its 16 bytes, big-endian, and packed tuples compare as unsigned
bytes. So within one prefix the keys sort by time, and a time window is a range from the **lower bound** of its start
to the lower bound of its end:

```java
Tuple orders = Tuple.of("events", tenantId, "orders");

byte[] begin = orders.add(TimeUuids.lowerBound(from)).pack();   // inclusive
byte[] end = orders.add(TimeUuids.lowerBound(to)).pack();       // exclusive
```

`TimeUuids.lowerBound` returns the smallest UUID of a millisecond. Its version is 0: it bounds ranges and is never an
ID.

## 6. PostgreSQL 18

PostgreSQL compares `uuid` values as unsigned bytes, so UUIDv7s sort by time in indexes and in `ORDER BY`, and new
rows go to the right edge of the B-tree instead of to random pages. PostgreSQL 18 can also read them:
`uuid_extract_timestamp(id)` returns the time and `uuid_extract_version(id)` returns 7.

### Table

The metadata becomes the leading columns of the primary key, the relational form of the tuple prefix:

```sql
CREATE TABLE event (
    tenant_id  bigint NOT NULL,
    kind       text   NOT NULL,
    id         uuid   NOT NULL,   -- UuidV7.random(), made on the client
    payload    jsonb,
    PRIMARY KEY (tenant_id, kind, id)
);
```

PostgreSQL 18 can also make the ID, with `DEFAULT uuidv7()` on the column. That is a fallback for writers that cannot
make one; its IDs interleave correctly by time with those made in Java.

### Writing

The JDBC driver sends a `java.util.UUID` as `uuid`:

```java
try (PreparedStatement insert = connection.prepareStatement(
        "INSERT INTO event (tenant_id, kind, id, payload) VALUES (?, ?, ?, ?::jsonb)")) {
    insert.setLong(1, tenantId);
    insert.setString(2, "orders");
    insert.setObject(3, UuidV7.random());
    insert.setString(4, json);
    insert.executeUpdate();
}
```

### Queries

A time window needs the lower bound in SQL too. This function builds it from a timestamp:

```sql
-- The smallest uuid of a point in time, for range bounds (TimeUuids.lowerBound in Java). Never an ID.
CREATE FUNCTION uuid_bound(at timestamptz) RETURNS uuid
    LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE
    RETURN (lpad(to_hex(floor(extract(epoch FROM at) * 1000)::bigint), 12, '0') || repeat('0', 20))::uuid;
```

```sql
-- Oldest first, and newest first
SELECT * FROM event WHERE tenant_id = 42 AND kind = 'orders' ORDER BY id LIMIT 100;
SELECT * FROM event WHERE tenant_id = 42 AND kind = 'orders' ORDER BY id DESC LIMIT 100;

-- One hour
SELECT * FROM event
WHERE tenant_id = 42 AND kind = 'orders'
  AND id >= uuid_bound('2026-11-18 10:00Z') AND id < uuid_bound('2026-11-18 11:00Z')
ORDER BY id;

-- The next page after the last ID seen (keyset paging, no OFFSET)
SELECT * FROM event WHERE tenant_id = 42 AND kind = 'orders' AND id > $1 ORDER BY id LIMIT 100;
```

All of them use the primary key. When the `LIMIT` is small compared with the rows that match, PostgreSQL reads the
index in order (backward for the newest) and stops early; when few rows match, it may fetch them all and sort them,
which is then just as cheap. The bounds are constants, so no function runs per row.

From Java, the bounds come from `TimeUuids.lowerBound`:

```java
try (PreparedStatement query = connection.prepareStatement(
        "SELECT id, payload FROM event WHERE tenant_id = ? AND kind = ? AND id >= ? AND id < ? ORDER BY id")) {
    query.setLong(1, tenantId);
    query.setString(2, "orders");
    query.setObject(3, TimeUuids.lowerBound(from));
    query.setObject(4, TimeUuids.lowerBound(to));
    try (ResultSet rows = query.executeQuery()) {
        while (rows.next()) {
            UUID id = rows.getObject("id", UUID.class);
            // ...
        }
    }
}
```

## Pitfalls

- **Do not filter on a function of the ID.** `WHERE uuid_extract_timestamp(id) BETWEEN ...` cannot use the index;
  compare `id` with bounds instead.
- **Store UUIDs as `uuid`, never as `text`.** RFC 9562 §6.13 asks for the 128-bit binary form; text takes more than
  twice the space, and its order depends on the collation.
- **Never store a lower bound as an ID.** It has version 0; `TimeUuids.isTimeOrdered` rejects it.
- **Sort UUIDs in Java with `TimeUuids.compare`** or by packed tuples. `UUID.compareTo` compares signed longs
  (JDK-7025832, kept for compatibility); for UUIDv7 IDs and lower bounds it agrees with the byte order until the year
  6429, but not for UUIDs in general.
- **Time order across machines is as good as their clocks.** Within one `UuidV7Generator` IDs strictly increase;
  across machines, keep clocks in sync. See [UUID generators](uuid-generators.md#the-clock).

## See also

- [Guides overview](README.md): principles, terms, and choosing a time-ordered ID.
- [UUID generators](uuid-generators.md): the factories, collision odds, precautions.
- [Hierarchical paths as keys](path-keys.md): a path as the metadata, in a `bytea` key.
- [Metadata inside a UUIDv8](uuidv8-metadata.md): when the ID must carry metadata itself.
