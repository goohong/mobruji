---
feature: 단계 2 재정의 — dev 배포 E2E 검증 (배포본 E2E 점검)
slug: stage2-dev-deploy-e2e
status: draft
owner: @goohong
scope: infra
related_issues: [1448, 1453, 1455]
related_prs: []
last_reviewed: 2026-06-02
---

# 단계 2 재정의 — dev 배포 E2E 검증 (배포본 E2E 점검)

> **본 spec 의 위치**: `rev-e2e-2-stages.md` 의 **단계 2 (구 "🔵 Post-merge audit / 머지 후 회귀 검토")** 를 본 spec 으로 **재정의**합니다. 단계 1 (Pre-merge review) 은 그대로 유지, 단계 3 (release 후 production 검증) 은 폐기 상태 그대로 유지 (production 환경 부재 — `rev-e2e-2-stages.md §1-1`). 본 spec 은 단계 2 의 **무엇을·어떻게** 를 정밀화하는 SoT 이고, 단계 구조 전체의 SoT 는 여전히 `rev-e2e-2-stages.md` 입니다.

## 1) 개요 (What / Why)

현재 단계 2 는 "머지 후 회귀 검토" 라는 이름으로, rev sub-agent 가 develop 머지 후 **PR 관련 테스트/시나리오를 재실행** 하도록 정의돼 있다 (`tools/agent/agent.py` `handle_post_merge_review_requested` task 본문: "이 PR 관련 테스트/시나리오를 재실행"). 이 정의에는 두 가지 결함이 있다.

1. **용어가 어색하다** — "회귀 검토" / "감사(audit)" 는 무엇을 검증하는지 직관적으로 드러나지 않는다.
2. **단위 테스트 재실행 = 무의미한 중복** — 단계 1 (Pre-merge review) 이 이미 PR 브랜치에서 단위·E2E 를 돌렸고, CI 가 머지 전 같은 테스트를 통과시켰다. develop 머지 후 같은 단위 테스트를 다시 돌리는 것은 **코드가 바뀌지 않은 동일 대상을 다시 검증**하는 것이라 새로운 정보를 주지 못한다.

**재정의 (사용자 정정 2026-06-02)**: 단계 2 는 단위 테스트 재실행이 아니라, develop 에 **dev 배포가 끝난 뒤** 그 **배포본 자체** 를 대상으로 E2E 를 돌려 **통합·배포 회귀** 를 잡는 단계다. 즉 "코드가 통과하는가" 가 아니라 "**배포된 결과물이 실제 dev 환경에서 동작하는가**" 를 본다 — 단위 테스트가 가릴 수 없는 사각지대 (docker compose 구성, nginx 라우팅, 환경 변수, 컨테이너 간 연동, 마이그레이션 반영, 빌드 산출물 차이) 를 배포본 E2E 로 검증한다.

**용어 정정**: "머지 후 회귀 검토" / "회귀 검토" / "Post-merge audit" → **"dev 배포 E2E 검증"** (짧게 "배포본 E2E 점검"). '감사' 표현은 사용하지 않는다.

대상 액터: **rev sub-agent** (주 사용자) / nmae (머지 결정, dev 배포 완료 감지) / fe·be sub-agent (E2E 시나리오 소유 — 본 spec 범위 밖).

## 2) 사용자 시나리오

본 기능의 직접 사용자 = 개발 사이클 액터 (rev / nmae). end-user 가시화는 없다.

1. **rev sub-agent — dev 배포 E2E 검증 (e2e 가능 PR)**: PR 이 develop 에 머지되고 `cd-dev.yml` 이 NCP VM 에 배포·healthcheck 통과한 뒤, rev 가 **dev 배포본** (`http://101.79.20.94/`) 을 대상으로 — 브라우저 가능 항목은 Playwright (`PLAYWRIGHT_BASE_URL`=dev), 브라우저 불가 항목은 프론트 호출 (API) E2E 로 — 배포 회귀가 없는지 확인한다. 통과 시 `rev-post-merge-pass` 라벨 → 재알림 loop 자연 종료.

