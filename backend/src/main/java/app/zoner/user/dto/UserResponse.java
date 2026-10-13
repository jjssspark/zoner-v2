package app.zoner.user.dto;

import app.zoner.user.User;

import java.util.UUID;

public record UserResponse(UUID id, String email, String nickname, int dailyGoalSeconds) {
    public static UserResponse from(User u) {
        return new UserResponse(u.getId(), u.getEmail(), u.getNickname(), u.getDailyGoalSeconds());
    }
}
