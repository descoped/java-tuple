# Time-ordered IDs that carry metadata: UUIDv8

Sometimes the metadata has to be **inside** the ID: the ID travels on its own, in a URL, a message or another system,
and must be routed or checked there without a lookup. RFC 9562 provides UUIDv8 for that. This guide shows the v8
layout of java-tuple's `UuidV8Generator`, which keeps v7's time order, and how to store and query the IDs in tuple
keys and in PostgreSQL 18.

If the ID only lives in your own keys, you do not need this: put the metadata in the key in front of a plain v7
([UUIDv7](uuidv7-metadata.md)), where it can be any length and every prefix can be scanned. For data in FoundationDB,
see [versionstamps](foundationdb-versionstamp.md).

## In short

- A v8 fixes only its version and variant bits; the other 122 bits are yours.
- Keep v7's 48-bit timestamp at the front, so v8 IDs sort by time, and together with v7 IDs.
- Follow it with a counter, a **process number**, a **tag** for the metadata, and random bits.
- The counter and the process number make IDs unique by construction, so every running generator needs a process
  number of its own.

```java
UuidV8Generator generator = new UuidV8Generator(10, processNumber);   // once per process
UUID id = generator.next(tenantNumber);
int tenant = generator.tag(id);
```

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
bits   0–47   unix_ts_ms      48   milliseconds since 1970, big-endian   (as in v7)
bits  48–51   ver              4   1000
bits  52–63   counter         12   per millisecond, from 0               (as v7's method 1)
bits  64–65   var              2   10
bits  66–95   process | tag    30   split by processBits, for example 10 | 20
bits  96–127  random          32
```

Each field has one job:
- The **timestamp** comes first and is big-endian, so comparing IDs byte by byte compares their times first. It is
  where v7 has it, so v7 and v8 IDs in the same column or key space sort together by time.
- The **counter** numbers the IDs one generator makes within a millisecond, up to 4096. They are unique and in
  creation order.
- The **process number** tells generators apart (the RFC's node ID, §6.4). `processBits` sets its width, 1 to 30.
- The **tag** is the metadata, in the bits the process number leaves: with 10 for the process, 20 bits, about a
  million values.
- The **random** bits make IDs harder to guess, and are the last line of defence if a process number is ever reused.

The split between process number and tag is part of the format. Choose it once, write it down, and never change it for
IDs already issued: nothing in an ID says which split made it.

## 3. What a tag can hold

Twenty bits (with the 10 | 20 split) hold a number, not a path: a tenant or shard number, an entity type, a schema
version, or a hash of a path.

- A hash cannot be turned back into the path, and different paths share hashes sooner than expected: with 20 bits,
  two of about 1 200 paths share one with even odds. Use a hash to route or to check, never to identify.
- Anyone who sees the ID can read the tag, the process number and the time. Never put a secret there.
- The tag does not group IDs: they sort by time first. To scan by tag, keep it in a column or key element of its own as
  well (section 6 does), or put it in front of the ID in the key, as the [UUIDv7 guide](uuidv7-metadata.md) does with
  all metadata.

## 4. Uniqueness

- **Within one generator** the counter guarantees it: no two IDs share a millisecond and a counter value.
- **Between generators with different process numbers** the process number guarantees it: their IDs differ in those
  bits, whatever the random bits are.
- **Between generators that share a process number**, only the 32 random bits are left. Two such processes that each
  make one ID per millisecond with the same tag collide about once in 50 days, around seven times a year.

So the one rule is: **no two running generators may share a process number.** Hand them out from a lease in the
database, an orchestrator's ordinal (such as a Kubernetes StatefulSet index) or a coordination service, never from a
configuration file copied to every node. A VM or container restored from a snapshot is a new process and needs a new
number. [UUID generators](uuid-generators.md) covers these precautions and the odds in full.

## 5. Generating UUIDv8

```java
UuidV8Generator generator = new UuidV8Generator(10, processNumber);   // 10 bits: process numbers 0..1023

UUID id = generator.next(tenantNumber);   // tenantNumber 0..generator.maxTag(), here 0..1048575

long millis = TimeUuids.millis(id);
int process = generator.processNumber(id);
int tenant = generator.tag(id);
```

- Create one generator per process and share it; it is thread-safe.
- Up to 4096 IDs per millisecond. The next one waits for the following millisecond, so the timestamp is never ahead of
  the clock. If the clock goes back, the generator keeps the last timestamp and counts on, so IDs never decrease.
- `processNumber(id)` and `tag(id)` read IDs made with the same `processBits`. Any generator with that split can read
  them, whatever its own process number.
- The JDK has no v8 factory in any version, by design: the bits belong to the application. A layout of your own is a
  few lines with `new UUID(msb, lsb)`; [UUID generators](uuid-generators.md) ends with a checklist for one.

## 6. Tuple keys in Java

In a tuple a v8 is a `UUID` like any other: type code `0x30` followed by its 16 bytes big-endian. Packed tuples compare
as unsigned bytes, so within one prefix the keys sort by time, and a time window is a range from the lower bound of
the start to the lower bound of the end:

```java
Tuple events = Tuple.of("events");
byte[] key = events.add(generator.next(tenantNumber)).pack();

byte[] begin = events.add(TimeUuids.lowerBound(from)).pack();   // inclusive
byte[] end = events.add(TimeUuids.lowerBound(to)).pack();       // exclusive
```

`TimeUuids.lowerBound` returns a UUID of version 0: a bound, never an ID. To sort UUIDs in Java, use
`TimeUuids.compare` or packed tuples. `UUID.compareTo` compares the two halves as signed longs (JDK-7025832, kept for
compatibility); for these IDs and bounds it happens to agree with the byte order until the year 6429, but not for UUIDs
in general.

## 7. PostgreSQL 18

PostgreSQL compares `uuid` values byte by byte, unsigned, whatever their version. v8 IDs with this layout therefore
sort by time in indexes and `ORDER BY`, interleaved correctly with v7 IDs from `uuidv7()`. `uuid_extract_version(id)`
returns 8.

What PostgreSQL does not do is read a v8: `uuid_extract_timestamp(id)` handles only v1 and v7 and returns `NULL` for
v8. A few small functions fill the gap.

### Functions

```sql
-- The Unix milliseconds in the first 48 bits.
CREATE FUNCTION uuid8_millis(id uuid) RETURNS bigint
    LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE
    RETURN ('x' || lpad(substr(replace(id::text, '-', ''), 1, 12), 16, '0'))::bit(64)::bigint;

-- The 30 bits of process number and tag: bits 66–95, the third 32-bit word without the variant bits.
CREATE FUNCTION uuid8_process_and_tag(id uuid) RETURNS integer
    LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE
    RETURN ('x' || substr(replace(id::text, '-', ''), 17, 8))::bit(32)::integer & 1073741823;

-- The tag and the process number, for a layout with tag_bits of tag (20 for processBits 10).
CREATE FUNCTION uuid8_tag(id uuid, tag_bits integer) RETURNS integer
    LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE
    RETURN uuid8_process_and_tag(id) & ((1 << tag_bits) - 1);

CREATE FUNCTION uuid8_process(id uuid, tag_bits integer) RETURNS integer
    LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE
    RETURN uuid8_process_and_tag(id) >> tag_bits;

-- The smallest uuid of a point in time, for range bounds (TimeUuids.lowerBound in Java). Never an ID.
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
    tag      integer GENERATED ALWAYS AS (uuid8_tag(id, 20)) STORED,
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
    insert.setObject(1, generator.next(tenantNumber));
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

From Java, the bounds come from `TimeUuids.lowerBound`:

```java
try (PreparedStatement query = connection.prepareStatement(
        "SELECT id, payload FROM event WHERE tag = ? AND id >= ? AND id < ? ORDER BY id")) {
    query.setInt(1, tenantNumber);
    query.setObject(2, TimeUuids.lowerBound(from));
    query.setObject(3, TimeUuids.lowerBound(to));
    try (ResultSet rows = query.executeQuery()) {
        while (rows.next()) {
            UUID id = rows.getObject("id", UUID.class);
            // ...
        }
    }
}
```
