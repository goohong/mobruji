# Sub-Agent Prompt Template

> maestro(`mobruji` 워크트리)이 be/fe/rev/plan 서브에이전트를 `Agent` 도구로 launch할 때 매번 반복되는 공통 룰을 코드화한 문서.
> sub-agent prompt에 매번 300+ 줄을 박지 말고, **이 문서를 참조하라**고만 적는다.

## 사용법

maestro이 sub-agent를 launch할 때 prompt 첫 줄에 다음 한 줄만 박는다:

```
공통 룰은 docs/ai-harness/12-sub-agent-prompt-template.md 따른다. 역할은 <be|fe|rev|plan>.
```

그 외 prompt 본문은 **이번 사이클 한정 작업 지시**(이슈 번호/구체 요구사항/완료 조건)만 담는다.

## 1) 공통 룰 (모든 sub-agent 공통)

### 워크트리 격리
- prompt 첫 명령으로 `cd <워크트리 절대경로>` 실행. maestro(`mobruji`), 다른 세션(`mobruji-be`/`mobruji-fe`/`mobruji-rev`/`mobruji-plan`) **절대 건드리지 마**.
- 워크트리 경로 외 다른 경로(예: `~/.claude/`, 다른 repo)를 읽거나 쓰지 마.

### 메모리 보호
- `~/.claude/projects/*/memory/` 디렉토리 **쓰기 금지**.
- 메모리 갱신은 maestro만 담당 (race 회피, `11-multi-session-runbook.md §1-2`).

### hook 우회 금지
- `git push --no-verify`, `git commit --no-verify`, `--no-gpg-sign` 등으로 hook을 우회하지 마.
- pre-push/pre-commit hook이 실패하면 **원인 수정** 후 재커밋. hook 우회 필요한 정당한 사유가 있으면 maestro에 보고.

### 보호 영역 라벨
- 다음 경로 변경 시 PR에 `needs-human-review` 라벨 필수:
  - `.github/workflows/**`, `.github/CODEOWNERS`
  - `**/db/migration/**`, `**/resources/db/**`
  - `**/application*.yml`, `**/application*.properties`, `.env*`
  - `backend/build.gradle*`, `backend/settings.gradle*`, `backend/gradle/**`
  - `web/next.config.*`, `web/package.json`, **lockfile 전체**(`web/pnpm-lock.yaml`, `web/package-lock.json`, `web/yarn.lock` 등) — devDep만 추가된 lockfile-only diff도 보호 영역
  - `Dockerfile`, `docker-compose*.yml`
  - `LICENSE`
- 상세: `CLAUDE.md §4 AI 작업 보호 영역`

#### 보호 영역 라벨 drift 가드 (사이클 9 retro, #124)
- `needs-human-review` 라벨은 머지 시까지 **유지**한다. 임의로 떼지 말 것.
- `.github/workflows/auto-label.yml`이 `opened|edited|synchronize|reopened|ready_for_review|unlabeled` 이벤트마다 보호 영역을 재평가해 라벨을 재부착하며, 부착 실패 시 워크플로우 자체를 실패시켜(빨간 체크) 머지를 차단한다.
- lockfile 변경(devDep 추가, transitive 업데이트)도 보호 영역이다. "package.json 본문은 안 건드렸으니 괜찮다"는 가정 금지.

### 기획/이슈 등록
- be/fe/rev는 **이슈 등록 금지** (maestro에 보고만). 기능/스펙 의사결정은 maestro이 한다.
- plan은 docs/spec/ADR 작업 일환으로 이슈를 직접 등록할 수 있다.

### 푸시 + ready 전환 표준 명령
```bash
git push
gh pr ready <PR번호>   # draft → ready for review
```

### 라벨 자기 점검 (PR 생성 직후)
- [ ] `type:*` 라벨 1개
- [ ] `scope:*` 라벨 1개
- [ ] `ai-generated` + `ai:claude` 라벨
- [ ] 보호 영역 변경 시 `needs-human-review`
- [ ] (해당 세션) `session:backend|frontend|review`

상세: `CLAUDE.md §7-2 PR 생성 직후`.

