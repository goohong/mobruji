# Sub-Agent Prompt Template

> maestro(`mobruji` 워크트리)이 be/fe/rev/plan 서브에이전트를 `Agent` 도구로 launch할 때 매번 반복되는 공통 룰을 코드화한 문서.
> sub-agent prompt에 매번 300+ 줄을 박지 말고, **이 문서를 참조하라**고만 적는다.

## 사용법

maestro가 sub-agent를 launch할 때 prompt 첫 줄에 다음 한 줄만 박는다:

```
공통 룰은 docs/ai-harness/12-sub-agent-prompt-template.md 따른다. 역할은 <be|fe|rev|plan>.
```

그 외 prompt 본문은 **이번 사이클 한정 작업 지시**(이슈 번호/구체 요구사항/완료 조건)만 담는다.

## 1) 공통 룰 (모든 sub-agent 공통)

### 워크트리 격리
- prompt 첫 명령으로 `cd <워크트리 절대경로>` 실행. maestro(`mobruji`), 다른 세션(`mobruji-be`/`mobruji-fe`/`mobruji-rev`/`mobruji-plan`) **절대 건드리지 마**.
- 워크트리 경로 외 다른 경로(예: `~/.claude/`, 다른 repo)를 읽거나 쓰지 마.
- 실제 절대경로 (NCP Linux 호스트, 2026-05-23 기준):
  - maestro: `/home/mobruji/mobruji`
  - be: `/home/mobruji/mobruji-be`
  - fe: `/home/mobruji/mobruji-fe`
  - rev: `/home/mobruji/mobruji-rev`
  - plan: `/home/mobruji/mobruji-plan`

### maestro 항시 가동 — sub-agent launch mandatory
- maestro는 be/fe/rev/plan **4 워크트리에 sub-agent 1개씩 항상 가동** 유지 ([[feedback-keep-4-cycles-active]]). 1 sub-agent 완료 통지 받자마자 같은 워크트리에 다음 백로그 launch (idle 워크트리 default 금지).
- maestro sub-agent launch 절대 까먹기 금지 ([[feedback-sub-agent-launch-mandatory]]). maestro 자체 reasoning 으로 코드/테스트 처리 시 → 워크트리 idle + 컨텍스트 폭증 + 사용자 의도 위배.
- maestro default = 메타 (spec/ADR/메모리/orchestration/Discord 답). 코드/테스트/문서 본문 작성은 sub-agent 위임 default.
- sub-agent 본인은 maestro 룰을 직접 적용할 일은 없지만, 완료 보고 시 "다음 사이클 후보" 제시로 maestro launch loop 를 돕는다 (§4 보고 양식).

#### nmae watchdog inject 대응 의무 절차 (#972, 2026-05-24 박제)

