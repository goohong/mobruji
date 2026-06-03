---
feature: 분위기 메이커 모드 (F3 · 분위기 즉석 모드)
slug: mood-mode
status: draft
owner: @goohong
scope: recommendation
related_issues: []
related_prs: [1562]
last_reviewed: 2026-06-03
---

# 분위기 메이커 모드 (F3 · 분위기 즉석 모드)

> 본 spec 은 `user-persona-and-pain-points.md §5 F3` 에서 분기된 **자식 spec** 이며, 동시에 `first-user-onboarding-flow.md` 의 **MOOD(P-B) 진입 경로** 가 핸드오프하는 목적지다. 본 spec 은 "분위기 메이커가 회식 자리 직전 어떤 흐름으로 첫 추천에 도달하고, 어떻게 그 곡으로 자리를 살리는가" 를 설계한다. 상위 온보딩 흐름(진입 카드·라우팅·완료 전이)은 부모 spec 이 SoT 이고, 본 spec 은 MOOD 경로의 화면·신호·결과 카드만 다룬다.

## 1) 개요 (What / Why)

- 대상 액터: 페르소나 B(분위기 메이커, `user-persona-and-pain-points.md §2-B`). 핵심 motivation = "이 자리를 살리고 싶다", 페인 **P3**("분위기 띄우는 노래 모드 부재 — mood 입력은 있으나 '분위기 메이커' 가치로 옷 입혀지지 않음") + **P5 부분**(추천 소진 후 다음 액션 부재).
- mobruji 는 이미 `mood` 입력과 `moodMatch`(#1485 분위기 연속 유사도) / `tempoMatch`(v2) 신호를 갖고 있으나, 사용자에게는 "신남/잔잔/감성" 같은 **곡 속성 라벨** 로만 노출되어 "지금 이 자리를 살릴 곡" 이라는 **상황 의도** 와 연결되지 않는다.
- 본 spec 은 그 의도를 **분위기 프리셋(MoodPreset)** — 회식 띄우기 / 떼창 / 감성 / 도입 — 으로 옷 입히고, 노래방 직전 "즉석" 사용 맥락에 맞춰 **0~2 탭 안에 첫 추천 도달** + 결과 카드에 **무대 활용 팁** 을 더한다. 신규 `Mood` enum 값·BE 엔티티 없이 기존 신호 위에 사용자-페이싱 view 를 얹는다(PoC).
- 본 spec 은 온보딩 `§8 Q4`(P-B 측정 위치)를 함께 확정한다 — §9 결정 로그 참조.

## 2) 사용자 시나리오

1. **(회식 자리 즉석)** 회식 2차 노래방 직전, 페르소나 B 가 진입 → 온보딩 "분위기 띄울 곡 찾기"(MOOD) 카드 선택 → **분위기 프리셋 picker**("회식 띄우기" 탭) → (선택) 청중 연령대 한 탭 → **음역은 마지막 가벼운 한 단계** 로 프레이밍된 측정 → 첫 추천 도달. 결과 카드에 "도입 8마디에서 박수 유도, 후렴 떼창 포인트" 활용 팁이 붙는다.
2. **(감성 마무리)** 자리 끝물에 잔잔히 마무리하고 싶은 사용자가 "감성" 프리셋 선택 → `EMOTIONAL` + 느린 BPM 매핑으로 추천 → 활용 팁 "감정선 살릴 호흡 구간".
3. **(떼창 유도)** 다 같이 부를 곡이 필요할 때 "떼창" 프리셋 → 인지도 높은 `POWERFUL/UPBEAT` 곡 우선 → "다 같이 부르는 후렴" mark.
4. **(추천 소진 후)** 한 곡 부른 뒤 "다음 분위기로" CTA → 프리셋만 바꿔 즉시 재추천(측정 재입력 없음). P5(추천 소진 후 막다른 길) 완화.

## 3) 요구사항

### 기능 요구사항
- [ ] **분위기 프리셋 picker** — 4 프리셋(`PARTY` 회식 띄우기 / `SINGALONG` 떼창 / `EMOTIONAL` 감성 / `ICEBREAKER` 도입) 노출. 각 프리셋이 기존 `Mood` enum + `preferredBpm` 으로 풀려 추천 요청에 실린다.
- [ ] **청중 연령대 옵션**(선택, 1탭) — 프리셋 선택 직후 가벼운 한 단계. PoC 1차는 클라이언트 세션 옵션(추천 신호 미연결, §8 Q3).
- [ ] **음역 측정은 마지막 경량 단계** — MOOD 경로에서 음역 입력을 "분위기 다 골랐고, 마지막으로 목소리만" 으로 프레이밍해 마지막에 배치(온보딩 Q4=a 확정). 측정 생략·default 음역 금지(§9 근거).
- [ ] **분위기 메이커 결과 카드 활용 팁** — `<RecommendationCard>` 에 무대 활용 팁 slot(도입 박수 포인트 / 떼창 후렴 / 감정선 호흡 등) 노출. 프리셋별 팁 톤 분기.
- [ ] **프리셋만 바꿔 재추천** — 첫 추천 후 측정·다른 입력 재요구 없이 프리셋 전환만으로 재추천(P5 완화).
- [ ] 프리셋↔`Mood`/`preferredBpm` 매핑은 단일 출처(`web/lib/` 매핑 + BE `RecommendationProperties.moodBpm`)로 두어 fe/be drift 방지.

### 비기능 요구사항
- **즉석 사용 맥락** — 프리셋 선택 → 첫 추천까지 0~2 탭. 온보딩 비기능(P95 ≤ 3분, 막다른 길 없음)을 상속한다.
- **추천 알고리즘 무변경** — 본 모드는 기존 `moodMatch`/`tempoMatch` 신호와 `voice-fit 0.5` 가중치를 그대로 쓴다. 결정성·p95 임계(`recommendation-p95-regression-guard.md §5-3`) 회귀 없음.
- **신규 BE 엔티티/마이그레이션 없음**(PoC) — 청중 연령대 영속은 §8 Q3 결정 전까지 클라이언트 세션 옵션만.
- **접근성** — 프리셋 카드/단계 전환은 키보드 포커스 + status live region announce(온보딩·`/voice-range/auto` 패턴 준수).

## 4) 범위 / 비범위 (중요)

### 포함
- 분위기 프리셋 picker(4 프리셋) + 프리셋→`Mood`/`preferredBpm` 매핑 설계.
- 청중 연령대 옵션 UI(PoC 세션 옵션) 설계.
- MOOD 경로의 측정 위치 확정(마지막 경량 단계) + 온보딩 핸드오프 지점.
- 분위기 메이커 결과 카드 활용 팁 slot 설계 + 프리셋별 팁 시드 방향.
- 프리셋 전환 재추천(P5 완화) 흐름.

### 제외 (Out of Scope)
- **신규 `Mood` enum 값 추가** — 프리셋은 기존 enum 위 view. enum 확장은 별도 결정(필요 시 `song`/`recommendation` ADR).
- **청중 반응 실시간 인식** — v0.4+(부모 F3 비범위 상속).
- **청중 연령대 추천 신호화** — 청중 세대를 추천 점수에 반영하는 신호(요청 `ageGroup`=본인 세대와 별 차원)는 별도 결정(§8 Q3 후속). 본 spec 1차는 입력 수집/표시까지.
- **음역 optional 추천 분기** — 추천이 음역 없이 동작하도록 알고리즘을 바꾸는 작업은 본 spec 밖(§9 Q4 근거 — 별 결정).
- **활용 팁 자동 생성** — 곡별 활용 팁의 자동 산출(audio-analysis 기반)은 후속. 1차는 프리셋 톤 + 수기 시드.
- **온보딩 진입 분기/완료 전이 자체** — 부모 `first-user-onboarding-flow.md` SoT.

## 5) 설계

### 5-1) 도메인 모델
- 신규 BE 엔티티 **없음**. 기존 재사용:
  - `Mood`(`song`, enum `UPBEAT/CALM/EMOTIONAL/POWERFUL/GROOVY/NOSTALGIC`) — 프리셋이 매핑하는 곡 속성.
  - `RecommendationRequest`(§5-3) `mood`/`preferredBpm`/`voiceRangeLow`/`voiceRangeHigh` — 프리셋이 풀어 채우는 입력.
  - `ScoreBreakdown.moodMatch`(#1485 연속 유사도) / `tempoMatch`(v2) — 프리셋이 소비하는 신호.
- 신규 용어(`06-domain-model.md §4-1` 등재, 본 PR 동반, **설계 단계** 표시):
  - **`MoodPreset`** — `PARTY`/`SINGALONG`/`EMOTIONAL`/`ICEBREAKER`. 기존 `Mood`+`preferredBpm` 위 사용자-페이싱 view(신규 enum 값 아님).
  - **`AudienceProfile`** — 청중 연령대 컨텍스트. 영속 형태 미정(§8 Q3).

#### 프리셋 → `Mood` / `preferredBpm` 매핑(초안, §8 Q1)
| MoodPreset | 사용자 라벨 | → `Mood` | → `preferredBpm`(moodBpm) | 비고 |
|---|---|---|---|---|
| `PARTY` | 회식 띄우기 | `UPBEAT`(또는 `GROOVY`) | 130(신남) | 업템포·인지도 가산 |
| `SINGALONG` | 떼창 | `POWERFUL` | 120 | 후렴 강한 anthem |
| `EMOTIONAL` | 감성 | `EMOTIONAL` | 95(감성) | 느린 발라드 |
| `ICEBREAKER` | 도입 | `GROOVY`(또는 `CALM`) | 105 | 부담 적은 분위기 잡기 |

- 매핑은 단일 출처 — fe `web/lib/moodPreset.ts`(표시·라우팅) + be `RecommendationProperties.moodBpm`(BPM). 신규 BPM 키(`떼창`/`도입`) 추가 여부·정확값은 §8 Q1. `moodBpm` 변경은 `application.yml`(보호 영역)이므로 be PR 에서 별도 박제.

### 5-2) API 엔드포인트
| Method | Path | 설명 | 인증 | Req | Res |
|---|---|---|---|---|---|
| GET | `/api/v1/mood-presets` | **프리셋 카탈로그 조회**(BE 1단계, #1562) — 4 프리셋을 각자의 `Mood` + `preferredBpm` 으로 해석해 노출. fe picker 가 라벨 표시 + 추천 호출 구성에 쓰는 **프리셋→`Mood`/`preferredBpm` 매핑 BE 단일 출처** | 익명 | — | `MoodPresetListResponse` (`presets[]` = `preset`/`label`/`mood`/`preferredBpm`) |
| POST | `/api/v1/recommendations` | 추천 — 기존 재사용. 프리셋은 fe 에서 카탈로그가 준 `mood`+`preferredBpm` 으로 풀어 기존 body 로 전송(신호·산식 무변경) | 익명 | (기존) | (기존) |

- 추천 자체는 기존 `POST /api/v1/recommendations` 그대로 — 추천 알고리즘·신호·결정성 무변경. 신규 카탈로그 엔드포인트는 **읽기 전용**(매핑 노출만)이라 p95/결정성 회귀 가드와 무관.
- `preferredBpm` 은 별도 BPM 표 없이 기존 `recommendation.tempo.moodDefaultBpm`(프리셋의 `Mood` 키)에서 해석 → 보호 영역(`application.yml`) 무변경. moodDefaultBpm 에 키 부재 시 `fallbackBpm`.
- 청중 연령대를 추천 신호로 보낼지(요청 필드 추가)는 §8 Q3 결정 전까지 미전송(클라이언트 수집만).

### 5-3) 추천 신호(무변경 재사용)
- 프리셋 선택 → fe 가 `{ mood, preferredBpm, voiceRangeLow, voiceRangeHigh }` 로 기존 추천 호출.
- `moodMatch`(weight 0.3) — 프리셋의 `Mood` 좌표(`(energy, brightness)`)와 곡 mood 거리 기반 연속 유사도(#1485) 그대로.
- `tempoMatch`(weight 0.1) — 프리셋 `preferredBpm` ↔ 곡 BPM 거리(v2 §5-3) 그대로.
- `voiceFit`(weight 0.5) — **음역 측정값 필수**. 본 모드도 음역을 받아 채운다(§9 Q4 근거). 가중치·산식 변경 없음 → 결정성/p95 회귀 가드 무영향.

### 5-4) 데이터 흐름 / 시퀀스
```
[온보딩 MOOD(P-B) 카드 선택]  (부모 spec)
   ▼
[분위기 프리셋 picker]  ── PARTY / SINGALONG / EMOTIONAL / ICEBREAKER
   ▼
[청중 연령대 옵션]  ── (선택, 1탭, PoC 세션 옵션)
   ▼
[음역 측정 — 마지막 경량 단계]  ── "마지막으로 목소리만" 프레이밍 (Q4=a)
   ▼
[추천 호출]  ── mood + preferredBpm + voiceRange (기존 endpoint, 신호 무변경)
   ▼
[분위기 메이커 결과 카드]  ── voiceFit/moodFit 사유 + 무대 활용 팁 slot
   │   └─ "다음 분위기로" CTA → 프리셋만 교체 후 재추천 (측정 재입력 없음, P5 완화)
   ▼
온보딩 상태 = 완료  (부모 spec 전이)
```

### 5-5) DB 마이그레이션
- 없음(PoC). 청중 연령대 영속(필드 추가)은 §8 Q3 결정 시 별 PR(보호 영역 = migration).

### 5-6) 프론트엔드 화면
- 신규/확장 컴포넌트(설계):
  - `<MoodPresetPicker>` — 4 프리셋 카드. 프리셋→`Mood`/`preferredBpm` 매핑(`web/lib/moodPreset.ts`)으로 추천 입력 구성.
  - `<AudiencePicker>` — 청중 연령대 옵션(선택 1탭). 온보딩 상태 store 에 보관(추천 미전송, PoC).
  - `<RecommendationCard>` 확장 — `stageTip` slot(무대 활용 팁). 프리셋별 팁 톤 분기. F2 `trainingTip` slot 과 동일 카드의 별 slot(상호 배타: 모드별 1개 노출).
- 재사용: `/voice-range/auto`·`/voice-range`(측정), `/recommend`(결과). 측정 진입 카피만 MOOD 경로용으로 "마지막 가벼운 단계" 톤.
- 온보딩 상태 store(`web/store/onboarding.ts`, 부모 spec) 에 `entryPath=MOOD` 와 함께 `moodPreset`/`audience` 진행 보관 — 중간 재진입 시 이어서.

## 6) 작업 분할 (예상 PR 리스트)
- [ ] PR 1 (docs, plan): 본 spec + `06-domain-model.md §4-1` 용어 등재(`MoodPreset`/`AudienceProfile`) + 온보딩 §8 Q4 확정 반영. **본 PR**.
- [ ] PR 2 (fe): `<MoodPresetPicker>` + 프리셋→`Mood`/`preferredBpm` 매핑(`web/lib/moodPreset.ts`) + MOOD 경로 측정 위치(마지막 단계) wiring. 온보딩 PR 4(MOOD 경로 wiring)와 동기.
- [x] PR 3 (be): **프리셋 카탈로그 엔드포인트 `GET /api/v1/mood-presets`**(BE 1단계, #1562) — `MoodPreset` enum(프리셋→`Mood` 단일 출처) + `preferredBpm` 을 기존 `recommendation.tempo.moodDefaultBpm` 에서 해석(별도 BPM 표·`application.yml` 변경 없이 단일 출처 유지) + RestAssured 성공 E2E. 추천 신호·산식·가중치 무변경 → 결정성/p95 회귀 가드 무영향(읽기 전용 엔드포인트).
- [ ] PR 4 (fe): `<AudiencePicker>` 청중 연령대 옵션(PoC 세션 옵션).
- [ ] PR 5 (fe): `<RecommendationCard>` `stageTip` slot + 프리셋별 활용 팁(수기 시드) + "다음 분위기로" 재추천 CTA.
- [ ] PR 6 (song-curation): mood 태그 정밀화 — 프리셋 변별에 필요한 곡 mood 정합(seed 보강, `song-curation-seed-100.md` 진행도 의존).

### 보호 영역 변경 여부 (필수 명시)

- 보호 영역 변경 여부: ☐ 없음 (현 PR 리스트 기준):
  - PR 3(be 카탈로그 엔드포인트)은 `preferredBpm` 을 기존 `recommendation.tempo.moodDefaultBpm` 에서 **읽어** 해석하므로 `application.yml` 변경이 없다(초안의 `moodBpm` 키 추가 방식 폐기 — 단일 출처를 기존 tempo 설정으로 통일). 추천 신호·산식·가중치 무변경.
  - 나머지 PR(2/4/5)은 `web/*` + docs 범위로 보호 영역 무변경. 청중 연령대 **영속**(migration) 채택 시 별 PR 에서 보호 영역 재명시.

## 7) 테스트 전략
- **fe 단위**: 프리셋 선택 → `{ mood, preferredBpm }` 매핑 정확성, 청중 옵션 store 전이, 프리셋 전환 재추천(측정 재요구 없음), `stageTip` 프리셋별 톤 분기.
- **fe e2e (Playwright)**: "MOOD 카드 → 프리셋 선택 → (청중) → 측정 → 첫 추천 + 활용 팁 노출" 1건, "추천 후 다음 분위기 프리셋 전환 → 재추천(측정 스킵)" 1건.
- **be 단위**: `MoodPresetCatalog` 가 프리셋 `Mood` 키로 `moodDefaultBpm` 에서 `preferredBpm` 해석, 키 부재 시 `fallbackBpm` 폴백.
- **결정성 회귀**: 같은 `{ mood, preferredBpm, voiceRange }` 두 번 → 1위 score 동일(기존 가드 재실행). 카탈로그는 읽기 전용·추천 신호 무변경이므로 신규 가드 불요.
- **정합 검증**: 프리셋→`Mood`/`preferredBpm` 매핑이 fe(`moodPreset.ts`) ↔ be(`/api/v1/mood-presets` 카탈로그) drift 없는지(단일 출처 가드). fe 는 카탈로그를 소비하거나 동일 매핑을 미러.
- **be E2E**: `GET /api/v1/mood-presets` 성공 E2E(RestAssured) — 4 프리셋 선언 순서 + `label`/`mood`/`preferredBpm` 매핑 검증. 본 spec 단독 docs PR(PR 1)은 rev 단계 1 no-op pass 대상.

## 8) 오픈 질문
> 구현 전에 답이 나와야 하는 것들. 해소되면 §9 결정 로그로 이동.

| # | 질문 | 선택지 | 담당/기한 |
|---|---|---|---|
| Q1 | 프리셋 BPM 매핑 — `떼창`/`도입` 신규 `moodBpm` 키 값 | (a) `떼창=120 / 도입=105` 초안 채택(권고) / (b) song-curation seed BPM 분포 측정 후 보정 | @goohong / PR 3 |
| Q2 | 프리셋 ↔ `Mood` 매핑 다대일 처리 — `PARTY`→`UPBEAT|GROOVY` 처럼 후보 다수일 때 | (a) 단일 enum 고정(권고, PoC) / (b) 프리셋당 복수 `Mood` OR 매칭(추천 후보 확대, 신호 조정 필요) | @goohong / PR 2 |
| Q3 | 청중 연령대 처리 — 수집/표시 vs 추천 신호화 vs 영속 | (a) 클라이언트 세션 옵션·수집만(권고, PoC) / (b) 요청 필드 추가 + 신호화(별 결정) / (c) 익명 세션 필드 영속(migration) | @goohong / PR 4 |
| Q4 | 활용 팁 시드 출처 — 곡별 무대 팁 | (a) 프리셋 톤 + 수기 시드(권고, PoC) / (b) audio-analysis 구간 자동 산출(후속) | @goohong / PR 5 |
| Q5 | F2 모드 picker 와 본 프리셋 picker 통합 — 한 화면에 모드+분위기 | (a) 경로별 분리 유지(권고, 페르소나 동기 분리) / (b) 통합 picker(`high-note-training-mode.md §8 Q1` 와 동반 결정) | @goohong / PR 2 |

## 9) 결정 로그
> 연대기 순. "YYYY-MM-DD: 결정 / 이유 / 출처(PR 번호 등)"

- **2026-06-03**: 초안 작성(status=draft). 부모 `user-persona-and-pain-points.md §5 F3` 분기 + `first-user-onboarding-flow.md` MOOD(P-B) 경로 핸드오프 목적지로 신설. 분위기 프리셋(`PARTY`/`SINGALONG`/`EMOTIONAL`/`ICEBREAKER`)을 **기존 `Mood` enum + `preferredBpm` 위 사용자-페이싱 view** 로 설계 — 신규 enum 값·BE 엔티티 없이 기존 `moodMatch`/`tempoMatch` 신호 재사용(추천 알고리즘 무변경, 결정성/p95 회귀 없음). `06-domain-model.md §4-1` 에 `MoodPreset`/`AudienceProfile`(설계 단계) 등재 동반.
- **2026-06-03**: **온보딩 `first-user-onboarding-flow.md §8 Q4`(P-B 측정 위치) 확정 = (a) 음역 측정을 마지막 경량 단계로 유지**.
  - 근거(evidence): (1) `06-domain-model.md §5-3` — `RecommendationRequestEntity.voiceRangeLow/voiceRangeHigh` 가 `int, not null`. 추천 요청이 음역을 **구조적으로 필수** 로 요구한다. (2) `application.yml` 가중치 — `voice-fit: 0.5` 가 1순위(dominant) 신호. (3) 따라서 선택지 (b)광역 default 음역은 voiceFit 을 균일 포화시켜 dominant 신호를 노이즈로 만들고 추천 품질을 떨어뜨린다. (c)음역 optional 추천 분기는 dominant 신호 + score 정규화 + 결정성 가드를 건드리는 **별 규모 결정**(recommendation scope)으로 본 spec 밖.
  - 결론: P-B 경로는 음역을 "분위기 다 골랐고 마지막으로 목소리만" 으로 **가볍게 프레이밍해 마지막에** 받는다. 추천 알고리즘 무변경 + 안전한 PoC 경로. 출처: 본 PR, 부모 spec §8 Q4 / §5-4.
- **2026-06-03 (BE 1단계 구현)**: 프리셋→`Mood`/`preferredBpm` 매핑의 BE 단일 출처를 **읽기 전용 카탈로그 엔드포인트 `GET /api/v1/mood-presets`** 로 신설(초안 §5-2 의 "신규 endpoint 없음" 을 갱신). 출처: 본 BE PR(#1562 1단계).
  - 변경 사유: 초안은 매핑을 fe `web/lib/moodPreset.ts` 단독에 두고 BE 는 BPM 키만 보강하려 했으나, §3 비기능 "프리셋↔`Mood`/`preferredBpm` 매핑 단일 출처(fe/be drift 방지)" 를 만족하려면 BE 가 권위 있는 매핑을 노출하는 편이 안전하다. 카탈로그는 추천을 호출하지 않고 매핑만 반환하므로 추천 신호·산식·가중치·결정성/p95 가드에 무영향.
  - `preferredBpm` 단일 출처 결정 = **기존 `recommendation.tempo.moodDefaultBpm`(프리셋의 `Mood` 키)에서 해석** (Q1 권고안 (a) 의 별도 `moodBpm` 키 추가 방식 폐기). 이유: 신규 BPM 표는 tempo 신호 설정과 또 하나의 진실원을 만들어 drift 위험. 기존 tempo 표를 재사용하면 프리셋 BPM 이 `tempoMatch` 신호와 항상 일치하고 `application.yml`(보호 영역) 변경도 불요. moodDefaultBpm 키 부재 시 `fallbackBpm` 폴백. (§8 Q1 → 결정: tempo 표 재사용으로 close.)
  - 신규: `recommendation` 패키지 `MoodPreset` enum(프리셋→`Mood` 매핑 SoT, §8 Q2 권고안 (a) 단일 enum 고정 채택: PARTY→UPBEAT / SINGALONG→POWERFUL / EMOTIONAL→EMOTIONAL / ICEBREAKER→GROOVY) + `MoodPresetCatalog`(BPM 해석) + `MoodPresetController` + RestAssured E2E.

## 10) 다음 단계
1. 본 PR 머지 후 온보딩 PR 4(MOOD 경로 wiring)와 본 spec PR 2(`<MoodPresetPicker>`)를 동기 fe 사이클로 launch 가능.
2. PR 3(프리셋 카탈로그 엔드포인트)은 추천 신호 무변경·읽기 전용이라 보호 영역 변경이 없다. fe PR 2 는 `/api/v1/mood-presets` 를 소비하거나 동일 매핑을 미러해 drift 가드를 닫는다.
3. Q3(청중 연령대)·Q5(F2 통합)는 PR 진입 전 권고안(각 a)로 빠르게 닫는 것을 권한다.
4. `v03-roadmap.md` 매트릭스 F3 행에 본 spec 진척 반영(다음 plan 사이클, 부모 §10 과 동반).
