---
feature: 유휴 시 백로그 자동 시드 (idle 감지 → GitHub 백로그 → directive 큐 보충, nmae STRICT 정합)
slug: roadmap-queue-autoseed
status: draft
owner: @mobruji-maestro
scope: infra
related_issues: []
related_prs: []
last_reviewed: 2026-06-03
---

# 유휴 시 백로그 자동 시드 — idle 감지 → GitHub 백로그 → directive 큐 보충

## 1) 개요 (What / Why)

자율 사이클(be/fe/rev/plan 4 워크트리)은 `~/.mobruji/directive-board.jsonl` 의 🟡 대기 entry 를 nmae 가 분배·소비하며 흘러간다 ([[directive-board-template-and-tags]] §5-6 — nmae = 백로그 owner). 그러나 사용자 부재 자율 운행(예: 밤샘) 중 **directive 큐가 고갈**되면 두 가지 문제가 발생한다:

1. **큐 고갈 → 사이클 idle 정지**: 분배할 🟡 대기 entry 가 없으면 watchdog 가 relaunch 를 시도해도 nmae 가 띄울 작업이 없다. [[autonomous-cycle-orchestration]] 의 "4 사이클 항시 가동" 원칙이 깨진다.
2. **nmae STRICT 와의 충돌**: 큐가 비면 nmae 가 GitHub 백로그를 직접 뒤져 작업을 "발명"하게 되는데, 이는 nmae STRICT(**자율 launch 금지 — 사용자 의도(directive 큐) 가 뒷받침되지 않은 작업을 nmae 가 임의로 launch 하지 않는다**) 원칙과 정면 충돌한다. nmae 가 raw GitHub 백로그에서 임의 picking 하면 사용자 우선순위와 drift 한다.

본 spec 은 **bot/agent 측이 cycle idle 을 감지해 GitHub 백로그(우선순위·scope 라벨 기준)에서 다음 작업을 directive 큐(🟡 대기 entry)로 자동 보충(seed)하는 안전 메커니즘**을 설계한다. nmae 는 여전히 큐만 소비 — STRICT 정합 유지. 핵심은 "nmae 가 백로그를 뒤지는 것"이 아니라 "큐가 끊기지 않도록 외부 데몬이 큐를 채우고, nmae 는 채워진 큐를 평소처럼 분배"하는 역할 분리다.

**대상 액터**: bot.py(idle 감지 + seed loop), nmae(seeded directive 를 평소 큐와 동일하게 분배·소비), 오너(seeded entry 를 forum sidebar 에서 회고 — provenance 가시), 사용자(자율 운행 중 큐 고갈 없이 백로그 소진).

**비-목표**: nmae 가 직접 GitHub 백로그를 picking 하게 만드는 것(=STRICT 위반). 본 spec 은 정반대 — nmae 의 직접 picking 을 **불요**하게 만드는 큐 보충 자동화다.

## 2) 용어 / 약어

| 약어 | 의미 |
|---|---|
| **autoseed** | 본 메커니즘. GitHub 백로그 이슈 → directive 큐 🟡 대기 entry 변환 |
| **seeded entry** | autoseed 가 생성한 directive entry (`source: "autoseed"` + `seed_issue: <num>` provenance) |
| **directive 큐** | `~/.mobruji/directive-board.jsonl` 의 status=`대기`(🟡) entry 집합 — nmae 분배 대상 |
| **pending cap** | 큐에 적재 가능한 seeded 🟡 대기 entry 동시 상한 (과잉 시드 가드) |
| **scope→cycle 매핑** | GitHub `scope:*` 라벨 → 워크트리(be/fe/rev/plan) 배정 규칙 |
| **STRICT (자율 launch 금지)** | nmae 는 directive 큐가 뒷받침하지 않는 작업을 임의 launch 하지 않음 ([[directive-board-template-and-tags]] §5-6 nmae=consumer) |

## 3) 요구사항