### 완료 보고 형식
sub-agent가 maestro에 회신할 때 다음을 포함:
- PR URL + mergeable 상태
- 변경 한 줄 요약 (수십 줄 코드 dump 금지)
- 품질 게이트 통과 여부
- 보호 영역 변경 여부 + `needs-human-review` 부착 여부

## 2) 역할별 추가 룰

### be (mobruji-be)
- 워크트리: `/Users/goohong/workspace/github/mobruji-be`
- 작업 가능 경로: `backend/**`, `docs/features/*.md`(backend 부분), `docs/ai-harness/06-domain-model.md` §5/§6 (Spring entity 변경 시)
- 금지: `web/**`, 공유 영역(`CLAUDE.md`/`AGENTS.md`/`docs/ai-harness/**` 단 §5/§6 entity 갱신 제외)/root 설정
- 품질 게이트 (푸시 전 필수):
  ```bash
  cd backend && ./gradlew checkstyleMain spotlessCheck test
  ```
- 포맷 위반 시: `./gradlew spotlessApply`
- 새 엔드포인트는 **성공 케이스 E2E(RestAssured) 필수** (`07-testing-guide.md`)
- DDD 계층 침범 금지 (Controller → Repository 직접 호출 등)

### fe (mobruji-fe)
- 워크트리: `/Users/goohong/workspace/github/mobruji-fe`
- 작업 가능 경로: `web/**`, `docs/features/*.md`(UI 부분)
- 금지: `backend/**`, 공유 영역, root 설정
- 품질 게이트 (푸시 전 필수):
  ```bash
  cd web && npm run lint && npm run typecheck && npm test && npm run build
  ```
- API 호출은 `web/src/lib/api/` 한 곳에서 집중 관리
- 환경변수 `NEXT_PUBLIC_*` / 서버 전용 명확히 구분

### rev (mobruji-rev)
- 워크트리: `/Users/goohong/workspace/github/mobruji-rev`
- **파일 수정 절대 금지** (`pre-push` hook으로 push 차단됨). PR 코멘트만.
- 동작 패턴:
  ```bash
  gh pr list --search "is:open draft:false -label:reviewed:claude" --json number,title
  # 각 PR마다:
  gh pr view <N> --json title,body,labels
  gh pr diff <N>
  gh pr review <N> --comment --body "..."
  gh pr edit <N> --add-label reviewed:claude
  ```
- QA 실행 검증 (read-only로 실행만):
  - BE: `./gradlew test`, RestAssured E2E 분석, curl로 endpoint 검증
  - FE: `npm run lint/typecheck/test/build`, `npm run dev` + curl SSR 응답 확인
  - 통합: `docker compose up -d` + `./gradlew bootRun` + `npm run dev` 동시 기동 후 흐름/결정성/p95/다양성 검증
- 발견 사항은 PR 코멘트로. 후속이 필요하면 maestro에 보고(이슈 등록은 maestro).

#### E-1) rev 감사 표준 절차 (비협상)

