# zoner-v2 트러블슈팅 기록

> zoner-v2 전용. 막힌 건 크기와 상관없이 전부 적는다. 해결하고 검증까지 끝난 직후에 쓴다.
> v1 기록은 `../zoner/docs/TROUBLESHOOTING.md` (TS-001~021).

각 항목은 증상 / 재현 조건 / 원인 / 시도했지만 안 된 것 / 해결 / 검증 / 추후 관리 / 배운 점을 담는다.
비밀값과 실제 주소는 `<REDACTED>`로 바꾼다.

---

## 인덱스

| ID | 날짜 | 문제 | 상태 |
|---|---|---|---|
| TS-006 | 2026-10-13 | 커스텀 필터를 기준으로 `addFilterBefore`를 했더니 "does not have a registered order"로 컨텍스트 전체가 안 뜸. 기준 필터를 먼저 등록해야 했다 | 해결 |
| TS-005 | 2026-10-13 | 없는 주소나 잘못된 메서드로 요청하면 404·405가 아니라 500 `INTERNAL_ERROR`. 전역 예외 처리의 catch-all이 스프링의 404·405 예외까지 삼켰다 | 해결 |
| TS-004 | 2026-10-13 | 토큰 갱신이 500. `RefreshToken.user`가 LAZY 프록시인데 트랜잭션 밖(컨트롤러)에서 읽어 `LazyInitializationException` | 해결 |
| TS-003 | 2026-10-13 | 만료된 JWT를 만들려고 TTL을 음수로 줬더니 Nimbus 인코더가 "expiresAt must be after issuedAt"으로 거부. 테스트가 만료를 못 만들었다 | 해결 |
| TS-002 | 2026-10-13 | 보안 설정 없이도 `/actuator/health` 테스트가 통과해 RED 단계가 안 빨갰다. Boot가 health를 기본으로 열어 둔다 | 해결(기록) |
| TS-001 | 2026-10-13 | Boot 4.1에서 `AutoConfigureMockMvc` import가 컴파일 에러. 패키지가 `boot.webmvc.test.autoconfigure`로 옮겨졌다 | 해결 |

---

## 기록

### TS-001: Boot 4.1에서 `AutoConfigureMockMvc` 패키지 이동

날짜: 2026-10-13
상태: 해결

증상
```
AbstractIntegrationTest.java:4: error: package org.springframework.boot.test.autoconfigure.web.servlet does not exist
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
```

재현 조건
Spring Boot 4.1.0 + `spring-boot-starter-webmvc-test`. Boot 3 시절 import 그대로 씀.

원인
Boot 4에서 테스트 자동 설정이 모듈별로 쪼개지면서 `AutoConfigureMockMvc`가 `spring-boot-webmvc-test` jar의
`org.springframework.boot.webmvc.test.autoconfigure` 패키지로 옮겨졌다. 계획 문서가 Boot 3 기준으로 적혀 있었다.

시도했지만 안 된 것
없음. jar 안을 `unzip -l`로 뒤져서 바로 찾았다.

해결
import를 `org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc`로 바꿨다.
같이 확인한 것: `@ServiceConnection`은 `org.springframework.boot.testcontainers.service.connection` 그대로,
Testcontainers 2.x의 `PostgreSQLContainer`는 `org.testcontainers.postgresql` 패키지.

검증
`./gradlew test --tests app.zoner.HealthCheckTest` 통과.

추후 관리
Boot 4 계열에서 import가 안 잡히면 먼저 `~/.gradle/caches`의 jar를 `unzip -l | grep 클래스명`으로 찾는다. 검색보다 빠르다.

배운 점
메이저 버전이 바뀌면 "스타터 이름"만 바뀌는 게 아니라 테스트 애노테이션 패키지도 같이 움직인다. 계획에 적힌 import를 그대로 믿지 말 것.

### TS-002: 보안 설정 없이도 health 테스트가 통과해 RED가 안 빨갰다

날짜: 2026-10-13
상태: 해결(기록)

증상
Task 1에서 "SecurityConfig를 만들기 전에는 401로 실패해야 한다"고 계획했는데, 테스트가 바로 통과했다.

재현 조건
`spring-boot-starter-security` + `spring-boot-starter-actuator`, 사용자 정의 `SecurityFilterChain` 없음.

원인
Boot의 `ManagementWebSecurityAutoConfiguration`이 사용자 보안 설정이 없을 때 `/actuator/health`를 기본으로 `permitAll` 한다.
그래서 "모든 주소가 막힌다"는 계획의 전제가 health에는 안 맞았다.

시도했지만 안 된 것
없음. 테스트 결과 XML에서 `tests="1" failures="0"`을 보고 바로 원인을 찾았다.

