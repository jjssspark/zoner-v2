package app.zoner.user.dto;

import app.zoner.common.validation.MaxUtf8Bytes;
import jakarta.validation.constraints.NotBlank;

public record DeleteMeRequest(
        @NotBlank(message = "비밀번호를 입력해 주세요.") @MaxUtf8Bytes(value = 72, message = "비밀번호가 너무 길어요.") String password
) {}
