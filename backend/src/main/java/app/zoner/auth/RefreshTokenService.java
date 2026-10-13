package app.zoner.auth;

import app.zoner.common.api.ApiException;
import app.zoner.common.api.ErrorCode;
import app.zoner.user.User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;

@Service
public class RefreshTokenService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final RefreshTokenRepository tokens;
    private final JwtProperties props;

    public RefreshTokenService(RefreshTokenRepository tokens, JwtProperties props) {
        this.tokens = tokens;
        this.props = props;
    }

    @Transactional
    public String issue(User user) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        tokens.save(new RefreshToken(user, hash(raw), Instant.now().plus(props.refreshTtl())));
        return raw;
    }

    // 이미 폐기된 토큰이 다시 오면 누가 훔쳐서 쓰는 상황이다. 그 사람 토큰을 전부 폐기해서 양쪽 다 다시 로그인하게 한다.
    // ApiException을 던져도 revokeAllByUser는 남아야 하므로 롤백 대상에서 뺀다.
    @Transactional(noRollbackFor = ApiException.class)
    public RefreshToken findUsable(String raw) {
        RefreshToken token = tokens.findByTokenHash(hash(raw))
                .orElseThrow(() -> new ApiException(ErrorCode.AUTH_REQUIRED));
        if (token.getRevokedAt() != null) {
            tokens.revokeAllByUser(token.getUser());
            throw new ApiException(ErrorCode.AUTH_REQUIRED);
        }
        if (!token.isUsable()) {
            throw new ApiException(ErrorCode.AUTH_REQUIRED);
        }
        return token;
    }

    @Transactional
    public void revoke(String raw) {
        tokens.findByTokenHash(hash(raw)).ifPresent(RefreshToken::revoke);
    }

    // 갱신 토큰은 무작위 32바이트라 느린 해시가 필요 없다. SHA-256이면 DB가 새어도 원문을 못 만든다.
    static String hash(String raw) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
