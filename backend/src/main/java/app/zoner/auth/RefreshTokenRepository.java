package app.zoner.auth;

import app.zoner.user.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    // 같은 토큰으로 동시에 갱신하면 둘 다 "아직 안 폐기됨"을 보고 유효 토큰이 둘 생긴다. 행을 잠가 한 번에 하나만 지나가게 한다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<RefreshToken> findByTokenHash(String tokenHash);

    @Modifying
    @Query("update RefreshToken t set t.revokedAt = CURRENT_TIMESTAMP where t.user = :user and t.revokedAt is null")
    int revokeAllByUser(User user);

    @Modifying
    @Query("delete from RefreshToken t where t.expiresAt < :before or (t.revokedAt is not null and t.revokedAt < :before)")
    int deleteExpiredOrRevokedBefore(Instant before);
}
