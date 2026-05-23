# Feature Specs

기능 단위의 **living document** 저장소. 한 번 쓰고 끝나는 구현 계획이 아니라,
기능이 존재하는 동안 계속 유지·갱신되는 명세서다.

## 1) 왜 필요한가

- **합의 이력**: 요구사항/설계 결정을 누가 언제 왜 내렸는지 한 파일에 모음
- **온보딩**: 신규 팀원이 "이 기능 왜 이렇게 만들었지?"를 코드 밖에서 바로 파악
- **AI 컨텍스트**: Claude/다른 에이전트가 관련 PR을 만들 때 Read해서 컨텍스트 로드
- **중복 논의 방지**: 같은 결정을 반복해서 논의하지 않도록 결정 로그로 고정

## 2) PR vs Feature Spec 역할

| | PR body | Feature Spec |
|---|---|---|
| 목적 | **이 PR이 무엇을 바꿨나** | **이 기능이 무엇인가** |
| 생명 | 머지되면 역사 | 살아있음, 지속 갱신 |
| 범위 | 한 번의 변경 | 기능 전체 (여러 PR 걸침) |
| 재방문 | 거의 없음 | 자주 |

1 spec ↔ N 이슈/PR. 요구사항이 바뀌면 spec을 `type:docs` PR로 갱신한다.

## 3) 언제 써야 하는가 (필수 기준)

다음 중 **하나라도 해당**하면 Feature Spec을 먼저 작성·합의한 뒤 구현에 착수한다.

- 신규 도메인 기능 (엔티티 신설 또는 신규 API)
- 외부 연동 도입 (음원/메타데이터 API, OAuth, ML 모델 등)
- 여러 PR에 걸쳐 구현될 중간 규모 이상 기능

**선택 사항** (spec 불필요):
- 단순 버그 수정, 리팩터링, 스타일, 문서-only
- 엔티티 1개 필드 추가 수준의 작은 변경

판단은 작성자 재량. 애매하면 작성하는 쪽을 권장.

## 4) 파일 네이밍

`<slug>.md` — 이슈 번호 없이. spec은 단일 이슈보다 생명이 길다.

예시:
- `voice-range-input.md`
- `song-metadata-source.md`
- `recommendation-algorithm-v1.md`

하위 디렉토리는 두지 않는다(평면 구조 유지).

## 5) 라이프사이클

frontmatter의 `status` 필드로 추적한다.

| Status | 의미 | 전이 조건 |
|---|---|---|
| `draft` | 작성 중, 아직 합의 전 | 초안 완성 시 리뷰어에 제시 |
| `approved` | 합의 완료, 구현 착수 가능 | 오픈 질문이 모두 해소되고 리뷰어 승인 |
| `implementing` | 일부/전부 구현 중 | 첫 구현 PR이 열릴 때 |
| `shipped` | 실 서비스 반영 | 모든 구현 PR이 main에 머지될 때 |
| `deprecated` | 교체/폐기, 이력 보존 | 기능 제거/교체 시. 파일은 삭제하지 않음 |

상태 전이는 해당 PR에서 frontmatter를 함께 수정한다.

## 6) 프로세스

1. **Spec 초안 PR** — `docs/features/<slug>.md` 신설
   - 제목: `docs(scope): <기능명> Feature Spec 초안`
   - 라벨: `type:docs`, `scope:*`, `ai-generated`(AI 작성 시)
2. **리뷰/수정** — 코멘트와 결정 로그를 통해 합의. 오픈 질문을 해소
3. **머지** — status = `approved`
4. **구현 PR들** — PR 본문에 `참고: docs/features/<slug>.md` 백링크
   - 첫 구현 PR 머지 시 spec의 status를 `implementing`으로 갱신
5. **요구사항 변경** — spec을 `type:docs` 갱신 PR로 먼저 업데이트 → 이어서 구현 PR
6. **기능 완료** — 마지막 구현이 main 도달하면 status = `shipped`

## 7) 템플릿

`_template.md`를 복사해서 시작한다. 모든 섹션을 다 채울 필요는 없지만,
"범위/비범위", "오픈 질문", "결정 로그"는 비우지 말 것.

