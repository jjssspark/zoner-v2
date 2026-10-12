- 작성일: 2026-10-04
- 상태: 확정 (2026-10-04)
- 앞 문서: `01-requirements.md`, `02-domain-db.md`

프론트와 백엔드가 주고받는 약속이다. 어떤 언어로 서버를 만들어도 이 문서대로 동작해야 한다.
언어가 정해지면 이 문서를 OpenAPI 파일로 옮긴다.

---

## 1. 공통 규칙

### 1.1 주소

- 모든 주소는 `/api/v1`로 시작한다. 약속을 깨는 변경이 생기면 `v2`를 만든다
- 주소는 명사 복수형, 동작은 HTTP 메서드로 나타낸다 (`GET /sessions`, `DELETE /sessions/{id}`)
- 메서드로 나타내기 어려운 동작만 끝에 동사를 붙인다 (`POST /sessions/{id}/end`)
- 내 것을 가리킬 때는 `me`를 쓴다 (`GET /users/me`). 주소에 내 id를 넣지 않는다

### 1.2 데이터 모양

- JSON, 이름은 camelCase (`netFocusSeconds`)
- 시각은 ISO 8601 UTC (`2026-10-04T05:30:00Z`)
- 날짜는 `YYYY-MM-DD`. 새벽 5시에 바뀌는 날짜다 (R-01)
- 시간 길이는 전부 초 단위 정수

### 1.3 응답 봉투

성공

```json
{ "success": true, "data": {}, "meta": { "nextCursor": "abc" } }
```

실패

```json
{
  "success": false,
  "error": { "code": "ROOM_FULL", "message": "방이 꽉 찼어요." }
}
```

- 프론트는 `message`가 아니라 `code`로 갈라서 처리한다. 문구는 언제든 바뀔 수 있다
- `meta`는 목록 응답에만 있다
- 입력이 틀렸을 때는 `error.fields`에 칸별 이유를 넣는다 (`{ "nickname": "2~12자여야 해요." }`)

v1은 봉투 없이 HTTP 상태 코드로만 갈랐다. v2는 주소가 50개쯤 되고 같은 404라도 이유가 여러 가지라 코드가 필요하다.

### 1.4 상태 코드와 에러 코드

| HTTP | 뜻                        | 에러 코드 예                                                                                  |
| ---- | ------------------------- | --------------------------------------------------------------------------------------------- |
| 200  | 성공                      |                                                                                               |
| 201  | 새로 만듦                 |                                                                                               |
| 204  | 성공, 돌려줄 내용 없음    |                                                                                               |
| 400  | 입력이 틀림               | `VALIDATION_FAILED`                                                                           |
| 401  | 로그인 필요               | `AUTH_REQUIRED`, `AUTH_TOKEN_EXPIRED`, `AUTH_INVALID_CREDENTIALS`                             |
| 403  | 로그인은 했지만 권한 없음 | `ROOM_OWNER_ONLY`, `ROOM_BANNED`                                                              |
| 404  | 없음                      | `SESSION_NOT_FOUND`, `ROOM_NOT_FOUND`                                                         |
| 409  | 지금 상태와 안 맞음       | `EMAIL_TAKEN`, `NICKNAME_TAKEN`, `SESSION_ALREADY_ACTIVE`, `ROOM_FULL`, `STAMP_ALREADY_GIVEN` |
| 429  | 한도 넘음                 | `RATE_LIMITED`, `STAMP_DAILY_LIMIT`, `COACH_DAILY_LIMIT`                                      |
| 500  | 서버 문제                 | `INTERNAL_ERROR`                                                                              |
| 502  | 외부 AI 호출 실패         | `AI_UNAVAILABLE`                                                                              |

남의 것을 요청했을 때는 403이 아니라 404를 준다. 403을 주면 "그 id가 있다"는 걸 알려주게 된다.

### 1.5 인증

