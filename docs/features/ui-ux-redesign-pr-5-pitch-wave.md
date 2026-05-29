---
feature: UI/UX redesign 단계 4 PR 5 — VoiceRangeAuto pitch wave 시각화 (circular SVG ring)
slug: ui-ux-redesign-pr-5-pitch-wave
status: approved
owner: @goohong
scope: web
related_issues: [1044]
related_prs: []
last_reviewed: 2026-05-29
---

# UI/UX redesign 단계 4 PR 5 — VoiceRangeAuto pitch wave 시각화 (circular SVG ring)

## 1) 개요 (What / Why)

`docs/features/ui-ux-redesign.md` §6 PR 5 (VoiceRangeAuto pitch wave) 단독 SoT. `ui-ux-redesign-pr-2-9-component-matrix.md` (6 PR 묶음) 에서 분리한 사유:
- **Web Audio API 의존** + 마이크 권한 회귀 가드 (현 `web/app/voice-range/auto/page.tsx` 의 race condition / `page.race.test.tsx` 박제 존재)
- **실시간 SVG ring 렌더링** — frequency → ring path 매핑 알고리즘 (MIDI 변환 + ring 좌표 계산 + 60fps 유지)
- **circular progress + frequency bar 이중 시각화** — pitch ring (음역대 위치 표시) + amplitude bar (실시간 frequency 강도) 두 layer 동시
- **4 step wizard progress** + reduced-motion 대응
- 신규 컴포넌트 `PitchWaveRing.tsx` 신설 (~250 LOC) — 가장 큰 단일 PR

도메인 모티프: 현 wizard 가 "Hz 텍스트 + 음표명" 만 보여줘 측정 과정이 "재미 없는 form" — pitch wave ring 으로 (1) 본인 음역대 시각 인지 (2) 실시간 음정 변화 시각화 (3) 4 step progress ring fill 로 측정 진행 체감 = **시나리오 A "측정 wow" 충족** (사용자 박제 의도).

대상 액터: fe sub-agent (구현) / rev sub-agent (audit — Web Audio race / 마이크 권한 / 60fps / 알고리즘 결정성 5 회귀 가드) / plan sub-agent (본 spec 갱신) / nmae (백로그 launch).

### 본 spec 의 역할 vs 기존 SoT

| SoT | 역할 |
|---|---|
| `docs/features/ui-ux-redesign.md` | 상위 SoT — 4 단계 흐름 + PR 5 골자 (link only) |
| `docs/decisions/0018-design-tokens.md` | token 결정값 SoT — `--brand-gradient` / `--brand-500` / `--success-500` 등 |
| `docs/features/design-tokens-residual-swap-matrix.md` | swap 진행도 SoT (PR 5 = voice-range 페이지 zinc swap 영향 0, 사전 게이트 약함) |
| `docs/features/voice-range-auto-measurement.md` | 자동 측정 도메인 SoT — 본 spec 의 알고리즘 / 마이크 / MIDI 변환은 기존 SoT 보존 |
| `docs/features/ui-ux-redesign-pr-2-9-component-matrix.md` | 분리 spec — PR 2/3/6/7/8/9 묶음 (본 spec 은 PR 5 단독) |
| **본 spec** | **PR 5 단독 SoT** — PitchWaveRing 컴포넌트 / SVG 렌더링 / 4 step progress / 회귀 가드 |

## 2) 사용자 시나리오

### 시나리오 A: 첫 측정 사용자 — 음정 변화 실시간 시각화
"`/voice-range/auto` 첫 진입 → 마이크 권한 요청 (브라우저 native) → 권한 승인 시 큰 circular pitch ring 이 화면 중앙 표시. ring 내부에 step indicator (1 of 4) + 안내 텍스트 ('편안한 음으로 아~ 발성해보세요'). 마이크 입력 시 ring 위에 indicator dot 이 본인 현재 음정 위치 (낮은음 ~ 높은음) 으로 움직임 + indicator 색이 본인 amplitude 에 따라 brand 색 강도 변화. 각 step 완료 시 ring fill 25% 씩 채워지며 '✅' + sparkle. 4 step 완료 = 100% fill + '측정 완료' 큰 텍스트 + brand gradient flash."