본 절은 rev 사이클 8 self-review(2026-05-21, 이슈 #104)에서 박제된 룰을 영속화한다. 16 PR 통틀어 보안 grep 0건이었고, 사이클 5~7 8연속 🔴=0 LGTM drift가 발견된 직후의 보강이다.

##### E-1.1 비기능 매트릭스 grep (rev 필수)

매 PR 변경분(`gh pr diff <N>`)에 대해 다음 패턴을 grep하고 **결과(0건도 명시)를 PR 코멘트에 보고**한다. "안 봤다"와 "0건"을 분간 가능하게 만드는 게 목적이다.

| 카테고리 | grep 패턴 / 점검 항목 | 참조 |
|---|---|---|
| 보안 | `password\|secret\|token\|api[_-]?key\|PII\|sessionId\|민감` | `04-security-policy.md` |
| 로그/관측성 | `log\.(info\|warn\|error)` — 구조화 로그 + trace ID 포함 여부 | `10-observability.md` |
| DB | 새 마이그레이션 / DDL / `fetch.*EAGER` / N+1 의심 쿼리 | `06-domain-model.md`, `03-quality-gates.md` |
| 의존성 | `package.json` / `build.gradle*` diff 시 신규 라이브러리 라이선스 + CVE | `02-license-agpl-3-0` ADR, `04-security-policy.md` |
| 마이그레이션 안전성 | DDL 변경 시 rollback 가능 여부 + zero-downtime 검증 | `03-quality-gates.md` |

> 보고 형식 예: `보안 grep: 0건 / 로그 grep: 2건 (log.info 2건, trace ID 미포함 — 보강 권장)`.

##### E-1.2 LGTM self-guard (rev 필수)

- 최근 **3 PR 연속 🔴=0**이면 본 PR 감사에 비기능 매트릭스를 **한 단계 더 깊게**(예: grep을 변경분 → 인접 파일 전체로 확장, 또는 통합 시나리오 1개 추가) 적용한다.
- drift 가능성을 maestro에 보고한다(예: "최근 N PR 🔴=0 — drift 의심, 추가 점검 권고"). maestro이 패턴 재검토 사이클을 launch할 수 있도록 가시화한다.
- 사이클 6 PR #74에서 LGTM 헤더 다음 EAGER fetch p95=80.9ms(3.7배) 회귀 신호를 누락한 사례가 본 룰의 근거다.

##### E-1.3 누적 경고 봉인 명시 섹션

PR 코멘트에 **"이전 사이클에서 예측한 패턴 N개 중 본 PR에서 봉인된 항목"** 표를 포함한다. drift 추적 가능하도록 누적 경고를 명시적으로 닫는다.

| 사이클/PR | 예측 패턴 | 본 PR에서 봉인 여부 | 비고 |
|---|---|---|---|
| 사이클 6 #74 | EAGER fetch p95 회귀 | 봉인됨 / 미봉인 / 해당 없음 | (관찰 또는 후속 이슈 링크) |

##### E-1.4 결론 헤더 폐기 (anchoring 회피)

- rev 코멘트 **첫 줄**을 `🟢 LGTM` 또는 `🟢 GREEN` 같은 **결론 단정형**으로 시작 **금지**.
- 대신 중립 헤더(`발견 사항 — 분석`)로 시작하여 **발견 → 분석 → 종합 판정** 순서로 작성한다.
- 이유: 리뷰어/머지권자가 첫 줄에 anchoring되어 본문 회귀 신호를 놓치는 confirmation bias를 회피한다.
- 사례: 사이클 6 PR #74에서 `🟢 LGTM` 헤더 다음에 EAGER fetch p95=80.9ms(3.7배) 회귀 신호가 누락된 적이 있다.

### plan (mobruji-plan)
- 워크트리: `/Users/goohong/workspace/github/mobruji-plan`
- 작업 가능 경로: 큰 docs/spec/ADR — `docs/ai-harness/**`, `docs/features/**`, `docs/decisions/**`, `scripts/**`, `.github/**`(보호 영역 라벨 필수)
- 금지: `backend/**`/`web/**` 구현 코드 (구현은 be/fe 담당)
- ADR/spec 작성 시 `docs/decisions/README.md`, `docs/features/README.md`, `docs/features/_template.md` 규약 준수

## 3) maestro sub-agent launch 시 prompt 예시

좋은 예시:
```
공통 룰은 docs/ai-harness/12-sub-agent-prompt-template.md 따른다. 역할은 be.

이번 사이클 작업:
- 이슈: #92 — RecommendationRequest 캐싱 도입
- 브랜치: feat/recommendation-cache-#92 (이미 스캐폴드됨)
- 요구사항:
  1. RecommendationService.recommend()에 Caffeine 캐시 적용
  2. TTL 5분, max size 1000
  3. E2E 테스트로 cache hit 확인

완료 후 PR URL + mergeable + 게이트 통과 여부 보고.
```

나쁜 예시 (공통 룰을 매번 박는다):
```
너는 be 세션. 워크트리 ... cd ... 메모리 절대 ... --no-verify ... (300줄)
```

## 4) 변경 이력

- 2026-05-21 — 최초 작성 (be/fe/rev/plan 4역할, 공통 룰 추출).
- 2026-05-21 — rev §E-1 추가: 비기능 매트릭스 grep / LGTM self-guard / 누적 경고 봉인 표 / 결론 헤더 폐기 (이슈 #104, PR #109).
- 2026-05-21 — §1 보호 영역 라벨 drift 가드 추가: lockfile-only 변경도 보호 영역 명시, auto-label.yml fail-fast 동작 박제 (이슈 #124, PR #127).
