package app.zoner.common.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.*;

// @Size는 문자 수를 세지만 BCrypt는 72바이트까지만 본다. 한글은 3바이트라 바이트 기준이 따로 필요하다.
@Documented
@Constraint(validatedBy = MaxUtf8BytesValidator.class)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface MaxUtf8Bytes {
    int value();
    String message() default "너무 길어요.";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
