# Zoner v2 구조·기술 선택

- 작성일: 2026-10-12
- 상태: 확정 (2026-10-12)
- 앞 문서: `01-requirements.md`, `02-domain-db.md`, `03-api.md`
- 고른 이유를 길게 적은 것은 `adr/` 폴더에 있다. 이 문서는 전체 그림 한 장이다.

순서: 1 전체 그림 → 2 저장소 → 3 백엔드 → 4 프론트 → 5 실시간 → 6 예약 작업 → 7 뱃지 지급 → 8 파일 저장 → 9 배포 → 10 요청 횟수 제한 → 11 테스트 → 12 앞 문서에서 넘어온 것 → 13 뒤 단계로 넘기는 것

---

## 1. 전체 그림

```
   사용자 PC 브라우저                         Oracle Cloud 무료 VM (docker compose)
┌──────────────────────┐                  ┌──────────────────────────────────────┐
│ Vite + React + TS    │   https / wss    │  Caddy  ──▶  Spring Boot  ──▶ Postgres│
│  ├ 웹캠 → MediaPipe  │ ───────────────▶ │  (HTTPS,     (API, /ws,              │
│  │   (판정은 여기서) │   REST + /ws     │   정적 파일)  예약 작업)              │
│  └ 타임랩스 파일     │                  └──────────────────┬───────────────────┘
└──────────┬───────────┘                                     │ 서명한 주소 발급
           │ presigned URL로 바로 올림                        ▼
           └──────────────────────────────────────▶  Cloudflare R2 (S3 호환)
                                                     타임랩스 · 학습 샘플
```

- 영상은 브라우저 밖으로 안 나간다 (원칙 1). 서버에는 판정 결과만 간다
- 파일은 서버를 거치지 않고 R2로 바로 간다 (`03-api.md` 9-1절)
- 서버는 한 대다. 두 대가 되면 바뀌어야 하는 지점은 13절과 ADR-003에 적었다

## 2. 저장소

`zoner-v2` 하나에 다 둔다.

```
zoner-v2/
├── backend/            Spring Boot (Gradle)
├── frontend/           Vite + React + TS
├── deploy/             docker-compose.yml, Caddyfile, 배포 스크립트
├── docs/               이 문서들
└── .github/workflows/  PR마다 테스트
```

혼자 만들고 프론트와 백엔드가 같이 바뀌는 일이 많다. 저장소를 나누면 API 하나 바꿀 때 PR 두 개를 맞춰야 해서 번거롭기만 하다.

## 3. 백엔드

| 항목 | 선택 | 한 줄 이유 |
|---|---|---|
| 언어 | Java 21 | 회사 스택과 같다. 배운 게 그대로 업무에 쓰인다 (ADR-001) |
| 프레임워크 | Spring Boot 4.1 | 같은 이유. 3.5는 2026-06-30에 OSS 지원이 끝났다 (ADR-001) |
| 빌드 | Gradle (Kotlin DSL) | Maven보다 빌드 스크립트가 짧고 캐시가 빠르다 |
| 인증 | Spring Security + JWT 직접 발급 | 접근 토큰은 서명만 검사, 갱신 토큰은 DB에 해시로 저장 (`03-api.md` 1.5절) |
| DB 접근 | Spring Data JPA | 단순 CRUD는 JPA, 통계 쿼리와 `member_count` 갱신 같은 건 네이티브 SQL |
| 마이그레이션 | Flyway | `02-domain-db.md`의 SQL을 그대로 버전 파일로 옮긴다 |
| DB | PostgreSQL 16 | 부분 인덱스, `timestamptz`, 한 문장 갱신(5.5절)을 설계가 이미 전제한다 |
| 요청 횟수 제한 | Bucket4j (메모리) | 서버 한 대라 공유 저장소가 필요 없다 |
| 캐시·메시지 브로커 | 없음 | 지금 쓸 곳이 없다 (ADR-003) |

JWT는 Spring Security에 들어 있는 Nimbus(`spring-security-oauth2-jose`)의 `NimbusJwtEncoder`/`NimbusJwtDecoder`로 HS256 서명한다. `jjwt`는 Boot 4의 Jackson 3와 안 맞아서 안 쓴다. 비밀번호는 `BCryptPasswordEncoder`.

## 4. 프론트엔드