### 시나리오 B: 재측정 사용자 — 이전 결과 비교
"이전 측정 결과 (low/high MIDI) 가 본인 프로필에 저장된 상태. 재측정 시 ring 에 이전 음역대 (low ~ high) 가 outline (점선) 으로 표시 — '이전: G2~F#4' label. 신규 측정 indicator 가 ring 위에서 움직이며 이전 범위와 비교 — '아 이번엔 더 높게 나오네' 직관."

### 시나리오 C: 접근성 사용자 — reduced-motion + 스크린리더
"`prefers-reduced-motion: reduce` 설정 사용자 = ring fill animation 0ms (즉시 채워짐) + indicator dot 위치 변경 transition 0ms. 스크린리더 사용자 = ring 은 `role="img"` `aria-label="음역대 측정 진행 N% — 현재 음정 G3"` 동적 갱신 (debounce 500ms)."

## 3) 요구사항

### 기능 요구사항

#### 사전 게이트
- [ ] **`design-tokens-residual-swap-matrix.md` 단계 4 swap 완결 게이트 통과 권장** — 단 voice-range 페이지는 swap 영향 거의 0 (페이지 잔여 swap = `history` 만), 사전 게이트 약함
- [ ] `voice-range-auto-measurement.md` 의 자동 측정 알고리즘 (autocorrelation pitch detection / MIDI 변환) 보존 의무 — 본 PR 은 시각화 layer 만 추가, 알고리즘 변경 X

#### PitchWaveRing 컴포넌트
- [ ] 신규 `web/components/voice/PitchWaveRing.tsx` 신설 + `PitchWaveRing.test.tsx`
- [ ] API: `<PitchWaveRing lowMidi={number} highMidi={number} currentFrequencyHz={number|null} progressPercent={number} previousRangeMidi?={[low, high]} />`
- [ ] SVG circular ring (radius 120 / strokeWidth 12 / 270deg arc — `-135deg ~ 135deg`)
- [ ] ring 위 indicator dot — currentFrequencyHz 의 MIDI 변환값 → ring 좌표 계산 (linear interpolation)
- [ ] amplitude → indicator dot 색 강도 (`--brand-500` ~ `--brand-700`)
- [ ] previousRangeMidi 가 있으면 ring 에 outline (점선 stroke) overlay

#### 4 step progress ring
- [ ] progressPercent 0 ~ 100 → ring stroke-dasharray 로 fill percentage 표현
- [ ] 25% / 50% / 75% / 100% 분기점에 ✅ + sparkle (CSS-only @keyframes)
- [ ] 100% 도달 시 brand gradient flash (`@keyframes brand-flash` — opacity 0 → 1 → 0 600ms)

#### Step indicator
- [ ] ring 내부 중앙 - 큰 step number (1 of 4 / 2 of 4 / ...) + 안내 텍스트
- [ ] step 전환 시 fade-in 200ms (reduced-motion 시 0ms)
- [ ] step indicator typography = `--text-display --font-black` (PR 8 brand wordmark token 동일)

#### 실시간 frequency bar (옵션 — 자율 결정 default = 포함)
- [ ] ring 하단 별도 horizontal bar — 실시간 frequency 강도 (amplitude) 막대 길이 표현
- [ ] bar 색 = `--brand-gradient` (왼쪽 brand-500 → 오른쪽 brand-700)
- [ ] 60fps 유지 (requestAnimationFrame throttle)

#### a11y
- [ ] ring: `role="img"` + `aria-label` 동적 갱신 (debounce 500ms — 스크린리더 spam 회피)
- [ ] step indicator: `aria-current="step"` (현 wizard 에 부재 — 본 PR 에서 보강)
- [ ] `prefers-reduced-motion: reduce` 시 모든 animation 0ms (단 indicator dot 위치 즉시 갱신은 유지 — pitch 정보 본질)
- [ ] 마이크 권한 거부 시 명확한 fallback UI + 텍스트 측정 옵션 제공 (현 페이지 회귀 보존)