### 기능 요구사항
- [ ] **idle 감지 → seed 트리거**: 한 워크트리 cycle 이 idle 이고( `in_progress=null` AND idle > N분 ) 그 cycle 에 assigned 된 🟡 대기 directive 가 0 일 때, GitHub 백로그에서 해당 cycle scope 매칭 이슈 1건을 큐에 seed.
- [ ] **GitHub 백로그 picking 기준**: 우선순위 라벨(§5-4 Q1) → 오래된 open 이슈(created_at asc, 백로그 소진 방향) → scope 라벨로 idle cycle 배정.
- [ ] **seeded entry 생성**: `directive_append.sh` 멱등 호출로 directive-board.jsonl 에 🟡 대기 entry 등록. `source: "autoseed"` + `seed_issue: <num>` + `polished: true` + `assigned_cycle: <cycle>` 박제.
- [ ] **provenance 가시화**: seeded entry forum thread 본문에 "🌱 백로그 자동 시드 (이슈 #N)" 표기 — 사용자 회고 시 사람이 등록한 directive 와 구분.
- [ ] **nmae 분배 흐름 재사용**: seeded entry 는 `polished=true` 로 박혀 nmae backlog-scan picker 가드를 통과 → nmae 가 평소 directive 와 동일하게 분배 ([[directive-board-template-and-tags]] §5-6 polished flag). seeded 라고 별도 분기 없음.

### 비기능 요구사항
- **STRICT 정합**: seed 는 bot/agent 측에서만 수행. nmae 는 큐 소비 역할 불변. seed loop 가 nmae tmux pane 을 직접 launch 하지 않음 — `mark-polished.sh` 의 기존 nmae inject 흐름([[directive-board-template-and-tags]] §5-6 PR E-1)만 재사용해 "새 directive 인식" 신호만 전달.
- **무한/과잉 시드 방지**: §5-3 의 pending cap(G1) + rate limit(G2) 로 큐 폭주 차단.
- **high-stakes 제외**: §5-3 G3 — release/secret/보호 영역 이슈는 자동 시드 대상에서 제외(사용자·nmae 수동만).
- **중복 방지**: §5-3 G4 — 이미 큐/PR 에 매핑됐거나 closed/assignee 보유 이슈는 skip. [[autonomous-cycle-orchestration]] §5-5 stale verification 재사용.
- **실패 격리**: §5-3 G5 — seed 1건 실패가 loop 전체를 멈추지 않음. graceful + 다음 iteration 재시도.
- **결정성**: 같은 백로그 상태에서 seed picking 순서 재현 가능(우선순위 → created_at asc → issue number tie-break).
- **관측성**: seed 발생 시 DIGEST 채널 1줄 push("🌱 autoseed: #N → <cycle> 큐 보충") + cycle-status note 반영.

## 4) 범위 / 비범위

### 포함
- idle 감지 조건 + seed 트리거 정의(bot.py loop 확장).
- GitHub 백로그 → directive 큐 변환 규칙(scope→cycle 매핑 / picking 우선순위 / provenance 필드).
- 5중 안전 가드(G1~G5) 정형화.
- seeded entry 의 directive-board.jsonl 스키마 확장(`source` / `seed_issue` 필드).
- nmae STRICT consume-only 모델과의 정합 명문화.

### 제외 (Out of Scope)
- **priority 라벨 체계 신설**: 현재 repo 에 `priority:*` 라벨 부재(2026-06-03 `gh label list` 확인). 우선순위 picking 의 라벨 source 는 §5-4 Q1 오픈 질문. 본 spec 은 라벨이 있으면 사용, 없으면 created_at fallback 으로 동작하게 설계만.
- **GitHub 이슈 자동 생성**: 본 메커니즘은 **기존 open 이슈**를 큐로 변환만. 신규 이슈 발굴/생성(예: rev audit follow-up 자동 등록)은 [[autonomous-cycle-orchestration]] §5 의 별도 흐름.
- **seeded entry 의 자동 구현(implementation)**: seed 는 큐 보충까지. 실제 분배·launch·PR 은 nmae + sub-agent 의 기존 흐름.
- **사용자 directive 와의 우선순위 경합 해소 알고리즘**: seeded entry 는 사람 등록 directive 보다 **항상 후순위**(§5-3 G1 cap 이 사람 directive 미포함 — seeded 만 카운트). 정교한 priority queue 는 follow-up.
- **stale seeded entry close 정책**: [[directive-board-stale-close-policy]] 가 cover(seeded entry 도 동일 4 패턴 분류 대상).
- **plan 분석 모드 자동 트리거**: 모호 이슈를 plan 으로 보내는 판단은 nmae 의 동사×대상 휴리스틱([[directive-board-template-and-tags]] §5-6). autoseed 는 scope 명확 이슈만 cycle 배정, 모호하면 `assigned_cycle: null` 로 두고 nmae 가 결정.