| 항목 | 선택 | 한 줄 이유 |
|---|---|---|
| 빌드 | Vite | CRA는 관리가 멈췄다. 개발 서버가 바로 뜨고 설정이 짧다 |
| UI | React 19 | v1 지식과 MediaPipe 코드를 그대로 가져온다 |
| 언어 | TypeScript | API 응답 타입을 Spring DTO와 1:1로 맞춘다 |
| 라우팅 | react-router 7 | v1과 같다 |
| 서버 상태 | TanStack Query | 캐시, 다시 불러오기, 로딩·에러 상태를 직접 안 짠다 |
| 클라이언트 상태 | React Context | 전역으로 들고 다닐 건 측정 중인 세션 하나다. 상태 라이브러리는 과하다 |
| 스타일 | `tokens.css` + 일반 CSS | v1 방식. 디자인 토큰 규약을 따른다 |
| 판정 | `@mediapipe/tasks-vision` | v1 ADR-001 그대로. 브라우저에서 돈다 |
| 테스트 | Vitest + Testing Library | Vite와 설정을 공유한다 |

API 응답 타입은 `frontend/src/api/types.ts`에 한 곳에 둔다. 나중에 OpenAPI 파일이 생기면 거기서 생성하는 것으로 바꾼다.

## 5. 실시간

Spring WebSocket을 STOMP 없이 그대로 쓴다 (ADR-004).

```
브라우저 ──/ws──▶ WebSocketHandler
                     ├ 첫 메시지 auth → 토큰 검사 → 세션에 userId 붙임
                     ├ RoomPresenceRegistry (메모리)
                     │    roomId → { userId → state, 마지막 ping 시각 }
                     │    userId → WebSocketSession
                     └ 보내기: 방 참여자 세션 목록을 돌며 JSON 전송
```

- 상태는 DB에 안 쓴다. 재시작하면 날아가고, 프론트가 다시 연결하면서 `room.snapshot`을 받는다 (`03-api.md` 10절이 이미 그렇게 정했다)
- `ping` 60초 없으면 `offline` 처리는 `@Scheduled` 10초 주기로 훑는다
- `room.progress`, `badge.earned`, `stamp.received`는 REST 처리가 끝난 뒤 이 레지스트리를 통해 나간다. 트랜잭션이 커밋된 뒤에만 보낸다 (7절)

## 6. 예약 작업

Spring `@Scheduled`. 서버 한 대라 잠금(ShedLock)은 안 건다.

| 작업 | 주기 | 하는 일 |
|---|---|---|
| 하루 마감 | 매일 05:00 KST | 전날 `daily_records` 확정, 달력 뱃지 등급, 연속 기록 갱신 |
| 주간 돌아보기 | 매일 05:10 KST | `review_weekday`가 오늘인 사용자에게 주간 돌아보기 생성 |
| 정리 | 매일 04:00 KST | 휴지통 30일 지난 세션 삭제, `pending` 타임랩스 치우기, 만료·폐기된 갱신 토큰 삭제 |
| 접속 끊김 감지 | 10초 | `ping` 60초 없는 사람을 `offline`으로 |

시간대는 서버 설정이 아니라 코드에 `Asia/Seoul`로 박는다. VM 시간대가 UTC여도 같게 돈다.

## 7. 뱃지 지급

분 기록 저장 → 하루 기록 갱신 → 뱃지 조건 검사 → 지급을 한 트랜잭션에서 동기로 한다 (ADR-006).

- 중복 지급은 `user_badges (user_id, badge_code)` 유니크 제약이 막는다
- `badge.earned` 알림은 `@TransactionalEventListener(phase = AFTER_COMMIT)`에서 보낸다. 롤백된 뱃지가 화면에 뜨면 안 된다
- 조건 검사는 `BadgeRule` 인터페이스 하나에 뱃지마다 구현을 둔다. 뱃지가 늘어도 서비스 코드는 안 바뀐다

## 8. 파일 저장

Cloudflare R2 (ADR-005). S3 호환이라 AWS SDK for Java v2를 그대로 쓴다.

- 서버는 presigned PUT/GET 주소만 만든다. 파일은 서버를 안 거친다
- `complete` 요청이 오면 서버가 `HeadObject`로 크기를 확인하고 `ready`로 바꾼다
- 버킷은 비공개. 보는 주소는 1시간짜리 presigned GET
- 로컬 개발은 docker compose의 MinIO. 같은 S3 API라 코드는 설정값(endpoint, 키)만 다르다
- 학습 샘플(`/sessions/{id}/samples`)도 같은 버킷의 다른 접두어에 둔다. 크기가 작아 서버를 거쳐도 되지만 저장소는 하나로 둔다

## 9. 배포

Oracle Cloud Always Free, `VM.Standard.A1.Flex` 4 OCPU / 24GB, Ubuntu 24.04 (ADR-002).

