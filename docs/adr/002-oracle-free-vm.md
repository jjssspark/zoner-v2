# ADR-002 Oracle Cloud 무료 VM 한 대에 docker compose

날짜 2026-10-12 · 상태 채택 · 관련 ADR-001, ADR-003

## 맥락

조건 N-09: 무료 또는 한 달 몇 만 원 안에서 실제로 배포한다.
ADR-001에서 Spring Boot를 골랐다. JVM은 기동만으로 300~500MB를 쓴다. 여기에 Postgres까지 붙어야 한다.
WebSocket을 쓰므로 요청마다 깨어나는 서버리스 방식은 안 맞는다.

## 선택지

| 안 | 내용 | 걸리는 점 |
|---|---|---|
| A. Oracle Cloud Always Free VM | ARM 4 OCPU / 24GB, 200GB 디스크, 기한 없음 | 가입 때 카드 인증이 까다롭다. 안 쓰는 VM을 회수하는 정책이 있다 |
| B. Supabase Postgres + Railway/Fly.io 유료 | DB는 무료, 서버만 월 5~10달러 | 예약 작업·Redis가 필요해지면 돈이 더 든다. 운영을 직접 안 해 본다 |
| C. Render/Fly.io 무료 티어 | 설정이 제일 쉽다 | 메모리 256~512MB. JVM이 안 뜨거나 뜨자마자 죽는다 |
| D. AWS 프리 티어 | 익숙한 사람이 많다 | 12개월 뒤 과금. t2.micro 1GB로 Postgres까지는 빠듯 |
| E. 로컬 docker로만 개발, 배포는 나중에 | 지금 결정을 미룸 | 나중에 배포 환경에 맞춰 설정을 다시 손본다 |

## 결정

A. `VM.Standard.A1.Flex` 4 OCPU / 24GB 한 대. Ubuntu 24.04. docker compose로 Postgres, Spring Boot, Caddy를 띄운다.

## 이유

무료 중에 JVM + Postgres를 여유 있게 올릴 수 있는 건 A뿐이다. 24GB면 부하 테스트(N-04)도 같은 VM에서 돌려볼 수 있다.
그리고 "배포와 운영을 직접 한다"가 이 프로젝트에서 배우고 싶은 것 중 하나다. B는 편하지만 ssh로 들어가서 로그를 보고 디스크를 확인하는 경험이 없다.
E는 미루는 것이지 결정이 아니다. 배포 환경은 `.env` 구성, 시간대, HTTPS 같은 걸 처음부터 정해 두는 게 나중에 덜 고친다.

Caddy를 nginx 대신 고른 건 HTTPS 인증서를 알아서 받고 갱신해서다. 웹캠은 https에서만 열리므로 인증서가 필수인데, certbot 설정을 따로 안 해도 된다.

## 감수하는 것

- Oracle은 Always Free 인스턴스가 7일간 CPU 10% 미만 같은 기준으로 놀면 회수할 수 있다. 계정을 Pay As You Go로 바꾸면 회수 대상에서 빠지고, 무료 한도 안이면 여전히 청구가 없다. 가입 뒤 바로 바꾼다
- ARM이다. Docker 이미지는 `arm64`로 빌드한다. 쓰는 라이브러리(Postgres, Caddy, Eclipse Temurin)는 전부 arm64 이미지가 있다
- 가입이 실패하는 사람이 꽤 있다. 그럴 땐 B로 간다. 코드는 docker compose 기준이라 옮기는 데 큰 수정이 없다
- 한 대라서 서버가 죽으면 서비스가 멈춘다. 학습용 프로젝트에서 받아들일 수 있는 수준이다

## 다시 볼 조건

가입이 안 될 때(→ B), 또는 한 대로 N-04 부하를 못 받을 때(→ ADR-003의 두 대 구성).
