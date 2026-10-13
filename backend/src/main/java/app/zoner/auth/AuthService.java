package app.zoner.auth;

import app.zoner.auth.dto.AuthResult;
import app.zoner.auth.dto.LoginRequest;
import app.zoner.auth.dto.SignupRequest;
import app.zoner.common.api.ApiException;
import app.zoner.common.api.ErrorCode;
import app.zoner.user.User;
import app.zoner.user.UserRepository;
import app.zoner.user.UserStats;
import jakarta.persistence.EntityManager;
import org.hibernate.Hibernate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final JwtProvider jwt;
    private final RefreshTokenService refreshTokens;
    private final EntityManager em;

    public AuthService(UserRepository users, PasswordEncoder encoder, JwtProvider jwt,
                       RefreshTokenService refreshTokens, EntityManager em) {
        this.users = users;
        this.encoder = encoder;
        this.jwt = jwt;
        this.refreshTokens = refreshTokens;
        this.em = em;
    }

    @Transactional
    public AuthResult signup(SignupRequest req) {
        String email = req.normalizedEmail();
        if (users.existsByEmail(email)) throw new ApiException(ErrorCode.EMAIL_TAKEN);
        if (users.existsByNickname(req.nickname())) throw new ApiException(ErrorCode.NICKNAME_TAKEN);

        User user = users.save(new User(email, encoder.encode(req.password()), req.nickname()));
        em.persist(new UserStats(user));
        return tokensFor(user);
    }

    @Transactional
    public AuthResult login(LoginRequest req) {
        User user = users.findByEmail(req.normalizedEmail())
                .filter(u -> encoder.matches(req.password(), u.getPasswordHash()))
                .orElseThrow(() -> new ApiException(ErrorCode.AUTH_INVALID_CREDENTIALS));
        return tokensFor(user);
    }

    // findUsable의 트랜잭션은 여기에 합류한다(REQUIRED). 바깥에도 noRollbackFor가 없으면
    // 재사용 감지 때 한 revokeAllByUser가 ApiException과 함께 롤백된다.
    @Transactional(noRollbackFor = ApiException.class)
    public AuthResult refresh(String rawRefreshToken) {
        RefreshToken old = refreshTokens.findUsable(rawRefreshToken);
        old.revoke();
        // user는 LAZY 프록시다. open-in-view가 꺼져 있어 컨트롤러에서 읽으면 세션이 없으니 여기서 풀어 둔다.
        User user = Hibernate.unproxy(old.getUser(), User.class);
        return tokensFor(user);
    }

    @Transactional
    public void logout(String rawRefreshToken) {
        refreshTokens.revoke(rawRefreshToken);
    }

    AuthResult tokensFor(User user) {
        return new AuthResult(user, jwt.createAccessToken(user.getId()), refreshTokens.issue(user));
    }
}
