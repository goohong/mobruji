---
feature: validate-workflow-related-prs-exception
slug: validate-workflow-related-prs-exception
status: draft
owner: @goohong
scope: infra
related_issues: []
related_prs: []
last_reviewed: 2026-05-26
---

# Feature Spec Validate Workflow — 신규 spec frontmatter / related_prs 가드 보강

## 1) 개요 (What / Why)

`.github/workflows/spec-status-check.yml` (job 이름 `validate`) 은 `docs/features/**.md` 변경 PR 에서
frontmatter `status` enum / `related_prs` 형식 / `related_issues` 형식을 검증한다.

**배경 — rev round 4 (2026-05-26) 박제**:
- PR #1135 (`agent-role-enforcement.md`) — `validate` fail: `missing frontmatter (--- block)`
- PR #1139 (`helper-writing-marker-timing-fix.md`) — `validate` fail: `missing frontmatter (--- block)`
- PR #1140 (`directive-board-event-driven-redesign.md`) — `validate` fail: `missing frontmatter (--- block)`

본 사이클 task 본문은 "신규 spec PR 의 `related_prs` 자기 PR 번호 등록 불가능 → chicken-and-egg false fail"
로 진단했으나 실제 `gh run view --log-failed` 분석 결과 fail 원인은 **frontmatter `--- ... ---` 블록 누락**.
현 validator 는 `related_prs` 누락 자체는 hard-fail 하지 않으며 (line 127-141, 형식만 검증), 따라서
"chicken-and-egg" 시나리오는 **현재 시점에 실제로 발생하지 않는다**. 진단을 재정의하여 root cause 정정한다.

**Root cause (2 layer)**:
- **Layer 1 (template adherence)**: plan sub-agent 가 신규 spec 작성 시 `_template.md` 의 frontmatter
  `--- ... ---` 블록을 빠뜨리고 `# Title` 부터 시작하는 패턴이 3 PR 모두에서 반복. 학습 의존이 약함.
- **Layer 2 (validator UX)**: fail 메시지가 `"<basename>: missing frontmatter (--- block)"` 만 출력. 작성자가
  어떻게 정정해야 하는지 안내 부재. `_template.md` 링크 / 자동 fix snippet 없음.

**가설적 chicken-and-egg (방어적 가드)**: README.md §10 가 향후 "spec 본문 변경 시 자기 PR 번호를
`related_prs` 에 hard-required" 로 진화하면 chicken-and-egg 가 실제 발생할 수 있음. 본 spec 은 그
시나리오를 **선제 가드** 로 명시한다 (Out-of-scope 아닌 §4 포함).

## 2) 사용자 시나리오

### S1 — plan sub-agent 가 신규 spec PR 생성

1. nmae 가 plan sub-agent launch
2. plan 이 `_template.md` 복사 의도로 신규 `docs/features/<slug>.md` 작성
3. 실수로 frontmatter `--- ... ---` 블록 누락 + `# Title` 로 시작
4. PR 생성 → `validate` job 즉시 fail → false fail 박제 (3건 누적)

### S2 — 신규 spec PR 의 self-reference

1. plan 이 새 spec 작성 + frontmatter 정상 작성
2. `related_prs: []` 빈 배열로 PR 생성 (자기 PR 번호 미지정 — 아직 생성 전이라 번호 부재)
3. (현재) validator 통과 — `related_prs` 누락은 hard-fail 안 함
4. (가설) README.md §10 강화 시 hard-fail → chicken-and-egg false fail

### S3 — rev audit 단계 1 — 본문 변경 + related_prs 누락

1. 기존 spec 본문 수정 PR
2. `related_prs` 에 본 PR 번호 미추가
3. validator soft-notice (현재) → rev audit 단계 1 에서 fail 처리 (README.md §10)

## 3) 요구사항

### 기능 요구사항

#### F1. 신규 spec frontmatter 누락 가드 (Layer 1 — preventive)

- [ ] **F1-a (단순 안내 강화)**: validator fail 메시지에 `_template.md` 링크 + frontmatter snippet 포함
  - 현재: `"<basename>: missing frontmatter (--- block)"`
  - TO-BE: 위 + `\n→ docs/features/_template.md 1-18 line 을 복사해 frontmatter 추가 필요. 예시:\n---\nfeature: <name>\nslug: <slug>\nstatus: draft\nowner: @<gh>\nscope: <infra|...>\nrelated_issues: []\nrelated_prs: []\nlast_reviewed: YYYY-MM-DD\n---`
- [ ] **F1-b (sub-agent prompt template 보강)**: `docs/ai-harness/12-sub-agent-prompt-template.md` plan 역할
  안에 "신규 spec 작성 시 첫 줄은 반드시 `---`" 명시 — plan sub-agent 의 학습 의존 ↓.

