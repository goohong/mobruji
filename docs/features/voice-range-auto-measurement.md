---
feature: 음역대 자동 측정 (voice-range-auto-measurement)
slug: voice-range-auto-measurement
status: draft
owner: "@goohong"
scope: voice
related_issues: [143]
related_prs: [144]
last_reviewed: 2026-05-21
---

# 음역대 자동 측정 (voice-range-auto-measurement)

## 1) 개요 (What / Why)
- v0.1 `voice-range-input` 기능은 사용자가 자신의 최저/최고 음을 select로 직접 골라 입력한다. 하지만 핵심 가정(`voice-range-input.md §1`)대로 **대부분의 사용자는 자기 음역대를 모른다**. 결과적으로 가장 마찰이 큰 단계.
- Vanido 영감(`docs/research/external-service-inspirations.md` A-1 / F-1) — 브라우저 마이크로 짧은 발성을 받아 pitch detection으로 `lowMidi/highMidi`를 자동 추정하면 select 노가다를 제거할 수 있다.
- 본 spec은 **자동 측정 페이지(`/voice-range/auto`)** 신규 추가가 목표. 측정 결과는 기존 `VoiceRange` 등록 API(`POST /api/v1/voice-ranges`)에 그대로 흘려보낸다. 백엔드 도메인 변경 없음.
- 대상 액터: 노래방 직전 "내 음역대 모르겠는데 그냥 빨리 측정해서 추천 받고 싶다" 상태의 사용자.

## 2) 사용자 시나리오
1. **온보딩 자동 측정** — 첫 진입한 사용자가 `/voice-range/intro`에서 "자동 측정" CTA를 누른다 → `/voice-range/auto` 진입 → 마이크 권한 허용 → 가이드대로 낮은 음/높은 음을 5~10초씩 발성 → 결과 `lowMidi/highMidi` 확인 + 슬라이더 보정 → 저장 → 추천 페이지로.
2. **권한 거부 fallback** — 사용자가 마이크 권한을 거부하거나 환경(데스크탑 외부 마이크 없음)이 안 맞다 → "수동 입력으로 이동" 안내 → 기존 `/voice-range` select UX로 redirect.
3. **재측정** — 추천 결과 페이지의 "다시 측정하기" 진입점에서 `/voice-range/auto`로 진입, 측정 결과로 기존 `VoiceRange`를 `PUT`로 덮어쓴다 (v0.1 정책 = 최신 1건만 보관, `voice-range-input.md §9 Q4`).

## 3) 요구사항

### 기능 요구사항
- [ ] `/voice-range/auto` 페이지 신규 추가 (Next.js App Router 라우트).
- [ ] Web Audio API + `getUserMedia({ audio: true })`로 마이크 입력 캡처. 권한 거부 시 fallback 분기.
- [ ] 실시간 pitch detection (라이브러리 결정은 §8 Q1) — 매 분석 프레임당 Hz 추정.
- [ ] Hz → MIDI note number 변환 (정수 반올림 + 안정성 필터). 백엔드 `lowestNoteMidi/highestNoteMidi`와 동일 단위([12, 119]).
- [ ] 측정 시나리오 2단계 분리: (1) "낮은 음부터 천천히 내려가세요" → 안정 최저 MIDI, (2) "이번엔 가장 높은 음" → 안정 최고 MIDI.
- [ ] 실시간 pitch 시각화 (현재 발성 음표 + Hz 표시).
- [ ] 측정 종료 후 결과 `lowMidi/highMidi`를 노출, **수동 보정 슬라이더**로 ±N semitone 조정 가능.
- [ ] 신뢰도 표시 — 측정 안정성(분산/노이즈) 기준으로 "안정/낮음" 라벨. "낮음"이면 "다시 측정" 권장 배너.
- [ ] 저장 버튼 → 기존 `POST /api/v1/voice-ranges` 또는 `PUT /api/v1/voice-ranges/{sessionId}` 호출(상황별), 성공 시 추천 페이지로 라우팅.
- [ ] 기존 `/voice-range` 페이지에 "자동 측정으로 시작하기" CTA 추가 (수동 입력은 유지).