2. **rev sub-agent — e2e 불가능 PR (docs/spec/chore 류)**: dev 배포 대상 코드 변경이 없으므로 단계 2 를 **skip** (단계 1 no-op pass 로 이미 종결). 단계 2 후보 발굴 자체에서 제외된다.

3. **nmae — dev 배포 완료 감지**: bridge merge 감지 loop 가 `post_merge_review_requested` event 를 발행하면, rev 큐에 dev 배포 E2E task 가 적재된다. rev 는 배포 완료 (healthcheck green) 를 확인한 뒤 검증을 시작한다.

## 3) 요구사항

### 기능 요구사항

- [ ] 단계 2 의 rev task 정의를 **"PR 관련 테스트/시나리오 재실행"** → **"dev 배포본 대상 E2E 실행"** 으로 교체 (`tools/agent/agent.py` `handle_post_merge_review_requested` task 본문 — §5-4 배선)
- [ ] **브라우저 가능** 항목 → Playwright 를 `PLAYWRIGHT_BASE_URL`=dev 로 실행 (`web-e2e-playwright.md` / `rev-browser-e2e-env.md` 스위트 재사용)
- [ ] **브라우저 불가** 항목 → 프론트 호출 (API) E2E — `web/lib/api/` 클라이언트 호출 또는 직접 HTTP (`curl` / RestAssured) 를 dev endpoint 대상으로
- [ ] dev 배포 완료 감지 — `cd-dev.yml` healthcheck green 확인 후 검증 시작 (배포 미완 상태 검증 = false negative 방지)
- [ ] 통과 → `rev-post-merge-pass` 라벨 + PR 코멘트 `✅ dev 배포 E2E 검증 pass` / 실패 → `regression:dev` 라벨 + revert 후속 이슈 + Discord push
- [ ] e2e 불가능 PR (코드 변경 없음) 은 단계 2 후보에서 제외 (skip)
- [ ] 용어 정정 propagation — "회귀 검토" / "Post-merge audit" → "dev 배포 E2E 검증" (§6 propagation)

### 비기능 요구사항

- **타임아웃**: dev 배포 E2E = `rev-e2e-2-stages.md §4` 단계 2 타임아웃 (현 5분) 안. Playwright smoke (5-7건 chromium-only) 3분 이하 목표.
- **배포 의존**: dev 배포가 완료되지 않았거나 dev endpoint 가 미응답이면 검증을 false fail 처리하지 말고 **배포 대기 후 재시도** (transient 회피, 1회 재시도) 하거나 graceful "배포 미완 — 재큐" 처리.
- **격리/멱등성**: `rev-post-merge-pass` 라벨이 멱등성 표식 — 이미 붙은 PR 은 후보에서 제외 (재알림 loop 자연 종료). rev 는 파일 수정 금지 (read-only 검증).
- **데이터 안전**: dev 환경은 사용자 dogfooding 환경과 동일 endpoint 이므로 mutation 플로우는 dev 전용 테스트 데이터로만, 누적 오염 회피.
- **관측성**: 단계별 결과는 PR 코멘트 + `cycle-status.json` `rev.in_progress` + `rev-sla-metrics.jsonl` (`rev-sla.md §3-5`) 박제. live URL 실패는 trace artifact 경로 첨부.

## 4) 범위 / 비범위 (중요)

### 포함