#### F2. chicken-and-egg 선제 exception (Layer 2 — defensive)

- [ ] **F2-a**: validator `related_prs` hard-fail 가드를 강화하더라도, **ADDED 파일** (PR diff 의 신규
  파일 — `git diff --name-status A` 라인) 에 한해 다음 케이스는 통과 허용
  - `related_prs: []` (빈 배열)
  - `related_prs: [<self_pr_number>]` (자기 PR 번호 1개만)
- [ ] **F2-b**: 기존 spec 수정 (MODIFIED) 은 변경 없음 — 현 룰 유지 (정수 배열 형식, soft-notice 본문 변경 시
  자기 PR 번호 포함 여부)
- [ ] **F2-c**: ADDED 파일 판별은 `git diff --name-status ${baseSha}...${headSha} -- 'docs/features/*.md'`
  의 `A\t<path>` 라인으로 식별. RENAMED (`R`) / COPIED (`C`) 는 ADDED 와 동일 취급.

#### F3. 보강 audit log

- [ ] **F3-a**: ADDED 신규 spec 우회 발생 시 `core.notice` 로 1 줄 audit (`"신규 spec <basename>: related_prs
  자기 PR self-reference exception 적용"`)
- [ ] **F3-b**: README.md §10 에 "신규 spec ADDED 는 self-reference exception" 1 줄 명시 — rev audit 단계 1
  학습 의존 ↓.

### 비기능 요구사항

- **회귀 0**: F2 exception 은 ADDED 만 적용. 기존 spec 수정의 형식 가드는 손대지 않음.
- **audit 가시성**: F3-a notice 가 GitHub Actions UI 의 Annotations 에 노출되어 우회 사실이 reviewer 에게 가시.
- **idempotent**: 같은 PR 재실행 (synchronize) 시 결과 동일.

## 4) 범위 / 비범위 (중요)

### 포함

- **§3 F1**: validator fail 메시지 강화 + sub-agent prompt template plan 역할 frontmatter 의무 명시 (Layer 1)
- **§3 F2**: chicken-and-egg ADDED self-reference exception (Layer 2, 선제 가드)
- **§3 F3**: audit log + README.md §10 1 줄 명시

### 제외 (Out of Scope)

- **PR 머지 후 post-merge hook 으로 `related_prs` 자동 갱신** — impl 복잡 (PR 머지 commit 의 spec 파일에
  머지된 PR 번호를 sed 로 삽입하는 follow-up commit). 본 사이클 미도입. 향후 별도 spec 으로 재논의.
- **`status` enum 추가** — 본 spec 범위 밖.
- **`last_reviewed` 자동 갱신** — README.md §10 룰만 유지, 자동화 미도입.
- **README.md / `_template.md` 파일 자체 frontmatter 의무** — 두 파일은 제외 리스트 (workflow line 49) 유지.

## 5) 설계

### 5-1) 영향 파일 (실 경로 확정)

| 파일 | 변경 | 담당 PR |
|---|---|---|
| `.github/workflows/spec-status-check.yml` (job `validate`) | F1-a (메시지 강화) + F2 (ADDED exception) + F3-a (notice) | be impl PR |
| `docs/ai-harness/12-sub-agent-prompt-template.md` | F1-b (plan 역할 frontmatter 첫 줄 의무) | plan follow-up PR |
| `docs/features/README.md` §10 | F3-b (ADDED self-reference exception 1 줄) | plan follow-up PR |

### 5-2) workflow 변경 sketch (be impl PR 참고용 — 본 spec 은 코드 변경 X)

ADDED 파일 식별:
```js
// 기존 changedSpecBasenames 계산 직후 추가
let addedSpecBasenames = new Set();
if (prNumber) {
  try {
    const out = execSync(`git diff --name-status ${baseSha}...${headSha} -- 'docs/features/*.md'`, { encoding: 'utf8' });
    addedSpecBasenames = new Set(
      out.split('\n')
        .map(line => line.trim())
        .filter(line => /^[ARC]\s/.test(line))  // ADDED / RENAMED / COPIED
        .map(line => path.basename(line.split(/\s+/).pop()))
    );
  } catch (e) {
    core.notice(`git diff --name-status 실패 (ADDED detection skip): ${e.message}`);
  }
}
```

`related_prs` 검증 안 self-reference exception (가설적 가드):
```js
// related_prs hard-fail 가 도입된 가설 시점에 적용
if (relatedPrsMatch && addedSpecBasenames.has(f)) {
  const isSelfRef = parsed.values.length === 0
    || (parsed.values.length === 1 && parsed.values[0] === prNumber);
  if (isSelfRef) {
    core.notice(`신규 spec ${f}: related_prs 자기 PR self-reference exception 적용`);
    // hard-fail skip — soft-notice 만
  }
}
```

