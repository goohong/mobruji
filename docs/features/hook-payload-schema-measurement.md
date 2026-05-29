---
feature: PreToolUse hook input payload schema 실측 + 정합 검증
slug: hook-payload-schema-measurement
status: draft
owner: @mobruji-maestro
scope: infra
related_issues: []
related_prs: [1146, 1154, 1252, 1257]
last_reviewed: 2026-05-29
---

# PreToolUse hook input payload schema 실측 + 정합 검증

## 1) 개요 (What / Why)

`/home/mobruji/.mobruji/helper-direct-work-guard.sh` (PreToolUse Bash matcher) 는 Claude Code harness 가 stdin 으로 전달하는 **JSON payload** 에서 `.cwd` (payload-root) 와 `.tool_input.command` 두 path 를 파싱한다. 그러나 `~/.mobruji/hook-bypass.log` 의 누적 audit evidence 가 **44 entry 중 43 entry 가 `cwd=""` + `role=unset`** 로, hook 의 cwd / role 자동 우회 분기가 사실상 발화하지 않는 사고가 박제되었다 (`docs/features/helper-direct-work-guard-subagent-context.md §14` 사고 박제).

본 spec 은 **PreToolUse hook 가 실제로 stdin 으로 받는 JSON payload 의 정확한 schema 를 실측** 하여 hook 파싱 path (`.cwd` / `.tool_input.cwd` / `.session.cwd` / `.context.cwd` 등 후보) 정합성을 확정하고, 결과를 hook 코드 + spec §14-3 표에 반영하는 절차를 정의한다.

### Motivation 의 핵심 evidence (2026-05-29 audit)

```
$ wc -l ~/.mobruji/hook-bypass.log
44
$ grep -c '"cwd": ""' ~/.mobruji/hook-bypass.log
43
$ grep -v '"cwd": ""' ~/.mobruji/hook-bypass.log
{"ts": "2026-05-27T10:11:44Z", "role": "unset", "cwd": "/home/mobruji/mobruji-be", "cmd": "gh pr create --base develop", "reason": "subagent-cwd"}
```

- 43/44 = 97.7% 가 `cwd=""` — hook 의 line 95-99 `json.load(sys.stdin).get('cwd','')` 파싱이 거의 항상 empty 반환
- 유일한 성공 entry (2026-05-27 10:11:44Z) 는 **`test_helper_direct_work_guard.sh` 테스트가 직접 stdin 으로 `{"cwd": "...", "tool_input": {"command": "..."}}`** payload 를 합성 주입한 결과로 강하게 의심됨 (직전 entry 10:11:35Z 가 `MOBRUJI_ALLOW_DIRECT=1 gh pr create --base develop --title 'test'` 로 T4 시나리오와 정확히 일치)
- 즉 **실측 schema 는 테스트 합성과 다를 가능성** — 본 spec 의 핵심 목표

본 spec 은 `helper-direct-work-guard-subagent-context.md §14-4 검증 1` 의 절차를 **별도 독립 spec 으로 추출** 하여 impl PR (직전 spec §14-6 PR 4) 의 작업 단위 SoT 를 명확히 한다.

## 2) 사용자 시나리오

본 spec 은 LLM / 운영자 시나리오만 다룬다 (end-user 시나리오 없음 — infra 측 검증).

### S1. 운영자가 hook payload schema 측정 1회 실행
```
운영자 → impl PR sub-agent 가 §5-4 임시 wrapper 스크립트 deploy
       → 5-10 분간 실제 sub-agent / nmae 활동
       → /tmp/hook-input-*.json 캡처본 N건 수집
       → schema 분석 → 본 spec §6 의 결과 표 update
       → impl PR (별도) 가 hook 파싱 path 정합
```

### S2. 측정 후 hook fallback cascade 적용 (후속 impl PR)
```
schema 가 .cwd 가 아닌 .tool_input.cwd 또는 .session.cwd 로 판정된 경우
  → hook 파싱 path 변경
  → 회귀 가드: cascade fallback (`.tool_input.cwd` → `.cwd` → `PWD` env) 도입
  → audit log evidence 가 다음 24h 안에 `cwd!=""` 비율 회복 확인
```

