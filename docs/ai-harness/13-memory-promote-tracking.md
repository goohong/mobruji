# Memory → Code Promote Tracking

> maestro/세션이 Claude 메모리(`~/.claude/projects/.../memory/<actor>/`)에 보관 중인 항목 중 **반복적으로 적용되는 운영 룰·프로젝트 결정**을 코드베이스(런북/CLAUDE.md/ADR/spec)로 promote한 기록.
>
> **2026-05-24 #1004**: 메모리 디렉토리가 actor 별로 분리됨 (`common/` `nmae/` `helper/` `subagent/` `rev/` `workflow/`). 본 매트릭스의 "메모리 항목" 열은 디렉토리 prefix 없이 slug 만 표기 (slug 가 unique).
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
| `feedback_notification_preempts_main` | ✅ 완전 promote됨 | `11-multi-session-runbook.md §0-8 통지 우선 처리` | 처리 순서·호흡 단위·우선순위 모두 코드화 (PR #116) |
| `feedback_auto_register_rev_findings` | ✅ 완전 promote됨 | `11-multi-session-runbook.md §0-9 rev 코멘트 자동 등록` | 🔴/🟡/🟢 분류·묶음 룰·즉시 트리거 모두 코드화 (PR #116) |
| `feedback_external_research` | 메모리 유지 | (코드 promote 부적합) | 기획 보조 룰. 한 줄 negative cue, 코드 박을 가치 < 메모리 |
| `feedback_rev_release_gate` | ✅ 완전 promote됨 | `11-multi-session-runbook.md §0` rev gate / release 절차 | release 전 `reviewed:claude` 라벨 gate. 런북 명시 |
| `project_discord_channel` | 부분 promote됨 | `14-discord-notify-setup.md`, `15-discord-message-templates.md` | 채널 ID는 메모리 전용(사용자 컨텍스트). webhook/메시지 룰만 코드화 |
| `feedback_discord_polling` | 메모리 유지 | (코드 promote 부적합) | maestro polling cadence. 사용자 컨텍스트 의존, 24/7 인프라는 plan 20에서 별도 추진 |

## 2) 보강 후보 (다음 docs PR에서 처리)

### 2-1) [✅ 완료] `11-multi-session-runbook.md §0-8` "통지 우선 처리" 절 추가
PR #116에서 promote 완료. 메모리 → 코드 1:1 매핑.

### 2-2) [✅ 완료] `11-multi-session-runbook.md §0-9` "rev 코멘트 자동 등록" 절 추가
PR #116에서 promote 완료. 메모리 → 코드 1:1 매핑.

### 2-3) [P2] `12-sub-agent-prompt-template.md §1`에 "통지/등록 절차" 백 참조
- §1 공통 룰에서 "maestro은 통지 받으면 위 절차(§0-8/§0-9) 따른다" 한 줄. sub-agent prompt 자체에는 영향 없음(maestro 룰).
- 다음 plan 사이클 후보.

### 2-4) [P3] 메모리 단축 후보 (maestro 영역, race 회피로 본 PR 직접 수정 안 함)
plan 22 이후 추가된 메모리 항목 중 다른 메모리와 중복도가 높아 1줄 단축이 가능한 후보. maestro 사이클에서 처리.

- **`feedback_discord_polling`** — `project_discord_channel`과 한 묶음으로 통합 가능. "maestro은 #모부르지 채널만 매 호흡 fetch_messages" 1줄로 압축.
- **`feedback_rev_release_gate`** — `feedback_autonomous_loop`(release만 사용자 확인)과 의미 겹침. "release 전 모든 PR `reviewed:claude` 라벨 gate" 1줄로 압축.
- **`feedback_external_research`** — 단독 유지 무방하나, `user_working_style`에 "기획 영감 부족 시 외부 서비스 분석 위임" 한 줄 추가하고 본 항목 삭제도 검토 가능.

처리 가이드: maestro이 다음 사용자 입력 idle 사이클에 메모리 편집 (`~/.claude/projects/.../memory/MEMORY.md` index 직접 수정 + actor 디렉토리 하위 feedback 본문 수정). plan 세션은 본 매트릭스에서만 추적.

## 3) 운영 룰

- 메모리 본문은 plan 세션이 **읽기만** 한다. 갱신/축소는 maestro(사용자 직접 작성 채널이 있는 곳)에서만.
- 메모리에 `코드 promote 완료` 문구가 있는 항목은 본 매트릭스에서 "완전 promote됨"으로 매핑. 누락 발견 시 본 문서 갱신.
- 새 메모리 항목 추가 시 다음 plan idle 사이클에 본 매트릭스에 분류 추가.

## 4) 변경 이력

- 2026-05-21 — 최초 작성 (PR #103, closes #102). 메모리 13개 분류 + 보강 후보 2건 도출.
- 2026-05-21 — PR #116(closes #115). `feedback_notification_preempts_main`, `feedback_auto_register_rev_findings` 2건을 런북 §0-8/§0-9로 완전 promote. 보강 후보 2-1/2-2 종결, 2-3은 다음 plan 사이클로 이월.
- 2026-05-21 — PR #217(closes #216). plan 22 이후 추가된 메모리 4건(`feedback_external_research`, `feedback_rev_release_gate`, `project_discord_channel`, `feedback_discord_polling`) 분류 추가 + 보강 후보 2-4(메모리 단축 후보) 도출.
