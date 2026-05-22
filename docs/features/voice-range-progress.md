---
feature: 음역대 진행 추적 (Voice Range Progress)
slug: voice-range-progress
status: draft
owner: @goohong
scope: voice
related_issues: [220, 171, 209]
related_prs: [221]
last_reviewed: 2026-05-21
---

# 음역대 진행 추적 (Voice Range Progress)

## 1) 개요 (What / Why)
- 사용자가 측정한 음역대(`voice_range`)는 현재 `sessionId`별 1행으로 **덮어쓰기**된다. 즉 시간이 흘러도 "처음 측정 → 지금"의 변화가 남지 않는다.
- v0.3 P1에서는 음역대 측정/입력 결과를 **시계열 스냅샷**으로 영속화하여, 사용자가 자신의 발전(예: 1주 후 +2 반음 확장)을 확인할 수 있게 한다. Yousician의 진행도 화면에서 영감을 얻었다.
- 대상 액터: (1) 익명 세션 사용자 — 본인 음역 변화 회고 / (2) 어드민(추후) — 사용자 코호트 발전 추세 분석.
- 현재 `web/history` 페이지(#171)는 추천 entry 시 음역 snapshot을 **클라이언트 LocalStorage에만** 보관 중 → 기기/세션 갱신 시 유실. 이번 작업으로 backend 권위 데이터로 통일한다.

## 2) 사용자 시나리오
1. **재측정 → 발전 인지**
   - 사용자가 1차 측정으로 lowMidi=48, highMidi=64 기록 → 추천을 받고 종료.
   - 1주일 뒤 재방문, 같은 `sessionId` 쿠키 유지 상태로 음역 재측정 → lowMidi=48, highMidi=66.
   - history 페이지에서 "최근 측정값"과 "1주 전 측정값"이 함께 보이고, **고음 +2 반음(C5 → D5)** 라는 발전이 표기된다.
2. **추천 시 자동 snapshot**
   - 사용자가 voice-range-input 화면에서 값을 갱신하고 추천을 요청 → 백엔드는 `voice_range`를 upsert함과 동시에 `voice_range_snapshot`에 **insert-only** 1행을 추가한다.
3. **history 동기화**
   - 사용자가 다른 기기에서 같은 `sessionId`로 접속 → fe history 페이지가 GET `/api/v1/sessions/{id}/voice-range-history`를 호출하여 클라 LocalStorage 없이도 시계열을 복원한다.

## 3) 요구사항
### 기능 요구사항
- [ ] `VoiceRangeSnapshot` 엔티티/테이블 신설.
  - 필드: `id` (PK, auto), `sessionId` (FK 의미상, 인덱스), `lowMidi` (int), `highMidi` (int), `sourceMethod` (enum: `MANUAL` / `AUTO_MIC` / `AUTO_AGGREGATE` — `voice-range-auto-measurement` spec과 정합), `measuredAt` (timestamp, default NOW)
- [ ] `voice_range` 테이블 변경(insert/update) 발생 시 동일 트랜잭션에서 `voice_range_snapshot`에 **insert-only** 1행 추가. 덮어쓰기 금지.
- [ ] `GET /api/v1/sessions/{id}/voice-range-history` 신규 엔드포인트.
  - 응답: `measuredAt` 오름차순 시계열 배열 `voiceRangeSnapshotResponses`.
  - 각 항목 필드: `lowMidi`, `highMidi`, `sourceMethod`, `measuredAt`.
- [ ] `web/history` 페이지가 위 API를 호출하여 LocalStorage 대신 backend 데이터를 source-of-truth로 사용. LocalStorage는 **오프라인 fallback**으로만 유지.
- [ ] fe 표시: 첫 측정 대비 최신 측정의 lowMidi/highMidi delta(반음 단위) 노출.
- [x] **session-bound 인증** (§5-2-1): `GET /sessions/{id}/voice-range-history` 호출 시 path sessionId 와 `X-Session-Id` 헤더 일치 검증. 누락/blank/불일치 모두 401. 사양 출처: ADR-0011, 구현: `SessionAuthGuard` (#244, closes #238).

### 비기능 요구사항
- 결정성: 본 기능은 추천 결과에 영향을 주지 **않는다** (read-side만 확장). `recommendation-algorithm-v1` 결정성 회귀 테스트는 기존과 동일하게 통과해야 한다.
- 성능: `GET .../voice-range-history` p95 100ms 이내 (snapshot N≤200 가정, `sessionId` 인덱스).
- 보안: 음역 원문은 로그 비노출(`04-security-policy` 준수). 응답에는 MIDI 정수만 포함.
- 관측성: snapshot insert 시 `voice.range.snapshot.inserted` 카운터 +1 (`10-observability` 룰).

## 4) 범위 / 비범위
### 포함
- `voice_range_snapshot` 영속화 (insert-only)
- `voice_range` upsert 시 자동 snapshot 추가 (서비스 계층)
- 시계열 조회 API 1개
- fe history 페이지의 backend 동기화

### 제외 (Out of Scope)
- ML 기반 발전 트렌드/예측 (v0.4+ 별도 spec)
- 어드민 대시보드/코호트 분석 UI (v0.4+)
- 알림(예: "음역 향상!") push/email
- 사용자 간 비교(리더보드 등)
- snapshot 기반 추천 가중치 조정 (결정성 영향 방지)

## 5) 설계
### 5-1) 도메인 모델
- 신규 엔티티: `VoiceRangeSnapshot` (aggregate root: `VoiceRange` 와 동일한 voice context).
- `voice-range-auto-measurement` spec의 `sourceMethod` enum을 재사용 — 새 enum 도입 금지. 정합 변경이 필요하면 `06-domain-model.md §4 유비쿼터스 랭귀지`를 먼저 갱신.
- `voice_range`는 "현재 값"을, `voice_range_snapshot`은 "변경 이력"을 담당하는 CQRS-라이트 분리.

### 5-2) API 엔드포인트
| Method | Path | 설명 | 인증 | Req | Res |
|---|---|---|---|---|---|
| GET | /api/v1/sessions/{id}/voice-range-history | 세션의 음역 측정 시계열 조회 | **session-bound (§5-2-1)** — `X-Session-Id` 헤더 = path sessionId | path: sessionId, header: `X-Session-Id` | `VoiceRangeHistoryResponse { voiceRangeSnapshotResponses: [...] }` |

#### 5-2-1) 인증/인가 (session-bound)

본 endpoint 는 **session-bound endpoint** 분류에 속한다. 정책 출처는 **ADR-0011 — Session-Bound Endpoint 인증 정책**, 구현은 `com.mobruji.auth.SessionAuthGuard` (#244, closes #238).

- 호출자는 path 의 `sessionId` 와 동일한 sessionId 를 **호출자 자신이 보유함**을 증명해야 한다 (= "본인 sessionId 만 본인 history 조회 가능").
- 증명 방식:
  - 클라이언트는 `X-Session-Id` 헤더(또는 동등한 cookie — fe 결정에 위임)로 자신의 sessionId 를 함께 전달한다.
  - 서버는 path `{sessionId}` 와 헤더 sessionId 를 **상수시간 비교(`MessageDigest.isEqual`)** 로 일치 여부 판정.
- 상태 코드 매핑 (ADR-0011 §Decision 에 따라 401 통일):
  - `X-Session-Id` 헤더 누락 / blank → **401 Unauthorized** ("missing session id")
  - 헤더와 path sessionId 불일치 → **401 Unauthorized** ("session id mismatch") — 403 이 아닌 이유는 ADR-0011 §Alternatives (D)
  - 정상 → **200 OK** (해당 sessionId 의 snapshot 이 0건이면 빈 배열 반환, 404 아님)
- 로그 정책: sessionId 원문은 로그/예외 메시지/응답에 노출하지 않는다. 디버깅이 필요하면 sessionId 의 prefix 8 자만 노출. `04-security-policy.md §3` 준수.
- admin 인증(#229 — `X-Admin-Token`)과는 **별 트랙**이다. session-bound 는 "본인 자신만", admin 은 "운영자만" 으로 의도가 다르다. 한 endpoint 가 두 인증을 동시에 요구하지 않는다.

### 5-3) 외부 연동
- 없음. 내부 DB만 사용.

### 5-4) 데이터 흐름
```
[사용자 측정/입력]
  → POST /api/v1/sessions/{id}/voice-range  (기존)
    → VoiceRangeService.upsert(...)
      ├─ voice_range UPSERT (기존)
      └─ voice_range_snapshot INSERT (신규, 같은 @Transactional)
[추후 조회]
  → GET /api/v1/sessions/{id}/voice-range-history
    → VoiceRangeSnapshotRepository.findBySessionIdOrderByMeasuredAtAsc
```

### 5-5) DB 마이그레이션
- Flyway `V4__create_voice_range_snapshot.sql`
  - `voice_range_snapshot (id BIGINT PK AUTO_INCREMENT, session_id VARCHAR NOT NULL, low_midi INT NOT NULL, high_midi INT NOT NULL, source_method VARCHAR NOT NULL, measured_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, INDEX idx_session_measured (session_id, measured_at))`
- `voice_range` 테이블은 스키마 변경 없음.
- `06-domain-model.md §5 엔티티` 및 `§6 Mermaid ERD`를 같은 PR(A)에서 갱신.

### 5-6) 프론트엔드 화면
- 라우트: `/history` (기존 #171)
- 신규 API 호출 모듈: `web/src/lib/api/voiceRangeHistory.ts`
- React Query key: `['voice-range-history', sessionId]`
- 표시: 라인차트 또는 표(최소 표 1차) + 첫 측정 대비 delta(반음).

## 6) 작업 분할 (예상 PR 리스트)
- [ ] PR A — backend: `VoiceRangeSnapshot` 엔티티/Repo + Flyway V4 + 도메인 문서 갱신 (보호 영역: migration → `needs-human-review`)
- [ ] PR B — backend: `VoiceRangeService` upsert 흐름에 snapshot insert 통합 + 통합 테스트
- [ ] PR C — backend: `GET /voice-range-history` 컨트롤러/DTO + E2E(RestAssured)
- [ ] PR D — web: `/history` 페이지 backend 동기화 + delta 표시 + 테스트
- [x] **PR E** — backend (#244, closes #238): `GET /voice-range-history` 에 session-bound 인증 게이트 적용 (§5-2-1, ADR-0011). `SessionAuthGuard` 신설 및 recommendation-history 와 공유.

## 7) 테스트 전략
- 단위: `VoiceRangeService` upsert 시 snapshot 1행이 같은 트랜잭션에 insert되는지(rollback 케이스 포함).
- 통합: `VoiceRangeSnapshotRepository` 정렬/필터 (`measuredAt ASC`).
- E2E (RestAssured, **신규 엔드포인트 필수**):
  - given: 같은 sessionId로 측정 3회 (서로 다른 lowMidi/highMidi)
  - when: `GET .../voice-range-history`
  - then: 3행, `measuredAt` 오름차순, 각 행 MIDI 일치.
- 결정성 회귀: `recommendation-algorithm-v1` 골든 픽스처 테스트가 그대로 통과해야 함 (snapshot 추가가 추천 입력에 영향 없음 검증).
- fe: history 페이지 React Testing Library — API mock 응답으로 시계열/ delta 렌더 검증.
- **인증 E2E (§5-2-1, ADR-0011 — 구현됨 #244)**:
  - given: sessionId=`A` 로 voice-range 1회 측정 + sessionId=`B` 로 0회 측정
  - case 1: `GET /sessions/A/voice-range-history` + `X-Session-Id: A` → 200, 1행
  - case 2: `GET /sessions/A/voice-range-history` + `X-Session-Id: B` → 401, 응답에 sessionId 원문 미노출
  - case 3: `GET /sessions/A/voice-range-history` (헤더 없음) → 401
  - case 4: `GET /sessions/B/voice-range-history` + `X-Session-Id: B` → 200, 빈 배열 (404 아님)

## 8) 오픈 질문
> 구현 전에 답이 나와야 하는 것들. 해소되면 §9 결정 로그로 이동.

| # | 질문 | 선택지 | 담당/기한 |
|---|---|---|---|
| Q1 | snapshot 보관 기간 | (a) 영구 보관 / (b) 1년 후 파티션/삭제 잡 | ~~@goohong / 2026-06-30~~ → **closed by ADR-0013 (plan 33)**: sessionId TTL=180일 inactive sliding window + cascade-delete 가 단일 진실. snapshot 도 sessionId 만료 시 함께 삭제. |
| ~~Q2~~ | ~~`sessionId` TTL(issue #209)과의 호환 — 세션 만료 시 snapshot도 함께 삭제할지~~ | ~~(a) 함께 삭제 (cascade) / (b) snapshot은 익명화 후 보존~~ | **closed 2026-05-22 by ADR-0013**: (a) cascade-delete default. (b) opt-in anonymize 는 v0.4 후속 별 PR. 구현 가이드: `docs/features/anonymous-session-lifecycle.md`. |

## 9) 결정 로그
> 연대기 순. "YYYY-MM-DD: 결정 / 이유 / 출처(PR 번호 등)"

- 2026-05-21: 초안 작성 (status=draft) — PR #221, closes #220.
- 2026-05-22 (be 27, #244 closes #238): history endpoint 에 `X-Session-Id` 헤더 인증 게이트 추가. `SessionAuthGuard` 가 path sessionId 와 헤더 값을 상수시간 비교, 누락/blank/불일치 모두 401.
- 2026-05-22 (plan 27, ADR-0011 영속화): session-bound 인증 정책을 ADR-0011 로 형식화 (정책 출처를 spec 본문에서 ADR 로 이동). §5-2-1 에 상세 절 추가, 상태 코드 매핑 401 통일(#244 구현 정합), admin 트랙(#229)과 별 트랙임을 명시. 후속 endpoint(like/bookmark 등) 도 본 ADR 패턴 강제.
- 2026-05-22 (plan 33, ADR-0013 cross-ref): Q1/Q2 (snapshot 보관 기간 + sessionId TTL 정합성) 를 ADR-0013 (`sessionid-ttl-rotation`) 로 닫음. snapshot 은 sessionId TTL 180일 inactive 만료 시 cascade-delete (default). opt-in anonymize 는 v0.4 후속. 구현 가이드 spec: `docs/features/anonymous-session-lifecycle.md`.