## 5) 설계

### 5-1) seeded directive entry 스키마 확장

기존 directive-board.jsonl entry 에 2 필드 추가(기존 entry 호환 — 필드 부재 = 사람 등록):

```jsonc
{
  "ts": "2026-06-03 03:14 KST",
  "summary": "추천 응답에 ageGroup 가중 회귀 테스트 보강",   // 이슈 제목 정제
  "status": "대기",                                          // 🟡 — seed 직후 default
  "owner": null,
  "related": "#1502",                                        // seed 원본 이슈
  "message_id": "autoseed-1502-1717...",                     // synthetic id (msg 없음)
  "thread_id": "<forum thread snowflake>",
  "assigned_cycle": "be",                                    // scope→cycle 매핑 (모호 시 null)
  "polished": true,                                          // race 가드 통과 (정제 불요 — 이슈 제목이 이미 명확)
  "source": "autoseed",                                      // ← 신규: provenance
  "seed_issue": 1502,                                        // ← 신규: 중복 가드 key
  "last_updated_kst": "2026-06-03 03:14 KST"
}
```

**필드 룰**:
- `source: "autoseed"` — 사람 등록 entry 와 구분. 부재 = 사람 등록(기존 호환).
- `seed_issue: <int>` — 중복 가드(G4) 의 dedup key. 같은 이슈 재시드 방지.
- `message_id` — Discord 메시지가 없으므로 `autoseed-<issue>-<epoch>` synthetic id. `directive_append.sh` 멱등성 grep 가드와 호환.
- `polished: true` — seed 시점 박제. 이슈 제목/본문이 이미 사람이 쓴 명확 텍스트라 helper 정제 불요. nmae backlog-scan picker 가 즉시 분배 가능.

### 5-2) scope → cycle 매핑

GitHub `scope:*` 라벨 → 워크트리 배정:

| scope 라벨 | assigned_cycle | 비고 |
|---|---|---|
| `scope:web` | `fe` | frontend |
| `scope:recommendation` `scope:song` `scope:user` `scope:voice` `scope:feedback` | `be` | backend 도메인 |
| `scope:infra` | `null` (nmae 결정) | infra 는 be/plan 모호 — nmae 동사×대상 휴리스틱 |
| 라벨 복수 / scope 라벨 없음 | `null` (nmae 결정) | 모호 — nmae 가 분배 |

- `assigned_cycle != null` → idle 그 cycle 에 우선 inject([[directive-board-template-and-tags]] §5-6 cycle-specific auto-inject).
- `assigned_cycle == null` → 일반 🟡 대기 큐로만 등록. nmae 가 backlog-scan 후 휴리스틱으로 분배(모호 시 plan).
- **type:* 라벨 보조**: `type:docs` 단독 이슈는 `plan`, `type:test`/`type:refactor` 는 scope 기준 유지.

### 5-3) 5중 안전 가드 (핵심)

| # | 가드 | 규칙 | env |
|---|---|---|---|
| **G1** | **동시 상한 (pending cap)** | directive-board 안 `source=autoseed` AND `status=대기`(🟡) entry 수 ≤ cap. cap 도달 시 seed skip. **사람 등록 directive 는 카운트 제외** — seeded 만 제한. | `AUTOSEED_MAX_PENDING=4` (4 워크트리 × 1) |
| **G2** | **rate limit** | loop iteration 당 max `AUTOSEED_BATCH` 건 seed. + cycle 당 seeded 🟡 대기 ≤ 1 (idle 한 워크트리에 1건만 보충). | `AUTOSEED_BATCH=1`, loop 주기 `AUTOSEED_INTERVAL=600`s |
| **G3** | **high-stakes 제외** | 다음 이슈는 자동 시드 **제외**(사람/nmae 수동만): ① `type:release` 라벨 ② 보호 영역 키워드(CLAUDE.md §4: migration / `.env` / secret / build.gradle / Dockerfile / docker-compose / workflow / LICENSE) 가 제목·본문 매칭 ③ security 라벨 또는 🔴/secret/credential 키워드 ④ 본문 "BREAKING" / "마이그레이션" / "운영 DB" 매칭. | `AUTOSEED_EXCLUDE_LABELS`, `AUTOSEED_EXCLUDE_KEYWORDS` |
| **G4** | **중복 방지** | seed 후보 이슈 #N 에 대해 다음 중 하나라도 참이면 skip: ① directive-board 에 `seed_issue=N` 이미 존재 ② `gh pr list --search "<N> in:body" --state all` 매핑 PR 존재 ③ 이슈 state=closed ④ assignee 보유(사람 작업 중) ⑤ 직전 시드 후 다시 닫혔다 재오픈된 이슈는 cooldown. [[autonomous-cycle-orchestration]] §5-5 stale verification 명령 재사용. | `AUTOSEED_COOLDOWN_HOURS=24` |
| **G5** | **실패 격리** | seed 1건 실패(gh API timeout / jsonl write race / forum-post 실패) → 그 iteration 만 stderr warning + skip. loop 전체 crash 방지. `directive_append.sh` 멱등성으로 부분 성공 시 재호출 안전. 연속 실패 N회 시 DIGEST 1줄 alert. | `AUTOSEED_FAIL_ALERT_THRESHOLD=3` |