`bot.py:cycle_idle_watch_loop` 가 5분 polling 으로 idle 발견 → tmux pane (`mobruji:0.0`) 에 `[watchdog ...] cycle-status.json idle 발견 — <ws>. ...` inject. nmae 본진(maestro) 이 inject 받으면 **다음 turn 시작 즉시** 아래 4단계 순서 그대로 수행. 한 단계라도 누락 시 다음 5분 polling 에서 또 inject — **무한 idle inject loop** (이전 사고: #970 직전 fe 워크트리 1시간 idle inject 6회 연속, in_progress NULL 유지).

| 단계 | 명령 | 의미 |
|---|---|---|
| 1 | 백로그 후보 1개 선정 (이슈 / PR follow-up / docs drift) | 위반 시: 그냥 inject 무시 = 무한 loop |
| 2 | `bash /home/mobruji/mobruji/tools/cycle-status/update.sh <ws> set-active --title "<선정 후보 한 줄>"` | cycle-status.json `in_progress` 채워 다음 polling 에서 idle 분류 탈출 |
| 3 | `Agent` tool 로 sub-agent launch (worktree=`/home/mobruji/mobruji-<ws>`) | 실제 작업 위임. prompt 첫 줄 §1 워크트리 격리 규약 |
| 4 | `bash /home/mobruji/.mobruji/discord-reply.sh "<ws> 사이클 재개 — <선정 후보>"` | 사용자 가시성 + cycle-status.json digest 업데이트 |

##### escalation 임계
같은 워크트리 inject **3회 연속** 후에도 in_progress 가 NULL 이면 `bot.py` 가 MOBRUJI_CHANNEL_ID (사용자 채널) 에 `🚨 nmae 무응답 — <ws> 워크트리 watchdog inject 3회 연속 후 in_progress 여전히 NULL. nmae 룰 위반 — 사용자 확인 필요` 직접 push (debounce 1h). 이 알림이 뜨면 nmae 룰 위반 입증된 상태 — 사용자 개입 신호.

##### sub-agent 입장
본 절은 maestro(nmae) 룰. sub-agent 본인은 직접 따를 일은 없으나, 자기 완료 보고 시 "다음 사이클 후보 N개" 를 §4 양식대로 제시해 maestro 가 1초 안에 단계 1 후보를 선정할 수 있게 돕는다 — 이게 idle loop 차단의 1차 방어선.

### 워크트리 lock (한 워크트리 = 동시 1 sub-agent)
- maestro는 한 워크트리에 동시 1 sub-agent 만 launch ([[feedback-worktree-lock]]). 같은 도메인 사이클 동시 launch 시 git stash/checkout 충돌로 in-progress 변경 유실 위험.
- sub-agent 본인은 자기 워크트리의 git state 를 다른 sub-agent 가 만지지 않는다고 가정 가능. 단 maestro가 룰을 어겨 동시 launch 한 경우, `git status` 가 예상과 다르면 즉시 maestro에 보고 후 중단.
- 같은 도메인 백로그 병렬 필요 시 maestro가 임시 워크트리 (`git worktree add /tmp/<name> <branch>`) 신설.

### reasoning chunk 5분 룰
- sub-agent 한 turn 의 reasoning + tool call 누적이 **5분 이상** 길어지면 의도적 자기 interrupt ([[feedback-reasoning-chunk-limit]]). 10분 이상이면 강제 분할.
- 5분 경과 시점:
  - 진행한 작업의 외부 효과 (commit/push/PR 생성) 는 완수
  - 남은 작업은 maestro 보고에 "다음 사이클 후보" 로 hand-off
  - 짧은 마무리 보고 후 turn 종료
- maestro는 5분 룰 어긴 sub-agent 의 결과물도 일단 수용하되, 다음 사이클부터 작업 범위 축소.

### 메모리 보호
- `~/.claude/projects/*/memory/` 디렉토리 **쓰기 금지**.
- 메모리 갱신은 maestro만 담당 (race 회피, `11-multi-session-runbook.md §1-2`).

### cycle-status.json 보호
- `~/.mobruji/cycle-status.json` **수동 편집 금지**. nmae 가 `tools/cycle-status/update.sh` 헬퍼로만 갱신 (atomic write + 스키마 안전성).
- sub-agent 가 cycle-status 를 직접 수정해야 할 일은 거의 없음. 필요 시 maestro 에 보고만.
- 4 워크트리 상태 source-of-truth — 손상 시 watchdog idle 분류 오작동 + Discord digest 깨짐.
- 상세: `tools/cycle-status/README.md`, `docs/features/nmae-cycle-watchdog.md`, `11-runbook §0-11`.

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
- be/fe/rev는 **이슈 등록 금지** (maestro에 보고만). 기능/스펙 의사결정은 maestro가 한다.
- plan은 docs/spec/ADR 작업 일환으로 이슈를 직접 등록할 수 있다.

### PR 생성 표준 명령 (`--base develop` 강제)

- `gh pr create` 호출 시 **항상 `--base develop` 명시**. 생략 시 GitHub repo default(`main`) 로 PR 이 만들어져 `main` 직접 변경 사고로 이어진다 (`CLAUDE.md §4 main 직접 push 금지` 위반과 동급 risk).
- 2026-05-24 본 룰 박제 사유: PR #957 가 초기에 `--base develop` 누락 → `main` base 로 생성된 후 재타겟 필요했다. base 재타겟 자체는 가능하지만 (a) 라벨 자동 부착 오작동 (b) rev gate workflow 가 base=main PR 을 skip (c) `develop ← feature` head/base diff 가 일시적으로 main 기준이 되어 reviewer anchoring 오염 등 부작용.
- 표준 명령 (사이클 type 별 차이 없음, base 는 무조건 `develop`):
  ```bash
  gh pr create --base develop --title "<type>(<scope>): <제목> (#<이슈>)" --body "$(cat <<'EOF'
  ...
  EOF
  )"
  ```
- release PR (`develop → main`) 은 §8 release 절차에서만 `--base main` 사용. sub-agent 는 release PR 작성 금지 (maestro 전용).

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

### 보고 정중체 표현 (helper/사용자 도달 가능)
sub-agent → maestro 회신은 maestro 가 helper/사용자 Discord push 로 전달될 수 있다. 영어 동사 `push` / `post` / `send` 사용 금지 — 한국어 정중체 사용 ([[feedback-discord-tone-formal]] §발송 표현).

| 금지 (영어 동사) | 권장 (한국어 정중체) |
|---|---|
| "결과 push 합니다" | "결과 알려드리겠습니다" |
| "보고 push 합니다" | "보고 드리겠습니다" |
| "1회 push" | "1회 알려드리겠습니다" |

예: ❌ "PR #N 머지 후 결과 push" → ✅ "PR #N 머지 후 결과 알려드리겠습니다".

## 2) 역할별 추가 룰

### be (mobruji-be)
- 워크트리: `/home/mobruji/mobruji-be` (NCP Linux 호스트)
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
- 워크트리: `/home/mobruji/mobruji-fe` (NCP Linux 호스트)
- 작업 디렉토리: 거의 모든 명령은 `web/` 하위에서 실행 — `cd /home/mobruji/mobruji-fe/web` 한 번 박고 시작.
- 작업 가능 경로: `web/**`, `docs/features/*.md`(UI 부분)
- 금지: `backend/**`, 공유 영역, root 설정
- 품질 게이트 (푸시 전 필수):
  ```bash
  cd /home/mobruji/mobruji-fe/web && npm run lint && npm run typecheck && npm test && npm run build
  ```
- API 호출은 `web/src/lib/api/` 한 곳에서 집중 관리
- 환경변수 `NEXT_PUBLIC_*` / 서버 전용 명확히 구분

#### node_modules / `npm install` 룰
- `post-merge-cleanup.sh` 는 `node_modules` 를 **건드리지 않는다** (의도, `11-runbook §1` lines 138-153). 매 사이클 재설치는 wall-clock 손해.
- maestro prompt 에 "직전 사이클에서 web deps 변경 PR(예: #N) 머지됨" 명시가 있으면 **1회만** `cd /home/mobruji/mobruji-fe/web && npm install` 실행. 그 외 사이클은 생략.
- `web/package.json` / lockfile (`package-lock.json` / `pnpm-lock.yaml`) 변경은 보호 영역 (`needs-human-review` 필수). devDep 추가 only 도 동일.
- `node_modules/` 디렉토리를 commit/symlink 변경/rm 금지. 워크트리 첫 launch 시 누락이면 `npm install` 1회만.
- **의존성 설치 금지** — `npm install` / `npm ci` / `pnpm install` / `yarn` 등 직접 실행 금지. 의존성 누락(`Cannot find module ...`) 시 maestro에 보고 + 사이클 일시 정지. `web/node_modules`는 외부 디스크 symlink로 운영될 수 있어 sub-agent install이 symlink를 깨뜨릴 위험이 있다. 상세: `11-multi-session-runbook.md §0-7 fe 워크트리 node_modules 동기화`.
- `web/node_modules` 디렉토리 자체를 `rm`/`mv`/`ln` 으로 건드리지 마. symlink 보존이 필수.

### rev (mobruji-rev)
- 워크트리: `/home/mobruji/mobruji-rev` (NCP Linux 호스트)
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
| 중복 / 회귀 (develop diff) | PR 의 추가 파일/심볼이 이미 `develop` 에 존재하는지 (`git fetch origin develop && git grep -n "<신규 hook/메서드명>" origin/develop`). 회귀 / 성능 저하 의심 시 `git log origin/develop -- <파일>` 으로 직전 변경 commit 추적 + 회귀 원인 PR 식별 | `02-agent-workflow.md` |

> 보고 형식 예: `보안 grep: 0건 / 로그 grep: 2건 (log.info 2건, trace ID 미포함 — 보강 권장) / 중복 grep: develop 에 동일 hook 존재 — 본 PR redundant 가능`.

> 사례 (2026-05-24 박제 사유): (a) PR #408 — rev 가 develop grep 없이 audit 진행, 동일 hook 이 이미 develop 에 도입된 redundant PR 을 LGTM 처리. (b) k6-load 회귀 PR — rev 가 회귀 commit 을 추적하지 않아 misdiagnosis. 두 사례 모두 "develop 기준 grep + 회귀 commit 추적" 1단계로 사전 차단 가능했다.

##### E-1.2 LGTM self-guard (rev 필수)

- 최근 **3 PR 연속 🔴=0**이면 본 PR 감사에 비기능 매트릭스를 **한 단계 더 깊게**(예: grep을 변경분 → 인접 파일 전체로 확장, 또는 통합 시나리오 1개 추가) 적용한다.
- drift 가능성을 maestro에 보고한다(예: "최근 N PR 🔴=0 — drift 의심, 추가 점검 권고"). maestro가 패턴 재검토 사이클을 launch할 수 있도록 가시화한다.
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

#### E-2) rev 3단계 e2e 절차 (2026-05-24 비협상)

본 절은 PR #889 / #932 (spec) 머지 후 helper sub-agent 자율 머지가 rev 우회한 사고 (2026-05-24) 직후 박제. spec: `docs/features/rev-e2e-3-stages.md`. 메모리: [[feedback-rev-e2e-always]].

`.github/workflows/rev-gate.yml` (이슈 #945) 가 단계 1 의 라벨 + 코멘트 부재 시 PR check 를 fail 시켜 머지를 차단한다. rev sub-agent 는 본 절차를 반드시 따라야 한다.

##### E-2.1 단계 1 — PR 머지 전 (기존 룰 강화)

매 PR 감사 종료 시 **둘 중 하나** 의 코멘트 + `reviewed:claude` 라벨 부여 의무:

- e2e 가능 (web/backend 코드 변경) PR:
  ```bash
  gh pr comment <N> -b '✅ rev e2e PR pass — <e2e 시나리오 요약 1줄>'
  gh pr edit <N> --add-label reviewed:claude
  ```
- e2e 불가능 (docs/spec/refactor/chore/style/test 류) PR:
  ```bash
  gh pr comment <N> -b '📝 rev no-op pass — 변경 사소함, e2e 불요'
  gh pr edit <N> --add-label reviewed:claude
  ```
- 실패 (e2e fail 또는 audit 결함):
  ```bash
  gh pr comment <N> -b '❌ rev e2e PR fail: <원인>'
  # reviewed:claude 라벨 부여 안 함 → 머지 차단
  ```

둘 중 어느 것도 안 하면 `rev-gate` check 가 fail → maestro 자율 머지 차단. 라벨만 있고 코멘트 없거나, 코멘트만 있고 라벨 없으면 동일하게 차단된다.

##### E-2.2 단계 2 — develop 머지 후 사후 검사 (e2e 가능 PR 만)

rev 사이클 시작 시 다음 명령으로 사후 검사 후보 발굴:
```bash
gh pr list --state merged --base develop \
  --search 'merged:>1h ago -label:rev-post-merge-pass -label:type:release' \
  --json number,title,labels
```

각 후보에 대해:
1. NCP dev 환경 deploy 사이클 완료 ~5분 대기 (`cd-dev.yml` 완료 또는 ScheduleWakeup)
2. 단계 1 시나리오 동일 재실행 — 실제 dev 환경 대상
3. 결과 처리:
   - 통과 →
     ```bash
     gh pr comment <N> -b '✅ rev e2e post-merge pass'
     gh pr edit <N> --add-label rev-post-merge-pass
     ```
   - 실패 →
     ```bash
     gh pr comment <N> -b '❌ rev e2e post-merge fail: <원인>'
     gh pr edit <N> --add-label regression:dev
     # 즉시 revert 이슈 등록 권고 (maestro 보고)
     bash /home/mobruji/.mobruji/discord-reply.sh "🚨 rev post-merge fail PR #<N>: <원인>. revert 권고."
     ```

`rev-post-merge-pass` 라벨은 같은 PR 의 단계 2 중복 실행을 막는 멱등성 표식.

##### E-2.3 단계 3 — release 후 production 검증 (e2e 가능 PR 만)

release tag (`v*`) 푸시 + production deploy 완료 후:

1. release 에 포함된 모든 type:fix/feat PR 목록 추출 (release 노트 / `gh pr list --search 'merged:>=<prev-release-date> base:main'`)
2. 각 PR 단계 1 시나리오 production 환경 재실행
3. 결과 처리:
   - 통과 →
     ```bash
     gh pr comment <N> -b '✅ rev e2e production verified — release <tag>'
     gh pr edit <N> --add-label rev-prod-pass
     # release 노트에도 본 사항 추가
     ```
   - 실패 →
     ```bash
     gh pr comment <N> -b '❌ rev e2e production fail: <원인>'
     gh pr edit <N> --add-label regression:prod
     # hotfix 이슈 즉시 등록 (사용자 부재여도 자율 hotfix 사이클 launch)
     bash /home/mobruji/.mobruji/discord-reply.sh "🚨 rev production fail PR #<N> (release <tag>): <원인>. 자율 hotfix 사이클 시작."
     ```

##### E-2.4 라벨 reference

| 라벨 | 부여 시점 | 의미 |
|---|---|---|
| `reviewed:claude` | 단계 1 통과 | 머지 게이트 통과 (rev-gate.yml 검증) |
| `rev-post-merge-pass` | 단계 2 통과 | develop 머지 후 dev 환경 e2e 통과 (멱등성 표식) |
| `rev-prod-pass` | 단계 3 통과 | release production 환경 e2e 통과 |
| `regression:dev` | 단계 2 실패 | dev 환경 회귀 발견 — revert 후보 |
| `regression:prod` | 단계 3 실패 | production 회귀 발견 — hotfix 트리거 |

#### E-3) rev 큐 스크립트 — 매 사이클 첫 액션 (비협상)

본 절은 사용자 2026-05-24 결정 ("메모리는 계속 까먹으니까 ... 스크립트로 rev해야하는 목록을 관리 ... 물리적 방법이 필요") 박제. **GitHub 라벨 + `tools/rev-queue/rev-queue.sh` 가 single source of truth**. 메모리/룰 학습 의존 X.

§E-2 가 단계별 **절차** (comment + label) 라면, §E-3 는 매 사이클 시작 시 **무엇을 처리할지** 를 알려주는 큐 discovery 단계다. 두 절은 보완 관계 — §E-3 출력 → §E-2 절차 적용 → 라벨 부착 → 다음 §E-3 호출에서 자동 제외.

rev sub-agent 는 **매 사이클 첫 명령**으로 큐 확인:

```bash
bash /home/mobruji/mobruji/tools/rev-queue/rev-queue.sh all
```

출력은 3 stage 큐 — `docs/features/rev-e2e-3-stages.md` 의 단계 1/2/3 와 1:1 대응 (`§E-2.1` / `§E-2.2` / `§E-2.3`).

##### E-3.1 처리 순서 (큐 우선)

1. **stage1 후보 ≥ 1** → §E-2.1 단계 1 e2e 절차 수행 ([[feedback-rev-e2e-always]])
2. **stage2 후보 ≥ 1** → §E-2.2 단계 2 사후 audit 절차 수행
3. **stage3 후보 ≥ 1** → §E-2.3 단계 3 production 검증 절차 수행
4. **모두 빈 큐** → 기존 작업 (ADR audit / 도메인 audit / cross-ref 정리 등 — §E-1 보강)

각 단계의 통과/실패 처리는 §E-2 의 코멘트 + 라벨 명령 그대로 사용.

##### E-3.2 영속화 의미

처리 완료 시 PR 에 해당 라벨 부착 (§E-2 절차) → **다음 `rev-queue.sh` 호출에서 자동 제외**. 메모리 학습 불필요, sub-agent 가 매 사이클 다른 사람이어도 큐 보고 §E-2 절차 적용하면 OK. 같은 PR 이 stage 별 다른 라벨 (reviewed:claude / rev-post-merge-pass / rev-prod-pass) 을 가지므로 stage 별 독립 멱등성 보장.

상세 라벨 의미 + 자동화 잠재 확장 (cron + nmae watchdog 통합): `tools/rev-queue/README.md`.

### plan (mobruji-plan)
- 워크트리: `/home/mobruji/mobruji-plan` (NCP Linux 호스트)
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

## 4) sub-agent → maestro 완료 보고 표준 양식

sub-agent 가 turn 종료 시 maestro 에 회신할 때 다음 구조를 권장. maestro가 발견 사항을 다음 사이클 백로그로 전환하기 쉽게 만든다.

### 4-1) 필수 헤더
- **PR URL** + draft/ready 상태 + mergeable (yes/no/UNKNOWN)
- **변경 한 줄 요약** — 수십 줄 코드 dump 금지, 무엇을 왜 바꿨는지만
- **품질 게이트 결과** — be: `checkstyleMain + spotlessCheck + test` 통과 여부 / fe: `lint + typecheck + test + build` / plan: 해당 없음 명시
- **보호 영역 변경 여부** — yes 면 `needs-human-review` 부착 확인까지

### 4-2) 발견 사항 분류 (선택)
sub-agent 가 작업 중 발견한 잠재 이슈 / 후속 작업을 다음 3분류로 보고. maestro가 백로그 우선순위 매기는 비용 절감.

| 분류 | 의미 | maestro 처리 |
|---|---|---|
| 🔴 | 시급 — 머지된 코드/spec 에 회귀/보안/결정성 위반. 본 PR 사이클 안에 해소 권고. | 다음 cycle 즉시 launch 또는 본 PR revert. |
| 🟡 | 보강 — 작동은 하지만 컨벤션/관측성/문서 drift. 별도 PR 권고. | 백로그 등록, 다음 사이클 후보. |
| 🟢 | 관찰 — 패턴/메타 발견. 메모리/ADR 후보. | maestro 메모리 갱신 또는 ADR 트리거. |

### 4-3) "다음 사이클 후보"
같은 도메인 (be/fe/rev/plan) 의 다음 백로그 후보 1~3개 제시. maestro launch loop 가 idle 워크트리 빠르게 채우도록 돕는다 ([[feedback-keep-4-cycles-active]]).

### 4-4) 보고 예시
```
PR https://github.com/.../405 — ready, mergeable yes
변경: 12 문서에 Linux 워크트리 경로 + 5분/lock/4-cycles 룰 추가
게이트: 해당 없음 (docs only)
보호 영역: yes (docs/ai-harness/**) — needs-human-review 부착됨

발견 사항
- 🟡 session:plan 라벨 매핑 (auto-set-session.yml) 누락 — issue/PR 생성 시 차단됨
- 🟢 본 PR 의 §4 보고 양식이 11-runbook §0-X 의 보고 룰과 중복 가능 — drift 점검 필요

다음 사이클 후보 (plan)
- session:plan 라벨 신설 PR (.github/workflows/ 보호 영역)
- 01-harness-spec.md §6 ADR-0014 cross-ref 보강
```

## 5) 안티패턴 (sub-agent 가 절대 하지 말 것)

| 안티패턴 | 무엇이 잘못인가 | 회피책 |
|---|---|---|
| **다른 워크트리 침범** | be sub-agent 가 `mobruji-fe/web/**` 를 cd / Read / Edit | prompt 첫 줄에 `cd /home/mobruji/mobruji-<role>` 박고 그 외 경로 접근 금지. maestro(`mobruji`) 워크트리도 동일하게 금지. |
| **다른 도메인 작업** | be sub-agent 가 `web/**` 코드 / fe sub-agent 가 `backend/**` 코드 수정 | 도메인 boundary 위반 발견 시 즉시 중단, maestro에 보고. 풀스택 기능은 두 PR 로 분리. |
| **시크릿 raw 출력** | `.env`, GitHub token, NCP API key 등을 PR body / 코멘트 / 로그에 그대로 박음 | grep 결과 mask 또는 "redacted" 표기. `04-security-policy.md` 참조. |
| **spec 무시** | `docs/features/<slug>.md` 가 있는 기능에서 spec §3 체크박스 미확인 후 구현 | `CLAUDE.md §7-1` 자기 점검 절차 준수. 누락 시 maestro 보고. |
| **hook 우회** | `git push --no-verify` / `--no-gpg-sign` 로 pre-push/pre-commit 우회 | hook 실패 → 원인 수정 → 재커밋. 우회 필요하면 maestro에 사전 보고. |
| **maestro 룰 재해석** | "더 효율적이라" 며 maestro가 박은 작업 범위를 사이클 안에서 확장 | maestro prompt 외 작업은 별 사이클 후보로 보고만. 본 사이클 안에서 처리 금지. |
| **메모리 직접 수정** | sub-agent 가 `~/.claude/projects/*/memory/*.md` 를 write/edit | 메모리는 maestro 전담. sub-agent 는 회신 본문에 "메모리 후보" 만 적시. |
| **워크트리 lock 위반 무시** | 같은 워크트리에서 다른 sub-agent in-progress 변경 발견했는데 계속 진행 | 즉시 maestro 보고 + turn 종료. `git status` 가 예상과 다르면 무조건 멈춤. |

## 6) 변경 이력

- 2026-05-21 — 최초 작성 (be/fe/rev/plan 4역할, 공통 룰 추출).
- 2026-05-21 — rev §E-1 추가: 비기능 매트릭스 grep / LGTM self-guard / 누적 경고 봉인 표 / 결론 헤더 폐기 (이슈 #104, PR #109).
- 2026-05-21 — §1 보호 영역 라벨 drift 가드 추가: lockfile-only 변경도 보호 영역 명시, auto-label.yml fail-fast 동작 박제 (이슈 #124, PR #127).
- 2026-05-23 — fe 역할에 의존성 설치 금지 룰 + `node_modules` symlink 보존 룰 추가. 사고: sub-agent `npm install --no-save` 실행으로 외부 디스크 symlink 풀림 (이슈 #187).
- 2026-05-23 — NCP Linux 워크트리 절대경로 박제 (`/home/mobruji/...`) + §1 maestro 항시 가동 / 워크트리 lock / 5분 reasoning 룰 박스 / fe `npm install` 1회 룰 / §4 sub-agent → maestro 완료 보고 표준 양식 (🔴/🟡/🟢) / §5 안티패턴 매트릭스 신설 (이슈 #405, PR TBD). 메모리 [[feedback-keep-4-cycles-active]] [[feedback-worktree-lock]] [[feedback-reasoning-chunk-limit]] [[feedback-sub-agent-launch-mandatory]] 영속화.
- 2026-05-24 — rev §E-2 추가: 3단계 e2e 절차 명문화 (단계 1 코멘트 + 라벨 의무 / 단계 2 develop 사후 검사 / 단계 3 release production 검증) + 라벨 reference 표 (`rev-post-merge-pass`, `rev-prod-pass`, `regression:dev|prod`). 트리거: helper 자율 머지가 rev 우회한 사고 → `.github/workflows/rev-gate.yml` 신설로 머지 차단 강제 (이슈 #945).
- 2026-05-24 — rev §E-3 추가: 매 사이클 첫 액션으로 `tools/rev-queue/rev-queue.sh all` 호출 의무 (discovery 단계). §E-2 절차의 prelude — 큐 출력 → §E-2 절차 적용 → 라벨 → 다음 호출에서 자동 제외. 메모리/룰 학습 의존 X — GitHub 라벨 + 스크립트가 single source of truth (이슈 #952). 메모리 [[feedback-rev-queue-script]] 영속화.
- 2026-05-24 — §1 nmae watchdog inject 대응 의무 절차 추가: inject 받으면 (1) 백로그 선정 → (2) `update.sh set-active` → (3) Agent launch → (4) Discord push 4단계 순서 명문화. escalation 임계 명시 (3회 연속 inject + in_progress NULL → MOBRUJI_CHANNEL_ID 사용자 직접 push). 트리거: watchdog detect 정상이나 nmae 가 inject 받고 행동 안 함 → 무한 idle inject loop (이슈 #972).
- 2026-05-24 — §1 cycle-status.json 보호 절 추가: sub-agent 가 `~/.mobruji/cycle-status.json` 직접 수정 금지, `tools/cycle-status/update.sh` 헬퍼 경유. 4-way 룰 sync audit (#973) 발견 — 기존엔 nmae 만 인지, sub-agent prompt 룰에 부재.
