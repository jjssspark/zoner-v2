# 1단계 계정 백엔드 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 백엔드 뼈대(Gradle, docker compose, Flyway, CI)를 세우고 계정 API 7개(F-101~104, F-201~202)를 테스트와 함께 완성한다.

**Architecture:** Spring Boot 4.1 단일 서버. 접근 토큰은 HS256 JWT로 서명만 검사하고, 갱신 토큰은 `refresh_tokens` 테이블에 해시로 저장해 회전·폐기한다. 응답은 전부 `{ success, data, error, meta }` 봉투. 통합 테스트는 Testcontainers의 실제 Postgres로 돌린다.

**Tech Stack:** Java 21, Spring Boot 4.1.0, Spring Security 7 + `spring-security-oauth2-jose`(Nimbus), Spring Data JPA, Flyway, PostgreSQL 16, Bucket4j, JUnit 5 + Testcontainers, Gradle Kotlin DSL

**Spec:** `docs/03-api.md` 1절·2절, `docs/02-domain-db.md` 4.1절, `docs/04-architecture.md` 2·3·10·11절, `docs/adr/001`, `docs/adr/003`

이 계획에는 프론트엔드가 없다. 프론트 뼈대와 로그인 화면은 다음 계획(`02-account-frontend`)에서 한다.

## Global Constraints

- 모든 주소는 `/api/v1`로 시작한다 (`03-api.md` 1.1)
- JSON 이름은 camelCase, 시각은 ISO 8601 UTC, 시간 길이는 초 단위 정수 (`03-api.md` 1.2)
- 성공 `{ "success": true, "data": {}, "meta": {} }`, 실패 `{ "success": false, "error": { "code", "message", "fields"? } }` (`03-api.md` 1.3)
- 접근 토큰 15분, 갱신 토큰 14일 httpOnly 쿠키, 쓸 때마다 회전 (`03-api.md` 1.5)
- 비밀번호 8자 이상. 로그인 실패는 이유와 상관없이 `AUTH_INVALID_CREDENTIALS` (`03-api.md` 2절)
- 로그인·가입은 IP당 분당 10회, 넘으면 `RATE_LIMITED` 429 (`04-architecture.md` 10절)
- 닉네임 2~12자 unique, `daily_goal_seconds` 1800~57600 기본 21600 (`02-domain-db.md` 4.1)
- 이메일은 소문자로 바꿔 저장하고 비교한다 (`02-domain-db.md` 4.1에서 "구현 때 정한다"고 한 것. 여기서 정함)
- Redis 없음. 횟수 제한은 메모리 (ADR-003)
- H2 안 씀. 통합 테스트는 Testcontainers Postgres (`04-architecture.md` 11절)
- Lombok 안 씀. DTO는 Java record
- 패키지 루트 `app.zoner`. 기능별 패키지(`auth`, `user`), 공통은 `common`
- 에러 메시지는 해요체 한국어 (`"방이 꽉 찼어요."` 식)
- Boot 4는 Jackson 3다. `ObjectMapper`는 `tools.jackson.databind` 패키지. `com.fasterxml.jackson.databind`를 import하면 빈이 없다

## Review Focus

1. 이메일 `A@Example.com ` 처럼 대문자·공백이 섞여 와도 가입되고 같은 이메일 소문자로 로그인된다 → Task 6 테스트
2. 갱신 토큰을 한 번 쓴 뒤 같은 토큰을 다시 보내면(훔친 토큰 재사용) 그 사용자의 토큰이 전부 폐기되고 401이 난다 → Task 7 테스트
3. `Authorization: Bearer 아무문자열` 처럼 깨진 토큰이 와도 500이 아니라 401 `AUTH_REQUIRED`가 난다 → Task 5 테스트
4. 요청 본문이 JSON이 아니거나 비어 있어도 500이 아니라 400 `VALIDATION_FAILED`가 난다 → Task 2 테스트
5. 탈퇴 요청의 비밀번호가 틀리면 401이 나고 사용자는 지워지지 않는다 → Task 8 테스트

---

## 파일 구조

```
backend/
├── build.gradle.kts
├── settings.gradle.kts
├── gradle/wrapper/…               gradle wrapper (Task 1에서 생성)
├── src/main/java/app/zoner/
│   ├── ZonerApplication.java
│   ├── common/
│   │   ├── api/
│   │   │   ├── ApiResponse.java              응답 봉투 record
│   │   │   ├── ErrorCode.java                에러 코드 enum (HTTP 상태 + 기본 문구)
│   │   │   ├── ApiException.java             ErrorCode를 담는 RuntimeException
│   │   │   └── GlobalExceptionHandler.java   예외 → 봉투
│   │   ├── security/
│   │   │   ├── SecurityConfig.java           필터 체인, 공개 주소
│   │   │   ├── JwtAuthFilter.java            Bearer 읽어 SecurityContext에 userId
│   │   │   ├── ApiAuthEntryPoint.java        인증 실패를 봉투로
│   │   │   └── CurrentUser.java              컨트롤러에서 userId 꺼내는 애노테이션
│   │   └── ratelimit/
│   │       └── AuthRateLimitFilter.java      /auth/login, /auth/signup IP당 분당 10회
│   ├── auth/
│   │   ├── JwtProperties.java                zoner.jwt.* 설정
│   │   ├── AuthProperties.java               zoner.auth.* 설정
│   │   ├── JwtProvider.java                  접근 토큰 발급·검증
│   │   ├── RefreshToken.java                 엔티티
│   │   ├── RefreshTokenRepository.java
│   │   ├── RefreshTokenService.java          발급·회전·폐기
│   │   ├── AuthService.java                  signup / login / refresh / logout
│   │   ├── AuthController.java               /auth/*
│   │   └── dto/  SignupRequest, LoginRequest, AuthResponse, AuthResult
│   └── user/
│       ├── User.java                         엔티티
│       ├── UserStats.java                    엔티티
│       ├── UserRepository.java
│       ├── UserService.java                  me / update / delete
│       ├── UserController.java               /users/me
│       └── dto/  UserResponse, UpdateMeRequest, DeleteMeRequest
├── src/main/resources/
│   ├── application.yml
│   ├── application-local.yml
│   └── db/migration/V1__account.sql
└── src/test/java/app/zoner/
    ├── AbstractIntegrationTest.java          Testcontainers + MockMvc 공통
    ├── AuthTestSupport.java                  가입·로그인 호출 헬퍼
    ├── HealthCheckTest.java
    ├── common/api/GlobalExceptionHandlerTest.java
    ├── auth/JwtProviderTest.java
    ├── auth/SignupTest.java
    ├── auth/LoginTest.java
    ├── auth/RefreshLogoutTest.java
    ├── auth/AuthRateLimitTest.java
    ├── user/UserRepositoryTest.java
    └── user/UserMeTest.java
deploy/docker-compose.yml                     postgres(5432), minio(9000/9001) — 로컬 개발용
.github/workflows/backend-ci.yml
```

---

### Task 1: 백엔드 뼈대와 docker compose

**Files:**
- Create: `backend/settings.gradle.kts`, `backend/build.gradle.kts`, `backend/src/main/java/app/zoner/ZonerApplication.java`, `backend/src/main/resources/application.yml`, `backend/src/main/resources/application-local.yml`, `backend/.gitignore`
- Create: `backend/src/main/java/app/zoner/common/security/SecurityConfig.java`
- Create: `deploy/docker-compose.yml`, `deploy/.env.example`
- Test: `backend/src/test/java/app/zoner/AbstractIntegrationTest.java`, `backend/src/test/java/app/zoner/HealthCheckTest.java`

**Interfaces:**
- Produces: `AbstractIntegrationTest` — 모든 통합 테스트가 상속. `protected MockMvc mvc` 제공. Postgres 컨테이너를 클래스 간 공유(static)
- Produces: `SecurityConfig` 최소본. `PasswordEncoder` 빈(BCrypt)

- [ ] **Step 1: Gradle 프로젝트 파일 작성**

`backend/settings.gradle.kts`
```kotlin
rootProject.name = "zoner"
```

`backend/build.gradle.kts`
```kotlin
plugins {
    java
    id("org.springframework.boot") version "4.1.0"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "app.zoner"
version = "0.0.1"

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

repositories { mavenCentral() }

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.springframework.security:spring-security-oauth2-jose")
    implementation("com.bucket4j:bucket4j_jdk17-core:8.14.0")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-postgresql")
}

tasks.withType<Test> { useJUnitPlatform() }
```

Boot 4.1 BOM이 Testcontainers 2.x를 관리한다. `testcontainers-postgresql`이 안 풀리면 `./gradlew dependencies --configuration testRuntimeClasspath | grep testcontainers`로 BOM이 잡은 버전을 보고, 1.x면 아티팩트를 `org.testcontainers:postgresql`로, import를 `org.testcontainers.containers.PostgreSQLContainer`로 바꾼다.

`backend/.gitignore`
```
build/
.gradle/
*.log
```

- [ ] **Step 2: Gradle wrapper 생성**

Run (로컬에 gradle이 있으면): `cd backend && gradle wrapper --gradle-version 9.1`
없으면 Spring Initializr에서 wrapper 파일만 받는다:
```bash
cd backend && curl -s https://start.spring.io/starter.tgz \
  -d type=gradle-project-kotlin -d language=java -d bootVersion=4.1.0 \
  -d javaVersion=21 -d groupId=app.zoner -d artifactId=zoner -d name=zoner \
  -d packageName=app.zoner -d dependencies=webmvc \
  | tar -xz gradlew gradlew.bat gradle
chmod +x gradlew
```
Expected: `backend/gradlew`, `backend/gradle/wrapper/gradle-wrapper.jar` 생김. Step 1에서 쓴 파일은 그대로 둔다.

- [ ] **Step 3: 애플리케이션과 설정 파일**

`backend/src/main/java/app/zoner/ZonerApplication.java`
```java
package app.zoner;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ZonerApplication {
    public static void main(String[] args) {
        SpringApplication.run(ZonerApplication.class, args);
    }
}
```

`backend/src/main/resources/application.yml`
```yaml
spring:
  application:
    name: zoner
  datasource:
    url: ${DB_URL}
    username: ${DB_USER}
    password: ${DB_PASSWORD}
  jpa:
    hibernate:
      ddl-auto: validate
    open-in-view: false
  flyway:
    locations: classpath:db/migration
  jackson:
    default-property-inclusion: non_null

server:
  port: 8080

management:
  endpoints:
    web:
      exposure:
        include: health

zoner:
  jwt:
    secret: ${JWT_SECRET}
    access-ttl: 15m
    refresh-ttl: 14d
  auth:
    cookie-secure: true
```