#### Web Audio API 회귀 보존
- [ ] 기존 `page.tsx` + `page.race.test.tsx` race condition 박제 회귀 0
- [ ] 마이크 권한 (granted / denied / dismissed) 3 분기 모두 회귀 0
- [ ] AudioContext suspended → resume 사이클 회귀 0

### 비기능 요구사항

- **성능**: SVG ring + indicator dot + frequency bar 60fps 유지 (requestAnimationFrame 동기화). DOM 직접 manipulation X — React state 만 (작은 컴포넌트, re-render 비용 ↓).
- **알고리즘 결정성**: frequency Hz → MIDI 변환 = `12 * log2(hz / 440) + 69` 고정 공식. MIDI → ring 좌표 = linear interpolation `(midi - lowMidi) / (highMidi - lowMidi)`. 결정성 테스트 필수.
- **회귀 0**: `voice-range-auto-measurement.md` 알고리즘 / `page.race.test.tsx` race condition / 마이크 권한 회귀 모두 보존.
- **접근성 WCAG 2.1 AA**: `role="img"` aria-label / `aria-current="step"` / reduced-motion / 마이크 권한 거부 fallback.
- **다크 모드**: ring / dot / bar 모두 token 사용 (자동 swap).
- **번들 사이즈**: CSS-only animation + 자체 SVG 렌더링. 외부 라이브러리 (d3 / visx) 도입 X.

## 4) 범위 / 비범위 (중요)

### 포함
- `web/components/voice/PitchWaveRing.tsx` + `.test.tsx` 신설
- `web/app/voice-range/auto/page.tsx` 통합 — 기존 텍스트 (Hz / 음표명) → PitchWaveRing 시각화로 교체
- 4 step progress ring fill + sparkle (CSS-only)
- 실시간 frequency bar (옵션 — default 포함)
- `aria-current="step"` 보강 (현 부재 — `ui-ux-redesign.md §3 접근성` SoT)
- previousRangeMidi outline overlay (이전 측정 결과 비교)

### 제외 (Out of Scope)
- **알고리즘 변경** — `voice-range-auto-measurement.md` SoT 알고리즘 (autocorrelation / MIDI 변환) 보존. 본 PR 은 시각화 layer 만.
- **음역대 결정 로직 변경** — measure 완료 후 low/high MIDI 결정은 기존 페이지 로직 유지
- **마이크 권한 UX 변경** — 본 PR 은 권한 부여 후 시각화만. 권한 거부 fallback UI 는 기존 회귀 보존만.
- **이전 결과 fetch 로직** — previousRangeMidi 가 props 로 주어지면 표시, fetch 로직 자체는 기존 페이지 또는 별도 PR (현재는 props default null)
- **공유 / 비교 페이지** — 이전 측정 결과 갤러리 등은 별도 spec (`history` 페이지 redesign 후속)
- **multi-pitch (코드 / 화음)** — 본 PR 은 단일 pitch 측정만 (도메인 동일)
- **외부 라이브러리** (d3 / visx / framer-motion) — 본 PR default = 자체 SVG + CSS-only

## 5) 설계

### 5-1) 컴포넌트 구조

```
PitchWaveRing (신규)
├─ svg (viewBox "-150 -150 300 300", role="img", aria-label dynamic)
│  ├─ <circle> base ring (stroke=var(--bg-subtle), strokeWidth=12)
│  ├─ <circle> progress ring (stroke=var(--brand-500), strokeDasharray=progressPercent)
│  ├─ <circle> previous range outline (stroke=var(--text-tertiary), strokeDasharray="4 4")
│  └─ <circle> indicator dot (cx,cy = MIDI→ring 좌표, fill=var(--brand-500))
├─ div center (step indicator + 안내 텍스트, aria-current="step")
└─ div frequency bar (실시간 amplitude, role="presentation")
```

### 5-2) 핵심 알고리즘