## 8) AI 에이전트 의무

- 관련 기능의 PR을 만들 때 **반드시** 해당 spec을 Read
- spec과 코드가 충돌하면 **spec을 먼저 갱신**한 뒤 구현 (01-harness-spec §5 결정 규칙)
- 오픈 질문 중 구현에 영향을 주는 것이 남아있으면 구현 착수 금지, 사용자에게 확인 요청

## 9) 현재 목록

아직 작성된 spec 없음. 첫 기능 spec은 도메인 모델(`docs/ai-harness/06-domain-model.md`) 채워진 뒤 작성.

## 10) Frontmatter 필드 의무

모든 spec(`_template.md` / `README.md` 제외)은 다음 frontmatter 필드를 갖는다. `.github/workflows/spec-status-check.yml` 워크플로우가 PR 단계에서 형식을 검증한다.

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `feature` | string | 필수 | 사람이 읽는 기능 이름 |
| `slug` | string | 필수 | 파일명과 동일(확장자 제외) |
| `status` | enum | 필수 | `draft` / `approved` / `implementing` / `shipped` / `deprecated` / `blocked` |
| `owner` | string | 필수 | `@<github-handle>` 형식 |
| `scope` | enum | 필수 | `user` / `song` / `recommendation` / `voice` / `infra` / `web` / `feedback` |
| `related_issues` | int[] | 필수 | 정수 배열. `[]` 허용. `#` prefix 금지 (예: `[123, 456]`) |
| `related_prs` | int[] | 필수 | 정수 배열. `[]` 허용. `#` prefix 금지 (예: `[123, 456]`) |
| `last_reviewed` | date | 필수 | `YYYY-MM-DD` 형식. 마지막 종합 리뷰 일자 |

### 10-1) `related_prs` 갱신 의무 (비협상)

PR 본문이 특정 spec 의 내용을 변경(섹션 추가/수정/결정 로그 항목 추가 등)할 때, 그 PR 의 diff 에 **반드시** 해당 spec frontmatter `related_prs` 에 자기 PR 번호를 추가해야 한다.

**왜 필요한가**: spec 별 영향 PR 추적이 끊기면 (a) 회귀 발생 시 원인 PR 식별 불가, (b) onboarding 시 "왜 이렇게 정해졌지?" → 결정 로그만 보고 cross-ref 실종, (c) rev audit 이 spec drift 발견 못 함. 2026-05-24 PR #965 rev 가 `anonymous-session-lifecycle` 의 `related_prs` 누락 발견 → 전수 sweep 결과 8 spec 누락/형식 오류 (PR #980 참조). 본 의무가 명문화 안 됐던 게 원인.

**예외**:
- PR 이 spec frontmatter `last_reviewed` 만 갱신 (본문 무변경) → 추가 불요
- PR 이 spec 을 단순 백링크 (`참고: docs/features/<slug>.md`) 만 하고 spec 자체는 안 건드림 → 추가 불요

**검증**:
- `spec-status-check.yml` 이 형식 검증: `#` prefix 가 있거나 정수가 아닌 경우 fail
- 머지된 PR 의 diff 가 spec 본문을 건드렸는데 frontmatter `related_prs` 에 자기 PR 번호가 없는 경우 워크플로우가 **소프트 경고**(notice) — fail 시키지 않음. rev audit 이 단계 1 에서 보고할 수 있도록 가시화만.

### 10-2) 형식 표준 예시

```yaml
---
feature: Anonymous Session Lifecycle
slug: anonymous-session-lifecycle
status: implementing
owner: @mobruji-maestro
scope: user
related_issues: [913, 924, 925]
related_prs: [913, 924, 925, 934, 936, 937, 957, 965]
last_reviewed: 2026-05-24
---
```

**금지 패턴**:
- `related_prs: [#385, #391]` — `#` prefix 금지
- `related_prs: 385, 391` — 배열 brackets 필수
- `related-prs: [385]` — underscore separator 필수 (`-` 금지)
- `related_prs: ["385"]` — 정수 (문자열 금지)
