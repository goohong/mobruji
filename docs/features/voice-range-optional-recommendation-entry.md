---
feature: 음역대 점진적 유도 추천 진입 (음역 optional fallback 피드)
slug: voice-range-optional-recommendation-entry
status: draft
owner: @mobruji-maestro
scope: recommendation
related_issues: []
related_prs: []
last_reviewed: 2026-06-03
---

# 음역대 점진적 유도 추천 진입 (음역 optional fallback 피드)

> 본 spec 은 `first-user-onboarding-flow.md §8 Q4`/§9 와 `mood-mode.md §4 비범위`/§9 가 공통으로 "별 규모 결정" 으로 미뤄 둔 **음역 optional 추천 분기** 의 정식 결정 SoT 다. 두 부모 spec 은 "추천 요청이 음역(`voiceRangeLow/High`)을 `not null` 로 요구한다" 는 현 제약 때문에 P-B(분위기) 경로에서 음역 측정을 **마지막 경량 단계로 유지**하기로 했다(=음역 없이 추천하지 않음). 본 spec 은 그 제약을 **알고리즘을 건드리지 않고** 우회하는 별 경로 — 음역 미입력 사용자에게 fallback 추천을 먼저 노출하고 점진적으로 음역 입력을 유도하는 진입 플로우 — 를 설계한다.

## 1) 개요 (What / Why)