### 비기능 요구사항
- 모바일 우선. **iOS Safari + Android Chrome** 둘 다 동작해야 함 (Web Audio + getUserMedia 호환성 확인 필수).
- **개인정보**: 마이크에서 캡처한 audio 스트림은 **브라우저 내에서만 처리**하고 서버에 업로드하지 않는다(`docs/ai-harness/04-security-policy.md` §개인정보 최소화). 서버에는 산출된 `lowMidi/highMidi` 정수만 전송.
- p95 측정 응답 지연(발성 끝 → 결과 화면) < 500ms.
- 권한 거부/장치 미지원 시 UI는 1초 안에 fallback 안내로 전환.
- a11y: 마이크 권한 상태/측정 상태를 스크린리더로도 전달 (aria-live).
- 측정 도중 오류(WebAudio context 실패, 라이브러리 예외)는 로그(에러 메시지만, audio 스트림 metadata는 금지)로 남기고 fallback 안내.

## 4) 범위 / 비범위 (중요)

### 포함
- `/voice-range/auto` 페이지 + 측정 UX (낮은음/높은음 2단계).
- 클라이언트 사이드 pitch detection 라이브러리 도입 + Hz → MIDI 변환.
- 수동 보정 슬라이더, 신뢰도 표시.
- 기존 `/voice-range` select 페이지에 자동 측정 CTA 진입점 추가.
- 기존 `POST/PUT /api/v1/voice-ranges` API에 측정 결과 연결 (백엔드 변경 없음).

### 제외 (Out of Scope)
- **서버 사이드 audio 분석** — 본 spec은 100% 클라이언트 사이드. 자체 곡 분석 파이프라인(`song-self-analysis-pipeline.md`)과 분리.
- **노래 부르며 측정** — Vanido도 노래가 아닌 단순 모음 발성으로 측정한다. 노래 부르며 음역 추정은 별 spec.
- **측정 이력 보관 / 회고 카드** — `voice-range-input.md §9 Q4` 결정대로 v0.1은 "최신 1건"만 영속화. 회고 시각화는 F-4(`voice-range-progress-tracking.md`, v0.3 후보)에서 다룬다.
- **음역대 자동 측정 → 추천 알고리즘 변경** — 측정 결과는 동일 `lowestNoteMidi/highestNoteMidi`로 들어가므로 추천 알고리즘은 영향 없음.
- **마이크 보정/캘리브레이션** — 디바이스별 마이크 특성 차이 보정은 v0.3+로 미룬다.
- **회원가입/인증 변경** — `voice-range-input.md §9 Q2` 결정(익명 세션)을 유지. sessionId는 동일.

## 5) 설계

### 5-1) 도메인 모델
- **신규 엔티티 없음**. 백엔드 `VoiceRange` 그대로 사용.
- 유비쿼터스 랭귀지 후보 (도메인 모델 §4 등재 필요):
  - `PitchDetection` — 실시간 발성 → Hz/MIDI 추정 과정.
  - `MeasurementSession` — 자동 측정 1회 세션(낮은음 + 높은음 phase). 클라이언트 한정 개념.
- `sourceMethod`(`voice-range-input.md §5-5`의 enum) 값은 v0.1에서 미사용. 본 spec 단계에서도 백엔드 컬럼 추가는 하지 않는다(스코프 분리). 단 클라이언트 store에서 측정 방식을 메타로 보관해 추후 백엔드 확장 시 활용.

### 5-2) API 엔드포인트
- **신규 API 없음**. 기존 v0.1 API 재사용.

| Method | Path | 사용 시점 | 비고 |
|---|---|---|---|
| POST | /api/v1/voice-ranges | 첫 측정 후 저장 | 기존 |
| PUT | /api/v1/voice-ranges/{sessionId} | 재측정 후 덮어쓰기 | 기존 |
| GET | /api/v1/voice-ranges/{sessionId} | 측정 페이지 진입 시 기존값 prefill | 기존 |

