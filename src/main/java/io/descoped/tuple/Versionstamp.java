package io.descoped.tuple;

import java.nio.ByteBuffer;

/**
 * A 96-bit versionstamp: the database commit version (8 bytes) and batch version (2 bytes) that make up a
 * transaction version, plus a user version (2 bytes) that orders writes within one transaction.
 *
 * <p>A versionstamp is <em>incomplete</em> when its transaction version is all 0xFF bytes: a placeholder that the
 * database fills in at commit time. See {@link Tuple#packWithVersionstamp(byte[])}.
 *
 * <p>Versionstamps compare in the same order as their encoded bytes, so incomplete ones sort last.
 *
 * @param commitVersion the database commit version, compared as unsigned
 * @param batchVersion  the batch version, 0..65535
 * @param userVersion   the user version, 0..65535
 */
public record Versionstamp(long commitVersion, int batchVersion, int userVersion) implements Comparable<Versionstamp> {

    /** Encoded size in bytes. */
    public static final int BYTES = 12;

    private static final long INCOMPLETE_COMMIT_VERSION = 0xffff_ffff_ffff_ffffL;
    private static final int INCOMPLETE_BATCH_VERSION = 0xffff;
    private static final int MAX_UNSIGNED_SHORT = 0xffff;

    /**
     * Checks that the batch and user versions fit in two bytes each.
     *
     * @param commitVersion the database commit version, compared as unsigned
     * @param batchVersion  the batch version, 0..65535
     * @param userVersion   the user version, 0..65535
     * @throws IllegalArgumentException if either is outside 0..65535
     */
    public Versionstamp {
        requireUnsignedShort("batchVersion", batchVersion);
        requireUnsignedShort("userVersion", userVersion);
    }

    /**
     * A placeholder whose transaction version the database fills in at commit time.
     *
     * @param userVersion orders writes within one transaction, 0..65535
     * @return the incomplete versionstamp
     */
    public static Versionstamp incomplete(int userVersion) {
        return new Versionstamp(INCOMPLETE_COMMIT_VERSION, INCOMPLETE_BATCH_VERSION, userVersion);
    }

    /**
     * Reads the 12-byte big-endian form.
     *
     * @param bytes exactly 12 bytes
     * @return the versionstamp
     * @throws IllegalArgumentException if {@code bytes} is not 12 bytes long
     */
    public static Versionstamp fromBytes(byte[] bytes) {
        if (bytes.length != BYTES) {
            throw new IllegalArgumentException("A versionstamp is " + BYTES + " bytes, got " + bytes.length);
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        return new Versionstamp(buffer.getLong(), Short.toUnsignedInt(buffer.getShort()), Short.toUnsignedInt(buffer.getShort()));
    }

    /**
     * The 12-byte big-endian form.
     *
     * @return a new array of 12 bytes
     */
    public byte[] toBytes() {
        return ByteBuffer.allocate(BYTES)
                .putLong(commitVersion)
                .putShort((short) batchVersion)
                .putShort((short) userVersion)
                .array();
    }

    /**
     * Whether the transaction version is filled in.
     *
     * @return {@code false} for a placeholder made by {@link #incomplete(int)} or decoded from one
     */
    public boolean isComplete() {
        return commitVersion != INCOMPLETE_COMMIT_VERSION || batchVersion != INCOMPLETE_BATCH_VERSION;
    }

    /**
     * Compares in the order of the encoded bytes: commit version (unsigned), then batch version, then user version.
     *
     * @param other the versionstamp to compare with
     * @return negative, zero or positive as this versionstamp sorts before, with or after {@code other}
     */
    @Override
    public int compareTo(Versionstamp other) {
        int byCommit = Long.compareUnsigned(commitVersion, other.commitVersion);
        if (byCommit != 0) {
            return byCommit;
        }
        int byBatch = Integer.compare(batchVersion, other.batchVersion);
        return byBatch == 0 ? Integer.compare(userVersion, other.userVersion) : byBatch;
    }

    /**
     * A readable form, with the commit version as unsigned.
     *
     * @return the components, or the user version alone for an incomplete versionstamp
     */
    @Override
    public String toString() {
        if (!isComplete()) {
            return "Versionstamp[incomplete, userVersion=" + userVersion + "]";
        }
        return "Versionstamp[commitVersion=" + Long.toUnsignedString(commitVersion)
                + ", batchVersion=" + batchVersion + ", userVersion=" + userVersion + "]";
    }

    private static void requireUnsignedShort(String name, int value) {
        if (value < 0 || value > MAX_UNSIGNED_SHORT) {
            throw new IllegalArgumentException(name + " must be between 0 and 65535, got " + value);
        }
    }
}
