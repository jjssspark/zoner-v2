package app.zoner.auth.dto;

import app.zoner.user.User;

public record AuthResult(User user, String accessToken, String refreshToken) {}