**G1 cap 이 사람 directive 미포함인 이유**: 사람이 등록한 directive 가 많아도 백로그 시드는 별개로 워크트리당 1건씩만 보충 — 사람 우선순위를 밀어내지 않고, 사람 directive 가 비었을 때만 백로그가 idle cycle 을 채운다. seeded 가 큐를 점령하는 것을 방지.

### 5-4) seed loop 시퀀스 (bot.py 확장)

idle 감지는 기존 `cycle_idle_watch_loop`([[nmae-cycle-watchdog]] §5-7 layer 1)과 같은 데몬에 **백로그 보충 책임**을 추가하거나, 별도 `backlog_autoseed_loop` 로 분리(§7 Q3). 시퀀스:

```text
backlog_autoseed_loop (AUTOSEED_INTERVAL 주기)
  │
  ├─ cycle-status.json read → 각 워크트리 idle 판정
  │     (in_progress=null AND idle_since > N분 — watchdog 와 동일 임계)
  │
  ├─ for each idle cycle C:
  │     ├─ directive-board.jsonl 에 assigned_cycle=C AND status=대기 entry 존재? → skip (이미 큐 있음)
  │     ├─ G1 pending cap 도달? → skip
  │     ├─ G2 이번 iteration batch 한도 초과? → break
  │     │
  │     ├─ GitHub 백로그 picking:
  │     │     gh issue list --label "scope:<C매핑>" --state open
  │     │       --json number,title,labels,createdAt,assignees,body
  │     │     → G3 high-stakes 제외 filter
  │     │     → G4 중복/매핑/closed/assignee 제외 filter
  │     │     → 우선순위 정렬 (priority 라벨 → createdAt asc → number asc)
  │     │     → 1건 선정 (없으면 해당 cycle skip)
  │     │
  │     ├─ seed 실행 (G5 try/except 격리):
  │     │     directive_append.sh <synthetic_msg_id> "<정제 제목>" "" "<template body>" \
  │     │       --source autoseed --seed-issue <N> --assigned-cycle C --polished
  │     │     → forum thread 신설 (🟡 대기 + 🌱 provenance line)
  │     │     → jsonl entry (source/seed_issue/polished=true)
  │     │
  │     └─ mark-polished.sh <id>  (기존 흐름 — nmae tmux inject "새 directive 분배 의무")
  │
  └─ DIGEST push (seed N건 발생 시 1줄: "🌱 autoseed: #N → <cycle>")
```

**STRICT 정합 포인트**: loop 는 nmae 에게 "**무엇을 launch 하라**"고 지시하지 않는다. 단지 큐(🟡 대기 entry)를 채우고, 기존 `mark-polished.sh` 의 inject 흐름으로 "새 directive 가 있다"는 신호만 보낸다. nmae 는 평소처럼 backlog-scan → 동사×대상 휴리스틱 → 분배. **picking 권한은 여전히 nmae** — loop 는 큐 보충만.

### 5-5) provenance template (seeded entry forum 본문)

[[directive-board-template-and-tags]] §5-2 template 에 시드 표기 추가:

```text
🌱 **{이슈 제목 정제}** (백로그 자동 시드)

💬 원본
> GitHub 이슈 #{N}: {제목}
> {본문 첫 200자}

🆔 `autoseed-{N}-{epoch}` · 🤖 autoseed · 🕐 {KST}

📋 진행 (🟡 대기)
- [ ] 분석 / 위임 결정 (nmae)
- [ ] 실행
- [ ] 결과 반영

🔖 관련
- 이슈: #{N}
- 출처: 유휴 백로그 자동 시드 (roadmap-queue-autoseed)

---
_갱신: {KST} · 🌱 autoseed (사람 등록 directive 와 구분)_
```

