package consumer;

import io.descoped.tuple.TimeUuids;
import io.descoped.tuple.Tuple;
import io.descoped.tuple.UuidV7;
import io.descoped.tuple.Versionstamp;

import java.util.Arrays;
import java.util.UUID;

/**
 * Uses the library as an application would: pack a key, decode it, check the order, pack with a versionstamp, and make
 * a UUIDv7.
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        String mode = args.length > 0 ? args[0] : "?";
        UUID id = UUID.randomUUID();

        byte[] key = Tuple.of("user", 42, id).pack();
        Tuple decoded = Tuple.fromBytes(key);
        check(decoded.getString(0).equals("user") && decoded.getLong(1) == 42 && decoded.getUUID(2).equals(id),
                "round trip");
        check(Arrays.compareUnsigned(Tuple.of("user", 41).pack(), Tuple.of("user", 42).pack()) < 0, "order");
        check(Tuple.of("user").range().contains(key), "range");
        check(Tuple.of("event", Versionstamp.incomplete(0)).packWithVersionstamp(new byte[0]).length == 24,
                "versionstamp");
        UUID uuid7 = UuidV7.random();
        check(uuid7.version() == 7 && TimeUuids.lowerBound(TimeUuids.millis(uuid7)).version() == 0, "time-ordered UUID");

        Module module = Tuple.class.getModule();
        System.out.println("OK (" + mode + "): " + (module.isNamed() ? "module " + module.getName() : "class path"));
    }

    private static void check(boolean condition, String what) {
        if (!condition) {
            throw new AssertionError(what);
        }
    }
}
