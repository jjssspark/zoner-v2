package app.zoner.user;

import app.zoner.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserRepositoryTest extends AbstractIntegrationTest {

    @Autowired UserRepository users;

    @Test
    void saves_user_with_defaults_and_finds_by_email() {
        User saved = users.save(new User("repo1@example.com", "hash", "저장테스트"));

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getDailyGoalSeconds()).isEqualTo(21600);
        assertThat(saved.getReviewWeekday()).isEqualTo((short) 1);
        assertThat(users.findByEmail("repo1@example.com")).isPresent();
        assertThat(users.existsByNickname("저장테스트")).isTrue();
    }

    @Test
    void duplicate_email_is_rejected_by_db() {
        users.save(new User("dup@example.com", "hash", "중복일"));
        assertThatThrownBy(() -> users.saveAndFlush(new User("dup@example.com", "hash", "중복이")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