#### Frequency Hz → MIDI 변환
```ts
function hzToMidi(hz: number): number {
  return 12 * Math.log2(hz / 440) + 69
}
```

#### MIDI → ring 좌표 (270deg arc, -135deg ~ 135deg)
```ts
function midiToRingCoord(
  midi: number,
  lowMidi: number,
  highMidi: number,
  radius: number,
): { cx: number; cy: number } {
  const percent = (midi - lowMidi) / (highMidi - lowMidi) // 0 ~ 1
  const angleDeg = -135 + percent * 270 // -135 ~ 135
  const angleRad = (angleDeg * Math.PI) / 180
  return {
    cx: radius * Math.sin(angleRad),
    cy: -radius * Math.cos(angleRad),
  }
}
```

#### Progress ring stroke-dasharray
```ts
const circumference = 2 * Math.PI * radius * (270 / 360) // 270deg arc 길이
const dashOffset = circumference * (1 - progressPercent / 100)
// stroke-dasharray={circumference}, stroke-dashoffset={dashOffset}
```

### 5-3) 회귀 가드 매트릭스 (rev 단계 1 grep 패턴)

| # | 가드 항목 | rev grep / 체크 |
|---|---|---|
| G1 | hzToMidi 결정성 | `PitchWaveRing.test.tsx` 안에 `expect(hzToMidi(440)).toBe(69)` + edge case (A0=27.5Hz → 21, C8=4186Hz → 108) verify |
| G2 | midiToRingCoord 결정성 | `expect(midiToRingCoord(60, 48, 72, 120)).toEqual({ cx: 0, cy: -120 })` (중간 = 위쪽 12시) 등 4 분기점 verify |
| G3 | progressPercent 0/100 edge | 0 → dashOffset = circumference (보이지 않음), 100 → dashOffset = 0 (꽉 참) verify |
| G4 | aria-label dynamic | currentFrequencyHz 변경 시 aria-label 업데이트 verify (debounce 500ms) |
| G5 | reduced-motion | matchMedia mock → animation 0ms + sparkle 비활성 verify |
| G6 | 마이크 권한 거부 | 기존 `page.race.test.tsx` race condition 회귀 0 + 권한 거부 시 fallback UI 표시 verify |
| G7 | AudioContext suspended | suspend → resume 사이클 시 PitchWaveRing currentFrequencyHz null → 정상값 전이 회귀 0 |
| G8 | 60fps 유지 | DevTools Performance Recording — frame drop 0 (수동, rev 단계 2 e2e) |
| G9 | previousRangeMidi outline | props 가 null 시 outline 비표시 / 값 있으면 outline 표시 verify |
| G10 | aria-current="step" | step 전환 시 `aria-current="step"` attribute 갱신 verify (현 wizard 부재 → 보강 의무) |
| G11 | 다크 모드 token swap | matchMedia dark mock → ring/dot/bar 토큰 자동 swap verify |
| G12 | step 완료 sparkle | progressPercent 25/50/75/100 도달 시 sparkle keyframe 부착 verify |

### 5-4) Visual ref + 디자인 결정

| ref | 인용 패턴 | 본 PR 적용 |
|---|---|---|
| **Apple Health** activity ring | 270deg arc + 컬러 fill + animated progress | ring 기본 구조 채택 |
| **Apple Music** | circular play button + ripple | step indicator 중앙 — circular 강조 |
| **카카오뱅크** | wizard 위쪽 progress bar + "Step N: ..." 큰 라벨 | step indicator 텍스트 패턴 |
| **토스** | progress ring fill spring easing | progress 채워질 때 `--ease-emphasized` 300ms |
| **Garmin / Strava** | activity ring 위 indicator dot | indicator dot — 본인 현재 음정 위치 표시 |
| **Spotify** wave visualizer | 실시간 amplitude bar | frequency bar (옵션 — default 포함) |
| **iOS HIG** | 44px 최소 터치 타겟 | step indicator 가 큰 시각만 (탭 X) — 본 ring 은 정보 표시 전용 |

