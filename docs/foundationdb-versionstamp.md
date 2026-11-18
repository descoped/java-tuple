# Time-ordered keys in FoundationDB: versionstamps

The [UUIDv7](uuidv7-metadata.md) and [UUIDv8](uuidv8-metadata.md) guides make time-ordered IDs on the client. When the
data lives in FoundationDB there is a third way: the metadata goes in the key as tuple elements, and the order comes
from the database itself, through a **versionstamp** that FoundationDB fills in when the transaction commits.

## In short

```java
Tuple event = Tuple.of("events", tenantId, "orders", Versionstamp.incomplete(0));
```

- The **metadata** comes first: any number of elements of any size, readable, and every prefix is a range to scan.
- The **versionstamp** comes last. It is a placeholder until commit; FoundationDB then replaces it with the
  transaction's version, which is unique and increases with every commit.
- No ID generator, no clock and no randomness: the keys sort in commit order within each prefix, and cannot collide.

## 1. What a versionstamp is

Twelve bytes, big-endian, compared unsigned:

```
bytes 0–7    commit version   8   the database version the transaction committed at
bytes 8–9    batch version    2   orders the transactions committed at the same version
bytes 10–11  user version     2   chosen by the client, orders the keys written in one transaction
```

The first ten bytes are the transaction version and come from the database; the last two are yours.

- **Unique**: no two transactions get the same transaction version. With a different user version for each key in a
  transaction, no two keys ever get the same versionstamp, across the whole cluster.
- **Ordered by commit**: a transaction that commits later gets a larger versionstamp. That is the order in which the
  data became visible, stricter than any clock: there is no clock skew between machines and no clock that goes back.
- **Not a time of day**: the commit version advances about a million per second, but it does not tell the time. When
  you need the time, store it as well, in the value or as a key element of its own.
- **Unknown before the commit**: the client learns the versionstamp, and so the full key, only after committing.

In java-tuple, `Versionstamp` is a record of the three parts and `Versionstamp.incomplete(userVersion)` makes the
placeholder. In a tuple it is type code `0x33` followed by the 12 bytes.

## 2. Writing

java-tuple builds the key, and the FoundationDB client (`org.foundationdb:fdb-java`, which java-tuple does not depend
on) writes it with the `SET_VERSIONSTAMPED_KEY` mutation. That mutation needs to know where the placeholder is:
`packWithVersionstamp(prefix)` packs the tuple behind the given prefix (a subspace, or an empty array for none) and
appends the placeholder's offset as a 4-byte little-endian integer, the format of API version 520 and later.

```java
int userVersion = 0;
Tuple event = Tuple.of("events", tenantId, "orders", Versionstamp.incomplete(userVersion));
byte[] key = event.packWithVersionstamp(new byte[0]);

CompletableFuture<byte[]> transactionVersion = db.run(tr -> {
    tr.mutate(MutationType.SET_VERSIONSTAMPED_KEY, key, value);
    return tr.getVersionstamp();          // completes after the commit, with the 10-byte transaction version
});
```

- **One placeholder per key**: `packWithVersionstamp` refuses a tuple with none or with more than one.
- **Several keys in one transaction**: give each its own user version, `incomplete(0)`, `incomplete(1)` and so on, up
  to 65 535. They share the transaction version and sort in user-version order.
- **No conflicts**: a transaction that only writes versionstamped keys reads nothing, so it never fails on a conflict,
  and every key it writes is new. Many writers can append to the same prefix without retries and without a hot key,
  which makes this the natural shape for logs, queues and event streams.
- **References to the new key**: `SET_VERSIONSTAMPED_VALUE` fills a placeholder in a value instead, for example an
  index entry that points at the event written in the same transaction.
- **Retries can duplicate**: when a commit's outcome is unknown (`commit_unknown_result`), `db.run` retries, and the
  retry writes a second key with a new versionstamp. Where duplicates matter, put a client-made ID in the value and
  skip repeats when reading, or record that ID under a key of its own and check it in the transaction.

### The key you wrote

To build the full key after the commit, join the transaction version with the user version:

```java
byte[] stamp = Arrays.copyOf(transactionVersion.join(), Versionstamp.BYTES);   // 10 bytes, padded to 12
stamp[10] = (byte) (userVersion >>> 8);
stamp[11] = (byte) userVersion;

Versionstamp written = Versionstamp.fromBytes(stamp);
Tuple writtenKey = Tuple.of("events", tenantId, "orders", written);
```

## 3. Reading

A prefix of the key is a range, and the keys in it come back in commit order:

```java
Range orders = Tuple.of("events", tenantId, "orders").range();

List<KeyValue> oldest = db.read(tr -> tr.getRange(orders.begin(), orders.end(), 100, false).asList().join());
List<KeyValue> latest = db.read(tr -> tr.getRange(orders.begin(), orders.end(), 100, true).asList().join());

for (KeyValue kv : oldest) {
    Versionstamp stamp = Tuple.fromBytes(kv.getKey()).getVersionstamp(3);
    // ...
}
```

- **Imports**: both libraries have a `Range` (`io.descoped.tuple.Range`, `com.apple.foundationdb.Range`), and
  `com.apple.foundationdb.tuple` has its own `Tuple` and `Versionstamp`. Import the java-tuple classes by name, not
  with wildcards.
- **Build the range from the prefix**: a tuple that still holds an incomplete versionstamp has no range.
- **Paging**: continue after the last key read, with that key followed by `0x00` as the new begin.
- **Time windows**: versionstamps are versions, not times, so "everything between 10:00 and 11:00" is not a key range.
  Store the time as a key element (`("events", tenantId, "orders", hour, versionstamp)`) or keep an index by time.
- **Subspaces**: java-tuple has no `range(prefix)` yet. With a subspace prefix in front of the tuple, put the prefix in
  front of `begin()` and `end()` yourself, or make the subspace the tuple's first element.

## 4. Choosing between the three

|                         | Versionstamp (FoundationDB)      | UUIDv7, metadata in the key        | UUIDv8, metadata in the ID          |
|-------------------------|----------------------------------|------------------------------------|-------------------------------------|
| Made by                 | the database, at commit          | the client                         | the client                          |
| Order                   | commit order, cluster-wide       | each client's clock, to the ms     | each client's clock, to the ms      |
| Unique by               | the database, guaranteed         | 62 to 74 random bits               | a counter, plus a process number in the tag |
| Known before the write  | no                               | yes                                | yes                                 |
| Metadata                | key elements, any size           | key elements, any size             | 30 bits inside the ID               |
| Time windows by key     | no, store the time too           | yes                                | yes                                 |
| Size in a tuple key     | 13 bytes                         | 17 bytes                           | 17 bytes                            |
| Works in                | FoundationDB                     | any ordered store, PostgreSQL 18   | any ordered store, PostgreSQL 18    |

- Use a **versionstamp** when the data lives in FoundationDB and the order that matters is the order of commits.
- Use a **UUIDv7** when the ID must exist before the write, the data also lives elsewhere, or you query by time.
- Use a **UUIDv8** only when the metadata must travel inside the ID.
