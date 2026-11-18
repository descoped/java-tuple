# UUID generators: choices, precautions and collision odds

## Context

java-tuple has small factories for time-ordered UUIDs (UUIDv7 and UUIDv8). Making an ID looks trivial, but the choices
behind it decide how likely two IDs are to collide, whether they come back in creation order, how easy they are to
guess, and what happens when a clock jumps or a process is cloned. The factories are a convenience: if your needs
differ, you are encouraged to write your own strategy.

## Goal

After this guide you can:
- pick the right factory for a use case;
- state the odds of a collision for your load;
- run the generators safely: process numbers, randomness, clocks, snapshots;
- write a generator of your own that keeps the same guarantees.

How to use the IDs in keys and in PostgreSQL is in [UUIDv7](uuidv7-metadata.md), [UUIDv8](uuidv8-metadata.md) and
[path keys](path-keys.md); the shared principles and terms are in the [guides overview](README.md).

## In short

- `UuidV7.random()` is the default: 74 random bits, no state, no limits.
- `UuidV7Generator` when one process must read its IDs back in creation order; `UuidV8Generator` when the ID must
  carry a small tag. Both are unique within a process by construction.
- Collisions are negligible for UUIDv7 at any realistic load, and impossible for UUIDv8 as long as **no two running
  generators share a process number**.
- Randomness is always `SecureRandom`. IDs are identifiers, never secrets.

## 1. The factories

| Factory                        | Layout after the 48-bit timestamp           | Use it when                                         |
|--------------------------------|---------------------------------------------|-----------------------------------------------------|
| `UuidV7.random()`              | 74 random bits                              | the default: IDs sorted by time to the millisecond  |
| `UuidV7Generator.next()`       | 12-bit counter, 62 random bits              | a process must read its IDs back in creation order  |
| `UuidV8Generator.next(tag)`    | 12-bit counter, process number and tag (30 bits), 32 random bits | the ID must carry metadata on its own |
| `TimeUuids`                    | helpers: `millis`, `lowerBound`, `isTimeOrdered`, `compare` | reading times, building range bounds, sorting |

```java
UUID id = UuidV7.random();

UuidV7Generator ordered = new UuidV7Generator();          // one per process
UUID next = ordered.next();

UuidV8Generator tagged = new UuidV8Generator(10, processNumber);   // 10 bits of process number, 20 of tag
UUID routed = tagged.next(regionNumber);
int region = tagged.tag(routed);

UUID from = TimeUuids.lowerBound(Instant.parse("2026-11-18T10:00:00Z"));   // a range bound, never an ID
```

## 2. Entropy against order

Every bit given to a counter or to metadata is a bit less randomness. The more random bits, the less likely a
collision and the harder an ID is to guess:

| Form                                   | Random bits | Order within a millisecond      |
|----------------------------------------|-------------|---------------------------------|
| `UUID.randomUUID()` (UUIDv4), for reference | 122        | none, and no time order at all  |
| `UuidV7.random()`                      | 74          | random                          |
| `UuidV7Generator`                      | 62          | creation order, per generator   |
| `UuidV8Generator`                      | 32          | creation order, per generator   |

`UuidV7.random()` is the most a UUIDv7 can hold. The generators trade random bits for order, and in the UUIDv8 case for
uniqueness by construction instead of by chance.

## 3. The odds of a collision

Figures per year of continuous load (3.16 × 10¹⁰ milliseconds). A collision needs two IDs in the same millisecond, so
what counts is the number of IDs made per millisecond, across every generator writing to the same key space.

### `UuidV7.random()`

`n` IDs in one millisecond collide with a chance of about n² / 2⁷⁵.

| Load                  | Expected collisions per year       |
|-----------------------|------------------------------------|
| 1 000 IDs per second  | 8 × 10⁻¹³                          |
| 1 million per second  | 8 × 10⁻⁷, once in a million years  |
| 10 million per second | 8 × 10⁻⁵                           |