| 토큰      | 수명 | 두는 곳       | 쓰는 곳                                  |
| --------- | ---- | ------------- | ---------------------------------------- |
| 접근 토큰 | 15분 | 프론트 메모리 | 모든 요청의 `Authorization: Bearer` 헤더 |
| 갱신 토큰 | 14일 | httpOnly 쿠키 | `POST /auth/refresh` 한 곳에서만         |

- 접근 토큰이 짧아서 새어 나가도 15분 뒤엔 못 쓴다
- 갱신 토큰은 자바스크립트가 못 읽는 쿠키에 둔다. 쓸 때마다 새 것으로 바꾸고 옛 것은 못 쓰게 한다
- 토큰을 주소 뒤(`?token=`)에 붙이지 않는다. 서버 로그에 남는다

### 1.6 목록 나눠 보내기

```
GET /sessions?limit=20&cursor=abc
```

- 쪽 번호가 아니라 커서 방식이다. 응답의 `meta.nextCursor`를 다음 요청에 넣는다. 더 없으면 `null`
- `limit`은 기본 20, 최대 50
- 쪽 번호 방식은 보는 중에 새 줄이 생기면 같은 줄이 두 번 나오거나 빠진다. 뒤쪽으로 갈수록 느려지기도 한다

### 1.7 같은 요청이 두 번 와도 되는 것

인터넷이 끊기면 프론트는 같은 요청을 다시 보낸다. 아래는 여러 번 와도 결과가 같아야 한다.

| 요청             | 방법                                                 |
| ---------------- | ---------------------------------------------------- |
| 분 기록 저장     | `(sessionId, minuteIndex)`가 같으면 무시             |
| 세션 끝내기      | 이미 끝난 세션이면 저장된 결과를 그대로 돌려줌       |
| 세션 조언 만들기 | 이미 있으면 저장된 걸 돌려줌                         |
| 도장 주기        | 같은 사람에게 오늘 이미 줬으면 `STAMP_ALREADY_GIVEN` |

---

## 2. 계정 (F-101~104, F-201~202)

| 메서드 | 주소            | 하는 일                                |
| ------ | --------------- | -------------------------------------- |
| POST   | `/auth/signup`  | 가입. 바로 로그인된 상태로 토큰을 준다 |
| POST   | `/auth/login`   | 로그인                                 |
| POST   | `/auth/refresh` | 접근 토큰 새로 받기                    |
| POST   | `/auth/logout`  | 갱신 토큰을 못 쓰게 하고 쿠키를 지운다 |
| GET    | `/users/me`     | 내 정보                                |
| PATCH  | `/users/me`     | 닉네임, 하루 목표 바꾸기               |
| DELETE | `/users/me`     | 탈퇴. 비밀번호를 다시 받는다           |

```json
// POST /auth/signup 요청
{ "email": "a@example.com", "password": "********", "nickname": "새벽공부" }

// 응답 201
{ "success": true, "data": {
    "accessToken": "...",
    "user": { "id": "...", "email": "a@example.com", "nickname": "새벽공부", "dailyGoalSeconds": 21600 }
} }
```

- 비밀번호는 8자 이상
- 로그인 실패는 이메일이 없든 비밀번호가 틀리든 똑같이 `AUTH_INVALID_CREDENTIALS`. 다르게 답하면 가입된 이메일을 알아낼 수 있다
- 로그인과 가입은 같은 곳에서 너무 자주 오면 `RATE_LIMITED`
- 목표를 바꾸면 내일부터 적용된다. 오늘 하루 기록이 이미 있으면 오늘 값은 그대로다

---

## 3. 측정 (F-301~306)

| 메서드 | 주소                     | 하는 일                             |
| ------ | ------------------------ | ----------------------------------- |
| POST   | `/sessions`              | 세션 시작                           |
| GET    | `/sessions/active`       | 진행 중인 세션. 없으면 `data: null` |
| PUT    | `/sessions/{id}/minutes` | 분 기록 저장 (1~60개)               |
| POST   | `/sessions/{id}/end`     | 세션 끝내기                         |

