package ma.s2m.payos.session.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lettuce.core.api.sync.RedisCommands;
import ma.s2m.payos.security.oidc.session.ISessionStore;
import ma.s2m.payos.security.oidc.session.SessionData;
import ma.s2m.payos.security.oidc.session.SessionStoreException;

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
        try {
            String json = objectMapper.writeValueAsString(data);
            if (ttlSeconds > 0) {
                commands.setex(key(sessionId), ttlSeconds, json);
            } else {
                commands.set(key(sessionId), json);
            }
        } catch (Exception e) {
            throw new SessionStoreException("Failed to save session " + sessionId + " to Redis", e);
        }
    }

    @Override
    public Optional<SessionData> load(String sessionId) {
        try {
            String json = commands.get(key(sessionId));
            if (json == null) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(json, SessionData.class));
        } catch (SessionStoreException e) {
            throw e;
        } catch (Exception e) {
            throw new SessionStoreException("Failed to load session " + sessionId + " from Redis", e);
        }
    }

    @Override
    public void delete(String sessionId) {
        try {
            commands.del(key(sessionId));
        } catch (Exception e) {
            throw new SessionStoreException("Failed to delete session " + sessionId + " from Redis", e);
        }
    }

    @Override
    public void touch(String sessionId, long ttlSeconds) {
        try {
            if (ttlSeconds > 0) {
                // Native TTL refresh — no re-serialization of the session payload needed, unlike
                // a backend without per-key expiry.
                commands.expire(key(sessionId), ttlSeconds);
            }
        } catch (Exception e) {
            throw new SessionStoreException("Failed to touch session " + sessionId + " in Redis", e);
        }
    }

    @Override
    public long countActive() {
        try {
            // KEYS is O(N) and blocks the Redis event loop — acceptable here since this is an
            // optional diagnostic capability, not a per-request hot path. Switch to a SCAN-based
            // cursor iteration (or a dedicated counter maintained on save/delete) if this needs to
            // run against a large keyspace routinely.
            return commands.keys(keyPrefix + "*").size();
        } catch (Exception e) {
            throw new SessionStoreException("Failed to count active sessions in Redis", e);
        }
    }

    private String key(String sessionId) {
        return keyPrefix + sessionId;
    }
}