### S3. 검증 실패 (schema 가 후보 4개 중 어디에도 없음) — escape hatch
```
schema 가 .cwd / .tool_input.cwd / .session.cwd / .context.cwd 모두 부재
  → hook 가 process `pwd` (또는 `$PWD` env) 직접 사용 fallback
  → MOBRUJI_ROLE env inherit 가설 (직전 spec §14-3 가설 3) 보강 PR 우선순위 ↑
```

## 3) 요구사항

### 기능 요구사항
- [ ] **임시 캡처 wrapper script** (`/tmp/hook-payload-capture.sh` 또는 동등) 작성:
  - 입력 stdin 을 `/tmp/hook-input-<epoch_ns>.json` 으로 저장
  - 그대로 `/home/mobruji/.mobruji/helper-direct-work-guard.sh` 에 passthrough — 기존 hook 동작 깨지 않음
  - 캡처 후 본 hook 의 exit code / stderr 그대로 forward
- [ ] **임시 wrapper 등록**: `~/.claude/settings.json` 의 PreToolUse Bash matcher command 를 임시 wrapper 로 swap (impl 단계)
- [ ] **최소 5분 / 최대 10분 캡처** — 그 사이 다음 활동 trigger 의무:
  - sub-agent (be/fe/rev/plan) 최소 1 회 launch + `gh pr create` 시도
  - nmae 본진 일반 명령 (`git status`, `ls` 등) 최소 5건
  - helper allowlist 명령 최소 2건
- [ ] **schema 분석**: 캡처된 N건 (목표 ≥ 20건) 의 top-level key 분포 + nested key 분포 측정:
  ```bash
  for f in /tmp/hook-input-*.json; do
    python3 -c "import json,sys; print(list(json.load(open('$f')).keys()))"
  done | sort | uniq -c | sort -rn
  ```
- [ ] **cwd path candidate evaluation**: 각 캡처본에서 다음 4 경로 값 추출 + 매칭률 측정:
  - `payload.cwd`
  - `payload.tool_input.cwd`
  - `payload.session.cwd`
  - `payload.context.cwd`
- [ ] **결과 박제**: 본 spec §6 결과 표 + `docs/features/helper-direct-work-guard-subagent-context.md §14-3` 표 update
- [ ] **임시 wrapper 제거**: 측정 완료 후 settings.json 을 원복 + `/tmp/hook-input-*.json` 삭제 (PII 잠재 위험)

### 비기능 요구사항
- **안전성**: 임시 wrapper 는 본 hook 의 exit code / stderr / 차단 로직을 **절대 변경하지 않는다** — 단순 stdin tee + passthrough. wrapper 자체 버그로 nmae 본진 차단이 깨지면 release / tag 보호 사고 발생 가능.
- **PII 가드**: 캡처본은 `gh pr create --body` 본문 / discord-reply 본문 / 사용자 메시지 raw 등 잠재 PII 포함. 측정 완료 후 **즉시 `/tmp/hook-input-*.json` 삭제** + 분석 결과 박제 시 raw payload 미 commit. spec 본문에는 schema **shape 만** 기재 (구체 값 redact).
- **반복 가능성**: 같은 측정 절차로 schema regression 발견 시 즉시 재실행 가능 — impl 단계가 wrapper script 를 `tools/discord-daemon/hook-payload-capture.sh` 로 commit (단, settings.json swap 은 수동) 권고.
- **measurement scope**: PreToolUse(Bash) 만. 다른 hook (PostToolUse, Stop, UserPromptSubmit 등) schema 는 본 spec out of scope.
- **회귀 가드**: 측정 결과 박제 후 hook fallback cascade impl PR 머지 시점에 `hook-bypass.log` 의 `cwd!=""` 비율이 **24h 안에 50% 이상 회복** 되어야 정합 성공. 미회복 시 추가 가설 검토 trigger.

## 4) 범위 / 비범위

