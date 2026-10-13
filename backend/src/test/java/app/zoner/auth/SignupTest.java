package app.zoner.auth;

import app.zoner.AbstractIntegrationTest;
import app.zoner.AuthTestSupport;
import app.zoner.user.UserRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SignupTest extends AbstractIntegrationTest {

    @Autowired UserRepository users;

    @Test
    void signup_returns_201_with_access_token_user_and_refresh_cookie() throws Exception {
        MvcResult r = AuthTestSupport.signup(mvc, "s1@example.com", "password1", "가입일");

        assertThat(r.getResponse().getStatus()).isEqualTo(201);
        assertThat(AuthTestSupport.accessTokenOf(r)).isNotBlank();
        assertThat(r.getResponse().getContentAsString())
                .contains("\"nickname\":\"가입일\"")
                .contains("\"dailyGoalSeconds\":21600")
                .doesNotContain("password");

        Cookie c = AuthTestSupport.refreshCookieOf(r);
        assertThat(c.isHttpOnly()).isTrue();
        assertThat(c.getPath()).isEqualTo("/api/v1/auth");
        assertThat(c.getMaxAge()).isEqualTo(14 * 24 * 3600);
        assertThat(r.getResponse().getHeader("Set-Cookie")).contains("SameSite=Strict");
    }

    @Test
    void signup_stores_bcrypt_hash_not_plain_password() throws Exception {
        AuthTestSupport.signup(mvc, "s2@example.com", "password1", "가입이");
        var u = users.findByEmail("s2@example.com").orElseThrow();
        assertThat(u.getPasswordHash()).startsWith("$2").isNotEqualTo("password1");
    }

    @Test
    void signup_lowercases_email() throws Exception {
        // 앞뒤 공백은 @Email이 400으로 거른다. 공백 제거는 프론트가 하고, 서버는 소문자 통일만 책임진다.
        MvcResult r = AuthTestSupport.signup(mvc, "S3@Example.COM", "password1", "가입삼");
        assertThat(r.getResponse().getStatus()).isEqualTo(201);
        assertThat(users.findByEmail("s3@example.com")).isPresent();
    }

    @Test
    void email_with_surrounding_spaces_is_400_not_500() throws Exception {
        MvcResult r = AuthTestSupport.signup(mvc, "  s3b@example.com ", "password1", "가입삼둘");
        assertThat(r.getResponse().getStatus()).isEqualTo(400);
        assertThat(r.getResponse().getContentAsString()).contains("VALIDATION_FAILED");
    }

    @Test
    void duplicate_email_is_409_EMAIL_TAKEN_even_with_different_case() throws Exception {
        AuthTestSupport.signup(mvc, "s4@example.com", "password1", "가입사");
        MvcResult r = AuthTestSupport.signup(mvc, "S4@EXAMPLE.com", "password1", "가입사둘");
        assertThat(r.getResponse().getStatus()).isEqualTo(409);
        assertThat(r.getResponse().getContentAsString()).contains("EMAIL_TAKEN");
    }

    @Test
    void duplicate_nickname_is_409_NICKNAME_TAKEN() throws Exception {
        AuthTestSupport.signup(mvc, "s5a@example.com", "password1", "같은닉");
        MvcResult r = AuthTestSupport.signup(mvc, "s5b@example.com", "password1", "같은닉");
        assertThat(r.getResponse().getStatus()).isEqualTo(409);
        assertThat(r.getResponse().getContentAsString()).contains("NICKNAME_TAKEN");
    }

    @Test
    void validation_failures_list_each_field() throws Exception {
        mvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"not-an-email\",\"password\":\"short7\",\"nickname\":\"a\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.fields.email").exists())
                .andExpect(jsonPath("$.error.fields.password").exists())
                .andExpect(jsonPath("$.error.fields.nickname").exists());
    }

    @Test
    void password_of_exactly_8_chars_is_accepted() throws Exception {
        MvcResult r = AuthTestSupport.signup(mvc, "s7@example.com", "12345678", "여덟자");
        assertThat(r.getResponse().getStatus()).isEqualTo(201);
    }

    @Test
    void password_over_72_bytes_is_400_with_field_not_500() throws Exception {
        // BCrypt는 72바이트까지만 받는다. 한글 25자는 75바이트라 문자 수 제한(72)으로는 못 거른다.
        MvcResult r = AuthTestSupport.signup(mvc, "s8@example.com", "가".repeat(25), "긴비번");
        assertThat(r.getResponse().getStatus()).isEqualTo(400);
        assertThat(r.getResponse().getContentAsString()).contains("\"password\"");
    }
}
