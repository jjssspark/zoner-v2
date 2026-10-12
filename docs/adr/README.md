# ADR 목록

구조·기술을 고를 때 왜 그렇게 했는지 적은 기록이다. 한 번 정한 걸 나중에 "왜 이랬지" 하지 않으려고 둔다.
전체 그림은 `../04-architecture.md`에 있다.

| 번호 | 제목 | 상태 | 날짜 |
|---|---|---|---|
| [001](001-java-spring-boot.md) | 백엔드는 Java + Spring Boot | 채택 | 2026-10-12 |
| [002](002-oracle-free-vm.md) | Oracle Cloud 무료 VM 한 대에 docker compose | 채택 | 2026-10-12 |
| [003](003-no-redis-yet.md) | Redis는 서버가 두 대가 될 때까지 안 둔다 | 채택 | 2026-10-12 |
| [004](004-raw-websocket.md) | WebSocket은 STOMP 없이 그대로 쓴다 | 채택 | 2026-10-12 |
| [005](005-cloudflare-r2.md) | 파일은 Cloudflare R2에 둔다 | 채택 | 2026-10-12 |
| [006](006-sync-badge-award.md) | 뱃지는 분 기록과 같은 트랜잭션에서 준다 | 채택 | 2026-10-12 |

형식: 맥락 → 선택지 → 결정 → 이유 → 감수하는 것 → 다시 볼 조건