### 포함
- PreToolUse(Bash) hook stdin JSON payload schema 측정 절차 정의
- 측정용 임시 capture wrapper script spec
- schema 후보 4개 (`payload.cwd` / `payload.tool_input.cwd` / `payload.session.cwd` / `payload.context.cwd`) 평가 매트릭스
- 측정 결과 박제 위치 (본 spec §6 + `helper-direct-work-guard-subagent-context.md §14-3` cross-ref)
- 측정 완료 후 cleanup 절차 (PII 가드)

### 제외 (Out of Scope)
- **hook 코드 자체 수정** — 본 spec 은 측정 절차만. 정합 fix 는 별도 impl PR (직전 spec §14-6 PR 4 또는 본 spec 후속 impl PR — §6 작업 분할 참조)
- **fallback cascade impl** — schema 측정 결과와 무관한 안전망 (직전 spec §14-6 PR 6). 본 spec 은 측정 결과를 cascade impl PR 의 priority 입력으로 제공
- **MOBRUJI_ROLE env inherit 검증** — 직전 spec §14-4 검증 2 + §14-6 PR 5 가 별도 다룸. 본 spec 은 cwd 만 책임
- **PostToolUse / Stop / UserPromptSubmit hook schema** — 다른 hook 도 동일 사고 가능하나 본 spec 의 trigger evidence (cwd=empty) 와 무관
- **Anthropic 공식 문서 hunt** — 공식 문서가 schema 를 명시했더라도 (a) 버전 lag (b) Agent SDK 와 Claude Code harness 차이 가능성 → 실측이 SoT
- **logrotate / log 폭주 대응** — 직전 spec §14-5 가 다룸

## 5) 설계

### 5-1) 도메인 모델
도메인 엔티티 변경 없음. **infra / harness 레이어**.

관련 컨텍스트:
- `docs/features/helper-direct-work-guard-subagent-context.md` — 본 spec 의 motivation parent (§14 사고 박제)
- `docs/features/agent-launch-wrapper-enforcement.md` — sub-agent launch flow (MOBRUJI_ROLE inherit 가설 3 검증 경로)
- `CLAUDE.md §3 필수 참조 문서` — settings.json hook 경로

### 5-2) API 엔드포인트
해당 없음.

### 5-3) 외부 연동
- **Claude Code harness** — PreToolUse hook 호출 mechanism 의 stdin payload 가 측정 대상. 공식 schema 문서 부재로 실측 의존.
- **`python3`, `jq`** — JSON 파싱. 시스템 기본 설치 가정.

### 5-4) 데이터 흐름 / 시퀀스

#### 측정 phase (5-10 분)

```
운영자 또는 impl sub-agent
  ↓ (1) settings.json hook command swap
~/.claude/settings.json  ──(PreToolUse Bash)──>  /tmp/hook-payload-capture.sh
                                                  │
                                                  ├─ stdin → tee /tmp/hook-input-<epoch_ns>.json
                                                  │
                                                  └─ stdin → /home/mobruji/.mobruji/helper-direct-work-guard.sh
                                                       (정상 차단 / 통과 동작 유지)

운영자 또는 impl sub-agent
  ↓ (2) 5-10 분간 sub-agent launch + nmae 일반 명령 + helper allowlist 명령 trigger
  ↓ (3) 캡처본 N건 (≥ 20건) 수집

운영자 또는 impl sub-agent
  ↓ (4) settings.json 원복 (capture wrapper 제거)
  ↓ (5) /tmp/hook-input-*.json N건 분석
  ↓ (6) 본 spec §6 결과 표 + 직전 spec §14-3 표 update (PR)
  ↓ (7) /tmp/hook-input-*.json 삭제 (PII)
```

#### 임시 wrapper script (예시)

```bash
#!/usr/bin/env bash
# /tmp/hook-payload-capture.sh — PreToolUse(Bash) payload 캡처 wrapper
# 사용: ~/.claude/settings.json 의 PreToolUse Bash command 를 본 경로로 swap.
# 측정 완료 후 원복 + /tmp/hook-input-*.json 삭제 의무 (PII).
set -u

INPUT="$(cat)"
TS_NS="$(date +%s%N)"
DUMP="/tmp/hook-input-${TS_NS}.json"

# 1. tee — payload 캡처 (실패해도 hook 통과)
printf '%s' "$INPUT" > "$DUMP" 2>/dev/null || true

# 2. passthrough — 기존 hook 호출 그대로
printf '%s' "$INPUT" | bash /home/mobruji/.mobruji/helper-direct-work-guard.sh
HOOK_EXIT=$?

# 3. exit code forward (차단 동작 보존)
exit "$HOOK_EXIT"
```