```json
// POST /sessions 요청
{ "studyMode": "book", "judgeVersion": "rule-1.0", "startedAt": "2026-10-04T00:00:00Z" }

// PUT /sessions/{id}/minutes 요청
{ "minutes": [
  { "minuteIndex": 0, "minuteStart": "2026-10-04T00:00:00Z",
    "totalTicks": 12, "focusedTicks": 10, "reasonCounts": { "head_turned": 2 } }
] }

// 응답 200
{ "success": true, "data": { "savedCount": 1, "todayNetFocusSeconds": 50 } }

// POST /sessions/{id}/end 요청
{ "endedAt": "2026-10-04T02:00:00Z", "pausedSeconds": 300, "alertCount": 4 }
```

- 평소엔 1분에 한 번, 한 개씩 보낸다. 끊겼다가 다시 이어지면 밀린 분을 한 번에 최대 60개까지 보낸다
- `minuteIndex`는 측정한 시간 기준이다. 일시정지한 동안은 번호가 늘지 않는다
- 서버는 `totalTicks`가 12 이하인지, `focusedTicks`가 그보다 작은지, `minuteStart`가 세션 시작 뒤이고 지금보다 미래가 아닌지 확인한다. 순위와 뱃지가 걸려 있어서 프론트가 보낸 값을 그대로 믿지 않는다
- 응답의 `todayNetFocusSeconds`로 프론트가 오늘 달성률을 바로 갱신한다
- 세션을 끝내면 서버가 분 기록으로 `netFocusSeconds`와 `focusScore`를 계산한다. 프론트가 계산한 값은 받지 않는다
- 12시간 넘게 분 기록이 안 오는 진행 중 세션은 서버가 마지막 분 기록 시각으로 끝낸다

---

## 4. 리포트와 기록 (F-401~403)

| 메서드 | 주소                           | 하는 일                           |
| ------ | ------------------------------ | --------------------------------- |
| GET    | `/sessions`                    | 끝난 세션 목록 (휴지통 제외)      |
| GET    | `/sessions/{id}`               | 세션 하나 + 분 기록 + 리포트 지표 |
| DELETE | `/sessions/{id}`               | 휴지통으로                        |
| GET    | `/trash/sessions`              | 휴지통 목록                       |
| POST   | `/trash/sessions/{id}/restore` | 되살리기                          |
| DELETE | `/trash/sessions/{id}`         | 완전히 지우기                     |

```json
// GET /sessions/{id} 응답의 data
{
  "id": "...",
  "studyMode": "book",
  "startedAt": "...",
  "endedAt": "...",
  "measuredSeconds": 6900,
  "netFocusSeconds": 5400,
  "pausedSeconds": 300,
  "focusScore": 78,
  "alertCount": 4,
  "minutes": [{ "minuteIndex": 0, "focusedTicks": 10, "totalTicks": 12 }],
  "metrics": {
    "longestStreakMinutes": 34,
    "firstBreakMinute": 12,
    "firstHalfScore": 84,
    "secondHalfScore": 72,
    "volatility": 11.3,
    "alertsPerHour": 2.1,
    "topDistraction": { "reason": "head_turned", "ratio": 14 }
  },
  "advice": null
}
```

- `metrics`는 서버가 그때그때 계산한다. v1은 프론트가 계산했는데, AI 코치도 같은 값을 써야 해서 서버로 옮긴다
- 세션을 지워도 하루 기록과 뱃지는 줄어들지 않는다. 기록을 지워서 달력을 고치는 걸 막고, 실수로 지웠을 때 뱃지가 사라지는 것도 막는다

---

## 5. 달력과 뱃지 (F-501~505)

