# session-service-redis

**Module:** `session-service-redis`
**Version:** `1.0.0-RELEASE`
**Group:** `ma.s2m.payos`

## Overview

`session-service-redis` is the Redis implementation of the PayOS `ISessionStore` abstraction, providing distributed OIDC session storage so that a login performed on one node can be read back from any other node behind the same load balancer — without sticky sessions. It is a standalone, optional module discovered through SPI (`ISessionStoreFactory`) and used by `PayOSSessionStore` whenever `security.sessionStoreType` is set to `redis` in `bootstrap.json`. See [`payos/docs/architects/session-store-redis-design.md`](../payos/docs/architects/session-store-redis-design.md) for the full design rationale and [`payos-docs/configuration/oidc-configuration-guide.md` §10](../payos-docs/configuration/oidc-configuration-guide.md#10-session-configuration) for the operator-facing configuration guide.

## Key Behavior

- `RedisSessionStore implements ISessionStore`: `save` serializes `SessionData` to JSON (Jackson) and writes it with `SET <keyPrefix><sessionId> <json> EX <ttl>` (or a plain `SET` when `ttlSeconds <= 0`); `load` issues `GET` and deserializes the JSON back into `SessionData`, returning `Optional.empty()` when the key is absent; `delete` issues `DEL`; `touch` refreshes the session's lifetime with a native `EXPIRE` rather than resérializing and rewriting the payload.
- Expiry is entirely delegated to Redis' own key expiry (`EX`/`EXPIRE`) — the store does no client-side lazy-expiry bookkeeping, unlike the in-memory default backend in the kernel.
- `countActive()` is implemented as an optional diagnostic via `KEYS <keyPrefix>*`; it is intentionally not used on any per-request hot path since `KEYS` is O(N) and blocks the Redis event loop. Switch to a `SCAN`-based cursor (or a dedicated counter maintained on save/delete) if this needs to run routinely against a large keyspace.
- Connects using the [Lettuce](https://lettuce.io/) Java client (thread-safe, supports Redis Cluster natively should that be needed later), built once by `RedisSessionStoreFactory` and reused for the lifetime of the process.

Factory type exposed to runtime:

- `RedisSessionStoreFactory.supports("redis")` → `true`

## Configuration

Add a `sessionStoreType` and (optionally) a `sessionStoreRedis` block under `security` in `bootstrap.json`:

```json
{
  "security": {
    "sessionStoreType": "redis",
    "sessionStoreRedis": {
      "host": "127.0.0.1",
      "port": 6379,
      "password": "...",
      "database": 0,
      "tls": false,
      "keyPrefix": "payos:session:"
    }
  }
}
```

| Key | Default | Description |
|---|---|---|
| `security.sessionStoreType` | `memory` | Set to `redis` to activate this module; any other/absent value keeps the kernel's zero-dependency in-memory store. |
| `sessionStoreRedis.host` | `localhost` | Redis host/endpoint. |
| `sessionStoreRedis.port` | `6379` | Redis port. |
| `sessionStoreRedis.password` | *(none)* | Redis `AUTH` password; omitted entirely from the connection when blank/absent. |
| `sessionStoreRedis.database` | `0` | Redis logical database index (`SELECT`). |
| `sessionStoreRedis.tls` | `false` | Enables TLS on the Redis connection. |
| `sessionStoreRedis.keyPrefix` | `payos:session:` | Prefix applied to every session key, so the same Redis instance/cluster can later be shared with other distributed stores (idempotency, tenant quotas) without key collisions. |

`sessionStoreRedis` is read as a nested config object (not dotted flat keys), consistent with the rest of PayOS' configuration conventions. All fields are optional — any missing field falls back to its default above.

> **Requires `session-service-redis` on the runtime classpath.** This module must be a declared dependency of `payos-runtime` (or otherwise present alongside `payos-kernel`) for `sessionStoreType: "redis"` to resolve. If it is missing, `SessionStores.resolve(...)` fails explicitly at startup rather than silently falling back to the in-memory store — a misconfigured deployment is meant to be visible immediately.

## Usage

This module has no public API of its own to call directly — it is wired in transparently through the kernel's SPI resolution. Once `security.sessionStoreType` is set to `redis` and the module is on the classpath:

1. `PayOSSessionStore` resolves its backend lazily, on first access, via `SessionStores.resolve("redis", config)`.
2. `ServiceLoader` discovers `RedisSessionStoreFactory` through the `META-INF/services/ma.s2m.payos.security.oidc.session.ISessionStoreFactory` registration below.
3. The factory builds one Lettuce connection from `sessionStoreRedis` and returns a `RedisSessionStore` wrapping it; this instance is reused for the lifetime of the process (backend selection is resolved once at startup, not hot-reloaded — see the design doc for why).
4. From that point on, every OIDC session `save`/`load`/`delete`/`touch` performed by `PayOSSessionStore` is transparently backed by Redis instead of an in-process map.

To verify a deployment is actually using Redis: connect to the configured Redis instance and run `KEYS payos:session:*` (or the configured `keyPrefix`) after a login — a JSON-serialized session should be visible with a TTL (`TTL <key>`).

## Module Structure

```text
session-service-redis/
├── pom.xml
└── src/
    ├── main/
    │   ├── java/ma/s2m/payos/session/redis/
    │   │   ├── RedisSessionStore.java
    │   │   └── RedisSessionStoreFactory.java
    │   └── resources/META-INF/services/
    │       └── ma.s2m.payos.security.oidc.session.ISessionStoreFactory
    └── test/
        └── java/ma/s2m/payos/session/redis/
            └── RedisSessionStoreTest.java
```

## Dependencies

| Dependency | Scope | Purpose |
|---|---|---|
| `ma.s2m.payos:payos-kernel` | `provided` | Session store abstractions (`ISessionStore`, `ISessionStoreFactory`, `SessionData`, `SessionStoreException`) |
| `com.fasterxml.jackson.core:jackson-databind` | `provided` | JSON serialization of `SessionData` |
| `io.lettuce:lettuce-core` | compile | Redis Java client |
| `org.slf4j:slf4j-api` | `provided` | Logging API |

## Testing

`RedisSessionStoreTest` covers `RedisSessionStore` against a Mockito mock of Lettuce's `RedisCommands`, asserting the exact Redis commands emitted (`SETEX`/`SET`, `GET`, `DEL`, `EXPIRE`, `KEYS`) rather than requiring a live Redis instance — there is no Testcontainers infrastructure in this workspace yet, so no integration test exercises a real Redis server. `ISessionStoreContractTest` (in the `payos` kernel) documents the behavioral contract every `ISessionStore` implementation is expected to satisfy; wiring `RedisSessionStore` into that shared contract suite against a real or embedded Redis is tracked as follow-up work, out of scope for this iteration.

## Build

```bash
mvn -q -DskipTests compile
mvn -q test
mvn -q -DskipTests package
```
