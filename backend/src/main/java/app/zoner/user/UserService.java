package app.zoner.user;

import app.zoner.common.api.ApiException;
import app.zoner.common.api.ErrorCode;
import app.zoner.user.dto.UserResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class UserService {

    private final UserRepository users;

    public UserService(UserRepository users) { this.users = users; }

    @Transactional(readOnly = true)
    public UserResponse me(UUID userId) {
        return UserResponse.from(load(userId));
    }

    // 토큰은 유효한데 사용자가 없으면(탈퇴 뒤 남은 토큰) AUTH_REQUIRED. 404면 "그 id가 있었다"를 알려주게 된다.
    User load(UUID userId) {
        return users.findById(userId).orElseThrow(() -> new ApiException(ErrorCode.AUTH_REQUIRED));
    }
}
