package ma.s2m.payos.session.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lettuce.core.api.sync.RedisCommands;
import ma.s2m.payos.events.metrics.PayOSMetrics;
import ma.s2m.payos.security.oidc.session.ISessionStore;
import ma.s2m.payos.security.oidc.session.SessionData;
import ma.s2m.payos.security.oidc.session.SessionStoreException;

import java.util.Map;
import java.util.Optional;

/**
 * Redis-backed {@link ISessionStore}: {@link SessionData} is serialized to JSON (Jackson) and
 * stored under {@code <keyPrefix><sessionId>}, relying on Redis' native key expiry ({@code EX} /
 * {@code EXPIRE}) rather than any client-side eviction bookkeeping — {@code load()} therefore
 * never needs to lazily check/evict an expired entry itself, Redis has already removed it.
 *
 * <p>See {@code payos/docs/architects/session-store-redis-design.md} for the design rationale.
 */
public class RedisSessionStore implements ISessionStore {

    private final RedisCommands<String, String> commands;
    private final String keyPrefix;
    private final ObjectMapper objectMapper;

    public RedisSessionStore(RedisCommands<String, String> commands, String keyPrefix, ObjectMapper objectMapper) {
        this.commands = commands;
        this.keyPrefix = keyPrefix;
        this.objectMapper = objectMapper;
    }

    @Override
    public void save(String sessionId, SessionData data, long ttlSeconds) {
        recordSessionOperation("save", () -> {
            String json = objectMapper.writeValueAsString(data);
            if (ttlSeconds > 0) {
                commands.setex(key(sessionId), ttlSeconds, json);
            } else {
                commands.set(key(sessionId), json);
            }
            return null;
        }, "Failed to save session " + sessionId + " to Redis");
    }

    @Override
    public Optional<SessionData> load(String sessionId) {
        return recordSessionOperation("load", () -> {
            String json = commands.get(key(sessionId));
            if (json == null) {
                PayOSMetrics.increment("session.store.misses", sessionTags("operation", "load", "backend", "redis"));
                return Optional.empty();
            }
            PayOSMetrics.increment("session.store.hits", sessionTags("operation", "load", "backend", "redis"));
            return Optional.of(objectMapper.readValue(json, SessionData.class));
        }, "Failed to load session " + sessionId + " from Redis");
    }

    @Override
    public void delete(String sessionId) {
        recordSessionOperation("delete", () -> {
            commands.del(key(sessionId));
            return null;
        }, "Failed to delete session " + sessionId + " from Redis");
    }

    @Override
    public void touch(String sessionId, long ttlSeconds) {
        recordSessionOperation("touch", () -> {
            if (ttlSeconds > 0) {
                // Native TTL refresh — no re-serialization of the session payload needed, unlike
                // a backend without per-key expiry.
                commands.expire(key(sessionId), ttlSeconds);
            }
            return null;
        }, "Failed to touch session " + sessionId + " in Redis");
    }

    @Override
    public long countActive() {
        return recordSessionOperation("count_active", () -> {
            // KEYS is O(N) and blocks the Redis event loop — acceptable here since this is an
            // optional diagnostic capability, not a per-request hot path. Switch to a SCAN-based
            // cursor iteration (or a dedicated counter maintained on save/delete) if this needs to
            // run against a large keyspace routinely.
            long activeSessions = commands.keys(keyPrefix + "*").size();
            PayOSMetrics.gauge("session.store.active", activeSessions, sessionTags("operation", "count_active", "backend", "redis"));
            return activeSessions;
        }, "Failed to count active sessions in Redis");
    }

    private String key(String sessionId) {
        return keyPrefix + sessionId;
    }

    private static <T> T recordSessionOperation(String operation, SessionOperation<T> action, String failureMessage) {
        long startedNanos = PayOSMetrics.startTimer();
        String outcome = "success";
        String exception = "none";
        try {
            return action.run();
        } catch (SessionStoreException e) {
            outcome = "error";
            exception = PayOSMetrics.safe(e.getClass().getSimpleName(), "unknown");
            PayOSMetrics.increment("session.store.errors", sessionTags("operation", operation, "backend", "redis", "exception", exception));
            throw e;
        } catch (Exception e) {
            outcome = "error";
            exception = PayOSMetrics.safe(e.getClass().getSimpleName(), "unknown");
            PayOSMetrics.increment("session.store.errors", sessionTags("operation", operation, "backend", "redis", "exception", exception));
            throw new SessionStoreException(failureMessage, e);
        } finally {
            Map<String, String> tags = sessionTags(
                    "operation", operation,
                    "backend", "redis",
                    "outcome", outcome,
                    "exception", exception);
            PayOSMetrics.increment("session.store.operations", tags);
            PayOSMetrics.duration("session.store.operation.duration", startedNanos, tags);
        }
    }

    private static Map<String, String> sessionTags(String... keyValues) {
        return PayOSMetrics.tags(keyValues);
    }

    @FunctionalInterface
    private interface SessionOperation<T> {
        T run() throws Exception;
    }
}
