# CLAUDE.md

Guidance for Claude Code in this repository.

## What this is

`io.descoped.tuple:java-tuple`, JPMS module `io.descoped.tuple`: an order-preserving binary encoding of tuples,
byte-compatible with the FoundationDB tuple layer (`design/tuple.md` in apple/foundationdb). Owned by Descoped. It is a
small building block for other projects, not a FoundationDB client. `README.md` is the user documentation and must stay
true when behaviour changes, including the element type table.

It replaces `descoped/fdb-java-tuple`, an earlier copy of Apple's tuple sources used for exploration. Nothing from that
repository is carried over: this is a rewrite from the specification.

## Layout

- `Tuple`: the public, immutable value. Normalizes input (`canonicalize`), packs eagerly, holds the packed bytes.
- `TupleEncoder` / `TupleDecoder`: package-private, one small named method per type code.
- `UuidV7`, `UuidV7Generator`, `UuidV8Generator`, `TimeUuids`: time-ordered UUID factories and helpers (RFC 9562).
  `MillisecondCounter` (package-private) is the timestamp and counter both generators share. Randomness is always
  `SecureRandom`; a UUIDv8 generator cannot be made without a process number. `TimeUuidTest` covers the layouts, order,
  rollover, a clock going back, and generators kept apart by process number alone.
- `docs/`: guides on keys that sort the way they are queried (paths, UUIDv7, UUIDv8, UUID generators, FoundationDB
  versionstamps), with `docs/README.md` as the overview: shared principles, terms, and which guide answers what. Every
  guide has the same structure: Context, Goal, In short, numbered sections, Pitfalls, See also. Shared material lives
  once, in the overview; guides link to it instead of repeating it. Their Java must compile on Java 17 against the
  current API (a snippet marked JDK 26+ on JDK 26), and their SQL must run on PostgreSQL 18; check both when the API or
  a guide changes.
- `TypeCode`: the type code constants. `package-info.java` documents the wire format in one table.
- `Versionstamp` (record), `Range` (record with defensive copies): public values.

Efficiency choices, each covered by tests (keep them when changing the code):
- Strings are encoded to UTF-8 straight into the buffer (space for 3 bytes per char reserved once), and decoded with
  `new String` directly from the input when they hold no escaped 0x00; the strict UTF-8 check runs only when a U+FFFD
  appears.
- A nested tuple without nulls is written by copying its own packed bytes; a decoded nested tuple keeps the bytes it
  was read from. Neither is encoded twice.
- The element list of the empty tuple must allow `contains(null)`: no `List.of()` for element lists.

## Invariants (never violate)

- **Java 17 baseline** (`maven.compiler.release` 17): every class file is Java 17 whatever JDK builds it. CI builds and
  tests on JDK 17 only (`ci.yml`). Nothing above Java 17 in main or test code: no pattern-matching `switch`, no
  `List.getFirst()`; use `instanceof` patterns and switch expressions on constants.
- **JPMS first.** Exported: `io.descoped.tuple` only. Users never need `opens`.
- **No runtime dependencies.** The JDK alone. `fdb-java` is a test dependency and must stay one.
- **Always the latest stable version** of every library, plugin and action. Pre-releases only on request. Old and
  outdated code and configuration is removed, not carried along.
- **Wire compatibility.** Every encoding is byte-for-byte what the FoundationDB tuple layer produces, and decoding reads
  what it writes, to the same Java types (`Long` when an integer fits, `BigInteger` otherwise; raw float bits). Packed
  bytes sort, as unsigned, in element order. `FdbCompatibilityTest` and the specification vectors in
  `TupleEncodingTest` are the proof; never weaken their expectations to make a test pass.
- **Immutability.** `Tuple`, `Versionstamp` and `Range` cannot change after construction. Byte arrays are copied on the
  way in and on the way out. Equality and order are those of the packed bytes.
- **Malformed input** raises `IllegalArgumentException` naming the offset. Never a partial result, never a silent
  replacement (strict UTF-8 both ways; an unpaired surrogate is rejected).
- **Readability first.** The point of the rewrite was code that can be followed against the specification: small named
  methods, codes as unsigned ints, comments that explain the format. Performance work must not undo that.
- **Publishing.** Releases go only to Maven Central, through the Central Portal (profiles `central,sign`), from the
  owner's machine and only with the owner's approval; CI never releases. After a release is on Maven Central, update
  the version in the README's Install section. SNAPSHOTs go only to maven.descoped.io (Descoped's AWS CodeArtifact),
  from CI's `snapshot` job: on a push to master, or by `workflow_dispatch` on a branch as `X.Y.Z-<branch>-SNAPSHOT`.
  The job names the target on its command line (`-DaltSnapshotDeploymentRepository`), so the POM has no SNAPSHOT
  repository and a release version cannot reach it. No AWS account ID or CodeArtifact endpoint in any public file (POM, workflow,
  deployed POM, CI log).

## Testing

- Unit tests (JUnit 6) are a module of their own, `io.descoped.tuple.test` (`src/test/java/module-info.java`), in
  package `io.descoped.tuple.test`, so they reach only the exported API. It is `open` for JUnit only. It requires
  `fdb.java`, an automatic module (the jar has no descriptor), hence `@SuppressWarnings("requires-automatic")`.
- The FoundationDB reference types are written fully qualified (`com.apple.foundationdb.tuple.Tuple`) to keep them
  apart from ours. Its `getItems()` returns nested tuples as `List`.
- The random tests use fixed seeds, so a failure reproduces.
- `ModuleTest` checks the JPMS shape at run time: named module, one exported package, not open, only `java.base`,
  internals package-private. It fails if the tests ever fall back to the class path.

## Maven Wrapper

- `./mvnw` pins Maven (`.mvn/wrapper/maven-wrapper.properties`, type `only-script`, so no jar in the repository) and
  checks the download against `distributionSha256Sum`. When Maven is bumped, by Renovate or by hand, set
  `distributionSha256Sum` to the SHA-256 of the new `apache-maven-<version>-bin.zip`, after checking that zip against
  the `.sha512` Maven publishes next to it. A stale checksum fails the build, it never skips the check.

## Commands

```sh
./mvnw verify                    # compile with -Xlint:all -Werror, tests on the module path (JUnit 6)
./mvnw verify javadoc:javadoc    # plus Javadoc with doclint, warnings fail the build
src/it/consumer/run.sh           # after package: a separate named module uses the jar, module path and class path
./mvnw verify -P coverage        # JaCoCo report in target/site/jacoco
```