| 메서드 | 주소                      | 하는 일                                    |
| ------ | ------------------------- | ------------------------------------------ |
| GET    | `/calendar?month=2026-10` | 한 달 치 하루 기록                         |
| GET    | `/users/me/stats`         | 연속 기록, 누적 시간, 도장 수, 오늘 달성률 |
| GET    | `/badges`                 | 업적 뱃지 전체 목록과 내가 받았는지        |

```json
// GET /calendar?month=2026-10 응답의 data
{
  "days": [
    {
      "day": "2026-10-03",
      "netFocusSeconds": 22000,
      "goalSeconds": 21600,
      "badgeTier": "gold"
    },
    {
      "day": "2026-10-04",
      "netFocusSeconds": 5400,
      "goalSeconds": 21600,
      "badgeTier": null
    }
  ]
}
```

오늘은 아직 안 끝나서 `badgeTier`가 `null`이다. 프론트는 `netFocusSeconds / goalSeconds`로 진행 정도를 보여준다.

---

## 6. AI 코치 (F-601~605)

| 메서드 | 주소                                 | 하는 일                                      |
| ------ | ------------------------------------ | -------------------------------------------- |
| POST   | `/sessions/{id}/advice`              | 세션 조언 만들기. 있으면 저장된 걸 돌려준다  |
| GET    | `/weekly-reviews`                    | 주간 돌아보기 목록                           |
| GET    | `/weekly-reviews/{weekStart}`        | 한 주 치 돌아보기와 다음 주 계획             |
| GET    | `/coach/conversations`               | 대화 목록                                    |
| POST   | `/coach/conversations`               | 새 대화                                      |
| DELETE | `/coach/conversations/{id}`          | 대화 지우기                                  |
| GET    | `/coach/conversations/{id}/messages` | 메시지 목록                                  |
| POST   | `/coach/conversations/{id}/messages` | 메시지 보내기. 답은 조각조각 흘려보낸다(SSE) |
| GET    | `/coach/usage`                       | 오늘 쓴 횟수와 남은 횟수                     |

한도

| 기능          | 한도                              | 이유                           |
| ------------- | --------------------------------- | ------------------------------ |
| 코치 대화     | 하루 30건 (내가 보낸 메시지 기준) | v1과 같다                      |
| 세션 조언     | 세션마다 한 번                    | 저장해 두고 다시 만들지 않는다 |
| 주간 돌아보기 | 주 1회, 서버가 알아서 만든다      | 사용자가 요청하는 게 아니다    |

- 한도의 "하루"도 새벽 5시 기준이다
- 10분 미만 세션은 조언을 만들지 않는다 (`SESSION_TOO_SHORT`). 근거가 모자라다
- 주간 돌아보기는 매주 한 번, 사용자가 정한 요일에 만든다 (기본 월요일). 만드는 날 바로 앞 7일을 다룬다
- 요일은 `PATCH /users/me`의 `reviewWeekday`(0 일요일 ~ 6 토요일)로 바꾼다. 바꾼 뒤 마지막 돌아보기에서 7일이 안 지났으면 그 주는 건너뛴다. 같은 날이 두 번 다뤄지지 않게 하기 위해서다
- 앞 7일에 기록이 하나도 없으면 AI를 부르지 않고 "기록 없음" 돌아보기만 만든다
- AI에게는 서버가 계산한 숫자만 넘긴다. 사용자가 쓴 글은 코치 대화에서만 AI에 들어간다

흘려보내는 답의 모양

```
data: {"text":"이번 주는 "}

data: {"text":"화요일이 제일 좋았어요."}

data: {"done":true,"messageId":"..."}
```

도중에 실패하면 `data: {"error":{"code":"AI_UNAVAILABLE"}}`를 보내고 끝낸다. 이때 답은 저장하지 않는다.

---

## 7. 스터디 방 (F-701~708)

