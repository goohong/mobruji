---
feature: rev 2 단계 e2e 자율 QA (Pre-merge / Post-merge)
slug: rev-e2e-2-stages
status: draft
owner: @goohong
scope: infra
related_issues: [882, 851]
related_prs: [889, 932, 953, 958, 1009, 1193, 1340]
last_reviewed: 2026-05-30
---

# rev 2 단계 e2e 자율 QA (Pre-merge / Post-merge)

> **이전 spec rename / 재구조 (2026-05-30)**: 본 spec 은 `rev-e2e-3-stages.md` (단계 1 / 단계 2 / 단계 3) 의 후속 재구조판입니다. 단계 3 (release 후 production 검증) 은 production 환경 부재 사유로 **폐기**, 단계 1 / 단계 2 만 유지합니다. 폐기 evidence 와 사유는 §9 결정 로그 + §10 propagation cleanup 표 참조. 단계 명명은 **영어 짧은 표기** 채택: 🟡 **Pre-merge review** (단계 1) / 🔵 **Post-merge audit** (단계 2).

## 1. 배경 (Why)

PR #851 (voice-range 404 fix) 가 CI green 인 채 30분+ 머지 안 되고 방치 → 사용자 production 에서 여전히 404 (issue #879).
기존 rev 룰은 ADR audit / 도메인 audit 중심. 머지 전 PR 에 대한 자율 e2e 부재. release 전 `reviewed:claude` 라벨 게이트만 있고 (메모리 [[feedback-rev-release-gate]]), 머지 후 회귀 검증 단계 부재.
사용자 (2026-05-23) 결정: "P0 관계없이 모든 type:fix/feat 작업에 대해 e2e 가능하면 rev 가 항상 검사. 사후 검사건 PR 검사건 모두".

### 1-1. 3 단계 → 2 단계 재구조 사유 (2026-05-30)

사용자 표현 (2026-05-30): "rev 보면 stage 1 2 3 이렇게 태그가 있는데, 각각 무슨 단계인지 잘 안 와 닿아. 너가 파악하고 적절한 이름 붙여줄 수 있겠어?" + "영어 짧은 거 좋은데, 우리는 지금 dev 는 배포가 따로 안 되어 있어서. 2 랑 3 이 어떻게 다른 상황이지 지금?".

mmae evidence (사용자 인식 일부 정정):

- **dev 배포 실재**: `.github/workflows/cd-dev.yml` (develop 머지 → NCP SSH + `docker compose -f docker-compose.dev.yml up`) 실재. NCP VM 에 docker 컨테이너 5종 running (`mobruji-web-dev` / `mobruji-backend-dev` / `mobruji-nginx-dev` / `mobruji-mysql-dev` / `mobruji-mysql-local`, 19h healthy)
- **production 환경 부재**: `.github/workflows/cd-prod.yml` 또는 `cd-release.yml` 워크플로우 부재. release tag (`v0.4.0` 등) 는 GitHub 마일스톤 표시일 뿐 별도 production deploy 없음
- 단계 2 = dev 환경 회귀 검증 = **의미 있음** (cd-dev 실재 + `http://101.79.20.94/` live)
- 단계 3 = production 검증 = **무의미** (production 환경 부재 — production endpoint 가 존재하지 않으므로 검증 대상 자체가 없음)

사용자 결정 (2026-05-30): **단계 3 폐기 + 2 단계 구조 채택**. 영어 짧은 명명 (`Pre-merge review` / `Post-merge audit`).

향후 production 환경 신설 시 본 spec 의 단계 3 부활 가능 (별 cycle — `deployment-infrastructure.md` Hetzner CX22 머지 의존). 본 spec 은 현 시점 (dev 만 존재) 의 SoT.

## 2. 스코프 (2026-05-24 재확장 + 2026-05-30 2 단계 재구조)

- 대상 PR: **모든 type:* PR** (`type:feat` / `type:fix` / `type:docs` / `type:refactor` / `type:chore` / `type:style` / `type:test`)
- e2e 가능 판정:
  - **e2e 가능** (web/backend 코드 변경) → 단계 1/2 모두 실제 수행
  - **e2e 불가능** (docs/spec/refactor/chore 류 — 런타임 행동 변경 없음) → 각 단계에서 **no-op pass 판정**만 + `reviewed:claude` 라벨 부여
