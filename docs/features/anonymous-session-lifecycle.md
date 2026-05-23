---
feature: 익명 sessionId 라이프사이클 (TTL 만료 + 사용자 회전 + 머지)
slug: anonymous-session-lifecycle
status: implementing
owner: @goohong
scope: infra
related_issues: [209, 238, 242, 243]
related_prs: [913, 924, 925, 933, 934, 936, 937, 957, 962, 965]
last_reviewed: 2026-05-24
---

# 익명 sessionId 라이프사이클 (TTL 만료 + 사용자 회전 + 머지)

## 1) 개요 (What / Why)

- ADR-0013 (`sessionid-ttl-rotation`) 이 익명 sessionId 의 TTL/회전/데이터 라이프사이클 정책을 단일 진실로 결정했다. 본 spec 은 그 정책을 v0.3 ~ v0.4 안에 코드로 구현하기 위한 **작업 분할 + DB 마이그레이션 + 관측성 + 스케줄러 운영 가이드**다.
- 대상 액터: 백엔드 (스케줄러/`SessionAuthGuard` 확장), 인프라 (systemd timer 또는 Spring `@Scheduled` 운영), 사용자 (v0.3 후반 fe "세션 초기화" UX).
- 본 spec 은 ADR-0013 의 §D-1~D-5 를 1:1 으로 구현 항목으로 매핑한다. 정책 결정이 필요한 항목은 모두 §8 오픈 질문이 아니라 ADR-0013 에서 닫혔다 — 본 spec 의 오픈 질문은 **구현 옵션** 수준에 한정.

## 2) 사용자 시나리오

- (S1) **자연 만료**: 사용자 A 가 2026-05-22 에 마지막으로 노래방 검색 후 6개월 + 1일 (181 일) 후 재방문 → 같은 sessionId 쿠키로 첫 요청 시 `SessionAuthGuard` 가 만료 판정 → 401 + "session expired" → fe 가 새 sessionId 발급 후 onboarding 재진입. 이전 voice-range/like/bookmark 는 cascade-delete 됨.
- (S2) **사용자 트리거 회전**: 사용자 B 가 가족과 공유한 디바이스에서 본인 데이터를 분리하고 싶다 → fe 의 "고급 설정 > 세션 초기화" 클릭 → `POST /api/v1/sessions/rotate` 호출 → 새 sessionId 발급 + 기존 sessionId 의 데이터 cascade-delete (또는 anonymize 옵션 선택) → fe 가 새 sessionId 로 쿠키 갱신.
- (S3) **v0.4 계정 머지**: 사용자 C 가 anonymous sessionId 로 like 50개 + voice-range snapshot 10개를 누적한 상태에서 v0.4 Google OAuth 로그인 → 백엔드가 해당 sessionId 의 모든 데이터를 `user_id` 로 owner 치환 + sessionId 를 revoke → fe 가 로그인 후 history 페이지에서 머지된 데이터 표시.
- (S4) **만료 batch 운영자 관측**: 운영자가 매일 자정 cascade-delete batch 실행 후 Discord webhook 으로 "오늘 만료된 sessionId N개, 삭제된 행 M개" 알림 수신 → Grafana 대시보드에서 `mobruji.session.expired{reason="ttl"}` 카운터 추이 확인.
- (S5) **신규 통합 클라이언트 / API 탐색자 discoverability** (rev 발견, 2026-05-23): 외부 개발자 D 가 `POST https://api.mobruji.app/api/v1/sessions` 로 "세션 생성" 호출을 시도 → 현재는 default Spring 404 (빈 body 또는 generic) → "API 가 어디서 sessionId 를 발급해 주는지" 알 수 없다. 본 spec §5-9 가 (a) discoverability 응답 + (b) README/API 문서 entry 점 + (c) 헤더 누락 401 hint 응답을 정한다. 결과: D 가 첫 호출 응답 body 만 보고 "client 가 UUIDv4 를 발급해서 `X-Session-Id` 헤더로 넣는다" 를 알 수 있다.

## 3) 요구사항

### 기능 요구사항

- [ ] **AnonymousSession 엔티티 신설** (§5-1): `sessionId` (PK, 외부 노출 식별자) + `firstSeenAt` + `lastSeenAt` + `revokedAt` (nullable) + `revokedReason` (enum: `TTL` / `USER_ROTATE` / `ACCOUNT_MERGE`).
- [ ] **`SessionAuthGuard` 만료 게이트 확장** (§5-2): 매 요청 진입 시 `AnonymousSession.lastSeenAt + 180일 > now()` 검증. 만료 시 **401 + body `{"error": "session expired"}`** 반환 (ADR-0011 의 다른 401 케이스와 본문으로 구분 — 응답 헤더 추가 검토는 §8 Q1).
- [ ] **`lastSeenAt` 갱신 정책**: 매 요청마다 DB write 는 부담이므로 **5분 윈도우 캐시** (in-memory `ConcurrentHashMap<sessionId, Instant>`) → 5분 경과 시 batch flush. 캐시 손실 허용 (만료 판정 정밀도가 5분 단위로 떨어지지만 180일 TTL 에서 무시 가능).
- [ ] **TTL 만료 batch** (§5-3): 매일 1회 (운영 시간 외, e.g., KST 04:00) `lastSeenAt + 180일 < now()` AND `revokedAt IS NULL` 인 sessionId 를 일괄 revoke + cascade-delete.
  - cascade-delete 대상: `voice_range`, `voice_range_snapshot`, `like`, `bookmark`, `recommendation`, `recommendation_result_entry` — 모두 FK on `sessionId` (혹은 의미상 FK).
  - 한 batch 당 최대 10,000 sessionId 처리 (초과 시 다음 날로 미룸 — 부하 분산).
