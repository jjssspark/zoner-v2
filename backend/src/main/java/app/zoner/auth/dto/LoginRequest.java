package app.zoner.auth.dto;

import app.zoner.common.validation.MaxUtf8Bytes;
import jakarta.validation.constraints.NotBlank;

// @Email을 안 건다. 형식 검사로 400을 주면 "그 형식은 가입 안 돼 있다"는 정보가 샌다. 찾아서 없으면 401.
public record LoginRequest(
        @NotBlank(message = "이메일을 입력해 주세요.") String email,
        @NotBlank(message = "비밀번호를 입력해 주세요.") @MaxUtf8Bytes(value = 72, message = "비밀번호가 너무 길어요.") String password
) {
    public String normalizedEmail() { return email.trim().toLowerCase(); }
}