- rev sub-agent 가 2 단계 모두 수행 (no-op pass PR 은 단계 1 만 — 단계 2 skip)
- **모든 PR 은 rev 라벨 없이는 nmae 자율 머지 금지**. rev = mandatory checkpoint.

**Why 확장:** 2026-05-24 사용자: "rev를 거쳐야 (뭐 rev가 간단변경이라 할게 없다 판정을 내린다하더라도 거쳐서) 사이클이 진행되는거야 알겠지?" — docs/refactor PR 도 rev 사이클을 거쳐 mandatory checkpoint 통과 후 머지.

**단계 3 (구 release 후 production 검증) 폐기**: production 환경 부재 사유 (§1-1). 단계 3 관련 모든 박제 (라벨 `rev-prod-pass` / `regression:prod`, phase 3 rollout, 단계 3 SLA 7일, `release 후 production verified` 코멘트, `tools/rev-queue/round-summary.sh` 단계 3 branch 등) 폐기 처리. propagation cleanup 표: §10.

## 3. 기능 요구사항

### 3-1. 🟡 Pre-merge review (단계 1) — PR 머지 전

- rev 사이클 시작 시 `gh pr list --state open --search 'no:label:reviewed:claude'` 후보 발굴 — **모든 type:* 포괄** (라벨 필터 제거)
- e2e 가능 판정 (코드 diff 검사):
  - `web/**` 또는 `backend/**` 코드 변경 포함 → **e2e 가능**
  - `docs/**` / `*.md` / `.github/**` / spec / ADR / `tools/` 만 → **e2e 불가능** (no-op pass 후보)