```
deploy/
├── docker-compose.yml   postgres, backend, caddy
├── Caddyfile            도메인 → HTTPS 자동 발급, /api·/ws → backend, 나머지 → 프론트 정적 파일
└── deploy.sh            git pull → 프론트 빌드 → 이미지 빌드 → compose up
```

- 웹캠은 https에서만 열린다 (`getUserMedia` 정책). 도메인은 DuckDNS 무료 또는 본인 도메인
- GitHub Actions는 PR마다 백엔드·프론트 테스트만 돌린다. 배포 자동화는 안 한다. 혼자 쓰는 서버에 ssh 한 번이 더 단순하다
- Postgres 데이터는 VM 디스크 볼륨. 백업은 `pg_dump`를 매일 R2에 올리는 cron 하나
- 비밀값(DB 비밀번호, JWT 서명 키, R2 키, AI API 키)은 VM의 `.env` 파일. 저장소에 안 올린다

## 10. 요청 횟수 제한

`03-api.md` 13절에서 넘어온 숫자다. AI 한도는 `03-api.md` 6절에 이미 있다.

| 대상 | 한도 | 기준 | 넘으면 |
|---|---|---|---|
| `POST /auth/login`, `/auth/signup` | 분당 10회 | IP | `RATE_LIMITED` 429 |
| 일반 API 전부 | 분당 120회 | 사용자 | `RATE_LIMITED` 429 |
| `PUT /sessions/{id}/samples/*` | 분당 2회 | 세션 | `RATE_LIMITED` 429 |
| WebSocket 연결 | 사용자당 2개 | 사용자 | 가장 오래된 연결을 끊음 |

분당 120회는 넉넉하다. 분 기록 저장이 분당 1회, 판정 상태는 WebSocket으로 가서 REST 호출이 많지 않다. 로그인 10회는 비밀번호 무차별 대입을 늦추는 용도다.

## 11. 테스트

| 층 | 도구 | 무엇을 |
|---|---|---|
| 백엔드 단위 | JUnit 5 | 뱃지 규칙, 날짜 계산(새벽 5시), 토큰 |
| 백엔드 통합 | Testcontainers (Postgres) + MockMvc | API 하나하나. H2는 안 쓴다. 부분 인덱스·`timestamptz`가 H2와 다르게 돈다 |
| 동시성 | JUnit + 스레드 | 방 정원 동시 입장 (`02-domain-db.md` 5.5절), 도장 중복 |
| 프론트 단위 | Vitest | 15초 상태 안정화, 판정 임계값, API 타입 |
| 부하 | k6 | N-04: 동시 1,000명이 분 기록을 보낼 때. 다 만든 뒤 한 번 |

## 12. 앞 문서에서 넘어온 것

| 어디서 | 항목 | 처리 |
|---|---|---|
| `01` 10절 | 언어, 프레임워크, DB, 배포 | 이 문서 3·9절 |
| `02` 7절 | 방의 지금 상태를 어디에 | 메모리. 5절 |
| `02` 7절 | 예약 작업을 뭘로 | `@Scheduled`. 6절 |
| `02` 7절 | 뱃지 지급 같이/따로 | 같이. 7절 |
| `02` 7절 | 학습 샘플 파일 저장소 | R2. 8절 |
| `03` 12절 | `refresh_tokens` 테이블, `rooms.name` 인덱스 | `02-domain-db.md`에 반영함 (2026-10-12) |
| `03` 13절 | 요청 횟수 제한 숫자 | 10절 |
| `03` 13절 | 서버 여러 대일 때 실시간 | 지금은 한 대. 13절 |
| `plans/01` | 계정 API 7개 (F-101~104, F-201~202) | 구현 완료 (2026-10-13). `backend/`, 테스트 41개 |

## 13. 뒤 단계로 넘기는 것

| 항목 | 정하는 시점 | 지금 생각 |
|---|---|---|
| 서버 2대 이상 | 한 대로 N-04를 못 맞출 때 | Redis pub/sub로 방 이벤트 전달, ShedLock, Bucket4j Redis 백엔드. ADR-003 |
| OpenAPI 파일 | 계정 API 구현 뒤 | `springdoc-openapi`로 코드에서 뽑아 프론트 타입 생성 |
| 분 기록 테이블 나누기 | 부하 테스트 뒤 | `02` 7절 그대로 |
| 배포 자동화 | ssh 배포가 귀찮아질 때 | GitHub Actions → ghcr.io → VM에서 pull |
| 모니터링 | 실제 사용자가 생길 때 | Spring Actuator + 로그 파일. 그 이상은 그때 |