해결
테스트는 그대로 두고(Postgres 컨테이너·Flyway·MockMvc가 같이 뜨는지 확인하는 역할은 그대로다), `SecurityConfig`의 동작은
Task 5의 401 테스트 4개가 대신 증명하도록 했다. 원장(ledger)에 판정으로 남겼다.

검증
Task 5 `UserMeTest` 4개가 SecurityConfig를 지우면 403으로 실패하는 걸 RED 단계에서 봤다.

추후 관리
없음.

배운 점
"통과하면 안 되는데 통과한" 테스트는 코드가 아니라 테스트의 전제를 의심한다. 프레임워크 기본값이 이미 그 동작을 주고 있을 수 있다.

### TS-003: Nimbus 인코더가 만료 시각이 발급 시각보다 앞서는 토큰을 거부

날짜: 2026-10-13
상태: 해결

증상
```
java.lang.IllegalArgumentException: expiresAt must be after issuedAt
	at org.springframework.security.oauth2.core.AbstractOAuth2Token.<init>(AbstractOAuth2Token.java:63)
	at org.springframework.security.oauth2.jwt.NimbusJwtEncoder.encode(NimbusJwtEncoder.java:187)
	at app.zoner.auth.JwtProvider.createAccessToken(JwtProvider.java:38)
```

재현 조건
`JwtProviderTest`에서 만료 토큰을 만들려고 `accessTtl`에 `Duration.ofMinutes(-5)`를 넣음.

원인
`NimbusJwtEncoder`가 토큰 객체를 만들 때 `expiresAt > issuedAt`을 검사한다. 음수 TTL로는 애초에 토큰을 못 만든다.
계획이 "디코더의 60초 시계 오차 때문에 -5분"이라고 적었는데, 인코더 쪽 검사를 놓쳤다.

시도했지만 안 된 것
없음. 스택이 인코더를 가리켜서 바로 방향을 잡았다.

해결
`JwtProvider`에 `java.time.Clock`을 받는 생성자를 하나 더 두고(`JwtProvider(JwtProperties, Clock)`), 스프링이 쓰는
한 개짜리 생성자에는 `@Autowired`를 붙여 `Clock.systemUTC()`로 넘기게 했다. 테스트는 20분 전으로 고정한 시계로
15분짜리를 발급해 "5분 전에 만료된" 토큰을 만든다. 디코더는 실제 시계를 쓰니 운영 동작은 그대로다.

검증
`JwtProviderTest` 4개 통과. 만료 테스트가 `AUTH_TOKEN_EXPIRED`를 받는다.

추후 관리
생성자가 두 개라 스프링이 헷갈릴 수 있다. `@Autowired`를 지우면 기동이 실패하니 지우지 말 것.

배운 점
시간이 끼는 코드는 처음부터 `Clock`을 주입받게 만드는 게 낫다. 테스트에서 시간을 조작할 다른 방법은 다 꼼수다.

### TS-004: 토큰 갱신이 500 — LAZY 프록시가 트랜잭션 밖으로 나감

날짜: 2026-10-13
상태: 해결

증상
`POST /api/v1/auth/refresh`가 200이 아니라 500 `INTERNAL_ERROR`. 로그:
```
org.hibernate.LazyInitializationException: Could not initialize proxy [app.zoner.user.User#<REDACTED>] - no session
	at app.zoner.user.User$HibernateProxy.getEmail(Unknown Source)
	at app.zoner.user.dto.UserResponse.from(UserResponse.java:9)
	at app.zoner.auth.dto.AuthResponse.from(AuthResponse.java:7)
	at app.zoner.auth.AuthController.respond(AuthController.java:73)
```

재현 조건
`spring.jpa.open-in-view: false`, `RefreshToken.user`가 `@ManyToOne(fetch = LAZY)`.
`AuthService.refresh()`가 `old.getUser()`를 그대로 `AuthResult`에 담아 반환.

원인
`refresh()`의 `@Transactional`이 끝나면 세션이 닫힌다. 반환된 `User`는 초기화 안 된 프록시였고, 컨트롤러가
`UserResponse.from(user)`에서 `getEmail()`을 부르는 순간 세션이 없어 터졌다. 가입·로그인은 `users.save()`가 실제
엔티티를 돌려줘서 같은 코드가 멀쩡했다. 그래서 refresh에서만 났다.

시도했지만 안 된 것
없음. 스택이 정확히 어디서 프록시를 건드리는지 보여 줬다.

해결
`AuthService.refresh()` 안에서 `Hibernate.unproxy(old.getUser(), User.class)`로 트랜잭션 안에서 실제 객체를 풀어서 넘긴다.

