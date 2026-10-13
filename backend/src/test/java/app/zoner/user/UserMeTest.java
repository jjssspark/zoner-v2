package app.zoner.user;

import app.zoner.AbstractIntegrationTest;
import app.zoner.auth.JwtProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class UserMeTest extends AbstractIntegrationTest {

    @Autowired UserRepository users;
    @Autowired JwtProvider jwt;

    private String tokenFor(String email, String nickname) {
        User u = users.save(new User(email, "hash", nickname));
        return jwt.createAccessToken(u.getId());
    }

    @Test
    void me_returns_my_profile() throws Exception {
        String token = tokenFor("me1@example.com", "나야나");

        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.email").value("me1@example.com"))
                .andExpect(jsonPath("$.data.nickname").value("나야나"))
                .andExpect(jsonPath("$.data.dailyGoalSeconds").value(21600))
                .andExpect(jsonPath("$.data.passwordHash").doesNotExist());
    }

    @Test
    void without_token_is_401_auth_required_in_envelope() throws Exception {
        mvc.perform(get("/api/v1/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("AUTH_REQUIRED"));
    }

    @Test
    void garbage_bearer_is_401_not_500() throws Exception {
        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer garbage"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH_REQUIRED"));
    }

    @Test
    void token_of_deleted_user_is_401() throws Exception {
        User u = users.save(new User("gone@example.com", "hash", "사라짐"));
        String token = jwt.createAccessToken(u.getId());
        users.delete(u);

        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH_REQUIRED"));
    }
}