`backend/src/main/resources/application-local.yml`
```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/zoner
    username: zoner
    password: zoner

zoner:
  jwt:
    secret: local-dev-secret-must-be-at-least-32-bytes-long!!
  auth:
    cookie-secure: false
```

`ddl-auto: validate`는 Flyway가 만든 스키마와 엔티티가 어긋나면 기동을 막는다. 엔티티를 고치고 마이그레이션을 안 쓴 실수를 바로 잡아 준다.

- [ ] **Step 4: docker compose**

`deploy/docker-compose.yml`
```yaml
services:
  postgres:
    image: postgres:16-alpine
    environment:
      POSTGRES_DB: zoner
      POSTGRES_USER: zoner
      POSTGRES_PASSWORD: zoner
    ports: ["5432:5432"]
    volumes: ["pgdata:/var/lib/postgresql/data"]
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U zoner"]
      interval: 5s
      timeout: 3s
      retries: 10

  minio:
    image: minio/minio:latest
    command: server /data --console-address ":9001"
    environment:
      MINIO_ROOT_USER: minio
      MINIO_ROOT_PASSWORD: minio12345
    ports: ["9000:9000", "9001:9001"]
    volumes: ["miniodata:/data"]

volumes:
  pgdata:
  miniodata:
```

`deploy/.env.example`
```
DB_URL=jdbc:postgresql://postgres:5432/zoner
DB_USER=zoner
DB_PASSWORD=change-me
JWT_SECRET=change-me-to-random-32-bytes-or-more
```

MinIO는 이 단계에서 안 쓰지만 compose 파일을 두 번 안 고치려고 같이 둔다.

- [ ] **Step 5: 통합 테스트 공통 클래스와 헬스 테스트**

`backend/src/test/java/app/zoner/AbstractIntegrationTest.java`
```java
package app.zoner;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "zoner.jwt.secret=test-secret-that-is-definitely-longer-than-32-bytes",
        "zoner.auth.cookie-secure=false"
})
public abstract class AbstractIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    @Autowired
    protected MockMvc mvc;
}
```

컨테이너를 `static {}`에서 한 번 띄우고 JVM이 끝날 때 Testcontainers가 알아서 지운다. `@Container`를 쓰면 테스트 클래스마다 다시 띄워서 느리다.

`backend/src/test/java/app/zoner/HealthCheckTest.java`
```java
package app.zoner;

import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class HealthCheckTest extends AbstractIntegrationTest {

    @Test
    void health_endpoint_is_public_and_up() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }
}
```

- [ ] **Step 6: 테스트 실행해서 실패 확인**

Run: `cd backend && ./gradlew test --tests app.zoner.HealthCheckTest`
Expected: FAIL. Spring Security 기본 설정이 모든 주소를 막아서 401. (Flyway는 마이그레이션 파일이 없으면 아무것도 안 하고 통과한다)

- [ ] **Step 7: 보안 설정 최소본**

`backend/src/main/java/app/zoner/common/security/SecurityConfig.java`
```java
package app.zoner.common.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .formLogin(f -> f.disable())
            .httpBasic(b -> b.disable())
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health").permitAll()
                .anyRequest().authenticated());
        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
```

CSRF를 끄는 이유: 상태를 쿠키 세션에 안 두고 접근 토큰은 헤더로 온다. 갱신 토큰 쿠키는 `SameSite=Strict`라 다른 사이트에서 못 보낸다. Task 5에서 JWT 필터와 진입점을 여기에 더한다.

- [ ] **Step 8: 테스트 통과 확인**

Run: `cd backend && ./gradlew test --tests app.zoner.HealthCheckTest`
Expected: PASS. Docker가 떠 있어야 한다.

- [ ] **Step 9: 커밋**

```bash
git add backend deploy
git commit -m "chore: 백엔드 뼈대 — Spring Boot 4.1 Gradle, docker compose, Testcontainers 헬스 테스트"
```

---

### Task 2: 응답 봉투와 예외 처리

**Files:**
- Create: `backend/src/main/java/app/zoner/common/api/ApiResponse.java`, `ErrorCode.java`, `ApiException.java`, `GlobalExceptionHandler.java`
- Modify: `backend/src/main/java/app/zoner/common/security/SecurityConfig.java` (`permitAll`에 `/api/v1/_probe/**`)
- Test: `backend/src/test/java/app/zoner/common/api/GlobalExceptionHandlerTest.java`

**Interfaces:**
- Produces: `ApiResponse.ok(T data)`, `ApiResponse.ok(T data, Map<String,Object> meta)`, `ApiResponse.error(ErrorCode)`, `ApiResponse.error(ErrorCode, Map<String,String> fields)`
- Produces: `ErrorCode` enum. 이 계획에서 쓰는 값: `VALIDATION_FAILED(400)`, `AUTH_REQUIRED(401)`, `AUTH_TOKEN_EXPIRED(401)`, `AUTH_INVALID_CREDENTIALS(401)`, `EMAIL_TAKEN(409)`, `NICKNAME_TAKEN(409)`, `RATE_LIMITED(429)`, `INTERNAL_ERROR(500)`. `status()`, `message()` 접근자
- Produces: `new ApiException(ErrorCode)` — 어디서든 던지면 핸들러가 봉투로 바꾼다. `code()` 접근자

- [ ] **Step 1: 실패 테스트 작성**

테스트용 컨트롤러를 테스트 소스에 둔다. 운영 코드에 테스트 전용 주소를 넣지 않기 위해서다.

`backend/src/test/java/app/zoner/common/api/GlobalExceptionHandlerTest.java`
```java
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
    void empty_body_is_400_not_500() throws Exception {
        mvc.perform(post("/api/v1/_probe/validate").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `cd backend && ./gradlew test --tests app.zoner.common.api.GlobalExceptionHandlerTest`
Expected: FAIL, 컴파일 에러 (`ApiResponse`, `ApiException`, `ErrorCode` 없음)

- [ ] **Step 3: 구현**

`backend/src/main/java/app/zoner/common/api/ErrorCode.java`
```java
package app.zoner.common.api;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "입력값을 확인해 주세요."),
    AUTH_REQUIRED(HttpStatus.UNAUTHORIZED, "로그인이 필요해요."),
    AUTH_TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "로그인이 만료됐어요. 다시 로그인해 주세요."),
    AUTH_INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 맞지 않아요."),
    EMAIL_TAKEN(HttpStatus.CONFLICT, "이미 가입된 이메일이에요."),
    NICKNAME_TAKEN(HttpStatus.CONFLICT, "이미 쓰고 있는 닉네임이에요."),
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
```

`backend/src/main/java/app/zoner/common/api/ApiException.java`
```java
package app.zoner.common.api;

public class ApiException extends RuntimeException {
    private final ErrorCode code;

    public ApiException(ErrorCode code) {
        super(code.message());
        this.code = code;
    }