### 5-6) cycle-status note 반영

seed 발생 시 그 cycle 의 idle note 를 업데이트해 watchdog STRICT relaunch 와 정합([[nmae-cycle-watchdog]] §5-8):

```bash
bash tools/cycle-status/update.sh <cycle> set-idle \
  --note "autoseed: #<N> 큐 보충 — nmae 분배 대기"
```

→ watchdog 가 idle 을 STRICT relaunch 가 아닌 "보충된 큐 대기" 로 인식. nmae inject 와 watchdog inject 가 중복 발사하지 않도록 note 가 신호 역할.

### 5-7) API / DB / FE

해당 없음. 운영 인프라(bot.py loop + jsonl + GitHub gh CLI) 만. backend/frontend 코드 변경 없음.

## 6) 작업 분할 (예상 PR 리스트)

본 spec PR 은 docs only. impl 은 후속(infra 사이클):

- [ ] **본 SPEC PR (plan, 현재)**: `docs/features/roadmap-queue-autoseed.md` + `docs/features/README.md` §9 인덱스 sync.
- [ ] **impl PR 1 (be/infra)**: `tools/directive-board/autoseed.sh` (또는 bot.py `backlog_autoseed_loop`) 신설 — idle 감지 + 백로그 picking + G1~G5 가드 + `directive_append.sh` 호출. pytest/bats 가드 단위 테스트.
- [ ] **impl PR 2 (be/infra)**: `directive_append.sh` 의 `--source` / `--seed-issue` / `--assigned-cycle` / `--polished` 인자 확장 + seeded template body(§5-5).
- [ ] **impl PR 3 (be/infra)**: `backlog-scan.sh` 가 `source=autoseed` 구분 표시(운영 가시성) + DIGEST seed 알림 1줄.
- [ ] **(사용자 결정 후, 선택)** priority 라벨 체계 신설 PR — §7 Q1 결정 시.

### 보호 영역 변경 여부 (필수 명시)

- 보호 영역 변경 여부: ☒ 없음 — 본 SPEC PR 은 `docs/features/**` + `README.md` 인덱스만. impl PR 은 `tools/**` + `bot.py`(보호 영역 아님) 대상이며, **autoseed 메커니즘 자체가 보호 영역 이슈(workflow/migration/.env/build/Dockerfile)를 시드 대상에서 제외(G3)** 하는 것이 본 spec 의 안전 핵심.

## 7) 테스트 전략

### 단위 (impl PR)
- **G1 cap**: seeded 🟡 대기 N=cap 상태에서 추가 seed 호출 → skip 확인. 사람 directive N건 추가해도 cap 무영향 확인.
- **G2 rate limit**: 1 iteration 에 batch 초과 seed 안 함 확인. cycle 당 seeded 🟡 ≤ 1 확인.
- **G3 high-stakes 제외**: `type:release` / `.env` / secret 키워드 이슈 → seed 후보에서 제외 확인.
- **G4 중복**: 같은 `seed_issue` 재시드 skip / 매핑 PR 존재 시 skip / closed/assignee skip 확인.
- **G5 실패 격리**: `directive_append.sh` mock 실패 주입 → loop 다음 cycle 정상 진행 확인. 연속 실패 N회 시 alert 확인.
- **scope→cycle 매핑**: 각 scope 라벨 → 올바른 assigned_cycle / 모호 시 null 확인.

### 통합 (NCP 배포 후 수동)
1. 모든 cycle idle + directive 큐 비운 상태 재현.
2. loop 1 주기 대기 → directive-board.jsonl 에 seeded entry(source=autoseed) 생성 확인.
3. forum `#모부르지-지시` 에 🌱 provenance thread 신설 확인.
4. nmae tmux pane 에 inject 도달 → nmae backlog-scan → 분배 확인(STRICT 정합 — nmae 가 GitHub 직접 picking 안 함).
5. DIGEST 채널 "🌱 autoseed" 1줄 push 확인.

