---
feature: helper-direct-work-guard sub-agent context 보강
slug: helper-direct-work-guard-subagent-context
status: implementing
owner: @mobruji-maestro
scope: infra
related_issues: []
related_prs: [1146, 1154, 1252]
last_reviewed: 2026-05-29
---

# helper-direct-work-guard hook sub-agent context 보강

## 1) 개요 (What / Why)

`/home/mobruji/.mobruji/helper-direct-work-guard.sh` (settings.json `PreToolUse(Bash)` matcher) 는 nmae 본진의 `gh pr create / release / tag / main push` 등을 차단하는 가드 hook 이다. 그러나 현재 차단 조건이 `cwd == /home/mobruji/mobruji` 단일 기준이기 때문에, sub-agent (be/fe/rev/plan) 가 어떤 이유로 cwd 가 메인 워크트리로 들어와 `gh pr create` 를 호출하면 동일하게 차단되어 매번 `MOBRUJI_ALLOW_DIRECT=1` sentinel 을 prefix 해야만 PR 생성이 가능하다.

본 spec 은 hook 의 차단 로직에 **sub-agent role context 인식 레이어를 추가** 하여, 정당한 sub-agent (be/fe/rev/plan + 임시 워크트리) 의 PR 생성 책무 수행 시 sentinel 없이 통과 시키되 nmae 본진의 직접 PR 차단은 그대로 유지하는 것을 목표로 한다.

