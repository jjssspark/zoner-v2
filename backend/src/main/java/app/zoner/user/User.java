package app.zoner.user;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(nullable = false, unique = true)
    private String nickname;

    @Column(name = "daily_goal_seconds", nullable = false)
    private int dailyGoalSeconds = 21600;

    @Column(name = "review_weekday", nullable = false)
    private short reviewWeekday = 1;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected User() {}

    public User(String email, String passwordHash, String nickname) {
        this.email = email;
        this.passwordHash = passwordHash;
        this.nickname = nickname;
    }

    public void changeNickname(String nickname) { this.nickname = nickname; }
    public void changeDailyGoal(int seconds) { this.dailyGoalSeconds = seconds; }

    public UUID getId() { return id; }
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
    public String getNickname() { return nickname; }
    public int getDailyGoalSeconds() { return dailyGoalSeconds; }
    public short getReviewWeekday() { return reviewWeekday; }
    public Instant getCreatedAt() { return createdAt; }
}