### 5-5) DB 마이그레이션
해당 없음 (web 시각화 layer 전용 — 데이터 흐름 변경 0).

### 5-6) 프론트엔드 화면

| 라우트 | 영향 |
|---|---|
| `/voice-range/auto` | 신규 PitchWaveRing 통합 — 기존 텍스트 (Hz / 음표명) 시각화로 교체 |
| `/voice-range` (수동) | 영향 0 (수동 측정은 별도 페이지) |
| `/history` (이전 측정 비교) | 영향 0 (본 PR 의 previousRangeMidi 는 페이지 prop 으로 주어짐 — fetch 로직은 별도) |

단일 라우트 영향 (`/voice-range/auto`) = scope 명확.

## 6) 작업 분할 (예상 PR 리스트)

### 본 spec 추적 1 PR

| PR | 이름 | LOC | 의존 | risk | 우선순위 |
|---|---|---:|---|---|---|
| 5 | VoiceRangeAuto pitch wave 시각화 (PitchWaveRing 신설 + page.tsx 통합) | ~250 (PitchWaveRing) + ~50 (page.tsx) + ~80 (테스트) | swap-matrix 완결 권장 (영향 약함) + PR 2 (Button 통합) | **높** | P3 (PR 4 머지 후) |

### 단일 PR 권장 사유
PitchWaveRing 신설 + page.tsx 통합 + 4 step progress + sparkle + frequency bar = 한 도메인 (자동 측정 시각화) 안에서 일관성. 분할 시:
- 단계 1: PitchWaveRing 신설 (LOC 250, 단독 미통합 — 사용 안 됨 dead code)
- 단계 2: page.tsx 통합 (LOC 50, PitchWaveRing 없으면 의미 X)
→ 두 단계 한 PR 안 권장 (총 LOC ~380, 5분 reasoning 한도 안 가능).

### 후속 PR 후보 (본 PR 머지 후)

- **previousRangeMidi fetch 로직** — `/history` 마지막 측정 결과 자동 fetch + props 전달 (별도 사이클, LOC 적음)
- **measure 결과 share image generation** — Open Graph image 자동 생성 (별도 spec)
- **multi-pitch 측정** (코드 / 화음) — 도메인 자체 확장, 별도 spec
- **PitchWaveRing 의 view-transition 통합** — `ui-ux-redesign-pr-2-9-component-matrix.md` PR 7 통합 시 hero morph 후보 (홈 → /voice-range/auto 진입 시 ring expansion)

### 보호 영역 변경 여부 (필수 명시)

- 본 PR: 없음 (`web/app/voice-range/**` + `web/components/voice/**` 만 변경)
- 본 spec 자체: 없음 (`docs/features/*` 만 신설)

## 7) 테스트 전략

### 단위 / 통합 테스트
- 신규 `web/components/voice/PitchWaveRing.test.tsx` — §5-3 G1~G12 12 가드 모두 verify
- 신규 `web/components/voice/PitchWaveRing.helpers.test.ts` (hzToMidi / midiToRingCoord 결정성)
- 기존 `web/app/voice-range/auto/page.test.tsx` + `page.race.test.tsx` 회귀 0 — 본 PR 변경은 시각화 layer 만, 페이지 도메인 로직 회귀 risk 0

### E2E (rev 단계 1 e2e)
- `/voice-range/auto` 진입 → 마이크 권한 요청 (Chrome DevTools mock)
- 권한 승인 → PitchWaveRing 표시 + step 1 indicator
- 마이크 simulation (Web Audio API mock) → indicator dot 위치 변경 시각
- 4 step 완주 시뮬레이션 → progress ring 25/50/75/100% fill 시각 + sparkle
- 측정 완료 → brand gradient flash 시각
- 권한 거부 → fallback UI (텍스트 측정 옵션) 회귀 보존
- 다크 모드 토글 — ring/dot/bar token 자동 swap
- `prefers-reduced-motion: reduce` 시 animation 0ms

