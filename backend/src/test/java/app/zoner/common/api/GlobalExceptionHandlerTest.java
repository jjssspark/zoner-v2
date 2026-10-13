package app.zoner.common.api;

import app.zoner.AbstractIntegrationTest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(GlobalExceptionHandlerTest.ProbeController.class)
class GlobalExceptionHandlerTest extends AbstractIntegrationTest {

    @TestConfiguration
    @RestController
    static class ProbeController {
        record Body(@Size(min = 2, max = 12, message = "2~12자여야 해요.") String nickname) {}

        @GetMapping("/api/v1/_probe/api-exception")
        void apiException() { throw new ApiException(ErrorCode.EMAIL_TAKEN); }

        @GetMapping("/api/v1/_probe/boom")
        void boom() { throw new IllegalStateException("secret detail"); }

        @PostMapping("/api/v1/_probe/validate")
        ApiResponse<String> validate(@Valid @RequestBody Body body) { return ApiResponse.ok("fine"); }
    }

    @Test
    void api_exception_becomes_envelope_with_code_and_status() throws Exception {
        mvc.perform(get("/api/v1/_probe/api-exception"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("EMAIL_TAKEN"))
                .andExpect(jsonPath("$.error.message").isString())
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void unknown_exception_becomes_internal_error_without_leaking_detail() throws Exception {
        mvc.perform(get("/api/v1/_probe/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.error.message").value(not(containsString("secret"))));
    }

    @Test
    void validation_failure_lists_fields() throws Exception {
        mvc.perform(post("/api/v1/_probe/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"a\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.fields.nickname").value("2~12자여야 해요."));
    }

    @Test
    void malformed_json_is_400_not_500() throws Exception {
        mvc.perform(post("/api/v1/_probe/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void unknown_path_is_404_not_500() throws Exception {
        mvc.perform(get("/api/v1/_probe/no-such-path"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void wrong_method_is_405_not_500() throws Exception {
        mvc.perform(post("/api/v1/_probe/boom"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.error.code").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    void empty_body_is_400_not_500() throws Exception {
        mvc.perform(post("/api/v1/_probe/validate").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }
}
