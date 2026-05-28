---
feature: Release Cadence v0.4.0 — cutoff 룰 + 범위 결정
slug: release-cadence-v0.4.0
status: draft
pending: user-decision (v0.4.0 release cut 진행 여부 — rev audit D12, 2026-05-23)
owner: @goohong
scope: infra
related_issues: []
related_prs: [774, 856, 871, 875, 939]
last_reviewed: 2026-05-24
---

# Release Cadence v0.4.0 — cutoff 룰 + 범위 결정

## 1) 개요 (What / Why)
- v0.3.3 머지(2026-05-23) 이후 `develop` ahead `main` 이 27 commit 까지 누적됨. cutoff 판단 룰이 없어 매번 사용자에게 "지금 release 할까?"를 물어보고 있다.
- v0.4.0 부터는 **cadence 룰**(언제 cut 할지)과 **scope 라벨**(어떤 PR을 포함할지)을 spec 으로 박제해, maestro가 자율적으로 release PR draft 를 만들 수 있게 한다.
- maestro가 release 만 사용자 확인을 받는 [Rev release gate](feedback_rev_release_gate.md) 흐름을 유지하되, "언제 어떤 PR로" 결정의 인지 비용을 0 으로 만든다.

## 2) 사용자 시나리오
- maestro는 매 사이클 끝에 ahead 카운트를 체크. cutoff 룰 임계치 도달 시 자동으로 release PR draft 생성 + `#모부르지` push.
- 사용자는 Discord 에서 release draft 링크를 받고 changelog 만 훑은 뒤 `merge` 한 마디로 승인.
- rev 워크트리는 release PR 의 모든 포함 PR 이 `reviewed:claude` 라벨인지 gate 검증 (이미 박제됨).

## 3) 요구사항

### 기능 요구사항
- [ ] cutoff 임계치 룰 합의 + 박제 (commits / days / 우선순위 PR 기준)
- [ ] release 범위 라벨 정책 합의 (`release:next`, `release:skip` 신설 or 기존 `type:*` 만으로 충분?)
- [ ] release notes draft 자동 생성 스크립트 흐름 (수동 PR 작성 vs 자동 changelog generator)
- [ ] v0.4.0 의 실제 범위 결정 + cutoff 날짜 잠정 합의

### 비기능 요구사항
- release 결정 인지 비용 = 사용자 1 메시지 ("merge" 또는 "wait")
- release 머지 후 24 시간 내 hotfix 발생율 ≤ 20% (cutoff 너무 공격적이면 증가)
- release notes 가독성: type 별 그룹핑 + 사용자 영향 1줄 요약

## 4) 범위 / 비범위

### 포함
- cadence 룰 (commits/days/우선순위 임계치 옵션 ABC)
- release 라벨 정책
- v0.4.0 잠정 범위 (현 develop ahead 27 → 어디까지 포함)

### 제외 (Out of Scope)
- semver 룰 자체 변경 (현 0.x.y 체계 유지)
- `main → production deploy` 자동화 (별도 spec, NCP Phase 5)
- hotfix branch 정책 (v0.4.1 이후 별도 spec)

## 5) 설계

### 5-1) cutoff 룰 옵션 ABC

#### 옵션 A: commit 카운트 단순 (10 commits 도달 시 cut)
- 장점: 결정 룰이 단순. 카운트만 보면 됨.
- 단점: chore/docs PR 이 많이 섞이면 의미 없는 release 가 잦음. hotfix-급 fix 가 큰 batch 뒤에 묻힘.

#### 옵션 B: 시간 + commit 하이브리드 (7 일 OR 15 commits 중 먼저 도달)
- 장점: 변경 빈도 적은 주에도 정기 release. batch 폭주 시에도 cap.
- 단점: 7 일 cutoff 가 너무 길면 ahead 누적, 너무 짧으면 의미 없는 release.

#### 옵션 C: 우선순위 PR + cap (fix/feat 5건 OR 20 commits) ← **권장**
- 트리거: `type:fix` + `type:feat` PR 합산 5 건 도달, **OR** ahead 20 commit 도달.
- `type:docs` / `type:chore` / `test` 만 쌓인 경우는 trigger 안 함 (사용자 노출 가치 낮음).
- 장점: "사용자가 체감할 변경"이 5건 모이면 자동 cut. docs/test 만 누적되어 ahead 50 가도 release 안 함.
- 단점: 룰이 약간 복잡. `gh pr list --label type:fix,type:feat --search "base:develop merged"` 로 카운트 가능.