### 시각 회귀
- 자동 (Percy / Chromatic) 미적용 — `ui-ux-redesign.md §7` 와 동일 정책
- 수동: ring 표시 / indicator dot 이동 / progress fill / sparkle / brand flash / 다크 모드 / reduced-motion 7 case

### 외부 연동 mock 전략
- Web Audio API mock — 기존 `page.race.test.tsx` 박제 패턴 보존 + PitchWaveRing 통합 회귀 가드 추가
- `navigator.mediaDevices.getUserMedia` mock — 권한 granted / denied / dismissed 3 분기 회귀 가드

## 8) 오픈 질문

| # | 질문 | 선택지 | 담당/기한 |
|---|---|---|---|
| Q1 | 알고리즘 라이브러리 도입 — pitchy (~10KB) vs 자체 autocorrelation | (a) 자체 autocorrelation 유지 (자율 default — 기존 알고리즘 회귀 0) / (b) pitchy 라이브러리 도입 (정밀도 ↑ but 회귀 risk) | fe sub-agent / PR 5 launch 시 — 본 PR 범위 외 (algorithm 변경 X), (a) 강제 default |
| Q2 | frequency bar 포함 여부 | (a) 포함 (자율 default — Spotify wave 패턴, 시각 풍성) / (b) ring 만 (단순성 ↑) | fe sub-agent / PR 5 launch 시 (자율 default 채택 가능) |
| Q3 | previousRangeMidi outline — 본 PR 포함 vs 후속 PR | (a) 본 PR 포함 (자율 default — props default null, fetch 로직 별도) / (b) 후속 PR | fe sub-agent / PR 5 launch 시 |
| Q4 | step indicator 텍스트 다국어 | (a) 한글 fixed (자율 default — `ui-ux-redesign.md` 국제화 범위 외) / (b) i18n 키 (별도 spec) | fe sub-agent / PR 5 launch 시 |
| Q5 | sparkle 방식 — CSS divs vs SVG | (a) CSS divs (자율 default — `pr-2-9-component-matrix.md` Q5 일치) / (b) SVG `<circle>` 동적 생성 | fe sub-agent / PR 5 launch 시 |
| Q6 | brand gradient flash 100% 도달 시 - 진동 / 사운드 추가 | (a) 시각 only (자율 default — 본 PR 단순) / (b) navigator.vibrate 추가 (LikeButton PR 6 일치) / (c) Web Audio 측정 완료음 (도메인 영향) | fe sub-agent / PR 5 launch 시 → (a) → 후속 PR 후보 |

### 자율 결정 default (질문 미해소 시 사용자 부재 가정 자율 결정)
- Q1 → (a) 자체 autocorrelation 유지 (강제)
- Q2 → (a) frequency bar 포함
- Q3 → (a) 본 PR 포함 (props default null)
- Q4 → (a) 한글 fixed
- Q5 → (a) CSS divs
- Q6 → (a) 시각 only → 후속 PR 후보

## 9) 결정 로그

- **2026-05-29**: 초안 작성 (status=approved). `ui-ux-redesign.md §6 PR 5` 골자를 단독 spec 으로 분리 — Web Audio race / 마이크 권한 / 60fps / 알고리즘 결정성 5 회귀 가드 깊이 + 신규 컴포넌트 LOC 250 + 단일 라우트 scope 명확 = 묶음 spec 평균 깊이 초과로 단독 spec 채택.
- **2026-05-29**: 알고리즘 변경 = 본 PR 범위 외 (`voice-range-auto-measurement.md` SoT 보존). 본 PR 은 시각화 layer 만.
- **2026-05-29**: 자율 결정 default 6건 박제 (autocorrelation 유지 / frequency bar 포함 / props default null / 한글 fixed / CSS divs / 시각 only).
- **2026-05-29**: 회귀 가드 매트릭스 12 항목 박제 (G1~G12) — 결정성 / aria / reduced-motion / 권한 / 60fps 5 카테고리.
- **2026-05-29**: 단일 PR 권장 — PitchWaveRing 신설 + page.tsx 통합 한 도메인 일관성, 분할 시 dead code risk.
