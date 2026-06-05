---
id: 0030
title: claude-code-orchestrator 학습점 3건 도입 평가 — 리서치 자산화 채택 · 패턴 승격 부분 채택 · hook 능동 제안 보류
status: accepted
date: 2026-06-05
deciders: [plan sub-agent (directive 1857)]
related_issues: [1857]
related_directives: [1857]
supersedes: []
superseded_by: []
---

# 0030. claude-code-orchestrator 학습점 3건 도입 평가

## Context

외부 글 *claude-code-orchestrator* (blog.naver.com/beyond-zero/224167178336) 분석에서 "우리가 안 하는데 검토 가치가 있는" 항목 3건이 도출되었다(이슈 #1857). 본 ADR 은 이 3건을 mobruji 자율 사이클(nmae 오케스트레이션 + be/fe/rev/plan sub-agent + helper + bot.py watchdog)에 **도입했을 때의 영향**을 항목별로 평가하고 도입/부분/보류 결론과 도입 순서를 확정한다.

분석 대상 3건:

1. **리서치 결과 자산화** — 외부/내부 리서치를 `docs/research/<topic>.md` 고정 경로에 누적해 plan·다른 사이클이 재참조.
2. **패턴 → 스킬/wrapper 승격 의례화** — 반복 사이클 패턴(BE+FE 병행, rev findings → 후속 이슈 등)을 주기적으로 분석해 스킬/wrapper/hook 으로 승격하는 명시적 메타 루프(현재는 사고 발생 후 수동 판단).
3. **Hook 기반 능동 제안** — 사용자 입력/변경 규모 키워드 감지 시 plan 사이클·추가 rev 를 자동 권유(현재 wrapper 는 강제만, 능동 제안 약함).

### 평가의 1차 입력 (제약 조건)

- **C1 무인 자율 default** ([[feedback-autonomous-default]]): 밤샘 운행 중 사용자가 부재하다. "사람에게 권유" 형태의 산출물은 수신자가 없어 무의미하다. 가치는 **자동 강제** 또는 **사후 재참조 가능한 산출물** 에서만 나온다.
- **C2 호스트 리소스 상한** (ADR-0015 / ADR-0027 제약1): maestro 호스트 = NCP c2-g3a(2vCPU/4GB). maestro 1 + sub-agent 4 + bot.py + Discord daemon 이 상주해 헤드룸이 빠듯하다. 상시 추가 루프/세션은 thrash risk.
- **C3 강제는 prose 가 아니라 코드** (CLAUDE.md §17 / `13-memory-and-enforcement.md`): 학습 의존 룰은 반복 위반된다. 룰은 hook/wrapper/test/workflow 로 옮겨야 강제된다.
- **C4 안티패턴 — self-churn 금지** (CLAUDE.md §9): 요청 안 한 리팩터링/스킬 양산 금지. 능동 메타 루프는 가치 없는 self-churn 을 유발할 risk 가 있다.

---

## 항목 1 — 리서치 결과 자산화

### (a) 우리 사이클에 생기는 변화

- 현재 `docs/research/` 디렉토리는 **존재하지만** 컨벤션이 없다. 파일이 1개(`external-service-inspirations.md`)뿐이고, `docs/ai-harness/**` 어디에서도 `docs/research` 를 언급/라우팅하지 않는다(grep 결과 0건). 즉 ad-hoc 산출물 1개가 있을 뿐 "자산"이 아니다.
- 도입 시: `docs/research/<topic>.md` 고정 네이밍 + README index + 프론트매터(트리거 이슈·관련 문서·검증 상태) 컨벤션이 생긴다. plan 사이클이 외부 서비스 분석·기술 조사·경쟁 분석을 한 곳에 누적하고, 다른 사이클(be/fe)이 구현 전 재참조한다.

### (b) 기존 메커니즘과의 중복·충돌

- **ADR(docs/decisions)·Feature Spec(docs/features)** 과 경계가 깨끗하다. `decisions/README.md` 가 이미 3분할을 정의한다: ADR = "결정(불변)", Spec = "하나의 기능", "단발 메모/리서치 → 이슈/PR 코멘트". 리서치 자산화는 이 중 **빈 칸**(검증 전 탐색·조사 산출물의 영속 보관처)을 메운다. 중복 아님.
- **메모리(`13-memory-and-enforcement.md`)** 와도 다르다. 메모리 = 룰의 "왜"(사고·정정 인용), research = 탐색/조사 산출. 충돌 없음.
- 약한 중복: `external-service-inspirations.md` 자체가 이미 research 성격이므로, 컨벤션 도입 시 이 파일이 첫 시민이 되어 자연 흡수된다.

### (c) 비용·리스크 (무인 자율 default 상충 포함)

- **비용: 낮음.** 디렉토리는 이미 존재. 컨벤션 doc 1개 + README index + `docs/ai-harness/00-index.md` 포인터 1줄이면 끝. 코드 변경 0.
- **리스크: stale 누적.** `external-service-inspirations.md` 머리말이 스스로 "모델 메모리 기반 추정 — 재검증 필요"라고 경고한다. 검증 상태 메타가 없으면 추정이 사실처럼 재참조될 risk. → 프론트매터에 `검증상태: 추정|부분검증|검증완료` 필드를 의무화해 완화.
- **C1(무인 자율) 상충: 없음.** research 는 사후 재참조 산출물이라 수신자가 사람이든 다음 사이클 sub-agent 든 무관하게 가치가 보존된다. 무인 운행과 정합.

### (d) 권고 — **도입 (경량)**

근거: 비용 최저, 기존 3분할(ADR/Spec/이슈)과 충돌 없이 빈 칸을 메움, C1 과 정합. 단 stale 방지 위해 `검증상태` 프론트매터 필드를 컨벤션에 못 박는다.

---

## 항목 2 — 패턴 → 스킬/wrapper 승격 의례화

### (a) 우리 사이클에 생기는 변화

- 현재 우리는 승격 메커니즘 자체는 보유한다 — `13-memory-and-enforcement.md` 가 "메모리 룰 → 코드 강제(hook/wrapper/test/workflow)" promote 원칙과 트래킹 표를 정의한다. 그러나 이는 **사고 발생 후 사후 박제(reactive)** 다. CLAUDE.md §17 의 "메커니즘 단 우선순위"도 *반복 사고가 두 번째일 때* 발동한다.
- 도입 시: 사고를 기다리지 않고 **주기적으로 반복 사이클 패턴을 능동 스캔**(예: BE+FE 병행, rev findings → 후속 이슈 양산 패턴)해 스킬/wrapper/hook 후보를 도출하는 명시적 메타 루프가 생긴다. promote 트래킹이 reactive → proactive 로 전환된다.

### (b) 기존 메커니즘과의 중복·충돌

- **`13-memory-and-enforcement.md` 와 강하게 중복.** promote 트래킹 표(§1-2)가 이미 룰↔코드 강제 매핑을 관리한다. 새 의례를 별 메커니즘으로 세우면 SoT 가 둘로 갈린다.
- **CLAUDE.md §15 세션 종료 doc-check 와 부분 중복.** 매 helper/nmae 세션 종료마다 도는 4-way(메모리/CLAUDE.md/docs/ai-harness/docs/features) 일치 체크가 이미 메타 루프다. 여기에 "반복 패턴 → 승격 후보" 5번째 갈래를 붙이면 별도 루프 없이 능동화 가능.
- **충돌: 신규 상시 루프 = C2·C4 위반.** 또 하나의 cron/세션을 상시 가동하면 2vCPU/4GB(C2)에서 경합하고, 능동 의례가 "요청 안 한 스킬 양산"(C4 self-churn)을 유발할 risk.

### (c) 비용·리스크 (무인 자율 default 상충 포함)

- **비용: 중.** 새 상시 루프 추가 시 nmae 오케스트레이션 복잡도 + 리소스. doc-check piggyback 방식이면 비용은 낮음(체크 항목 1개 추가).
- **리스크: over-promotion.** 1~2회 등장한 패턴을 성급히 스킬/wrapper 로 승격하면 유지보수 부담만 늘고 사용처가 없는 죽은 추상이 된다(§9 안티패턴, "Three similar lines is better than a premature abstraction").
- **C1(무인 자율) 상충: 경미.** 메타 루프 산출물이 "후보 backlog"(사후 재참조)면 정합. 단 자동으로 스킬을 *생성*까지 하면 무인 중 검증 없는 자동 추상화 = risk.

### (d) 권고 — **부분 도입 (신규 루프 X, doc-check piggyback)**

근거: 메커니즘은 `13-memory-and-enforcement.md` 에 이미 있다. 부족한 것은 *능동성*뿐이다. 새 상시 루프(C2/C4 위반)를 세우지 말고, **이미 매 세션 종료마다 도는 §15 doc-check 에 "최근 3개 사이클 반복 패턴 → 승격 후보 1건 식별" 갈래를 추가**하고, 식별된 후보는 `13-memory-and-enforcement.md` 의 promote 후보 backlog 섹션에 누적한다. 승격 실행(스킬/wrapper 작성)은 후보가 **2회 이상 재등장**할 때만(over-promotion 방지). 자동 생성은 하지 않는다.

---

## 항목 3 — Hook 기반 능동 제안

### (a) 우리 사이클에 생기는 변화

- 현재 hook 은 존재한다 — `.claude/settings.json` 에 `PreToolUse → helper-tool-progress.sh`(진행 표시), `PostToolUse(Bash) → pr-register-rev.sh`(PR 자동 rev 등록) 2건. 그러나 모두 **진행/강제(enforcement)** 용이고, "능동 제안"은 없다. `agent-launch-wrapper.sh` 도 강제(set-active + launch prompt)만 한다.
- "중간 규모 이상 = Feature Spec 먼저"(CLAUDE.md §4) 룰은 현재 **학습 의존**이다.
- 도입 시: hook 이 입력/diff 규모 키워드를 감지하면 plan 사이클 신설 또는 추가 rev 를 **권유하는 메시지를 emit** 한다. 학습 의존이 약간 줄어든다.

### (b) 기존 메커니즘과의 중복·충돌

- **`rev-gate.yml`(라벨 머지 게이트)·`rev-queue.sh`(매 사이클 큐 discovery)·`agent-launch-wrapper.sh` 와는 보완적**이나, 이들은 모두 *강제*다. "권유"는 강제가 아니라 **C3("강제는 prose 가 아니라 코드") 철학과 정면으로 약하다** — 권유는 또 하나의 학습 의존 prose 일 뿐이다.
- **C1(무인 자율)과 직접 충돌.** 무인 운행 중 "사람에게 권유"는 수신자가 없다. 만약 권유 대상을 nmae/plan 자동 trigger 로 만들면 그것은 더 이상 "권유"가 아니라 **강제 자동화**다 — 원래 학습점의 형태(능동 *제안*)가 우리 운영 모델에서 성립하지 않는다.

### (c) 비용·리스크 (무인 자율 default 상충 포함)

- **비용: 중~고.** 키워드/diff 규모 감지 로직의 정밀도 확보가 어렵다. false positive → 불필요한 plan 사이클·rev 남발 → C2 리소스 낭비 + C4 self-churn.
- **리스크: 수신자 모호.** 무인 환경에서 "제안"을 받아 판단할 주체가 없다. 사람용이면 무인 시 무용, 자동 trigger 면 false-positive 폭주 위험.

### (d) 권고 — **보류 (능동 제안 형태) / 부분 (강제 자동화 형태로 재해석 시)**

근거: "능동 제안"이라는 형태 자체가 C1·C3 과 충돌한다 — 보류한다. 우리 모델에 맞는 변형은 *제안*이 아니라 *강제*다. 즉 향후 별도 이슈로, **diff 규모/보호 영역 임계 초과 시 PR 에 `rev:신중도가중` 류 라벨을 자동 부착하는 PostToolUse/CI hook**(권유가 아닌 강제 분류) 형태로만 선별 재검토한다. 능동 권유 메시지 emit 은 도입하지 않는다.

---

## 최종 종합 의견

| 항목 | 결론 | 핵심 사유 |
|---|---|---|
| 1. 리서치 자산화 | **도입(경량)** | 비용 최저 · 기존 3분할과 무충돌 · 무인 자율과 정합 |
| 2. 패턴 승격 의례화 | **부분(신규 루프 X)** | 메커니즘은 이미 있음(13). 능동성만 §15 doc-check 에 piggyback |
| 3. hook 능동 제안 | **보류(능동) / 부분(강제화 시)** | "제안" 형태가 C1 무인 자율·C3 강제 철학과 충돌 |

공통 관통선: 우리 운영 모델에서 가치는 **(a) 사후 재참조 산출물** 또는 **(b) 코드 강제** 에서 나온다. "사람에게 능동 제안"은 무인 자율 default(C1) 때문에 약하다. 따라서 3건 중 산출물형(1)은 그대로, 강제형으로 재해석 가능한 것(2 일부, 3 일부)은 변형 채택, 순수 제안형(3 본형)은 보류한다.

### 도입 순서 (우선순위)

1. **[즉시] 항목 1 — 리서치 자산화.** 비용 최저·충돌 없음·즉시 가치. 별도 후속 이슈로 `docs/research/` 컨벤션 doc + README index + `검증상태` 프론트매터 필드 + `00-index.md` 포인터를 plan 사이클이 작성. (본 ADR 의 직접 산출 아님 — 후속 plan 작업)
2. **[다음] 항목 2 — 패턴 승격 piggyback.** 항목 1 이후, `13-memory-and-enforcement.md` 에 promote 후보 backlog 섹션 + CLAUDE.md §15 doc-check 에 "반복 패턴 → 승격 후보" 갈래 1개 추가. 신규 루프 없음.
3. **[조건부·후순위] 항목 3 — 강제화 변형만.** 1·2 정착 후, *능동 제안*은 영구 보류하고 *강제 분류 hook*(라벨 자동 부착)만 별도 이슈로 비용 대비 효과 재평가. 효과 불명확 시 미도입.

## Consequences

- 본 ADR 은 **평가·결정만** 담는다. 항목 1·2 의 실제 컨벤션/문서 변경은 후속 plan 이슈로 분리한다(한 PR 비대화 방지).
- 항목 3 능동 제안은 영구 보류로 기록한다 — 재논의 시 본 ADR 의 C1/C3 충돌 근거를 먼저 반박해야 한다.
- 관련: [[feedback-autonomous-default]] · `docs/ai-harness/13-memory-and-enforcement.md` · ADR-0027(온디맨드 vs 상시 리소스 trade-off 선례) · `docs/decisions/README.md`(ADR/Spec/리서치 3분할).
