# ADR-001 백엔드는 Java + Spring Boot

날짜 2026-10-12 · 상태 채택

## 맥락

v1은 Supabase에 기대서 백엔드 코드가 거의 없었다. 인증은 Supabase Auth, 권한은 RLS, 서버 로직은 Edge Function 두 개뿐이었다.
v2는 백엔드를 직접 만드는 게 목적이다. 인증, 토큰, 트랜잭션, 실시간, 예약 작업, 외부 API 연동을 전부 내 코드로 한다.
나는 지금 Java Spring을 쓰는 회사에서 백엔드로 일하고 있다.

## 선택지

| 안 | 내용 | 걸리는 점 |
|---|---|---|
| A. Java + Spring Boot | 회사와 같은 스택 | JVM 메모리가 커서 무료 호스팅이 빠듯하다 |
| B. Kotlin + Spring Boot | Spring은 같고 언어만 바꿈 | 코루틴은 좋지만 회사가 Java라 전이가 조금 줄어든다 |
| C. TypeScript + NestJS | Spring과 구조가 비슷하고 프론트와 타입 공유 | 배운 게 회사 코드에 바로 안 쓰인다 |
| D. Go | 실시간·동시성에 강하고 배포가 가볍다 | Spring과 사고방식이 달라 업무 전이가 적다 |

## 결정

A. Java 21 + Spring Boot 4.1.

처음엔 3.5로 적었다가 확인해 보니 3.5는 2026-06-30에 OSS 지원이 끝났다. 4.0은 2026-12-31까지라 짧고, 4.1(2026-06 출시)이 지금 시작하기에 맞다. 회사가 3.x를 쓰더라도 Security·JPA·트랜잭션의 쓰는 법은 같고, 바뀐 건 스타터 이름(`starter-web` → `starter-webmvc`)과 Jackson 3 정도다.

## 이유

이 프로젝트의 목적이 "백엔드를 깊게"다. 깊게 판 게 업무에 바로 쓰이려면 같은 스택이어야 한다. Spring Security, JPA, 트랜잭션 경계, `@Scheduled`, WebSocket을 여기서 겪어 보면 회사 코드가 왜 그렇게 생겼는지 보인다.
C와 D가 더 가볍고 배포가 쉬운 건 맞다. 그런데 그 이점은 운영 비용에서 나오고, 운영 비용 문제는 ADR-002에서 VM을 고르면서 따로 풀었다.
B는 반쯤 끌렸다. 하지만 Kotlin 문법을 배우는 시간이 Spring을 배우는 시간을 깎아 먹는다. 언어는 이미 아는 걸 쓰고 프레임워크에 집중한다.

## 감수하는 것

- 기동에 메모리 300~500MB. 무료 PaaS(Render 512MB, Fly.io 256MB)로는 못 간다 → ADR-002
- 코드 양이 C, D보다 많다. 보일러플레이트는 Lombok 없이 Java 21 record로 줄인다
- Boot 4는 Jackson 3를 쓴다. Jackson 2에 묶인 라이브러리(`jjwt` 등)는 못 쓴다. JWT는 Spring Security의 Nimbus 모듈로 한다

## 다시 볼 조건

회사 스택이 바뀌거나, 이 프로젝트가 학습용이 아니라 실제 서비스로 바뀌어 운영 비용이 우선이 될 때.