### 5-2) release 라벨 정책

| 라벨 | 용도 | 부여 시점 |
|---|---|---|
| `release:next` | 다음 release 에 포함 (옵션 C 기본값) | PR 머지 후 자동 (없으면 모든 fix/feat 자동 포함) |
| `release:skip` | release notes 에 노출하지 않음 | docs-only 또는 revert 시 수동 부여 |
| `release:hotfix` | 다음 cutoff 무시하고 즉시 release | 사용자 명시 |

> 권장: 디폴트 "전부 포함", `release:skip` 만 명시. 라벨 부담 최소화.

### 5-3) release notes 자동화 스크립트 흐름
1. maestro 사이클 끝에 `gh pr list --base develop --state merged --search "merged:>=<last_release_date>"` 호출.
2. 옵션 C 임계치 도달 여부 판단.
3. 도달 시:
   - `git log <last_tag>..origin/develop --oneline` 으로 commit 추출
   - `type:*` 라벨 기준 그룹핑 (feat / fix / docs / chore / test / refactor)
   - `release/v0.4.0` branch 생성, draft PR (base: main) 생성
   - `#모부르지` push: "v0.4.0 release draft #XXX — ahead 22 / fix 3 + feat 2 / 검토 부탁"
4. 사용자 `merge` 시 maestro가 `gh pr merge --merge` (Squash 금지, develop 히스토리 보존).
5. tag + `gh release create` 도 maestro 자동.

### 5-4) v0.4.0 잠정 범위

현 develop ahead 27 PR 중 사용자 영향 큰 변경:
- `feat`: bot.py digest 가독성 (#733), maestro 응답 캡처 (#735), auto-ack ETA (#737)
- `fix`: NODE_OPTIONS preserve-symlinks (#734, #743), midiToNoteName NaN guard (#763), sessionId UUID entropy (#769), maestro watcher Discord mask (#751), watcher chunk 재시도 (#761), Discord mention sanitize (#767), recommend page lint (#750), vitest test (#748), digest test (#741), cd-dev env-file (#758), SECRET_MASK_PATTERNS (#771)
- `docs`: ADR-0014 patch (#491, #495), context auto-clear (#753, #768, #772), session:plan 라벨 (#415), scoreBreakdown zero-span (#762), 등
- `test`: apiFetch coverage 5건 (#722, #724, #726, #728, #730), RootLayout metadata (#732)

**옵션 C 기준 적용**: fix 11건 + feat 3건 = 14건 → 5건 임계치 초과. **이미 release cut 시점.**

### 5-5) DB 마이그레이션
- 없음.

## 6) 후속 PR 분할
1. **PR-1**: 본 spec 머지 (현재 PR)
2. **PR-2**: `02-agent-workflow.md §8 릴리즈` 에 옵션 C 룰 + 라벨 정책 반영
3. **PR-3**: maestro prompt template (`actors/sub-agent.md`) 에 cutoff 자동 판단 step 추가
4. **PR-4**: `release/v0.4.0` 실 PR 생성 (사용자 승인 후)

## 7) 오픈 이슈
- 옵션 C 의 5건 / 20 commit 임계치는 첫 사이클에서 측정 후 조정 가능. v0.5.0 회고 때 재평가.
- `release:skip` 라벨 강제 룰을 안 두면 docs/test PR 도 모두 changelog 에 나옴 → notes 길어짐 → 사용자 가독성 저하. 첫 release notes 길이 보고 결정.
- hotfix 별도 branch 정책은 v0.4.1 첫 발생 시 spec 신설.
- 2026-05-23: rev audit D12 발견 — fix 11 + feat 3 = 14건 (5건 임계치 3배 초과), v0.3.3..origin/develop 56 commit ahead. 자동화 (cron self-trigger) 미구현 → 사용자 결정 대기. v0.4.0 release cut 진행 여부 maestro Discord push 알림 완료.

## 8) 영향 파일 (코드 변경 없음, 후속 PR 에서 갱신)
- `docs/ai-harness/02-agent-workflow.md` §8 (PR-2)
- `docs/ai-harness/actors/sub-agent.md` (PR-3)
- `CLAUDE.md` §8 (옵션 C 룰 1줄 박제, PR-2 와 동시)