### 사고 박제 (2026-05-26)
- be sub-agent 2회 (PR #1143 nginx fix 진행 중 포함), plan sub-agent 1회 → 총 3회 `gh pr create` 시 hook 차단 발생
- 우회: `MOBRUJI_ALLOW_DIRECT=1 gh pr create ...` sentinel prefix
- 문제점:
  1. **학습 부담** — sub-agent prompt template 에 sentinel 학습이 명시되지 않아 매번 시행착오 + nmae 재지시 필요
  2. **역할 의미 누락** — hook 가 cwd 만 보고 "직접 작업" 으로 판단. role context (sub-agent 정당 책무) 인식 부재
  3. **자율 default 룰 위반 우려** — sub-agent 가 sentinel 모르면 "사용자 결정 대기" wait state 에 진입 가능 ([[feedback-sub-agent-no-user-wait]] 위반)

## 2) 사용자 시나리오

### S1. sub-agent 워크트리 cwd 에서 PR 생성 (정상 경로)
```
be sub-agent (cwd=/home/mobruji/mobruji-be)
  → gh pr create --base develop ...
  → hook 통과 (cwd 가 워크트리이므로 기존 로직에서 이미 통과)
```
이 경우는 **현재도 통과**. 본 spec 의 핵심 대상은 S2.

### S2. sub-agent 가 메인 워크트리 cwd 로 들어와 PR 생성 (사고 케이스)
```
sub-agent (Agent tool cwd 미지정 또는 잘못 지정 → 기본값 메인)
  → gh pr create
  → hook 차단 (cwd=mobruji + gh pr create 패턴 매치)
  → sub-agent 우회: MOBRUJI_ALLOW_DIRECT=1 gh pr create ... (학습 의존)
```

### S3. nmae 본진 직접 PR 생성 (차단 유지 — 기존 동작)
```
nmae 본진 (cwd=/home/mobruji/mobruji)
  → gh pr create
  → hook 차단 (sub-agent role marker 없음)
```

### S4. helper 본체 default-DENY (영향 없음)
helper 본체는 별도 allowlist 로 처리되며 본 spec 대상이 아니다.

## 3) 요구사항

### 기능 요구사항
- [ ] hook 는 다음 중 하나 이상 만족 시 PR 생성 명령 (`gh pr create` 등) 통과:
  - (A) **cwd 가 sub-agent 워크트리 화이트리스트** (`/home/mobruji/mobruji-be|fe|rev|plan`) 또는 **임시 워크트리** (`/tmp/mobruji-*` 또는 `/home/mobruji/mobruji-tmp-*`)
  - (B) **환경변수 `MOBRUJI_ROLE` 값이 sub-agent role** 중 하나 (`backend`, `frontend`, `review`, `plan`, `subagent`) 인 경우
  - (C) **기존 `MOBRUJI_ALLOW_DIRECT=1` sentinel** (호환성 유지, 비상 우회로 잔존)
- [ ] cwd 검증은 **`realpath`** 로 정규화 후 화이트리스트 매치 (symlink/`..` 우회 가드)
- [ ] sub-agent role 식별 매핑 (sub-agent prompt template §1 PR session 라벨 표 거울):
  - be → `MOBRUJI_ROLE=backend`
  - fe → `MOBRUJI_ROLE=frontend`
  - rev → `MOBRUJI_ROLE=review`
  - plan → `MOBRUJI_ROLE=plan`
  - 기타 임시 sub-agent → `MOBRUJI_ROLE=subagent`
- [ ] helper 본체 default-DENY 분기 (`MOBRUJI_ROLE=helper`) 는 본 변경의 영향을 받지 않는다 — helper 는 차단 우선 평가
- [ ] nmae 본진 (cwd=`/home/mobruji/mobruji` + `MOBRUJI_ROLE` unset/`nmae`) 에서의 `gh pr create / release / tag / main push` 차단 동작은 **현행 유지**

### 비기능 요구사항
- **audit log**: 본 hook 가 sub-agent context 로 우회 허용 시, `~/.mobruji/hook-bypass.log` 에 한 줄 append (JSON Lines):
  ```json
  {"ts":"<ISO-8601>","role":"<MOBRUJI_ROLE or detected>","cwd":"<realpath>","cmd":"<truncated 200 chars>","reason":"<subagent-cwd|subagent-role|sentinel>"}
  ```
  - **`MOBRUJI_ALLOW_DIRECT=1` sentinel** 우회는 **항상** audit 기록 (오용 추적용)
  - cwd/role 자동 통과는 **DEBUG 모드** (`MOBRUJI_HOOK_DEBUG=1`) 일 때만 기록 (default off, log 폭주 방지)
- **성능**: hook 추가 분기는 단순 string match + 단일 `realpath` 호출 — 추가 지연 < 50ms 목표
- **로그 보안**: cmd 200자 truncate (`gh pr create --body` 본문 일부 노출 방지)
- **테스트 가능성**: hook 의 핵심 분기 로직을 `bats` 또는 shell unit test 로 검증할 수 있도록 함수화 권장 (impl 단계)

## 4) 범위 / 비범위

### 포함
- hook script (`/home/mobruji/.mobruji/helper-direct-work-guard.sh`) 의 nmae 분기 (line 52 이후) 에 sub-agent context 식별 추가
- 화이트리스트 cwd 매트릭스 정의 (be/fe/rev/plan 4 워크트리 + 임시 패턴)
- audit log 포맷 정의
- `MOBRUJI_ROLE` env 값 표준화 (sub-agent prompt template §1 거울)

### 제외 (Out of Scope)
- **Claude Code Agent tool `subagent_type` → `MOBRUJI_ROLE` env auto-inject 메커니즘 자체 구현** — Anthropic Agent SDK 가 sub-agent 호출 시 env 를 inherit 하는지는 §8 미해결 Q (자율 결정 표시) 로 두며, 본 spec 은 인프라 측 인식 로직만 정의. 만약 auto-inject 가 불가능하다면 cwd 화이트리스트 (요건 A) 로 fallback
- helper allowlist 확장 — `helper-direct-work-guard.sh` 의 helper 분기 (line 21-50) 는 별도 spec (`docs/features/helper-direct-work-guard-allowlist.md` — 미작성, 필요 시 후속)
- bot.py / discord-daemon 변경 — 본 hook 는 Claude Code harness PreToolUse 단일 책임
- sub-agent prompt template (`docs/ai-harness/actors/sub-agent.md`) 변경 — 본 spec 후속 impl PR 에서 "PR 생성 시 sentinel 학습" 항목 제거 가능 (별도 doc PR)

## 5) 설계

### 5-1) 도메인 모델
도메인 엔티티 변경 없음. **infra / harness 레이어**.

관련 컨텍스트:
- `docs/ai-harness/actors/sub-agent.md §1` — sub-agent role ↔ session 라벨 매핑 (envvar 명명도 거울)
- `CLAUDE.md §13-1` — sub-agent 공통 룰 포인터 (`gh pr create --base develop` 강제)

### 5-2) API 엔드포인트
해당 없음.

### 5-3) 외부 연동
- **GitHub CLI (`gh`)** — `gh pr create` 호출 자체는 외부 GitHub API. hook 는 단지 그 명령의 실행 허용/차단만 결정.
- **`realpath` (coreutils)** — cwd 정규화. 시스템 기본 설치 가정.

### 5-4) 데이터 흐름 / 시퀀스

```
sub-agent Bash tool 호출 (cmd="gh pr create ...")
  ↓
Claude Code harness PreToolUse hook
  ↓
/home/mobruji/.mobruji/helper-direct-work-guard.sh
  │
  ├─ MOBRUJI_ALLOW_DIRECT=1 sentinel?
  │   YES → audit log append → exit 0 (allow)
  │
  ├─ MOBRUJI_ROLE=helper?
  │   YES → helper allowlist 분기 (현행 유지)
  │
  ├─ [신설] MOBRUJI_ROLE in {backend,frontend,review,plan,subagent}?
  │   YES → (DEBUG=1 시 audit log) → exit 0 (allow)
  │
  ├─ [신설] realpath(cwd) in {mobruji-be, mobruji-fe, mobruji-rev, mobruji-plan, /tmp/mobruji-*, /home/mobruji/mobruji-tmp-*}?
  │   YES → (DEBUG=1 시 audit log) → exit 0 (allow)
  │
  ├─ cwd == /home/mobruji/mobruji?
  │   YES → FORBIDDEN_PATTERNS 검사
  │       매치 → stderr 메시지 + exit 2 (deny)
  │       미매치 → exit 0 (allow)
  │
  └─ 그 외 cwd → exit 0 (allow, 안전 default)
```

### 5-5) DB 마이그레이션
없음.

### 5-6) 프론트엔드 화면
없음.

## 5-7) 영향 파일

| 경로 | 변경 종류 | 비고 |
|---|---|---|
| `/home/mobruji/.mobruji/helper-direct-work-guard.sh` | edit | sub-agent context 분기 추가 (line 52 nmae 분기 보강) |
| `~/.mobruji/hook-bypass.log` | 신설 (runtime) | JSON Lines audit log. logrotate 미적용 (수동 점검). impl 단계에서 logrotate spec 추가 검토 |
| `docs/ai-harness/actors/sub-agent.md` | edit (후속) | sub-agent 가 `MOBRUJI_ROLE` env 를 신뢰할 수 있다는 룰 명시 (impl PR 별도) |

**경로 검증 절차** (impl 단계 sub-agent 가 자가 탐색):
```bash
find /home/mobruji -name "helper-direct-work-guard*" -type f 2>/dev/null
# 기대 출력: /home/mobruji/.mobruji/helper-direct-work-guard.sh
```

settings.json 에 등록된 hook 명령 경로도 함께 확인:
```bash
grep -n "helper-direct-work-guard" /home/mobruji/.claude/settings.json
```

## 6) 작업 분할 (예상 PR 리스트)

- [x] **PR 1 (plan, 본 PR)**: spec 작성 (`docs/features/helper-direct-work-guard-subagent-context.md`)
- [ ] **PR 2 (be)**: hook script 구현 + audit log + 셸 unit test (가능하면 `bats`)
- [ ] **PR 3 (plan / 후속)**: `docs/ai-harness/actors/sub-agent.md` 갱신 — sub-agent prompt 에 sentinel 학습 항목 제거 / `MOBRUJI_ROLE` env 참조 룰 명시

## 7) 테스트 전략

### 7-1) 검증 시나리오 (impl PR 게이트)

| # | 시나리오 | cwd | MOBRUJI_ROLE | cmd | 기대 |
|---|---|---|---|---|---|
| T1 | sub-agent 워크트리 PR 생성 (정상) | `/home/mobruji/mobruji-be` | (unset) | `gh pr create ...` | 통과 (cwd 화이트리스트) |
| T2 | sub-agent role env set + 메인 cwd | `/home/mobruji/mobruji` | `backend` | `gh pr create ...` | 통과 (role 화이트리스트) |
| T3 | nmae 본진 직접 PR (차단 유지) | `/home/mobruji/mobruji` | (unset) | `gh pr create ...` | 차단 + exit 2 |
| T4 | nmae 본진 sentinel 우회 | `/home/mobruji/mobruji` | (unset) | `MOBRUJI_ALLOW_DIRECT=1 gh pr create ...` | 통과 + audit log |
| T5 | nmae 본진 일반 명령 (영향 없음) | `/home/mobruji/mobruji` | (unset) | `git status` | 통과 |
| T6 | helper default-DENY (영향 없음) | `/home/mobruji/mobruji` | `helper` | `vim` | 차단 (helper allowlist 미매치) |
| T7 | helper allowlist 명령 (영향 없음) | `/home/mobruji/mobruji` | `helper` | `discord-reply.sh ...` | 통과 |
| T8 | 임시 워크트리 PR 생성 | `/tmp/mobruji-hotfix-x` | (unset) | `gh pr create ...` | 통과 (임시 워크트리 패턴) |
| T9 | symlink/realpath 우회 시도 | `/home/mobruji/mobruji/../mobruji-be` | (unset) | `gh pr create ...` | 통과 (realpath 정규화 후 화이트리스트) |
| T10 | symlink 으로 가짜 sub-agent cwd 생성 | `/tmp/fake-mobruji-be` (symlink → mobruji) | (unset) | `gh pr create ...` | 차단 (realpath 결과는 mobruji 본진) |

### 7-2) 테스트 형식 (impl PR 시)
- **shell unit test (bats 또는 plain bash + assertion)**:
  - hook script 의 핵심 분기 로직을 함수화 (`is_subagent_context()`, `check_cwd_whitelist()`)
  - 위 T1-T10 매트릭스를 자동 실행
- **수동 verification (impl PR 머지 직전 ai-harness §16 의무)**:
  - 실제 sub-agent (be) launch → `gh pr create` 호출 → 통과 확인 (`~/.mobruji/hook-bypass.log` 점검)

### 7-3) Mock 전략
없음 (hook 는 stdin JSON + env + cwd 만 사용. 외부 의존 없음).

## 8) 오픈 질문

| # | 질문 | 선택지 | 자율 결정 (plan sub-agent, 2026-05-26) |
|---|---|---|---|
| Q1 | tmpfs / nested worktree (`/tmp/mobruji-*/nested-mobruji-*`) 도 허용? | (a) 패턴 화이트리스트 (`/tmp/mobruji-*`) / (b) realpath 기준 sub-agent 워크트리 4 개 + `/tmp/mobruji-*` 1단계만 | **(b) — 4 워크트리 + `/tmp/mobruji-*` 1단계 까지만. nested 허용 시 우회 공격 surface ↑** |
| Q2 | `MOBRUJI_ROLE=subagent` env 가 Claude Code Agent tool 호출 시 sub-agent shell 에 inherit 되는지? | (a) inherit 됨 → role 화이트리스트 의존 OK / (b) inherit 안 됨 → cwd 화이트리스트 + sub-agent prompt 가 export 의무 명시 | **(b) 추정 권고 — Agent tool 문서 미확인. impl 단계 be sub-agent 가 실측 후 결정. 불확실하면 cwd 화이트리스트 (요건 A) 만 신뢰** |
| Q3 | audit log rotation 정책? | (a) 미적용 (수동 점검) / (b) `logrotate` 100KB / (c) hook 자체 100줄 cap | **(a) draft / (c) impl 단계 reconsider — log 폭주 시 hook 자체 100 줄 cap 도입** |
| Q4 | nmae 본진 분기에서 `MOBRUJI_ROLE=nmae` 명시 env 도 신뢰? | (a) cwd 기준만 / (b) cwd + role 둘 다 매치 시만 nmae | **(a) — 단순화. cwd 가 메인 + role unset 또는 명시 nmae 둘 다 차단 적용** |

## 9) 결정 로그

- 2026-05-26: 초안 작성 (status=draft). plan sub-agent 가 사고 박제 3건 (be 2회 + plan 1회 PR 차단 사고) 기반 작성. Q1-Q4 자율 결정 (Q2 는 impl 단계 실측 보류).

## 10) 위험 / 롤백

### 위험
1. **realpath 정규화 누락 시 symlink 우회**: sub-agent 가 `/tmp/fake-mobruji-be` 같은 symlink 를 만들어 우회 가능 → realpath 절대 누락 금지 (요구사항 명시)
2. **`MOBRUJI_ROLE` env spoofing**: nmae 본진에서 `MOBRUJI_ROLE=backend gh pr create` 로 우회 시도 가능 → 본 hook 는 LLM 의 자율 정직성에 의존 (악의적 사용자 아님). sentinel 우회와 동일 신뢰 모델
3. **임시 워크트리 패턴 누락**: `/tmp/mobruji-*` 와 `/home/mobruji/mobruji-tmp-*` 외 추가 패턴 (예: `/var/tmp/mobruji-*`) 발견 시 → impl 단계에서 확장
4. **DEBUG audit log 미작동**: `MOBRUJI_HOOK_DEBUG=1` 환경변수 설정 누락 시 sub-agent 통과 흐름이 silent → 운영자 가시성 ↓. impl 단계에서 일정 비율 sampling 권고

### 롤백
- hook script 는 단일 파일 (`/home/mobruji/.mobruji/helper-direct-work-guard.sh`) → impl PR revert 또는 git checkout 으로 이전 버전 복원
- 분기 추가가 nmae 본진 차단을 깨뜨리는 경우 → T3 시나리오 검증 실패 시 즉시 revert
- audit log 파일은 누적만 함 (revert 시 손상 없음). impl 단계에서 logrotate 100KB cap 고려 시 별도 정리

## 11) 의존성

없음. 본 spec 은 **단일 hook script edit + audit log 신설** 로 완결. 외부 라이브러리 / DB / API 의존 없음.

후속 의존:
- impl PR (be) → sub-agent prompt template 갱신 PR (plan) 순서 권고. impl 완료 전 prompt 룰 변경 시 sub-agent 가 `MOBRUJI_ROLE` 신뢰하다 차단당하는 역행 발생 가능.

## 12) PR 분담

- **plan = 본 spec (이번 PR)** — docs/features/ 작성, 코드 변경 없음
- **be = 후속 impl PR** — hook script edit + audit log + shell unit test (bats 권고)
- **plan = 후속 doc PR (선택)** — sub-agent prompt template 의 sentinel 학습 룰 제거 / `MOBRUJI_ROLE` 신뢰 룰 명시 (impl PR 머지 후)

## 13) 관련 메모리 / 룰

- `[[feedback-sub-agent-no-user-wait]]` — sub-agent 가 sentinel 모르면 wait state 진입 가능 → 본 spec 의 핵심 motivation
- `[[feedback-pr-base-develop]]` — sub-agent PR 책무 (`gh pr create --base develop`) 와 직결
- `[[feedback-autonomous-default]]` — sentinel 학습 의존 = 자율성 저해. hook 가 role context 인식 시 자율 ↑
- `CLAUDE.md §13-1 / §13-2` — sub-agent 워크트리 + 라벨 룰 매트릭스
- `docs/ai-harness/actors/sub-agent.md §1 PR session 라벨 부착 의무` — role ↔ env 매핑 거울

## 14) 사고 박제 (2026-05-29 PR #1252) — impl 가 cwd 분기 통과 안 함

### 14-1) 발생

- **시각**: 2026-05-29 02:30:09Z (KST 11:30) — `gh pr create` 호출 시각 (audit log `~/.mobruji/hook-bypass.log` evidence).
- **sub-agent**: plan, cwd 의도 = `/home/mobruji/mobruji-plan`.
- **call site**: `cd /home/mobruji/mobruji-plan && MOBRUJI_ALLOW_DIRECT=1 gh pr create --base develop ...` — sentinel 미부착 시 hook exit 2 차단 메시지 발생 (직전 시도 retry).
- **결과**: PR #1252 는 sentinel prefix 우회로 정상 생성됨. 본 spec 의 §5-4 흐름이 의도대로 동작하지 않음 (요건 A cwd 분기 + 요건 B role env 분기 둘 다 통과 실패).

### 14-2) Evidence (audit log `~/.mobruji/hook-bypass.log`)

- 33건 sentinel 우회 entry 중 `"cwd": "<비어있지 않음>"` 인 entry **단 1건** (2026-05-27 PR #1154 직후 단일 record: `cwd=/home/mobruji/mobruji-be, reason=subagent-cwd`).
- 나머지 32건 모두 `"cwd": "", "role": "unset"` — hook 가 cwd 도 role 도 sub-agent 로 식별 못 함 → sentinel 만 유일 우회 path.
- 즉 **cwd / role 자동 우회 분기는 실전에서 거의 발화하지 않으며, sub-agent / nmae 모두 sentinel 학습 의존** 상태 (본 spec §1 motivation 의 핵심이 미해결).

### 14-3) Root cause 가설

#### 가설 1 (높음) — PreToolUse hook input 에 `cwd` field 가 sub-agent / nmae 호출 시 비어있음

- hook 파싱 코드 (line 95-99):
  ```python
  CWD_RAW="$(printf '%s' "$INPUT" | python3 -c "import json,sys
  try:
      print(json.load(sys.stdin).get('cwd',''))
  except Exception:
      pass")"
  ```
- Claude Code harness 의 `PreToolUse(Bash)` 가 stdin JSON payload 에 `cwd` 키를 채워서 전달하는지 **공식 문서 / 실증 검증 누락**. audit log 의 32/33 empty-cwd 가 강력 evidence.
- 만약 payload schema 가 `{"tool_input": {...}, "cwd": "..."}` 가 아닌 `{"tool_input": {"cwd": "..."}}` 또는 `{"context": {"cwd": "..."}}` 등 다른 위치라면 `.get('cwd','')` 가 항상 empty.

#### 가설 2 (낮음) — Agent tool (sub-agent) 호출 시 child shell 에 cwd 가 inherit 되지만 hook input payload 가 별도 worker 컨텍스트에서 생성됨

- harness 가 hook 을 PR meta-process 로 실행하는 경우 PreToolUse hook 의 cwd 가 항상 `harness root` 일 가능성.
- 검증 방법: hook 진입 시 `pwd` 직접 capture vs `tool_input.cwd` 비교.

#### 가설 3 (낮음) — `MOBRUJI_ROLE` env 가 Agent tool 호출 시 child shell 로 inherit 안 됨

- 본 spec §8 Q2 가 이미 "impl 단계 실측 후 결정" 으로 보류한 항목.
- audit log evidence 가 33건 모두 `role: "unset"` — sub-agent shell 에서 호출된 명령조차 role 미설정.
- 즉 Anthropic Agent SDK 가 sub-agent prompt 안에 `MOBRUJI_ROLE` 을 자동으로 export 하지 않으며, sub-agent prompt template 도 명시 export 하지 않음 (현행 wrapper `tools/agent-launch-wrapper.sh` 확인 필요).

### 14-4) 검증 절차 (다음 impl PR 또는 별도 검증 PR 의무)

#### 검증 1 — hook input schema 실측

```bash
# hook 진입 시 input payload 를 dump 하는 임시 wrapper 추가
cat > /tmp/hook-debug.sh <<'EOF'
#!/usr/bin/env bash
INPUT=$(cat)
DUMP="/tmp/hook-input-$(date +%s%N).json"
printf '%s' "$INPUT" > "$DUMP"
echo "[hook-debug] payload saved: $DUMP" >&2
# 본 hook 호출 그대로 패스스루
printf '%s' "$INPUT" | bash /home/mobruji/.mobruji/helper-direct-work-guard.sh
EOF
chmod +x /tmp/hook-debug.sh
# settings.json 의 PreToolUse command 를 /tmp/hook-debug.sh 로 임시 swap
```

기대 출력 — 다음 중 어느 schema 인지 판정:

| 후보 schema | 매칭 시 fix |
|---|---|
| `{"tool_input": {...}, "cwd": "/home/mobruji/mobruji-plan"}` | 현행 코드 OK — 다른 가설 검토 |
| `{"tool_input": {"cwd": "...", "command": "..."}}` | hook 의 cwd 파싱 path 를 `tool_input.cwd` 로 변경 |
| `{"session": {"cwd": "..."}, "tool_input": {...}}` | hook 파싱 path 를 `session.cwd` 로 변경 |
| `{"tool_input": {...}}` (cwd 없음) | hook 가 `pwd` 또는 환경변수 `PWD` 직접 사용 |

#### 검증 2 — MOBRUJI_ROLE env inherit 실측

```bash
# agent-launch-wrapper.sh 가 sub-agent prompt 안에 export 박는지 확인
grep -nE "MOBRUJI_ROLE|export" /home/mobruji/mobruji/tools/agent-launch-wrapper.sh

# sub-agent 가 가동 중인 tmux pane 에 env 확인 inject
tmux send-keys -t mobruji-plan:0.0 "env | grep MOBRUJI_ROLE" Enter
sleep 1
tmux capture-pane -t mobruji-plan:0.0 -p | tail -10
```

기대: `MOBRUJI_ROLE=plan` 출력. unset 이면 wrapper 가 export 의무 누락 — wrapper 보강 PR + spec §3 요구사항 [ ] 항목 추가.

#### 검증 3 — fallback path (Agent tool cwd 미지정 시)

위 두 검증이 모두 실패해도 sub-agent 가 PR 책무 수행 가능해야 함. fallback:

- (A) Agent tool 호출 직전 `tool_input` 에 `cwd: "/home/mobruji/mobruji-plan"` 명시 박음 — helper / nmae 의 Agent tool 호출부 (`launch-spawn.sh` 등 wrapper) 가 cwd 명시 의무.
- (B) sub-agent prompt 첫 줄에 `export MOBRUJI_ROLE=plan && cd /home/mobruji/mobruji-plan` 를 박음 (현재 `cd` 만 박혀있음 → role 추가).
- (C) hook 자체가 `pwd` (= process cwd) 와 input.cwd 둘 다 시도 — fallback 가드.

### 14-5) 회귀 가드 — sentinel 우회 빈도 모니터링

`~/.mobruji/hook-bypass.log` 가 100 줄 FIFO cap (line 47-54) 이므로 단기 통계만 추적 가능. 회귀 가드 권고:

- **임계치**: 100 줄 중 sentinel 우회 (`reason=sentinel`) 가 80% 이상 → impl 가 cwd / role 분기 미작동 신호.
- **자동 alert (follow-up)**: cron 또는 bot.py loop 가 1시간마다 sentinel 비율 측정 → 임계 초과 시 nmae tmux pane inject ("hook subagent-cwd/role 분기 dead 가능성 — 검증 절차 §14-4 의무").
- **수동 점검 명령**:
  ```bash
  total=$(wc -l < ~/.mobruji/hook-bypass.log)
  sentinel=$(grep -c '"reason": "sentinel"' ~/.mobruji/hook-bypass.log)
  echo "sentinel ratio: $sentinel / $total"
  ```

### 14-6) 작업 분할 (사고 박제 후속)

- [ ] **PR 4 (be 또는 helper-launched, 검증 가설 1)**: 임시 hook-debug wrapper 로 input payload schema 실측 → 결과 본 spec §14-3 표 update + hook 파싱 path 정합 PR.
- [ ] **PR 5 (plan 또는 be, 검증 가설 3)**: `tools/agent-launch-wrapper.sh` 가 sub-agent prompt 에 `export MOBRUJI_ROLE=<role>` 박는지 검증 + 누락 시 wrapper 보강.
- [ ] **PR 6 (be, fallback C)**: hook 가 `tool_input.cwd` + payload-root `.cwd` + `PWD` env 셋 다 시도하는 cascade fallback 도입 (가설 1 검증 결과와 무관하게 안전망).
- [ ] **PR 7 (be, 회귀 가드)**: sentinel 비율 모니터링 cron / loop + 임계 초과 시 nmae inject.

### 14-7) 자율 결정 (plan sub-agent, 2026-05-29)

- **결정**: 본 spec 에 사고 박제 §14 추가 — 별 spec 신설 대신 보강. 사유: 본 spec 의 §1 motivation (학습 부담 / 역할 의미 누락 / 자율 default 위반) 과 동일 root cause 의 추가 evidence 이므로 분리 시 SoT 분산.
- **후보 alternative**: 별도 spec `docs/features/helper-direct-work-guard-cwd-detection-failure.md` 신설.
- **채택 사유**: 본 spec 이 이미 cwd 화이트리스트 + role env 분기를 정의하므로, 실패 evidence + root cause 가설 + 검증 절차도 같은 spec 안에 있어야 후속 impl PR 작성자가 single source 로 의사결정 가능.
- **follow-up 분리**: 검증 4건 (PR 4-7) 은 본 spec 후속으로 별 PR 분리 — rev 부담 분담 + 가설별 검증 결과에 따라 PR scope 가 달라질 수 있음.