#### 분석 phase

```bash
# top-level key 분포
for f in /tmp/hook-input-*.json; do
  python3 -c "import json; print(','.join(sorted(json.load(open('$f')).keys())))" 2>/dev/null
done | sort | uniq -c | sort -rn

# cwd candidate 4 경로 매칭률
for f in /tmp/hook-input-*.json; do
  python3 -c "
import json
d = json.load(open('$f'))
candidates = {
    'payload.cwd':         d.get('cwd', ''),
    'payload.tool_input.cwd': d.get('tool_input', {}).get('cwd', '') if isinstance(d.get('tool_input'), dict) else '',
    'payload.session.cwd': d.get('session', {}).get('cwd', '') if isinstance(d.get('session'), dict) else '',
    'payload.context.cwd': d.get('context', {}).get('cwd', '') if isinstance(d.get('context'), dict) else '',
}
print(','.join(k for k, v in candidates.items() if v))
" 2>/dev/null
done | sort | uniq -c | sort -rn

# tool_input.command 매칭 sanity check (이미 정합 알려진 path)
for f in /tmp/hook-input-*.json; do
  python3 -c "
import json
d = json.load(open('$f'))
cmd = d.get('tool_input', {}).get('command', '') if isinstance(d.get('tool_input'), dict) else ''
print('MATCH' if cmd else 'EMPTY')
" 2>/dev/null
done | sort | uniq -c
```

### 5-5) DB 마이그레이션
없음.

### 5-6) 프론트엔드 화면
없음.

## 5-7) 영향 파일

| 경로 | 변경 종류 | 비고 |
|---|---|---|
| `docs/features/hook-payload-schema-measurement.md` | 신설 (본 PR) | 측정 절차 SoT |
| `~/.claude/settings.json` | 임시 swap (impl 단계, runtime only) | 측정 phase 만, commit 금지 |
| `/tmp/hook-payload-capture.sh` | 임시 신설 (impl 단계, runtime only) | 측정 phase 만, commit 금지. 단 재현 가능성 위해 본 spec §5-4 에 코드 인용 |
| `/tmp/hook-input-*.json` | 임시 신설 (runtime only) | 측정 완료 후 즉시 삭제 의무 (PII) |
| `docs/features/helper-direct-work-guard-subagent-context.md` | edit (별도 후속 PR) | §14-3 표 의 후보 4개 schema → 측정 결과로 ✅/❌ marking |

**경로 검증 절차** (impl sub-agent 자가 탐색):
```bash
# settings.json hook command 현행 확인
grep -nE 'PreToolUse|helper-direct-work-guard' ~/.claude/settings.json

# 측정용 wrapper 위치 (제안 — runtime only, repo 미 commit)
ls -la /tmp/hook-payload-capture.sh 2>/dev/null || echo "<not yet deployed>"

# 캡처 결과 위치
ls /tmp/hook-input-*.json 2>/dev/null | wc -l
```

### 보호 영역 변경 여부 (필수 명시)

- 보호 영역 변경 여부: ☐ 없음 / ☑ 있음 — `~/.claude/settings.json` (runtime only, repo 미 commit). 본 spec 은 docs 만 — 머지 시 보호 영역 변경 0. impl 단계 settings.json swap 은 commit 대상 아님 — repo 무영향. 단 운영자 / sub-agent 가 settings.json 을 잘못 swap 한 채 원복 누락 시 nmae 본진 차단 사고 가능 (rev 단계 1 가중도 정보).

## 6) 작업 분할 (예상 PR 리스트)

