package app.zoner.user;

import app.zoner.auth.AuthProperties;
import app.zoner.common.api.ApiResponse;
import app.zoner.common.security.CurrentUser;
import app.zoner.user.dto.DeleteMeRequest;
import app.zoner.user.dto.UpdateMeRequest;
import app.zoner.user.dto.UserResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final UserService service;
    private final AuthProperties authProps;

    public UserController(UserService service, AuthProperties authProps) {
        this.service = service;
        this.authProps = authProps;
    }

    @GetMapping("/me")
    ApiResponse<UserResponse> me(@CurrentUser UUID userId) {
        return ApiResponse.ok(service.me(userId));
    }

    @PatchMapping("/me")
    ApiResponse<UserResponse> update(@CurrentUser UUID userId, @Valid @RequestBody UpdateMeRequest req) {
        return ApiResponse.ok(service.update(userId, req));
    }

    // 쿠키 삭제가 AuthController.refreshCookie와 겹친다. 세 곳째가 생기면 common/security로 뺀다.
    @DeleteMapping("/me")
    ResponseEntity<Void> delete(@CurrentUser UUID userId, @Valid @RequestBody DeleteMeRequest req) {
        service.delete(userId, req.password());
        ResponseCookie cleared = ResponseCookie.from("refresh_token", "")
                .httpOnly(true).secure(authProps.cookieSecure()).sameSite("Strict")
                .path("/api/v1/auth").maxAge(Duration.ZERO).build();
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cleared.toString()).build();
    }
}
