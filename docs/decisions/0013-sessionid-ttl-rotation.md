---
id: 0013
title: 익명 sessionId TTL · 회전 · 데이터 라이프사이클 정책
status: accepted
date: 2026-05-22
deciders: [@goohong]
---

# 0013. 익명 sessionId TTL · 회전 · 데이터 라이프사이클 정책

## Context
v0.2 ~ v0.3 의 모든 user-facing endpoint 가 **익명 sessionId** 위에서 동작한다 — voice-range 측정·snapshot 시계열(#231/#233), recommendation 요청·히스토리(#237), like/bookmark(#236 묶음). ADR-0011 (`session-bound-auth-policy`) 이 path/body sessionId 와 `X-Session-Id` 헤더 일치를 강제하는 1차 게이트를 형식화했지만, 해당 ADR §Consequences 부정적 항목과 References 가 명시했듯 **TTL/회전 정책은 미정**이다 (#209).

미정 상태가 남기는 문제:

1. **저장 부담**: sessionId 별 voice-range snapshot / Like / Bookmark / Recommendation 결과가 무기한 누적 — 한 sessionId 당 100~1000 행이 5년 누적되면 DB 비용 + p95 회귀.
2. **보안 표면**: sessionId 가 위조 가능한 자칭 식별자이므로 **클라이언트가 한번 노출된 sessionId 는 영구 유효**. 분실/공유 시 평생 추적·역추적 가능.
3. **GDPR/개인정보보호 정합성**: 사용자가 "내 데이터 잊혀질 권리" 를 행사할 통로가 없다 — 익명이라 본인 인증도 불가.
4. **v0.4 계정 시스템(#243) 의 머지 정책 결정 의존**: TTL/회전이 정해져야 anonymous-session ↔ account 머지 윈도우/cascade 규칙을 결정 가능.
5. **#238 게이트 우회 잔존 위험**: 만료 sessionId 로도 무한 접근 가능 (ADR-0011 §Consequences 부정 #2).

본 ADR 은 위 5개 문제에 대한 v0.3 ~ v0.4 단일 진실을 영속한다. 구현 가이드 (스케줄러/마이그레이션/관측성) 는 후속 spec `docs/features/anonymous-session-lifecycle.md` 로 분리한다.

## Decision

### D-1) TTL — **180일 inactive 만료** (sliding window)
- "inactive" = 해당 sessionId 로 들어온 **모든 endpoint 호출** (read/write 무관) 의 마지막 시각 기준.
- sliding window: 호출이 있을 때마다 `last_seen_at` 갱신 → 마지막 호출 후 180일 경과 시 만료.
- 절대 만료(absolute expiry) 는 두지 않음 — 활성 사용자가 강제로 끊기는 UX 비용을 피한다. 위조 sessionId 위협은 D-2 회전 + 정식 인증(v0.4) 으로 단계적 해소.
- 만료 판정 주체: 매 요청 진입 시 `SessionAuthGuard` 가 `AnonymousSession.last_seen_at` 을 확인. 만료된 sessionId 는 **401 Unauthorized + "session expired"** 응답 (ADR-0011 의 다른 401 케이스와 응답 헤더로 구분 — 본문에 사유 노출은 §D-5 PII 룰 준수 범위에서).
- 180일 근거: (1) Korea Personal Information Protection Act (PIPA) 의 "장기 미접속 분리 보관" 권고가 365일이라 그 절반인 180일을 conservative default 로 채택, (2) 노래방 사용 패턴 — 분기·반기 단위 사용이 일반적이므로 90일은 너무 짧다는 운영 판단, (3) 만료 시 데이터 라이프사이클 결정(§D-3) 이 cascade-delete 이므로 너무 짧으면 사용자 분노 → 너무 길면 저장 비용. 180 이 1차 합리적 default.

### D-2) 회전 — **사용자 트리거 only, 정기 회전 없음 (v0.3)**
- v0.3 에서 **정기 회전(N일마다 새 sessionId)** 은 도입하지 않는다. 정당화: (a) 위조 sessionId 의 본질적 해결은 정식 인증(v0.4) 이며, 정기 회전은 회피책일 뿐 위협 모델을 본질적으로 바꾸지 않는다, (b) 회전마다 §D-3 데이터 이전 로직이 발동되어 운영 복잡도 증가, (c) 정식 계정 시스템 도입(#243) 이 v0.4 의 핵심이라 v0.3 에서 정기 회전을 도입하면 v0.4 진입 시 두 번 마이그레이션.
- 대신 **사용자 트리거 회전** 1개만 도입: fe 의 "세션 초기화" 설정에서 `POST /api/v1/sessions/rotate` 호출 → 서버가 새 sessionId 발급, 기존 sessionId 의 데이터를 §D-3 룰에 따라 cascade-delete 또는 새 sessionId 로 이전 (사용자 선택). fe UI 는 v0.4 계정 시스템 spec(#243) 과 같이 결정 — v0.3 에서는 endpoint 만 노출, fe 노출은 v0.4.
- v0.4 정식 계정 시스템 도입 시 본 결정을 superseded — anonymous-session ↔ account 머지 정책이 회전을 흡수.

### D-3) 만료/회전 시 데이터 라이프사이클 — **cascade-delete (default) + 사용자 선택 anonymize 옵션**
- **default = cascade-delete**: sessionId 만료/회전 시 해당 sessionId 로 영속된 모든 데이터를 삭제한다 — `voice_range`, `voice_range_snapshot`, `like`, `bookmark`, `recommendation`, `recommendation_result_entry` (FK on `sessionId`). soft-delete 가 아니라 **물리 삭제** — 익명 데이터의 잔존 가치가 낮고 저장 부담이 크다.
- **opt-in anonymize**: 통계/추천 모델 학습용으로 sessionId 를 익명 해시 (e.g., HMAC-SHA256 + 단일 salt) 로 치환한 데이터는 별 테이블 (`anonymous_session_aggregate`) 로 옮긴다. 본 옵션은 운영 합의 후 별 PR 로 추가 — 본 ADR 은 prescriptive 정책만 선언.
- voice-range-progress spec §8 Q2, recommendation-history-and-feedback spec §8 (TTL 관련) 의 오픈 질문을 본 결정으로 닫는다.

### D-4) v0.4 계정 시스템(#243) 머지 정합성
- 사용자가 v0.4 계정으로 로그인 시 **자동 머지** 1회만 수행:
  - 현재 보유한 sessionId 의 모든 데이터를 `user_id` 로 owner 치환 (sessionId → userId 컬럼 이름 변경 또는 dual write — v0.4 spec 에서 결정).
  - 머지 완료 후 해당 sessionId 는 **revoke** (만료 처리). 동일 sessionId 를 익명으로 재사용하면 401 + "session merged into account".
- **충돌 시나리오**: 한 user 가 여러 sessionId (여러 디바이스/익명 세션) 를 계정에 연결할 때 → 각 sessionId 의 데이터를 모두 user 로 머지, like/bookmark 의 `(sessionId, songId)` unique 제약은 `(user_id, songId)` 로 변경. 중복은 oldest createdAt 유지.
- **계정 탈퇴 시 데이터 처리**: §D-3 cascade-delete 룰을 user 차원에서 동일 적용. v0.4 spec 에서 별도 결정 — 본 ADR 은 anonymous-session 한정.

#### D-4 보강 (plan 38) — 머지 트리거 시점 + 비회원/회원 흐름 정책 합의

본 보강은 `docs/features/anonymous-to-account-conversion.md` (plan 38) 의 사용자 정책 결정을 본 ADR 의 단일 진실에 반영한 것이다. 머지 알고리즘 단계별 상세는 그 spec §3-D 를 참조하되, **정책 결정** 자체는 본 ADR 이 영속한다.

- **(1) 머지 트리거 시점 — 로그인 콜백 동기 처리**: OAuth 콜백 (`GET /api/v1/auth/oauth/{provider}/callback`) 또는 이메일 로그인 (`POST /api/v1/auth/email/login`) 응답을 반환하기 **전에** 머지 트랜잭션을 동기 실행한다. 비동기 큐 NOT 권장 — fe 가 로그인 직후 like/bookmark 보류 액션을 재호출하려면 머지가 끝나 있어야 unique 충돌 처리가 결정적으로 동작한다.
- **(2) 머지 대상 sessionId 의 식별**: 클라이언트가 로그인 콜백 요청 시 `X-Session-Id` 헤더 (또는 동등한 cookie) 로 현재 비회원 sessionId 를 함께 전송. 헤더 없으면 머지 스킵 (그냥 로그인 처리). sessionId 가 이미 revoked 면 머지 스킵.
- **(3) 비회원/회원 흐름 1순위 정책**: **비회원 흐름이 default**. 음역 측정·추천·history 조회는 비회원 그대로 가능. **회원 전환 트리거는 좋아요/북마크 클릭 시점 (모달)에 한정**. 그 외 시점의 회원 강제 (popup/interstitial/redirect) 는 금지. like/bookmark endpoint 자체는 v0.4 부터 회원 전용으로 게이트 (fe 가 모달로 호출 차단).
- **(4) 머지 트랜잭션 실패 처리**: 로그인 자체는 성공 처리 (인증 토큰 발급) + 머지만 실패. `mobruji.session.merge.failed` 카운터 증분 (본 ADR §D-5 표 보강 — 본 ADR 의 D-5 카운터 표에 `failed` 추가). 수동 재시도 endpoint (`POST /api/v1/sessions/merge-to-account`) 는 v0.4 PR G 에서 노출.
- **(5) sessionId 의 owner 컬럼 모델**: dual column (sessionId nullable + userId nullable, 둘 중 하나 NOT NULL CHECK) 을 default 로 권장. 이유: backward compatible, 마이그레이션 부담 최소, ownerType enum 도입 시의 application 분기 복잡도 회피. 최종 결정은 v0.4 PR B (User 엔티티 + 마이그레이션) 에서.
- **(6) 머지 시 owner 치환 대상 테이블**: `voice_range`, `voice_range_snapshot`, `like`, `bookmark`, `recommendation`, `recommendation_result_entry` 6 개. 새 테이블이 sessionId 컬럼을 가질 때 본 목록 자동 확장은 아님 — 새 테이블 도입 시 같은 PR 에서 머지 대상 명시.
- **(7) 비회원 사용 한도**: **한도 없음** (v0.4). 데이터 누적 후 v0.5+ 에서 재검토.

### D-5) 관측성 + 로그 정책
- 만료/회전/머지 이벤트 카운터 (`observability-baseline.md §5-3` 표 갱신 필요):
  - `mobruji.session.expired` (counter, 라벨 `reason=ttl|user_rotate|account_merge`)
  - `mobruji.session.rotated` (counter)
  - `mobruji.session.merged` (counter — v0.4 계정 머지 시)
- sessionId 원문은 로그/예외/응답 body 에 절대 노출하지 않는다 — ADR-0011 §Decision 룰 그대로 (prefix 8 자 + 마스킹). 만료 응답 본문은 `{"error": "session expired"}` 만 (sessionId 의 만료 시각/생성 시각 미노출 — 외부에서 sessionId enumeration 시도 방어).

## Consequences

### 긍정적
- **저장 부담 상한 확보**: sessionId 별 데이터가 180일 후 자동 삭제 → DB 크기/p95 회귀 위험 1차 차단.
- **GDPR/PIPA 정합성 1차 충족**: "장기 미접속 분리 보관" 권고를 cascade-delete 로 더 보수적으로 충족 (분리 보관 대신 삭제).
- **#238 게이트 우회 잔존 위험 해소**: 만료 sessionId 는 가드에서 401 거부 → 무한 접근 차단.
- **v0.4 계정 시스템 진입 명확성**: anonymous → account 머지 정책이 ADR 로 영속화 → v0.4 spec 작성 시 결정 트리 단순화.
- **사용자 자율성**: "세션 초기화" UX 가 v0.4 전에도 제공 가능 (트리거 endpoint 만이라도).

### 부정적
- **180일 후 데이터 손실**: 활성 사용자가 6개월 만에 돌아왔을 때 voice-range 시계열/like/bookmark 가 모두 사라진 상태로 재시작. UX 비용. → 완화: v0.4 계정 시스템 도입 후 계정 연결로 영속화. v0.3 사용자에게는 fe 의 "데이터 백업/내보내기" UX 가 별 spec 으로 필요할 수 있다 (현 spec 범위 아님).
- **만료 판정 비용**: 매 요청 진입 시 `last_seen_at` 확인 + 갱신 — DB write 부담. → 완화: `last_seen_at` 갱신은 매 요청이 아닌 **N분 (e.g., 5분) 윈도우** 안에서는 in-memory 캐시로 미루고 batch flush (구현 spec 에서 결정).
- **만료 스케줄러 운영 부담**: 매일 1회 cascade-delete batch 가 필요 → systemd timer 또는 Spring `@Scheduled` 로 운영. → 완화: AudioAnalysisScheduledBackfill (#226) 과 같은 패턴이라 운영 부담 점진 흡수.
- **회전 endpoint 의 1차 노출 시점 UX 미정**: v0.3 에서 endpoint 만 노출하고 fe UI 는 v0.4 계정 시스템과 같이 결정 → endpoint 가 노출되지만 사용자가 호출할 경로가 없는 inconsistency. → 완화: v0.3 후반에 fe 가 "고급 설정" 메뉴에 옵션 1개로 노출 (v0.4 spec 일부로 처리 권장).
- **opt-in anonymize 미구현**: 추천 모델 학습용 익명 aggregate 가 누적되지 않음 → ML 추천(#216 후속) 의 학습 데이터 부족. v0.4 안에 별 PR 로 추가 필요.

## Alternatives (considered)

- **(A) 영구 sessionId (TTL 없음)** — 가장 단순. 거절 사유: §Context 1~3 (저장 부담 / 보안 표면 / GDPR) 모두 미해결. 미니 서비스 시점에는 동작하나 사용자 수 증가 시 회귀 누적. v0.4 계정 시스템 도입 후에도 익명 sessionId 가 무한 누적되는 문제 잔존.
- **(B) 30일 inactive 만료** — 보수적. 거절 사유: 노래방 사용 패턴이 분기/반기 단위라 한 달이면 활성 사용자도 자주 만료 → UX 비용 과대. PIPA 권고(365일) 의 1/12 라 정당화 약함.
- **(C) 90일 inactive 만료** — 절충. 보류 사유: 180일 default 와 비교해 저장 부담 추가 절감(~50%) 은 있으나, v0.3 의 사용자 트래픽이 미미한 단계에서 실익 약함. 운영 데이터로 sessionId 수명 분포 측정 후 (`mobruji.session.lifetime` histogram, v0.4 후속) 재조정. **본 ADR 의 1차 default 는 180일이고, 운영 측정 후 90일로 조정하는 PR 은 본 ADR 의 갱신만으로 가능** — 코드 변경은 환경변수 1개.
- **(D) 절대 만료 (absolute expiry, 예: 발급 후 365일)** — RFC 6265 cookie 표준 호환. 거절 사유: 활성 사용자도 1년 후 강제 끊김 → UX 비용. sliding window (180d inactive) 가 활성 사용자 보호와 저장 부담 사이 균형 우수.
- **(E) 정기 회전 (e.g., 30일마다 새 sessionId 자동 발급)** — 보안 강화. 거절 사유: §D-2 의 (a)~(c). 정식 인증(v0.4) 이 본질적 해결이고, 정기 회전은 회피책. v0.3 진입 시 두 번 마이그레이션 부담.
- **(F) HMAC 서명된 sessionId (JWT-like)** — 위조 방지. 거절 사유: ADR-0011 §Alternatives (C) 와 동일 — 클라가 sessionId 평문을 이미 갖고 있어 비밀화 효과 없음. 서명 검증으로 위조는 막을 수 있으나 "다른 사람의 sessionId 를 안다면 우회 가능" 문제는 동일.
- **(G) cascade-delete 대신 soft-delete (`deleted_at` 컬럼)** — 사용자 복구 가능성. 거절 사유: 익명 데이터의 복구 가치 낮음 (사용자가 누구인지 식별 불가). 저장 부담 절감 의도와 모순. opt-in anonymize (§D-3) 가 학습용 보존의 대체 경로.
- **(H) 만료 = anonymize-only (삭제 안 함)** — 보존 우선. 거절 사유: PIPA 권고는 "분리 보관" 인데 익명화도 분리 보관의 한 형태이므로 명목상 충족. 그러나 실질적으로 anonymize 후에도 sessionId 단위 그룹핑이 유지되면 행동 패턴 + 다른 데이터셋과의 cross-reference 로 재식별 위험. cascade-delete 가 보수적 default 로 더 안전.

## References
- 이슈 #209 (본 ADR 의 트리거)
- ADR-0011 (`docs/decisions/0011-session-bound-auth-policy.md`) — 본 ADR 이 §Consequences 부정 #2 (만료 sessionId 무한 접근) 를 닫음. ADR-0011 References 가 본 ADR 을 가리키도록 같이 갱신.
- Feature Spec: `docs/features/voice-range-progress.md §8 Q2` (cascade vs anonymize) — 본 ADR §D-3 으로 닫힘
- Feature Spec: `docs/features/recommendation-history-and-feedback.md` — 본 ADR 룰에 따른 cascade-delete 대상 (FK on sessionId) 명시 필요
- 신규 Feature Spec: `docs/features/anonymous-session-lifecycle.md` (본 ADR 의 구현 가이드 — TTL 평가 스케줄러, 회전 endpoint, 관측성 카운터, 마이그레이션)
- 신규 Feature Spec: `docs/features/anonymous-to-account-conversion.md` (plan 38) — 본 ADR §D-4 의 머지 알고리즘 단계별 상세화 + 비회원/회원 흐름 매트릭스 + 로그인 모달 UX. 본 ADR §D-4 보강은 그 spec 의 결정을 단일 진실로 영속.
- 후속: v0.4 계정 시스템 spec (#243) — 본 ADR §D-4 + `anonymous-to-account-conversion.md` 가 결정 트리 영속. v0.4 정식 인증 ADR (Spring Security / JWT 결정) 머지 시 본 ADR superseded 후보.
- 관련 정책: `docs/ai-harness/04-security-policy.md` §3, CLAUDE.md §4
- 관측성: `docs/features/observability-baseline.md §5-3` 표에 `mobruji.session.expired|rotated|merged` 신설 (본 ADR 머지 후 후속 PR 에서 표 갱신)
- 법적 근거: PIPA (개인정보보호법) "장기 미접속 분리 보관" 권고 (365일) — 본 ADR 은 보수적으로 180일 + cascade-delete

## Changelog
- **2026-05-22 (plan 33, 본 PR)**: 초안 작성 (status=accepted). TTL=180일 inactive sliding window, 정기 회전 없음 (사용자 트리거만), cascade-delete default + opt-in anonymize, v0.4 계정 머지 시 sessionId revoke. ADR-0011 의 만료 sessionId 우회 위험 (§Consequences 부정 #2) 을 본 ADR 의 D-1 만료 게이트가 닫음.
- **2026-05-22 (plan 38)**: §D-4 보강 7항목 추가 — 머지 트리거 시점(로그인 콜백 동기), 비회원/회원 흐름 1순위 정책(비회원 default + 좋아요/북마크만 회원 전환 트리거), 머지 실패 처리(로그인 성공 + 머지만 실패 + 재시도 endpoint), owner 컬럼 모델(dual column default 권장), 머지 대상 6개 테이블 명시, 비회원 사용 한도 없음(v0.4). 머지 알고리즘 단계별 상세는 신규 spec `docs/features/anonymous-to-account-conversion.md` 로 분리하되 정책 결정 자체는 본 ADR 이 단일 진실로 보유. References 에 신규 spec 추가.