### 5-3) 외부 연동
- **라이브러리 후보** (§8 Q1에서 결정):
  - (a) **Pitchy** (npm, MIT) — 가벼움(~5KB gz), autocorrelation 기반, TypeScript 친화. v0.1 단계 강력 후보.
  - (b) 자체 autocorrelation 구현 — 의존성 0, 학습 가치 있으나 구현/테스트 부담.
  - (c) `aubio.js` (WASM) — 정확도는 좋으나 번들 크기 큼, 모바일 부담.
- **작성자 권고**: **(a) Pitchy**. 이유 — v0.1 PoC 단계에서 검증 우선, 번들 영향 최소, 라이브러리 자체 테스트 신뢰도 충분. ADR 후보 `0011 pitch detection 라이브러리 채택`.
- API 키 없음(로컬 라이브러리). 실패 처리는 try/catch + fallback 안내.

### 5-4) 데이터 흐름 / 시퀀스

```
[사용자] /voice-range/auto 진입
[브라우저] getUserMedia({audio:true}) → 권한 요청
  ↳ 거부 → /voice-range (수동 입력) redirect
[브라우저] AudioContext + AnalyserNode 셋업 → Pitchy로 매 프레임 Hz 추정
[브라우저] phase 1: 낮은음 측정 (5~10초) → midiSamples[] 수집 → 안정 최저 MIDI 산출
[브라우저] phase 2: 높은음 측정 (5~10초) → midiSamples[] 수집 → 안정 최고 MIDI 산출
[브라우저] 결과 화면 → 슬라이더 보정 → 저장 버튼
[브라우저] POST/PUT /api/v1/voice-ranges {sessionId, lowestNoteMidi, highestNoteMidi}
[Spring] 기존 처리 그대로 → 응답
[브라우저] 추천 페이지로 라우팅
```

- 원본 audio 샘플은 브라우저 메모리에서만 다루고 즉시 폐기. 서버에는 정수 2개만 전송.

### 5-5) DB 마이그레이션
- **없음.** 본 spec 단계에서는 기존 `voice_range` 테이블 그대로 사용.
- 추후 측정 방식 메타(`sourceMethod`) 컬럼 추가는 별 spec으로 분리 (F-4 진행 시점).

### 5-6) 프론트엔드 화면 (해당 시)
- 신규 라우트: `web/src/app/voice-range/auto/page.tsx`
- 신규 유틸:
  - `web/src/lib/audio/pitchDetector.ts` — Pitchy wrap, `start() / stop() / onPitch(callback)` 인터페이스.
  - `web/src/lib/audio/midiConvert.ts` — Hz → MIDI 정수 변환 + 안정성 필터(연속 N프레임 동일 MIDI 시 confirm).
  - `web/src/lib/audio/types.ts` — 측정 phase, 신뢰도 타입.
- 컴포넌트:
  - `MeasurementPhaseGuide` — phase별 안내 텍스트.
  - `LivePitchIndicator` — 실시간 음표/Hz 표시 (aria-live).
  - `MeasurementResultCard` — 결과 + 보정 슬라이더 + 신뢰도 배지.
- 상태 관리: 기존 voice-range store 확장(마이크 권한 상태, 측정 phase, 측정 샘플 임시 보관). 글로벌 상태 라이브러리는 기존 선택(추후 ADR) 따름.
- 기존 `web/src/app/voice-range/page.tsx`에 "자동 측정으로 시작" 링크 추가.

