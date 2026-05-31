---
feature: spec-status-check legacy key hard fail
slug: spec-status-check-legacy-key-fail
status: draft
owner: @mobruji-maestro
scope: infra
related_issues: []
related_prs: [1167, 1169]
last_reviewed: 2026-05-27
---

# spec-status-check legacy key hard fail

## 1) 개요 (What / Why)

`.github/workflows/spec-status-check.yml` 이 README §10 표준 이전의 legacy frontmatter 키
(`name` / `owners` / `related-issues` / `related-prs`) 를 발견하면 hard fail 시키도록
강화한다.

**배경 — 박제 사례**:
- PR #1167 (`docs(infra): docs/features + docs/decisions README 인덱스 sync`) 작업 중
  `docs/features/rev-e2e-3-stages.md` (2026-05-30 PR #1367 머지로 `rev-e2e-2-stages.md`
  로 rename, 단계 3 폐기) frontmatter 가 legacy schema (`name` / `owners` /
  `related-issues: ["#882", "#851"]`) 로 잔존하여 README 표 `last_reviewed` 컬럼이 채워
  지지 못한 사례 발견.
- PR #1169 (`docs(infra): rev-e2e-3-stages frontmatter modernize`) 에서 modernize
  진행. 하지만 그동안 `spec-status-check.yml` 은 legacy 키 자체를 **무시 (silent
  pass)** 했기 때문에 신규 spec 작성자가 templates / 다른 spec 을 참고해 legacy
  패턴을 다시 도입할 위험 잔존.

**왜 필요한가**:
- frontmatter 표준이 README §10 으로 명문화돼도, workflow 가 legacy 키를 hard fail
  안 시키면 학습 / 검토 의존이 누적. 같은 사고 반복 위험.
- `_template.md` 안내 주석 (PR 분담 §9 plan) 만으로는 LLM / 사람 모두 답습 가능 —
  CI 단에서 차단해야 한다.

**대상 액터**: plan / be / fe / rev sub-agent (spec 작성·수정 시) + Feature Spec 작성
하는 사람 전반.

## 2) 사용자 시나리오

1. **신규 spec 작성**: plan sub-agent 가 `_template.md` 를 복사해서 새 spec 을 만든
   다. 만약 다른 spec (예: 과거 legacy 잔존 파일) 을 참고하여 `name:` / `owners:` /
   `related-issues:` 키를 그대로 쓰면, PR 단계에서 CI 가 hard fail. error message 에
   "legacy key `name` — use README §10 표준 `feature` + `slug` 대신" 형태로 정정 가
   이드 노출.
2. **spec frontmatter 수정**: 기존 spec 의 `related-prs: ["#385"]` → `related_prs:
   [385]` 로 modernize 하는 PR. 다른 legacy 키 (`name` / `owners`) 잔존 시 같은 PR
   안에서 한꺼번에 fix 하도록 fail 가이드.
3. **rev audit**: rev sub-agent 가 단계 1 (PR 머지 전) 에서 spec frontmatter 가
   modernize 됐는지 추가 grep 안 해도 — CI 가 이미 hard fail 로 차단.

## 3) 요구사항

### 기능 요구사항
- [ ] `.github/workflows/spec-status-check.yml` 가 다음 legacy 키 발견 시 **hard
      fail** 한다 (현 silent pass → 변경):
  - `name:` (top-level frontmatter key)
  - `owners:` (복수형, 단수 `owner` 가 표준)
  - `related-issues:` (kebab-case, snake_case 가 표준)
  - `related-prs:` (kebab-case, snake_case 가 표준)
- [ ] error message 형식: `<filename>: legacy key '<key>' — README §10 표준 '<표준
      키>' 사용. 상세: docs/features/README.md §10 + docs/features/spec-status-
      check-legacy-key-fail.md`
- [ ] legacy 키와 표준 키 매핑 표 (코드 주석 + spec 본 문서에 동시 명시):
  | legacy key | 표준 key | 변환 가이드 |
  |---|---|---|
  | `name` | `feature` + `slug` | 사람이 읽는 이름은 `feature`, 파일명은 `slug` 로 분리 |
  | `owners: <handle>` | `owner: @<github-handle>` | 단수 + `@` prefix |
  | `related-issues: ["#A"]` | `related_issues: [A]` | snake_case + 정수 |
  | `related-prs: ["#A"]` | `related_prs: [A]` | snake_case + 정수 |
- [ ] 변경된 spec (본 PR diff 에 포함) 만 hard fail 적용 (기존 spec 잔존 legacy 키는
      notice 로 가시화). 사유: README §10 첫 도입 시점 (`spec-status-check.yml`) 의
      `changedSpecBasenames` strict 가드 패턴 그대로 유지. 잔존 정리는 별도 sweep PR
      에서.
- [ ] notice 단계에서도 legacy 키 가진 spec 의 basename 목록을 한 번에 보고 (`legacy
      keys detected in N specs:` 형식) 하여 다음 sweep 우선순위 정함.

### 비기능 요구사항
- workflow 실행 시간 영향 최소 (현재 ~10s, 추가 ~1s 이내 expected)
- false positive 0: frontmatter 본문 안 키만 검사 (코드 블록 / spec 본문 내 예시는
  무시). 현재 `m[1]` slice 후 `match()` 패턴 그대로 유지.

## 4) 범위 / 비범위

### 포함
- 4개 legacy 키 (`name` / `owners` / `related-issues` / `related-prs`) hard fail.
- error message 정정 가이드 동봉.
- 변경된 spec 만 strict 가드 (기존 패턴 유지).
- 본 spec 문서 (계획 / 결정 로그).
- `_template.md` 안내 주석 보강 (legacy 키 금지 명시).

