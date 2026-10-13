package app.zoner.auth;

import app.zoner.AbstractIntegrationTest;
import app.zoner.AuthTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;

class LoginTest extends AbstractIntegrationTest {

    @Test
    void login_with_correct_credentials_returns_tokens() throws Exception {
        AuthTestSupport.signup(mvc, "l1@example.com", "password1", "로그인일");
        MvcResult r = AuthTestSupport.login(mvc, "l1@example.com", "password1");

        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(AuthTestSupport.accessTokenOf(r)).isNotBlank();
        assertThat(AuthTestSupport.refreshCookieOf(r).getValue()).isNotBlank();
    }

    @Test
    void login_email_is_case_insensitive() throws Exception {
        AuthTestSupport.signup(mvc, "l2@example.com", "password1", "로그인이");
        MvcResult r = AuthTestSupport.login(mvc, "L2@Example.com", "password1");
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void wrong_password_and_unknown_email_give_identical_401() throws Exception {
        AuthTestSupport.signup(mvc, "l3@example.com", "password1", "로그인삼");

        MvcResult wrongPw = AuthTestSupport.login(mvc, "l3@example.com", "wrongwrong");
        MvcResult noUser = AuthTestSupport.login(mvc, "nobody@example.com", "password1");

        assertThat(wrongPw.getResponse().getStatus()).isEqualTo(401);
        assertThat(noUser.getResponse().getStatus()).isEqualTo(401);
        assertThat(wrongPw.getResponse().getContentAsString())
                .isEqualTo(noUser.getResponse().getContentAsString())
                .contains("AUTH_INVALID_CREDENTIALS");
    }
}