## 6) 작업 분할 (예상 PR 리스트)
- [ ] **PR A (docs)**: 본 spec — 이 PR (#144).
- [ ] **PR B (feat:voice, web)**: `pitchDetector.ts` + `midiConvert.ts` 헬퍼 + 단위 테스트 (Pitchy wrap, Hz→MIDI 변환).
- [ ] **PR C (feat:voice, web)**: `/voice-range/auto` 페이지 + 측정 UX 컴포넌트 + 권한 거부 fallback.
- [ ] **PR D (feat:voice, web)**: 기존 `/voice-range` 페이지에 "자동 측정" CTA + 측정 결과 → API 저장 + 추천 페이지 라우팅.
- [ ] **PR E (test, web)**: 통합 시나리오(측정 → 저장 → 라우팅) Playwright/jest 통합 1건 + a11y 점검.

> B → C → D 순서 권장. A는 본 PR로 선행. E는 D 머지 후 별 사이클로 분리.

## 7) 테스트 전략
- **단위(Hz → MIDI 변환)**: 표준 조율 기준 440Hz=A4=MIDI69, 261.63Hz=C4=MIDI60 등 경계 케이스 + 잡음 입력(0Hz, NaN) 안전 처리.
- **단위(안정성 필터)**: 연속 동일 MIDI N프레임 임계값 충족 시에만 confirm, 그 외 reject. mock 샘플 시퀀스로 검증.
- **컴포넌트**: 권한 거부 시 fallback 라우팅 1건, 측정 phase 전환 UI 1건.
- **통합**: 측정 결과 → API 호출 → 추천 페이지 라우팅 happy path 1건 (API mock).
- **a11y**: aria-live + 키보드 진입 점검.
- **외부 mock**: Web Audio API + Pitchy는 vitest/jest 환경에서 stub 모듈로 대체. `navigator.mediaDevices.getUserMedia` mock.

## 8) 오픈 질문
> 구현 전에 답이 나와야 하는 것들. 해소되면 §9 결정 로그로 이동.

| # | 질문 | 선택지 | 담당/기한 |
|---|---|---|---|
| Q1 | pitch detection 라이브러리 선택 | (a) **Pitchy** (권고) / (b) 자체 autocorrelation 구현 / (c) aubio.js (WASM) | @goohong / PR B 진입 전 |
| Q2 | 측정 시간 (phase당) | **(a) 5초 고정 (확정 2026-05-22, §9 참조)** / (b) 10초 고정 / (c) 조기 종료 — 폐기(#313) | 확정 |
| Q3 | "신뢰도 낮음" 임계 | (a) 분산(MIDI std) > 2 / (b) 안정 픽 미달(연속 N프레임 미충족) / (c) 둘 다 | @goohong / PR C 진입 전 |
| Q4 | 측정 결과 노출 형태 | (a) 별 결과 페이지(`/voice-range/auto/result`) / (b) 측정 페이지 하단에 그대로 노출 + 저장 CTA (권고, 1페이지 흐름) | @goohong / PR C 진입 전 |
| Q5 | iOS Safari WebAudio context 자동 시작 제약 회피 | (a) "측정 시작" 버튼 클릭 시 AudioContext.resume() / (b) 페이지 진입 시 lazy init | @goohong / PR C 진입 전 |

## 9) 결정 로그
> 연대기 순. "YYYY-MM-DD: 결정 / 이유 / 출처(PR 번호 등)"

- 2026-05-21: 초안 작성 (status=draft). plan 사이클 11. 출처: 이슈 #143, PR #144.
  - 영감 출처: `docs/research/external-service-inspirations.md` A-1 (Vanido), F-1 (P1 후보).
  - 라이브러리 권고: Pitchy(§5-3). 확정은 Q1 해소 시 §8 → §9 이동.
- 2026-05-22: **Q2 = (a) 5초 고정**. 이유: 이전 (c) 조기 종료 채택 시 실측이 1초도 안
  걸려 5초 안내 vs 실측 시간이 어긋났다 (issue #313). 5초 동안 안정 샘플을 누적한 뒤
  phase 방향 percentile (low → P5, high → P95) 로 결정하고, 안정 샘플 수가
  `MIN_STABLE_SAMPLES`(10) 이상이면 `confirmed=true`. 다양한 음역을 시도할 시간을
  보장하면서 첫 발성/끝맺음 흔들림 outlier를 percentile로 흡수. 출처: PR #313-fix,
  `web/lib/audio/sampler.ts`.