검증
`RefreshLogoutTest` 5개 통과. 전체 33개 통과.

추후 관리
엔티티를 서비스 밖으로 넘기는 자리는 전부 "이게 프록시일 수 있나"를 본다. 자리가 늘면 서비스가 DTO를 바로 만들어 돌려주는
쪽으로 바꾼다.

배운 점
`open-in-view: false`는 맞는 선택이지만, 그 대신 "LAZY 연관을 트랜잭션 밖에서 읽지 않는다"를 코드가 지켜야 한다.
save()가 돌려주는 엔티티와 연관으로 꺼낸 엔티티는 겉모습이 같아도 다르다.

### TS-005: 없는 주소·잘못된 메서드가 500으로 나감

날짜: 2026-10-13
상태: 해결

증상
Task 6·8의 RED 단계에서 "아직 없는 주소"에 요청했는데 404·405가 아니라 500 `INTERNAL_ERROR`가 나왔다.

재현 조건
`GlobalExceptionHandler`에 `@ExceptionHandler(Exception.class)` catch-all만 있고, 스프링이 던지는
`NoResourceFoundException`(404), `HttpRequestMethodNotSupportedException`(405)에 대한 처리가 없음.

원인
`@RestControllerAdvice`의 catch-all은 스프링 MVC가 내부적으로 던지는 "정상적인" 예외까지 잡는다. 그걸 500으로 바꾸고
에러 로그까지 남겼다. 주소를 잘못 친 사람이 "문제가 생겼어요"를 보고, 운영 로그에는 가짜 에러가 쌓인다.

시도했지만 안 된 것
없음.

해결
`ErrorCode`에 `NOT_FOUND`(404), `METHOD_NOT_ALLOWED`(405)를 추가하고 두 예외에 핸들러를 달았다. 스펙(`03-api.md`)에는
자원별 404 코드(`SESSION_NOT_FOUND` 등)만 있어서 "주소 자체가 없음"용 공통 코드를 새로 뒀다.
테스트 `unknown_path_is_404_not_500`, `wrong_method_is_405_not_500`을 먼저 빨갛게 만들고 고쳤다.

검증
`GlobalExceptionHandlerTest` 7개 통과.

추후 관리
catch-all 아래로 떨어지는 예외가 더 있을 수 있다(`MissingServletRequestParameterException` 등은 지금 400 아님).
새 종류의 요청이 생길 때마다 "잘못된 요청"이 500으로 가는지 테스트를 하나씩 추가한다.

배운 점
catch-all 핸들러를 두면 그 위에 "스프링이 원래 알아서 처리하던 것들"을 다시 하나씩 돌려줘야 한다. 공짜가 아니다.

### TS-006: 커스텀 필터를 기준으로 `addFilterBefore` 했더니 컨텍스트 전체가 안 뜸

날짜: 2026-10-13
상태: 해결

증상
테스트 41개 중 37개 실패. 전부 `ApplicationContext failure threshold (1) exceeded`. 맨 처음 실패의 원인:
```
Caused by: java.lang.IllegalArgumentException: The Filter class app.zoner.common.security.JwtAuthFilter does not have a registered order
```

재현 조건
```java
.addFilterBefore(authRateLimitFilter, JwtAuthFilter.class)
.addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
```
이 순서로 적음 (계획 문서 그대로).

원인
`addFilterBefore(필터, 기준클래스)`의 기준은 스프링 시큐리티가 "순서를 아는" 필터여야 한다. 내장 필터는 처음부터 알고,
커스텀 필터는 `addFilterBefore/After`로 한 번 등록돼야 순서가 생긴다. `JwtAuthFilter`가 아직 등록 안 된 상태에서
기준으로 썼으니 순서를 모른다고 거부했다.

시도했지만 안 된 것
없음. 에러 문구가 정확했다.

해결
두 줄을 바꿨다. `jwtAuthFilter`를 먼저 `UsernamePasswordAuthenticationFilter` 앞에 등록하고, 그다음
`authRateLimitFilter`를 `JwtAuthFilter.class` 앞에 둔다. 실행 순서(횟수 제한 → JWT)는 계획대로다.

검증
전체 41개 통과. `AuthRateLimitTest`에서 11번째 요청이 429, 다른 IP는 영향 없음.

추후 관리
필터를 더 추가할 때 기준 클래스는 "이미 위에서 등록한 것"만 쓴다.

배운 점
테스트가 한꺼번에 수십 개 깨지면 첫 번째 실패의 `Caused by`만 본다. 나머지는 다 "컨텍스트 못 떠서"다.
