package app.zoner.user;

import app.zoner.common.api.ApiResponse;
import app.zoner.common.security.CurrentUser;
import app.zoner.user.dto.UserResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final UserService service;

    public UserController(UserService service) { this.service = service; }

    @GetMapping("/me")
    ApiResponse<UserResponse> me(@CurrentUser UUID userId) {
        return ApiResponse.ok(service.me(userId));
    }
}