- mobruji 의 본 추천(`POST /api/v1/recommendations`)은 `voiceRangeLow`/`voiceRangeHigh` 를 `@NotNull` 로 요구한다(`RecommendationCreateRequest`, `06-domain-model.md §5-3`). 또한 `voice-fit: 0.5` 가 1순위 가중치라 음역이 추천 품질의 지배 신호다. 결과적으로 **음역대 측정을 마치기 전까지는 추천 화면에 아무것도 띄울 수 없다** — 첫 진입 사용자는 "측정부터 하라" 는 관문(gate)을 먼저 만난다.
- 입문자(P-C)는 진입 즉시 `D3?` 표기 충격(P1, #318)에, 분위기 메이커(P-B)·연습러(P-A)는 "일단 뭐가 있는지 보고 싶다" 는 동기와 "측정부터" 요구 사이의 마찰에 노출된다. 측정은 가치가 크지만 **진입 장벽을 0 으로 만들면 더 많은 사용자가 첫 추천 경험에 도달**한다.
- 본 spec 은 음역 미입력 사용자에게 **fallback 추천 피드**(트렌딩/큐레이션/분위기 기반)를 먼저 노출하고, 그 위에서 **"더 정확한 맞춤 추천을 원하면 음역대를 알려 주세요"** 로 음역 입력을 **비강제·점진적으로 유도**하는 진입 플로우를 설계한다. 측정은 관문이 아니라 **업그레이드 선택지**가 된다.
- 대상 액터: 첫 진입(또는 미측정) 익명 사용자 전반. 재방문(측정 완료) 사용자는 본 흐름을 건너뛰고 기존 개인화 추천으로 직행한다.

## 2) 사용자 시나리오

1. **(둘러보기 우선)** 첫 진입 사용자가 측정 없이 추천 화면에 도달 → "요즘 인기곡"(트렌딩) + (선택 시) "이 분위기로 뜨는 곡" fallback 피드를 둘러본다. 화면 상단/곡 카드 근처에 가벼운 음역 유도 배너("내 목소리에 맞는 곡으로 더 정확하게 — 1분 측정").
2. **(점진적 전환)** 피드를 둘러보다 "이 중에 내가 부를 수 있는 게 뭐지?" 궁금해진 사용자가 유도 배너 탭 → 음역 측정(기존 `/voice-range/auto`) → 측정 완료 즉시 **개인화 추천**(`POST /recommendations`)으로 피드가 전환되고, 곡 카드에 `voiceFit`/사유가 강조된다.
3. **(분위기 메이커, 측정 미루기)** 회식 직전 P-B 사용자가 MOOD 경로로 진입 → 분위기 프리셋만 고르고 측정은 미룬 채 fallback 피드(분위기 필터 트렌딩)로 바로 곡을 둘러본다. 음역 유도는 "마지막으로 목소리만" 톤으로 한 번 더 노출된다(`mood-mode.md` Q4=a 정합 — 측정 위치는 유지하되 **선행 강제는 해제**).
4. **(끝까지 미입력)** 측정을 끝내 하지 않는 사용자도 막다른 길 없이 fallback 피드 + 재추천("다른 곡")으로 곡 탐색을 완결할 수 있다. 음역 유도는 반복 강요하지 않는다(노출 빈도 가드).

## 3) 요구사항

### 기능 요구사항
- [ ] 음역 미입력 상태에서 추천 화면 진입 시 **fallback 추천 피드**(`VoiceRangeOptionalFeed`)를 노출한다 — 본 추천(`POST /recommendations`) 호출 없이.
- [ ] fallback 콘텐츠는 graceful chain 으로 구성한다: ① (분위기 선택 시) 분위기 필터 트렌딩 → ② 트렌딩(`GET /recommendations/trending`, 음역 미지정) → ③ 콜드스타트(트렌딩 비/희소) 시 큐레이션/인기 곡(§8 Q2).
- [ ] fallback 피드 위에 **음역 입력 유도 트리거**(`VoiceRangeNudge`)를 비강제로 노출한다 — 피드를 막지 않고(opt-in), 문구는 "더 정확한 맞춤 추천을 원하면 음역대를 알려 주세요(약 1분)" 톤.
- [ ] 유도 트리거 탭 → 기존 음역 측정(`/voice-range/auto` 또는 수동 `/voice-range`) → **측정 완료 시 개인화 추천(`POST /recommendations`)으로 피드 전환**.
- [ ] 음역 유도는 **반복 강요하지 않는다** — 세션 내 노출 빈도/dismiss 상태를 클라이언트에 보관(§8 Q3).
- [ ] 재방문(측정 완료) 사용자는 fallback 분기를 건너뛰고 기존 개인화 추천 화면으로 직행한다.
- [ ] 본 흐름은 **추천 알고리즘(점수식·가중치·결정성·정규화)을 변경하지 않는다** — `POST /recommendations` 의 `voiceRange @NotNull` 제약을 유지한다.

### 비기능 요구사항
- **진입 장벽 0** — 음역 미입력 사용자도 어떤 명시 입력 없이 첫 추천 피드(또는 명확한 다음 액션)에 도달한다(막다른 길 없음, 온보딩 비기능 상속).
- **추천 결정성·p95 무영향** — fallback 은 조회 전용(트렌딩/큐레이션)이라 본 추천 점수 결정성·`recommendation-p95-regression-guard.md §5-3` 임계에 회귀를 주지 않는다.
- **유도 비침투성** — 음역 유도는 피드를 가리거나 차단하지 않으며, dismiss 후 같은 세션 재노출은 가드한다.
- **보안** — fallback 응답/로그에 sessionId·음역대 원문을 노출하지 않는다(트렌딩 비기능 상속, `04-security-policy.md §3`).
- **접근성** — 유도 배너/피드 전환은 키보드 포커스 이동 + status live region announce(`/voice-range/auto` 패턴 준수).

## 4) 범위 / 비범위 (중요)

### 포함
- 음역 미입력 추천 진입 플로우 설계(fallback 피드 + 점진적 음역 유도 + 측정 후 개인화 전환).
- fallback 콘텐츠 전략 설계(트렌딩 / 분위기 필터 / 콜드스타트 큐레이션 chain).
- 음역 유도 트리거의 배치·문구·노출 빈도 가드 설계.
- 도메인 용어 등재(`VoiceRangeOptionalFeed`, `VoiceRangeNudge`) + 부모 spec(`first-user-onboarding-flow` / `mood-mode`) 정합.
- be/fe 작업 분할 식별.

### 제외 (Out of Scope)
- **`POST /recommendations` 의 voiceRange optional 화** — 음역 없이 본 추천 알고리즘이 동작하도록 점수식/정규화/결정성 가드를 바꾸는 작업. dominant 신호(`voiceFit 0.5`) 포화·정규화·결정성 회귀 위험이 커 **본 spec 이 명시적으로 기각**(§9 결정 — Option B 기각). fallback 은 별 조회 경로로 우회한다.
- **트렌딩을 본 추천 점수에 가중으로 섞기** — `trending-recommendation.md §4`·Q1 비범위 유지.
- **신규 BE 추천 엔티티/마이그레이션** — fallback 은 기존 트렌딩(+곡 카탈로그) 재사용. 새 영속 단위 없음.
- **온보딩 진입 분기(3 페르소나 카드) 자체** — `first-user-onboarding-flow.md` SoT. 본 spec 은 "측정 전 추천을 무엇으로 어떻게 보여줄지" 만.
- **음역 측정 UI 자체** — F1(`voice-range-measure-guided-tour.md`) / 기존 `/voice-range/auto`. 본 spec 은 유도→측정 핸드오프 지점만.
- **온보딩 퍼널 이벤트 트래킹 인프라** — 후속(관측 인프라 도입 시).

## 5) 설계

### 5-1) 도메인 모델
- 신규 BE 엔티티 **없음**. 기존 재사용:
  - `TrendingSong` / `TrendingQuery`(`06-domain-model.md §4-1`, `trending-recommendation.md`) — fallback 1차 소스. `voiceRangeLow/High` 미지정 + (선택) `mood` 필터로 음역 없이 호출 가능.
  - `Song`(§5-2) — 콜드스타트 큐레이션 fallback 소스(§8 Q2).
  - `VoiceRange`(§5-1) / `RecommendationRequest`(§5-3) / `AnonymousSession`(§5-6) — 측정 후 개인화 전환 시 기존 경로 그대로.
- 신규 용어는 `06-domain-model.md §4-1` 에 등재(본 PR 동반):
  - **`VoiceRangeOptionalFeed`**(음역 미입력 추천 피드) — 음역 측정 전 노출하는 fallback 추천 표면. 트렌딩+큐레이션 graceful chain read-model. 신규 엔티티 아님.
  - **`VoiceRangeNudge`**(음역 입력 유도) — fallback 피드 위 비강제 음역 측정 유도 클라이언트 UX 트리거. 신규 BE 엔티티 아님(클라이언트 상태).

### 5-2) API 엔드포인트
| Method | Path | 설명 | 인증 | Req | Res |
|---|---|---|---|---|---|
| GET | /api/v1/recommendations/trending | (기존, 재사용) 음역 미지정 fallback 피드 소스 | 없음 | query params(모두 옵션) | `TrendingListResponse` |
| GET | /api/v1/songs | (기존) 콜드스타트 큐레이션 후보(§8 Q2 결정 시) | 없음 | (현 keyword) | `List<SongResponse>` |
| POST | /api/v1/recommendations | (기존, 무변경) 측정 후 개인화 전환 | 익명(세션ID) | `RecommendationCreateRequest`(voiceRange `@NotNull` 유지) | `RecommendationResponse` |

- **신규 BE 엔드포인트는 1차에 두지 않는 것을 권고**(§8 Q2-a) — 트렌딩 재사용으로 fallback 을 구성한다. 콜드스타트 큐레이션을 위한 경량 엔드포인트(예: `GET /api/v1/songs/curated` 또는 트렌딩의 empty-fallback 보강)는 §8 Q2 결정에 따라 선택적 be 작업으로 분기.

### 5-3) 외부 연동
- 없음. 트렌딩·곡 카탈로그 모두 내부 DB 조회.

### 5-4) 데이터 흐름 / 시퀀스

```
[추천 화면 진입]
   │  voiceRangeId == null (미측정)
   ▼
[VoiceRangeOptionalFeed]  ── fallback chain
   │   ① (mood 선택 시) 트렌딩 + mood 필터
   │   ② 트렌딩 (음역 미지정)
   │   └ ③ (트렌딩 희소) 큐레이션/인기 곡  ← §8 Q2
   │
   ├─ [VoiceRangeNudge] "더 정확한 맞춤을 원하면 음역대를 알려 주세요"  (비강제, dismiss 가드)
   │        │ 탭
   │        ▼
   │   [음역 측정]  /voice-range/auto (or 수동)
   │        │ 측정 완료 (voiceRangeId 생성)
   │        ▼
   └─────► [개인화 추천]  POST /recommendations  (voiceFit/사유 강조)
   │
   ▼
(미입력 지속) fallback 피드 + "다른 곡" 재탐색 — 막다른 길 없음
```

- 측정 후 전환은 기존 voiceRangeId 상태 분기(`useHasHydratedSession` 류)에 자연 합류 — 별도 BE 상태 전이 없음.

### 5-5) DB 마이그레이션
- 없음(1차, 트렌딩 재사용). 콜드스타트 큐레이션을 위해 BE 보강을 택하면(§8 Q2-b) 그 PR 에서 별도 평가 — 단 신규 테이블/컬럼은 지양(읽기 전용 조회).

### 5-6) 프론트엔드 화면 (해당 시)
- **fallback 피드 컴포넌트**(`VoiceRangeOptionalFeed`) — 음역 미입력 시 추천 화면(`/recommend`)에서 트렌딩(+분위기 필터) 결과를 카드 리스트로 노출. 기존 `<RecommendationCard>` 재사용(단, `voiceFit`/사유는 음역 없으므로 숨김 또는 "음역대를 알려 주면 적합도를 보여 드려요" placeholder).
- **음역 유도 컴포넌트**(`VoiceRangeNudge`) — 피드 상단 또는 카드 사이 비차단 배너/카드. 탭 → 측정 라우팅. dismiss + 세션 노출 빈도 상태 store(`web/store/onboarding.ts` 확장 또는 신규 `web/store/voiceRangeNudge.ts`).
- **측정 후 전환** — voiceRangeId 생성 시 fallback 피드 → 개인화 추천(`POST /recommendations`)으로 자동 전환. "내 음역 C3~A4 기준" + `voiceFitReason`/`moodFitReason` 강조 노출.
- **재방문 분기** — 측정 완료 사용자는 fallback 분기 스킵 → 기존 개인화 화면 직행.

## 6) 작업 분할 (예상 PR 리스트)
- [ ] PR 1 (docs, plan): 본 spec + `06-domain-model.md §4-1` 용어 등재(`VoiceRangeOptionalFeed`/`VoiceRangeNudge`) + 부모 spec(`first-user-onboarding-flow`/`mood-mode`) 정합 cross-ref. **본 PR**.
- [ ] PR 2 (fe): `VoiceRangeOptionalFeed` — 음역 미입력 시 추천 화면에서 트렌딩(+분위기 필터) fallback 피드 노출 + 카드 음역 적합도 placeholder. 콜드스타트(트렌딩 빈 결과)는 graceful empty-state.
- [ ] PR 3 (fe): `VoiceRangeNudge` — 비강제 음역 유도 배너 + dismiss/노출 빈도 store + 측정 라우팅 핸드오프.
- [ ] PR 4 (fe): 측정 후 개인화 추천 전환 wiring(voiceRangeId 생성 → `POST /recommendations` 피드 교체 + 적합도 강조) + 재방문 분기 스킵.
- [ ] PR 5 (be, **선택 — §8 Q2-b 채택 시**): 콜드스타트 큐레이션 fallback 보강(트렌딩 empty 시 인기/큐레이션 곡 조회) + 성공 E2E.

### 보호 영역 변경 여부 (필수 명시)

- 보호 영역 변경 여부: ☑ 없음 (1차) / ☐ 있음
  - PR 1~4 는 docs + `web/` 범위. 신규 BE 엔티티/마이그레이션/`application.yml`/build 파일 변경 없음.
  - PR 5(선택)는 조회 전용 be 작업으로 한정하며 신규 테이블/컬럼·`application.yml` 스키마 변경을 지양한다 — 불가피 시 해당 PR 에서 보호 영역으로 재명시.

## 7) 테스트 전략
- **fe 단위**: fallback 피드 렌더(트렌딩 결과 매핑 / 빈 결과 empty-state), 음역 유도 노출·dismiss·재노출 가드, 측정 후 개인화 전환 분기, 재방문 스킵.
- **fe e2e (Playwright)**: "측정 없이 진입 → fallback 피드 노출 → 유도 탭 → (측정 stub) → 개인화 추천 전환" 1건, "측정 없이 끝까지 둘러보기 → 막다른 길 없음" 1건, "재방문(측정 완료) → fallback 스킵" 1건.
- **be**: 1차 신규 엔드포인트 없음 → 기존 트렌딩 E2E 재사용(단계 1 no-op pass 대상). PR 5 채택 시 큐레이션 fallback 성공 E2E(RestAssured) 추가.
- **정합 검증**: `mood-mode.md`(측정 위치) / `first-user-onboarding-flow.md`(진입 분기) 와 모순 없는지 cross-ref 회귀.

## 8) 오픈 질문
> 구현 전에 답이 나와야 하는 것들. 해소되면 §9 결정 로그로 이동.

| # | 질문 | 선택지 | 담당/기한 |
|---|---|---|---|
| Q1 | fallback 1차 소스 | (a) 트렌딩 재사용(권고, BE 무변경) / (b) 신규 큐레이션 엔드포인트 신설 | @mobruji-maestro / PR 2 |
| Q2 | 콜드스타트(트렌딩 희소) 처리 | (a) FE 곡 카탈로그 큐레이션 + graceful empty(권고, PoC) / (b) be 큐레이션/인기 fallback 엔드포인트 신설(PR 5) | @mobruji-maestro / PR 2~5 |
| Q3 | 음역 유도 노출 정책 | (a) 진입 상단 1회 + dismiss 후 세션 무재노출(권고) / (b) 스크롤/탭 engagement 트리거 / (c) 혼합 | @mobruji-maestro / PR 3 |
| Q4 | 음역 미입력 카드의 적합도 표기 | (a) `voiceFit` 영역 숨김 + "음역대 알려 주면 적합도 표시" placeholder(권고) / (b) 영역 자체 제거 | @mobruji-maestro / PR 2 |

## 9) 결정 로그
> 연대기 순. "YYYY-MM-DD: 결정 / 이유 / 출처(PR 번호 등)"

- **2026-06-03**: 초안 작성 (status=draft). **핵심 결정 — Option A(별 fallback 경로) 채택, Option B(본 추천 voiceRange optional 화) 기각.**
  - **AS-IS 박제**: `RecommendationCreateRequest.voiceRangeLow/High` 가 `@NotNull`(`backend/.../api/dto/RecommendationCreateRequest.java:46-47`) + `voice-fit: 0.5` 1순위 가중치 → 음역 측정 전 추천 화면 노출 불가(측정 관문). `first-user-onboarding-flow.md §8 Q4`/§9 + `mood-mode.md §4`/§9 가 이 제약을 "음역 측정을 마지막 경량 단계로 유지" 로 우회했으나, **측정 선행 강제 자체는 남아 있었다**.
  - **Option B 기각 근거**: voiceRange 를 optional 로 풀어 본 추천이 음역 없이 동작하게 하면 dominant 신호(`voiceFit 0.5`) 포화/중화 → 정규화·결정성 가드(`recommendation-algorithm-v1.md §3 결정성`)를 건드리는 별 규모 변경. 온보딩 §9 가 이미 동일 사유로 (c) 광역 default·음역 optional 분기를 "별 결정" 으로 분리했다.
  - **Option A 채택**: 본 추천 알고리즘·`@NotNull` 제약을 **그대로 두고**, 음역 미입력 사용자에게는 조회 전용 fallback(`VoiceRangeOptionalFeed` = 트렌딩 재사용 + 콜드스타트 큐레이션)을 먼저 노출 → 점진적 음역 유도(`VoiceRangeNudge`) → 측정 완료 시 기존 개인화 경로로 전환. 결정성·p95 회귀 0, BE 변경 1차 0(트렌딩 재사용).
  - `06-domain-model.md §4-1` 에 `VoiceRangeOptionalFeed`/`VoiceRangeNudge` 등재 동반. 부모 spec 2건과 cross-ref 정합. be/fe 작업 분할(PR 2~5) 식별, BE(PR 5)는 §8 Q2 결정 의존 선택 작업.

## 10) 다음 단계
1. 본 PR 머지 후 §8 Q1/Q2 를 PR 2(fe) 착수 전 확정(권고: Q1=a 트렌딩 재사용, Q2=a FE 큐레이션 + empty-state).
2. fe 사이클이 PR 2~4 를 순차 진행 — PR 2(피드) → PR 3(유도) → PR 4(전환). 자식 측정 UI(F1)·MOOD 경로와 핸드오프 지점 정합 유지.
3. 콜드스타트 큐레이션이 PoC 에서 FE 만으로 부족하다고 판명되면 §8 Q2-b(be PR 5) 분기.