    public ErrorCode code() { return code; }
}
```

`backend/src/main/java/app/zoner/common/api/ApiResponse.java`
```java
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
```

`application.yml`의 `default-property-inclusion: non_null` 덕에 `data`, `error`, `meta`, `fields`가 null이면 JSON에서 빠진다.

`backend/src/main/java/app/zoner/common/api/GlobalExceptionHandler.java`
```java
package app.zoner.common.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiResponse<Void>> handleApi(ApiException e) {
        return ResponseEntity.status(e.code().status()).body(ApiResponse.error(e.code()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException e) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (FieldError fe : e.getBindingResult().getFieldErrors()) {
            fields.putIfAbsent(fe.getField(), fe.getDefaultMessage());
        }
        return ResponseEntity.status(ErrorCode.VALIDATION_FAILED.status())
                .body(ApiResponse.error(ErrorCode.VALIDATION_FAILED, fields));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiResponse<Void>> handleUnreadable(HttpMessageNotReadableException e) {
        return ResponseEntity.status(ErrorCode.VALIDATION_FAILED.status())
                .body(ApiResponse.error(ErrorCode.VALIDATION_FAILED));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiResponse<Void>> handleOther(Exception e) {
        log.error("unhandled", e);
        return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.status())
                .body(ApiResponse.error(ErrorCode.INTERNAL_ERROR));
    }
}
```

`SecurityConfig.filterChain`의 `permitAll` 줄을 이렇게 바꾼다:
```java
.requestMatchers("/actuator/health", "/api/v1/_probe/**").permitAll()
```

`_probe` 주소의 컨트롤러는 테스트 소스에만 있다. 운영에서는 404만 나므로 열어 둬도 된다.

- [ ] **Step 4: 통과 확인**

Run: `cd backend && ./gradlew test --tests app.zoner.common.api.GlobalExceptionHandlerTest`
Expected: PASS 5개

- [ ] **Step 5: 커밋**

```bash
git add backend
git commit -m "feat: 응답 봉투와 전역 예외 처리 — ApiResponse, ErrorCode, 검증 실패 fields"
```

---

### Task 3: 마이그레이션 V1과 엔티티

**Files:**
- Create: `backend/src/main/resources/db/migration/V1__account.sql`
- Create: `backend/src/main/java/app/zoner/user/User.java`, `UserStats.java`, `UserRepository.java`
- Create: `backend/src/main/java/app/zoner/auth/RefreshToken.java`, `RefreshTokenRepository.java`
- Test: `backend/src/test/java/app/zoner/user/UserRepositoryTest.java`

**Interfaces:**
- Produces: `User` 엔티티 — 생성자 `User(String email, String passwordHash, String nickname)`, `getId() UUID`, `getEmail()`, `getPasswordHash()`, `getNickname()`, `getDailyGoalSeconds() int`, `getReviewWeekday() short`, `changeNickname(String)`, `changeDailyGoal(int)`
- Produces: `UserStats` 엔티티 — `UserStats(User user)`. 계정 단계에서는 가입 때 한 줄 만들기만 한다
- Produces: `UserRepository extends JpaRepository<User, UUID>` — `Optional<User> findByEmail(String)`, `boolean existsByEmail(String)`, `boolean existsByNickname(String)`
- Produces: `RefreshToken` 엔티티 — `RefreshToken(User user, String tokenHash, Instant expiresAt)`, `getUser()`, `getExpiresAt()`, `getRevokedAt()`, `revoke()`, `isUsable()`(만료 전이고 폐기 안 됨)
- Produces: `RefreshTokenRepository extends JpaRepository<RefreshToken, UUID>` — `Optional<RefreshToken> findByTokenHash(String)`, `int revokeAllByUser(User)`, `int deleteExpiredOrRevokedBefore(Instant)`

- [ ] **Step 1: 마이그레이션 SQL**

`02-domain-db.md` 4.1절을 그대로 옮긴다. `pg_trgm`은 뒤에 `rooms` 인덱스가 쓸 것이라 여기서 미리 켠다.

`backend/src/main/resources/db/migration/V1__account.sql`
```sql
create extension if not exists pg_trgm;

create table users (
  id                 uuid primary key default gen_random_uuid(),
  email              text not null unique,
  password_hash      text not null,
  nickname           text not null unique check (char_length(nickname) between 2 and 12),
  daily_goal_seconds int  not null default 21600
                     check (daily_goal_seconds between 1800 and 57600),
  review_weekday     smallint not null default 1 check (review_weekday between 0 and 6),
  created_at         timestamptz not null default now(),
  updated_at         timestamptz not null default now()
);

create table user_stats (
  user_id                 uuid primary key references users(id) on delete cascade,
  total_net_focus_seconds bigint not null default 0,
  current_streak          int not null default 0,
  longest_streak          int not null default 0,
  last_gold_day           date,
  stamps_received         int not null default 0,
  stamps_given            int not null default 0,
  updated_at              timestamptz not null default now()
);

create table refresh_tokens (
  id         uuid primary key default gen_random_uuid(),
  user_id    uuid not null references users(id) on delete cascade,
  token_hash text not null unique,
  expires_at timestamptz not null,
  revoked_at timestamptz,
  created_at timestamptz not null default now()
);
create index idx_refresh_tokens_user on refresh_tokens (user_id);
```

- [ ] **Step 2: 실패 테스트**

`backend/src/test/java/app/zoner/user/UserRepositoryTest.java`
```java
package app.zoner.user;

import app.zoner.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserRepositoryTest extends AbstractIntegrationTest {

    @Autowired UserRepository users;

    @Test
    void saves_user_with_defaults_and_finds_by_email() {
        User saved = users.save(new User("repo1@example.com", "hash", "저장테스트"));

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getDailyGoalSeconds()).isEqualTo(21600);
        assertThat(saved.getReviewWeekday()).isEqualTo((short) 1);
        assertThat(users.findByEmail("repo1@example.com")).isPresent();
        assertThat(users.existsByNickname("저장테스트")).isTrue();
    }

    @Test
    void duplicate_email_is_rejected_by_db() {
        users.save(new User("dup@example.com", "hash", "중복일"));
        assertThatThrownBy(() -> users.saveAndFlush(new User("dup@example.com", "hash", "중복이")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
```

- [ ] **Step 3: 실패 확인**

Run: `cd backend && ./gradlew test --tests app.zoner.user.UserRepositoryTest`
Expected: FAIL, 컴파일 에러 (`User`, `UserRepository` 없음)

- [ ] **Step 4: 엔티티와 리포지토리**

`backend/src/main/java/app/zoner/user/User.java`
```java
package app.zoner.user;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(nullable = false, unique = true)
    private String nickname;

    @Column(name = "daily_goal_seconds", nullable = false)
    private int dailyGoalSeconds = 21600;

    @Column(name = "review_weekday", nullable = false)
    private short reviewWeekday = 1;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected User() {}

    public User(String email, String passwordHash, String nickname) {
        this.email = email;
        this.passwordHash = passwordHash;
        this.nickname = nickname;
    }

    public void changeNickname(String nickname) { this.nickname = nickname; }
    public void changeDailyGoal(int seconds) { this.dailyGoalSeconds = seconds; }

    public UUID getId() { return id; }
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
    public String getNickname() { return nickname; }
    public int getDailyGoalSeconds() { return dailyGoalSeconds; }
    public short getReviewWeekday() { return reviewWeekday; }
    public Instant getCreatedAt() { return createdAt; }
}
```

`backend/src/main/java/app/zoner/user/UserStats.java`
```java
package app.zoner.user;

import jakarta.persistence.*;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "user_stats")
public class UserStats {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @OneToOne(fetch = FetchType.LAZY)
    @MapsId
    @JoinColumn(name = "user_id")
    private User user;

    @Column(name = "total_net_focus_seconds", nullable = false)
    private long totalNetFocusSeconds = 0;

    @Column(name = "current_streak", nullable = false)
    private int currentStreak = 0;

    @Column(name = "longest_streak", nullable = false)
    private int longestStreak = 0;

    @Column(name = "last_gold_day")
    private LocalDate lastGoldDay;

    @Column(name = "stamps_received", nullable = false)
    private int stampsReceived = 0;

    @Column(name = "stamps_given", nullable = false)
    private int stampsGiven = 0;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected UserStats() {}

    public UserStats(User user) { this.user = user; }

    public UUID getUserId() { return userId; }
    public long getTotalNetFocusSeconds() { return totalNetFocusSeconds; }
    public int getCurrentStreak() { return currentStreak; }
    public int getLongestStreak() { return longestStreak; }
}
```

`backend/src/main/java/app/zoner/user/UserRepository.java`
```java
package app.zoner.user;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {
    Optional<User> findByEmail(String email);
    boolean existsByEmail(String email);
    boolean existsByNickname(String nickname);
}
```

`backend/src/main/java/app/zoner/auth/RefreshToken.java`
```java
package app.zoner.auth;

import app.zoner.user.User;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "refresh_tokens")
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "token_hash", nullable = false, unique = true)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected RefreshToken() {}

    public RefreshToken(User user, String tokenHash, Instant expiresAt) {
        this.user = user;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
    }

    public void revoke() {
        if (revokedAt == null) revokedAt = Instant.now();
    }

    public boolean isUsable() {
        return revokedAt == null && expiresAt.isAfter(Instant.now());
    }

    public UUID getId() { return id; }
    public User getUser() { return user; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getRevokedAt() { return revokedAt; }
}
```

`backend/src/main/java/app/zoner/auth/RefreshTokenRepository.java`
```java
package app.zoner.auth;

import app.zoner.user.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    @Modifying
    @Query("update RefreshToken t set t.revokedAt = CURRENT_TIMESTAMP where t.user = :user and t.revokedAt is null")
    int revokeAllByUser(User user);

    @Modifying
    @Query("delete from RefreshToken t where t.expiresAt < :before or (t.revokedAt is not null and t.revokedAt < :before)")
    int deleteExpiredOrRevokedBefore(Instant before);
}
```

- [ ] **Step 5: 통과 확인**

Run: `cd backend && ./gradlew test`
Expected: PASS. 전체를 돌려서 `ddl-auto: validate`가 엔티티와 SQL이 맞다고 하는지도 같이 본다. 틀리면 기동 단계에서 `Schema-validation` 에러가 난다.

- [ ] **Step 6: 커밋**

```bash
git add backend
git commit -m "feat: V1 마이그레이션(users, user_stats, refresh_tokens)과 엔티티"
```

---

### Task 4: 접근 토큰 발급·검증 (JwtProvider)

**Files:**
- Create: `backend/src/main/java/app/zoner/auth/JwtProperties.java`, `JwtProvider.java`
- Test: `backend/src/test/java/app/zoner/auth/JwtProviderTest.java`

**Interfaces:**
- Produces: `JwtProperties(String secret, Duration accessTtl, Duration refreshTtl)` record, `@ConfigurationProperties("zoner.jwt")`
- Produces: `JwtProvider` — `String createAccessToken(UUID userId)`, `UUID parseUserId(String token)`. 만료면 `ApiException(AUTH_TOKEN_EXPIRED)`, 그 외 못 읽으면 `ApiException(AUTH_REQUIRED)`

- [ ] **Step 1: 실패 테스트 (단위, Spring 없이)**

`backend/src/test/java/app/zoner/auth/JwtProviderTest.java`
```java
package app.zoner.auth;

import app.zoner.common.api.ApiException;
import app.zoner.common.api.ErrorCode;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtProviderTest {

    private static final String SECRET = "unit-test-secret-that-is-at-least-32-bytes-long!!";

    private JwtProvider provider(Duration ttl) {
        return new JwtProvider(new JwtProperties(SECRET, ttl, Duration.ofDays(14)));
    }

    @Test
    void round_trips_user_id() {
        UUID id = UUID.randomUUID();
        String token = provider(Duration.ofMinutes(15)).createAccessToken(id);
        assertThat(provider(Duration.ofMinutes(15)).parseUserId(token)).isEqualTo(id);
    }

    @Test
    void expired_token_raises_token_expired() {
        String token = provider(Duration.ofMinutes(-5)).createAccessToken(UUID.randomUUID());
        assertThatThrownBy(() -> provider(Duration.ofMinutes(15)).parseUserId(token))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code()).isEqualTo(ErrorCode.AUTH_TOKEN_EXPIRED);
    }

    @Test
    void token_signed_with_other_secret_raises_auth_required() {
        JwtProvider other = new JwtProvider(new JwtProperties(
                "another-secret-that-is-also-32-bytes-long!!!", Duration.ofMinutes(15), Duration.ofDays(14)));
        String token = other.createAccessToken(UUID.randomUUID());
        assertThatThrownBy(() -> provider(Duration.ofMinutes(15)).parseUserId(token))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code()).isEqualTo(ErrorCode.AUTH_REQUIRED);
    }

    @Test
    void garbage_raises_auth_required() {
        assertThatThrownBy(() -> provider(Duration.ofMinutes(15)).parseUserId("not.a.jwt"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code()).isEqualTo(ErrorCode.AUTH_REQUIRED);
    }
}
```

만료 테스트에서 -5분을 쓰는 이유: `NimbusJwtDecoder`는 기본 60초의 시계 오차를 봐준다. -1초면 아직 유효한 걸로 본다.

- [ ] **Step 2: 실패 확인**

Run: `cd backend && ./gradlew test --tests app.zoner.auth.JwtProviderTest`
Expected: FAIL, 컴파일 에러

- [ ] **Step 3: 구현**

`backend/src/main/java/app/zoner/auth/JwtProperties.java`
```java
package app.zoner.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("zoner.jwt")
public record JwtProperties(String secret, Duration accessTtl, Duration refreshTtl) {}
```

`backend/src/main/java/app/zoner/auth/JwtProvider.java`
```java
package app.zoner.auth;

