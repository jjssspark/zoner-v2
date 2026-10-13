package app.zoner.user.dto;

import jakarta.validation.constraints.NotBlank;

public record DeleteMeRequest(@NotBlank(message = "비밀번호를 입력해 주세요.") String password) {}
