package ma.s2m.payos.session.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lettuce.core.api.sync.RedisCommands;
import ma.s2m.payos.security.oidc.session.SessionData;
import ma.s2m.payos.security.oidc.session.SessionStoreException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies the exact Redis commands issued for each {@link ma.s2m.payos.security.oidc.session.ISessionStore}
 * operation, against a mocked {@link RedisCommands} — no live Redis instance required. Key
 * prefixing and TTL semantics are the parts most likely to regress silently, so they are the
 * focus here.
 */
@ExtendWith(MockitoExtension.class)
class RedisSessionStoreTest {

    private static final String PREFIX = "payos:session:";

    @Mock
    private RedisCommands<String, String> commands;

    private RedisSessionStore store;

    @BeforeEach
    void setUp() {
        store = new RedisSessionStore(commands, PREFIX, new ObjectMapper());
    }

    @Test
    void saveWithPositiveTtlUsesSetexWithPrefixedKey() {
        SessionData data = new SessionData();
        data.getData().put("k", "v");

        store.save("abc", data, 60);

        verify(commands).setex(eq(PREFIX + "abc"), eq(60L), anyString());
    }

    @Test
    void saveWithNonPositiveTtlUsesPlainSetWithNoExpiry() {
        store.save("abc", new SessionData(), 0);

        verify(commands).set(eq(PREFIX + "abc"), anyString());
        verify(commands, never()).setex(anyString(), anyLong(), anyString());
    }

    @Test
    void loadDeserializesTheStoredJsonBackIntoSessionData() {
        when(commands.get(PREFIX + "abc")).thenReturn("{\"data\":{\"k\":\"v\"},\"lastAccessMillis\":123}");

        Optional<SessionData> loaded = store.load("abc");

        assertThat(loaded).isPresent();
        assertThat(loaded.get().getData()).containsEntry("k", "v");
    }

    @Test
    void loadOfAMissingKeyReturnsEmpty() {
        when(commands.get(PREFIX + "missing")).thenReturn(null);

        assertThat(store.load("missing")).isEmpty();
    }

    @Test
    void deleteUsesThePrefixedKey() {
        store.delete("abc");
        verify(commands).del(PREFIX + "abc");
    }

    @Test
    void touchWithPositiveTtlRefreshesExpiryNatively() {
        store.touch("abc", 60);
        verify(commands).expire(PREFIX + "abc", 60L);
    }

    @Test
    void touchWithNonPositiveTtlDoesNothing() {
        store.touch("abc", 0);
        verify(commands, never()).expire(anyString(), anyLong());
    }

    @Test
    void countActiveScansKeysUnderThePrefix() {
        when(commands.keys(PREFIX + "*")).thenReturn(List.of(PREFIX + "a", PREFIX + "b"));
        assertThat(store.countActive()).isEqualTo(2);
    }

    @Test
    void aRedisFailurePropagatesAsSessionStoreException() {
        when(commands.get(anyString())).thenThrow(new RuntimeException("connection reset"));
        assertThatThrownBy(() -> store.load("abc")).isInstanceOf(SessionStoreException.class);
    }
}
