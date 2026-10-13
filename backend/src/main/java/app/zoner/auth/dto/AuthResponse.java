package app.zoner.auth.dto;

import app.zoner.user.dto.UserResponse;

public record AuthResponse(String accessToken, UserResponse user) {
    public static AuthResponse from(AuthResult r) {
        return new AuthResponse(r.accessToken(), UserResponse.from(r.user()));
    }
}
