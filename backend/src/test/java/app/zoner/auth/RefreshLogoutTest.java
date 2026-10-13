package app.zoner.auth;

import app.zoner.AbstractIntegrationTest;
import app.zoner.AuthTestSupport;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RefreshLogoutTest extends AbstractIntegrationTest {

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
}
