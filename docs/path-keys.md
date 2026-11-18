# Hierarchical paths as keys

## Context

Many records live in a hierarchy: documents in folders, entities under a namespace, resources under a tenant. Each
record has a **path**, such as `a/b/a/a`, and the common questions are about parts of the tree: everything under
`a/b`, the direct children of `a/b`, the record at exactly `a/b/a/a`. Records also move: a subtree is renamed or
re-parented, and every record in it gets a new path.

Linked Data Store, for one, stored each entity's path as a string together with its packed tuple, and answered subtree
queries with a range over the packed tuple. This guide describes that pattern. It is one use case among many.

## Goal

After this guide you can:
- store a path as a key that PostgreSQL (or any ordered store) can scan by prefix;
- query a subtree, the children of a node, or one node, each with a single index range;
- move a subtree with one `UPDATE`, without changing any record's ID;
- keep a history per path in time order, when you need one.

It assumes the three principles in the [guides overview](README.md): a prefix is a range, an ID is identity, and time
order comes from a timestamp at the front.

## In short

- A record has an **ID** (a `uuid`, made on the client with `UuidV7.random()`) and a **path key** (a `bytea` holding
  the packed tuple of the path's segments). The ID never changes; the path key changes when the record moves.
- PostgreSQL compares `bytea` as unsigned bytes, which is the order of packed tuples, so one B-tree index on the path
  key serves every subtree query.

```java
byte[] pathKey = Tuple.of("a", "b", "a", "a").pack();   // 02 61 00 02 62 00 02 61 00 02 61 00
Range below = Tuple.of("a", "b").range();               // every path under a/b
```

## 1. Identity and location

A path says where a record is, not which record it is. If the path were the ID, or part of it, then moving `a/b` to
`c/d` would change the ID of every record in the subtree, and every reference to them, in other tables, other systems
and URLs, would break.

So each record gets two keys:

| Column     | Holds                                   | Changes when the record moves | Made by                       |
|------------|-----------------------------------------|-------------------------------|-------------------------------|
| `id`       | a UUIDv7: identity, and creation time   | never                         | the client, `UuidV7.random()` |
| `path_key` | the packed path: location               | yes                           | the client, `Tuple.pack()`    |

The ID is made on the client, so it is known before the write and can be used in other systems at once. Neither key
needs anything from the database server.

## 2. A path as a tuple

Split the path into its segments and pack them as a tuple, one element per segment:

| Path       | Tuple                     | Packed (hex)                    |
|------------|---------------------------|---------------------------------|
| `a/b`      | `("a", "b")`              | `02 61 00 02 62 00`             |
| `a/b/a`    | `("a", "b", "a")`         | `02 61 00 02 62 00 02 61 00`    |
| `a/b/a/a`  | `("a", "b", "a", "a")`    | `02 61 00 02 62 00 02 61 00 02 61 00` |
| `a/bc`     | `("a", "bc")`             | `02 61 00 02 62 63 00`          |

Each string is type code `0x02`, its UTF-8 bytes, and a `0x00` terminator. Three properties follow, and the rest of
the guide relies on them:

- **A parent's key is a prefix of its descendants' keys.** The packed `a/b` is the first six bytes of the packed
  `a/b/a/a`.
- **The terminator separates siblings.** `a/bc` does not fall under `a/b`: after `02 61 00 02 62` comes `00` for
  `a/b` and `63` for `a/bc`. Packing the whole path as one string would lose this, because `"a/bc"` starts with
  `"a/b"`.
- **Keys concatenate.** The packed tuple of a path is the packed parent followed by the packed rest:
  `pack("a","b","a","a")` equals `pack("a","b")` followed by `pack("a","a")`. Moving a subtree is therefore a
  replacement of a byte prefix (section 5).

The types of the segments matter for the order. Strings sort by their UTF-8 bytes, so `"10"` comes before `"9"`;
integers sort by value, so `9` comes before `10`; and every string sorts before every integer (type code `0x02`
against `0x0b` to `0x1d`). Choose one type per
level of the hierarchy and keep to it.

## 3. Which rows a range covers

A node's packed key `P` gives three ranges, all over the same index:

| Question                         | Range                          | In Java                                              |
|----------------------------------|--------------------------------|------------------------------------------------------|
| the subtree, the node included   | `P` ≤ key < `P + FF`           | begin `node.pack()`, end `node.range().end()`        |
| the subtree, the node excluded   | `P + 00` ≤ key < `P + FF`      | `node.range()`                                       |
| the node itself                  | key = `P`                      | `node.pack()`                                        |

`P + FF` is `P` followed by one `0xFF` byte: no packed element starts with `0xFF`, so it lies after every key under
`P`. With the rows `a/b`, `a/b/a`, `a/b/a/a`, `a/b/a/b`, `a/bc` and `c`:

- the subtree of `a/b`, node included: `a/b`, `a/b/a`, `a/b/a/a`, `a/b/a/b`;
- the subtree of `a/b/a`, node included: `a/b/a`, `a/b/a/a`, `a/b/a/b`;
- neither ever includes `a/bc` or `c`.

The direct children of a node are not a range of their own: they are interleaved with their own descendants. Find
them with the subtree range and a depth (section 4).

## 4. PostgreSQL 18

### Table

```sql
CREATE TABLE entity (
    id        uuid     PRIMARY KEY,           -- identity: UuidV7.random(), made on the client
    path_key  bytea    NOT NULL UNIQUE,       -- location: Tuple.of(segments...).pack()
    path      text     NOT NULL,              -- the same path, readable: 'a/b/a/a'
    depth     integer  GENERATED ALWAYS AS (cardinality(string_to_array(path, '/'))) STORED,
    payload   jsonb
);
```

- `UNIQUE` on `path_key` gives one record per path, and the index that serves the ranges.
- `path` is for people and logs; queries use `path_key`.
- `depth` is the number of segments, computed from `path`, for finding direct children.

### Writing

The JDBC driver sends a `byte[]` as `bytea` and a `java.util.UUID` as `uuid`:

```java
Tuple path = Tuple.of("a", "b", "a", "a");

try (PreparedStatement insert = connection.prepareStatement(
        "INSERT INTO entity (id, path_key, path, payload) VALUES (?, ?, ?, ?::jsonb)")) {
    insert.setObject(1, UuidV7.random());
    insert.setBytes(2, path.pack());
    insert.setString(3, "a/b/a/a");
    insert.setString(4, json);
    insert.executeUpdate();
}
```

### Queries

```sql
-- The subtree of a/b, the node included: P <= path_key < P || FF
SELECT path FROM entity
WHERE path_key >= '\x026100026200' AND path_key < '\x026100026200ff'
ORDER BY path_key;

-- The direct children of a/b: the subtree without the node, at depth 3
SELECT path FROM entity
WHERE path_key > '\x026100026200' AND path_key < '\x026100026200ff' AND depth = 3
ORDER BY path_key;

-- One node
SELECT * FROM entity WHERE path_key = '\x026100026200026100026100';
```

The subtree queries are one range scan of the `UNIQUE` index. The children query scans the subtree and filters by
depth; if children are asked for often in large subtrees, an index on `(depth, path_key)` makes it a range of its own.

From Java, the bounds come from the tuple:

```java
Tuple node = Tuple.of("a", "b");

try (PreparedStatement query = connection.prepareStatement(
        "SELECT id, path, payload FROM entity WHERE path_key >= ? AND path_key < ? ORDER BY path_key")) {
    query.setBytes(1, node.pack());           // the node included
    query.setBytes(2, node.range().end());
    try (ResultSet rows = query.executeQuery()) {
        while (rows.next()) {
            UUID id = rows.getObject("id", UUID.class);
            Tuple path = Tuple.fromBytes(rows.getBytes("path_key"));   // when the segments are needed
            // ...
        }
    }
}
```

## 5. Moving a subtree

Because keys concatenate, moving `a/b` to `c/d` replaces the prefix `pack("a","b")` with `pack("c","d")` in every key
of the subtree, and the IDs stay as they are:

```java
byte[] from = Tuple.of("a", "b").pack();
byte[] to = Tuple.of("c", "d").pack();

try (PreparedStatement move = connection.prepareStatement("""
        UPDATE entity
        SET path_key = ? || substring(path_key FROM length(?) + 1),
            path     = ? || substring(path FROM length(?) + 1)
        WHERE path_key >= ? AND path_key < ?""")) {
    move.setBytes(1, to);
    move.setBytes(2, from);
    move.setString(3, "c/d");
    move.setString(4, "a/b");
    move.setBytes(5, from);
    move.setBytes(6, Tuple.of("a", "b").range().end());
    move.executeUpdate();
}
```

- The new keys are exactly what packing the new paths gives: `a/b/a/a` becomes `pack("c","d","a","a")`.
- Run it in a transaction with whatever else the move changes. The `UNIQUE` constraint stops the move if a path in the
  target subtree already exists.
- Do not move a node into its own subtree (`a/b` to `a/b/x`): that is not a valid move, and the range would overlap
  the keys it writes.

## 6. History in time order: path and ID in one key

Some data is a log rather than a set of entities: events, versions, audit entries. Nothing moves, and the questions are
"what happened at this path, in order" and "what happened under this subtree". Then the ID can go into the key itself,
after the path:

```java
byte[] key = Tuple.of("a", "b", UuidV7.random()).pack();   // path first, then time
```

```sql
CREATE TABLE event (
    key      bytea PRIMARY KEY,   -- Tuple.of(segments..., uuid7).pack()
    payload  jsonb
);
```

- **One path in time order**: the range of `Tuple.of("a", "b")`, read with `ORDER BY key`. The events of `a/b` come
  oldest first.
- **One path in a time window**: from `Tuple.of("a", "b", TimeUuids.lowerBound(from)).pack()` to
  `Tuple.of("a", "b", TimeUuids.lowerBound(to)).pack()`, end excluded.
- **A subtree is grouped by path, not merged by time.** The range of `("a", "b")` returns the events of `a/b/a` before
  those of `a/b` itself, because a string element (type `0x02`) sorts before a UUID (`0x30`). Each path's events are in
  time order; merging them across paths takes an `ORDER BY` on the time, or a separate index by time.

Use this shape for append-only data. For entities that have an identity and can move, keep the two columns of
section 4.

## 7. Other ordered stores

The keys are the same in any store that sorts by unsigned bytes. In FoundationDB, RocksDB or LMDB, the packed path is
the key itself (or the key of an index entry pointing at the record's ID), and `node.range()` gives the begin and end
of a range read. Section 6's path-and-ID keys work unchanged; in FoundationDB a
[versionstamp](foundationdb-versionstamp.md) can take the place of the UUID.

## Pitfalls

- **Normalize paths before packing.** `a/b`, `a/b/`, `/a/b` and `a//b` pack differently. Decide once: no leading or
  trailing slash, no empty segments, and Unicode in one normal form (`java.text.Normalizer` with `NFC`), since `é` can
  be one code point or two.
- **Keep one type per level.** A level that mixes `"9"` and `9` sorts every string before every integer.
- **`range()` excludes the node itself.** Start at `node.pack()` to include it (section 3).
- **Mind the key size.** PostgreSQL refuses B-tree entries over 2 704 bytes (`index row size ... exceeds btree version
  4 maximum 2704`). A packed path is about the length of its segments plus two bytes each; very deep or long paths need
  a limit in the application.
- **Do not put the path into the ID.** Not into a UUIDv7 (the RFC does not allow it), and not into a UUIDv8 (it does
  not fit, and a move would change the ID). See [principle 2](README.md#three-principles).
- **Compare in Java as unsigned bytes.** `Arrays.compareUnsigned(a, b)` or `Tuple.compareTo` give the order of the
  keys; `Arrays.compare` treats bytes as signed and does not.

## See also

- [Guides overview](README.md): principles, terms, and choosing a time-ordered ID.
- [Time-ordered keys with UUIDv7](uuidv7-metadata.md): the IDs used here, and time windows.
- [UUID generators](uuid-generators.md): which factory to use, and the odds of a collision.
