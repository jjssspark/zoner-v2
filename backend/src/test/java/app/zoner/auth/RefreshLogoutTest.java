package app.zoner.auth;

import app.zoner.AbstractIntegrationTest;
import app.zoner.AuthTestSupport;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RefreshLogoutTest extends AbstractIntegrationTest {

    @Autowired AuthService authService;
    @Autowired PlatformTransactionManager txManager;

    @Test
    void refresh_rotates_token_and_old_one_stops_working() throws Exception {
        MvcResult signup = AuthTestSupport.signup(mvc, "r1@example.com", "password1", "갱신일");
        Cookie first = AuthTestSupport.refreshCookieOf(signup);

        MvcResult refreshed = mvc.perform(post("/api/v1/auth/refresh").cookie(first))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").isString())
                .andReturn();
        Cookie second = AuthTestSupport.refreshCookieOf(refreshed);
        assertThat(second.getValue()).isNotEqualTo(first.getValue());

        mvc.perform(post("/api/v1/auth/refresh").cookie(first))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH_REQUIRED"));
    }

    @Test
    void reusing_a_rotated_token_revokes_the_whole_family() throws Exception {
        MvcResult signup = AuthTestSupport.signup(mvc, "r2@example.com", "password1", "갱신이");
        Cookie first = AuthTestSupport.refreshCookieOf(signup);
        Cookie second = AuthTestSupport.refreshCookieOf(
                mvc.perform(post("/api/v1/auth/refresh").cookie(first)).andReturn());

        mvc.perform(post("/api/v1/auth/refresh").cookie(first)).andExpect(status().isUnauthorized());

        mvc.perform(post("/api/v1/auth/refresh").cookie(second))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void refresh_without_cookie_is_401() throws Exception {
        mvc.perform(post("/api/v1/auth/refresh"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH_REQUIRED"));
    }

    @Test
    void logout_revokes_token_and_clears_cookie() throws Exception {
        MvcResult signup = AuthTestSupport.signup(mvc, "r3@example.com", "password1", "갱신삼");
        Cookie c = AuthTestSupport.refreshCookieOf(signup);

        MvcResult out = mvc.perform(post("/api/v1/auth/logout").cookie(c))
                .andExpect(status().isNoContent())
                .andReturn();
        assertThat(out.getResponse().getHeader("Set-Cookie")).contains("refresh_token=;").contains("Max-Age=0");

        mvc.perform(post("/api/v1/auth/refresh").cookie(c)).andExpect(status().isUnauthorized());
    }

    @Test
    void logout_without_cookie_is_still_204() throws Exception {
        mvc.perform(post("/api/v1/auth/logout")).andExpect(status().isNoContent());
    }

    @Test
    void concurrent_refresh_with_same_token_lets_only_one_through() throws Exception {
        MvcResult signup = AuthTestSupport.signup(mvc, "r4@example.com", "password1", "갱신사");
        Cookie c = AuthTestSupport.refreshCookieOf(signup);
        String raw = c.getValue();

        CountDownLatch aInside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // A: 회전까지 끝냈지만 아직 커밋하지 않은 채 멈춰 있는다
            Future<?> a = pool.submit(() -> new TransactionTemplate(txManager).execute(status -> {
                authService.refresh(raw);
                aInside.countDown();
                try { release.await(10, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return null;
            }));
            assertThat(aInside.await(10, TimeUnit.SECONDS)).isTrue();

            // B: 같은 토큰으로 갱신. 행 잠금이 있으면 A가 커밋할 때까지 기다려야 한다
            Future<Integer> b = pool.submit(() ->
                    mvc.perform(post("/api/v1/auth/refresh").cookie(c)).andReturn().getResponse().getStatus());
            assertThatThrownBy(() -> b.get(1500, TimeUnit.MILLISECONDS))
                    .as("잠금 없이 B가 먼저 끝나면 같은 토큰으로 유효한 토큰이 둘 생긴다")
                    .isInstanceOf(TimeoutException.class);

            release.countDown();
            a.get(10, TimeUnit.SECONDS);
            assertThat(b.get(10, TimeUnit.SECONDS)).isEqualTo(401);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }
}
