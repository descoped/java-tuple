# Guides

These guides show how to store and find data by keys that sort the way you query them: by location (a path), by
time, or both. They use java-tuple with PostgreSQL 18 and with FoundationDB. Each guide stands alone, but they share
the principles and terms on this page; read it first.

## The guides

| Guide                                            | Read it when you want to ...                                          |
|--------------------------------------------------|-----------------------------------------------------------------------|
| [Hierarchical paths as keys](path-keys.md)       | find entities by a path prefix (a subtree), and move them             |
| [Time-ordered keys with UUIDv7](uuidv7-metadata.md) | give records IDs that sort by creation time, with metadata in the key |
| [Metadata inside a UUIDv8](uuidv8-metadata.md)   | carry a small, fixed piece of metadata inside the ID itself           |
| [UUID generators](uuid-generators.md)            | choose a UUID factory, know the odds of a collision, or write your own |
| [Versionstamps in FoundationDB](foundationdb-versionstamp.md) | order records by commit in FoundationDB                  |

A typical path through them: this page, then the guide for your question. The UUID guides refer to
[UUID generators](uuid-generators.md) for the details of making IDs.

## Three principles

**1. A packed tuple sorts like its elements, so a prefix is a range.** `Tuple.of("a", "b").pack()` is a prefix of the
packed form of every tuple that starts with `"a", "b"`, and all of them sit next to each other in sorted order. Any
store that sorts keys as unsigned bytes can find them with one range scan: FoundationDB, RocksDB, a B-tree, and
PostgreSQL, which compares both `bytea` and `uuid` that way.

**2. An ID is identity; metadata is not.** An ID names a thing for as long as it exists and is copied into references
everywhere. Metadata (a path, a tenant, a type) describes the thing and may change. So metadata goes into key elements
and columns next to the ID, where it can change and be indexed, and the ID carries only what never changes: its
uniqueness and the time it was made. The one exception is a small, immutable value that must travel inside the ID,
which is what UUIDv8 is for.

**3. Time order comes from a timestamp at the front.** A UUIDv7 or UUIDv8 starts with the time it was made, so
sorting the IDs sorts by time. A FoundationDB versionstamp starts with the commit version, so sorting sorts by commit.
Put such an ID last in a key, and the records under each prefix come back in time order.

## Choosing a time-ordered ID

|                        | UUIDv7                            | UUIDv8 (`UuidV8Generator`)              | Versionstamp                     |
|------------------------|-----------------------------------|-----------------------------------------|----------------------------------|
| Made by                | the client                        | the client                              | FoundationDB, at commit          |
| Order                  | each client's clock, to the ms    | each client's clock, to the ms          | commit order, cluster-wide       |
| Unique by              | 62 to 74 random bits              | a counter and a process number          | the database, guaranteed         |
| Known before the write | yes                               | yes                                     | no                               |
| Metadata               | in the key, next to the ID        | 30 bits inside the ID, plus the key     | in the key, next to the ID       |
| Time windows by key    | yes                               | yes                                     | no, store the time as well       |
| Size in a tuple key    | 17 bytes                          | 17 bytes                                | 13 bytes                         |
| Works in               | any ordered store, PostgreSQL 18  | any ordered store, PostgreSQL 18        | FoundationDB                     |

- **UUIDv7** is the default.
- **UUIDv8** only when a small, immutable value must travel inside the ID.
- **Versionstamps** when the data lives in FoundationDB and the order that matters is the order of commits.

## Terms

| Term              | Meaning in these guides                                                                          |
|-------------------|--------------------------------------------------------------------------------------------------|
| packed tuple      | the bytes `Tuple.pack()` returns; they sort like the tuple's elements                            |
| key               | the value a store sorts and looks up by: a packed tuple, or a column of a primary key or index   |
| prefix range      | every key that starts with a given packed tuple; `Tuple.range()` returns its bounds              |
| ID                | a value that names one record for its whole life: here a UUID                                    |
| metadata          | facts about a record that a query filters or groups by: a path, a tenant, a type                 |
| path              | a record's place in a hierarchy, such as `a/b/a/a`, stored as a tuple of its segments            |
| UUIDv7            | RFC 9562 UUID: 48-bit Unix milliseconds, then random bits (or a counter)                         |
| UUIDv8            | RFC 9562 UUID whose bits the application defines; here: time, counter, process number, tag       |
| process number    | a number unique to each running ID generator; keeps their UUIDv8s apart                          |
| tag               | the metadata inside a UUIDv8                                                                     |
| lower bound       | the smallest UUID of a millisecond (`TimeUuids.lowerBound`), used to bound time ranges; never an ID |
| versionstamp      | FoundationDB's 12-byte commit version plus user version, filled in at commit                     |

## How the guides are checked

Every Java snippet compiles on Java 17 against the java-tuple API (and, for FoundationDB, `fdb-java`). Every SQL
snippet has been run on PostgreSQL 18.7, with keys and IDs made by java-tuple, including the query plans the guides
describe. When the API or a guide changes, check both again.
