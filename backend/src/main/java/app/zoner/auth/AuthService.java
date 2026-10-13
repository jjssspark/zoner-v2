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

    AuthResult tokensFor(User user) {
        return new AuthResult(user, jwt.createAccessToken(user.getId()), refreshTokens.issue(user));
    }
}
