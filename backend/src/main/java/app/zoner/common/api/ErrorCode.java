package app.zoner.common.api;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "입력값을 확인해 주세요."),
    AUTH_REQUIRED(HttpStatus.UNAUTHORIZED, "로그인이 필요해요."),
    AUTH_TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "로그인이 만료됐어요. 다시 로그인해 주세요."),
    AUTH_INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 맞지 않아요."),
    EMAIL_TAKEN(HttpStatus.CONFLICT, "이미 가입된 이메일이에요."),
    NICKNAME_TAKEN(HttpStatus.CONFLICT, "이미 쓰고 있는 닉네임이에요."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "없는 주소예요."),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "요청이 너무 많아요. 잠시 뒤에 다시 해 주세요."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "문제가 생겼어요. 잠시 뒤에 다시 해 주세요.");

    private final HttpStatus status;
    private final String message;

    ErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    public HttpStatus status() { return status; }
    public String message() { return message; }
}
