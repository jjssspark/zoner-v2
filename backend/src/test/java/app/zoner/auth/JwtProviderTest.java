package app.zoner.auth;

import app.zoner.common.api.ApiException;
import app.zoner.common.api.ErrorCode;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtProviderTest {

    private static final String SECRET = "unit-test-secret-that-is-at-least-32-bytes-long!!";

    private JwtProvider provider(Duration ttl) {
        return provider(ttl, Clock.systemUTC());
    }

    private JwtProvider provider(Duration ttl, Clock clock) {
        return new JwtProvider(new JwtProperties(SECRET, ttl, Duration.ofDays(14)), clock);
    }

    @Test
    void round_trips_user_id() {
        UUID id = UUID.randomUUID();
        String token = provider(Duration.ofMinutes(15)).createAccessToken(id);
        assertThat(provider(Duration.ofMinutes(15)).parseUserId(token)).isEqualTo(id);
    }

    @Test
    void expired_token_raises_token_expired() {
        // 20분 전 시계로 15분짜리를 발급하면 5분 전에 만료된 토큰이 된다 (디코더의 60초 오차 허용보다 크게)
        Clock past = Clock.fixed(Instant.now().minus(Duration.ofMinutes(20)), ZoneOffset.UTC);
        String token = provider(Duration.ofMinutes(15), past).createAccessToken(UUID.randomUUID());
        assertThatThrownBy(() -> provider(Duration.ofMinutes(15)).parseUserId(token))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code()).isEqualTo(ErrorCode.AUTH_TOKEN_EXPIRED);
    }

    @Test
    void token_signed_with_other_secret_raises_auth_required() {
        JwtProvider other = new JwtProvider(new JwtProperties(
                "another-secret-that-is-also-32-bytes-long!!!", Duration.ofMinutes(15), Duration.ofDays(14)),
                Clock.systemUTC());
        String token = other.createAccessToken(UUID.randomUUID());
        assertThatThrownBy(() -> provider(Duration.ofMinutes(15)).parseUserId(token))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code()).isEqualTo(ErrorCode.AUTH_REQUIRED);
    }

    @Test
    void garbage_raises_auth_required() {
        assertThatThrownBy(() -> provider(Duration.ofMinutes(15)).parseUserId("not.a.jwt"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code()).isEqualTo(ErrorCode.AUTH_REQUIRED);
    }
}
