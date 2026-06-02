---
feature: 트렌딩 — 다른 사용자 인기곡 추천
slug: trending-recommendation
status: implementing
owner: @mobruji-maestro
scope: recommendation
related_issues: [1488]
related_prs: []
last_reviewed: 2026-06-03
---

# 트렌딩 — 다른 사용자 인기곡 추천

## 1) 개요 (What / Why)
- 추천 결과 히스토리(`recommendation` 결과 row)를 기간별로 집계해 "다른 사용자에게 자주·앞쪽으로 추천된 곡"을 인기곡으로 산출한다.
- 노래방 일반 차트(단순 부른 횟수)와의 차별점: **음역대·분위기와 결합**해 "내 음역대에서, 이 분위기로 뜨는 곡"을 보여 준다.
- 사용자가 "뭐 부르지?" 고민을 시작할 때, 본인 입력 기반 개인화 추천 외에 사회적 신호(다른 사용자 인기)를 한 축으로 제공한다.

## 2) 사용자 시나리오
- 사용자는 추천 결과 화면에서 "요즘 인기곡" 섹션을 보고, 최근 7일간 많이 추천된 곡을 둘러본다.
- 사용자는 분위기(예: EMOTIONAL)를 골라 "이 분위기에서 요즘 뜨는 곡"만 추려 본다.
- 사용자는 자신의 음역대(low~high)를 넘겨 "내 음역대와 겹치는 추천에서 인기 있는 곡"만 본다.

## 3) 요구사항
### 기능 요구사항
- [x] 추천 결과 히스토리를 기간(`periodDays`)으로 필터링해 곡별 인기도를 집계한다.
- [x] 인기도 = 등장마다 `1.0 / rankPosition` 을 더한 rank 감쇠 합 (상위 노출일수록 가중 ↑) + 등장 횟수(`appearanceCount`).
- [x] `mood` / `voiceRange(low,high)` 옵션 필터로 노래방 일반 차트와 차별화한다 (음역대는 구간 overlap).
- [x] 순위(rankPosition)·등장 횟수·인기도를 곡 메타데이터와 함께 응답한다.
- [x] 신규 엔드포인트 성공 E2E (RestAssured).

### 비기능 요구사항
- 읽기 전용 — 추천 알고리즘(점수 계산) 결정성에 영향 없음.
- 집계는 `recommendation` × `recommendation_request` application 레벨 join (도메인 간 FK 미설정 정책 유지).
- N+1 회피: 집계 1쿼리 + 곡 메타 `findAllById` 1쿼리. 정렬/limit 은 provider 별 ORDER BY alias 차이를 피해 application 에서 결정성 있게 처리.
- 운영 가드: `periodDays ≤ 365`, `limit ≤ 100`.
- 보안: sessionId·음역대 원문을 응답/로그에 노출하지 않는다 (집계는 곡 단위 결과만 반환).

## 4) 범위 / 비범위
### 포함
- `GET /api/v1/recommendations/trending` 집계 엔드포인트.
- 기간/분위기/음역대 필터 + rank 감쇠 인기도.

### 제외 (Out of Scope)
- 트렌딩을 본 추천(POST /recommendations) 점수에 **직접 가중**으로 섞는 것 — 결정성·알고리즘 회귀 위험이 커 별도 트렌딩 섹션(조회 전용)으로 한정. 추후 별도 spec.
- 실시간 캐싱/머티리얼라이즈드 뷰 — 1차는 온디맨드 집계. 트래픽 증가 시 후속.
- 개인화(본인 히스토리 제외) — 익명 세션 특성상 1차 미적용.

## 5) 설계
### 5-1) 도메인 모델
- 신규 엔티티 없음. 기존 `Recommendation`(결과 row) + `RecommendationRequestEntity`(요청 메타) 만 읽는다.
- 도메인 값 객체: `TrendingSong`(곡 + 순위 + 등장 횟수 + 인기도), `TrendingSongAggregate`(집계 프로젝션), `TrendingQuery`(입력 커맨드).

### 5-2) API 엔드포인트
| Method | Path | 설명 | 인증 | Req | Res |
|---|---|---|---|---|---|
| GET | /api/v1/recommendations/trending | 기간/분위기/음역대 기반 인기곡 순위 | 없음 | query params | `TrendingListResponse` |

Query params (모두 옵션):
- `periodDays` (기본 7, 1..365) — 최근 N일 추천 결과만 집계.
- `mood` — 분위기 필터 (정의되지 않은 값은 400).
- `voiceRangeLow` / `voiceRangeHigh` — 둘 다 또는 모두 미입력. 요청 음역대와 구간 overlap 하는 추천만 집계.
- `limit` (기본 10, 1..100).

응답 `TrendingListResponse`: 적용된 집계 조건(periodDays/mood/voiceRange) echo + `trendingSongs[]`(인기도 DESC 순위).

### 5-4) 데이터 흐름
1. controller 가 query param 검증(범위/both-or-neither) → `TrendingQuery`.
2. `TrendingService` 가 `since = now - periodDays` 를 산출, repository 집계 쿼리 호출.
3. 집계 쿼리: `recommendation` × `recommendation_request` join → 기간/mood/range 필터 → `GROUP BY songId` → (등장 횟수, `SUM(1/rank)`).
4. application 에서 인기도 DESC → 등장 횟수 DESC → songId ASC 정렬, limit, 곡 메타 join (누락 곡 skip).

### 5-5) DB 마이그레이션
- 없음. 기존 `recommendation`(`ix_recommendation_request_rank`) / `recommendation_request`(`ix_recommendation_request_session_created`) 인덱스 활용.

## 6) 작업 분할 (예상 PR 리스트)
- [x] PR 1 (#1488): 집계 쿼리 + 도메인/서비스 + 컨트롤러/DTO + E2E + 본 spec.

### 보호 영역 변경 여부 (필수 명시)
- 보호 영역 변경 여부: ☑ 없음

## 7) 테스트 전략
- E2E (`TrendingIntegrationTest`): repository 로 결정성 있게 시드 후 전체 순위 / mood 필터 / voiceRange 필터 / limit / 빈 결과 / 400(부분 range·기간 초과·잘못된 mood).
- 단위 (`TrendingServiceTest`): 정렬·limit·곡 누락 skip·기간 환산(`since` 인자) — Mockito.
- 단위 (`TrendingQueryTest` / `TrendingSongTest`): 입력/불변식 검증.

## 8) 오픈 질문
| # | 질문 | 선택지 | 담당/기한 |
|---|---|---|---|
| Q1 | 트렌딩을 본 추천 점수에 가중으로 섞을지 | (a) 조회 전용 유지 / (b) 점수 가중 도입 | @mobruji-maestro / 후속 |

## 9) 결정 로그
- 2026-06-03: 초안 + 구현 동시 (status=implementing). 인기도 = `SUM(1/rank)` rank 감쇠 채택 — 단순 카운트보다 "앞쪽 추천"을 더 반영. 본 추천 점수 직접 가중은 결정성 회귀 위험으로 비범위(별도 조회 섹션). 출처: #1488.
