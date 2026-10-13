package app.zoner.auth;

import app.zoner.user.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    @Modifying
    @Query("update RefreshToken t set t.revokedAt = CURRENT_TIMESTAMP where t.user = :user and t.revokedAt is null")
    int revokeAllByUser(User user);

    @Modifying
    @Query("delete from RefreshToken t where t.expiresAt < :before or (t.revokedAt is not null and t.revokedAt < :before)")
    int deleteExpiredOrRevokedBefore(Instant before);
}
