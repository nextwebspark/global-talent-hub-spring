package com.globaltalenthub;

import java.util.UUID;

/**
 * Deterministic UUIDs from readable labels for tests. Same label -> same UUID,
 * so {@code uuid("org-1")} can stand in everywhere the old {@code "org-1"}
 * String id was used (stubs, args, assertions) and still match.
 */
public final class TestIds {
    private TestIds() {}

    public static UUID uuid(String label) {
        return UUID.nameUUIDFromBytes(label.getBytes());
    }
}
