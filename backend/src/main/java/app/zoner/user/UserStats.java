package app.zoner.user;

import jakarta.persistence.*;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "user_stats")
public class UserStats {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @OneToOne(fetch = FetchType.LAZY)
    @MapsId
    @JoinColumn(name = "user_id")
    private User user;

    @Column(name = "total_net_focus_seconds", nullable = false)
    private long totalNetFocusSeconds = 0;

    @Column(name = "current_streak", nullable = false)
    private int currentStreak = 0;

    @Column(name = "longest_streak", nullable = false)
    private int longestStreak = 0;

    @Column(name = "last_gold_day")
    private LocalDate lastGoldDay;

    @Column(name = "stamps_received", nullable = false)
    private int stampsReceived = 0;

    @Column(name = "stamps_given", nullable = false)
    private int stampsGiven = 0;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected UserStats() {}

    public UserStats(User user) { this.user = user; }

    public UUID getUserId() { return userId; }
    public long getTotalNetFocusSeconds() { return totalNetFocusSeconds; }
    public int getCurrentStreak() { return currentStreak; }
    public int getLongestStreak() { return longestStreak; }
}
