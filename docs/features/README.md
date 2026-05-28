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

> **자동 갱신 의무 (2026-05-27 신설)**: 신규 spec 추가 / status 전이 / `last_reviewed` 갱신 PR 은 같은 diff 안에서 본 표 행도 함께 갱신한다. drift 발견 시 plan 사이클이 docs(infra) sync PR 로 보강.
>
> scope 별 그룹 → slug 알파벳순. status 는 frontmatter 와 1:1.

### scope: infra

| Slug | Feature | Status | Last reviewed |
|---|---|---|---|
| [agent-launch-wrapper-enforcement](agent-launch-wrapper-enforcement.md) | Agent Launch Wrapper Enforcement (사이클 가시화 + wrapper 호출 강제) | draft | 2026-05-26 |
| [agent-role-enforcement](agent-role-enforcement.md) | Agent Role Enforcement | approved | 2026-05-26 |
| [adr-0023-suboption-analysis](adr-0023-suboption-analysis.md) | ADR-0023 sub-옵션 A1/A2/A3 비교 분석 (release PR #1122 unblock) | draft | 2026-05-27 |
| [anonymous-session-lifecycle](anonymous-session-lifecycle.md) | 익명 sessionId 라이프사이클 (TTL 만료 + 사용자 회전 + 머지) | implementing | 2026-05-24 |
| [answer-first-verification-hook](answer-first-verification-hook.md) | helper answer-first verification hook (본답 누락 inject) | draft | 2026-05-26 |
| [autonomous-cycle-orchestration](autonomous-cycle-orchestration.md) | 자율 사이클 오케스트레이션 (4 워크트리 동시 가동 + cycle-status digest + worktree lock + helper boundary) | implementing | 2026-05-23 |
| [branch-and-issue-cleanup-policy](branch-and-issue-cleanup-policy.md) | Branch + Issue Cleanup Policy (expanded SoT) | approved | 2026-05-26 |
| [branch-cleanup-policy](branch-cleanup-policy.md) | Branch cleanup policy (remote / local / agent worktrees) | approved | 2026-05-26 |
| [bridge-deployment-dir-separation](bridge-deployment-dir-separation.md) | Bridge 배포 dir 분리 (옵션 A 근본 fix) | approved | 2026-05-26 |
| [channel-history-grep-wrapper](channel-history-grep-wrapper.md) | helper-turn-start 채널 history grep step 추가 | draft | 2026-05-26 |
| [clear-pre-hook](clear-pre-hook.md) | /clear pre-hook 강제 (4 액션 자동 수행 + graceful fallback) | draft | 2026-05-24 |
| [context-auto-clear](context-auto-clear.md) | maestro/helper context auto-clear 자동화 (95% 임계 → 자율 핸드오프 + /clear) | draft | 2026-05-24 |
| [cycle-backlog-and-auto-merge-hooks](cycle-backlog-and-auto-merge-hooks.md) | cycle-backlog-and-auto-merge-hooks | implemented | 2026-05-26 |
| [deployment-infrastructure](deployment-infrastructure.md) | 운영 배포 인프라 (Phase 5 prod — NCP 별 VM + Cloudflare, ADR-0015 재결정 반영) | draft | 2026-05-24 |
| [directive-board-event-driven-redesign](directive-board-event-driven-redesign.md) | directive board event-driven redesign | approved | 2026-05-27 |
| [directive-jsonl-mismatch-sweep](directive-jsonl-mismatch-sweep.md) | directive-board jsonl ↔ Discord mismatch 106건 자동 sweep + 지속 sync | draft | 2026-05-26 |
| [discord-daemon-hosting](discord-daemon-hosting.md) | Discord 데몬 호스팅 (무료 24/7 옵션) | draft | 2026-05-23 |
| [discord-driven-mobruji](discord-driven-mobruji.md) | Discord-driven maestro (tmux interactive + Discord bridge) | draft | 2026-05-24 |
| [discord-message-style](discord-message-style.md) | Discord 메시지 체계화 + 채널 매핑 | approved | 2026-05-24 |
| [discord-realtime-bidirectional](discord-realtime-bidirectional.md) | Discord 실시간 양방향 소통 인프라 | draft | 2026-05-24 |
| [discord-reply-cycle-channel-forum-adapter](discord-reply-cycle-channel-forum-adapter.md) | discord-reply.sh --cycle-channel forum adapter | draft | 2026-05-27 |
| [discord-reply-length-split](discord-reply-length-split.md) | discord-reply.sh length 2000 초과 split + retry | draft | 2026-05-26 |
| [discord-status-push](discord-status-push.md) | Discord 상태 push 룰 (maestro 사이클 트레일) | approved | 2026-05-24 |
| [event-action-mapping](event-action-mapping.md) | event-action-mapping | approved | 2026-05-26 |
| [helper-agent](helper-agent.md) | Helper Agent (nmae 영구 가동으로 mmae 대체 + 사용자 양방향 전담) | draft | 2026-05-23 |
| [helper-direct-work-guard-subagent-context](helper-direct-work-guard-subagent-context.md) | helper-direct-work-guard sub-agent context 보강 | implementing | 2026-05-27 |
| [helper-role-enforcement](helper-role-enforcement.md) | Helper Role Enforcement (relay-only 정의 + system prompt 강제 메커니즘) | draft | 2026-05-26 |
| [helper-target-freeze-enforced](helper-target-freeze-enforced.md) | helper-current-target.txt freeze 강제 (wrapper 호출 의무화) | draft | 2026-05-26 |
| [helper-thread-stream](helper-thread-stream.md) | Helper UX 묶음 — bot 1초 auto-ack + Discord thread stream + reply.referenced_message forwarding | draft | 2026-05-24 |
| [helper-writing-marker-timing-fix](helper-writing-marker-timing-fix.md) | helper writing marker timing fix | implementing | 2026-05-27 |
| [internal-label-scrub](internal-label-scrub.md) | 내부 ID 라벨 scrub (A1/B2/D1 → user-friendly paraphrase 분리) | draft | 2026-05-24 |
| [librosa-ci-build-monitoring](librosa-ci-build-monitoring.md) | librosa CI 빌드 시간 모니터링 + 캐싱 전략 | draft | 2026-05-24 |
| [maestro-auto-wake](maestro-auto-wake.md) | maestro 자동 wake 사이클 (idle 시 self-perpetuating + secondary backup) | approved | 2026-05-24 |
| [ncp-dev-deployment](ncp-dev-deployment.md) | Phase 4 — NCP maestro VM에 mobruji dev 환경 docker 격리 배포 | shipped | 2026-05-24 |
| [ncp-maestro-resilience](ncp-maestro-resilience.md) | NCP maestro 전체 사이클 멈춤 위험 점검 + 회복 자동화 | approved | 2026-05-23 |
| [nginx-dns-resolver-pattern](nginx-dns-resolver-pattern.md) | nginx DNS resolver pattern (stale upstream IP 회피) | implementing | 2026-05-27 |
| [nmae-cycle-watchdog](nmae-cycle-watchdog.md) | nmae 사이클 watchdog (4중 안전망 + STRICT mode + escalation) | draft | 2026-05-24 |
| [observability-baseline](observability-baseline.md) | 운영 관측성 베이스라인 (핵심 카운터 + p95 + 수집 스택) | draft | 2026-05-24 |
| [redundant-rules-audit-2026-05-26](redundant-rules-audit-2026-05-26.md) | Redundant Rules Audit (2026-05-26) | draft | 2026-05-26 |
| [release-cadence-v0.4.0](release-cadence-v0.4.0.md) | Release Cadence v0.4.0 — cutoff 룰 + 범위 결정 | draft | 2026-05-24 |
| [release-fork-watchdog](release-fork-watchdog.md) | release fork watchdog (scheduled GHA + 임계치 Discord push) | draft | 2026-05-27 |
| [rev-e2e-3-stages](rev-e2e-3-stages.md) | rev 3단계 e2e 자율 QA | draft | (frontmatter legacy) |
| [rev-qa-protocol](rev-qa-protocol.md) | rev 세션 QA 실행 검증 프로토콜 | implementing | 2026-05-24 |
| [spec-status-check-legacy-key-fail](spec-status-check-legacy-key-fail.md) | spec-status-check legacy key hard fail | draft | 2026-05-27 |
| [systemd-restart-always](systemd-restart-always.md) | mobruji-helper.service Type=simple + Restart=always | draft | 2026-05-26 |
| [user-decisions-pending-2026-05-26](user-decisions-pending-2026-05-26.md) | User Decisions Pending — 2026-05-26 권고안 | draft | 2026-05-26 |
| [v03-roadmap](v03-roadmap.md) | v0.3 P1~P3 로드맵 (백로그 우선순위 정리) | draft | 2026-05-24 |
| [validate-workflow-related-prs-exception](validate-workflow-related-prs-exception.md) | validate-workflow-related-prs-exception | draft | 2026-05-26 |
| [work-cycle-refactor](work-cycle-refactor.md) | work-cycle-refactor | approved | 2026-05-26 |
| [work-cycle-simplification](work-cycle-simplification.md) | work-cycle-simplification | draft | 2026-05-26 |

### scope: user

| Slug | Feature | Status | Last reviewed |
|---|---|---|---|
| [anonymous-to-account-conversion](anonymous-to-account-conversion.md) | 비회원/회원 흐름 정책 + 계정 전환 트리거 (v0.4) | draft | 2026-05-24 |
| [user-persona-and-pain-points](user-persona-and-pain-points.md) | 사용자 페르소나 + 페인 포인트 + 신규 feature 우선순위 (v0.3 사용자 가치 정렬) | draft | 2026-05-24 |

### scope: song

| Slug | Feature | Status | Last reviewed |
|---|---|---|---|
| [audio-tooling-bootstrap](audio-tooling-bootstrap.md) | Python 오디오 분석 툴링 부트스트랩 | shipped | 2026-05-24 |
| [external-api-fallback](external-api-fallback.md) | 외부 API graceful fallback (Spotify / MusicBrainz / YouTube / iTunes 횡단) | draft | 2026-05-24 |
| [musicbrainz-integration](musicbrainz-integration.md) | MusicBrainz 통합 — ISRC/mbid backfill + 메타 보강 | draft | 2026-05-24 |
| [song-curation-seed-100](song-curation-seed-100.md) | 노래방 시드 곡 큐레이션 100곡 확장 | draft | 2026-05-22 |
| [song-metadata-source](song-metadata-source.md) | 곡 메타데이터 출처 | shipped | 2026-05-23 |
| [song-self-analysis-pipeline](song-self-analysis-pipeline.md) | 자체 곡 분석 파이프라인 | draft | 2026-05-22 |
| [spotify-audio-features-integration](spotify-audio-features-integration.md) | Spotify Audio Features 통합 — mood signal (valence/energy) 보강 | draft | 2026-05-24 |

### scope: recommendation

| Slug | Feature | Status | Last reviewed |
|---|---|---|---|
| [high-note-training-mode](high-note-training-mode.md) | 고음 뚫기 / 발성 연습 추천 모드 (F2) | draft | 2026-05-24 |
| [recommendation-algorithm-v1](recommendation-algorithm-v1.md) | 추천 알고리즘 v1 | shipped | 2026-05-23 |
| [recommendation-algorithm-v2](recommendation-algorithm-v2.md) | 추천 알고리즘 v2 | shipped | 2026-05-23 |
| [recommendation-history-and-feedback](recommendation-history-and-feedback.md) | 추천 히스토리 백엔드 동기화 & 좋아요/북마크 피드백 | implementing | 2026-05-23 |
| [recommendation-p95-regression-guard](recommendation-p95-regression-guard.md) | 추천 API p95 응답시간 회귀 가드 (k6 + Micrometer 이원화) | draft | 2026-05-23 |
| [score-breakdown-zero-span](score-breakdown-zero-span.md) | scoreBreakdown zero-span (single-note) 정책 | blocked | 2026-05-23 |

### scope: voice

| Slug | Feature | Status | Last reviewed |
|---|---|---|---|
| [voice-range-auto-measurement](voice-range-auto-measurement.md) | 음역대 자동 측정 | draft | 2026-05-21 |
| [voice-range-input](voice-range-input.md) | 음역대 입력 | shipped | 2026-05-21 |
| [voice-range-measure-guided-tour](voice-range-measure-guided-tour.md) | 음역 측정 가이드 투어 + 한국어 친화 표기 통합 (F1) | draft | 2026-05-24 |
| [voice-range-progress](voice-range-progress.md) | 음역대 진행 추적 | shipped | 2026-05-21 |

### scope: web

| Slug | Feature | Status | Last reviewed |
|---|---|---|---|
| [ui-ux-redesign](ui-ux-redesign.md) | UI/UX 디자인 4단계 부활 (audit → ref → spec → 구현) | approved | 2026-05-24 |
| [web-e2e-playwright](web-e2e-playwright.md) | web/ Playwright e2e 인프라 도입 (design tokens 시각 회귀 가드) | draft | 2026-05-28 |

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
