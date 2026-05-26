# Redundant Rules Audit — 2026-05-26 (#1124)

> Status: audit only — 제거 X
> Owner: mobruji plan sub-agent
> Created: 2026-05-26
> Related: `docs/features/agent-role-enforcement.md` (PR docs/agent-role-enforcement-#1124)
>
> **목적**: PR #1129 (helper-role-enforcement) + 본 사이클 agent-role-enforcement 도입으로 5 actor (nmae + be/fe/rev/plan + helper) role 이 system prompt 강제됨. 이에 따라 CLAUDE.md / 메모리 / docs 의 일부 룰이 redundant. 본 PR 은 **후보 식별만** — 실제 제거는 사용자 review 후 별도 PR.
>
> **블라인드 삭제 금지**: 메모리는 사고 박제 / 사용자 인용 / why·how-to-apply 가치 유지. 단순 룰 진술만 후보.

---

## §1) Audit 대상 4 채널

| 채널 | 경로 | 도입 | 강제력 |
|---|---|---|---|
| ① role definition (NEW) | `.claude/agents/<role>.md` + `tools/discord-daemon/{nmae-role.md, helper-role.md}` | 2026-05-26 | 높음 (system prompt 강제) |
| ② CLAUDE.md | `/CLAUDE.md` §1-§16 | 기존 | 중간 (학습 의존) |
| ③ 메모리 | `memory/{common,helper,nmae,subagent,rev,workflow}/feedback_*.md` | 기존 | 낮음 (학습 의존) |
| ④ docs | `docs/ai-harness/*.md`, `docs/helper-rules.md` | 기존 | 중간 (Read 의무) |

**SoT 원칙**: 같은 룰이 ①②③④ 에 모두 있으면 ① 이 SoT. ② 는 포인터/요약. ③ 은 사고 박제/nuance. ④ 는 상세 SoT.

---

## §2) 중복 후보 — 구체적

### 2-1) "AskUserQuestion 금지 / 사용자 wait 금지"

**현재 위치 (4 곳)**:
- ① role def: `be.md` "작업 중 사용자 입력 대기(AskUserQuestion) 금지", `fe.md` 동일, `rev.md` 동일, `plan.md` 동일
- ② CLAUDE.md §13-1 "사용자 wait state 금지 ([[feedback-sub-agent-no-user-wait]])"
- ③ 메모리 `subagent/feedback_sub_agent_no_user_wait.md`
- ④ docs `docs/ai-harness/12-sub-agent-prompt-template.md §1`

**판단**: ① 이 강제. ② §13-1 한 줄 / ③ 메모리 / ④ docs 모두 유지 권고 — 한 줄짜리 룰은 redundant 라기보다 SoT 분산. ③ 메모리는 "사고 박제 (wait state 진입한 사례)" 가치 유지.

**권고**: **보존** (단 ② 한 줄은 ① 포인터로 슬림화 가능).

---

### 2-2) "rev 는 구현 / 파일 수정 금지"

**현재 위치 (3 곳)**:
- ① role def: `rev.md` "너는 **구현(코드 작성)을 하지 않는다**"
- ② CLAUDE.md §13-2 표 "rev | 파일 수정 금지 (PR 코멘트만)"
- ③ 메모리 `rev/feedback_rev_*.md` (3 파일 — e2e, release-gate, develop-grep) — 구현 금지 자체는 핵심이 아니고 e2e 흐름이 핵심
- ④ docs `docs/ai-harness/12-sub-agent-prompt-template.md §2 Role 별`

**판단**: ① 강제됨. ② 표 한 줄 / ④ docs SoT 유지. ③ 메모리 3 파일은 e2e 절차 박제 — redundant 아님.

**권고**: **보존**.

---

### 2-3) "plan 은 프로덕션 코드 금지 / docs only"

**현재 위치**:
- ① `plan.md` "프로덕션 코드 구현을 하지 않는다 — 명세/문서/결정만"
- ② CLAUDE.md §13-2 표 "plan | docs/**, .github/**"
- ④ docs `docs/ai-harness/12-sub-agent-prompt-template.md §2`

**판단**: ① 강제. ② 표 한 줄 유지 (역할 매트릭스 가시성). ④ docs 상세 유지.

**권고**: **보존**.

---

### 2-4) "fe npm install 금지 / symlink swap 사고"

**현재 위치**:
- ① `fe.md` — **없음** (role def 는 일반론만)
- ② CLAUDE.md §13-2 "fe: 의존성 설치 금지 — `npm install` 실행 금지"
- ③ 메모리 `subagent/feedback_npm_install_symlink_swap.md` (사고 박제 본체)
- ④ docs 없음

**판단**: ① role def 에 없음 → ② ③ 가 SoT. 메모리는 사고 박제 핵심. **redundant 아님**.

**권고**: **보존**. (fe.md 에 한 줄 추가하면 강제력 ↑ 가능 — 별도 PR 후보)

---

### 2-5) "helper 본체 = 사용자 응답 + 자체 수정 / nmae 위임"

**현재 위치 (4 곳)**:
- ① helper-role.md (PR #1129 도입) — 핵심 boundary
- ② CLAUDE.md §12-1 "권한 경계 [[feedback-helper-role-boundary]]"
- ③ 메모리 `helper/feedback_helper_role_boundary.md`
- ④ docs `docs/helper-rules.md` (PR #1085 SoT)

**판단**: ④ 가 SoT 명시. ① 강제. ② 는 ④ 의 mirror. ③ 메모리는 nuance/사고 박제.

**권고**: **통합 후보** — ② §12-1 본문이 길고 ④ docs 와 거의 동일. ② 를 "상세 ④, 강제 ①" 포인터 한 줄로 축소 가능. 단, 본 PR 은 audit only — 사용자 review 후 별도 PR.

---

### 2-6) "helper relay scope — nmae sub-agent 디테일 relay 금지"

**현재 위치**:
- ① helper-role.md — 가능성 있음 (확인 필요)
- ② CLAUDE.md §12-1 "보고/relay 범위 [[feedback-helper-relay-scope]]"
- ③ 메모리 `helper/feedback_helper_relay_scope.md`
- ④ docs `docs/helper-rules.md`

**판단**: ④ SoT. ③ 사고 박제 가치. ② 본문 길 → 포인터 한 줄로 축소 후보.

**권고**: **통합 후보**.

---

### 2-7) "PR base=develop 강제"

**현재 위치**:
- ① role def — be/fe/rev/plan.md "CLAUDE.md 비협상 룰 준수(develop 파생 브랜치 ...)"
- ② CLAUDE.md §13-1 "`gh pr create --base develop` 강제 ([[feedback-pr-base-develop]])"
- ③ 메모리 `subagent/feedback_pr_base_develop.md`
- ④ docs `docs/ai-harness/02-agent-workflow.md`

**판단**: ① 명시. ② §13-1 / ③ 메모리 / ④ docs 모두 유지 권고 — base=main 사고 가드 박제.

**권고**: **보존**.

---

### 2-8) "품질 게이트 (be: gradlew checkstyleMain spotlessCheck test / fe: lint typecheck test)"

**현재 위치**:
- ① role def: `be.md` `fe.md` 명시
- ② CLAUDE.md §4 품질 게이트 / §13-2 표
- ④ docs `docs/ai-harness/03-quality-gates.md`

**판단**: ① 강제. ④ docs SoT. ② 한 줄 유지 (가시성).

**권고**: **보존**.

---

### 2-9) "변경 범위 밖 리팩터링 금지"

**현재 위치**:
- ① role def: `be.md` `fe.md` 명시
- ② CLAUDE.md §9 안티패턴
- ④ docs `docs/ai-harness/08-code-conventions.md`

**판단**: ① 강제. ② 안티패턴 리스트 유지. 보존.

**권고**: **보존**.

---

### 2-10) "release/태그/배포 자율 진행 금지 (nmae)"

**현재 위치**:
- ① nmae-role.md "release(develop→main)·태그·배포는 사용자 결정 없이 진행 금지"
- ② CLAUDE.md §4 자율 default 예외 + §8 릴리즈
- ③ 메모리 `common/feedback_autonomous_default.md` "release/destructive 포함 항상 자율" — **모순 가능** ⚠️

**판단**: ⚠️ **모순 후보**. ③ 메모리는 "release 포함 항상 자율" 인데 ① nmae-role 은 "사용자 결정 없이 진행 금지". 사용자 의도 확인 필요. **본 PR 은 audit only — 사용자 결정 대기** (§5 미해결 질문).

---

### 2-11) "보호 영역 변경 시 needs-human-review 라벨"

**현재 위치**:
- ① nmae-role.md "보호 영역(systemd/.env/CI/build) 변경 PR 은 needs-human-review + infra 라벨"
- ② CLAUDE.md §4 AI 작업 보호 영역 (전체 리스트)
- ④ docs `docs/ai-harness/01-harness-spec.md §6`

**판단**: ② 전체 보호 영역 리스트가 SoT. ① 요약. ④ docs 상세. **보존**.

**권고**: **보존**.

---

### 2-12) "cycle-status.json 갱신 의무 (nmae)"

**현재 위치**:
- ① nmae-role.md "매 사이클 진행마다 cycle-status.json 갱신"
- ② CLAUDE.md §11-1 ~ §11-4 (watchdog / inject 대응 / 보호 / note 의무)
- ③ 메모리 `nmae/feedback_cycle_status_json.md`
- ④ docs `docs/features/autonomous-cycle-orchestration.md`

**판단**: ② 가 가장 상세 (4 단계 inject 대응 + STRICT mode). ① 한 줄 요약. ④ feature spec SoT.

**권고**: **보존** (각 채널 깊이 다름).

---

### 2-13) "4 사이클 유지 (be/fe/rev/plan)"

**현재 위치**:
- ① nmae-role.md "be/fe/rev/plan 4개 사이클 유지"
- ② CLAUDE.md §11-5 "4 사이클 동시 launch — 도메인 독립 병행"
- ③ 메모리 `nmae/feedback_keep_4_cycles_active.md`
- ④ docs `docs/features/autonomous-cycle-orchestration.md`

**판단**: ② §11-5 가 SoT (워크트리 lock + 병행 패턴). 보존.

---

### 2-14) "Discord 정중체 / 줄임 표현 금지"

**현재 위치**:
- ① role def — 없음
- ② CLAUDE.md §4 공통 행동 룰 + §12-5
- ③ 메모리 `common/feedback_discord_tone_formal.md`
- ④ docs 없음

**판단**: ① 에 없음. ② ③ SoT. **redundant 아님**.

**권고**: **보존**.

---

### 2-15) "agent-launch-wrapper.sh 의무 통과"

**현재 위치**:
- ① nmae-role.md "agent-launch-wrapper.sh 의무 통과(forum-post+thread)"
- ② CLAUDE.md §11-2 단계 2 권장
- ④ docs `docs/features/work-cycle-simplification.md` 등

**판단**: ① 강제. ② 권장. ④ 상세. **보존** (강제력 차이).

---

## §3) 권고 — 카테고리별

### 3-1) 보존 (가치 유지)
- 사고 박제 메모리 (예: `subagent/feedback_npm_install_symlink_swap.md`, `subagent/feedback_stash_drop_unmerged_file.md`)
- 사용자 인용 (예: 메모리 본문의 "사용자 정정 2026-05-XX" 인용)
- why / how-to-apply 가 풍부한 메모리 (rev e2e 3단계, helper directive-board 등)
- CLAUDE.md 의 매트릭스 표 (§13-2 역할별 워크트리/게이트/경로) — 가시성

### 3-2) 통합 (SoT 정리 — 별도 PR 후보)
- §2-5 helper-role-boundary: CLAUDE.md §12-1 본문 → ④ `docs/helper-rules.md` 포인터로 축소
- §2-6 helper-relay-scope: 동일 패턴
- §2-1 sub-agent AskUserQuestion 금지: CLAUDE.md §13-1 한 줄 → ① role def 포인터로 슬림화

### 3-3) 제거 (사용자 review 후 — **본 PR 은 안 함**)
- 식별된 명확한 redundant 없음 (모든 채널이 깊이 다름)
- 단, 통합 후보가 진행되면 ② CLAUDE.md 일부 단락이 자연스럽게 짧아짐

### 3-4) 모순 / 결정 대기
- §2-10 release/배포 자율 여부: nmae-role.md ("사용자 결정 없이 금지") vs 메모리 [[autonomous_default]] ("release 포함 항상 자율") 충돌. **§5 미해결 질문**.

---

## §4) 절차 — 본 PR 이후

1. **본 PR (audit) 머지** → 사용자 review baseline 확보
2. **사용자 review** → 통합/제거 범위 결정 (§3-2, §3-4 항목별)
3. **별도 PR (audit 단위)** 로 통합/제거 진행:
   - 예: `refactor(docs): CLAUDE.md §12-1 helper-role 본문 → docs/helper-rules.md 포인터 슬림화 (#1124-followup)`
   - 예: `fix(docs): autonomous default vs release-gate 모순 해소 (#1124-followup)`
4. **검증**: 슬림화 후에도 actor 가 룰 위반 안 하는지 다음 사이클 관찰

---

## §5) 미해결 질문 — 사용자 결정 대기

### Q1) §2-10 모순 해소
- nmae-role.md "release/태그/배포 사용자 결정 없이 진행 금지"
- 메모리 [[autonomous_default]] "release/destructive 포함 항상 자율"
- 어느 쪽이 canonical? release 만 wait, develop 머지는 자율? 사용자 결정 필요.

### Q2) CLAUDE.md 슬림화 범위
- §12-1 helper-role 본문 (50+ 줄) → `docs/helper-rules.md` 포인터 한 줄로 축소 OK?
- §13-1 sub-agent 공통 룰 (15+ 줄) → role def + `docs/ai-harness/12-sub-agent-prompt-template.md` 포인터로 축소 OK?

### Q3) `.claude/agents/<role>.md` 강화
- 현재 role def 본문이 짧음 (5-7 줄). 핵심 룰을 더 흡수해서 system prompt 강제력 ↑ 시킬지?
- 예: `fe.md` 에 "npm install 실행 금지 — 외부 디스크 symlink 보존" 한 줄 추가?
- 예: `rev.md` 에 "release 전 모든 PR `reviewed:claude` 필수" 한 줄 추가?

---

## §6) 참고

- 관련 PR: #1135 (agent-role-enforcement 흡수), #1129 (helper-role-enforcement)
- 관련 spec: `docs/features/agent-role-enforcement.md`, `docs/features/helper-role-enforcement.md`
- 메모리 인벤토리:
  - common: 9 파일 (autonomous_default, keep_promises, discord_tone_formal, ...)
  - nmae: 16 파일 (orchestration, watchdog, cycle-status, per-cycle-channel, ...)
  - helper: 18 파일 (role-boundary, relay-scope, thread-usage, ...)
  - subagent: 5 파일 (no-user-wait, reasoning-chunk-limit, pr-base-develop, npm-install, stash-drop)
  - rev: 3 파일 (e2e-always, release-gate, develop-grep)
  - workflow: 3 파일 (runbook-spelling, domain-model-§7, env-sync-ops)