- [x] **PR A (plan, 본 PR)**: 본 spec 신설 (`docs/features/hook-payload-schema-measurement.md`)
- [ ] **PR B (be 또는 helper-launched, impl)**: 임시 wrapper deploy + 5-10 분 측정 + 캡처본 분석 + 본 spec §6 결과 표 update + `helper-direct-work-guard-subagent-context.md §14-3` cross-ref update. 측정 후 wrapper / 캡처본 정리.
- [ ] **PR C (be, hook 정합)**: PR B 결과 기반 hook 의 cwd 파싱 path 정합. 단순 case (e.g. `tool_input.cwd` 로 이동) 면 PR B 와 통합 가능. fallback cascade (직전 spec §14-6 PR 6) 와는 별도.

### 6-1) 측정 결과 표 (PR B 가 작성)

> 본 표는 PR B 가 측정 후 update. 본 PR (plan) 머지 시점에는 placeholder.

| candidate path | 매칭률 (캡처 N건 중) | 가장 자주 등장한 cwd 값 (redacted) | 채택 / 폐기 |
|---|---|---|---|
| `payload.cwd` | TBD / N | TBD | TBD |
| `payload.tool_input.cwd` | TBD / N | TBD | TBD |
| `payload.session.cwd` | TBD / N | TBD | TBD |
| `payload.context.cwd` | TBD / N | TBD | TBD |
| 외 (예: `payload.workingDirectory`) | TBD | TBD | TBD |

또한 top-level keys 분포 (PR B update):

```
<TBD — PR B 측정 후 박제>
```

### 6-2) PR B 완료 조건 (rev 단계 1 게이트)

- [ ] 캡처본 N ≥ 20 (5-10 분 측정 + ≥ 3 sub-agent launch)
- [ ] §6-1 표 의 4 candidate 모두 매칭률 측정 완료
- [ ] 채택 path 가 매칭률 ≥ 80% 거나, 모두 < 50% 면 fallback C (process `pwd` / `$PWD`) escalation 권고
- [ ] `helper-direct-work-guard-subagent-context.md §14-3` 표 의 후보 4개 schema 행에 ✅ / ❌ marker append
- [ ] 임시 wrapper / 캡처본 삭제 검증 (`ls /tmp/hook-input-*.json` empty + settings.json 원복)
- [ ] `~/.mobruji/hook-bypass.log` 의 다음 24h `cwd!=""` 비율 추이 메모

## 7) 테스트 전략

### 7-1) 임시 wrapper 자체 검증 (PR B impl 단계 사전 게이트)

deploy 전 wrapper script 자체가 hook 동작을 깨지 않는지 검증:

```bash
# T1: sentinel 우회 통과 검증 (직전 spec T4 거울)
echo '{"cwd":"/home/mobruji/mobruji","tool_input":{"command":"MOBRUJI_ALLOW_DIRECT=1 gh pr create"}}' | bash /tmp/hook-payload-capture.sh
echo "T1 exit=$?"  # 기대: 0

# T2: nmae 본진 차단 유지 검증 (직전 spec T3 거울)
echo '{"cwd":"/home/mobruji/mobruji","tool_input":{"command":"gh pr create --base develop"}}' | bash /tmp/hook-payload-capture.sh
echo "T2 exit=$?"  # 기대: 2

# T3: 캡처 결과 존재 확인
ls /tmp/hook-input-*.json | wc -l  # 기대: ≥ 2 (T1+T2)
```

### 7-2) 측정 phase 활동 매트릭스

| trigger 유형 | 최소 횟수 | 목적 |
|---|---|---|
| sub-agent launch + Bash 호출 (be/fe/rev/plan 중 ≥ 2 role) | 3 | sub-agent 컨텍스트 payload schema 확인 |
| nmae 본진 일반 명령 (`git status` 등 — Bash) | 5 | nmae 컨텍스트 payload schema 확인 |
| helper 본체 Bash 호출 (allowlist) | 2 | helper 컨텍스트 payload schema 확인 |
| Agent tool 호출 (`launch` 도구) | 1+ | nested context payload schema 확인 (Bash 내부 호출 시) |

### 7-3) 결과 정합성 검증 (PR C 후속)

- PR C 머지 후 24h 동안 `~/.mobruji/hook-bypass.log` 모니터링
- 기대: `cwd!=""` 비율 50% 이상 회복 + `reason=subagent-cwd` / `reason=subagent-role` entry 출현
- 미회복 시: 직전 spec §14-3 가설 2 (Agent tool 의 PR meta-process cwd 문제) escalate

