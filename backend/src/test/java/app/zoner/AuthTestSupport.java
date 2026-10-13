package app.zoner;

import jakarta.servlet.http.Cookie;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

public final class AuthTestSupport {

    private static final ObjectMapper JSON = new ObjectMapper();

    private AuthTestSupport() {}

    public static MvcResult signup(MockMvc mvc, String email, String password, String nickname) throws Exception {
        return mvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s","nickname":"%s"}
                                """.formatted(email, password, nickname)))
                .andReturn();
    }

    public static MvcResult login(MockMvc mvc, String email, String password) throws Exception {
        return mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s"}
                                """.formatted(email, password)))
                .andReturn();
    }

    public static String accessTokenOf(MvcResult r) throws Exception {
        JsonNode node = JSON.readTree(r.getResponse().getContentAsString());
        return node.path("data").path("accessToken").asText();
    }

    public static Cookie refreshCookieOf(MvcResult r) {
        Cookie c = r.getResponse().getCookie("refresh_token");
        if (c == null) throw new AssertionError("refresh_token cookie missing");
        return c;
    }
}
