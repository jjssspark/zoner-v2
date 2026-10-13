package app.zoner.auth;

import app.zoner.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

import java.net.URI;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 상위 클래스의 한도(1000)를 10으로 되돌린다. 설정이 다르면 컨텍스트가 따로 떠서 다른 테스트의 버킷과 안 섞인다.
@TestPropertySource(properties = "zoner.ratelimit.auth-per-minute=10")
class AuthRateLimitTest extends AbstractIntegrationTest {

    private static final String BODY = "{\"email\":\"rl@example.com\",\"password\":\"whatever1\"}";

    @Test
    void eleventh_login_attempt_from_same_ip_within_a_minute_is_429() throws Exception {
        for (int i = 0; i < 10; i++) {
            mvc.perform(post("/api/v1/auth/login").with(r -> { r.setRemoteAddr("10.0.0.7"); return r; })
                            .contentType(MediaType.APPLICATION_JSON).content(BODY))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/api/v1/auth/login").with(r -> { r.setRemoteAddr("10.0.0.7"); return r; })
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("RATE_LIMITED"));
    }

    @Test
    void different_ip_has_its_own_bucket() throws Exception {
        for (int i = 0; i < 10; i++) {
            mvc.perform(post("/api/v1/auth/login").with(r -> { r.setRemoteAddr("10.0.0.8"); return r; })
                    .contentType(MediaType.APPLICATION_JSON).content(BODY));
        }
        mvc.perform(post("/api/v1/auth/login").with(r -> { r.setRemoteAddr("10.0.0.9"); return r; })
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void percent_encoded_path_cannot_bypass_the_limit() throws Exception {
        for (int i = 0; i < 10; i++) {
            mvc.perform(post("/api/v1/auth/login").with(r -> { r.setRemoteAddr("10.0.0.10"); return r; })
                    .contentType(MediaType.APPLICATION_JSON).content(BODY));
        }
        // /log%69n 은 디코딩하면 /login. 필터가 원문 문자열만 비교하면 여기서 제한 없이 그냥 통과해 버린다.
        // 문자열로 넘기면 MockMvc가 %를 다시 인코딩(%2569)하므로 URI 객체로 원문 그대로 보낸다.
        mvc.perform(post(URI.create("/api/v1/auth/log%69n")).with(r -> { r.setRemoteAddr("10.0.0.10"); return r; })
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isTooManyRequests());
    }
}