### 회귀
- 사람 등록 directive 가 있는 상태에서 seed 가 그것을 밀어내지 않는지(G1 cap seeded-only) 확인.
- 보호 영역 이슈가 절대 자동 시드되지 않는지(G3) 24시간 운영 후 0건 확인.
- 같은 이슈 중복 시드 0건(G4) 확인.

## 8) 오픈 질문

| # | 질문 | 선택지 | 담당/기한 |
|---|---|---|---|
| Q1 | 우선순위 picking 의 source. 현재 `priority:*` 라벨 부재(2026-06-03 확인). | (a) `priority:high/med/low` 라벨 신설 / (b) GitHub milestone 기준 / (c) 라벨 없이 createdAt asc(백로그 소진) fallback 만 | @mobruji-maestro / impl PR 1 전 |
| Q2 | seeded entry 의 사람 검토 필요 여부 — 자율 default vs 일정 검토. | (a) 완전 자율(시드 즉시 분배) / (b) seeded 는 🟡 대기 유지 + 사용자 forum 확인 후에만 nmae 분배 / (c) high-stakes(G3 경계) 만 검토 | @mobruji-maestro / 운영 1주 후 |
| Q3 | seed loop 위치. | (a) 기존 `cycle_idle_watch_loop` 에 책임 추가 / (b) 별도 `backlog_autoseed_loop` 분리(관심사 분리) / (c) `tools/` shell + cron | @mobruji-maestro / impl PR 1 |
| Q4 | `assigned_cycle: null`(모호 scope) seed 를 자동 등록할지, nmae 가 분배할 수 있는 모호 entry 라 큐 노이즈가 될지. | (a) 모호도 seed(nmae 가 plan 분배) / (b) scope 명확 이슈만 seed / (c) 모호는 별도 cap | @mobruji-maestro / 운영 1주 후 |
| Q5 | cap(G1) 4건이 적정한가 — 워크트리 4 × 1. 사이클 처리 속도 대비 큐 깊이. | (a) 4 고정 / (b) cycle 당 동적 / (c) env 튜닝 후 결정 | @mobruji-maestro / 운영 1주 후 |

## 9) 결정 로그

- 2026-06-03: 초안 작성 (status=draft). 자율 운행 중 directive 큐 고갈 → 사이클 idle 정지 + nmae STRICT 충돌 문제 식별. **핵심 결정**: bot/agent 측 idle 감지 → GitHub 백로그 → directive 큐 보충(seed) 모델 채택. nmae 는 큐 소비 역할 불변 — STRICT(자율 launch 금지) 정합 유지. seed 는 nmae 에게 작업을 지시하지 않고 큐만 채워 picking 권한을 nmae 에 보존.
- 2026-06-03: 5중 안전 가드(G1 pending cap / G2 rate limit / G3 high-stakes 제외 / G4 중복 방지 / G5 실패 격리) 정형화. G1 cap 이 seeded-only 카운트인 이유 — 사람 directive 우선순위를 밀어내지 않기 위함.
- 2026-06-03: priority 라벨 부재 확인(`gh label list`) → 우선순위 source 를 §8 Q1 오픈으로 남기고, 라벨 부재 시 createdAt asc fallback 으로 동작하게 설계.
- 2026-06-03: seeded entry 의 자동 구현·신규 이슈 발굴·stale close 는 OOS — 각각 nmae+sub-agent 기존 흐름 / [[autonomous-cycle-orchestration]] / [[directive-board-stale-close-policy]] cover.

## 10) References

- [[autonomous-cycle-orchestration]] §5-5 — stale verification 명령(G4 재사용) + 4 사이클 항시 가동 원칙.
- [[directive-board-template-and-tags]] §5-6 — nmae=백로그 owner / consume-only / polished flag / cycle-specific auto-inject / mark-polished nmae inject(PR E-1). 본 spec 의 STRICT 정합 SoT.
- [[directive-board-event-driven-redesign]] — directive_append.sh / directive_status.sh atomic 호출 + jsonl=SoT.
- [[directive-board-stale-close-policy]] — seeded entry 의 stale close(seeded 도 4 패턴 분류 대상).
- [[nmae-cycle-watchdog]] §5-7 (5중 안전망) / §5-8 (STRICT mode + note) — idle 감지 임계 공유 + note 정합.
- `tools/directive-board/backlog-scan.sh` / `mark-polished.sh` — 재사용 도구.
- 메모리: [[feedback-autonomous-default]] / [[feedback-evidence-based-root-cause]] / [[feedback-keep-4-cycles-active]].