import app.zoner.common.api.ApiException;
import app.zoner.common.api.ErrorCode;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

@Component
public class JwtProvider {

    private final JwtProperties props;
    private final JwtEncoder encoder;
    private final JwtDecoder decoder;

    public JwtProvider(JwtProperties props) {
        this.props = props;
        SecretKey key = new SecretKeySpec(props.secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        this.encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
        this.decoder = NimbusJwtDecoder.withSecretKey(key).build();
    }

    public String createAccessToken(UUID userId) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(userId.toString())
                .issuedAt(now)
                .expiresAt(now.plus(props.accessTtl()))
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    public UUID parseUserId(String token) {
        try {
            Jwt jwt = decoder.decode(token);
            return UUID.fromString(jwt.getSubject());
        } catch (JwtValidationException e) {
            boolean expired = e.getErrors().stream()
                    .anyMatch(err -> err.getDescription() != null && err.getDescription().contains("expired"));
            throw new ApiException(expired ? ErrorCode.AUTH_TOKEN_EXPIRED : ErrorCode.AUTH_REQUIRED);
        } catch (JwtException | IllegalArgumentException e) {
            throw new ApiException(ErrorCode.AUTH_REQUIRED);
        }
    }
}
```

`NimbusJwtDecoder`는 기본으로 만료를 검사하고, 만료면 `JwtValidationException`에 "Jwt expired at ..." 설명이 들어온다. 그 문자열로 두 에러 코드를 가른다. 서명이 틀리면 `BadJwtException`(`JwtException`의 하위)이라 `AUTH_REQUIRED`로 떨어진다.

- [ ] **Step 4: 통과 확인**

Run: `cd backend && ./gradlew test --tests app.zoner.auth.JwtProviderTest`
Expected: PASS 4개

- [ ] **Step 5: 커밋**

```bash
git add backend
git commit -m "feat: JwtProvider — HS256 접근 토큰 발급·검증, 만료와 위조를 다른 코드로"
```

---

### Task 5: JWT 필터, 인증 진입점, `GET /users/me`

**Files:**
- Create: `backend/src/main/java/app/zoner/common/security/JwtAuthFilter.java`, `ApiAuthEntryPoint.java`, `CurrentUser.java`
- Modify: `backend/src/main/java/app/zoner/common/security/SecurityConfig.java`
- Create: `backend/src/main/java/app/zoner/user/dto/UserResponse.java`, `backend/src/main/java/app/zoner/user/UserService.java`, `UserController.java`
- Test: `backend/src/test/java/app/zoner/user/UserMeTest.java`

**Interfaces:**
- Consumes: `JwtProvider.parseUserId`, `UserRepository`
- Produces: 컨트롤러 파라미터 `@CurrentUser UUID userId`
- Produces: `UserResponse(UUID id, String email, String nickname, int dailyGoalSeconds)` + `static UserResponse from(User)`
- Produces: `UserService.me(UUID userId) → UserResponse`, 패키지 전용 `User load(UUID)`(없으면 `AUTH_REQUIRED`)
- Produces: `SecurityConfig`의 공개 주소 목록에 `/api/v1/auth/signup`, `/login`, `/refresh` (컨트롤러는 Task 6·7에서)

- [ ] **Step 1: 실패 테스트**

`backend/src/test/java/app/zoner/user/UserMeTest.java`
```java
package app.zoner.user;

import app.zoner.AbstractIntegrationTest;
import app.zoner.auth.JwtProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class UserMeTest extends AbstractIntegrationTest {

    @Autowired UserRepository users;
    @Autowired JwtProvider jwt;

    private String tokenFor(String email, String nickname) {
        User u = users.save(new User(email, "hash", nickname));
        return jwt.createAccessToken(u.getId());
    }

    @Test
    void me_returns_my_profile() throws Exception {
        String token = tokenFor("me1@example.com", "나야나");

        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.email").value("me1@example.com"))
                .andExpect(jsonPath("$.data.nickname").value("나야나"))
                .andExpect(jsonPath("$.data.dailyGoalSeconds").value(21600))
                .andExpect(jsonPath("$.data.passwordHash").doesNotExist());
    }

    @Test
    void without_token_is_401_auth_required_in_envelope() throws Exception {
        mvc.perform(get("/api/v1/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("AUTH_REQUIRED"));
    }

    @Test
    void garbage_bearer_is_401_not_500() throws Exception {
        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer garbage"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH_REQUIRED"));
    }

    @Test
    void token_of_deleted_user_is_401() throws Exception {
        User u = users.save(new User("gone@example.com", "hash", "사라짐"));
        String token = jwt.createAccessToken(u.getId());
        users.delete(u);

        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH_REQUIRED"));
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `cd backend && ./gradlew test --tests app.zoner.user.UserMeTest`
Expected: FAIL. 첫 테스트는 401(필터 없음), 둘째는 401이지만 본문이 봉투가 아님

- [ ] **Step 3: 보안 구현**

`backend/src/main/java/app/zoner/common/security/CurrentUser.java`
```java
package app.zoner.common.security;

import org.springframework.security.core.annotation.AuthenticationPrincipal;

import java.lang.annotation.*;

@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@AuthenticationPrincipal
public @interface CurrentUser {}
```

`backend/src/main/java/app/zoner/common/security/JwtAuthFilter.java`
```java
package app.zoner.common.security;

import app.zoner.auth.JwtProvider;
import app.zoner.common.api.ApiException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    public static final String FAILURE_ATTR = "zoner.auth.failure";

    private final JwtProvider jwt;

    public JwtAuthFilter(JwtProvider jwt) { this.jwt = jwt; }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String header = req.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            try {
                UUID userId = jwt.parseUserId(header.substring(7));
                var auth = new UsernamePasswordAuthenticationToken(userId, null, List.of());
                SecurityContextHolder.getContext().setAuthentication(auth);
            } catch (ApiException e) {
                req.setAttribute(FAILURE_ATTR, e.code());
            }
        }
        chain.doFilter(req, res);
    }
}
```

토큰이 깨졌을 때 여기서 바로 응답을 쓰지 않고 이유만 요청에 붙여 둔다. 어떤 주소가 로그인을 요구하는지는 `SecurityConfig`가 정하고, 그때 진입점이 이유를 읽어 코드를 고른다.

`backend/src/main/java/app/zoner/common/security/ApiAuthEntryPoint.java`
```java
package app.zoner.common.security;

import app.zoner.common.api.ApiResponse;
import app.zoner.common.api.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;

@Component
public class ApiAuthEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper mapper;

    public ApiAuthEntryPoint(ObjectMapper mapper) { this.mapper = mapper; }

    @Override
    public void commence(HttpServletRequest req, HttpServletResponse res, AuthenticationException e) throws IOException {
        Object reason = req.getAttribute(JwtAuthFilter.FAILURE_ATTR);
        ErrorCode code = reason instanceof ErrorCode c ? c : ErrorCode.AUTH_REQUIRED;
        res.setStatus(code.status().value());
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        res.setCharacterEncoding("UTF-8");
        res.getWriter().write(mapper.writeValueAsString(ApiResponse.error(code)));
    }
}
```

`SecurityConfig` 전체를 이렇게 바꾼다:
```java
package app.zoner.common.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;
    private final ApiAuthEntryPoint entryPoint;

    public SecurityConfig(JwtAuthFilter jwtAuthFilter, ApiAuthEntryPoint entryPoint) {
        this.jwtAuthFilter = jwtAuthFilter;
        this.entryPoint = entryPoint;
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .formLogin(f -> f.disable())
            .httpBasic(b -> b.disable())
            .exceptionHandling(e -> e.authenticationEntryPoint(entryPoint))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health").permitAll()
                .requestMatchers("/api/v1/_probe/**").permitAll()
                .requestMatchers("/api/v1/auth/signup", "/api/v1/auth/login", "/api/v1/auth/refresh").permitAll()
                .anyRequest().authenticated())
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
```

- [ ] **Step 4: 사용자 조회 구현**

`backend/src/main/java/app/zoner/user/dto/UserResponse.java`
```java
package app.zoner.user.dto;

import app.zoner.user.User;

import java.util.UUID;

public record UserResponse(UUID id, String email, String nickname, int dailyGoalSeconds) {
    public static UserResponse from(User u) {
        return new UserResponse(u.getId(), u.getEmail(), u.getNickname(), u.getDailyGoalSeconds());
    }
}
```

`backend/src/main/java/app/zoner/user/UserService.java`
```java
package app.zoner.user;

import app.zoner.common.api.ApiException;
import app.zoner.common.api.ErrorCode;
import app.zoner.user.dto.UserResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class UserService {

    private final UserRepository users;

    public UserService(UserRepository users) { this.users = users; }

    @Transactional(readOnly = true)
    public UserResponse me(UUID userId) {
        return UserResponse.from(load(userId));
    }

    User load(UUID userId) {
        return users.findById(userId).orElseThrow(() -> new ApiException(ErrorCode.AUTH_REQUIRED));
    }
}
```

토큰은 유효한데 사용자가 없으면(탈퇴 뒤 남은 토큰) `AUTH_REQUIRED`다. 404를 주면 "그 id가 있었다"는 걸 알려주게 된다.

`backend/src/main/java/app/zoner/user/UserController.java`
```java
package app.zoner.user;

import app.zoner.common.api.ApiResponse;
import app.zoner.common.security.CurrentUser;
import app.zoner.user.dto.UserResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final UserService service;

    public UserController(UserService service) { this.service = service; }

    @GetMapping("/me")
    ApiResponse<UserResponse> me(@CurrentUser UUID userId) {
        return ApiResponse.ok(service.me(userId));
    }
}
```

- [ ] **Step 5: 통과 확인**

Run: `cd backend && ./gradlew test`
Expected: 전부 PASS. `GlobalExceptionHandlerTest`도 여전히 통과해야 한다

- [ ] **Step 6: 커밋**

```bash
git add backend
git commit -m "feat: JWT 인증 필터와 봉투형 401 진입점, GET /users/me"
```

---

### Task 6: 가입과 로그인

**Files:**
- Create: `backend/src/main/java/app/zoner/auth/dto/SignupRequest.java`, `LoginRequest.java`, `AuthResponse.java`, `AuthResult.java`
- Create: `backend/src/main/java/app/zoner/auth/AuthProperties.java`, `RefreshTokenService.java`, `AuthService.java`, `AuthController.java`
- Test: `backend/src/test/java/app/zoner/AuthTestSupport.java`, `backend/src/test/java/app/zoner/auth/SignupTest.java`, `LoginTest.java`

**Interfaces:**
- Consumes: `UserRepository`, `RefreshTokenRepository`, `JwtProvider`, `PasswordEncoder`, `JwtProperties.refreshTtl()`, `UserStats(User)`
- Produces: `AuthProperties(boolean cookieSecure)` `@ConfigurationProperties("zoner.auth")`
- Produces: `RefreshTokenService` — `String issue(User)`(원문 반환, DB엔 해시), `RefreshToken findUsable(String raw)`(없거나 못 쓰면 `ApiException(AUTH_REQUIRED)`. 폐기된 토큰이면 그 사용자 전부 폐기 뒤 던짐), `void revoke(String raw)`
- Produces: `AuthResult(User user, String accessToken, String refreshToken)`
- Produces: `AuthService.signup(SignupRequest) → AuthResult`, `login(LoginRequest) → AuthResult`, 패키지 전용 `tokensFor(User)`
- Produces: `AuthResponse(String accessToken, UserResponse user)`
- Produces: `AuthController` — `POST /api/v1/auth/signup` 201, `POST /api/v1/auth/login` 200. 둘 다 `Set-Cookie: refresh_token=...; HttpOnly; Path=/api/v1/auth; SameSite=Strict; Max-Age=1209600`. 패키지 전용 `respond(AuthResult, HttpStatus)`, `refreshCookie(String value, Duration maxAge)`
- Produces(테스트): `AuthTestSupport.signup(MockMvc, email, password, nickname) → MvcResult`, `login(MockMvc, email, password) → MvcResult`, `accessTokenOf(MvcResult)`, `refreshCookieOf(MvcResult) → Cookie`

- [ ] **Step 1: 테스트 헬퍼**

`backend/src/test/java/app/zoner/AuthTestSupport.java`
```java
package app.zoner;

import jakarta.servlet.http.Cookie;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

public final class AuthTestSupport {

    private static final ObjectMapper JSON = new ObjectMapper();

    private AuthTestSupport() {}

    public static MvcResult signup(MockMvc mvc, String email, String password, String nickname) throws Exception {
        return mvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s","nickname":"%s"}
                                """.formatted(email, password, nickname)))
                .andReturn();
    }

    public static MvcResult login(MockMvc mvc, String email, String password) throws Exception {
        return mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s"}
                                """.formatted(email, password)))
                .andReturn();
    }

    public static String accessTokenOf(MvcResult r) throws Exception {
        JsonNode node = JSON.readTree(r.getResponse().getContentAsString());
        return node.path("data").path("accessToken").asText();
    }

    public static Cookie refreshCookieOf(MvcResult r) {
        Cookie c = r.getResponse().getCookie("refresh_token");
        if (c == null) throw new AssertionError("refresh_token cookie missing");
        return c;
    }
}
```

- [ ] **Step 2: 실패 테스트 — 가입**

`backend/src/test/java/app/zoner/auth/SignupTest.java`
```java
package app.zoner.auth;

import app.zoner.AbstractIntegrationTest;
import app.zoner.AuthTestSupport;
import app.zoner.user.UserRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SignupTest extends AbstractIntegrationTest {

    @Autowired UserRepository users;

    @Test
    void signup_returns_201_with_access_token_user_and_refresh_cookie() throws Exception {
        MvcResult r = AuthTestSupport.signup(mvc, "s1@example.com", "password1", "가입일");

        assertThat(r.getResponse().getStatus()).isEqualTo(201);
        assertThat(AuthTestSupport.accessTokenOf(r)).isNotBlank();
        assertThat(r.getResponse().getContentAsString())
                .contains("\"nickname\":\"가입일\"")
                .contains("\"dailyGoalSeconds\":21600")
                .doesNotContain("password");

        Cookie c = AuthTestSupport.refreshCookieOf(r);
        assertThat(c.isHttpOnly()).isTrue();
        assertThat(c.getPath()).isEqualTo("/api/v1/auth");
        assertThat(c.getMaxAge()).isEqualTo(14 * 24 * 3600);
        assertThat(r.getResponse().getHeader("Set-Cookie")).contains("SameSite=Strict");
    }

    @Test
    void signup_stores_bcrypt_hash_not_plain_password() throws Exception {
        AuthTestSupport.signup(mvc, "s2@example.com", "password1", "가입이");
        var u = users.findByEmail("s2@example.com").orElseThrow();
        assertThat(u.getPasswordHash()).startsWith("$2").isNotEqualTo("password1");
    }

    @Test
    void signup_lowercases_and_trims_email() throws Exception {
        AuthTestSupport.signup(mvc, "  S3@Example.COM ", "password1", "가입삼");
        assertThat(users.findByEmail("s3@example.com")).isPresent();
    }

    @Test
    void duplicate_email_is_409_EMAIL_TAKEN_even_with_different_case() throws Exception {
        AuthTestSupport.signup(mvc, "s4@example.com", "password1", "가입사");
        MvcResult r = AuthTestSupport.signup(mvc, "S4@EXAMPLE.com", "password1", "가입사둘");
        assertThat(r.getResponse().getStatus()).isEqualTo(409);
        assertThat(r.getResponse().getContentAsString()).contains("EMAIL_TAKEN");
    }

    @Test
    void duplicate_nickname_is_409_NICKNAME_TAKEN() throws Exception {
        AuthTestSupport.signup(mvc, "s5a@example.com", "password1", "같은닉");
        MvcResult r = AuthTestSupport.signup(mvc, "s5b@example.com", "password1", "같은닉");
        assertThat(r.getResponse().getStatus()).isEqualTo(409);
        assertThat(r.getResponse().getContentAsString()).contains("NICKNAME_TAKEN");
    }

    @Test
    void validation_failures_list_each_field() throws Exception {
        mvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"not-an-email\",\"password\":\"short7\",\"nickname\":\"a\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.fields.email").exists())
                .andExpect(jsonPath("$.error.fields.password").exists())
                .andExpect(jsonPath("$.error.fields.nickname").exists());
    }

    @Test
    void password_of_exactly_8_chars_is_accepted() throws Exception {
        MvcResult r = AuthTestSupport.signup(mvc, "s7@example.com", "12345678", "여덟자");
        assertThat(r.getResponse().getStatus()).isEqualTo(201);
    }
}
```

- [ ] **Step 3: 실패 테스트 — 로그인**

`backend/src/test/java/app/zoner/auth/LoginTest.java`
```java
package app.zoner.auth;

import app.zoner.AbstractIntegrationTest;
import app.zoner.AuthTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;

class LoginTest extends AbstractIntegrationTest {

    @Test
    void login_with_correct_credentials_returns_tokens() throws Exception {
        AuthTestSupport.signup(mvc, "l1@example.com", "password1", "로그인일");
        MvcResult r = AuthTestSupport.login(mvc, "l1@example.com", "password1");

        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(AuthTestSupport.accessTokenOf(r)).isNotBlank();
        assertThat(AuthTestSupport.refreshCookieOf(r).getValue()).isNotBlank();
    }

    @Test
    void login_email_is_case_insensitive() throws Exception {
        AuthTestSupport.signup(mvc, "l2@example.com", "password1", "로그인이");
        MvcResult r = AuthTestSupport.login(mvc, "L2@Example.com", "password1");
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void wrong_password_and_unknown_email_give_identical_401() throws Exception {
        AuthTestSupport.signup(mvc, "l3@example.com", "password1", "로그인삼");

        MvcResult wrongPw = AuthTestSupport.login(mvc, "l3@example.com", "wrongwrong");
        MvcResult noUser = AuthTestSupport.login(mvc, "nobody@example.com", "password1");

        assertThat(wrongPw.getResponse().getStatus()).isEqualTo(401);
        assertThat(noUser.getResponse().getStatus()).isEqualTo(401);
        assertThat(wrongPw.getResponse().getContentAsString())
                .isEqualTo(noUser.getResponse().getContentAsString())
                .contains("AUTH_INVALID_CREDENTIALS");
    }
}
```

- [ ] **Step 4: 실패 확인**

Run: `cd backend && ./gradlew test --tests 'app.zoner.auth.*'`
Expected: FAIL, 컴파일 에러

- [ ] **Step 5: DTO**

`backend/src/main/java/app/zoner/auth/dto/SignupRequest.java`
```java
package app.zoner.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SignupRequest(
        @NotBlank(message = "이메일을 입력해 주세요.") @Email(message = "이메일 형식이 아니에요.") String email,
        @NotBlank(message = "비밀번호를 입력해 주세요.") @Size(min = 8, max = 72, message = "비밀번호는 8자 이상이어야 해요.") String password,
        @NotBlank(message = "닉네임을 입력해 주세요.") @Size(min = 2, max = 12, message = "닉네임은 2~12자여야 해요.") String nickname
) {
    public String normalizedEmail() { return email.trim().toLowerCase(); }
}
```

BCrypt는 72바이트까지만 본다. 그래서 상한 72. `@Email`은 앞뒤 공백이 있는 `"  S3@Example.COM "`도 통과시키지 않을 수 있다. 통과 안 하면 Step 2의 `signup_lowercases_and_trims_email` 입력을 `"S3@Example.COM"`으로 바꾸고 trim은 유지한다.

`backend/src/main/java/app/zoner/auth/dto/LoginRequest.java`
```java
package app.zoner.auth.dto;

import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
        @NotBlank(message = "이메일을 입력해 주세요.") String email,
        @NotBlank(message = "비밀번호를 입력해 주세요.") String password
) {
    public String normalizedEmail() { return email.trim().toLowerCase(); }
}
```

로그인 요청은 `@Email`을 안 건다. 형식 검사로 400을 주면 "그 형식은 가입 안 돼 있다"는 정보가 샌다. 그냥 찾아서 없으면 401.

`backend/src/main/java/app/zoner/auth/dto/AuthResult.java`
```java
package app.zoner.auth.dto;

import app.zoner.user.User;

public record AuthResult(User user, String accessToken, String refreshToken) {}
```

`backend/src/main/java/app/zoner/auth/dto/AuthResponse.java`
```java
package app.zoner.auth.dto;

import app.zoner.user.dto.UserResponse;

public record AuthResponse(String accessToken, UserResponse user) {
    public static AuthResponse from(AuthResult r) {
        return new AuthResponse(r.accessToken(), UserResponse.from(r.user()));
    }
}
```

- [ ] **Step 6: 갱신 토큰 서비스**

`backend/src/main/java/app/zoner/auth/AuthProperties.java`
```java
package app.zoner.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("zoner.auth")
public record AuthProperties(boolean cookieSecure) {}
```

`backend/src/main/java/app/zoner/auth/RefreshTokenService.java`
```java
package app.zoner.auth;

import app.zoner.common.api.ApiException;
import app.zoner.common.api.ErrorCode;
import app.zoner.user.User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;

@Service
public class RefreshTokenService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final RefreshTokenRepository tokens;
    private final JwtProperties props;

    public RefreshTokenService(RefreshTokenRepository tokens, JwtProperties props) {
        this.tokens = tokens;
        this.props = props;
    }

    @Transactional
    public String issue(User user) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        tokens.save(new RefreshToken(user, hash(raw), Instant.now().plus(props.refreshTtl())));
        return raw;
    }

    @Transactional
    public RefreshToken findUsable(String raw) {
        RefreshToken token = tokens.findByTokenHash(hash(raw))
                .orElseThrow(() -> new ApiException(ErrorCode.AUTH_REQUIRED));
        if (token.getRevokedAt() != null) {
            tokens.revokeAllByUser(token.getUser());
            throw new ApiException(ErrorCode.AUTH_REQUIRED);
        }
        if (!token.isUsable()) {
            throw new ApiException(ErrorCode.AUTH_REQUIRED);
        }
        return token;
    }

    @Transactional
    public void revoke(String raw) {
        tokens.findByTokenHash(hash(raw)).ifPresent(RefreshToken::revoke);
    }

    static String hash(String raw) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
```

갱신 토큰은 무작위 32바이트라 BCrypt처럼 느린 해시가 필요 없다. SHA-256이면 DB가 새어도 원문을 못 만든다.
이미 폐기된 토큰이 다시 오면 누가 훔쳐서 쓰는 상황이다. 그 사람 토큰을 전부 폐기해서 양쪽 다 다시 로그인하게 한다.
`findUsable` 안에서 예외를 던져도 `revokeAllByUser`는 남아야 한다. 그런데 `@Transactional` 메서드에서 `RuntimeException`이 나가면 롤백된다. 그래서 `ApiException`은 롤백 대상에서 빼야 한다. 다음 Step에서 처리한다.

`RefreshTokenService`의 `findUsable`에 붙은 `@Transactional`을 이렇게 바꾼다:
```java
    @Transactional(noRollbackFor = ApiException.class)
    public RefreshToken findUsable(String raw) {
```

- [ ] **Step 7: AuthService와 Controller**

`backend/src/main/java/app/zoner/auth/AuthService.java`
```java
package app.zoner.auth;

import app.zoner.auth.dto.AuthResult;
import app.zoner.auth.dto.LoginRequest;
import app.zoner.auth.dto.SignupRequest;
import app.zoner.common.api.ApiException;
import app.zoner.common.api.ErrorCode;
import app.zoner.user.User;
import app.zoner.user.UserRepository;
import app.zoner.user.UserStats;
import jakarta.persistence.EntityManager;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final JwtProvider jwt;
    private final RefreshTokenService refreshTokens;
    private final EntityManager em;

    public AuthService(UserRepository users, PasswordEncoder encoder, JwtProvider jwt,
                       RefreshTokenService refreshTokens, EntityManager em) {
        this.users = users;
        this.encoder = encoder;
        this.jwt = jwt;
        this.refreshTokens = refreshTokens;
        this.em = em;
    }

    @Transactional
    public AuthResult signup(SignupRequest req) {
        String email = req.normalizedEmail();
        if (users.existsByEmail(email)) throw new ApiException(ErrorCode.EMAIL_TAKEN);
        if (users.existsByNickname(req.nickname())) throw new ApiException(ErrorCode.NICKNAME_TAKEN);

        User user = users.save(new User(email, encoder.encode(req.password()), req.nickname()));
        em.persist(new UserStats(user));
        return tokensFor(user);
    }

    @Transactional
    public AuthResult login(LoginRequest req) {
        User user = users.findByEmail(req.normalizedEmail())
                .filter(u -> encoder.matches(req.password(), u.getPasswordHash()))
                .orElseThrow(() -> new ApiException(ErrorCode.AUTH_INVALID_CREDENTIALS));
        return tokensFor(user);
    }

    AuthResult tokensFor(User user) {
        return new AuthResult(user, jwt.createAccessToken(user.getId()), refreshTokens.issue(user));
    }
}
```

`existsByEmail` 검사와 insert 사이에 같은 이메일이 들어오면 DB 유니크 제약이 `DataIntegrityViolationException`을 던지고 500이 된다. 가입 폼을 동시에 두 번 누르는 경우라 드물고, 이 단계에서는 받아들인다. 나중에 거슬리면 핸들러에서 그 예외를 409로 바꾼다.

`backend/src/main/java/app/zoner/auth/AuthController.java`
```java
package app.zoner.auth;

import app.zoner.auth.dto.AuthResponse;
import app.zoner.auth.dto.AuthResult;
import app.zoner.auth.dto.LoginRequest;
import app.zoner.auth.dto.SignupRequest;
import app.zoner.common.api.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    static final String REFRESH_COOKIE = "refresh_token";
    static final String COOKIE_PATH = "/api/v1/auth";

    private final AuthService auth;
    private final AuthProperties authProps;
    private final JwtProperties jwtProps;

    public AuthController(AuthService auth, AuthProperties authProps, JwtProperties jwtProps) {
        this.auth = auth;
        this.authProps = authProps;
        this.jwtProps = jwtProps;
    }

    @PostMapping("/signup")
    ResponseEntity<ApiResponse<AuthResponse>> signup(@Valid @RequestBody SignupRequest req) {
        return respond(auth.signup(req), HttpStatus.CREATED);
    }

    @PostMapping("/login")
    ResponseEntity<ApiResponse<AuthResponse>> login(@Valid @RequestBody LoginRequest req) {
        return respond(auth.login(req), HttpStatus.OK);
    }

    ResponseEntity<ApiResponse<AuthResponse>> respond(AuthResult result, HttpStatus status) {
        return ResponseEntity.status(status)
                .header(HttpHeaders.SET_COOKIE, refreshCookie(result.refreshToken(), jwtProps.refreshTtl()).toString())
                .body(ApiResponse.ok(AuthResponse.from(result)));
    }

    ResponseCookie refreshCookie(String value, Duration maxAge) {
        return ResponseCookie.from(REFRESH_COOKIE, value)
                .httpOnly(true)
                .secure(authProps.cookieSecure())
                .sameSite("Strict")
                .path(COOKIE_PATH)
                .maxAge(maxAge)
                .build();
    }
}
```

- [ ] **Step 8: 통과 확인**

Run: `cd backend && ./gradlew test`
Expected: 전부 PASS

- [ ] **Step 9: 커밋**

```bash
git add backend
git commit -m "feat: 가입·로그인 — BCrypt, 접근 토큰 + httpOnly 갱신 토큰 쿠키"
```

---

### Task 7: 토큰 갱신과 로그아웃

**Files:**
- Modify: `backend/src/main/java/app/zoner/auth/AuthService.java`, `AuthController.java`, `backend/src/main/java/app/zoner/common/security/SecurityConfig.java`
- Test: `backend/src/test/java/app/zoner/auth/RefreshLogoutTest.java`

**Interfaces:**
- Consumes: `RefreshTokenService.findUsable`, `revoke`, `AuthService.tokensFor`, `AuthController.respond`, `refreshCookie`
- Produces: `AuthService.refresh(String rawRefreshToken) → AuthResult`(옛 토큰 폐기 + 새 토큰), `logout(String rawRefreshToken)`
- Produces: `POST /api/v1/auth/refresh` 200 (쿠키 필수, 없으면 401 `AUTH_REQUIRED`), `POST /api/v1/auth/logout` 204 + 쿠키 삭제

- [ ] **Step 1: 실패 테스트**

`backend/src/test/java/app/zoner/auth/RefreshLogoutTest.java`
```java
package app.zoner.auth;

import app.zoner.AbstractIntegrationTest;
import app.zoner.AuthTestSupport;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RefreshLogoutTest extends AbstractIntegrationTest {

    @Test
    void refresh_rotates_token_and_old_one_stops_working() throws Exception {
        MvcResult signup = AuthTestSupport.signup(mvc, "r1@example.com", "password1", "갱신일");
        Cookie first = AuthTestSupport.refreshCookieOf(signup);

        MvcResult refreshed = mvc.perform(post("/api/v1/auth/refresh").cookie(first))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").isString())
                .andReturn();
        Cookie second = AuthTestSupport.refreshCookieOf(refreshed);
        assertThat(second.getValue()).isNotEqualTo(first.getValue());

        mvc.perform(post("/api/v1/auth/refresh").cookie(first))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH_REQUIRED"));
    }

    @Test
    void reusing_a_rotated_token_revokes_the_whole_family() throws Exception {
        MvcResult signup = AuthTestSupport.signup(mvc, "r2@example.com", "password1", "갱신이");
        Cookie first = AuthTestSupport.refreshCookieOf(signup);
        Cookie second = AuthTestSupport.refreshCookieOf(
                mvc.perform(post("/api/v1/auth/refresh").cookie(first)).andReturn());

        mvc.perform(post("/api/v1/auth/refresh").cookie(first)).andExpect(status().isUnauthorized());

        mvc.perform(post("/api/v1/auth/refresh").cookie(second))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void refresh_without_cookie_is_401() throws Exception {
        mvc.perform(post("/api/v1/auth/refresh"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH_REQUIRED"));
    }

    @Test
    void logout_revokes_token_and_clears_cookie() throws Exception {
        MvcResult signup = AuthTestSupport.signup(mvc, "r3@example.com", "password1", "갱신삼");
        Cookie c = AuthTestSupport.refreshCookieOf(signup);

        MvcResult out = mvc.perform(post("/api/v1/auth/logout").cookie(c))
                .andExpect(status().isNoContent())
                .andReturn();
        assertThat(out.getResponse().getHeader("Set-Cookie")).contains("refresh_token=;").contains("Max-Age=0");

        mvc.perform(post("/api/v1/auth/refresh").cookie(c)).andExpect(status().isUnauthorized());
    }

    @Test
    void logout_without_cookie_is_still_204() throws Exception {
        mvc.perform(post("/api/v1/auth/logout")).andExpect(status().isNoContent());
    }
}
```

로그아웃에 쿠키가 없어도 204다. 이미 로그아웃된 사람이 또 눌렀을 때 에러를 보여줄 이유가 없다.

- [ ] **Step 2: 실패 확인**

Run: `cd backend && ./gradlew test --tests app.zoner.auth.RefreshLogoutTest`
Expected: FAIL. `/refresh`는 404(핸들러 없음), `/logout`은 401(주소가 안 열려 있음)

- [ ] **Step 3: 구현**

`AuthService`에 추가:
```java
    @Transactional(noRollbackFor = ApiException.class)
    public AuthResult refresh(String rawRefreshToken) {
        RefreshToken old = refreshTokens.findUsable(rawRefreshToken);
        old.revoke();
        return tokensFor(old.getUser());
    }

    @Transactional
    public void logout(String rawRefreshToken) {
        refreshTokens.revoke(rawRefreshToken);
    }
```

`AuthController`에 추가:
```java
    @PostMapping("/refresh")
    ResponseEntity<ApiResponse<AuthResponse>> refresh(
            @CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new ApiException(ErrorCode.AUTH_REQUIRED);
        }
        return respond(auth.refresh(refreshToken), HttpStatus.OK);
    }

    @PostMapping("/logout")
    ResponseEntity<Void> logout(@CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken) {
        if (refreshToken != null && !refreshToken.isBlank()) {
            auth.logout(refreshToken);
        }
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, refreshCookie("", Duration.ZERO).toString())
                .build();
    }
```
import 추가: `app.zoner.common.api.ApiException`, `app.zoner.common.api.ErrorCode`, `org.springframework.web.bind.annotation.CookieValue`

`SecurityConfig`의 공개 주소 줄에 `/api/v1/auth/logout` 추가:
```java
.requestMatchers("/api/v1/auth/signup", "/api/v1/auth/login", "/api/v1/auth/refresh", "/api/v1/auth/logout").permitAll()
```

로그아웃은 접근 토큰이 만료된 상태에서도 돼야 한다. 쿠키만으로 처리하므로 열어 둔다.

`refresh`에도 `noRollbackFor`를 붙이는 이유: `findUsable`의 `@Transactional`은 바깥 `refresh` 트랜잭션에 합류한다(기본 전파 REQUIRED). 안쪽에서 `ApiException`이 나가면 안쪽 규칙은 롤백 표시를 안 하지만, 같은 예외가 바깥 `refresh`를 빠져나갈 때 바깥 규칙(기본: RuntimeException이면 롤백)이 전체를 롤백한다. 그러면 `revokeAllByUser`가 취소된다. 바깥에도 같은 예외 규칙을 둬야 폐기가 남는다. Task 7의 `reusing_a_rotated_token_revokes_the_whole_family` 테스트가 이걸 잡는다.

- [ ] **Step 4: 통과 확인**

Run: `cd backend && ./gradlew test`
Expected: 전부 PASS

- [ ] **Step 5: 커밋**

```bash
git add backend
git commit -m "feat: 갱신 토큰 회전과 재사용 감지, 로그아웃"
```

---

### Task 8: 내 정보 수정과 탈퇴

**Files:**
- Create: `backend/src/main/java/app/zoner/user/dto/UpdateMeRequest.java`, `DeleteMeRequest.java`
- Modify: `backend/src/main/java/app/zoner/user/UserService.java`, `UserController.java`
- Test: `backend/src/test/java/app/zoner/user/UserMeTest.java` (테스트 추가)

**Interfaces:**
- Consumes: `UserService.load`, `PasswordEncoder`, `RefreshTokenRepository.revokeAllByUser`, `AuthProperties.cookieSecure()`
- Produces: `UserService.update(UUID, UpdateMeRequest) → UserResponse`, `delete(UUID, String password)`
- Produces: `PATCH /api/v1/users/me` `{ nickname?, dailyGoalSeconds? }` 200, `DELETE /api/v1/users/me` `{ password }` 204 + 쿠키 삭제

- [ ] **Step 1: 실패 테스트 추가**

`UserMeTest`에 아래를 덧붙인다. 기존 헬퍼 `tokenFor`는 비밀번호 해시가 가짜라 탈퇴 테스트는 실제 가입을 쓴다.

```java
    @Test
    void patch_nickname_and_goal() throws Exception {
        String token = tokenFor("p1@example.com", "바꾸기전");

        mvc.perform(patch("/api/v1/users/me")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"바꾼뒤\",\"dailyGoalSeconds\":28800}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nickname").value("바꾼뒤"))
                .andExpect(jsonPath("$.data.dailyGoalSeconds").value(28800));
    }

    @Test
    void patch_only_goal_leaves_nickname() throws Exception {
        String token = tokenFor("p2@example.com", "그대로");

        mvc.perform(patch("/api/v1/users/me")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dailyGoalSeconds\":1800}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nickname").value("그대로"))
                .andExpect(jsonPath("$.data.dailyGoalSeconds").value(1800));
    }

    @Test
    void patch_rejects_out_of_range_goal_and_taken_nickname() throws Exception {
        users.save(new User("taken@example.com", "hash", "선점닉"));
        String token = tokenFor("p3@example.com", "삼번");

        mvc.perform(patch("/api/v1/users/me")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dailyGoalSeconds\":100}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fields.dailyGoalSeconds").exists());

        mvc.perform(patch("/api/v1/users/me")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"선점닉\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("NICKNAME_TAKEN"));
    }

    @Test
    void delete_with_correct_password_removes_user_and_tokens() throws Exception {
        MvcResult signup = AuthTestSupport.signup(mvc, "d1@example.com", "password1", "탈퇴일");
        String token = AuthTestSupport.accessTokenOf(signup);

        mvc.perform(delete("/api/v1/users/me")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"password1\"}"))
                .andExpect(status().isNoContent());

        assertThat(users.findByEmail("d1@example.com")).isEmpty();
        mvc.perform(post("/api/v1/auth/refresh").cookie(AuthTestSupport.refreshCookieOf(signup)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void delete_with_wrong_password_is_401_and_user_survives() throws Exception {
        MvcResult signup = AuthTestSupport.signup(mvc, "d2@example.com", "password1", "탈퇴이");
        String token = AuthTestSupport.accessTokenOf(signup);

        mvc.perform(delete("/api/v1/users/me")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"wrongwrong\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH_INVALID_CREDENTIALS"));

        assertThat(users.findByEmail("d2@example.com")).isPresent();
    }
```
import 추가: `app.zoner.AuthTestSupport`, `org.springframework.http.MediaType`, `org.springframework.test.web.servlet.MvcResult`, `static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.{patch, delete, post}`, `static org.assertj.core.api.Assertions.assertThat`

- [ ] **Step 2: 실패 확인**

Run: `cd backend && ./gradlew test --tests app.zoner.user.UserMeTest`
Expected: 새 테스트 FAIL (405 Method Not Allowed)

- [ ] **Step 3: 구현**

`backend/src/main/java/app/zoner/user/dto/UpdateMeRequest.java`
```java
package app.zoner.user.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

public record UpdateMeRequest(
        @Size(min = 2, max = 12, message = "닉네임은 2~12자여야 해요.") String nickname,
        @Min(value = 1800, message = "목표는 30분 이상이어야 해요.")
        @Max(value = 57600, message = "목표는 16시간 이하여야 해요.") Integer dailyGoalSeconds
) {}
```

둘 다 null 허용. null이면 안 바꾼다. `@Size`, `@Min`, `@Max`는 null을 통과시킨다.

`backend/src/main/java/app/zoner/user/dto/DeleteMeRequest.java`
```java
package app.zoner.user.dto;

import jakarta.validation.constraints.NotBlank;

public record DeleteMeRequest(@NotBlank(message = "비밀번호를 입력해 주세요.") String password) {}
```

`UserService` 전체:
```java
package app.zoner.user;

import app.zoner.auth.RefreshTokenRepository;
import app.zoner.common.api.ApiException;
import app.zoner.common.api.ErrorCode;
import app.zoner.user.dto.UpdateMeRequest;
import app.zoner.user.dto.UserResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class UserService {

    private final UserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordEncoder encoder;

    public UserService(UserRepository users, RefreshTokenRepository refreshTokens, PasswordEncoder encoder) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.encoder = encoder;
    }

    @Transactional(readOnly = true)
    public UserResponse me(UUID userId) {
        return UserResponse.from(load(userId));
    }

    @Transactional
    public UserResponse update(UUID userId, UpdateMeRequest req) {
        User user = load(userId);
        if (req.nickname() != null && !req.nickname().equals(user.getNickname())) {
            if (users.existsByNickname(req.nickname())) throw new ApiException(ErrorCode.NICKNAME_TAKEN);
            user.changeNickname(req.nickname());
        }
        if (req.dailyGoalSeconds() != null) {
            user.changeDailyGoal(req.dailyGoalSeconds());
        }
        return UserResponse.from(user);
    }

    @Transactional
    public void delete(UUID userId, String password) {
        User user = load(userId);
        if (!encoder.matches(password, user.getPasswordHash())) {
            throw new ApiException(ErrorCode.AUTH_INVALID_CREDENTIALS);
        }
        refreshTokens.revokeAllByUser(user);
        users.delete(user);
    }

    User load(UUID userId) {
        return users.findById(userId).orElseThrow(() -> new ApiException(ErrorCode.AUTH_REQUIRED));
    }
}
```

"목표를 바꾸면 내일부터 적용"(`03-api.md` 2절)은 여기서 할 일이 없다. 오늘 하루 기록은 2단계에서 `daily_records`가 생길 때 그 줄에 목표를 복사해 두므로, `users.daily_goal_seconds`를 지금 바꿔도 오늘 줄은 안 변한다.

탈퇴에서 `revokeAllByUser`는 `on delete cascade`가 어차피 지우므로 없어도 된다. 그래도 둔다. 나중에 토큰 테이블의 cascade를 빼더라도 안전하고, "토큰 먼저 끊고 사용자 삭제"라는 의도가 코드에 남는다.

`UserController` 전체:
```java
package app.zoner.user;

import app.zoner.auth.AuthProperties;
import app.zoner.common.api.ApiResponse;
import app.zoner.common.security.CurrentUser;
import app.zoner.user.dto.DeleteMeRequest;
import app.zoner.user.dto.UpdateMeRequest;
import app.zoner.user.dto.UserResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final UserService service;
    private final AuthProperties authProps;

    public UserController(UserService service, AuthProperties authProps) {
        this.service = service;
        this.authProps = authProps;
    }

    @GetMapping("/me")
    ApiResponse<UserResponse> me(@CurrentUser UUID userId) {
        return ApiResponse.ok(service.me(userId));
    }

    @PatchMapping("/me")
    ApiResponse<UserResponse> update(@CurrentUser UUID userId, @Valid @RequestBody UpdateMeRequest req) {
        return ApiResponse.ok(service.update(userId, req));
    }

    @DeleteMapping("/me")
    ResponseEntity<Void> delete(@CurrentUser UUID userId, @Valid @RequestBody DeleteMeRequest req) {
        service.delete(userId, req.password());
        ResponseCookie cleared = ResponseCookie.from("refresh_token", "")
                .httpOnly(true).secure(authProps.cookieSecure()).sameSite("Strict")
                .path("/api/v1/auth").maxAge(Duration.ZERO).build();
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cleared.toString()).build();
    }
}
```

쿠키 삭제 코드가 `AuthController.refreshCookie`와 겹친다. 두 곳이라 아직 안 뽑는다. 세 곳째가 생기면 `common/security/RefreshCookies`로 뺀다.

- [ ] **Step 4: 통과 확인**

Run: `cd backend && ./gradlew test`
Expected: 전부 PASS

- [ ] **Step 5: 커밋**

```bash
git add backend
git commit -m "feat: PATCH/DELETE /users/me — 닉네임·목표 수정, 비밀번호 확인 뒤 탈퇴"
```

---

### Task 9: 로그인·가입 요청 횟수 제한

**Files:**
- Create: `backend/src/main/java/app/zoner/common/ratelimit/AuthRateLimitFilter.java`
- Modify: `backend/src/main/java/app/zoner/common/security/SecurityConfig.java`
- Test: `backend/src/test/java/app/zoner/auth/AuthRateLimitTest.java`

**Interfaces:**
- Produces: `/api/v1/auth/login`, `/api/v1/auth/signup`에 IP당 분당 10회. 넘으면 429 `RATE_LIMITED` 봉투. 다른 주소는 영향 없음

- [ ] **Step 1: 실패 테스트**

`backend/src/test/java/app/zoner/auth/AuthRateLimitTest.java`
```java
package app.zoner.auth;

import app.zoner.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthRateLimitTest extends AbstractIntegrationTest {

    private static final String BODY = "{\"email\":\"rl@example.com\",\"password\":\"whatever1\"}";

    @Test
    void eleventh_login_attempt_from_same_ip_within_a_minute_is_429() throws Exception {
        for (int i = 0; i < 10; i++) {
            mvc.perform(post("/api/v1/auth/login").with(r -> { r.setRemoteAddr("10.0.0.7"); return r; })
                            .contentType(MediaType.APPLICATION_JSON).content(BODY))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/api/v1/auth/login").with(r -> { r.setRemoteAddr("10.0.0.7"); return r; })
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("RATE_LIMITED"));
    }

    @Test
    void different_ip_has_its_own_bucket() throws Exception {
        for (int i = 0; i < 10; i++) {
            mvc.perform(post("/api/v1/auth/login").with(r -> { r.setRemoteAddr("10.0.0.8"); return r; })
                    .contentType(MediaType.APPLICATION_JSON).content(BODY));
        }
        mvc.perform(post("/api/v1/auth/login").with(r -> { r.setRemoteAddr("10.0.0.9"); return r; })
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
    }
}
```

다른 테스트들은 `remoteAddr` 기본값 `127.0.0.1`을 쓴다. 한 JVM 안에서 가입·로그인 호출이 1분에 10회를 넘으면 429가 섞여 다른 테스트가 깨진다. 지금 계획의 테스트는 `SignupTest` 8회 + `LoginTest` 5회 + `RefreshLogoutTest` 3회 + `UserMeTest` 2회라 넘는다. 그래서 Step 3에서 테스트 프로파일의 한도를 높인다.

- [ ] **Step 2: 실패 확인**

Run: `cd backend && ./gradlew test --tests app.zoner.auth.AuthRateLimitTest`
Expected: 첫 테스트 FAIL (11번째도 401)

- [ ] **Step 3: 구현**

`backend/src/main/java/app/zoner/common/ratelimit/AuthRateLimitFilter.java`
```java
package app.zoner.common.ratelimit;

import app.zoner.common.api.ApiResponse;
import app.zoner.common.api.ErrorCode;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class AuthRateLimitFilter extends OncePerRequestFilter {

    private static final Set<String> LIMITED_PATHS = Set.of("/api/v1/auth/login", "/api/v1/auth/signup");

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final ObjectMapper mapper;
    private final int limitPerMinute;

    public AuthRateLimitFilter(ObjectMapper mapper,
                               @Value("${zoner.ratelimit.auth-per-minute:10}") int limitPerMinute) {
        this.mapper = mapper;
        this.limitPerMinute = limitPerMinute;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        return !("POST".equals(req.getMethod()) && LIMITED_PATHS.contains(req.getRequestURI()));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        Bucket bucket = buckets.computeIfAbsent(clientIp(req), ip -> Bucket.builder()
                .addLimit(Bandwidth.builder().capacity(limitPerMinute)
                        .refillGreedy(limitPerMinute, Duration.ofMinutes(1)).build())
                .build());
        if (bucket.tryConsume(1)) {
            chain.doFilter(req, res);
            return;
        }
        res.setStatus(ErrorCode.RATE_LIMITED.status().value());
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        res.setCharacterEncoding("UTF-8");
        res.getWriter().write(mapper.writeValueAsString(ApiResponse.error(ErrorCode.RATE_LIMITED)));
    }

    private String clientIp(HttpServletRequest req) {
        return req.getRemoteAddr();
    }
}
```

`getRemoteAddr()`은 Caddy 뒤에서는 항상 Caddy의 주소가 된다. 배포할 때 `server.forward-headers-strategy: framework`를 켜고 Caddy가 `X-Forwarded-For`를 넘기게 해야 진짜 IP가 들어온다. 그 설정은 배포 계획에서 한다. `clientIp`를 메서드로 뺀 건 그때 한 곳만 고치려고다.

`buckets` 맵은 IP가 늘면 계속 커진다. 설계 기준(가입자 1만)에서는 문제가 안 되고, 거슬리면 `Caffeine` 캐시로 바꾼다.

`application.yml`의 `zoner:` 아래 추가:
```yaml
  ratelimit:
    auth-per-minute: 10
```

`AbstractIntegrationTest`의 `@TestPropertySource`에 한 줄 추가. `127.0.0.1`에서 오는 다른 테스트들이 걸리지 않게 하는 것이다. `AuthRateLimitTest`는 자기 IP를 따로 쓰므로 한도를 10으로 다시 내려야 한다:
```java
@TestPropertySource(properties = {
        "zoner.jwt.secret=test-secret-that-is-definitely-longer-than-32-bytes",
        "zoner.auth.cookie-secure=false",
        "zoner.ratelimit.auth-per-minute=1000"
})
```

`AuthRateLimitTest` 클래스에 붙인다 (`@Test` 메서드들 위, 클래스 선언 바로 위):
```java
@org.springframework.test.context.TestPropertySource(properties = "zoner.ratelimit.auth-per-minute=10")
class AuthRateLimitTest extends AbstractIntegrationTest {
```
하위 클래스의 `@TestPropertySource`가 상위 것을 덮어쓴다. 이 클래스는 컨텍스트가 따로 뜨므로(설정이 다르면 Spring이 새 컨텍스트를 만든다) 다른 테스트의 버킷과 섞이지 않는다.

`SecurityConfig`에 필터 등록. `JwtAuthFilter` 앞에 둔다:
```java
.addFilterBefore(authRateLimitFilter, JwtAuthFilter.class)
.addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
```
생성자에 `AuthRateLimitFilter authRateLimitFilter` 주입을 추가하고 필드로 둔다. (import `app.zoner.common.ratelimit.AuthRateLimitFilter`)

- [ ] **Step 4: 통과 확인**

Run: `cd backend && ./gradlew test`
Expected: 전부 PASS

- [ ] **Step 5: 커밋**

```bash
git add backend
git commit -m "feat: 로그인·가입 IP당 분당 10회 제한 (Bucket4j, 메모리)"
```

---

### Task 10: GitHub Actions CI

**Files:**
- Create: `.github/workflows/backend-ci.yml`

**Interfaces:**
- Produces: PR과 main 푸시마다 `./gradlew test`. Testcontainers는 GitHub 러너의 Docker를 쓴다

- [ ] **Step 1: 워크플로 작성**

`.github/workflows/backend-ci.yml`
```yaml
name: backend-ci

on:
  push:
    branches: [main]
    paths: ["backend/**", ".github/workflows/backend-ci.yml"]
  pull_request:
    paths: ["backend/**", ".github/workflows/backend-ci.yml"]

jobs:
  test:
    runs-on: ubuntu-latest
    defaults:
      run:
        working-directory: backend
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: "21"
      - uses: gradle/actions/setup-gradle@v4
      - run: ./gradlew test --no-daemon
      - uses: actions/upload-artifact@v4
        if: failure()
        with:
          name: test-reports
          path: backend/build/reports/tests/test
```

- [ ] **Step 2: 로컬에서 같은 명령 확인**

Run: `cd backend && ./gradlew test --no-daemon`
Expected: PASS

- [ ] **Step 3: 커밋하고 푸시해서 CI 확인**

```bash
git add .github
git commit -m "ci: 백엔드 테스트 워크플로"
git push
```
Expected: GitHub Actions에서 `backend-ci` 초록. `gh run watch`로 본다.

---

## 끝나면

- `docs/04-architecture.md` 12절 표에 "계정 API 7개 구현 완료 (날짜)" 한 줄
- `docs/TROUBLESHOOTING.md`: 이 계획을 실행하며 막힌 것은 전부 기록한다 (프로젝트 규칙). Boot 4.1 스타터 이름, Testcontainers 2.x 패키지, Jackson 3 패키지는 막힐 가능성이 높은 곳이다
- 다음 계획: `02-account-frontend` — Vite 뼈대, 로그인·가입 화면, 접근 토큰 메모리 보관과 자동 갱신