frontmatter 누락 메시지 강화:
```js
reportFail(
  `${f}: missing frontmatter (--- block)\n` +
  `→ docs/features/_template.md line 1-18 의 frontmatter 블록을 복사해 추가하십시오. 예시:\n` +
  `---\nfeature: <name>\nslug: <slug>\nstatus: draft\nowner: @<gh>\nscope: <infra|user|song|...>\n` +
  `related_issues: []\nrelated_prs: []\nlast_reviewed: YYYY-MM-DD\n---`
);
```

### 5-3) 도메인 모델

- 변경 없음 — workflow / docs only.

### 5-4) 시퀀스

```
plan sub-agent
  └─ 신규 spec PR open
       └─ GitHub Actions: validate job
            ├─ git diff --name-status → ADDED set
            ├─ for each spec file:
            │    ├─ frontmatter parse
            │    │    └─ 누락 시 강화된 메시지 (F1-a)
            │    ├─ status enum 검증 (변경 없음)
            │    ├─ related_prs 형식 검증 (변경 없음)
            │    └─ ADDED + self-ref → exception notice (F2 + F3-a)
            └─ soft-notice / hard-fail 결정
```

## 6) 작업 분할 (예상 PR 리스트)

| # | 담당 | 작업 | 비고 |
|---|---|---|---|
| 1 (본 spec PR) | plan | `docs/features/validate-workflow-related-prs-exception.md` 신설 | 본 사이클 |
| 2 (impl) | be | `.github/workflows/spec-status-check.yml` F1-a + F2 + F3-a | `needs-human-review` 라벨 (보호 영역) |
| 3 (follow-up docs) | plan | `12-sub-agent-prompt-template.md` F1-b + README.md §10 F3-b | 별도 PR |

PR 2 는 보호 영역 (`.github/workflows/**`) — `needs-human-review` 라벨 + self-merge 가능 (CLAUDE.md §4).

## 7) 테스트 전략

### PR 2 (impl) 검증 시나리오

| # | 시나리오 | 기대 |
|---|---|---|
| T1 | 신규 spec frontmatter 누락 PR | hard-fail + 강화된 메시지 (F1-a) |
| T2 | 신규 spec frontmatter 정상 + `related_prs: []` | pass |
| T3 | 신규 spec frontmatter 정상 + `related_prs: [<self_pr>]` | pass + audit notice (F3-a) |
| T4 | 기존 spec 수정 + 본문 변경 + `related_prs` 누락 | soft-notice (변경 없음) |
| T5 | 기존 spec 수정 + `related_prs: [#385]` (`#` prefix) | hard-fail (변경 없음) |
| T6 | 신규 spec + `related_prs: [9999, <self_pr>]` (다른 번호 포함) | (현재 룰) pass — self-ref exception 미적용 |

검증 방법: PR 2 자체에 위 6 시나리오를 commit fixture 로 추가하는 대신, PR 2 머지 후 다음 plan-launched
spec PR 3-5건의 결과를 관찰. 회귀 0 확인.

### PR 1 (본 spec) 검증

- `validate` job 자기 자신이 통과 — frontmatter 정상 (본 파일 line 1-10).
- `related_prs: []` (현재 PR 번호 미지정) → pass (현 룰).

## 8) 오픈 질문

| # | 질문 | 선택지 | 담당/기한 |
|---|---|---|---|
| Q1 | F2 self-reference exception 의 "self_pr" 1 개만 허용? 아니면 ADDED 신규 spec 은 `related_prs` 자체 검증 skip? | (a) self-ref + `[]` 만 / (b) ADDED 전체 skip | plan 자율 결정: (a) — surface area 좁힘 |
| Q2 | post-merge hook 으로 `related_prs` 자동 갱신 도입? | (a) 도입 / (b) 미도입 | plan 자율 결정: (b) — 복잡, 별도 spec |
| Q3 | F2 가 현 시점 발생 안 하는 선제 가드인데 본 spec 에 포함? | (a) 포함 / (b) Layer 1 만 처리 후 Layer 2 별도 spec | plan 자율 결정: (a) — 같은 root area, 한 PR 묶음 |

## 9) 결정 로그

- 2026-05-26: 초안 작성 (status=draft). rev round 4 박제 (3 PR `missing frontmatter` fail) 기반.
  task 본문의 "related_prs chicken-and-egg" 진단을 root cause 정정 — 실제 fail = frontmatter 누락.
  Layer 1 (preventive) + Layer 2 (defensive self-ref exception) 2-track 으로 분리.