### `UuidV7Generator`

Within one generator, never: the counter gives every ID of a millisecond its own value. Between `k` generators that
each make `m` IDs per millisecond, two IDs collide only with the same millisecond, the same counter value and the same
62 random bits: about m·k(k−1) / 2⁶³ per millisecond.

| Load                                         | Expected collisions per year |
|----------------------------------------------|------------------------------|
| 2 processes, 1 ID per millisecond each       | 7 × 10⁻⁹                     |
| 10 processes, 1 million per second in total  | 3 × 10⁻⁵                     |
| 100 processes, 100 million per second in total | 3 × 10⁻³                   |

Negligible, though higher than random-only at the same load: the counters make every generator use the low values
first, so IDs meet on them more often than fully random bits would.

### `UuidV8Generator`

Within one generator, never (the counter). Between generators with **different process numbers**, never: their IDs
differ in the process number bits, whatever the random bits are.

Between generators that **share a process number**, only the 32 random bits are left, and that is not enough: about
m·k(k−1) / 2³³ per millisecond for the same tag.

| Load (same process number, same tag)         | Expected collisions per year |
|----------------------------------------------|------------------------------|
| 2 processes, 1 ID per millisecond each       | **7**                        |
| 10 processes, 10 IDs per millisecond each    | **about 3 300**              |

This is why the process number is a required constructor argument, and why it must be unique, as described next.

## 4. Precautions

### Process numbers must be unique among running generators

Not per service or per deployment: per running generator. Two replicas started from the same configuration, a
restarted container that comes up while the old one still runs, and a VM or container restored from a snapshot all
share a number unless something hands them out. Good sources:

- a lease in the database (a row per number with an expiry, claimed at start and renewed while running);
- a StatefulSet ordinal or another orchestrator-assigned index that is unique while the pod runs;
- a coordination service (etcd, ZooKeeper, a FoundationDB key).

A number copied into every node's configuration file is not a source. Size `processBits` for the most generators that
can run at once, with room to spare: 10 bits give 1024.

### Randomness comes from `SecureRandom`

The factories use `new SecureRandom()`. Never use `java.util.Random`, `ThreadLocalRandom` or `SplittableRandom` for
IDs: their state is small and predictable, so IDs can be guessed, and two generators that start from related seeds
produce related bits, which turns "1 in 2⁶²" into "likely". Avoid `SecureRandom.getInstanceStrong()` too: on some
systems it blocks until the operating system has gathered entropy.

The constructors accept a `SecureRandom`, so tests can pass a seeded one (`SecureRandom.getInstance("SHA1PRNG")`
with `setSeed` before its first use). Never seed one in production.

### Snapshots and clones repeat state

A VM or container restored from a snapshot, or a process cloned with CRaC, resumes with the generator's memory as it
was: the same last timestamp, the same counter, and possibly the same random state. Two copies restored from one
snapshot then share a process number too. Treat a restore like a new process: new generators, and a newly assigned
process number.

### The clock

- **Order across machines is only as good as their clocks.** Within one generator, IDs strictly increase. Across
  machines, an ID made a millisecond later on a machine whose clock is behind sorts earlier. Keep clocks in sync (NTP,
  chrony); for an exact global order, use [versionstamps](foundationdb-versionstamp.md).
- **When the clock goes back**, the generators keep their last timestamp and count on, so their IDs never decrease.
  After 4096 such IDs they wait until the clock has caught up. A large step back (a wrong clock corrected by hours)
  stalls a busy generator until then; `UuidV7.random()` does not stall, but its IDs then sort before the ones already
  made.
- **More than 4096 IDs in one millisecond** from one generator: the next one waits for the following millisecond. At
  that rate, use several generators (each with its own process number, for UUIDv8).
- **The timestamp is 48 bits of Unix milliseconds**, valid from 1970 to the year 10889. A clock outside that range is
  an error, not a wrap-around.

### IDs are not secrets

