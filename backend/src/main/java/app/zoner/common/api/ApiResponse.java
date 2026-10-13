package app.zoner.common.api;

import java.util.Map;

public record ApiResponse<T>(boolean success, T data, ErrorBody error, Map<String, Object> meta) {

    public record ErrorBody(String code, String message, Map<String, String> fields) {}

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data, null, null);
    }

    public static <T> ApiResponse<T> ok(T data, Map<String, Object> meta) {
        return new ApiResponse<>(true, data, null, meta);
    }

    public static ApiResponse<Void> error(ErrorCode code) {
        return new ApiResponse<>(false, null, new ErrorBody(code.name(), code.message(), null), null);
    }

    public static ApiResponse<Void> error(ErrorCode code, Map<String, String> fields) {
        return new ApiResponse<>(false, null, new ErrorBody(code.name(), code.message(), fields), null);
    }
}
