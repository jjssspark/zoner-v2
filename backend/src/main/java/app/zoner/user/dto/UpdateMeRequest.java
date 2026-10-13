package app.zoner.user.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

// 둘 다 null 허용. null이면 안 바꾼다. @Size, @Min, @Max는 null을 통과시킨다.
public record UpdateMeRequest(
        @Size(min = 2, max = 12, message = "닉네임은 2~12자여야 해요.") String nickname,
        @Min(value = 1800, message = "목표는 30분 이상이어야 해요.")
        @Max(value = 57600, message = "목표는 16시간 이하여야 해요.") Integer dailyGoalSeconds
) {}
