package app.zoner.user;

import app.zoner.auth.RefreshTokenRepository;
import app.zoner.common.api.ApiException;
import app.zoner.common.api.ErrorCode;
import app.zoner.user.dto.UpdateMeRequest;
import app.zoner.user.dto.UserResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class UserService {

    private final UserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordEncoder encoder;

    public UserService(UserRepository users, RefreshTokenRepository refreshTokens, PasswordEncoder encoder) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.encoder = encoder;
    }

    @Transactional(readOnly = true)
    public UserResponse me(UUID userId) {
        return UserResponse.from(load(userId));
    }

    @Transactional
    public UserResponse update(UUID userId, UpdateMeRequest req) {
        User user = load(userId);
        if (req.nickname() != null && !req.nickname().equals(user.getNickname())) {
            if (users.existsByNickname(req.nickname())) throw new ApiException(ErrorCode.NICKNAME_TAKEN);
            user.changeNickname(req.nickname());
        }
        if (req.dailyGoalSeconds() != null) {
            user.changeDailyGoal(req.dailyGoalSeconds());
        }
        return UserResponse.from(user);
    }

    // revokeAllByUser는 on delete cascade가 어차피 지우지만, "토큰 먼저 끊고 사용자 삭제"라는 의도를 코드에 남긴다.
    @Transactional
    public void delete(UUID userId, String password) {
        User user = load(userId);
        if (!encoder.matches(password, user.getPasswordHash())) {
            throw new ApiException(ErrorCode.AUTH_INVALID_CREDENTIALS);
        }
        refreshTokens.revokeAllByUser(user);
        users.delete(user);
    }

    // 토큰은 유효한데 사용자가 없으면(탈퇴 뒤 남은 토큰) AUTH_REQUIRED. 404면 "그 id가 있었다"를 알려주게 된다.
    User load(UUID userId) {
        return users.findById(userId).orElseThrow(() -> new ApiException(ErrorCode.AUTH_REQUIRED));
    }
}