### 제외 (Out of Scope)
- 잔존 legacy spec 자동 sweep (별도 sweep PR — rev audit 가 발견 시 별도 이슈 등록).
- frontmatter 이외 다른 표준 정정 (예: 본문 `## 1) 개요` 헤딩 룰 강제 등).
- `docs/decisions/*.md` (ADR) frontmatter 표준 — ADR 표준은 별도 spec/룰 (`docs/
  decisions/README.md` 별).

## 5) 영향 파일 / 설계

### 5-1) 영향 파일
| 파일 | 변경 | 담당 PR |
|---|---|---|
| `docs/features/_template.md` | legacy 키 금지 안내 주석 추가 | plan (본 PR) |
| `docs/features/spec-status-check-legacy-key-fail.md` | 신설 spec | plan (본 PR) |
| `.github/workflows/spec-status-check.yml` | legacy 키 hard fail 로직 추가 | be (후속 PR) |

### 5-2) workflow 변경 (be 담당 구현 가이드)

`spec-status-check.yml` 의 spec 파일 loop 안에 다음 검증 블록을 추가한다 (위치: status
enum 검증과 related_prs 검증 사이 또는 둘 다 이후):

```javascript
// ---- legacy 키 hard fail
const legacyKeyMap = {
  'name': 'feature + slug (사람이 읽는 이름은 feature, 파일명은 slug)',
  'owners': 'owner (단수, @<github-handle> 형식)',
  'related-issues': 'related_issues (snake_case)',
  'related-prs': 'related_prs (snake_case)',
};
for (const [legacyKey, replacement] of Object.entries(legacyKeyMap)) {
  // frontmatter 안 top-level key 패턴: ^<key>: (시작 anchor + colon)
  // legacy `related-issues` / `related-prs` 는 `-` 가 포함됐기 때문에 정규식 escape 불필요.
  const legacyRe = new RegExp(`^${legacyKey}:`, 'm');
  if (legacyRe.test(fm)) {
    reportFail(
      `${f}: legacy key '${legacyKey}' — README §10 표준 '${replacement}' 사용. ` +
      `상세: docs/features/README.md §10 + docs/features/spec-status-check-legacy-key-fail.md`
    );
  }
}
```

`reportFail` 헬퍼는 기존 코드 그대로 재사용 (변경된 spec 만 strict, 나머지는 notice).

### 5-3) 검증 시나리오

| 시나리오 | 입력 | 기대 결과 |
|---|---|---|
| modernize 된 spec 변경 | `feature/slug/owner/scope/related_issues/related_prs/last_reviewed` | ✓ pass |
| legacy `name` 사용 | `name: foo` | hard fail (변경된 spec) / notice (잔존) |
| legacy `owners` 사용 | `owners: foo` | hard fail (변경된 spec) / notice (잔존) |
| legacy `related-issues` 사용 | `related-issues: [882]` | hard fail (변경된 spec) / notice (잔존) |
| legacy `related-prs` 사용 | `related-prs: [882]` | hard fail (변경된 spec) / notice (잔존) |
| 본문 코드블록 안 `name:` 예시 | `\`\`\`yaml\nname: foo\n\`\`\`` (본문) | ✓ pass (frontmatter slice 밖) |

## 6) 작업 분할 (예상 PR 리스트)

- [x] **plan PR (본 PR)** — `docs/features/_template.md` 안내 주석 보강 + 본 spec
      신설
- [ ] **be PR (후속)** — `.github/workflows/spec-status-check.yml` legacy 키 hard fail
      로직 추가
- [ ] **(선택) sweep PR** — 잔존 legacy spec (현재 0건으로 추정 — PR #1169 가
      `rev-e2e-3-stages` modernize 후 sweep 결과 확인 필요) 일괄 정리

## 7) 테스트 전략

### plan PR (본 PR)
- `.github/workflows/spec-status-check.yml` 자체 변경 없음 → frontmatter 검증만 통과
  하면 됨.
- 본 spec 자체 frontmatter 가 README §10 준수 (`feature` / `slug` / `status` / `owner`
  / `scope` / `related_issues` / `related_prs` / `last_reviewed`) — workflow self-test.

### be PR (후속)
- workflow 변경 후, 의도적으로 legacy 키 가진 임시 spec 을 PR 에 포함하여 hard fail
  관찰 (테스트 머지하지 않음).
- 잔존 legacy spec (있다면) 이 notice 로만 가시화되는지 — fail 안 됨 확인.
- 정상 spec PR (예: README §10 준수) 가 통과하는지 sanity.

## 8) 오픈 질문

| # | 질문 | 선택지 | 담당/기한 |
|---|---|---|---|
| Q1 | legacy 키 hard fail 시점에 잔존 legacy spec 이 남아 있다면 notice 메시지에 sweep 권고 한 줄 추가할까? | (a) yes — notice 메시지에 "잔존 N건 — 별도 sweep PR 권장" 동봉 / (b) no — silent | be 구현 시 결정 |
| Q2 | error message 의 정정 가이드 분량 — 한 줄 vs 표 link 만? | (a) 한 줄 + spec link / (b) spec link 만 (현 README §10 패턴) | be 구현 시 결정. 권고: (a) — workflow 안 contextual guidance 가 학습 가속 |

## 9) 결정 로그

- 2026-05-27: 초안 작성 (status=draft). PR #1167 + #1169 박제 사례 동봉. plan 사이클
  분담 — plan PR = `_template.md` 안내 주석 보강 + 본 spec 신설, be PR = workflow
  hard fail 구현.