### 7-4) Mock 전략
mock 사용 금지 — 실측이 본 spec 의 핵심 목적. 합성 payload 는 §7-1 wrapper 자체 검증에서만 사용.

## 8) 오픈 질문

| # | 질문 | 선택지 | 자율 결정 (plan sub-agent, 2026-05-29) |
|---|---|---|---|
| Q1 | 측정 phase 동안 차단 사고 (e.g. wrapper 버그로 nmae 본진 차단 깨짐) 위험을 어떻게 가드? | (a) wrapper 자체 검증 (§7-1) 만 / (b) measurement read-only 모드 (hook 차단 효과 0 + log only) / (c) 측정 시간 ≤ 5 분 cap | **(a) — §7-1 사전 검증 통과 시 wrapper passthrough 가 hook exit code 그대로 forward 하므로 안전. (b) 는 측정 phase 중 실제 차단 사고 발생 시 사용자 인지 없이 우회 = security risk** |
| Q2 | 측정 N건 ≥ 20 미달 시 (5-10 분 동안 activity 부족) 어떻게? | (a) 측정 phase 연장 (최대 30 분) / (b) sub-agent launch 합성 (의도된 Bash 호출) / (c) 부족한 채로 결과 박제 + 추가 측정 trigger 명시 | **(a) + (b) 병행 — 최대 30 분 cap + sub-agent launch 의도 호출 (단 prompt 에 "측정 phase" 명시)** |
| Q3 | top-level key 가 4 candidate 외 (예: `payload.metadata.cwd`, `payload.workingDirectory`) 인 경우? | (a) 본 spec §6-1 표에 행 추가 / (b) hook 의 cascade 에 추가 candidate 포함 | **(a) — 본 spec update. cascade impl 은 별도 PR (직전 spec §14-6 PR 6) 의 책임** |
| Q4 | 측정 결과 박제 시 payload 의 raw 값 일부 commit 가능? | (a) 절대 금지 (PII) / (b) cwd / role 등 redact 가능 field 만 / (c) hash 또는 shape 만 | **(a) — schema shape 만 commit. 구체 cwd 값도 worktree 경로 leak 으로 보고 redact** |
| Q5 | 측정 결과가 직전 spec §14-3 의 가설 1 (`tool_input.cwd` 등 다른 path) 와 일치하지 않으면? | (a) 본 spec §3 escape hatch S3 (process `pwd` fallback) escalate / (b) 추가 가설 spec 신설 / (c) Anthropic 공식 문서 hunt 후 escalate | **(a) — process `pwd` fallback 이 가장 robust. impl PR C 가 cascade 마지막 단계로 추가 권고** |

## 9) 결정 로그