- **scope:web 추가 trigger** (PR #1193 spec `docs/features/web-e2e-playwright.md` 도입 후):
  - `web/playwright.config.ts` **존재** + scope:web 코드 변경 → **e2e 의무** (Playwright headless 실행)
  - `web/playwright.config.ts` **부재** → 단계 1 **no-op pass** (e2e 인프라 미도입 사유 명시). impl PR 1 (`docs/features/web-e2e-playwright.md §10 후속 PR 1`) 머지 시점부터 의무로 전환.
  - 본 trigger 는 PR #1193 머지 시점 (2026-05-28) 부터 본 spec 안 명문화. impl PR 1 머지 시 본 항목을 "의무" 단일 라인으로 simplify 갱신 (후속 docs PR).
- **e2e 가능** PR:
  - scope:web → Playwright headless 또는 NCP dev 직접 호출
  - scope:backend → RestAssured E2E 또는 curl + 응답 검증
  - 통과 → `reviewed:claude` 라벨 + PR 코멘트 `✅ rev e2e PR pass`
  - 실패 → 후속 이슈 등록 + PR 코멘트 `❌ rev e2e PR fail: <원인>` + 머지 보류
- **e2e 불가능** PR (no-op pass 절차):
  - rev 가 diff 빠르게 audit (오타/형식/링크 깨짐 등 명백한 결함 없는지)
  - 통과 → `reviewed:claude` 라벨 + PR 코멘트 `📝 rev no-op pass — 변경 사소함, e2e 불요`
  - 결함 발견 → 후속 이슈 등록 + PR 코멘트 `❌ rev no-op audit fail: <원인>`
- 머지 게이트: **모든 PR** `reviewed:claude` 라벨 없으면 nmae 자율 머지 안 함
- **SLA**: 정규 type:* = **30분** / `type:docs` (no-op pass) = **5분** / `type:release` = **면제** (사용자 명시 확인) / `type:emergency-hotfix` = **면제** (whitelist 머지). 측정 시작 (T0) = `rev-queue.sh register <PR>` 호출 시각 (또는 `cycle-status.json` `rev.in_progress.started_at` / PR `createdAt` fallback). 측정 종료 = `reviewed:claude` 라벨 부착 시각 + 통과 코멘트 (✅/📝/❌) 부착 시각 중 늦은 쪽. SLA 미달성 시 `watchdog_rev_sla_loop` 1분 polling 이 DIGEST push + nmae 별 rev sub-agent 추가 launch — **차단이 아니라 가시화 + 큐 race 해소**. 상세 SoT: `docs/features/rev-sla.md §3-1`(SLA 매트릭스) + §3-2(T0/완료 시점 정의) + §3-3(`watchdog_rev_sla_loop`).

### 3-2. 🔵 Post-merge audit (단계 2) — develop 머지 후 dev 환경 회귀 검증

- **e2e 가능 PR 만 해당** — no-op pass PR 은 skip
- 후보 발굴: `gh pr list --state merged --base develop --search 'merged:>1h ago -label:rev-post-merge-pass -label:type:release'`
- develop 머지 직후 NCP dev deploy 사이클 완료까지 대기 (~5분, `cd-dev.yml` healthcheck 통과 후)
- **dev 환경 endpoint**: `http://101.79.20.94/` (web) + `http://101.79.20.94/api/v1/...` (backend) — `cd-dev.yml` 가 NCP VM 의 docker compose stack 5종 (`mobruji-web-dev` / `mobruji-backend-dev` / `mobruji-nginx-dev` / `mobruji-mysql-dev` / `mobruji-mysql-local`) 을 develop tip 으로 자동 갱신
- rev 가 단계 1 시나리오 동일 재실행 — **실제 dev 환경** (local 3-tier 가 아니라 NCP VM live)
- 통과 → PR 코멘트 `✅ rev e2e post-merge pass` + 라벨 `rev-post-merge-pass` (멱등성 표식)
- 실패 → 즉시 revert 이슈 등록 + `regression:dev` 라벨 + Discord push
- **SLA**: 정규 type:* = **24시간** / `type:release` = **24시간** / `type:emergency-hotfix` (정규) = **30분** (정규 단계 1 등급) / `type:emergency-hotfix` + body `security` (🔴 critical-public) = **15분**. T0 = PR `mergedAt` (GitHub API). 측정 종료 = `rev-post-merge-pass` 또는 `regression:dev` 라벨 부착 시각. security 분류 미달성 시 DIGEST + Discord 본 채널 + 사용자 reply 3 채널 동시 push — 정규 분류는 DIGEST 1 채널만. SoT: `docs/features/rev-sla.md §3-1` + §3-2 + §3-4(escalation 매트릭스).

### 3-3. (폐기) release 후 production 검증 — 단계 3

> **2026-05-30 폐기**: production 환경 부재 사유 (§1-1). 본 sub-section 은 향후 production 환경 신설 시 (`deployment-infrastructure.md` Hetzner CX22 머지) 부활 가능 marker 로 유지 — 본문 박제 없음.
>
> 폐기 evidence: `.github/workflows/` 에 `cd-prod.yml` / `cd-release.yml` 부재. release tag (`v0.4.0` 등) 는 GitHub 마일스톤 표시만, deploy 대상 production endpoint 없음.
>
> 단계 3 의 의도 (release 후 사용자 실제 환경 검증) 는 단계 2 (`Post-merge audit`) 가 NCP dev 환경에서 사용자 dogfooding 환경과 동일 endpoint 검증으로 흡수.

## 4. 비기능 요구사항

- 단계별 timeout: 단계 1 = 10분, 단계 2 = 5분
- 실패 시 재시도 1회 (transient 회피)
- 모든 단계 결과는 PR 코멘트 + cycle-status.json `rev.in_progress` 에 기록
- **SLA 관측성**: 모든 단계의 응답 evidence (T0 / 완료 / SLA 결과 / escalation) 는 `~/.mobruji/rev-sla-metrics.jsonl` append-only 박제. schema / 적재 룰 SoT: `docs/features/rev-sla.md §3-5`. 후속 회고 spec 가 본 jsonl 로 단계 1 SLA 달성률 / 평균 elapsed / P95 / escalation 발생률 / security 100% / 분류 분포 6 지표 집계 (`rev-sla.md §6-PR3 detailed design (5)`).
- **테스트 전략 cross-ref**: 본 spec 의 단계별 SLA 검증 시나리오는 `docs/features/rev-sla.md §7` (5 분류 매트릭스 검증 / `watchdog_rev_sla_loop` polling 멱등성 / Escalation 분기 / sub-agent self-query) 와 단일 SoT. 본 spec 의 §3-1/§3-2 SLA 행 갱신 시 `rev-sla.md §7` 동시 갱신 의무 (drift 방지).

## 5. 구현 계획

- [x] rev sub-agent prompt template §E-2 (단계 절차) 갱신 (`docs/ai-harness/actors/sub-agent.md`) — **PR #945 (2026-05-24 완료, 3 단계 구조)**
- [x] GitHub Actions `rev-gate.yml` check 신설 (라벨/코멘트 부재 시 머지 차단) — **PR #945 (2026-05-24 완료)**
- [x] rev 큐 스크립트 (`tools/rev-queue/`) + sub-agent prompt §E-3 (큐 discovery) — **PR #952 (2026-05-24 완료)**. 매 사이클 첫 액션으로 호출. 라벨 + 스크립트가 single source of truth
- [ ] CLAUDE.md §4 품질 게이트에 "모든 type:* PR 머지 전 rev 2 단계 e2e" 한 줄 — 별 PR (구 "rev 3단계 e2e" → "rev 2단계 e2e")
- [ ] rev e2e 시나리오 라이브러리 (`tools/rev-e2e/`) — 별 PR
- [ ] `rev-gate.yml` 을 `required_status_checks` 로 GitHub 브랜치 보호 설정 등록 — 별 PR (사용자 admin 작업)
- [ ] **propagation cleanup 후속 PR** (§10 SoT) — 단계 3 박제 정정 / 단계 명명 통일 (Pre-merge / Post-merge)

## 6. 마이그레이션 / rollout

- phase 1: 단계 1 (PR 머지 전) — 즉시 (완료)
- phase 2: 단계 2 (사후, dev 환경) — 완료 (#1008 자동 trigger loop 도입)
- ~~phase 3: 단계 3 (release 후) — release v0.4.0 부터~~ — **2026-05-30 폐기** (production 환경 부재)

향후 production 환경 신설 시 phase 3 부활 가능 — 별 cycle 트리거 (`deployment-infrastructure.md` Hetzner CX22 머지 의존).

## 7. 작업 분할 (rev2s rollout PR 시퀀스)

본 spec 머지 후 후속 PR 시퀀스 — 영향 분석 + 우선순위 sorting (§10 propagation cleanup 표 의존).

- [ ] **PR rev2s-1** (본 PR, plan / docs): 본 spec 신설 (`docs/features/rev-e2e-2-stages.md`) + 기존 spec rename (`git mv rev-e2e-3-stages.md rev-e2e-2-stages.md`). 영향 = 1 파일 rename + 본문 rewrite (단계 3 본문 제거 + 명명 변경).
- [ ] **PR rev2s-2** (plan / docs): propagation cleanup — `rev-sla.md` §3-1 매트릭스 / §3-2 T0 / §3-3 단계 3 행 정리 / `rev-qa-protocol.md` §5-9-3 / `rev-direct-qa-extension.md` 시나리오 3 / `sub-agent.md §2-rev` "3단계 e2e" → "2단계 e2e" + 단계 3 row 제거 / `web-e2e-playwright.md` / `visual-regression-ci.md` / `emergency-hotfix-flow.md` / `cycle-forum-placeholder-guard.md` / `security-disclosure-flow.md` / `rev-gate-audit-workflow.md` / `discord-reply-cycle-channel-forum-adapter.md` / `work-cycle-simplification.md` / `song-catalog-genre-browse.md` / `README.md` 의 `rev-e2e-3-stages` reference 일괄 `rev-e2e-2-stages` 로 갱신. 상세: §10.
- [ ] **PR rev2s-3** (be / infra, mmae 직접 가능): Discord `PR_REVIEW_FORUM_ID` 의 `available_tags` 4종 한국어 → 영어 정정 (`🟡 Pre-merge review` / `🔵 Post-merge audit` / `✅ rev pass` / `❌ rev fail`). NCP bot.py discord.py 코드 직접 정정.
- [ ] **PR rev2s-4** (be / infra): bot.py 코드 주석 정정 — `rev_post_merge_audit_loop` 등 함수 주석 / log message 의 "단계 3" / "rev-prod-pass" / "regression:prod" 박제 폐기.

### 보호 영역 변경 여부 (필수 명시)

- 보호 영역 변경 여부: ☐ 없음 (본 PR 한정 — docs only, rename + 본문 갱신)
- 후속 PR rev2s-3 / rev2s-4 는 bot.py 변경 — 보호 영역 X 이지만 daemon 변경 가중도

## 8. 단계 2 자동 trigger 구현 (#1008)

`bot.py` 의 `rev_post_merge_audit_loop` (5분 polling) 이 단계 2 후보 PR 을 발굴해
nmae tmux pane 에 audit launch 알림을 inject 한다.

- 후보 발굴: `gh pr list --state merged --base develop --search 'merged:>1h ago -label:rev-post-merge-pass'`
- inject: `[rev e2e post-merge] PR #N 단계 2 audit launch — develop deploy 후 시나리오 재실행`
- debounce: PR 별 15분 (반복 inject 차단)
- nmae 책임: 단계 2 통과 시 `rev-post-merge-pass` 라벨 부여 (이후 polling 에서 제외)
- env: `REV_POST_MERGE_AUDIT_LOOP=1` (default), `REV_POST_MERGE_AUDIT_INTERVAL_SECONDS=300`,
  `REV_POST_MERGE_AUDIT_INJECT_TARGET=mobruji:0.0`
- 단순화: gh CLI 실패 / 후보 0 / tmux 부재 시 graceful skip — 데몬 영구 dead 방어

## 9. 결정 로그

- 2026-05-23: 초안 작성 (status=draft). PR #851 voice-range 404 fix 머지 lag 사고 + release 전 rev 게이트만 존재 사례 박제.
- 2026-05-24: phase 1 (단계 1) 즉시 적용 + #1008 단계 2 자동 trigger loop 도입.
- 2026-05-28: phase 3 단계 3 (release 후) 룰 박제 — release v0.4.0 부터 시작.
- **2026-05-30 (본 PR rev2s-1)**: **3 단계 → 2 단계 재구조** + spec rename (`rev-e2e-3-stages.md` → `rev-e2e-2-stages.md`). 사용자 evidence-based 결정: production 환경 부재 (`.github/workflows/cd-prod.yml` / `cd-release.yml` 부재 + release tag 는 마일스톤 표시만) 사유로 단계 3 폐기. 단계 1 / 단계 2 만 유지 + 영어 짧은 명명 (`Pre-merge review` / `Post-merge audit`) 채택. 사용자 표현: "영어 짧은 거 좋은데, 우리는 지금 dev 는 배포가 따로 안 되어 있어서. 2 랑 3 이 어떻게 다른 상황이지 지금?" — 사용자의 "dev 배포 부재" 인식은 mmae evidence (cd-dev.yml + NCP VM 5 컨테이너 19h healthy) 로 정정. 단계 2 = dev 환경 회귀 = 의미 있음 / 단계 3 = production 검증 = 무의미 결론. propagation cleanup 표는 §10 — 후속 PR rev2s-2 / rev2s-3 / rev2s-4 로 분할.

## 10. propagation cleanup 표 (PR rev2s-2 의무 갱신 대상)

본 spec 머지 후 후속 PR rev2s-2 가 일괄 갱신할 파일 + 라인 추정.

### 10-1. SoT 영향 docs

| 파일 | 박제 위치 (예상) | 변경 종류 | 라인 추정 |
|---|---|---|---|
| `docs/features/rev-sla.md` | §3-1 매트릭스 단계 3 row / §3-2 T0 단계 3 / §3-3 단계 3 SLA / §3-4 escalation 정규 단계 3 row / §6-PR6 §C (rev-e2e-3-stages §3-3 박제 위치) / §6-PR6 §D §7 관련 / §8-closure-candidates §A row "단계 1" 유지 (단계 3 박제 없음 추정) | 단계 3 row 제거 + `rev-e2e-3-stages.md` → `rev-e2e-2-stages.md` slug 갱신 | 15-20 |
| `docs/features/rev-qa-protocol.md` | §5-9-3 (단계 3 — production smoke) 전 sub-section 폐기 + §5-9-5 wrapper 출력 format 의 "단계 3 (release 후)" row 제거 | sub-section 1개 + format 1줄 제거 | 10-15 |
| `docs/features/rev-direct-qa-extension.md` | 시나리오 3 (단계 3 release 후 production) / 매트릭스 row C (production smoke) / 후속 (production 머지 후) row / Endpoint 매트릭스 (단계 3) / PR 4 (production 구축 후 후보 C) | "단계 3" / production smoke 박제 전부 폐기 또는 "단계 3 부활 시 별 cycle" marker 로 다운그레이드 | 8-12 |
| `docs/features/web-e2e-playwright.md` | §1 개요 (rev #1174 단계 3 e2e), §3 시나리오 2 (단계 1 / 단계 2 / 단계 3), §5-7 (§3-3 단계 3 release 후), §10 후속 PR rev-e2e-3-stages §3 갱신 | 단계 3 박제 폐기 + slug 갱신 | 10-15 |
| `docs/features/visual-regression-ci.md` | 단계 3 production smoke row / §결정 로그 단계 3 박제 | 단계 3 row 제거 (production 환경 부재 marker 추가) | 5-8 |
| `docs/features/emergency-hotfix-flow.md` | `rev-e2e-3-stages.md §3-2` cross-ref | slug 갱신 | 2-3 |
| `docs/features/security-disclosure-flow.md` | `rev-e2e-3-stages.md §3-2` cross-ref / PR 5 (`rev-e2e-3-stages.md §3-2` cross-ref 보강) | slug 갱신 | 2-3 |
| `docs/features/rev-gate-audit-workflow.md` | `rev-e2e-3-stages.md §3-2` cross-ref | slug 갱신 | 1-2 |
| `docs/features/discord-reply-cycle-channel-forum-adapter.md` | "rev 사이클 3단계 audit" 박제 | "2단계 audit" + slug 갱신 | 1-2 |
| `docs/features/work-cycle-simplification.md` | "rev-e2e-3-stages.md" cross-ref + "rev 3단계 e2e" 박제 | slug 갱신 + "2단계" 갱신 | 2-3 |
| `docs/features/song-catalog-genre-browse.md` | "rev e2e 3단계 (rev-e2e-3-stages.md)" 박제 2건 | slug 갱신 + "2단계" 갱신 | 2 |
| `docs/features/cycle-forum-placeholder-guard.md` | "단계 3 production 검증은 release PR 단계에서" 박제 | "단계 3 폐기 (production 환경 부재)" 갱신 | 1 |
| `docs/features/README.md` | rev-e2e-3-stages row | slug + title 갱신 ("3단계" → "2단계 Pre-merge/Post-merge") | 1 |
| `docs/features/spec-status-check-legacy-key-fail.md` | "rev-e2e-3-stages.md frontmatter" 박제 (PR #1169 사례) | 사례 박제 유지 (legacy 사례 — 갱신 불필요), 단 본문에 rename 사실 1줄 추가 | 1 |
| `docs/ai-harness/actors/sub-agent.md` | §2-rev "3단계 e2e" / 단계 3 row / `rev-e2e-3-stages.md` cross-ref | "2단계 e2e" + 단계 3 row 제거 + slug 갱신 | 3-5 |
| `docs/ai-harness/actors/nmae.md` | "rev-e2e-3-stages" 박제 (있다면) | slug 갱신 | 1-2 |
| `docs/ai-harness/14-discord-ops.md` | `available_tags` 4종 한국어 박제 (예상) | 영어 명명 정정 (별 PR rev2s-3 본 PR 과 동기) | 2-3 |
| `docs/ai-harness/00-MANIFEST.md` | "rev-e2e-3-stages" 박제 (있다면) | slug 갱신 | 1-2 |
| `docs/ai-harness/06-domain-model.md` | "rev-e2e-3-stages" 박제 / `RevSlaTarget` 단계 3 SLA 등재 | slug 갱신 + 단계 3 SLA 박제 폐기 | 2-3 |
| `docs/ai-harness/11-multi-session-runbook.md` | "rev-e2e-3-stages" 박제 | slug 갱신 | 1-2 |

**라인 합계 추정**: 약 60-90 라인 (단일 PR rev2s-2 처리 가능).

### 10-2. ADR 영향

| 파일 | 박제 위치 | 변경 종류 |
|---|---|---|
| `docs/decisions/0019-event-driven-architecture-v2.md` | "rev-e2e-3-stages" reference (있다면) | slug 갱신 |
| `docs/decisions/0021-worktree-count-evaluation.md` | "rev-e2e-3-stages" reference (있다면) | slug 갱신 |
| `docs/decisions/0026-visual-regression-ci.md` | "rev-e2e-3-stages" reference (있다면) | slug 갱신 |

### 10-3. 코드 영향 (PR rev2s-4 의무)

| 파일 | 박제 위치 (예상) | 변경 종류 |
|---|---|---|
| `tools/discord-daemon/bot.py` | `rev_post_merge_audit_loop` 함수 주석 / log message / `rev-e2e-3-stages` reference / 단계 3 관련 dead code | 주석 + log 정정. 단계 3 관련 함수 (있다면) 폐기 또는 향후 부활 marker 처리 |
| `tools/rev-queue/round-summary.sh` | "단계 3 (release 후)" branch (`rev-qa-protocol.md §5-9-5` 박제) | 단계 3 branch 제거 |
| `tools/rev-queue/rev-queue.sh` | "stage 3" 또는 "단계 3" 박제 (있다면) | 제거 |
| `tools/rev-queue/README.md` | 단계 3 박제 (있다면) | 제거 |

### 10-4. Discord forum 영향 (PR rev2s-3 의무)

| 대상 | 변경 |
|---|---|
| `PR_REVIEW_FORUM_ID` `available_tags` | 4종 한국어 → 영어 정정: `🟡 1차 review` → `🟡 Pre-merge review` / `🔵 사후 E2E QA` → `🔵 Post-merge audit` / `✅ rev pass` 유지 / `❌ rev fail` 유지 |
| `REV_FORUM_ID` thread 명명 (있다면) | "rev 3단계" / "stage 3" 박제 제거 |

### 10-5. 메모리 영향 (자율 갱신 — drift 발견 시)

| 메모리 | 박제 위치 | 변경 종류 |
|---|---|---|
| `feedback_rev_e2e_always.md` (있다면) | "3단계 e2e" 박제 | "2단계 e2e" 갱신 |
| `feedback_rev_release_gate.md` (있다면) | "단계 3" 박제 | "단계 3 폐기" 갱신 |

메모리는 사용자 canonical 영역이 아니므로 mmae / helper 가 다음 세션 doc-check (CLAUDE.md §15) 에서 발견 시 자율 갱신.

## 11. 오픈 질문

| # | 질문 | 선택지 | 담당/기한 |
|---|---|---|---|
| Q1 | 본 spec rename 후 기존 slug `rev-e2e-3-stages` 의 backward compat redirect 필요한가 (외부 메모리 / Discord 메시지 / git history 에서 잔존 link)? | (a) 불필요 (git mv 만, history 추적 가능) / (b) `docs/features/rev-e2e-3-stages.md` 를 1줄 stub 으로 남기고 본 spec 으로 redirect 박제 | @goohong / 본 PR rev2s-1 머지 전 |
| Q2 | propagation cleanup PR rev2s-2 의 라인 합계 60-90 — 단일 PR 처리 vs SoT docs 우선 + 도메인 spec 후속 분할 | (a) 단일 PR (권고) / (b) SoT (rev-sla.md / rev-qa-protocol.md / sub-agent.md) + 도메인 (나머지) 2 PR 분할 | @goohong / PR rev2s-1 머지 후 |
| Q3 | bot.py `rev_post_merge_audit_loop` 함수 명 — 단계 3 폐기 후에도 "post_merge_audit" 의미는 단계 2 SoT 유지 (rename 불필요) vs `rev_stage2_audit_loop` 로 명명 통일 | (a) 유지 (의미 명확) / (b) rename (단계 명명 통일) | @goohong / PR rev2s-4 launch 전 |
| Q4 | 향후 production 환경 신설 시 단계 3 부활 — 본 spec rename (`rev-e2e-3-stages.md` 부활) vs 별 spec 신설 (`rev-e2e-prod-verification.md` 등) | (a) 본 spec rename / (b) 별 spec | @goohong / production 환경 신설 cycle 트리거 시 |

## 12. 관련

- 이슈 #882, #879 (원인 PR #851), #1008 (단계 2 자동 trigger)
- 메모리 [[feedback-rev-e2e-always]] [[feedback-rev-release-gate]] [[feedback-role-expansion]] [[feedback-evidence-based-root-cause]]
- **SLA spec cross-ref**: `docs/features/rev-sla.md` (PR #1340) — §3-1 SLA 목표값 매트릭스 / §3-2 측정 시작·종료 시점 / §3-3 `watchdog_rev_sla_loop` (`nmae-cycle-watchdog.md` 4중 → 5중 안전망 확장) / §3-4 escalation 채널 매트릭스 / §3-5 `rev-sla-metrics.jsonl` schema / §3-6 sub-agent self-query 도구 / §6-PR3 detailed design (5 sub-section). 본 spec 의 §3 SLA 행 = `rev-sla.md` mirror — 동시 갱신 의무 (`rev-sla.md §9` 2026-05-29 plan round 14 PR 6 prototype 동기).
- **이전 spec**: `rev-e2e-3-stages.md` (본 spec rename 전, 단계 3 박제). git history 추적 가능.
- 다음 plan 사이클에서 PR rev2s-2 (propagation cleanup) launch
