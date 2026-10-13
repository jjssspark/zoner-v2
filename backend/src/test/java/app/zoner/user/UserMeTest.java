package app.zoner.user;

import app.zoner.AbstractIntegrationTest;
import app.zoner.AuthTestSupport;
import app.zoner.auth.JwtProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

    @Test
    void patch_nickname_and_goal() throws Exception {
        String token = tokenFor("p1@example.com", "바꾸기전");

        mvc.perform(patch("/api/v1/users/me")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"바꾼뒤\",\"dailyGoalSeconds\":28800}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nickname").value("바꾼뒤"))
                .andExpect(jsonPath("$.data.dailyGoalSeconds").value(28800));
    }

    @Test
    void patch_only_goal_leaves_nickname() throws Exception {
        String token = tokenFor("p2@example.com", "그대로");

        mvc.perform(patch("/api/v1/users/me")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dailyGoalSeconds\":1800}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nickname").value("그대로"))
                .andExpect(jsonPath("$.data.dailyGoalSeconds").value(1800));
    }

    @Test
    void patch_rejects_out_of_range_goal_and_taken_nickname() throws Exception {
        users.save(new User("taken@example.com", "hash", "선점닉"));
        String token = tokenFor("p3@example.com", "삼번");

        mvc.perform(patch("/api/v1/users/me")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dailyGoalSeconds\":100}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fields.dailyGoalSeconds").exists());

        mvc.perform(patch("/api/v1/users/me")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"선점닉\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("NICKNAME_TAKEN"));
    }

    @Test
    void delete_with_correct_password_removes_user_and_tokens() throws Exception {
        MvcResult signup = AuthTestSupport.signup(mvc, "d1@example.com", "password1", "탈퇴일");
        String token = AuthTestSupport.accessTokenOf(signup);

        mvc.perform(delete("/api/v1/users/me")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"password1\"}"))
                .andExpect(status().isNoContent());

        assertThat(users.findByEmail("d1@example.com")).isEmpty();
        mvc.perform(post("/api/v1/auth/refresh").cookie(AuthTestSupport.refreshCookieOf(signup)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void delete_with_wrong_password_is_401_and_user_survives() throws Exception {
        MvcResult signup = AuthTestSupport.signup(mvc, "d2@example.com", "password1", "탈퇴이");
        String token = AuthTestSupport.accessTokenOf(signup);

        mvc.perform(delete("/api/v1/users/me")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"wrongwrong\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH_INVALID_CREDENTIALS"));

        assertThat(users.findByEmail("d2@example.com")).isPresent();
    }
}
