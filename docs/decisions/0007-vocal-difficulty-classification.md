---
id: 0007
title: 곡 난이도 분류 — Difficulty enum (EASY|NORMAL|HARD)
status: accepted
date: 2026-05-21
deciders: [@goohong]
---

# 0007. 곡 난이도 분류 — Difficulty enum (EASY|NORMAL|HARD)

## Context
PR #96(곡 카드 UI 개편)에서 사용자가 단순 음역 막대 그래프를 폐기하고 **직관적 난이도 라벨**로 대체할 것을 요구했다(2026-05-21). 사용자 의도는 "노래방에서 뭐 부르지?"라는 결정 부담을 줄이는 데 있으며, raw MIDI 값을 그대로 노출하면 일반 사용자는 자신의 음역과 비교하기 어렵다.

PR #96 구현 과정에서 분류 기준이 backend `Song` 엔티티와 frontend `SongCard` 양쪽에 **코드로 박혀** 들어갔다. 이 상태로 두면 fe/be가 enum/임계값을 독립적으로 변경하다 drift할 위험이 있고, 추후 Pro 단계 추가 검토 시 결정 근거가 휘발된다. 따라서 본 ADR로 결정을 promote한다.

## Decision
곡 난이도는 **`Difficulty` enum = `EASY | NORMAL | HARD`** 3단계로 분류한다. **Pro 단계는 추후 데이터로 검토**한다(현 시점에서는 도입하지 않는다).

분류 기준은 다음과 같이 **fe와 be가 1:1 일치**한다:

| 라벨 | 조건 |
|---|---|
| `HARD` | 최고음 MIDI ≥ 76 (E5) **OR** (highMidi − lowMidi) ≥ 17 반음 |
| `NORMAL` | 71 ≤ highMidi ≤ 75 (위 HARD 조건에 해당하지 않을 때) |
| `EASY` | highMidi < 71 |

판정은 위에서 아래로 적용한다 (HARD 우선, 그다음 NORMAL, 마지막 EASY).

## Consequences
### 긍정적
- 사용자가 자신의 음역을 모르더라도 한 단어로 부를 만한 곡을 결정할 수 있다 — 인지 부담 최소화.
- 임계값이 **단일 ADR에 영속**되므로 fe/be 어느 쪽이 바뀌어도 본 ADR을 갱신해야 함이 명시된다(drift 차단).
- 3단계로 단순화하여 사용자가 라벨을 학습하는 비용이 낮다.
- enum 명이 동일(`Difficulty`)하고 값 집합이 동일하여 backend `RecommendationResponse` → frontend `SongCard` 직렬화에 변환 코드가 필요 없다.

### 부정적
- 임계값(76 / 71 / 17반음)이 휴리스틱이며 실데이터 검증을 거치지 않았다. 추천 정확도 데이터가 쌓이면 재조정 필요.
- 라벨이 3개뿐이라 비슷한 곡들이 같은 라벨로 묶여 변별력이 떨어질 수 있다. Pro 단계 추가가 필요해질 가능성.
- `lowMidi`가 누락된 곡은 HARD의 두 번째 조건(반음 차)을 평가할 수 없다. 데이터 결손 시 NORMAL/EASY 분기로만 판정되어 일부 곡이 실제보다 쉽게 분류될 위험.

## Alternatives (considered)
- **별점 5단계(★~★★★★★)** — 변별력은 높으나 임계값 4개를 잡아야 하고 사용자가 별 1개와 2개를 구분하기 어렵다. 인지 부담 증가가 단순화 목표와 충돌하여 채택하지 않음.
- **Pro 단계 즉시 추가(EASY/NORMAL/HARD/PRO)** — HARD 위에 PRO를 두는 안. 현 시점에는 데이터가 부족해 PRO와 HARD를 가르는 임계값을 정당화하기 어렵다. 추천 정확도 메트릭이 쌓인 뒤 별도 ADR로 도입 검토하기로 함.
- **분류 없이 raw MIDI 표시(현행 유지)** — 막대 그래프 + MIDI 숫자. 사용자가 명시적으로 폐기를 요구한 안이라 채택하지 않음.
- **두 단계(EASY/HARD)** — 가장 단순하지만 노래방 인기곡 다수가 NORMAL 대역에 몰려 있어 변별력이 부족. NORMAL이 모달인 분포를 무시할 수 없어 채택하지 않음.

## References
- PR #96 — 곡 카드 UI 개편(난이도 라벨 도입).
- 사용자 결정 2026-05-21 — 단순 음역 막대 그래프 폐기 + 직관적 라벨 요구.
- `docs/ai-harness/06-domain-model.md` §5 엔티티 (Song.difficulty 필드).
- 후속 검토: Pro 단계 도입 여부는 v0.2 추천 정확도 데이터 수집 이후 별도 ADR.