| 메서드 | 주소                           | 하는 일                            | 누가                     |
| ------ | ------------------------------ | ---------------------------------- | ------------------------ |
| POST   | `/rooms`                       | 방 만들기                          | 누구나                   |
| GET    | `/rooms?q=공시`                | 공개 방 목록, 검색                 | 누구나                   |
| GET    | `/rooms/mine`                  | 내가 들어가 있는 방                | 누구나                   |
| GET    | `/rooms/{id}`                  | 방 정보와 참여자                   | 참여자. 공개 방은 누구나 |
| PATCH  | `/rooms/{id}`                  | 이름, 설명, 공개 여부, 정원 바꾸기 | 방장                     |
| DELETE | `/rooms/{id}`                  | 방 없애기                          | 방장                     |
| POST   | `/rooms/{id}/join`             | 공개 방 들어가기                   | 누구나                   |
| POST   | `/rooms/join-by-code`          | 초대 코드로 들어가기               | 누구나                   |
| DELETE | `/rooms/{id}/members/me`       | 나가기                             | 참여자                   |
| DELETE | `/rooms/{id}/members/{userId}` | 내보내기                           | 방장                     |
| PUT    | `/rooms/{id}/owner`            | 방장 넘기기                        | 방장                     |
| POST   | `/rooms/{id}/invite-code`      | 초대 코드 새로 만들기              | 방장                     |
| GET    | `/rooms/{id}/ranking`          | 오늘 순 집중 시간 순위             | 참여자                   |
| POST   | `/reports`                     | 신고                               | 누구나                   |

- 비공개 방은 참여자가 아니면 `ROOM_NOT_FOUND`다. 있는지도 알려주지 않는다
- 초대 코드는 참여자에게만 보인다
- 정원을 지금 인원보다 적게 줄일 수 없다 (`ROOM_CAPACITY_TOO_SMALL`)
- 들어갈 때 생기는 에러: `ROOM_FULL`, `ROOM_BANNED`, `ROOM_LIMIT_REACHED`(이미 5개), `ROOM_ALREADY_JOINED`
- 방장이 나가면 가장 먼저 들어온 사람이 방장이 된다. 혼자였으면 방이 없어진다 (R-10)

남에게 보이는 것 (원칙 3)

| 정보                                      | 같은 방 사람 | 공개 방 목록에서 |
| ----------------------------------------- | ------------ | ---------------- |
| 닉네임                                    | 보임         | 방장만           |
| 지금 상태                                 | 보임         | 안 보임          |
| 오늘 순 집중 시간                         | 보임         | 안 보임          |
| 연속 기록                                 | 보임         | 안 보임          |
| 집중도 점수, 세부 사유, 이메일, 세션 목록 | 안 보임      | 안 보임          |

```json
// GET /rooms/{id}/ranking 응답의 data
{
  "day": "2026-10-04",
  "ranking": [
    {
      "rank": 1,
      "userId": "...",
      "nickname": "새벽공부",
      "netFocusSeconds": 18000,
      "currentStreak": 12
    }
  ]
}
```

---

## 8. 칭찬 도장 (F-801~804)

| 메서드 | 주소            | 하는 일                                |
| ------ | --------------- | -------------------------------------- |
| POST   | `/stamps`       | 도장 주기                              |
| GET    | `/stamps/quota` | 오늘 남은 개수, 오늘 이미 준 사람 목록 |

```json
// POST /stamps 요청
{ "receiverId": "...", "roomId": "..." }
```

서버는 두 사람이 지금 그 방 참여자인지 확인한다.
에러: `STAMP_ALREADY_GIVEN`, `STAMP_DAILY_LIMIT`, `STAMP_SELF`, `STAMP_NOT_ROOMMATE`.

---

## 9. 학습용 데이터 (F-901~903)

| 메서드 | 주소                                   | 하는 일                       |
| ------ | -------------------------------------- | ----------------------------- |
| GET    | `/consent`                             | 지금 동의 상태                |
| PUT    | `/consent`                             | 동의                          |
| DELETE | `/consent`                             | 철회. 모은 샘플을 지운다      |
| PUT    | `/sessions/{id}/samples/{minuteIndex}` | 그 분의 얼굴 좌표 흐름 올리기 |
| POST   | `/sessions/{id}/corrections`           | 판정이 틀린 구간 표시         |

