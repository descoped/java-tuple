# Metadata inside a UUIDv8

## Context

Usually metadata belongs next to an ID, in the key, where it can change and be indexed
([principle 2](README.md#three-principles)). Sometimes an ID must carry a small piece of it on its own: the ID
travels in a URL, a message or another system, and has to be routed or checked there before any database is reached.
A UUIDv7 cannot hold it (RFC 9562 reserves its free bits for randomness and ordering); a UUIDv8 can.

## Goal

After this guide you can:
- decide whether a value belongs inside the ID at all;
- understand the time-ordered UUIDv8 layout of java-tuple's `UuidV8Generator`, and why it is unique;
- make the IDs on the client and read the metadata back;
- store them in PostgreSQL 18 and query by metadata and time.

## In short

- Put metadata inside the ID only when it is **small, never changes, and is needed without a lookup**.
- A UUIDv8 fixes only its version and variant; the layout here keeps v7's timestamp first, so the IDs sort by time,
  together with UUIDv7s.
- After the timestamp come a counter, a **process number**, a **tag** (the metadata) and random bits. The counter and
  the process number make the IDs unique by construction, so every running generator needs a process number of its
  own.

```java
UuidV8Generator generator = new UuidV8Generator(10, processNumber);   // once per process
UUID id = generator.next(regionNumber);
int region = generator.tag(id);
```

## 1. When metadata belongs inside the ID

All three must hold:

| Condition                   | Why                                                                                   |
|-----------------------------|---------------------------------------------------------------------------------------|
| **Small**                   | the tag has 20 bits here (about a million values); a number, not a name or a path     |
| **Never changes**           | an ID is forever; if the value can change, the ID would have to change with it        |
| **Needed without a lookup** | otherwise a column next to the ID does the same job, with no limits                   |

A region or shard number for routing, a data-format version, or the kind of record are typical fits. A path, a name, a
status or an owner are not: they are large, they change, or both. They belong in the key or in columns, as in the
[UUIDv7](uuidv7-metadata.md) and [path keys](path-keys.md) guides.

## 2. What the RFC gives

```
bits   0–47   custom_a  48
bits  48–51   ver        4   fixed: 1000 (8)
bits  52–63   custom_b  12
bits  64–65   var        2   fixed: 10
bits  66–127  custom_c  62
```

UUIDv8 (§5.8) is the RFC's format for "experimental or vendor-specific" layouts, and where it sends an embedded node ID
(§6.4) and any other timestamp source (§6.1). It comes with one condition: a v8's "uniqueness will be implementation
specific and MUST NOT be assumed". The layout must provide it.

## 3. The layout

```
bits   0–47   unix_ts_ms      48   milliseconds since 1970, big-endian   (as in v7)
bits  48–51   ver              4   1000
bits  52–63   counter         12   per millisecond, from 0               (as v7's method 1)
bits  64–65   var              2   10
bits  66–95   process | tag    30   split by processBits, for example 10 | 20
bits  96–127  random          32
```

Each field has one job:
- The **timestamp** comes first and is big-endian, so comparing IDs as unsigned bytes compares their times first. It
  is where v7 has it, so v7 and v8 IDs in one column or key space sort together by time.
- The **counter** numbers the IDs one generator makes within a millisecond, up to 4096, in creation order.
- The **process number** tells generators apart: the RFC's node ID (§6.4). `processBits` sets its width, 1 to 30.
- The **tag** is the metadata, in the bits the process number leaves: with 10 for the process, 20 bits.
- The **random** bits make IDs harder to guess, and are the last line of defence if a process number is ever reused.

The split between process number and tag is part of the format. Choose it once, write it down, and never change it for
IDs already issued: nothing in an ID says which split made it.

## 4. Why the IDs are unique

- **Within one generator**, the counter: no two IDs share a millisecond and a counter value.
- **Between generators with different process numbers**, the process number: their IDs differ in those bits, whatever
  the random bits are.
- **Between generators that share a process number**, only the 32 random bits are left. Two such processes, each
  making one ID per millisecond with the same tag, collide about once in 50 days.

So the one rule is: **no two running generators may share a process number.** Hand the numbers out from a lease in
the database, an orchestrator's ordinal (such as a Kubernetes StatefulSet index) or a coordination service, never
from a configuration file copied to every node. A VM or container restored from a snapshot is a new process and needs a
new number. [UUID generators](uuid-generators.md) covers the precautions and the odds in full.

## 5. Making UUIDv8s

```java
UuidV8Generator generator = new UuidV8Generator(10, processNumber);   // process numbers 0..1023

UUID id = generator.next(regionNumber);   // tags 0..generator.maxTag(), here 0..1048575

long millis = TimeUuids.millis(id);
int process = generator.processNumber(id);
int region = generator.tag(id);
```

- Create one generator per process and share it; it is thread-safe.
- Up to 4096 IDs per millisecond; the next one waits for the following millisecond, so the timestamp is never ahead of
  the clock. If the clock goes back, the generator keeps its last timestamp and counts on, so IDs never decrease.
- `processNumber(id)` and `tag(id)` read IDs made with the same `processBits`, by any generator.
- The JDK has no v8 factory in any version, by design: the bits belong to the application. A layout of your own is a
  few lines with `new UUID(msb, lsb)`; [UUID generators](uuid-generators.md#5-writing-your-own-generator) has a
  checklist.

## 6. Time windows in tuple keys

In a tuple, a v8 is a `UUID` like any other: type code `0x30` followed by its 16 bytes. Within one prefix the keys sort
by time, and a time window is a range from the lower bound of its start to the lower bound of its end:

```java
Tuple events = Tuple.of("events");
byte[] key = events.add(generator.next(regionNumber)).pack();

byte[] begin = events.add(TimeUuids.lowerBound(from)).pack();   // inclusive
byte[] end = events.add(TimeUuids.lowerBound(to)).pack();       // exclusive
```

## 7. PostgreSQL 18

PostgreSQL compares `uuid` values as unsigned bytes, whatever their version, so these IDs sort by time in indexes and
`ORDER BY`, together with UUIDv7s. `uuid_extract_version(id)` returns 8. What PostgreSQL does not do is read a v8's
fields: `uuid_extract_timestamp(id)` handles only v1 and v7 and returns `NULL` for v8. A few functions fill the gap.

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
-- The same function as in the UUIDv7 guide; it works for both.
CREATE FUNCTION uuid_bound(at timestamptz) RETURNS uuid
    LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE
    RETURN (lpad(to_hex(floor(extract(epoch FROM at) * 1000)::bigint), 12, '0') || repeat('0', 20))::uuid;
```

### Table

The ID is the primary key. The tag is a generated column, computed from the ID so the two never disagree, and an
index on `(tag, id)` serves queries by tag and time:

```sql
CREATE TABLE event (
    id       uuid    PRIMARY KEY,                                      -- made on the client
    tag      integer GENERATED ALWAYS AS (uuid8_tag(id, 20)) STORED,
    payload  jsonb   NOT NULL
);

CREATE INDEX event_tag_id ON event (tag, id);
```

### Writing

The JDBC driver sends a `java.util.UUID` as `uuid`; PostgreSQL fills in `tag`:

```java
try (PreparedStatement insert = connection.prepareStatement(
        "INSERT INTO event (id, payload) VALUES (?, ?::jsonb)")) {
    insert.setObject(1, generator.next(regionNumber));
    insert.setString(2, json);
    insert.executeUpdate();
}
```

### Queries

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

-- The newest 100 of one tag
SELECT id, payload FROM event WHERE tag = 42 ORDER BY id DESC LIMIT 100;

-- The next page after the last ID seen (keyset paging, no OFFSET)
SELECT id, payload FROM event WHERE tag = 42 AND id > $1 ORDER BY id LIMIT 100;
```

Each uses an index: the primary key for time alone, `(tag, id)` with a tag. When the `LIMIT` is small compared with the
rows that match, PostgreSQL reads the index in time order (backward for the newest) and stops early; when few rows
match, it may fetch them all and sort them, which is then just as cheap. To show the time of a row, use
`to_timestamp(uuid8_millis(id) / 1000.0)`.

From Java, the bounds come from `TimeUuids.lowerBound`:

```java
try (PreparedStatement query = connection.prepareStatement(
        "SELECT id, payload FROM event WHERE tag = ? AND id >= ? AND id < ? ORDER BY id")) {
    query.setInt(1, regionNumber);
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

## Pitfalls

- **A shared process number undoes the uniqueness.** Section 4; it is the one rule that matters.
- **The tag does not group IDs.** They sort by time first. To query by tag, index it as a column of its own (the
  generated `tag` above) or put it in front of the ID in a key.
- **Anyone who sees the ID can read it.** Time, process number and tag are in plain bits, and a neighbouring ID is only
  32 random bits away. Never put a secret in a tag, and never treat knowing an ID as permission
  ([UUID generators](uuid-generators.md#ids-are-not-secrets)).
- **A hash in the tag identifies nothing.** A path hashed to 20 bits shares its hash with another path, with even odds,
  among about 1 200 paths. Use a hash to route or to check, never to identify.
- **Do not filter on a function of the ID.** `WHERE uuid8_millis(id) BETWEEN ...` cannot use the index; compare `id`
  with bounds instead.
- **Never change the split** between process number and tag for IDs already issued.

## See also

- [Guides overview](README.md): principles, terms, and choosing a time-ordered ID.
- [UUID generators](uuid-generators.md): precautions, collision odds, writing your own generator.
- [Time-ordered keys with UUIDv7](uuidv7-metadata.md): the default, with metadata in the key.
