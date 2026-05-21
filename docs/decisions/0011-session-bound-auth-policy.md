---
id: 0011
title: Session-Bound Endpoint 인증 정책
status: accepted
date: 2026-05-22
deciders: [@goohong]
---

# 0011. Session-Bound Endpoint 인증 정책

## Context
v0.3 P1 진입 시점에 익명 sessionId 기반 endpoint 가 늘었다 — `GET /sessions/{id}/voice-range-history` (#233), `GET /sessions/{id}/recommendation-history` (#237), 후속 `POST/DELETE /likes` `/bookmarks` (#236 묶음). 이들 모두 path 또는 body 의 `sessionId` 만으로 누구나 타인의 음역/추천/좋아요 데이터를 조회·수정할 수 있는 공개 endpoint 로 노출됐다 (rev 16, #238). admin endpoint 는 #229 에서 `X-Admin-Token` 게이트로 해결했지만, "본인 sessionId 의 데이터만 본인이" 라는 의도는 별도 정책이 필요하다. Spring Security 정식 도입 전에 1~2 PR 로 끝낼 수 있는 최소 게이트가 요구된다. be 27 이 #244 로 history 두 endpoint 에 1차 게이트를 구현했으며, 본 ADR 은 그 정책을 형식화 + 후속 endpoint(like/bookmark 등) 도 같은 패턴으로 강제하기 위한 영속 결정이다.

## Decision
"session-bound endpoint" 분류를 새로 정의하고, 이 분류에 속하는 모든 endpoint 는 다음 규칙을 따른다.

- 클라이언트는 매 요청에 `X-Session-Id` HTTP 헤더(또는 동등한 cookie — fe 결정에 위임) 로 자신의 sessionId 를 동봉한다.
- 서버는 path 의 `{sessionId}` (또는 request body 의 `sessionId`) 와 헤더 값을 `MessageDigest.isEqual` 로 **상수시간 비교** 하여 일치하지 않으면 거부한다.
- 응답 코드: 헤더 누락/blank → **401 Unauthorized** ("missing session id") / 불일치 → **401 Unauthorized** ("session id mismatch") / 본인 sessionId 의 리소스가 0건이라도 **200 OK + 빈 배열** 을 반환한다 (404 아님 — 리소스 존재 여부를 외부에 누설하지 않기 위함). 401/403 통일 근거는 §Alternatives (D).
- sessionId 원문은 로그/예외 메시지/응답에 절대 노출하지 않으며 (`04-security-policy.md §3`), 디버깅이 필요하면 prefix 8 자만 마스킹된 형태로 노출한다.
- 구현은 공용 컴포넌트 `com.mobruji.auth.SessionAuthGuard` (#244 신설) 하나로 모든 session-bound endpoint 가 공유한다. admin 트랙(#229 `com.mobruji.admin.AdminTokenVerifier`)과 동일한 패턴(검증기) 으로 두되 **별 트랙**으로 둔다 — 한 endpoint 가 admin 과 session 두 인증을 동시에 요구하지 않는다.
- 본 결정은 Spring Security 정식 도입 전까지의 **임시 게이트**다. Security 도입 시 새 ADR 로 본 결정을 superseded 처리한다.

### Decision — 적용 범위 (HTTP method 별 매핑 규칙)

본 ADR 은 GET endpoint 에서 출발했지만, **HTTP method 와 무관하게** 다음 조건을 만족하는 endpoint 는 모두 session-bound 분류에 속한다 — POST/PUT/PATCH/DELETE 포함.

> **분류 기준**: endpoint 가 path 또는 request body 의 `sessionId` 만으로 특정 sessionId 의 데이터 (음역 / 추천 히스토리 / 좋아요 / 북마크 / voice-range snapshot 등) 를 조회·생성·수정·삭제할 수 있다면 session-bound 다.

- **GET `/api/v1/sessions/{sessionId}/...`** — path `{sessionId}` vs `X-Session-Id` 헤더 일치. (#244 적용 완료)
- **POST/PUT/PATCH** with body `sessionId` — request body 의 `sessionId` (DTO 필드) vs `X-Session-Id` 헤더 일치. **예: `POST /api/v1/likes` `POST /api/v1/bookmarks` (body 의 `{sessionId, songId}`).**
- **DELETE** with body `sessionId` 또는 path `sessionId` — 동일 규칙 (body 우선, body 없으면 path).
- **DELETE `/api/v1/{resource}/{songId}?sessionId=...`** 형태 (query string sessionId) — query 의 `sessionId` vs 헤더 일치. (path 의 두번째 segment 가 sessionId 가 아닌 경우, request body 가 없으면 query 로 fallback.)

이 규칙은 like/bookmark POST/DELETE endpoint 에도 그대로 적용된다 — 후속 PR (recommendation-history-and-feedback spec §6 PR F) 가 동일 `SessionAuthGuard` 컴포넌트를 재사용해 1 라인 호출로 게이트한다.

> **반대 사례**: `POST /api/v1/recommendations` 는 body 에 `sessionId` 가 있어 익명 sessionId 단위 추천 요청을 만들지만, 결과 곡 리스트는 sessionId 의 기존 데이터를 외부에 누설하지 않는다 (요청·응답이 같은 요청 내에서만 결합). **단**, recommendation 결과가 `Recommendation` 엔티티에 persistence 되어 후속 `GET /sessions/{id}/recommendation-history` 로 조회되는 흐름이라면 — `POST /recommendations` 도 body sessionId 의 진위성을 검증해야 타인의 sessionId 로 위조 데이터를 inject 할 수 없다. 본 ADR 은 **persistence 가 발생하는 POST** 는 session-bound 로 강제하기로 한다 — recommendation-create 도 후속 spec 에서 적용 범위에 포함시킬지 결정 (현재 P2, `recommendation-history-and-feedback.md §8 Q7` 신설 후보).

## Consequences
### 긍정적
- 타 sessionId 의 음역/추천/좋아요 데이터를 외부에서 임의 조회·수정할 수 없게 됨 (#238 해소).
- admin (`X-Admin-Token`) 과 session (`X-Session-Id`) 의 의도/트랙이 분리되어 endpoint 별 인증 정책이 명확.
- 단일 `SessionAuthGuard` 로 후속 session-bound endpoint 도 1 라인 호출만으로 보호.

### 부정적
- `X-Session-Id` 는 client 가 자칭하는 값이라 **위조 가능** — 다른 사람의 sessionId 를 안다면 우회 가능. 본질적 해결은 서버 발급 토큰(Spring Security) 도입이며, 본 결정은 "URL 추측만으로는 막힌다" 수준의 1차 방어선이다.
- sessionId TTL/회전 (#209) 이 미정인 상태라, 만료된 sessionId 로 무한 접근 가능. #209 결정 후 본 ADR 갱신 또는 후속 ADR 필요.
- fe 모든 호출에 헤더 부착 작업 필요 (axios interceptor 1회 등록으로 처리 권장).

## Alternatives (considered)
- (A) Spring Security 정식 도입 — 본 결정 시점에는 범위가 과대 (의존성/세션 정책/cookie 설계 동시 결정 필요). v0.3 P3 또는 v0.4 로 미룸.
- (B) admin 토큰과 동일한 `X-Admin-Token` 으로 통합 — 의도(본인만 vs 운영자만) 가 달라 권한 모델이 뒤섞임. 거절.
- (C) URL 의 sessionId 자체를 비밀로 취급 (HMAC 서명) — 클라가 sessionId 평문을 이미 갖고 있으므로 비밀화 효과 없음.
- (D) 헤더 누락=401, 불일치=403 분리 vs 401 통일 — RFC 7235 의미상으로는 분리(401=인증 자체 부재, 403=인증은 됐는데 권한 없음) 가 정합. 그러나 본 게이트는 헤더 값이 자칭 sessionId 이므로 "다른 sessionId 의 데이터 존재 여부" 자체가 누설 정보가 된다 — 401 통일 시 외부에서 sessionId 가 유효한지/일치하는지 구분 불가. #244 구현이 이미 401 통일을 채택했고, 본 ADR 도 정합성을 위해 같은 선택. fe 가 "권한 없음" vs "데이터 없음" 을 구분해야 한다면 200+빈 배열(데이터 없음) vs 401(권한/매칭 실패) 로 충분.
- (E) 200 + 빈 배열 대신 404 로 통일 (존재 여부 누설 차단 강화) — 정상 빈 배열과 구분 불가, fe 가 "내 데이터인데 비었음" 도 404 처리해야 함. UX 거절.

## References
- 이슈 #238 (history endpoints session-bound 인증) — closed by #244
- PR #244 — `SessionAuthGuard` 1차 구현 (voice-range-history + recommendation-history)
- PR #229 — admin endpoint `X-Admin-Token` 게이트 (별 트랙 참조 구현)
- 정책 문서: `docs/ai-harness/04-security-policy.md`
- Feature Spec: `docs/features/voice-range-progress.md §5-2-1`, `docs/features/recommendation-history-and-feedback.md §5-2-1`
- 후속: #209 (sessionId TTL/회전), like/bookmark POST/DELETE/GET endpoint 적용 PR (recommendation-history-and-feedback spec PR F), `POST /api/v1/recommendations` persistence-write 적용 검토 (recommendation-history-and-feedback §8 Q7 신설), v0.3 P3 Spring Security 도입 후보

## Changelog
- **2026-05-22 (plan 28, 본 PR)**: §Decision 에 "적용 범위 (HTTP method 별 매핑 규칙)" 절 추가 — POST/PUT/PATCH/DELETE 까지 본 ADR 의 적용 대상임을 명시하고, body/path/query 세 위치의 sessionId 검증 매핑을 명문화. like/bookmark POST/DELETE 가 후속 PR F (recommendation-history-and-feedback §6) 에서 동일 컴포넌트로 게이트됨을 References 에 반영. persistence-write POST (예: `POST /api/v1/recommendations`) 의 적용 여부를 후속 spec Q7 로 분리.
