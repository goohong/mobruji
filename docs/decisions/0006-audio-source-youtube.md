---
id: 0006
title: 자체 곡 분석 파이프라인의 audio 출처 — YouTube audio extract
status: accepted
date: 2026-05-21
deciders: [@goohong]
---

# 0006. 자체 곡 분석 파이프라인의 audio 출처 — YouTube audio extract

## Context
v0.2부터 추천 정확도를 외부 메타데이터 카탈로그(Spotify audio-features, MusicBrainz 등)에 의존하던 모델을 폐기하고 **자체 분석 파이프라인**으로 vocal range/key/tempo를 추출하는 방향으로 pivot한다(`docs/features/song-self-analysis-pipeline.md`). 이 파이프라인은 분석 대상 audio를 어딘가에서 확보해야 한다. 가능한 출처는 4종이며 각자 라이선스/커버리지/UX 트레이드오프가 다르다.

- (a) **YouTube audio extract** — yt-dlp 등으로 영상에서 audio 추출
- (b) **Spotify/iTunes 30초 preview** — 공식 preview URL
- (c) **사용자 업로드** — 사용자가 직접 audio 파일을 업로드
- (d) **CC0/Public Domain dataset** — MusiCaps, FMA 등 라이선스 안전 데이터셋

1차 PoC(v0.2)는 한국 노래방 인기곡 100곡 분석이 목표이며 분석 정확도와 곡 커버리지가 가장 중요한 제약이다.

## Decision
PoC 단계의 audio 출처는 **YouTube audio extract**(yt-dlp 또는 동등 도구)로 한다. 운영(production) 진입 전에는 본 ADR을 재평가하고 법무 자문을 통해 결정을 갱신한다.

본 결정은 **PoC 한정**임을 명시한다. 운영 시점에 본 ADR이 그대로 유효하다고 가정해선 안 된다.

## Consequences
### 긍정적
- 한국 노래방 인기곡 커버리지가 사실상 100%다. (b)/(d)는 한국 발라드/트로트 커버리지가 빈약하다.
- 동일 곡의 원곡/커버/MR 버전을 함께 확보할 수 있어 vocal pitch detection의 robust check가 가능하다.
- 30초 preview(b) 대비 곡 전체 audio를 분석할 수 있어 vocal range/key 추정 신뢰도가 높다.
- 사용자 업로드(c) 대비 UX 변경이 없다(사전 batch 처리).

### 부정적
- **YouTube ToS 위반 가능성** — YouTube 이용약관은 download를 일반적으로 제한한다. yt-dlp 사용은 서비스 약관 위반으로 해석될 수 있다.
- **저작권 리스크** — 원곡 audio를 임시라도 시스템에 보관하면 복제권 침해 소지가 있다. 분석 후 즉시 삭제하더라도 캐시 정책/로그 보관 정책을 엄격히 관리해야 한다.
- **PoC 범위 한정** — 본 결정은 운영 단계에서 유효하지 않다. 사용자(서비스 출시) 전 반드시 법무 자문 + ADR 갱신 필요.
- yt-dlp는 외부 도구로 YouTube 측 API/UI 변경에 취약하다. 분석 batch가 깨질 수 있다.

## Alternatives (considered)
- **(b) Spotify/iTunes 30초 preview** — 라이선스가 깔끔하고 API 토큰만 있으면 안정적이다. 그러나 30초 preview는 곡 중반 일부만 포함하는 경우가 많아 vocal range(특히 하이라이트 최고음/브릿지 최저음) 추정에 한계가 크다. v0.2 추천 정확도 목표를 달성하기 어려움. 채택하지 않음.
- **(c) 사용자 업로드** — 사적 사용(fair use) 범위에 가까워 법적 안전. 그러나 추천 결과를 받기 전에 곡 audio를 직접 올리라는 요구는 "노래방에서 뭐 부르지?" UX와 정면 충돌한다. 사용자 입장에선 입력 부담이 비대해 사실상 불가. 채택하지 않음.
- **(d) CC0/Public Domain dataset** — MusiCaps, FMA, MTG-Jamendo 등은 라이선스 100% 안전하나 한국 인기곡 커버리지가 거의 0이다. 학습/검증용 보조 데이터로는 유용하나 1차 분석 대상 곡 풀로는 부적합. 채택하지 않음.

## References
- `docs/features/song-self-analysis-pipeline.md` — 본 ADR이 audio 출처를 정의하는 spec.
- `docs/features/song-metadata-source.md` — 기존 외부 카탈로그 의존 spec. 본 pivot으로 일부 결정이 무력화됨.
- 이슈 #67, #98, PR #99.
- 사용자 결정 2026-05-21 (세션 plan 묶음 질문).
