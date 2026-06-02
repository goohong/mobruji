---
feature: Directive board — thread template + tag 체계 (status 5 + category 4)
slug: directive-board-template-and-tags
status: approved
owner: @mobruji-maestro
scope: infra
related_issues: []
related_prs: []
last_reviewed: 2026-05-28
---

# Directive board — thread template + tag 체계

## 1) 개요 (What / Why)

`directive_append.sh` 가 forum thread 생성 시 본문 = `title` (= 원본 메시지 80자) 단일 줄. 사용자가 forum 한 번 보면 무슨 상황 / 어디까지 진행됐는지 즉시 파악 불가 — 발화 archive 로 전락.

**사용자 정정 (2026-05-28)**: "forum 에 작성할 때에도 어떤 상황인지 잘 이해했는지, 어떤 진행 상황인지 내가 이해하기 쉽게 양식이 필요할 거 같아. 태그도 좋고, 템플릿도 필요할 거 같아".

본 spec = **template body + tag 체계** 도입. 직전 spec [[directive-pushpin-registration]] (등록 mechanism) 의 follow-up.

> **갱신 (#1385, 2026-05-30)** — 쓰레드 맥락 요약 + LLM 제목:
> - `build_template_body` 의 `💬 원본` 섹션 헤더를 **`💬 요약`** 으로 변경. `DIRECTIVE_SUMMARY_BODY` env 가 있으면 이 섹션에 LLM 정제 본문을 삽입 (env 미설정 시 기존처럼 `> {title}` 인용 fallback). 6 marker 양식은 그대로 유지.
> - forum thread **제목** = 원본 80자 truncate 가 아니라 **LLM 짧은 요약 제목** (bot.py `_run_claude_summarize`). 단건 메시지가 아니라 **핀(📌) 메시지가 속한 쓰레드 전체 대화 맥락**을 요약 대상으로 사용.
> - sub-agent cycle forum 제목도 동일 — `agent-launch-wrapper.sh` 가 `--directive-id` 로 directive-board.jsonl 의 정제 summary 를 제목 권위 소스로 사용.

## 2) 사용자 시나리오

- **시나리오 1 (등록 직후)**: 사용자가 메시지에 📌 tap → `directive_append.sh` 호출. forum thread 신설 + 본문 = template (원본 본문 + 발화자 + 시각 + 진행 체크박스 + 빈 관련 섹션). status tag = 🟡 대기.
- **시나리오 2 (helper 정제)**: helper 가 다음 turn 안에서 thread 본문 PATCH — 정제된 한 줄 요약 + 컨텍스트 (직전 사이클 / 작업 상황) 1-2 문장 + category tag 부착 (🎯 결정 / 🛠️ 작업 / 🐛 사고 / 💡 spec). 사용자 부담 0 (autonomous).
- **시나리오 3 (작업 위임)**: nmae 가 sub-agent 위임 결정 → `directive_status.sh in_progress <id>` 호출 → tag 🟡 → 🔵 + 본문 "진행" 섹션 [x] 분석 완료 + owner / cycle (be/fe/rev/plan) 부착.
- **시나리오 4 (PR 머지 완료)**: 관련 PR 머지 webhook → `directive_status.sh completed <id> <pr_url>` → tag 🔵 → 🟢 + 본문 [x] 완료 + PR URL 부착.
- **시나리오 5 (사용자가 forum 회고)**: 한 달 뒤 사용자가 forum sidebar 에서 🟢 완료 + 🎯 결정 filter → 어떤 결정 어떻게 진행됐는지 한눈에. 회고 가능.

## 3) 요구사항

### 기능 요구사항

- [x] `directive_append.sh` — `build_template_body` 함수 추가. 4번째 arg `[body]` 미명시 시 자동 호출.
- [x] template format: 📌 title / 💬 원본 / 🆔 directive_id · 👤 user · 🕐 KST / 📋 진행 (체크박스 3개) / 🔖 관련 / footer.
- [x] user_id 전달 — 5번째 arg 또는 `DIRECTIVE_USER_ID` env. graceful (없으면 👤 line 생략).
- [ ] **(follow-up PR)** `directive_status.sh` — tag 전이 (status 5: 🟡 / 🔵 / 🟢 / 🔴 / ⚪) + 본문 PATCH (진행 체크박스 + 관련 PR URL).
- [ ] **(follow-up PR)** helper 자동 정제 — turn-end 직전 hook 또는 helper rule. helper context % > 65% 시 skip.
- [ ] **(follow-up PR)** PR 머지 webhook — `.github/workflows/` 에 PR merge → `directive_status.sh completed <id> <pr_url>` 자동 호출.
- [ ] **(follow-up PR)** 4 사이클 cross-link — sub-agent 위임 시 per-cycle forum thread URL 부착.
- [ ] **(사용자 수동)** Discord forum 채널 `#모부르지-지시` 의 available_tags 설정 — 9개 (status 5 + category 4).

### 비기능 요구사항

- **graceful**: template build 실패 → 기존 raw `title` body fallback (이전 동작 호환).
- **idempotency**: 같은 msg_id 재 호출 시 jsonl grep 가드 (기존 동작 유지).
- **사용자 수동 setup 부담 최소**: forum 채널 tag 설정만 1회 — bot 자동 ensure 는 별도 PR (선택).

## 4) 범위 / 비범위

### 포함 (본 PR)

- `directive_append.sh` 의 template body 빌드 함수 + default body 변경.
- spec 문서 (본 파일).
- 단순 shell smoke test (template 본문 검증).

### 제외 (follow-up PR)

- `directive_status.sh` tag 전이 + 본문 PATCH.
- helper 자동 정제 hook / rule.
- PR 머지 webhook 자동 status 전이.
- 4 사이클 cross-link.
- forum available_tags bot 자동 ensure.
- `bot.py _handle_pin_reaction` 의 `DIRECTIVE_USER_ID` env 전달 (PR #1204 머지 후 follow-up).

## 5) 설계

### 5-1) Tag 체계

**status** (1개만 active, mutually exclusive — directive 의 생명주기):

| tag | 의미 | 부착 시점 |
|---|---|---|
| 🟡 대기 | 등록 직후 default | `directive_append.sh` |
| 🔵 진행 중 | sub-agent / nmae / 사용자 작업 중 | `directive_status.sh in_progress` |
| **🟣 결정 대기** | plan 분석 완료, 사용자 결정 대기 (§5-6 plan 분석 모드) | `directive_status.sh awaiting_decision` |
| 🟢 완료 | PR 머지 / 결정 적용 | `directive_status.sh completed` 또는 PR 머지 webhook |
| 🔴 폐기 | 취소 / reject | `directive_status.sh dropped` |
| ⚪ 보류 | defer (timeline 미정) | `directive_status.sh deferred` |

**category** (1-2개 — directive 의 분류, mobruji 빈도 기준):

| tag | 의미 | 예 |
|---|---|---|
| 🎯 결정 | 옵션 채택 / 의사결정 | "옵션 B로 가자" |
| 🛠️ 작업 | sub-agent 위임 / 구현 의뢰 | "rev 만 재개" |
| 🐛 사고 | 회귀 / 버그 / 운영 사고 | "추천 저장 실패 400" |
| 💡 spec | 기획 / 검토 / 정책 | "feature spec 검토" |

**제외 사유**: 정책 / 운영 / 비용 등 category 는 mobruji 빈도 낮음 (작업 / spec 으로 흡수). 6+ tag 는 사용자 매번 분류 부담 + 운영 over-engineering. 4 가 sweet spot.

### 5-2) Template — 등록 직후 (사용자 📌 tap 직후)

```text
📌 **{title}**

💬 원본
> {title}

🆔 `{message_id}` · 👤 <@{user_id}> · 🕐 {KST timestamp}

📋 진행 (🟡 대기)
- [ ] 분석 / 위임 결정
- [ ] 실행
- [ ] 결과 반영

🔖 관련 *(없음 — 진행되며 helper 가 추가)*

---
_갱신: {KST timestamp}_
```

**필드 설명**:
- `{title}` = 원본 메시지 첫 90자 (forum thread name 과 동일).
- `{message_id}` = Discord snowflake. 클릭 시 원본 메시지로 이동 가능 (사용자 회고 시 유용).
- `{user_id}` = 발화자 Discord user_id. graceful — 미명시 시 line 생략.
- 진행 체크박스 3단계: 분석 → 실행 → 결과 반영. helper / nmae 가 단계 별로 [x] 갱신.

### 5-3) Template — helper 정제 후 (follow-up PR)

```text
📌 **{정제된 한 줄 요약}**

💬 원본
> {원본 본문 (절단 없이 전체)}

🆔 `{message_id}` · 👤 <@{user_id}> · 🕐 {KST}

🧠 컨텍스트
{helper LLM 정제 — 직전 사이클 / 작업 상황 1-2 문장}

📋 진행 (🔵 진행 중)
- [x] 분석 / 위임 결정 — {결정 사유}
- [ ] 실행 — owner: {sub-agent name / nmae / 사용자}
- [ ] 결과 반영

🔖 관련
- PR: #N
- 이슈: #M
- 후속 directive: `{id}`
- 메모리: [[feedback-...]]

---
_갱신: {KST} (helper 정제 N회)_
```

### 5-4) `build_template_body` 함수 (shell)

```bash
build_template_body() {
  local title="$1"
  local msg_id="$2"
  local ts_kst="$3"
  local user_id="${4:-}"

  local user_line=""
  if [[ -n "$user_id" ]]; then
    user_line="👤 <@${user_id}> · "
  fi

  cat <<EOF
📌 **${title}**

💬 원본
> ${title}

🆔 \`${msg_id}\` · ${user_line}🕐 ${ts_kst}

📋 진행 (🟡 대기)
- [ ] 분석 / 위임 결정
- [ ] 실행
- [ ] 결과 반영

🔖 관련 *(없음 — 진행되며 helper 가 추가)*

---
_갱신: ${ts_kst}_
EOF
}
```

호출 시점 (`directive_append.sh`):
```bash
USER_ID="${5:-${DIRECTIVE_USER_ID:-}}"
if [[ -z "$BODY" ]]; then
  BODY=$(build_template_body "$TITLE" "$MSG_ID" "$TS_KST" "$USER_ID")
fi
```

### 5-5) 흐름 (state machine)

```text
사용자 📌 tap
    ↓
directive_append.sh (msg_id, title, "", "", user_id)
    ↓
build_template_body → forum thread 본문
+ 🟡 대기 tag + (선택) category fallback
    ↓
[follow-up] helper 다음 turn 자동 정제
    ↓
forum thread starter PATCH (정제 한 줄 + 컨텍스트 + category tag)
    ↓
[작업 위임]
    ↓
directive_status.sh in_progress <id> <cycle>
    ↓
🟡 → 🔵 tag swap + 본문 [x] 분석 + owner + cycle
    ↓
[PR 머지]
    ↓
directive_status.sh completed <id> <pr_url>
    ↓
🔵 → 🟢 tag swap + 본문 [x] 완료 + PR URL
```

### 5-6) 백로그 운영 모델 (사용자 결정 2026-05-28)

**사용자 정정**: "이렇게 등록해둔 directive forum 은 어디서 추적 관리해서 진행해? 큰 작업이면 plan, 작은 변경이면 be-fe 에게 넘어가서 해결되었음/구현되었음이 확인되면 완료가 표시되는 프로세스가 이어져야 유의미".

**핵심**: directive 등록 후 자동으로 추적 / 분배 / 완료 프로세스가 흘러가야 의미. nmae 가 백로그 owner.

#### 책임 분담

| actor | 역할 |
|---|---|
| **사용자** | 📌 tap 으로 등록. 폐기 / 보류 명시 결정 시 helper 통해 신호. |
| **bot.py** | event 처리 — 📌 reaction → 등록 (PR #1204), PR merge webhook → status 전이 (follow-up). |
| **nmae** | **백로그 owner**. 매 사이클 시작 시 🟡 대기 entry scan + 분배 결정 + 위임 (사유 명시 의무) + 완료 추적. |
| **sub-agent (be/fe/rev/plan)** | nmae 위임 받은 단일 사이클 작업. PR body 에 `directive: <id>` 명시. |
| **helper** | relay only — directive 자체 추적은 nmae 책임. |

#### 분배 분기 (nmae 판단)

| 분기 | 기준 | 위임 |
|---|---|---|
| **plan** | (a) 신규 기능 개발 OR (b) 단순 기능 개선을 넘어선 작업 OR (c) 신중하게 고민해야 하는 작업 (신규 도메인 / 다중 PR / 외부 연동 / spec 필요) OR **(d) 문제 정의 / 해결 선택지 발굴 자체가 필요한 모호 directive** (사용자: "내가 뱉은 말이 ~를 해결해 인데 누가 담당할 작업인지 애매하고, 어떤 선택지들로 해결할 수 있나 고민해서 문제를 설정하고 고민하는 과정이 필요하다면 plan") | plan 사이클 — spec 작성 / problem framing / alternative 발굴 후 be/fe 분기 |
| **be** | backend 모듈 단일 변경 / API 변경 / DB 마이그레이션 / 단순 기능 개선 (동사 + 대상 모두 명확) | be 사이클 직접 |
| **fe** | frontend UI 변경 / 컴포넌트 / 디자인 토큰 / 단순 기능 개선 (동사 + 대상 모두 명확) | fe 사이클 직접 |
| **rev** | 사고 / 회귀 / 버그 root cause 분석 필요 | rev 사이클 |
| **즉시 반영** | 의사결정 / 단순 결정 (옵션 채택 / mode 전환 등) | nmae 가 cycle 위임 없이 즉시 반영 |

#### nmae 판단 휴리스틱 (동사 × 대상)

directive 본문을 **동사 명확성 × 대상 명확성** 2x2 matrix 로 분류:

| 동사 명확성 | 대상 명확성 | 분배 | 예 |
|---|---|---|---|
| 명확 ("구현해" / "수정해" / "추가해") | 명확 | be / fe 직접 | "Player 컴포넌트 zinc 토큰 swap" |
| 명확 | 모호 | rev (사고 가능성) 또는 plan | "어딘가에서 400 뜨는데 고쳐줘" |
| **모호** ("해결해" / "이상해" / "어떻게 할까") | 명확 | **plan (해결 방법 발굴)** | "추천 정확도가 낮은데 어떻게 개선할까?" |
| **모호** | **모호** | **plan (문제 설정부터)** | "전반적으로 사용성 떨어져 보여 — 뭔가 정리가 필요해" |

#### plan 위임 사유 예시

| directive 유형 | 사유 예시 |
|---|---|
| 신규 기능 | `신규 도메인 (X-Y 추천 엔진), 다중 PR 예상, BE+FE 동시 변경` |
| 모호 문제 | `문제 정의 불명확 — 해결 방법 선택지 발굴 필요` |
| 결정 분기 다수 | `사용자 결정 분기점 다수 — spec 합의 우선 (옵션 A/B/C)` |
| 외부 연동 | `Spotify API 통합 — 인증 / rate limit / data shape 사전 검증 필요` |
| 다른 작업 영향 | `기존 X 흐름 / Y 룰과 충돌 가능 — 영향 분석 후 PR 분할 결정 필요` |

#### plan 위임 시 사유 명시 의무

사용자 정정: "plan 에게 위임했다면 그 이유를 적어줬으면 좋겠어. 위임 사유: 큰 기능이라서 / 변경점이 ~라서 이런 거".

nmae 가 `directive_status.sh in_progress <id> plan <reason>` 호출 → thread 본문에 다음 형식 박힘:

```text
📋 진행 (🔵 진행 중)
- [x] 분석 / 위임 결정 — nmae
- [ ] 실행 — **owner: plan**
  - **사유**: {reason — 예: "신규 도메인 추가 (X-Y 추천 엔진), 다중 PR 예상, 사용자 검토 필요"}
  - **위임 시각**: {KST}
- [ ] 결과 반영
```

**be / fe / rev 위임 시도 사유 명시 권장** (의무 X, 운영 안전). 단순 위임은 owner 만 박아도 충분.

#### Cycle-specific auto-inject

사용자 정정: "프론트에 할당된 작업이 있는데 프론트가 놀고있다 그러면 알아서 가져가야지".

- nmae 가 directive 분배 결정 시 `directive-board.jsonl` entry 에 `assigned_cycle: "fe"` 필드 박음.
- nmae watchdog (또는 bot.py `cycle_idle_watch_loop`) 이 cycle idle 검출 시 그 cycle 에 assigned 된 🟡 대기 entry 우선 inject.
- 사용자 부재 / autonomous loop 시 cycle 자동 처리 — [[feedback-autonomous-loop]] 의 자연스러운 확장.

#### 완료 자동화

- sub-agent 가 PR 생성 시 body 에 `directive: 1509427220...` 또는 `Closes directive 1509427220...` 명시 (sub-agent.md 룰 추가).
- PR 머지 webhook (`.github/workflows/directive-complete-on-merge.yml`) 이 pattern 매칭 → `directive_status.sh completed <id> <pr_url>` 호출 → 🟢 + 본문 [x] 완료 + PR URL 부착.

#### helper sub-agent 정제 흐름 (PR D, 2026-05-28)

사용자 정정: "pin 이 된건 좋은데, 그 이후에 속 내용을 채우고 인식하는 건 엔마에 역할인가? 추가된줄도 모르는거같애".

**책임 분담**:
- 📌 등록 = bot.py
- **본문 정제 (한 줄 + 컨텍스트 + category tag) = helper sub-agent batch** (helper 본체 = relay only 보존)
- 분배 결정 = nmae
- 실제 구현 = be / fe sub-agent

**왜 helper sub-agent**: helper 본체 = relay only ([[feedback-helper-relay-only]]) → 정제는 sub-agent 가 자연. nmae 가 정제하면 owner 룰 흐려짐 + nmae context ↑ ([[feedback-nmae-overload-signals]]).

**흐름**:

```text
1. 사용자 📌 tap → bot.py _handle_pin_reaction → directive_append.sh → forum thread (🟡 대기, raw template)
2. bot.py 가 ~/.mobruji/helper-queue.jsonl 에 polish task append:
   {"type":"directive_polish","directive_id":"...","raw_body":"...","ts":"...","status":"pending"}
3. helper 본체 다음 turn-start (helper-turn-start.sh) → queue scan → directive_polish pending count 가시화
4. 사용자 응답 우선 → 그 후 polish pending 1+ 시 helper sub-agent batch launch (Agent 도구)
5. helper sub-agent batch:
   - N건 directive 본문 정제 (한 줄 요약 + 컨텍스트 1-2 문장 + category 분류)
   - discord-reply.sh --forum-edit <thread_id> "<정제된 본문>"
   - discord-reply.sh --forum-retag <thread_id> directive "<category tag>"
   - stdout 보고 (처리 N건 / OK / fail)
6. helper 본체가 sub-agent 보고 받은 후 queue task status: pending → done atomic
7. nmae 가 다음 backlog-scan 호출 시 정제된 본문 + category tag 보고 분배 결정
```

**batch 효과**:
- 1 helper sub-agent launch 가 N task 처리 — launch overhead 분담
- N=1 도 정상 동작 (단일 launch, 흐름 일관)
- 한 turn 처리 한도 max 5 (5+ 이면 다음 turn 에 남은 것 처리)

**상세 룰**: `docs/ai-harness/actors/sub-agent.md §2-helper` 의 `directive 본문 정제 task` 섹션 SoT.

#### polished flag — race 가드 (사용자 정정 2026-05-28)

사용자 정정: "지금 처럼 아직 내용이 채워지기 이전에 엔마가 백로그 들여다보면 문제가 생기니까, 내용 있을 때만 물어가는 작업도 필요하겠다".

**문제**: 사용자 📌 tap → directive 등록 (raw 본문, polished=false) → nmae 가 backlog-scan 으로 들고 가 분배 시도 → raw 본문 보고 잘못된 cycle 위임 / 정제 미완 entry 와 race.

**해결**: directive entry 의 `polished` boolean field + nmae picker 가드.

- **directive entry schema 확장**: `directive_append.sh` 가 새 entry 등록 시 `polished: false` 박음.
- **helper sub-agent 정제 완료 의무**: `bash tools/directive-board/mark-polished.sh <id>` 호출 → `polished=true` + `last_updated_kst` 갱신.
- **nmae picker 가드**: `backlog-scan.sh` default filter = `select((.polished // false) == true)`. polished=false 무시. `--include-unpolished` 옵션으로 디버깅 / backfill 시 우회.
- **사용자 가시화**: directive thread 본문 template footer 에 `🤖 helper 정제 대기 (nmae 분배 보류 — race 가드)` line. polish 완료 후 helper sub-agent 가 `--forum-edit` 으로 line 제거.

**race 가드 흐름**:
```text
1. bot.py 📌 등록 → polished=false + raw template + "🤖 정제 대기" footer
2. helper-queue polish task append (pending)
3. helper 본체 turn-start → polish 1+ 시 helper sub-agent batch launch
4. helper sub-agent → 정제 + forum-edit + forum-retag + mark-polished.sh
5. mark-polished.sh → jsonl polished=true 박음 (race 가드 해제)
6. nmae backlog-scan → polished=true 만 list → 정제 완료된 entry 만 분배 결정
```

기존 entry (polished field 부재) 처리: `select((.polished // false) == true)` = polished field 없으면 false 취급 → 무시. backfill 필요 시 별도 script `backfill-polish-tasks.sh` (follow-up) 가 모든 기존 entry 의 helper-queue polish task 재등록.

#### PR E-1 — bot.py / mark-polished → nmae 자동 inject (2026-05-28)

**사용자 정정 (2026-05-28)**: helper 가 사용자 메시지 받아 직접 nmae 위임 결정 (관찰됨: 17:28 "추천 400 → be / 다크모드 → fe" — relay-only 룰 위반). nmae 가 backlog-scan 호출 안 함 = 학습 의존 사고.

**해결 (코드 강제)**: `mark-polished.sh` 가 polished=true 박은 직후 **nmae tmux pane 에 inject** — 학습 의존 ↓.

```bash
# mark-polished.sh 끝에 자동 호출:
tmux send-keys -t "${MOBRUJI_NMAE_PANE:-mobruji:0.0}" \
  "[directive] 새 polished directive ${ID} — backlog-scan + 분배 결정 의무. ..." \
  Enter
```

**효과**:
- helper sub-agent 정제 완료 → mark-polished.sh → polished=true + nmae inject 자동
- nmae 가 directive 인식 학습 의존 X — tmux pane 에 강제 신호
- helper 가 직접 위임하는 우회 path 와 무관 (PR E-2 가 helper 차단)

**graceful**:
- `MOBRUJI_NMAE_INJECT_ENABLED=0` → inject skip (테스트 / 로컬 dev 용)
- tmux 부재 (macOS local) → skip
- tmux session `mobruji` 부재 → skip
- nmae 가 thinking 중이면 inject 가 tmux input queue 에 쌓임 → 다음 turn 처리

#### plan 분석 모드 + 🟣 결정 대기 흐름 (사용자 결정 2026-05-28)

**사용자 정정**: "plan 에게 문제 분석을 맡긴 케이스이니까 그 포럼에 plan 이 생각하는 해결책까지만 제시하고 작업 진행하지는 않는게 좋겠다. A안 B안 C안이 있는데 그중 ~를 추천합니다. 내가 거기다 ~로 해라고 댓글달면 다시 지시 포럼에 추가".

**plan 의 2 모드**:

| 모드 | 출력 위치 | 조건 |
|---|---|---|
| **가벼운 분석** | directive thread 본문 PATCH (옵션 + 추천) | 단순 결정 분기 / 한 사이클 분석 가능. 모호 directive (분배 분기 d) 의 주 path. |
| **무거운 spec** | `docs/features/<slug>.md` + PR | 큰 기능 / 다중 PR / 검토 필요. 분배 분기 (a)(b)(c) 의 주 path. |

plan 이 directive 받으면 일단 가벼운 분석 시도 → spec 까지 필요하면 자체 escalate (사용자에게 thread 본문에 "추가 spec 필요" 명시).

**가벼운 분석 thread 본문 format**:

```text
📌 **{원본 directive title}**

💬 원본
> {원본 본문}

🆔 `{msg_id}` · 👤 <@{user}> · 🕐 {KST}

🧠 컨텍스트 (plan 분석)
{직전 사이클 / 작업 상황 / 문제 정의 1-2 문장}

📊 분석 — 해결 방법 후보
- **A안**: {제목} — {간단 설명, 장단점}
- **B안**: {제목} — {간단 설명, 장단점}
- **C안**: {제목} — {간단 설명, 장단점}

✅ **추천**: {A|B|C}안 — {추천 사유 (cost / 효과 / risk)}

📋 진행 (🟣 결정 대기)
- [x] 분석 / 위임 결정 — nmae
- [x] 옵션 발굴 + 추천 — plan
- [ ] **사용자 결정 대기** ← 이 thread 안에 댓글로 알려주세요 (예: "B로 해줘")

🔖 관련
- 후속 directive (자동 등록 예정)

---
_갱신: {KST} (plan 분석 1회)_
```

**규칙**:
- plan 은 **작업 진행 X** — 분석 + 추천만. 실제 구현은 사용자 결정 후 별도 sub-agent (be / fe) 위임.
- plan 분석 완료 시 status: 🔵 → **🟣 결정 대기**.

**사용자 댓글 → 새 directive 자동 등록 가드**:

bot.py 가 forum thread 안 댓글 detect 시 다음 모든 조건 충족 → 자동 `directive_append.sh` 호출:

1. thread 가 directive forum 채널 (`DIRECTIVE_BOARD_FORUM_ID`) 안
2. thread 의 현재 status tag = 🟣 **결정 대기**
3. 댓글 author = `allowed_user_ids` 안
4. 댓글 본문 길이 > 1자 (단순 reaction / 짧은 ack 제외)
5. **같은 thread + 사용자의 첫 댓글만** — 두 번째 댓글부터 일반 thread 댓글 처리 (등록 X)

가드 통과 시:
- 새 directive entry 생성 — `parent_directive_id: <원본 directive id>` 필드 박힘
- 원본 thread status: 🟣 → 🔵 진행 중 (또는 🟢 완료 — 사용자 결정에 따라 다음 단계)
- nmae 가 새 directive 의 동사+대상 명확성 보고 be/fe 직접 위임

**부모-자식 cascade**:

- 자식 directive 완료 (🟢) 시 → 부모 directive 도 자동 🟢 전이 (또는 사용자 명시 보존 결정 시 🔵 → 🟢 수동).
- 부모 thread 본문에 자식 PR URL + 결과 cross-link.

### 5-7) Forum tag setup (사용자 수동, 1회)

Discord `#모부르지-지시` (DIRECTIVE_BOARD_FORUM_ID `1507992370044600442`) 채널 설정 → Tags 9개 추가:
- 🟡 대기 / 🔵 진행 중 / 🟢 완료 / 🔴 폐기 / ⚪ 보류
- 🎯 결정 / 🛠️ 작업 / 🐛 사고 / 💡 spec

`discord-reply.sh --forum-post-auto-tag directive` 의 fallback chain 이 "대기" 가 첫 번째라 자동 부착. 사용자 tag 이름을 "🟡 대기" 로 바꿔도 매칭 가능 (다음 PR 에서 chain 정정).

자동 ensure (bot.py PATCH /channels/{forum_id} 호출) 는 별도 PR (선택).

## 6) rollback plan

- `directive_append.sh` 의 `build_template_body` 함수 제거 + default body 를 `$TITLE` 로 revert.
- 또는 4번째 arg `[body]` 명시 호출 시 그대로 사용 — graceful path 보존.

## 7) 작업 분할

- [x] PR 1 (본 PR): `directive_append.sh` template 함수 + spec (운영 모델 §5-6 포함). minimal — sh 1 파일 + spec.
- [ ] **PR A** (follow-up): nmae 룰 + backlog scan + `directive_status.sh in_progress` reason arg 확장 — `actors/nmae.md` update (백로그 owner / 분배 / plan 위임 사유 명시) + `tools/directive-board/backlog-scan.sh` 신규 + `directive_status.sh in_progress <id> <cycle> [reason]` 확장.
- [ ] **PR B** (follow-up): PR 머지 webhook 자동 `completed` 전이 — `.github/workflows/directive-complete-on-merge.yml` 신규 + sub-agent.md 룰 (`directive: <id>` PR body 명시 의무).
- [ ] **PR C** (follow-up): cycle-specific auto-inject — `directive-board.jsonl` 의 `assigned_cycle` 필드 + nmae watchdog (또는 bot.py `cycle_idle_watch_loop`) 가 cycle idle 시 그 cycle 의 🟡 directive 자동 inject.
- [ ] **PR D** (follow-up): helper 자동 정제 hook — 등록 직후 helper 가 다음 turn 에 thread 본문 PATCH (정제 한 줄 + 컨텍스트 + category tag).
- [ ] **PR E** (follow-up): plan 분석 모드 + 🟣 결정 대기 status + 사용자 댓글 자동 등록 가드 + parent/child cascade. spec §5-6 plan 분석 모드 + §5-2 status 6종 (🟣 추가) 구현. plan 룰 (`actors/sub-agent.md §2-plan` 또는 `actors/plan.md`) update + `directive_status.sh awaiting_decision` 신규 + `bot.py` thread comment handler (가드 5조건) + parent_directive_id 필드.
- [ ] PR 2-bot-user-id (follow-up): `bot.py _handle_pin_reaction` 에 `DIRECTIVE_USER_ID` env 전달. PR #1204 머지 후.
- [ ] PR 6-cross-link (follow-up): 4 사이클 cross-link.
- [ ] PR 7-auto-ensure (선택): forum available_tags bot 자동 ensure.

## 8) 테스트 전략

### 단위 (본 PR)
- shell smoke test — `build_template_body` 호출 → 본문에 📌 / 💬 / 🆔 / 📋 / 🔖 마커 + 진행 체크박스 3개 + footer 확인.
- user_id 부재 시 👤 line 생략 확인.

### 통합 (수동, NCP 배포 후)
1. `tools/discord-daemon/deploy.sh` 로 develop 반영.
2. 사용자가 `#모부르지` 채널에 임의 메시지 + 📌 tap.
3. `#모부르지-지시` forum 에 thread 신설 확인.
4. thread 본문이 template 형식 확인 (📌 title / 💬 원본 / 🆔 / 📋 진행 / 🔖 관련 / footer).
5. status tag 🟡 대기 부착 확인.

## 9) 결정 로그

- 2026-05-28 — 사용자 정정: "forum 에 작성할 때 어떤 상황인지 / 어떤 진행 상황인지 이해하기 쉽게 양식". **결정**: template body + tag 체계 도입.
- 2026-05-28 — **결정 1**: status 5 (대기 / 진행 / 완료 / 폐기 / 보류). 사유: release / sweep / 사고 다양한 종료 상태 cover, 3 개는 부족.
- 2026-05-28 — **결정 2**: category 4 (결정 / 작업 / 사고 / spec). 사유: mobruji 실제 directive 95% cover, 6+ 는 over-engineering.
- 2026-05-28 — **결정 3**: minimal template + helper 자동 정제 (점진 보강). 사유: 사용자 typing 0, [[feedback-autonomous-default]] 일치, helper LLM 책임.
- 2026-05-28 — **결정 4**: cycle cross-link / PR 머지 webhook 자동 전이는 follow-up PR. 사유: 본 PR 은 template MVP 만 — 한 번에 너무 큰 변경 회피.
- 2026-05-28 — **결정 5 (사용자)**: 백로그 운영 모델 명문화. nmae = 백로그 owner. 분배 분기 = plan (신규 / 초과 / 신중) / be / fe / rev / 즉시 반영. **plan 위임 시 사유 명시 의무** (사용자: "위임 사유: 큰 기능이라서 / 변경점이 ~라서").
- 2026-05-28 — **결정 6 (사용자)**: cycle-specific auto-inject. cycle 별 assigned directive 가 그 cycle idle 시 자동 처리 (사용자: "프론트가 놀고있다 그러면 알아서 가져가야지"). `assigned_cycle` 필드 + nmae watchdog 자동 inject.
- 2026-05-28 — **결정 7**: 자동화 우선순위 자율 (사용자: "둘다 구현되기만 하면 되니까 순서는 상관없어"). PR A/B/C/D 병행 가능.
- 2026-05-28 — **결정 8 (사용자)**: plan 위임 기준에 **(d) 모호 directive — 문제 정의 / 해결 선택지 발굴 자체가 필요한 경우** 추가. 사용자: "내가 뱉은 말이 ~를 해결해 인데 누가 담당할 작업인지 애매하고, 어떤 선택지들로 해결을 할 수 있나 고민해서 문제를 설정하고 고민하는 과정이 필요하다면 plan". 동사 × 대상 명확성 2x2 휴리스틱 명문화. 모호 directive 를 be/fe 직접 위임하면 sub-agent 가 결정 부담 → 정합성 위험.
- 2026-05-28 — **결정 9 (사용자)**: plan 분석 모드 분리. 사용자: "plan 에게 문제 분석을 맡긴 케이스이니까 그 포럼에 plan 이 생각하는 해결책까지만 제시하고 작업 진행하지는 않는게 좋겠다. A안 B안 C안이 있는데 그중 ~를 추천합니다. 내가 거기다 ~로 해라고 댓글달면 다시 지시 포럼에 추가". plan 의 2 모드 (가벼운 분석 vs 무거운 spec) 명문화. 가벼운 분석 = thread 본문 PATCH + 옵션 발굴 + 추천 + status 🟣 결정 대기.
- 2026-05-28 — **결정 10 (사용자)**: 🟣 결정 대기 status 신규 추가 (총 status 6종). plan 분석 완료 후 사용자 결정 대기 lock 역할. sidebar filter 가능.
- 2026-05-28 — **결정 11 (사용자, 옵션 2 채택)**: 사용자 댓글 → 새 directive 자동 등록. 가드 5조건 (directive forum / status=🟣 / allowed user / 본문 >1자 / 첫 댓글). status 🟣 lock 이 false-positive 방어 — 일반 thread 댓글은 영향 X.
- 2026-05-28 — **결정 12**: parent_directive_id 필드 + cascade — 자식 완료 시 부모 자동 🟢.

## 10) 자율 결정 (사유)

- 본 PR scope = `directive_append.sh` template 함수 + spec 만. 나머지 (status 전이 / helper 정제 / PR webhook / cycle cross-link) 모두 follow-up. 사유: scope 최소화 + 머지 후 1주일 사용 실측 후 follow-up 우선순위 결정 가능.

## 11) 사용자 확인 필요

- Discord 채널 `#모부르지-지시` 의 available_tags 9개 (status 5 + category 4) 설정 — 사용자 수동 1회.
- 본 PR 머지 후 forum 본문 가독성 / 정보 밀도 피드백 → follow-up PR 우선순위 (helper 정제 자동화 vs status 전이 등) 조정.

## 12) References

- [[directive-pushpin-registration]] — 본 spec 의 직전 PR. 등록 mechanism.
- [[discord-reaction-choice-input]] — reaction event-driven 일반 패턴.
- `tools/discord-daemon/directive_append.sh` — 본 PR 변경 대상.
- `tools/discord-daemon/directive_status.sh` — follow-up PR (status 전이) 대상.
- 메모리: [[feedback-autonomous-default]] / [[feedback-evidence-based-root-cause]].

## 13) 변경 이력

- 2026-05-28 — 초안 작성 + PR 1 (sh template + spec). status=approved (사용자 직접 정정 응답).
- 2026-05-28 — spec 보강 (PR 1 commit 추가): §5-6 백로그 운영 모델 (책임 분담 / 분배 분기 / plan 위임 사유 명시 / cycle-specific auto-inject / 완료 자동화) + §7 follow-up PR scope 4건 (A-D) 재정리 + §9 결정 5/6/7 추가. 사용자 정정 직접 반영.
