---
id: 0025
title: 잔존 directive 정리 — 옵션 B (분류 기반 자율 sweep) 채택
status: proposed
date: 2026-05-29
deciders: [@mobruji-maestro]
---

# 0025. 잔존 directive 정리 — 옵션 B (분류 기반 자율 sweep) 채택

## Context

`~/.mobruji/directive-board.jsonl` 의 `status: 대기` entry 가 운영 누적으로
stale 화 (의미상 완료 / 폐기 / 사용자 결정 보류) 되어 forum sidebar 백로그
가시화를 왜곡. 2026-05-28 14:24 KST 사용자 trigger "잔존 작업들 어떻게
정리할래??" 에 대해 3 옵션 (A=전체 일괄 dropped / B=분류 기반 자율 sweep /
C=수동 사용자 검토) 비교 후 의사결정 필요.

## Decision

**옵션 B 채택** — `대기` entry 를 4 패턴 (A=PR 머지 완료 / B=의사 결정 적용 /
C=testing 페이로드 / D=timeline 미정) 으로 분류해 자율 sweep. 운영 정책 SoT 는
`docs/features/directive-board-stale-close-policy.md`, 의사결정 + 1회성
실행 박제는 `docs/features/directive-cleanup-option-b.md`.

채택 신호: 2026-05-28 14:27 KST directive `1509427824803577936` "옵션 B 채택"
(helper-queue.jsonl entry).

## Consequences

### 긍정적

- 진짜 완료 entry (PR 머지 / 결정 적용) 가 `completed` 로 박제되어 회고 시
  PR URL 추적 가능.
- 자율 default ([[feedback-autonomous-default]]) 일치 — 분류 명확 entry 는
  사용자 검토 불요.
- ⚪ 보류 (timeline 미정) 와 🔴 폐기 분리 → forum sidebar 필터 의미 보존.
- 분류 오판 시 `재활성` 액션으로 정정 가능 (false-negative 비용 낮음).

### 부정적

- 사이클 1회 (~10분) 자율 작업 비용 발생 (옵션 A 는 즉시 0건).
- 분류 오판 risk — 사용자 정정 시 retraction 발생 가능.
- 정책 SoT (stale-close-policy) ↔ 의사결정 박제 (본 ADR + option-b spec) 분리
  유지 보수 비용.

## Alternatives (considered)

- (A) **전체 일괄 dropped** — 모든 `대기` entry 를 `dropped` 로 전환 후 새 시작.
  기각 사유: 진짜 완료 entry 가 `dropped` 로 박제 → PR 추적 불가 + ⚪ 보류 /
  🟢 완료 / 🔴 폐기 의미 손실.
- (C) **수동 사용자 검토** — 사용자가 각 entry 별 close 액션 직접 결정.
  기각 사유: 자율 default 원칙 위반 + 분류 명확 entry (4 패턴) 는 사용자
  검토 과잉 부담.

## References

- `docs/features/directive-cleanup-option-b.md` — 의사결정 + 1회성 sweep 실행
  spec.
- `docs/features/directive-board-stale-close-policy.md` — 4 패턴 / close 액션 /
  자율 분기 운영 정책 SoT.
- `docs/features/directive-board-template-and-tags.md` §5-1, §5-6 — status 5종 +
  백로그 운영 모델 SoT.
- helper-queue.jsonl entry `1509427824803577936` (2026-05-28 14:27 KST) —
  옵션 B 채택 사용자 신호 evidence.
- `tools/discord-daemon/directive_status.sh` — sweep 명령 호출 대상.
- 메모리: [[feedback-autonomous-default]] / [[feedback-evidence-based-root-cause]].
