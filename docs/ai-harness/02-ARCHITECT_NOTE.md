# AI Harness Volume 2: ARCHITECT NOTE

> Plan 에이전트와 Maestro가 서비스의 뼈대를 설계하고 의사결정을 내릴 때 참조하는 지식 기반입니다.

---

## 1. 도메인 모델 및 용어 (Ubiquitous Language)
- **VoiceRange(음역대)**: 사용자의 가창 가능 범위 (lowest/highest MIDI).
- **Song(곡)**: 추천 대상. Key, BPM, Mood, Difficulty(자동 계산)를 포함.
- **Mood(분위기)**: 신남, 잔잔함 등 정성적 요소. 추천의 핵심 입력값.
- **Difficulty(가창 난이도)**: EASY/NORMAL/HARD. 곡의 음역폭과 최고음에 따라 자동 분류.
- **Recommendation(추천)**: 사용자 컨텍스트와 곡 메타데이터의 매칭 결과 및 점수.
- **Like/Bookmark**: 사용자의 긍정 시그널. (v0.2 기준 추천 가중치에는 아직 미반영)

## 2. 패키지 구조 (Bounded Context)
모든 비즈니스 로직은 다음 4개 계층으로 엄격히 분리한다 (ADR 0005).
- `domain`: 순수 자바 엔티티, VO, 도메인 예외 (의존성 없음).
- `application`: 유스케이스 서비스, Scorer, Properties.
- `infrastructure`: Repository 구현체, 외부 API 어댑터.
- `api`: Controller, DTO (응답 DTO는 domain 엔티티 참조 가능).

## 3. 핵심 설계 원칙
- **결정성(Determinism)**: 추천 알고리즘은 동일 입력에 대해 항상 동일 결과를 보장해야 한다. `Random()`, `Instant.now()` 직접 호출을 금지하고 `SeedDeriver`나 `Clock` DI를 사용한다.
- **BC 간 격리**: 타 도메인 엔티티는 ID-only 참조를 지향하며, 필요 시 application 계층에서 조인한다.
- **메모리 트래킹**: 반복되는 설계 결정은 `11-memory-promote-tracking.md`를 통해 정식 문서로 promote하여 휘발을 방지한다.

## 4. ADR 및 Spec 작성 가이드
- **ADR**: 횡단적인 기술 결정(DB 전략, 라이선스, 아키텍처 패턴)은 `docs/decisions/`에 기록한다.
- **Feature Spec**: 중간 규모 이상의 기능은 구현 전 `docs/features/`에 명세서를 작성하고 `status: approved`를 받아야 한다.

---
**참조 문서**: `docs/ai-harness/06, 09, 13`, `docs/decisions/*`