- [ ] **사용자 회전 endpoint** (§5-4): `POST /api/v1/sessions/rotate` — body `{ "currentSessionId": "...", "dataMode": "DELETE" | "ANONYMIZE" }`. 헤더 `X-Session-Id` 와 body `currentSessionId` 일치 검증 (`SessionAuthGuard` 재사용). 응답 `{ "newSessionId": "..." }`. v0.3 P3 에 endpoint 만 노출, fe UI 는 v0.4 spec (#243) 에서 결정.
- [ ] **v0.4 계정 머지 endpoint** (§5-5): `POST /api/v1/sessions/merge-to-account` (v0.4 spec 에서 정식 명세). 본 spec 은 endpoint placeholder 와 머지 시 trigger 되는 데이터 이전 트랜잭션 골격만 정의.
- [ ] **관측성 카운터** (§5-6): `mobruji.session.expired{reason}`, `mobruji.session.rotated`, `mobruji.session.merged` 신설. observability-baseline.md §5-3 표 갱신 PR 동반.
- [ ] **만료 batch Discord 알림**: batch 1회 종료 시 `mobruji.session.expired` 증분 + 삭제된 row 합계를 `MOBRUJI_ALERT_WEBHOOK_URL` 로 통지 (sessionId 원문 미노출).
- [ ] **sessionId discoverability** (§5-9, rev 발견 2026-05-23): (1) `POST /api/v1/sessions` 와 `GET /api/v1/sessions` 경로에 명시적 405/410 hint 핸들러 — body `{"error": "session-id is client-generated", "hint": "generate a UUIDv4 client-side and send via X-Session-Id header. session paths are nested as /api/v1/sessions/{sessionId}/<resource>"}` + 응답에 `Link: </README#sessionid>` 헤더. (2) `SessionAuthGuard` 의 헤더 누락/blank 401 응답 body 를 동일 hint 로 확장 (현재는 `"missing session id"` reason 만). (3) `README.md` "API 사용 안내" 섹션 신설 + `docs/api/sessionid-discovery.md` 1페이지 cookbook (curl 예시 + Node/Python snippet). sessionId 원문은 hint 어디에도 노출 금지 (보안 §3 비기능 그대로).

### 비기능 요구사항

- **결정성**: TTL 180일 / cascade-delete / 회전 endpoint 모두 ADR-0013 단일 진실. 변경은 ADR-0013 갱신 후 본 spec 후속.
- **응답시간 영향**: `SessionAuthGuard` 만료 판정은 in-memory 캐시 hit 시 < 1ms, miss 시 DB select 1회 + < 5ms. p95 영향 추정 +3ms 이내. observability-baseline.md §5-4 p95 매트릭스에 영향 없도록 가드.
- **설정 외부화**: TTL 일수 (`mobruji.session.ttl-days`, default 180), batch cron (`mobruji.session.cleanup-cron`, default `0 0 4 * * *` KST), batch 최대 처리 건수 (`mobruji.session.cleanup-max-per-run`, default 10000) 모두 `application.yml` 외부화. 기본값 미설정 시 부트 fail-fast 가 아닌 default fallback 허용 (운영 fail-fast 는 webhook URL 같은 정말 중요한 것만).
- **관측성**: §5-6 카운터 + Grafana 대시보드 1개 panel (sessionId 수명 분포 — v0.4 후보, 본 spec 은 카운터까지만).
- **보안**: sessionId 원문은 로그/응답/webhook 어디에도 노출하지 않음 — ADR-0011 §Decision + 04-security-policy.md §3 + ADR-0013 §D-5. prefix 8 자 마스킹만 허용. 만료 응답 본문에 `expiredAt` 같은 시각 정보 미노출 (sessionId enumeration 방어).
- **트랜잭션 안전성**: cascade-delete 는 단일 트랜잭션 — sessionId 1개씩 처리. 다중 sessionId 를 한 트랜잭션에 묶으면 락 범위 확대 + rollback 부담. batch loop 안에서 sessionId 별 독립 트랜잭션.

## 4) 범위 / 비범위

### 포함

- AnonymousSession 엔티티 + Flyway 마이그레이션
- `SessionAuthGuard` 만료 게이트 확장 (ADR-0011 의 컴포넌트 재사용)
- TTL 만료 batch 스케줄러 + cascade-delete 트랜잭션 골격
- 사용자 회전 endpoint (`POST /api/v1/sessions/rotate`)
- 관측성 카운터 3종 + observability-baseline.md §5-3 표 갱신
- 만료 batch Discord 알림 (observability-baseline.md §5-6 알림 4 규칙에 1개 추가)
- 운영 런북 (batch 운영, 만료/회전 트러블슈팅, 관측 panel 가이드)

### 제외 (Out of Scope)

- **v0.4 계정 시스템 자체 (#243)**: 본 spec 은 anonymous-session 한정. 머지 endpoint 의 정식 명세는 v0.4 spec 에서.
- **opt-in anonymize 의 상세 구현**: ADR-0013 §D-3 의 `anonymous_session_aggregate` 별 테이블은 본 spec 범위 외 (v0.4 ML 추천 spec 의 학습 데이터 요구사항에 따라 결정).
- **데이터 백업/내보내기 UX**: 사용자가 만료 전에 자기 데이터를 내려받는 fe UX — v0.4 후보, 본 spec 범위 외.
- **sessionId 수명 분포 histogram**: ADR-0013 §C 의 "운영 데이터로 sessionId 수명 분포 측정" — 카운터만 본 spec 에서 도입, histogram 은 v0.4 후보.
- **HMAC 서명 sessionId / JWT 전환**: ADR-0013 §Alternatives (F) 거절. 정식 인증은 v0.4 계정 시스템.
- **다중 디바이스 sessionId 동기화 (cross-device)**: ADR-0011 §Alternatives 및 recommendation-history-and-feedback spec §8 Q4. 본 spec 은 동일 sessionId 가 여러 디바이스에서 공유되는 경우는 그대로 허용 (위조 가능성과 동일 트레이드오프).

## 5) 설계

### 5-1) 도메인 모델

신규 엔티티 1개:

```
AnonymousSession
├─ sessionId        : VARCHAR(64), PK
├─ firstSeenAt      : TIMESTAMP NOT NULL
├─ lastSeenAt       : TIMESTAMP NOT NULL, INDEX
├─ revokedAt        : TIMESTAMP NULL
└─ revokedReason    : VARCHAR(32) NULL (enum: TTL / USER_ROTATE / ACCOUNT_MERGE)
```

- 기존 엔티티 (`voice_range`, `voice_range_snapshot`, `like`, `bookmark`, `recommendation`, `recommendation_result_entry`) 의 `sessionId` 컬럼은 **FK 추가 없음** — soft reference. cascade-delete 는 application 로직에서 명시적 DELETE. 이유: ADR-0011 시점에 이미 FK 없이 운영 중이고, Flyway 마이그레이션으로 FK 추가 시 운영 락 부담 + revoke 후에도 데이터가 잠시 남아있을 수 있는 trace window 필요.
- `06-domain-model.md §4` 유비쿼터스 랭귀지 등재: `AnonymousSession`, `SessionRevocation`, `SessionRotation`, `AccountMerge`. 본 spec 머지 PR 에 동반 갱신.
- `06-domain-model.md §5` 엔티티 표에 `AnonymousSession` 행 추가.
- `06-domain-model.md §6` Mermaid ERD 에 `AnonymousSession` 노드 추가 (다른 엔티티의 sessionId 컬럼이 의미상 참조).

### 5-2) `SessionAuthGuard` 만료 게이트 확장

기존 (ADR-0011 / #244):
```
1. X-Session-Id 헤더 추출 → 누락/blank 401
2. path/body/query sessionId 와 헤더 상수시간 비교 → 불일치 401
3. 통과 → 다음 필터
```

확장 후 (본 spec):
```
1. X-Session-Id 헤더 추출 → 누락/blank 401
2. path/body/query sessionId 와 헤더 상수시간 비교 → 불일치 401
3. NEW: AnonymousSession.findBySessionId(headerSessionId) 조회
   ├─ 없음 → 401 (sessionId 가 한번도 등록 안 됨 — 위조 시도 또는 첫 호출 후 DB write 누락)
   ├─ revokedAt != null → 401 + body { error: "session revoked", reason: revokedReason }
   ├─ lastSeenAt + TTL < now() → 401 + body { error: "session expired" }
   └─ OK → in-memory 캐시에 lastSeenAt = now() 마킹 (5분 윈도우)
4. 통과 → 다음 필터
```

- 첫 호출 시 `AnonymousSession` row 가 없는 문제: §5-5 의 "session bootstrap" 처리 — 첫 호출 시 자동 생성 또는 명시적 `POST /api/v1/sessions/bootstrap` endpoint. §8 Q2 에서 결정.
- in-memory 캐시는 Spring Bean (`SessionActivityTracker`) 으로 분리. flush 정책: **per-request in-line flush** (5분 캐시 윈도우로 DB UPDATE 부담을 sessionId 당 5분에 1회로 제한). 별 트랜잭션(`REQUIRES_NEW`)은 `SessionActivityFlusher` 컴포넌트가 담당해 Spring AOP proxy self-invocation 문제를 회피한다 (rev follow-up #936). `@Scheduled` batch flush 대안은 채택하지 않음 — per-request in-line 이 race condition 없이 더 단순.
- **메모리 누수 방어 (PR #937 follow-up, 2026-05-24)**: in-memory 캐시 누적은 2 layer 로 방어한다.
  1. **revoke 시점 직접 evict**: `SessionRotationService#rotate` (사용자 트리거 회전, USER_ROTATE) 와 `AnonymousSessionTtlCleanup#runOnce` (TTL batch, TTL revoke) 가 `SessionActivityTracker#evict(sessionId)` 를 호출해 캐시에서 즉시 제거.
  2. **LRU 상한 fallback**: access-order `LinkedHashMap` + `synchronizedMap` 으로 `mobruji.session.activity-cache-max-size` (default 10,000) 도달 시 가장 오래된 entry 자동 evict. 위조/누락된 sessionId (revoke 경로 미경유) 의 비정상 누적 차단.
  - Caffeine 신규 의존성은 도입하지 않음 — JDK 만으로 메모리 안전 달성 가능 + `backend/build.gradle*` 보호 영역 변경 부담 회피. 운영 측정 후 capacity 부족이 잦아지면 ADR 로 Caffeine 재검토.
  - 캐시에서 빠진 sessionId 가 재요청해도 miss → flush → 재마킹으로 정합성 손실 없음. SessionAuthGuard 가 revoke 된 sessionId 를 DB 조회로 401 차단하므로 캐시 잔존이 보안 문제로 직결되지도 않는다 — 본 layer 는 OOM/heap 누수 방어 전용.

### 5-3) TTL 만료 batch 스케줄러

```java
@Scheduled(cron = "${mobruji.session.cleanup-cron:0 0 4 * * *}")
@Transactional(propagation = NOT_SUPPORTED) // 외부 트랜잭션, 각 sessionId 별 독립 tx
public void expireInactiveSessions() {
    final var cutoff = Instant.now().minus(ttlDays, DAYS);
    final var maxPerRun = props.cleanupMaxPerRun();
    final var sessionIds = anonymousSessionRepository
        .findIdsByLastSeenBeforeAndRevokedAtIsNull(cutoff, maxPerRun);

    var deletedRows = 0L;
    for (final var sessionId : sessionIds) {
        deletedRows += cascadeDeleteOne(sessionId); // 각자 @Transactional
    }

    meterRegistry.counter("mobruji.session.expired", "reason", "ttl")
        .increment(sessionIds.size());
    discordAlerter.notifyExpired(sessionIds.size(), deletedRows);
}
```

- `cascadeDeleteOne`: 한 sessionId 의 모든 데이터 DELETE → `AnonymousSession.revokedAt = now()` + `revokedReason = TTL` UPDATE. 단일 트랜잭션.
- batch loop 가 maxPerRun 도달 시 종료 — 다음 batch 가 나머지 처리. 부하 분산.

### 5-4) 사용자 회전 endpoint

```
POST /api/v1/sessions/rotate
Headers:
  X-Session-Id: <current>
Body:
  {
    "currentSessionId": "<current>",
    "dataMode": "DELETE" | "ANONYMIZE"   // ANONYMIZE 는 v0.4 후속, v0.3 은 DELETE only
  }
Response 200:
  {
    "newSessionId": "<freshly generated>"
  }
Errors:
  401 — SessionAuthGuard 일반 케이스 (헤더 누락 / 불일치 / 만료 / revoked)
  400 — dataMode unsupported (v0.3 에서 ANONYMIZE 요청 시)
```

- 새 sessionId 발급은 UUIDv4. fe 가 응답 받은 후 쿠키/LocalStorage 갱신.
- ANONYMIZE 모드는 v0.4 까지 400 반환. v0.4 에서 별 PR 로 활성화.

### 5-5) v0.4 계정 머지 (placeholder)

본 spec 은 endpoint placeholder + 데이터 이전 트랜잭션 골격만:

```
POST /api/v1/sessions/merge-to-account     (v0.4 spec 에서 정식 명세)
Body:
  {
    "sessionId": "...",
    "userId": "..."   // 이미 인증된 user
  }
```

- 트랜잭션: `voice_range.sessionId → null + userId 컬럼 신설 + userId 값 set` (또는 dual write — v0.4 spec 에서 결정). 마지막에 `AnonymousSession.revokedAt = now() + revokedReason = ACCOUNT_MERGE`.
- v0.4 spec (#243) 머지 시 본 spec 의 §5-5 가 그 spec 으로 이관 또는 cross-reference.

### 5-5-1) Session bootstrap

- 옵션 (a): **자동 부트스트랩** — 첫 요청 시 `AnonymousSession` row 가 없으면 즉시 생성 후 검증 통과 (sessionId 가 헤더에 있으면 우선 신뢰).
- 옵션 (b): **명시적 endpoint** — `POST /api/v1/sessions/bootstrap` 호출 후에야 정상 sessionId 로 인정.
- §8 Q2 에서 결정. 1차 추천: (a) — fe 워크플로우 변경 최소화. 단점: 위조 sessionId 가 자동 등록되어 무한 누적 위험 → batch 가 만료 처리하므로 180일 후 자동 삭제, 즉시 위협은 ADR-0011 의 위조 sessionId 위협과 동일.

### 5-6) 관측성 카운터

observability-baseline.md §5-3 표에 신규 행 3개:

| Metric | Type | 라벨 | 의미 | 신설/기존 |
|---|---|---|---|---|
| `mobruji.session.expired` | counter | `reason` (`ttl`/`user_rotate`/`account_merge`) | sessionId revoke 1건 | 신설 (본 spec) |
| `mobruji.session.rotated` | counter | — | 사용자 트리거 회전 1건 | 신설 (본 spec) |
| `mobruji.session.merged` | counter | — | v0.4 계정 머지 1건 | 신설 (본 spec) |

라벨 화이트리스트 (observability-baseline.md §5-7) 의 `reason` enum 에 `ttl`, `user_rotate`, `account_merge` 3 값 추가 — 사전 정의 enum 룰 준수.

알림 규칙 1개 추가 (observability-baseline.md §5-6):

| 규칙 | 트리거 | 채널 | 우선순위 |
|---|---|---|---|
| TTL batch 종료 통지 | `mobruji.session.expired{reason="ttl"}` 일별 batch 종료 시 | Discord webhook | P3 (정보성) |

### 5-7) DB 마이그레이션 (Flyway)

```
V<next>__create_anonymous_session.sql
  CREATE TABLE anonymous_session (
    session_id        VARCHAR(64) NOT NULL PRIMARY KEY,
    first_seen_at     TIMESTAMP   NOT NULL,
    last_seen_at      TIMESTAMP   NOT NULL,
    revoked_at        TIMESTAMP   NULL,
    revoked_reason    VARCHAR(32) NULL,
    INDEX idx_last_seen_at (last_seen_at),
    INDEX idx_revoked_at (revoked_at)
  );

V<next+1>__backfill_anonymous_session.sql
  -- 기존 sessionId 들 (like/bookmark/voice_range/recommendation 에서 distinct) 을
  -- AnonymousSession 으로 backfill. first_seen_at = MIN(createdAt), last_seen_at = MAX(createdAt).
  INSERT INTO anonymous_session (session_id, first_seen_at, last_seen_at)
  SELECT s.session_id, MIN(s.created_at), MAX(s.created_at)
  FROM (
    SELECT session_id, created_at FROM voice_range_snapshot
    UNION ALL
    SELECT session_id, created_at FROM `like`
    UNION ALL
    SELECT session_id, created_at FROM bookmark
    UNION ALL
    SELECT session_id, created_at FROM recommendation
  ) s
  GROUP BY s.session_id;
```

- backfill 마이그레이션은 운영 데이터 양에 따라 분리 PR 권장 (PR D 별 분리).

### 5-8) `application.yml` 변경

```yaml
mobruji:
  session:
    ttl-days: ${MOBRUJI_SESSION_TTL_DAYS:180}
    cleanup-cron: ${MOBRUJI_SESSION_CLEANUP_CRON:0 0 4 * * *}
    cleanup-max-per-run: ${MOBRUJI_SESSION_CLEANUP_MAX:10000}
    activity-flush-interval: ${MOBRUJI_SESSION_ACTIVITY_FLUSH_INTERVAL:PT5M}
```

- `application.yml` 은 CLAUDE.md §4 의 보호 영역 — 본 spec 의 PR B 가 `needs-human-review` 라벨.

### 5-9) sessionId discoverability (rev 발견 2026-05-23)

**문제** (rev 사이클 발견):

외부 통합 클라이언트 / API 탐색자가 `POST /api/v1/sessions` 또는 `GET /api/v1/sessions` 로 "세션 생성/조회" 를 시도하면 default Spring 404 가 돌아간다. 응답에 hint 가 없어 호출자는 다음 단서를 얻을 수 없다:

1. sessionId 가 **client-side 발급** (UUIDv4) 이라는 점 — ADR-0011 §Decision + `web/store/session.ts` `generateSessionId()`.
2. 모든 session-bound endpoint 가 **`/api/v1/sessions/{sessionId}/<resource>`** 형태로 nested 라는 점.
3. 인증은 **`X-Session-Id` HTTP 헤더** 로 수행한다는 점 — `SessionAuthGuard`.
4. 만료 / 회전 endpoint (`POST /api/v1/sessions/rotate`) 는 본 spec PR 5 머지 후에야 노출된다는 점.

ADR-0011 §Decision 은 "sessionId 는 client 가 발급" 을 단일 진실로 박았지만, **이 규약을 외부에 공개적으로 발견 가능 (discoverable) 하게 만드는 응답/문서 entry point 가 없다**. 본 절은 그 entry point 를 박제한다.

**비고**: 본 절은 ADR-0011 의 client-side 발급 정책을 **재검토하지 않는다**. server-side 발급으로 갈아탈지 여부는 §8 Q6 으로 분리 — Q6 (a) 유지 가 default 권장.

#### 5-9-1) `/api/v1/sessions` 루트 hint 핸들러 (be 측)

신규 컨트롤러 1개 (또는 `RestControllerAdvice` 1개) 가 `/api/v1/sessions` (path 끝, sessionId 없음) 의 GET/POST/PUT/PATCH/DELETE 5 메서드 모두 가로채서 hint body 를 반환한다.

```
GET|POST|... /api/v1/sessions
GET|POST|... /api/v1/sessions/
```

**응답 (HTTP 405 Method Not Allowed)**:

```http
HTTP/1.1 405 Method Not Allowed
Allow: <empty>
Content-Type: application/json
Link: </README.md#sessionid>; rel="help"

{
  "error": "session-id is client-generated",
  "hint": "Generate a UUIDv4 client-side and send via the X-Session-Id HTTP header. Session-bound endpoints are nested as /api/v1/sessions/{sessionId}/<resource> (e.g., /api/v1/sessions/{sessionId}/voice-range-history).",
  "docs": "https://github.com/goohong/mobruji/blob/main/README.md#sessionid"
}
```

설계 메모:

- 405 를 선택한 이유: 404 는 "리소스 없음" 으로 generic 검색 봇 트래픽과 섞인다. 405 는 "이 경로는 존재하지만 해당 메서드는 지원 안 함" 의미. `Allow: <empty>` 로 "여기에 메서드 호출 자체가 의미 없다" 를 명시.
- `Link: </README.md#sessionid>; rel="help"` 헤더 — RFC 5988 표준. curl `-I` 로도 hint 발견 가능.
- 본 spec PR 5 머지 후 `POST /api/v1/sessions/rotate` 가 정상 endpoint 가 되면, `/api/v1/sessions/rotate` 만 별 핸들러로 정상 처리하고 base path (`/api/v1/sessions`) 는 계속 405 hint.
- sessionId 원문은 hint body / header 어디에도 들어가지 않는다 (그 자체로 client 발급이라 backend 가 아는 값이 아니지만 명시적으로 정책 박제).

#### 5-9-2) `SessionAuthGuard` 401 응답 body 보강

현재 (PR #244, ADR-0011 §Decision):

```http
HTTP/1.1 401 Unauthorized
{}   # 또는 Spring default error body
```

확장 후 (본 PR 5):

```http
HTTP/1.1 401 Unauthorized
Link: </README.md#sessionid>; rel="help"
Content-Type: application/json

{
  "error": "session id required",
  "hint": "Send your client-generated UUIDv4 via the X-Session-Id HTTP header. See README.md#sessionid for details."
}
```

- 위 §5-2 의 만료/revoke 401 body (`{"error": "session expired"}` / `{"error": "session revoked"}`) 와 **error 값으로 구분** — body schema 일관.
- header 누락/blank 외에도 path-header 불일치 케이스에 동일 hint 적용 가능 (단, 불일치는 attack signature 일 수 있으므로 hint 를 최소화 — `{"error": "session id mismatch"}` 만 반환).
- 본 변경은 PR 3 (가드 확장) 에 합류 (별 PR 분리하면 가드 코드 두 번 수정).

#### 5-9-3) `README.md` + `docs/api/sessionid-discovery.md`

**README.md 신설 섹션** (`## API 사용 안내` → `### sessionId`, 약 30 줄):

```md
### sessionId

mobruji 의 모든 사용자 데이터 (음역대 / 좋아요 / 북마크 / 추천 히스토리) 는
**client-generated UUIDv4 sessionId** 로 식별된다. 서버는 sessionId 를 발급하지 않는다.

```bash
# 1. client 가 UUIDv4 발급 (예: bash + uuidgen)
SESSION_ID=$(uuidgen)

# 2. 모든 session-bound endpoint 호출 시 `X-Session-Id` 헤더로 전달
curl -H "X-Session-Id: $SESSION_ID" \
  https://api.mobruji.app/api/v1/sessions/$SESSION_ID/voice-range-history
```

- sessionId 는 **URL path** (`/api/v1/sessions/{sessionId}/...`) 와 **`X-Session-Id` 헤더** 둘 다 동일 값으로 보내야 한다 — `SessionAuthGuard` 가 상수시간 비교.
- `POST /api/v1/sessions` 같은 "세션 생성" endpoint 는 **존재하지 않는다** — client 가 직접 UUIDv4 를 발급한다.
- TTL / 만료 / 회전 정책은 `docs/features/anonymous-session-lifecycle.md` 참조.
```

**`docs/api/sessionid-discovery.md`** (1 페이지 cookbook, 신규 디렉토리 `docs/api/` 안):

- 위 README 내용 확장 + Node.js (`crypto.randomUUID()`), Python (`uuid.uuid4()`), curl 예시 각 5~10 줄.
- 만료/회전 endpoint (PR 5 머지 후) 사용 흐름.
- 보안 룰 (sessionId 를 로그/스크린샷에 노출 금지) — `04-security-policy.md` cross-reference.

#### 5-9-4) 본 spec PR 분할 영향

discoverability 작업은 **2개 PR 로 쪼개진다**:

- **PR 8 (be, docs)** — 신규: `/api/v1/sessions` 루트 hint 핸들러 (§5-9-1) + README.md `### sessionId` 섹션 (§5-9-3) + `docs/api/sessionid-discovery.md` 신설. 본 spec PR 3 (가드 확장) 머지 후 또는 병행. 분량 S (≤ 80 LOC + docs).
- PR 3 (be) — `SessionAuthGuard` 401 body 보강 (§5-9-2) 을 기존 §5-2 만료 게이트 확장과 같은 PR 에 합류. 별 PR 분리하면 가드 두 번 수정.

§6 작업 분할 표에 PR 8 추가, PR 3 의 acceptance 에 §5-9-2 항목 추가.

#### 5-9-5) 비기능 요구사항 영향

- **응답시간**: 405 hint 핸들러는 `RestControllerAdvice` 또는 dedicated controller — request 처리 < 1ms, p95 영향 무. observability-baseline.md §5-4 매트릭스 영향 없음.
- **결정성**: hint 본문은 i18n 안 함 (영어 단일). 향후 다국어 시 별 ADR.
- **관측성**: 405 hint 응답에 대해 별 카운터 신설 하지 않음 (Spring 의 default `http.server.requests{status="405"}` Micrometer 메트릭으로 충분, observability-baseline.md §5-3 RED 자동 수집 표).
- **보안**: hint body 에 sessionId / admin token / DB 스키마 / 내부 path 그 어떤 PII 도 노출 금지. `04-security-policy.md` §3 그대로.

## 6) 작업 분할 (예상 PR 리스트)

- [x] **PR 1 (현 PR, plan 33)**: ADR-0013 + 본 spec(`anonymous-session-lifecycle.md`) + voice-range-progress / recommendation-history-and-feedback cross-reference 갱신. **본 PR**.
- [x] **PR 2 (be)**: `AnonymousSession` 엔티티 + repository + Flyway V<next> + V<next+1> (backfill) + `application.yml` 환경변수. 보호 영역 변경(`application.yml` + Flyway) → `needs-human-review` 라벨. (#913)
- [ ] **PR 3 (be)**: `SessionAuthGuard` 만료/revoke 게이트 확장 + `SessionActivityTracker` (in-memory 캐시 + 5분 flush). 기존 ADR-0011 컴포넌트 확장. (#924 진행 중)
- [x] **PR 4 (be)**: TTL 만료 batch (`@Scheduled` + cascade-delete 트랜잭션 + Discord 알림). 관측성 카운터 신설 + observability-baseline.md §5-3 / §5-6 / §5-7 표 갱신 같이. (#913, `AnonymousSessionTtlCleanup` 골격)
- [x] **PR 5 (be)**: `POST /api/v1/sessions/rotate` endpoint + session bootstrap (§5-5-1 Q2 결정 따라 옵션 (a) 자동 또는 (b) endpoint). (#913, `SessionRotationService` + Controller)
- [ ] **PR 6 (be)**: Flyway V9 backfill 마이그레이션 — 기존 sessionId 들 (like/bookmark/voice_range/recommendation 에서 distinct) 을 `AnonymousSession` 으로 backfill (§5-7 V<next+1>).
- [ ] **PR 7 (be, infra)**: 만료 batch Discord 알림 + observability-baseline.md §5-3/§5-6/§5-7 표 갱신 (관측성 카운터 3종 + 라벨 화이트리스트 + 알림 규칙). (#925 진행 중)
- [ ] **PR 8 (be, docs)**: §5-9 sessionId discoverability — `/api/v1/sessions` 루트 405 hint 핸들러 + `README.md` `### sessionId` 섹션 + `docs/api/sessionid-discovery.md` 신설. 단독 PR 가능 (PR 3/5 의존 없음). rev 발견 (2026-05-23) 기반 fast-track.
- [ ] **PR 9 (plan)**: v0.4 계정 시스템 spec (#243) 머지 시 본 spec 의 §5-5 placeholder 를 그 spec 으로 이관 + 본 spec `last_reviewed` 갱신.
- [ ] **PR 10 (fe, v0.3 후반 또는 v0.4)**: "고급 설정 > 세션 초기화" UX 노출 + 만료 시 onboarding redirect 처리.

## 7) 테스트 전략

- **단위**:
  - `SessionAuthGuard` 만료/revoke 분기 (각 401 케이스).
  - `SessionActivityTracker` 5분 윈도우 캐시 동작 (캐시 hit/miss/flush).
  - cascade-delete 트랜잭션의 모든 테이블 DELETE 검증.
- **통합**:
  - Flyway V<next+1> backfill 마이그레이션이 기존 sessionId 를 `AnonymousSession` 으로 정확히 이전.
  - TTL batch 가 만료 sessionId 만 처리 (활성 sessionId 보호).
- **E2E (RestAssured)**:
  - `POST /api/v1/sessions/rotate` 성공 케이스 1건 — 새 sessionId 발급 + 기존 sessionId 후속 호출 시 401 revoked.
  - 만료 sessionId 로 voice-range-history 호출 시 401 + body `{"error": "session expired"}`.
- **부하**:
  - TTL batch 가 10,000 sessionId 처리 시 cascade-delete 트랜잭션 합 < 5분.
  - `SessionAuthGuard` 만료 판정 p95 < 5ms (`SimpleMeterRegistry` 측정).
- **discoverability (PR 8)**:
  - `/api/v1/sessions` 5 메서드 (GET/POST/PUT/PATCH/DELETE) 모두 405 + hint body + `Link` 헤더 — RestAssured E2E 1건 (status=405, body.error/hint/docs 존재, `Link` 헤더 포함).
  - `/api/v1/sessions/` (trailing slash) 동일 405.
  - 보안 grep: hint body / 헤더 어디에도 sessionId 원문 / admin token / 내부 path 노출 없음.
  - 401 응답 (SessionAuthGuard) 의 body 가 §5-9-2 schema 와 일치.

## 8) 오픈 질문

| # | 질문 | 선택지 | 담당/기한 |
|---|---|---|---|
| Q1 | 만료 401 응답을 다른 401(헤더 누락/불일치)과 응답 헤더로 구분? | (a) `X-Session-Status: expired` 헤더 추가 / (b) body 만으로 구분 (현재 default) | @goohong / PR 3 |
| Q2 | Session bootstrap 옵션 | (a) 자동 부트스트랩 (첫 요청 시 자동 등록) / (b) 명시적 endpoint (`POST /api/v1/sessions/bootstrap` 호출 필수) | @goohong / PR 3 |
| Q3 | `lastSeenAt` 갱신 캐시 윈도우 | (a) 5분 (default) / (b) 1분 (정밀도 우선) / (c) 환경변수만 두고 default 5분 | @goohong / PR 3 |
| Q4 | backfill 마이그레이션 V<next+1> 을 PR 2 와 분리? | (a) 같은 PR (운영 데이터 양 적으면) / (b) 분리 PR (운영 락 부담 측정 후) | @goohong / PR 2 |
| Q5 | 회전 endpoint 의 ANONYMIZE 모드를 v0.3 에 활성화? | (a) v0.4 까지 400 (default) / (b) v0.3 후반에 별 PR 로 활성화 | @goohong / v0.4 진입 시 |
| Q6 | sessionId 발급 주체 (rev 발견 후 재검토) | (a) **client 발급 유지** (ADR-0011 §Decision, default 권장) / (b) server 발급으로 전환 (`POST /api/v1/sessions` 정식 endpoint 추가, ADR-0011 §Decision 갱신 + ADR 신설) | @goohong / PR 8 시작 전 |
| Q7 | 405 hint vs 404 hint vs 200 catalog | (a) **405 + Allow: empty + Link 헤더** (default 권장, §5-9-1) / (b) 404 + same body / (c) 200 + API catalog JSON (HATEOAS-lite) | @goohong / PR 8 |
| Q8 | `docs/api/` 디렉토리 신설 적절성 | (a) `docs/api/sessionid-discovery.md` 1 페이지로 시작 (default) / (b) `docs/features/` 또는 README 단일 섹션으로 충분, `docs/api/` 신설 보류 | @goohong / PR 8 |

## 9) 결정 로그

- **2026-05-22 (plan 33, 본 PR)**: 초안 작성 (status=draft). ADR-0013 의 §D-1~D-5 를 1:1 구현 항목으로 매핑. 7개 PR 로 분할 (엔티티/마이그레이션 → 가드 확장 → batch → 회전 endpoint → v0.4 spec 이관 → fe UX). 관측성 카운터 3종 신설 → observability-baseline.md §5-3 / §5-6 / §5-7 표 갱신 동반 필요. 첫 호출 시 AnonymousSession bootstrap 정책은 Q2 (PR 3 결정).
- **2026-05-23 (plan, 본 PR)**: **§5-9 sessionId discoverability 추가** (rev 발견 — 외부 API 탐색자가 `POST /api/v1/sessions` 호출 시 default 404 라 client-side UUID 발급 규약을 알 수 없음). 해소 방안 3건: (1) `/api/v1/sessions` 루트 405 + hint body + `Link` 헤더, (2) `SessionAuthGuard` 401 응답 body 보강 (hint 추가), (3) `README.md` `### sessionId` 섹션 + `docs/api/sessionid-discovery.md` cookbook. PR 8 신설 (be + docs), PR 3 에 (2) 합류. §3 기능 요구사항 1개 추가, §5-9 신설, §6 PR 표 PR 8 추가, §8 Q6/Q7/Q8 신설. ADR-0011 §Decision (client 발급) 은 재검토하지 않음 — Q6 (a) 유지 default.
- **2026-05-24 (be, F2)**: PR #913 머지 — PR 2 (`AnonymousSession` 엔티티 + V8 migration) + PR 4 (`AnonymousSessionTtlCleanup` 골격) + PR 5 (`SessionRotationService` + Controller) 동시 반영. spec frontmatter `status: draft` → `implementing` (docs/features/README.md §5 라이프사이클 룰), `related_prs: [913, 924, 925]` 보강, `last_reviewed: 2026-05-24`. §6 PR 표 재정렬 (V9 backfill 을 PR 6 으로 분리, Discord 알림 + 관측성 표 갱신을 PR 7 으로 분리, v0.4 spec 이관/fe UX 를 PR 9/10 으로 뒤로 이동). PR 3 (#924) / PR 7 (#925) 동시 진행 중. PR 6/8/9/10 미착수.
- **2026-05-24 (be, #936)**: PR #934 (PR 3 fast-track) rev follow-up bundle. §5-2 flush 정책 drift 봉인 — 초안의 "5분마다 `@Scheduled` batch flush" 가 실제 구현은 "per-request in-line flush + 5분 캐시 윈도우" 였음. spec 문장을 구현 일치로 갱신 (race condition 없는 더 단순한 방식이라 채택 유지). 함께 (1) `SessionActivityTracker` self-invocation 해소 — `SessionActivityFlusher` 별 컴포넌트로 분리해 `@Transactional(REQUIRES_NEW)` AOP proxy 가 실효, (2) `SessionDataCascadeDeleter` javadoc broken link 수정 (`com.mobruji.auth` → `com.mobruji.user.application`), (3) `flushOne` catch 블록 `e.getMessage()` → `e.getClass().getSimpleName()` (JpaSystemException SQL 본문 내 sessionId 노출 차단, 04-security-policy.md).
- **2026-05-24 (be, PR #937 follow-up)**: §5-2 `SessionActivityTracker` 메모리 누수 방어 2 layer 도입. (1) revoke 시점 직접 evict — `SessionRotationService#rotate` 와 `AnonymousSessionTtlCleanup#runOnce` 가 `evict(sessionId)` 호출. (2) LRU 상한 fallback — `ConcurrentHashMap` → access-order `LinkedHashMap`(`synchronizedMap` 래핑) 전환 + `removeEldestEntry` override 로 `mobruji.session.activity-cache-max-size` (default 10,000) 도달 시 가장 오래된 entry 자동 evict. Caffeine 신규 의존성은 도입하지 않음 — `backend/build.gradle*` 보호 영역 변경 부담 회피, JDK 만으로 메모리 안전 달성 가능. 단위 테스트: `SessionActivityTrackerTest` 에 evict 4건 + LRU 2건 추가, `SessionRotationServiceTest` 신설 (evict 호출 가드), `AnonymousSessionTtlCleanupIntegrationTest` 에 cache evict 회귀 가드 1건 추가.
