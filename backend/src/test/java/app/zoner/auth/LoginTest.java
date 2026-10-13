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

    @Test
    void unknown_email_takes_as_long_as_wrong_password() throws Exception {
        // 없는 이메일에서 BCrypt를 건너뛰면 응답이 수십 배 빨라져 가입 여부가 시간으로 드러난다
        AuthTestSupport.signup(mvc, "l4@example.com", "password1", "로그인사");
        AuthTestSupport.login(mvc, "l4@example.com", "wrongwrong");   // 워밍업
        AuthTestSupport.login(mvc, "ghost@example.com", "password1");

        long wrongPw = Long.MAX_VALUE, unknown = Long.MAX_VALUE;
        for (int i = 0; i < 3; i++) {
            long t = System.nanoTime();
            AuthTestSupport.login(mvc, "l4@example.com", "wrongwrong");
            wrongPw = Math.min(wrongPw, System.nanoTime() - t);
            t = System.nanoTime();
            AuthTestSupport.login(mvc, "ghost@example.com", "password1");
            unknown = Math.min(unknown, System.nanoTime() - t);
        }
        assertThat(unknown).isGreaterThanOrEqualTo(wrongPw / 2);
    }

    @Test
    void overlong_password_on_login_is_400_not_500() throws Exception {
        AuthTestSupport.signup(mvc, "l5@example.com", "password1", "로그인오");
        MvcResult r = AuthTestSupport.login(mvc, "l5@example.com", "가".repeat(25));   // 75바이트
        assertThat(r.getResponse().getStatus()).isEqualTo(400);
        assertThat(r.getResponse().getContentAsString()).contains("VALIDATION_FAILED");
    }
}