- 단계 2 의 **정의 교체** — 단위 테스트 재실행 → dev 배포본 E2E
- E2E 범위 2분류 (브라우저 가능 = Playwright / 브라우저 불가 = 프론트 호출·HTTP) 의 판정 기준 (§5-3)
- 기존 `post_merge_review_requested` event → rev 큐 배선 (#1448) 재사용 — rev **task 본문** 만 dev 배포 E2E 로 교체 (§5-4)
- dev 배포 완료 감지 / 배포 의존 절차
- 용어 정정 propagation (§6)

### 제외 (Out of Scope)

- **단계 1 (Pre-merge review) 의 정의** — 그대로 유지. 본 spec 은 단계 2 만.
- **단계 3 (release 후 production 검증)** — production 환경 부재로 폐기 상태 유지 (`rev-e2e-2-stages.md §1-1`). 환경 신설 시 별도 부활.
- **`web/e2e/` Playwright 스위트 시나리오 내용 작성** — `web-e2e-playwright.md` 소관 (fe).
- **`web/playwright.config.ts` / `PLAYWRIGHT_BASE_URL` env 분기 config 작성** — `web-e2e-playwright.md` / `rev-browser-e2e-env.md` 소관.
- **rev 워크트리 브라우저 binary 셋업 절차** — `rev-browser-e2e-env.md` 소관. 본 spec 은 "무엇을 검증하는가" 를 정의하고, "어느 환경에서 어떻게 구동하는가" 는 그 spec 에 위임.
- **dev 배포 메커니즘 (`cd-dev.yml`) 자체의 구축/수정** — 이미 실재 (§5-5). 본 spec 은 그 결과물을 검증 대상으로 삼을 뿐.
- **E2E 실패 시 자동 롤백** — `cd-dev.yml` 은 healthcheck 실패 시 직전 SHA 롤백을 이미 한다. rev 의 배포본 E2E 실패는 healthcheck green 이후 발견되는 **기능 회귀** 이므로 자동 롤백 대상인지 = **오픈 질문 Q4**.

## 5) 설계

### 5-1) 도메인 모델

해당 없음. 본 기능은 rev 사이클 인프라 (검증 정의) 이며 product 도메인 엔티티 변경 없음 — `06-domain-model.md §4` 유비쿼터스 랭귀지 신규 등재 불요. 단, 06-domain-model 에 단계 2 명명이 박제돼 있으면 (`rev-post-merge-pass` 설명 등) 용어 정정 propagation (§6) 대상.

### 5-2) API 엔드포인트

해당 없음. rev 가 기존 dev 배포된 endpoint 를 호출·검증할 뿐 신규 API 도입 없음.

dev 배포본 검증 대상 endpoint (예시):
- web: `http://101.79.20.94/` (+ `/recommend` `/songs` `/voice-range` 등 라우트)
- backend (API E2E): `http://101.79.20.94/api/v1/...` (nginx 프록시 경유)

### 5-3) E2E 범위 2분류 — 판정 기준

본 spec 의 핵심. rev 가 PR 변경을 보고 어떤 E2E 채널로 검증할지 정한다.

| 분류 | 검증 채널 | 대상 항목 (예) | 도구 |
|---|---|---|---|
| **브라우저 가능** | Playwright (`PLAYWRIGHT_BASE_URL`=dev) | 페이지 렌더, 클라이언트 라우팅, CTA 동작, 다크/라이트 토큰, JS 런타임 에러, 페이지 전이 | `PLAYWRIGHT_BASE_URL=http://101.79.20.94 npx playwright test --grep @smoke` (스위트 = `web-e2e-playwright.md` 산출물 재사용) |
| **브라우저 불가** | 프론트 호출 (API) E2E | API 계약 (status/스키마), 추천 결과 정합성, 비가시 동작 (캐시·정렬·필터 로직), 마이그레이션 반영, BE-FE 연동 | `web/lib/api/` 클라이언트 함수 직접 호출 (node script) **또는** 직접 HTTP (`curl http://101.79.20.94/api/v1/...` + 응답 검증 / RestAssured) |

**판정 기준 (rev 가 적용)**:
- 변경이 **사용자 가시 화면 (렌더/라우팅/CTA/스타일)** 에 영향 → 브라우저 가능 → Playwright
- 변경이 **API 계약·추천 로직·데이터 정합성 등 비가시 동작** → 브라우저 불가 → 프론트 호출·HTTP
- 한 PR 이 **양쪽 다** 건드리면 두 채널 모두 실행
- `web/playwright.config.ts` 부재 (아직 미도입) → 브라우저 가능 항목도 잠정 프론트 호출·HTTP fallback (config 도입 시점부터 Playwright 의무) — `web-e2e-playwright.md §10` impl PR 1 머지 의존

> 판정 기준의 명확화·자동화 가능성은 **오픈 질문 Q2**.

### 5-4) 데이터 흐름 / 배선 (#1448 event → rev 큐 재사용)

이미 #1448 (PR #1447) 이 만든 배선을 **그대로 재사용** 하되, rev task 본문의 **"무엇을 하는가"** 만 dev 배포 E2E 로 교체한다.

```
[bridge] develop merge 감지 (rev_post_merge_audit_loop, 5분 polling)
   └─ rev-post-merge-pass 라벨 없는 merged PR 발굴
   └─ append_agent_event("post_merge_review_requested", {pr_number, pr_url, pr_title})

[agent] handle_post_merge_review_requested
   └─ rev 큐에 directive "rev-postmerge-{num}" 적재  ← 배선 재사용 (변경 없음)
   └─ task 본문 = "dev 배포본 E2E 실행"  ← ★ 본 spec 이 교체하는 부분

[dispatcher] rev sub-agent 실행
   └─ dev 배포 완료(healthcheck green) 확인
   └─ E2E 범위 2분류 판정 (§5-3)
   │    ├─ 브라우저 가능 → PLAYWRIGHT_BASE_URL=dev npx playwright test --grep @smoke
   │    └─ 브라우저 불가 → web/lib/api 호출 또는 curl dev endpoint + 응답 검증
   └─ 통과 → rev-post-merge-pass 라벨 + ✅ 코멘트 → bridge 검색 대상 제외 → loop 종료
   └─ 실패 → regression:dev 라벨 + revert 후속 이슈 + Discord push
```

**현 `agent.py` task 본문 (교체 대상)**:
```
PR #{n} ... 머지 후 회귀 검토(단계 2).
절차: 최신 develop 을 checkout 해 이 PR 관련 테스트/시나리오를 재실행하고 dev 환경 회귀가 없는지 확인.
```
→ **교체 후 (개념)**: develop checkout + 단위 테스트 재실행이 아니라, **dev 배포본 (NCP live) 대상 E2E** — 브라우저 가능은 Playwright(`PLAYWRIGHT_BASE_URL`=dev), 불가는 프론트 호출·HTTP. 단위 테스트 재실행 문구 제거. (정확한 문안은 impl PR — 본 spec 은 개념 정의.)

### 5-5) dev 배포 의존 — `cd-dev.yml` 확인

dev 배포 메커니즘은 **이미 실재** — 선행 구축 불요.

- `.github/workflows/cd-dev.yml`: `push: branches: [develop]` (paths `backend/**` `web/**` `docker-compose.dev.yml` `nginx/**` 등) → NCP SSH → `git reset --hard origin/develop` → `docker compose -f docker-compose.dev.yml build/up` → `/actuator/health/liveness` 30회 polling (총 150s) → 통과 OK / 실패 시 직전 SHA 롤백.
- **dev URL**: `http://101.79.20.94/` (web, nginx 경유) + `http://101.79.20.94/api/v1/...` (backend). canonical 출처 = `ncp-dev-deployment.md`.
- **배포 완료 감지**: `cd-dev.yml` 의 GitHub Actions run 성공 (또는 healthcheck step green) = 배포 완료 신호. rev 는 이를 확인한 뒤 검증 시작.
- **경계 케이스**: `cd-dev.yml` paths 필터에 `web/**` `backend/**` 가 포함되므로 docs-only PR 은 dev 배포 자체가 trigger 되지 않는다 → 단계 2 skip 과 일관 (§5-3 e2e 불가능 = skip).

> dev 배포 완료를 rev 가 **어떻게 감지** 하는가 (Actions API polling / healthcheck 직접 / 고정 대기) = **오픈 질문 Q3**.

### 5-6) 프론트엔드 화면

해당 없음. 화면 변경 없음 — rev 는 기존 dev 배포 화면을 검증만 한다.

## 6) 작업 분할 (예상 PR 리스트)

> 본 spec 자체는 docs. dev 배포 E2E 의 실제 구동은 `web-e2e-playwright.md` impl PR 1 (config + `PLAYWRIGHT_BASE_URL` env 분기) 머지에 일부 의존 (브라우저 가능 분류). 브라우저 불가 (HTTP) 분류는 즉시 가능.

- [x] **본 PR (type:docs scope:infra)**: `docs/features/stage2-dev-deploy-e2e.md` spec 신설 + 단계 2 재정의
- [ ] **impl PR 1 (type:docs scope:infra)**: `rev-e2e-2-stages.md §3-2` 본문 — "머지 후 회귀 검토 / Post-merge audit / 테스트 재실행" → "dev 배포 E2E 검증 (배포본 E2E 점검)" + E2E 2분류 절차 + 본 spec cross-ref. §1-1 / 결정 로그 명명도 동기.
- [ ] **impl PR 2 (type:feat scope:infra)**: `tools/agent/agent.py` `handle_post_merge_review_requested` task 본문 교체 (§5-4) — 단위 테스트 재실행 문구 제거, dev 배포본 E2E (Playwright dev / HTTP dev) 지시로. `directive_id` `rev-postmerge-{n}` 배선·라벨은 변경 없음. 관련 test (`test_post_merge_review_handler.py`) task 문구 assertion 갱신.
- [ ] **impl PR 3 (type:docs scope:infra)**: 용어 propagation — `tools/discord-daemon/bot.py` 주석·log·`available_tags` ("Post-merge audit" → "dev 배포 E2E 검증") / `tools/rev-queue/` (rev-queue.sh stage2 주석 / README) / `sub-agent.md §2-rev` / `11-multi-session-runbook.md` stage2 row / `06-domain-model.md` `rev-post-merge-pass` 설명 / `rev-e2e-2-stages.md` 잔여. 라벨 명 `rev-post-merge-pass` 는 **유지** (멱등성 표식 — rename 시 배선 대량 변경, Q5).

### 보호 영역 변경 여부 (필수 명시)

- 보호 영역 변경 여부: ☑ 없음 (본 PR 한정 — docs only, spec 신설).
- impl PR 2 의 `tools/agent/agent.py` / impl PR 3 의 `bot.py` 는 보호 영역 아님이나 daemon 변경 가중도 (rev 사이클 추가 신중도). `.github/workflows/cd-dev.yml` 은 **읽기만** — 본 spec 은 변경하지 않는다.

## 7) 테스트 전략

본 spec 자체는 docs 이므로 검증 = "후속 impl PR 의 rev task 가 dev 배포본 E2E 를 실제 수행하고 라벨/코멘트를 남기는가".

- **단위/통합**: impl PR 2 의 `test_post_merge_review_handler.py` 가 교체된 task 문구 ("dev 배포" / "Playwright" / "HTTP" 등) 를 assert 하도록 갱신.
- **E2E (배선 sanity)**: web-e2e-playwright impl PR 1 머지 후, develop 에 web 변경 PR 1건 머지 → bridge event → rev 큐 적재 → rev 가 `PLAYWRIGHT_BASE_URL`=dev 로 실제 dev 배포본 검증 + `rev-post-merge-pass` 부여 + loop 종료 1 사이클 sanity ([[feedback-verify-and-iterate]]).
- **실패 회귀 가드**: dev 에 의도적으로 깨진 변경을 배포했을 때 rev 배포본 E2E 가 `regression:dev` 를 실제 잡는지 1회 sanity.
- **mock 전략**: dev 배포본 대상이므로 mock 불요 (실제 BE 호출). 이것이 단계 1 (mock 가능) 과의 차별점.

## 8) 오픈 질문

> 결정 필요 — nmae(사용자)가 답하면 §9 결정 로그로 이동. 본 spec 의 핵심 미결 지점.

| # | 질문 | 선택지 | 담당/기한 |
|---|---|---|---|
| Q1 | dev 배포 trigger / URL 의 canonical 값 | (a) `http://101.79.20.94/` 직접 하드코딩 / (b) env (`PLAYWRIGHT_DEV_URL` / `DEV_BASE_URL`) 주입 — `ncp-dev-deployment.md` 실제 URL 과 동기 | @goohong / impl PR 2 전. 권장 = (b) 하드코딩 회피 |
| Q2 | 브라우저 가능 vs 브라우저 불가 **분류 기준** | (a) rev sub-agent 가 diff 보고 매번 판단 (휴리스틱) / (b) PR scope 라벨 기반 자동 (`scope:web`=브라우저, `scope:recommendation`/`scope:song`=API) / (c) 변경 파일 경로 기반 (`web/app/**`=브라우저, `backend/**`+`web/lib/api/**`=API) | @goohong / impl PR 2 전 |
| Q3 | dev 배포 **완료 대기 방식** | (a) GitHub Actions `cd-dev` run 성공 polling (API) / (b) dev healthcheck endpoint 직접 polling (`curl .../actuator/health/liveness`) / (c) 고정 대기 (~5분 후 시작, 현 룰) | @goohong / impl PR 2 전. 권장 = (a) 또는 (b) — 고정 대기는 false negative 위험 |
| Q4 | dev 배포 E2E **실패 시 자동 롤백** 여부 | (a) 롤백 없음 — `regression:dev` 라벨 + revert 후속 이슈만 (현 단계 2 룰) / (b) rev 가 dev 를 직전 머지 SHA 로 자동 롤백 (`cd-dev.yml` workflow_dispatch + 이전 SHA) / (c) Discord 사용자 확인 후 수동 롤백 | @goohong / impl PR 2 전 |
| Q5 | 라벨 명 `rev-post-merge-pass` — 재정의 후 rename 여부 | (a) 유지 (배선 대량 변경 회피, 멱등성 표식 의미 충분) / (b) `dev-deploy-e2e-pass` 등으로 rename (bot.py / rev-queue / agent.py / domain-model 동시 갱신) | @goohong / impl PR 3 전. 권장 = (a) |
| Q6 | `web/playwright.config.ts` 미도입 동안 (브라우저 가능 항목) 처리 | (a) 프론트 호출·HTTP fallback 으로 부분 검증 / (b) 단계 2 자체를 web-e2e-playwright impl PR 1 머지까지 no-op pass | @goohong / impl PR 1 전. 권장 = (a) |

## 9) 결정 로그

> 연대기 순. "YYYY-MM-DD: 결정 / 이유 / 출처(PR 번호 등)"

- 2026-06-02: 초안 작성 (status=draft). 사용자 정정 — 단계 2 ("머지 후 회귀 검토") 의 (1) 용어 어색 + (2) 단위 테스트 재실행 = pre-merge rev 중복 무의미 사유로 **폐기 대신 재정의** 지시. 올바른 단계 2 = develop dev 배포 후 **배포본 E2E** (브라우저 가능 = Playwright `PLAYWRIGHT_BASE_URL`=dev / 브라우저 불가 = 프론트 호출·HTTP) 로 통합·배포 회귀 검증. 용어 = "dev 배포 E2E 검증" / "배포본 E2E 점검" ('감사' 금지). 배선 = #1448 (PR #1447) `post_merge_review_requested` event → rev 큐 재사용, rev task 본문만 교체. dev 배포 = `cd-dev.yml` 실재 (선행 구축 불요). 단계 1 유지 / 단계 3 폐기 유지.

## 10) 관련

- **단계 구조 SoT**: `docs/features/rev-e2e-2-stages.md` (§3-2 = 본 spec 으로 재정의되는 단계 2 / §1-1 단계 3 폐기 근거)
- **스위트·config 소관**: `docs/features/web-e2e-playwright.md` (`web/e2e/` Playwright 스위트 + `PLAYWRIGHT_BASE_URL` env 분기 config)
- **rev 실행 환경 소관**: `docs/features/rev-browser-e2e-env.md` (rev 워크트리 브라우저 셋업 + 단계 2 live URL 재실행 규약)
- **SLA**: `docs/features/rev-sla.md` (단계 2 타임아웃·escalation·`rev-sla-metrics.jsonl`)
- **배포**: `docs/features/ncp-dev-deployment.md` (dev URL canonical) + `.github/workflows/cd-dev.yml` (배포 메커니즘 — 읽기만)
- **배선**: 이슈 #1448 (PR #1447 — `post_merge_review_requested` event → rev 큐) / `tools/agent/agent.py handle_post_merge_review_requested` / `tools/discord-daemon/bot.py rev_post_merge_audit_loop`
- **운영 이슈**: #1453 (NCP 과부하 — dev 배포·E2E 가 소형 VM thrash 가능, 검증 타이밍/리소스 고려) / #1455 (be 타임아웃 directive 오표시 — 단계 2 완료/실패 표시 정확성과 연관)
- 메모리 [[feedback-rev-e2e-always]] [[feedback-rev-release-gate]] [[feedback-verify-and-iterate]] [[feedback-evidence-based-root-cause]]
</content>
</invoke>
