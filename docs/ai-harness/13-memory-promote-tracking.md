# Memory → Code Promote Tracking

> 본진/세션이 Claude 메모리(`~/.claude/projects/.../memory/`)에 보관 중인 항목 중 **반복적으로 적용되는 운영 룰·프로젝트 결정**을 코드베이스(런북/CLAUDE.md/ADR/spec)로 promote한 기록.
> 메모리는 휘발성·세션 의존이라 핵심 룰은 코드로 옮겨 다음 세션도 동일 룰을 읽게 한다.
>
> 본 문서는 **트래킹 매트릭스**이며 실제 promote는 별 PR로 진행. 메모리 본문은 **수정하지 않는다** (race 회피, `11-multi-session-runbook.md §1-2`).

## 1) 매트릭스

| 메모리 항목 | 분류 | 현재 코드 위치 | 비고 |
|---|---|---|---|
| `user_working_style` | 메모리 유지 | (코드 promote 부적합) | 사용자 컨텍스트라 메모리에 두는 게 적절 |
| `feedback_domain_model_section_7` | 메모리 유지 | (코드 promote 부적합) | 한 줄짜리 negative rule. 코드에 박을 가치 < 메모리 |
| `feedback_runbook_spelling` | 메모리 유지 | (코드 promote 부적합) | 표기 정정 1줄. 별 코드 문서 가치 없음 |
| `project_multi_session_setup` | 완전 promote됨 | `11-multi-session-runbook.md §1` | 메모리는 짧은 포인터 유지 |
| `feedback_orchestration_pattern` | 완전 promote됨 | `11-multi-session-runbook.md §0-1`, `§2` | 메모리에 "promote 완료" 명시됨 |
| `feedback_role_expansion` | 완전 promote됨 | `11-multi-session-runbook.md §2 표`, `12-sub-agent-prompt-template.md §2` | 메모리에 "promote 완료" 명시됨 |
| `feedback_continuous_cycles` | 완전 promote됨 | `11-multi-session-runbook.md §2 공통 룰 마지막 항목` | 메모리에 "promote 완료" 명시됨 |
| `feedback_autonomous_loop` | 완전 promote됨 | `11-multi-session-runbook.md §2 공통 룰` | 메모리에 "한 줄 promote" 명시됨 |
| `feedback_plan_session_option` | 완전 promote됨 | `12-sub-agent-prompt-template.md §2 plan`, `11-multi-session-runbook.md §0-4 사이클 명명` | plan 세션 정식 합류 |
| `project_plan_session_active` | 완전 promote됨 | `11-multi-session-runbook.md §2 표`, `12-sub-agent-prompt-template.md §2 plan` | 4번째 세션 도입 사실 자체 |
| `project_self_analysis_pivot` | 완전 promote됨 | `docs/features/song-self-analysis-pipeline.md`, `docs/decisions/0006-audio-source-youtube.md` | spec + ADR 두 곳에 모두 코드화 |
| `feedback_notification_preempts_main` | **일부 promote** | `11-multi-session-runbook.md §2 공통 룰 마지막 항목 "자율 운영" 한 줄` | 절차(우선순위, 호흡 단위)는 메모리에만. 보강 후보 → §2 참조 |
| `feedback_auto_register_rev_findings` | **일부 promote** | `11-multi-session-runbook.md §4 리뷰 세션 운영` (rev 동작만), `§2 rev 역할 표 ("후속이 필요하면 본진에 보고")` | 본진 측 자동 등록 절차(🔴/🟡 분류, 동일 사이클 즉시 트리거)는 메모리에만. 보강 후보 → §2 참조 |

## 2) 보강 후보 (다음 docs PR에서 처리)

본 PR(#103)에서는 **트래킹만**. 실제 promote는 별 PR로 분리해 변경 범위를 좁힌다.

### 2-1) [P1] `11-multi-session-runbook.md §0`에 "통지 처리 우선순위" 절 추가
- 출처 메모리: `feedback_notification_preempts_main`
- 추가 내용:
  - sub-agent 완료 통지 도착 시 본진은 자기 작업의 현재 도구 호출 단위 마치고 통지를 먼저 처리
  - 우선순위 — 🔴 > release 머지 결정 > 일반 보고
  - wall-clock 최소화 목적 + 본진 작업은 짧은 호흡 단위로 쪼개기
- 추정 신규 절: `§0-8) 통지 우선 처리`

### 2-2) [P1] `11-multi-session-runbook.md §0`에 "rev 코멘트 자동 등록" 절 추가
- 출처 메모리: `feedback_auto_register_rev_findings`
- 추가 내용:
  - rev 사이클 완료 통지 받으면 본진이 코멘트를 GitHub 이슈로 자동 등록
  - 🔴은 단독 이슈 + 같은 사이클 즉시 fix 트리거 (1~2건 한정)
  - 🟡은 PR 단위 묶음 이슈 (7건 이상이면 상위만 등록 + 나머지 `backlog` 라벨)
  - 🟢은 등록 안 함
  - 이슈 본문에 rev 코멘트 URL 인용 + spec/스코프 명시
- 추정 신규 절: `§0-9) rev 코멘트 자동 등록`

### 2-3) [P2] `12-sub-agent-prompt-template.md §1`에 "통지/등록 절차" 백 참조
- §1 공통 룰에서 "본진은 통지 받으면 위 절차(§0-8/§0-9) 따른다" 한 줄. sub-agent prompt 자체에는 영향 없음(본진 룰).

## 3) 운영 룰

- 메모리 본문은 plan 세션이 **읽기만** 한다. 갱신/축소는 본진(사용자 직접 작성 채널이 있는 곳)에서만.
- 메모리에 `코드 promote 완료` 문구가 있는 항목은 본 매트릭스에서 "완전 promote됨"으로 매핑. 누락 발견 시 본 문서 갱신.
- 새 메모리 항목 추가 시 다음 plan idle 사이클에 본 매트릭스에 분류 추가.

## 4) 변경 이력

- 2026-05-21 — 최초 작성 (PR #103, closes #102). 메모리 13개 분류 + 보강 후보 2건 도출.
