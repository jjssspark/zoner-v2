package app.zoner.auth;

import app.zoner.auth.dto.AuthResponse;
import app.zoner.auth.dto.AuthResult;
import app.zoner.auth.dto.LoginRequest;
import app.zoner.auth.dto.SignupRequest;
import app.zoner.common.api.ApiException;
import app.zoner.common.api.ApiResponse;
import app.zoner.common.api.ErrorCode;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    static final String REFRESH_COOKIE = "refresh_token";
    static final String COOKIE_PATH = "/api/v1/auth";

    private final AuthService auth;
    private final AuthProperties authProps;
    private final JwtProperties jwtProps;

    public AuthController(AuthService auth, AuthProperties authProps, JwtProperties jwtProps) {
        this.auth = auth;
        this.authProps = authProps;
        this.jwtProps = jwtProps;
    }

    @PostMapping("/signup")
    ResponseEntity<ApiResponse<AuthResponse>> signup(@Valid @RequestBody SignupRequest req) {
        return respond(auth.signup(req), HttpStatus.CREATED);
    }

    @PostMapping("/login")
    ResponseEntity<ApiResponse<AuthResponse>> login(@Valid @RequestBody LoginRequest req) {
        return respond(auth.login(req), HttpStatus.OK);
    }

    @PostMapping("/refresh")
    ResponseEntity<ApiResponse<AuthResponse>> refresh(
            @CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new ApiException(ErrorCode.AUTH_REQUIRED);
        }
        return respond(auth.refresh(refreshToken), HttpStatus.OK);
    }

    // 쿠키가 없어도 204. 이미 로그아웃된 사람이 또 눌렀을 때 에러를 보여줄 이유가 없다.
    @PostMapping("/logout")
    ResponseEntity<Void> logout(@CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken) {
        if (refreshToken != null && !refreshToken.isBlank()) {
            auth.logout(refreshToken);
        }
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, refreshCookie("", Duration.ZERO).toString())
                .build();
    }

    ResponseEntity<ApiResponse<AuthResponse>> respond(AuthResult result, HttpStatus status) {
        return ResponseEntity.status(status)
                .header(HttpHeaders.SET_COOKIE, refreshCookie(result.refreshToken(), jwtProps.refreshTtl()).toString())
                .body(ApiResponse.ok(AuthResponse.from(result)));
    }

    ResponseCookie refreshCookie(String value, Duration maxAge) {
        return ResponseCookie.from(REFRESH_COOKIE, value)
                .httpOnly(true)
                .secure(authProps.cookieSecure())
                .sameSite("Strict")
                .path(COOKIE_PATH)
                .maxAge(maxAge)
                .build();
    }
}
