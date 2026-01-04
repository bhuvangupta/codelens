package com.codelens.util;

import java.nio.ByteBuffer;
import java.util.UUID;

/**
 * Utility class for UUID operations.
 */
public final class UuidUtils {

    private UuidUtils() {
        // Utility class - prevent instantiation
    }

    /**
     * Convert UUID to byte array for native SQL queries.
     * MySQL stores UUIDs as BINARY(16), so we need to convert.
     *
     * @param uuid The UUID to convert
     * @return 16-byte array representation, or null if uuid is null
     */
    public static byte[] toBytes(UUID uuid) {
        if (uuid == null) {
            return null;
        }
        ByteBuffer bb = ByteBuffer.wrap(new byte[16]);
        bb.putLong(uuid.getMostSignificantBits());
        bb.putLong(uuid.getLeastSignificantBits());
        return bb.array();
    }

    /**
     * Convert byte array back to UUID.
     *
     * @param bytes 16-byte array representation
     * @return UUID, or null if bytes is null
     */
    public static UUID fromBytes(byte[] bytes) {
        if (bytes == null || bytes.length != 16) {
            return null;
        }
        ByteBuffer bb = ByteBuffer.wrap(bytes);
        long mostSigBits = bb.getLong();
        long leastSigBits = bb.getLong();
        return new UUID(mostSigBits, leastSigBits);
    }
}
