package ma.s2m.payos.session.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import ma.s2m.payos.security.oidc.session.ISessionStore;
import ma.s2m.payos.security.oidc.session.ISessionStoreFactory;
import ma.s2m.payos.security.oidc.session.SessionStoreException;

import java.util.Map;

/**
 * Builds a {@link RedisSessionStore} from the {@code security.sessionStoreRedis} config block:
 *
 * <pre>{@code
 * "security": {
 *   "sessionStoreType": "redis",
 *   "sessionStoreRedis": {
 *     "host": "127.0.0.1",
 *     "port": 6379,
 *     "password": "...",
 *     "database": 0,
 *     "tls": false,
 *     "keyPrefix": "payos:session:"
 *   }
 * }
 * }</pre>
 *
 * <p>{@code keyPrefix} defaults to {@code "payos:session:"} — namespaced so the same Redis
 * instance/cluster can later be shared with other distributed stores (idempotence, tenant
 * quotas — see {@code deployment-topologies.md} §9) without key collisions.
 */
public class RedisSessionStoreFactory implements ISessionStoreFactory {

    private static final String TYPE = "redis";
    private static final String CONFIG_BLOCK_KEY = "sessionStoreRedis";
    private static final String DEFAULT_HOST = "localhost";
    private static final int DEFAULT_PORT = 6379;
    private static final int DEFAULT_DATABASE = 0;
    private static final String DEFAULT_KEY_PREFIX = "payos:session:";

    @Override
    public boolean supports(String type) {
        return TYPE.equalsIgnoreCase(type);
    }

    @Override
    public ISessionStore create(Map<String, Object> config) {
        Map<String, Object> redisConfig = subMap(config, CONFIG_BLOCK_KEY);
        try {
            String host = stringOrDefault(redisConfig, "host", DEFAULT_HOST);
            int port = intOrDefault(redisConfig, "port", DEFAULT_PORT);
            String password = stringOrDefault(redisConfig, "password", null);
            int database = intOrDefault(redisConfig, "database", DEFAULT_DATABASE);
            boolean tls = booleanOrDefault(redisConfig, "tls", false);
            String keyPrefix = stringOrDefault(redisConfig, "keyPrefix", DEFAULT_KEY_PREFIX);

            RedisURI.Builder uriBuilder = RedisURI.Builder.redis(host, port).withDatabase(database).withSsl(tls);
            if (password != null && !password.isBlank()) {
                uriBuilder.withPassword(password.toCharArray());
            }

            RedisClient client = RedisClient.create(uriBuilder.build());
            StatefulRedisConnection<String, String> connection = client.connect();

            return new RedisSessionStore(connection.sync(), keyPrefix, new ObjectMapper());
        } catch (SessionStoreException e) {
            throw e;
        } catch (Exception e) {
            throw new SessionStoreException("Failed to create Redis session store", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> subMap(Map<String, Object> config, String key) {
        if (config == null) {
            return Map.of();
        }
        Object value = config.get(key);
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static String stringOrDefault(Map<String, Object> config, String key, String defaultValue) {
        Object value = config.get(key);
        return value != null && !value.toString().isBlank() ? value.toString() : defaultValue;
    }

    private static int intOrDefault(Map<String, Object> config, String key, int defaultValue) {
        Object value = config.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(value.toString().trim());
            } catch (NumberFormatException ignored) {
                // fall through to default
            }
        }
        return defaultValue;
    }

    private static boolean booleanOrDefault(Map<String, Object> config, String key, boolean defaultValue) {
        Object value = config.get(key);
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value != null) {
            return Boolean.parseBoolean(value.toString().trim());
        }
        return defaultValue;
    }
}
