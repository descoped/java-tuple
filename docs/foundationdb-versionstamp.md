# Versionstamps in FoundationDB

## Context

The [UUIDv7](uuidv7-metadata.md) and [UUIDv8](uuidv8-metadata.md) guides make time-ordered IDs on the client, ordered
by each client's clock. When the data lives in FoundationDB there is another way: the database itself stamps each key
with the version of the transaction that wrote it. The order is then the order of commits across the whole cluster,
with no clocks, no generators and no chance of a collision.

## Goal

After this guide you can:
- explain what a versionstamp holds and what it guarantees;
- write keys that FoundationDB completes with a versionstamp at commit, using java-tuple;
- find out which key a transaction wrote;
- read a prefix back in commit order, oldest or newest first.

It assumes the principles in the [guides overview](README.md), which also compares versionstamps with UUIDs.

## In short

```java
Tuple event = Tuple.of("events", tenantId, "orders", Versionstamp.incomplete(0));
```

- The **metadata** comes first, as tuple elements: any number, any size, and every prefix is a range to scan.
- The **versionstamp** comes last. It is a placeholder until commit; FoundationDB then fills in the transaction's
  version, which is unique and grows with every commit.
- The keys under each prefix come back in commit order and cannot collide.

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
  data became visible, stricter than any clock: no clock skew between machines, no clock that goes back.
- **Not a time of day**: the commit version advances about a million per second, but it does not tell the time.
- **Unknown before the commit**: the client learns the versionstamp, and so the full key, only after committing.

In java-tuple, `Versionstamp` is a record of the three parts, and `Versionstamp.incomplete(userVersion)` makes the
placeholder. In a tuple it is type code `0x33` followed by the 12 bytes.

## 2. Writing

java-tuple builds the key; the FoundationDB client (`org.foundationdb:fdb-java`, which java-tuple does not depend on)
writes it with the `SET_VERSIONSTAMPED_KEY` mutation. The mutation needs to know where the placeholder is:
`packWithVersionstamp(prefix)` packs the tuple after the given prefix (a subspace, or an empty array for none) and
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
  to 65 535. They share the transaction version and sort by user version.
- **No conflicts**: a transaction that only writes versionstamped keys reads nothing, so it never fails on a conflict,
  and every key it writes is new. Many writers can append to the same prefix without retries and without a hot key:
  the natural shape for logs, queues and event streams.
- **References to the new key**: `SET_VERSIONSTAMPED_VALUE` fills a placeholder in a value instead, for example in an
  index entry that points at the event written in the same transaction.

## 3. The key you wrote

After the commit, join the transaction version with the user version to get the full key:

```java
byte[] stamp = Arrays.copyOf(transactionVersion.join(), Versionstamp.BYTES);   // 10 bytes, padded to 12
stamp[10] = (byte) (userVersion >>> 8);
stamp[11] = (byte) userVersion;

Versionstamp written = Versionstamp.fromBytes(stamp);
Tuple writtenKey = Tuple.of("events", tenantId, "orders", written);
```

## 4. Reading

A prefix of the key is a range, and the keys in it come back in commit order:

```java
Range orders = Tuple.of("events", tenantId, "orders").range();

List<KeyValue> oldest = db.read(tr -> tr.getRange(orders.begin(), orders.end(), 100, false).asList().join());
List<KeyValue> newest = db.read(tr -> tr.getRange(orders.begin(), orders.end(), 100, true).asList().join());

for (KeyValue kv : oldest) {
    Versionstamp stamp = Tuple.fromBytes(kv.getKey()).getVersionstamp(3);
    // ...
}
```

To read the next page, start after the last key read: that key followed by a `0x00` byte is the new begin.

## Pitfalls

- **Retries can write twice.** When a commit's outcome is unknown (`commit_unknown_result`), `db.run` retries, and the
  retry writes a second key with a new versionstamp. Where duplicates matter, put a client-made ID in the value and
  skip repeats when reading, or record that ID under a key of its own and check it in the transaction.
- **No time windows by key.** Versionstamps are versions, not times, so "everything between 10:00 and 11:00" is not a
  key range. Store the time as a key element (`("events", tenantId, "orders", hour, versionstamp)`), or keep an index
  by time.
- **Build ranges from the prefix.** A tuple that still holds an incomplete versionstamp has no range.
- **Import the right classes.** Both libraries have a `Range` (`io.descoped.tuple.Range`,
  `com.apple.foundationdb.Range`), and `com.apple.foundationdb.tuple` has its own `Tuple` and `Versionstamp`. Import
  the java-tuple classes by name, not with wildcards.
- **Subspaces.** java-tuple has no `range(prefix)` yet. With a subspace prefix in front of the tuple, put the prefix in
  front of `begin()` and `end()` yourself, or make the subspace the tuple's first element.

## See also

- [Guides overview](README.md): principles, terms, and how versionstamps compare with UUIDv7 and UUIDv8.
- [Hierarchical paths as keys](path-keys.md): paths as key prefixes; a versionstamp can take the UUID's place in its
  path-and-ID keys.
- [FoundationDB tuple layer](https://github.com/apple/foundationdb/blob/main/design/tuple.md): the encoding.