동의하지 않은 사람이 샘플을 올리면 `CONSENT_REQUIRED`(403).
틀린 구간 표시는 동의와 상관없이 할 수 있지만, 순위와 뱃지에는 반영되지 않는다 (원칙 2).

---

## 9-1. 타임랩스 (F-1001~1005)

| 메서드 | 주소 | 하는 일 |
|---|---|---|
| POST | `/sessions/{id}/timelapse/upload-url` | 올릴 주소 받기 |
| POST | `/sessions/{id}/timelapse/complete` | 다 올렸다고 알리기 |
| GET | `/timelapses` | 내 타임랩스 목록 |
| GET | `/sessions/{id}/timelapse` | 볼 수 있는 주소 받기 (1시간 동안만 열림) |
| DELETE | `/sessions/{id}/timelapse` | 지우기 |
| GET | `/timelapses/usage` | 쓴 용량과 한도 |

올리는 순서

```
1. 프론트 → 서버     POST upload-url { "sizeBytes": 31000000, "durationSeconds": 240 }
2. 서버 → 프론트     { "uploadUrl": "...", "expiresInSeconds": 600 }
3. 프론트 → 저장소   uploadUrl로 파일을 바로 올림 (서버를 거치지 않음)
4. 프론트 → 서버     POST complete
5. 서버              저장소에 파일이 있는지, 크기가 맞는지 확인하고 ready로 바꿈
```

- 파일이 서버를 거치지 않는다. 거치면 수십 MB가 서버 메모리와 회선을 차지해서 다른 요청이 느려진다. 서버는 "여기에 올려도 된다"는 잠깐 쓰는 주소만 만들어 준다
- 1번에서 서버가 크기와 남은 용량을 먼저 확인한다. 에러: `TIMELAPSE_TOO_LARGE`, `TIMELAPSE_QUOTA_EXCEEDED`
- 5번의 확인이 필요한 이유는, 프론트가 1번에서 말한 크기와 다른 파일을 올릴 수 있어서다
- 보는 주소도 잠깐만 열린다. 저장소는 비공개이고 주소를 아는 것만으로 계속 볼 수 없다
- 4번이 안 온 채로 남은 `pending` 줄은 예약 작업이 치운다

---

## 10. 실시간 통로 (F-704, N-06)

방 안의 "지금 상태"는 요청하고 답을 받는 방식으로는 2초 안에 전할 수 없다. 연결을 하나 열어 두고 서버가 먼저 보낸다(WebSocket).

- 주소: `/ws`. 사용자당 연결 하나로 내가 들어가 있는 방 전부의 소식을 받는다
- 연결한 뒤 첫 메시지로 접근 토큰을 보낸다. 주소에 토큰을 붙이지 않는다
- 메시지는 전부 `{ "type": "...", "data": { } }` 모양이다

프론트가 보내는 것

| type              | data                       | 언제                |
| ----------------- | -------------------------- | ------------------- |
| `auth`            | `{ "accessToken": "..." }` | 연결 직후 한 번     |
| `presence.update` | `{ "state": "focusing" }`  | 내 상태가 바뀔 때만 |
| `ping`            |                            | 30초마다            |

서버가 보내는 것

