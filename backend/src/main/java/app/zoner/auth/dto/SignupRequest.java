package app.zoner.auth.dto;

import app.zoner.common.validation.MaxUtf8Bytes;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SignupRequest(
        @NotBlank(message = "이메일을 입력해 주세요.") @Email(message = "이메일 형식이 아니에요.") String email,
        @NotBlank(message = "비밀번호를 입력해 주세요.") @Size(min = 8, message = "비밀번호는 8자 이상이어야 해요.")
        @MaxUtf8Bytes(value = 72, message = "비밀번호가 너무 길어요.") String password,
        @NotBlank(message = "닉네임을 입력해 주세요.") @Size(min = 2, max = 12, message = "닉네임은 2~12자여야 해요.") String nickname
) {
    public String normalizedEmail() { return email.trim().toLowerCase(); }
}
