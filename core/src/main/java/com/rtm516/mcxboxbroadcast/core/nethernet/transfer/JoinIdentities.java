package com.rtm516.mcxboxbroadcast.core.nethernet.transfer;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Identities from the offers we receive, keyed by connection ID
 */
public class JoinIdentities {
    private static final long MAX_AGE_NANOS = TimeUnit.MINUTES.toNanos(2);

    public record Identity(String xuid, String gamertag, long createdAt) {
    }

    private final Map<String, Identity> identities = new ConcurrentHashMap<>();

    public void put(String connectionId, String xuid, String gamertag) {
        long now = System.nanoTime();
        identities.values().removeIf(identity -> now - identity.createdAt() > MAX_AGE_NANOS);
        identities.put(connectionId, new Identity(xuid, gamertag, now));
    }

    public Identity remove(String connectionId) {
        return connectionId == null ? null : identities.remove(connectionId);
    }

    public void clear() {
        identities.clear();
    }
}