- 2026-05-29: 초안 작성 (status=draft). plan sub-agent (nmae 직접 trigger, PR #1257 §14-6 PR 4 spec 분리) 가 작성. 사고 박제 evidence (43/44 empty cwd) 기반. PR A 본 spec 만, PR B (측정) / PR C (정합) 후속 분리.

## 10) 위험 / 롤백

### 위험
1. **임시 wrapper 가 hook 동작 변경**: wrapper 가 exit code forward 누락 시 nmae 본진 차단 깨짐 → release / tag 보호 사고. 가드: §7-1 사전 검증 의무 + impl PR B 가 wrapper script 코드 PR 본문에 첨부 (review 가능)
2. **캡처본 PII leak**: `/tmp/hook-input-*.json` 이 `gh pr create --body` 본문 / 사용자 메시지 raw 포함 가능. 가드: 측정 완료 즉시 삭제 + 본 spec 박제 시 schema shape 만
3. **settings.json swap 후 원복 누락**: 측정 phase 종료 후 원복 안 하면 capture wrapper 가 계속 동작 → `/tmp` 파일 누적 + 잠재 wrapper 버그 노출 시간 ↑. 가드: PR B 완료 조건 §6-2 의 "settings.json 원복 검증"
4. **측정 sample size 부족**: 5-10 분 동안 ≥ 20 건 캡처 불가능 시 결과 신뢰도 ↓. 가드: §8 Q2 결정 (최대 30 분 + 의도 trigger)
5. **schema 가 dynamic (per-tool / per-context 분기)**: 같은 Bash tool 인데 컨텍스트 별 payload 다를 가능성. 가드: 활동 매트릭스 §7-2 가 다양한 trigger 유형 수집 — 분석 시 그룹별 비교

### 롤백
- 본 spec PR (PR A) 자체 롤백 영향 0 — 코드 변경 없음, 다른 spec 의존 0
- 측정 phase (PR B impl) 롤백 = settings.json 원복 + `/tmp/hook-input-*.json` 삭제 1줄 명령
- hook 정합 (PR C) 롤백 = git revert — 단일 파일 (`helper-direct-work-guard.sh`) edit

## 11) 의존성

### 본 spec 자체
- 없음 (docs only)

### PR B (측정 impl) 의존
- `~/.claude/settings.json` write 권한 (운영자 또는 helper-launched sub-agent)
- `/tmp/` write 권한
- 측정 phase 동안 sub-agent / helper / nmae 활동 trigger 가능 (idle 상태에서는 측정 불가)

### PR C (정합) 의존
- PR B 결과 (§6-1 표)

## 12) PR 분담

- **plan = 본 spec (PR A, 이번 PR)** — docs/features/ 작성, 코드 변경 없음
- **be 또는 helper-launched = PR B (측정 impl)** — capture wrapper + 측정 + 결과 박제. helper-launched 권고 (단순 1회 작업 + settings.json swap 권한)
- **be = PR C (hook 정합)** — PR B 결과 기반. 단순 path 수정만이면 PR B 와 통합 가능

## 13) 관련 메모리 / 룰

- `[[feedback-evidence-based-root-cause]]` — 본 spec 의 motivation 자체 (추정 X → evidence 수집 → root cause 확정)
- `[[feedback-verify-and-iterate]]` — 측정 → 결과 박제 → fix → 24h 모니터링 까지 1 cycle
- `[[feedback-sub-agent-no-user-wait]]` — hook 정합 후 sub-agent 가 sentinel 학습 의존 ↓
- `docs/features/helper-direct-work-guard-subagent-context.md` — parent spec (§14 사고 박제). 본 spec 은 §14-4 검증 1 의 독립 SoT
- `docs/features/agent-launch-wrapper-enforcement.md` — sub-agent launch flow (MOBRUJI_ROLE inherit 가설 3 separate)

## 14) 권고 fix path 요약 (impl PR 가이드)

본 spec 의 측정 결과와 무관하게 **최종 hook 코드 권고 형태**:

```bash
# pseudocode — PR C (hook 정합) 가 cascade 도입
CWD_RAW="$(printf '%s' "$INPUT" | python3 -c "
import json, sys, os
try:
    d = json.load(sys.stdin)
    # cascade fallback (priority 순)
    cwd = (
        d.get('cwd', '')
        or (d.get('tool_input', {}).get('cwd', '') if isinstance(d.get('tool_input'), dict) else '')
        or (d.get('session', {}).get('cwd', '') if isinstance(d.get('session'), dict) else '')
        or os.environ.get('PWD', '')
    )
    print(cwd)
except Exception:
    print('')
")"
```

본 spec 측정 결과 (§6-1) 가 priority 순서 결정 — 매칭률 가장 높은 path 가 첫 번째. 모두 < 50% 면 `PWD` env 가 첫 번째 + 다른 path 들이 후순위 fallback.

추가 권고 (별도 spec / PR 분리 가능):
- **MOBRUJI_ROLE export wrapper 보강** — `tools/agent-launch-wrapper.sh` 가 sub-agent prompt 첫 줄에 `export MOBRUJI_ROLE=<role>` 박는지 검증 (직전 spec §14-6 PR 5 의 SoT). 본 spec 의 측정 결과가 cwd 분기 dead 로 확정되면 role 분기가 유일한 우회 path 가 되므로 PR 5 의 priority ↑
- **sentinel 우회 비율 모니터링** — `~/.mobruji/hook-bypass.log` 의 `reason=sentinel` 비율 cron alert (직전 spec §14-6 PR 7)