| type                                      | data                                        | 언제                               |
| ----------------------------------------- | ------------------------------------------- | ---------------------------------- |
| `room.snapshot`                           | 방의 참여자 전원의 지금 상태                | 연결 직후, 방마다 한 번            |
| `room.presence`                           | `{ "roomId", "userId", "state" }`           | 누군가의 상태가 바뀔 때            |
| `room.progress`                           | `{ "roomId", "userId", "netFocusSeconds" }` | 누군가의 분 기록이 저장될 때       |
| `room.member_joined` / `room.member_left` | `{ "roomId", "userId", "nickname" }`        | 들어오고 나갈 때                   |
| `room.removed`                            | `{ "roomId", "reason" }`                    | 내가 내보내졌거나 방이 없어졌을 때 |
| `stamp.received`                          | `{ "fromNickname", "roomId" }`              | 도장을 받았을 때                   |
| `badge.earned`                            | `{ "code", "name" }`                        | 뱃지를 받았을 때                   |

상태 값은 `focusing`(집중 중), `resting`(쉬는 중), `away`(자리 비움), `offline`(측정 안 함)이다.

- 집중과 딴짓이 5초마다 뒤집힐 수 있다. 판정이 한 번 흔들릴 때마다 보내면 남의 화면이 깜빡이고 세부 판정을 그대로 보여주는 셈이 된다. 그래서 프론트는 같은 상태가 3번(15초) 이어졌을 때만 바뀐 걸로 보낸다. 일시정지와 자리 비움은 바로 보낸다
- `ping`이 60초 동안 안 오면 서버가 그 사람을 `offline`으로 바꾸고 방에 알린다
- 상태는 DB에 저장하지 않는다. 연결이 끊겼다가 다시 이어지면 `room.snapshot`으로 처음부터 다시 받는다
- 실시간 통로는 알려주기만 한다. 방 들어가기, 도장 주기 같은 바꾸는 동작은 전부 위의 일반 주소로 한다. 바꾸는 길이 하나여야 검사도 한 곳에서 한다

---

## 11. 요구사항과 맞춰 보기

| 요구사항     | 주소                                                               |
| ------------ | ------------------------------------------------------------------ |
| F-101~104    | `/auth/*`, `/users/me`                                             |
| F-201, F-202 | `PATCH /users/me`                                                  |
| F-301        | `POST /sessions`의 `studyMode`                                     |
| F-302, F-305 | `PUT /sessions/{id}/minutes`                                       |
| F-303        | 브라우저에서 처리. `alertCount`만 서버로                           |
| F-304        | `pausedSeconds`, 실시간 `resting`                                  |
| F-306        | `POST /sessions/{id}/end`                                          |
| F-401~403    | `/sessions`, `/trash/sessions`                                     |
| F-501~503    | `/calendar`, `/users/me/stats`                                     |
| F-504, F-505 | `/badges`, 실시간 `badge.earned`                                   |
| F-601        | `POST /sessions/{id}/advice`                                       |
| F-602, F-603 | `/weekly-reviews`                                                  |
| F-604, F-605 | `/coach/*`                                                         |
| F-701~708    | `/rooms/*`, `/reports`, 실시간 `/ws`                               |
| F-801~804    | `/stamps`                                                          |
| F-901~903    | `/consent`, `/sessions/{id}/samples`, `/sessions/{id}/corrections` |

## 12. 앞 문서에 더해지는 것

API를 쓰다가 DB 설계에 없던 게 필요해졌다. 2026-10-12에 `02-domain-db.md`에 반영했다 (4.1, 4.5).

| 항목                       | 이유                                                                              |
| -------------------------- | --------------------------------------------------------------------------------- |
| 갱신 토큰 저장 테이블      | 로그아웃과 "쓸 때마다 바꾸기"를 하려면 서버가 어떤 토큰이 살아 있는지 알아야 한다 |
| `rooms.name` 검색용 인덱스 | `GET /rooms?q=`                                                                   |

## 13. 뒤 단계로 넘기는 것

| 항목                                                | 정하는 시점      |
| --------------------------------------------------- | ---------------- |
| 요청 횟수 제한의 정확한 숫자                        | 구조·기술 선택   |
| 서버가 여러 대일 때 실시간 메시지를 어떻게 전하는지 | 구조·기술 선택   |
| OpenAPI 파일로 옮기기                               | 언어가 정해진 뒤 |