Anyone who sees an ID can read when it was made, and for a UUIDv8, its process number and tag. Someone who has seen one UUIDv8
ID knows everything about its neighbours except 32 random bits: about four billion guesses, within reach of a
determined attacker. RFC 9562 §8 says not to use UUIDs as security capabilities; for UUIDv8 that is a hard rule. Check
access to the thing an ID names; never treat knowing the ID as permission.

### Bounds are not IDs

`TimeUuids.lowerBound` returns a UUID of version 0 to use in range queries. Never store one as an ID:
`TimeUuids.isTimeOrdered` rejects it, and so does `TimeUuids.millis`.

### Sorting in Java

`UUID.compareTo` compares the two halves as signed longs. For UUIDv7 and UUIDv8 IDs and their bounds it agrees with the byte
order until the year 6429, but not for UUIDs in general. Sort with `TimeUuids.compare`, or by packed tuples, which is
the order of tuple keys and of PostgreSQL.

### Throughput

Measured on a laptop, one thread:

| Factory              | Cost per ID                                  | Limit                                  |
|----------------------|----------------------------------------------|----------------------------------------|
| `UUID.randomUUID()`  | about 140 ns                                 | none                                   |
| `UuidV7.random()`    | about 165 ns (two `SecureRandom` reads)      | none                                   |
| `UuidV7Generator`    | under 100 ns (one read and a short lock)     | 4096 per millisecond, about 4 million per second |
| `UuidV8Generator`    | under 100 ns (one read and a short lock)     | 4096 per millisecond, about 4 million per second |

A generator that is asked for more than 4096 IDs in a millisecond waits for the next one; a tight loop runs at exactly
that limit, 244 ns per ID. Below it, the cost of an ID is the read from `SecureRandom`. Under heavy concurrency the
lock and the shared `SecureRandom` become the limit; measure first, then give each thread its own generator (with its
own process number, for UUIDv8).

## 5. Writing your own generator

You are encouraged to, when the factories do not fit: another split of the bits, a counter seeded randomly, sub-
millisecond time (RFC 9562 method 3), or an ID scheme of your own. Check it against this list:

1. **Version and variant.** Set the four version bits (0111 for UUIDv7, 1000 for UUIDv8) and the two variant bits (10). Only
   then do PostgreSQL's `uuid_extract_version` and Java's `UUID.version()` report it correctly.
2. **Compliance.** A UUIDv7's free bits are for randomness and ordering only (RFC 9562 §5.7, §6.2). Anything else, a node
   number or application data, makes it a UUIDv8 (§6.4, §5.8).
3. **Time first, big-endian.** Keep the 48-bit Unix milliseconds in bits 0–47 if the IDs should sort by time and
   together with other UUIDv7 and UUIDv8 IDs.
4. **Uniqueness by construction where you can.** A counter within a process and a unique number per process cost few
   bits and remove whole classes of collisions; randomness covers the rest. Work out the odds for your load with the
   formulas above.
5. **Counter rollover and a clock that goes back.** Decide what happens: wait, or move the timestamp ahead (which never
   blocks but lets IDs drift ahead of real time). Never let IDs decrease or repeat.
6. **`SecureRandom`**, not a fast generator with small state.
7. **Thread safety.** The read of the clock, the counter update and the use of the result must happen as one step.
8. **Tests** for the layout bits, order across a rollover, a clock going back, and two generators kept apart by
   construction even with identical random streams. `TimeUuidTest` in this repository does exactly that for the
   factories.
9. **Write the layout down** next to the code, and never change it for IDs already issued: nothing in an ID says which
   layout made it.

## See also

- [Guides overview](README.md): principles, terms, and choosing a time-ordered ID.
- [Time-ordered keys with UUIDv7](uuidv7-metadata.md) and [Metadata inside a UUIDv8](uuidv8-metadata.md): using the
  IDs in keys and in PostgreSQL 18.
- [RFC 9562](https://www.rfc-editor.org/rfc/rfc9562): the UUID specification.
