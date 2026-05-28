# sub-agent Runbook — be / fe / rev / plan / helper-launched

> **로드 대상: sub-agent (be/fe/rev/plan + helper-launched 일회성) launch 시만.** nmae/helper 본체는 자기 actor 런북(`actors/nmae.md` / `actors/helper.md`) 사용.
> **강제는 prose 가 아니라 코드** — `tools/agent-launch-wrapper.sh` (launch prompt + set-active) + `pre-push` hook (rev 파일 수정 차단) + `.github/workflows/auto-label.yml` + `rev-gate.yml` + `spec-status-check.yml` + `pre-commit / pre-push` + `tools/rev-queue/rev-queue.sh` 가 본 룰을 강제합니다. 본 문서는 **판단이 필요한 룰만**, 인시던트 "왜" 는 메모리(`memory/<actor>/feedback_*.md`)가 보관.
> 메모리 actor: `subagent` (+ rev: `rev/` + 모든 actor: `common/` `workflow/`). 충돌 시 본 문서 + 강제 코드 우선.

## 사용법

nmae 가 sub-agent launch 시 prompt 첫 줄에 한 줄만 박는다:

```
공통 룰은 docs/ai-harness/actors/sub-agent.md 따른다. 역할은 <be|fe|rev|plan|helper>.
```

그 외 prompt 본문은 **이번 사이클 한정 작업 지시**(이슈/요구사항/완료 조건)만.

| 메모리 디렉토리 | 로드 대상 actor |
|---|---|
| `common/` `subagent/` `workflow/` | 모든 sub-agent |
| `rev/` | rev sub-agent 만 |
| `nmae/` `helper/` | **로드 금지** (다른 actor 룰) |

루트 `MEMORY.md` + `project_*` / `user_*` (핸드오프/상태) 는 read-only 참고.

---

## 1) 공통 룰 (모든 sub-agent)

### 1-1) 워크트리 격리 / lock

- prompt 첫 명령 `cd <워크트리 절대경로>`. **다른 워크트리 / `~/.claude/` / 다른 repo 접근 금지**.
  - be `/home/mobruji/mobruji-be` · fe `/home/mobruji/mobruji-fe` · rev `/home/mobruji/mobruji-rev` · plan `/home/mobruji/mobruji-plan`
  - helper-launched `/home/mobruji/mobruji/.claude/worktrees/agent-<id>`
- **한 워크트리 = 동시 1 sub-agent** ([[feedback-worktree-lock]]). `git status` 가 예상과 다르면 즉시 nmae 보고 후 중단.

### 1-2) 자율 결정 STRICT — 사용자 wait state 절대 금지 (#1015 P1)

- **`AskUserQuestion` 도구 사용 자체 금지** ([[feedback-sub-agent-no-user-wait]]). 금지 표현: "사용자 결정 대기 / 확인 필요 / 승인 후 진행".
- 모호한 분기점 → **자율 결정 + 사유 PR 본문** 패턴: PR 본문에 `## 자율 결정 (사유)` 섹션 (결정 / 후보 alternative / 채택 사유 / follow-up 이슈 분리 가능 명시).
- high-stakes (release / production secret / 의존성 추가 / 보호 영역 변경 미명시) → 자율 결정 X, **nmae 위임 후 turn 종료**. PR 본문 `## 사용자 확인 필요` 섹션은 질문 X 사실 진술 (예: "lodash 누락 — 현재 PR native 우회. follow-up 이슈 등록 권고").

### 1-3) 검증 의무 ([[feedback-verify-and-iterate]])

작업 완료 보고 전: PR 머지 후 CI green / daemon 변경 후 journal / file 변경 후 read back. 실패 시 root cause 추론 + alternative path. "되었겠지" 금지. 미검증은 "(검증 못함 — alternative path 필요)" 명시.

### 1-4) reasoning chunk 5분 룰 ([[feedback-reasoning-chunk-limit]])

한 turn reasoning + tool call 누적 5분 ↑ → 자기 interrupt. 10분 ↑ 강제 분할. 외부 효과 (commit/push/PR 생성) 만 완수, 남은 작업은 "다음 사이클 후보" 로 hand-off.

### 1-5) 메모리 보호

`~/.claude/projects/*/memory/` **쓰기 금지** (nmae 전담, race 회피). sub-agent 는 회신에 "메모리 후보" 만 적시.

### 1-6) cycle-status.json 보호

`~/.mobruji/cycle-status.json` **수동 편집 금지**. nmae 가 `tools/cycle-status/update.sh` 헬퍼로만 갱신. 손상 시 watchdog idle 분류 오작동 + Discord digest 깨짐.

### 1-7) hook 우회 금지

`git push --no-verify` / `git commit --no-verify` / `--no-gpg-sign` 등 금지. hook 실패 → 원인 수정 → 재커밋. 우회 필요한 정당한 사유 있으면 nmae 사전 보고.

### 1-8) 보호 영역 — 정보성 분류 (라벨 의무 폐지 2026-05-28)

다음 경로 변경은 PR title / scope 에서 명확히 신호 (CLAUDE.md §4 SoT). **rev sub-agent 가 review 대행하므로 별도 라벨 부착 의무 없음** ([[feedback-needs-human-review-deprecated]]):

- `.github/workflows/**`, `.github/CODEOWNERS`, `**/db/migration/**`, `**/resources/db/**`
- `**/application*.yml`, `.env*`, `backend/build.gradle*`, `backend/gradle/**`
- `web/next.config.*`, `web/package.json`, **lockfile 전체**
- `Dockerfile`, `docker-compose*.yml`, `LICENSE`

`.github/workflows/auto-label.yml` 가 변경 감지 시 core.notice 로 visibility 만 제공 (라벨 자동 부착 / check fail 폐지). rev 사이클이 다른 PR 과 동일하게 통과 의무 — release 머지만 사용자 명시 확인.

### 1-9) PR 생성 표준 — `--base develop` 강제 ([[feedback-pr-base-develop]])

생략 시 GitHub repo default(`main`) 로 만들어져 `main` 직접 변경 risk. release PR (`develop → main`) 만 §8 에서 `--base main` (sub-agent 작성 금지, nmae 전용).

```bash
gh pr create --base develop --title "<type>(<scope>): <제목> (#<이슈>)" --label "type:<t>,scope:<s>,ai-generated,ai:claude,session:<role-token>" --body "..."
```

### 1-10) PR session 라벨 명시 부착 (#1002)

`auto-label.yml` 파일 경로 추론은 fallback. sub-agent 가 자기 정체 명시:

| role | session 라벨 토큰 (실제 repo 라벨, [[feedback-session-label-tokens]]) |
|---|---|
| be | `session:backend` |
| fe | `session:frontend` |
| rev | `session:review` |
| plan | `session:plan` |
| helper sub-agent | `session:helper` |

### 1-11) Discord thread 진행 stream + cycle forum 본문 정제 (PR D, 2026-05-28)

cycle forum thread 본문은 `agent-launch-wrapper.sh` 가 launch 시점에 template 박음 (📋 진행 6 체크박스). **sub-agent 가 큰 milestone (분석 완료 / PR 생성 / PR 머지) 시 본문의 체크박스 [x] update + 댓글 append**:

```bash
# milestone 시 (예: PR 생성 후) 본문 PATCH — discord-reply.sh --forum-edit
bash /home/mobruji/.mobruji/discord-reply.sh --forum-edit "$LAUNCH_THREAD_ID" "$(cat <<'EOF'
🛠️ **{...기존 title...}**
...
📋 진행
- [x] launch
- [x] 분석 / 설계 — {짧은 요약}
- [x] 구현 — branch={...}
- [x] 검증 — checkstyle+spotless+test green
- [x] PR 생성 — #1234
- [ ] PR 머지

🔖 관련
- PR: #1234
- directive: {id} (있으면)
---
_갱신: {ts}_
EOF
)"

# 댓글 append 는 milestone 1 줄 stream (기존 패턴 유지)
bash /home/mobruji/.mobruji/discord-reply.sh --auto-thread "[milestone] PR #1234 생성"
```

본문 PATCH = 사용자가 thread 한 번 보면 어디까지 진행됐는지 즉시 파악 (사용자 정정 2026-05-28). 댓글 = milestone 이력 추적.



nmae/helper 가 `--auto-ack-thread` 로 사전 thread 생성 → `~/.mobruji/last-launch-thread.txt` atomic write. sub-agent 권장 호출 (file 자동 read, hallucination 우회):

```bash
bash /home/mobruji/.mobruji/discord-reply.sh --auto-thread "[milestone] <짧은 진행 1줄>"
```

milestone: 이슈 등록 / 브랜치 생성 / 첫 commit / push / PR 생성 / PR 머지 / daemon 재시작 / 검증 통과 / 완료 보고. main 채널 (`#모부르지`) 직접 push 는 최종 보고 1건 또는 0건. thread 만료 / 4xx 는 graceful skip — turn 안 깨짐.

### 1-12) 보고 표현

- 영어 동사 `push` / `post` / `send` 금지 → "알려 드리겠습니다 / 보고 드리겠습니다" ([[feedback-discord-tone-formal]]).
- 줄임 표현 금지: "별 sub" → "별도 sub-agent", "별 PR" → "별도 PR". 비문 / 미완성 금지 — 짧아도 완성 문장 ("근본 fix 필요" → "근본 원인 fix 가 필요합니다.").

### 1-13) directive board event-driven sync (#1129)

directive 작업 완료 보고 직전 의무:

```bash
bash ~/.mobruji/directive_status.sh <directive_id_or_thread_id> completed [pr_url]
```

jsonl entry status + Discord forum 태그 retag + body PR URL 을 atomic. directive 가 아닌 (단순 사이클 후속 백로그) 경우 skip.

### 1-14) watchdog inject 대응 — sub-agent 입장은 §4 보고 양식만 책임

nmae watchdog inject 절차 자체는 `actors/nmae.md §11-2` SoT. sub-agent 본인은 자기 완료 보고 시 §4 "다음 사이클 후보" 를 제시해 nmae 가 단계 1 후보를 1초 안에 선정할 수 있게 돕는 것이 1차 방어선.

---

## 2) 역할별 추가 룰

### 2-be (mobruji-be)

- 작업 가능: `backend/**`, `docs/features/*.md`(backend 부분), `docs/ai-harness/06-domain-model.md §5/§6` (Spring entity 변경 시)
- 금지: `web/**`, 공유 영역 (`CLAUDE.md` / `docs/ai-harness/**` 단 §5/§6 제외) / root 설정
- 품질 게이트 (push 전): `cd backend && ./gradlew checkstyleMain spotlessCheck test` — 포맷 위반 시 `./gradlew spotlessApply`
- **새 엔드포인트 = 성공 케이스 E2E (RestAssured) 필수** (`07-testing-guide.md`)
- DDD 계층 침범 금지 (Controller → Repository 직접 호출 등)

### 2-fe (mobruji-fe)

- 작업 디렉토리: `cd /home/mobruji/mobruji-fe/web` 한 번 박고 시작
- 작업 가능: `web/**`, `docs/features/*.md`(UI 부분). 금지: `backend/**` / 공유 영역
- 품질 게이트: `cd /home/mobruji/mobruji-fe/web && npm run lint && npm run typecheck && npm test && npm run build`
- API 호출 = `web/src/lib/api/` 집중. `NEXT_PUBLIC_*` / 서버 전용 구분.
- **의존성 설치 / `node_modules` 조작 절대 금지** ([[feedback-npm-install-symlink-swap]]) — `npm install` / `npm ci` / `pnpm install` / `yarn` / `rm` / `mv` / `ln` 모두 금지. symlink 보존이 필수. 누락 (`Cannot find module …`) 시 nmae 보고 + 사이클 일시 정지.
- `web/package.json` / lockfile 변경 = 정보성 보호 영역 (라벨 의무 폐지 2026-05-28, rev 가 review 대행).

### 2-rev (mobruji-rev)

- **파일 수정 절대 금지** (`pre-push` hook 으로 push 차단). PR 코멘트만.
- **매 사이클 첫 액션**: `bash /home/mobruji/mobruji/tools/rev-queue/rev-queue.sh all` — 3 stage 큐 discovery ([[feedback-rev-queue-script]]). 큐 출력 → §E-2 절차 → 라벨 → 다음 호출 자동 제외 (멱등성).
- **3단계 e2e** ([[feedback-rev-e2e-always]] [[feedback-rev-release-gate]]):
  - 단계 1 (PR 머지 전) — `reviewed:claude` 라벨 + ✅/📝/❌ 코멘트 의무 (없으면 `rev-gate.yml` fail → 머지 차단)
  - 단계 2 (develop 머지 후 dev 환경) — `rev-post-merge-pass` / `regression:dev` 라벨
  - 단계 3 (release 후 production) — `rev-prod-pass` / `regression:prod` 라벨
  - 상세 절차 / 명령 / 라벨 reference: **`docs/features/rev-e2e-3-stages.md` SoT**
- **감사 표준 절차** (비기능 매트릭스 grep / LGTM self-guard / 누적 경고 봉인 / 결론 헤더 폐기): **`docs/features/rev-qa-protocol.md` SoT** ([[feedback-rev-qa-protocol]]).
- **단계 별 보고 템플릿 + Discord push 차등** (사용자 정정 2026-05-28 — rev 작업 가시화): `docs/features/rev-qa-protocol.md §5-9` SoT. 단계 1 = cycle forum push / 단계 2,3 = DIGEST push / ❌ = DIGEST + 사용자 reply. PR 코멘트 format 통일 (`rev단계N: 🟢/🟡/🔴 ...` 검색 패턴).
- **round 종료 wrapper 호출 의무** (강제 메커니즘): rev 매 round 종료 직전 다음 명령 호출. 누락 = 사용자 가시화 X.
  ```bash
  bash tools/rev-queue/round-summary.sh <round_id>
  ```
  wrapper 가 jsonl scan + 단계 별 push 분기 + ❌ 사용자 reply 자동. 상세: `rev-qa-protocol.md §5-9-5`.
- **flock 의존 shell test 실행 시 wrapper 의무** (PR #1194, 이슈 #1192): macOS rev 환경에 `flock` 명령 부재로 lock 의존 shell test 가 false-fail 하는 사고 (rev #1175 보고: 14건 false-fail) 가 박제됨. lock 의존 shell test 는 **반드시 `tools/rev-queue/flock-fallback.sh exec` wrapper 통해 실행** — 로컬 flock 가용 시 직접 실행, 부재 시 자동 NCP ssh fallback (`MOBRUJI_NCP_HOST` 설정 시) 또는 graceful warning + exit 3. 직접 호출 금지. 상세: `tools/rev-queue/README.md §flock-fallback.sh`.

  ```bash
  # 검증 명령 예시 (macOS rev 환경 false-fail 방지)
  bash tools/rev-queue/flock-fallback.sh detect tests/lock-dependent-test.sh  # exit 0=의존 1=비의존 2=파일없음
  MOBRUJI_NCP_HOST=user@ncp-host \
    bash tools/rev-queue/flock-fallback.sh exec tests/lock-dependent-test.sh   # 자동 fallback
  ```

- 발견 사항은 PR 코멘트만. 이슈 등록은 nmae.

### 2-plan (mobruji-plan)

- 작업 가능: `docs/ai-harness/**`, `docs/features/**`, `docs/decisions/**`, `scripts/**`, `.github/**` (보호 영역 라벨 필수)
- 금지: `backend/**` / `web/**` 구현 코드
- ADR/spec 규약: `docs/decisions/README.md`, `docs/features/README.md`, `docs/features/_template.md`
- **신규 spec frontmatter 의무** ([[feedback-spec-frontmatter-required]]) — `_template.md` 의 `---` ~ `---` 블록 복제 + 8 필드 (feature/slug/status/owner/scope/related_issues/related_prs/last_reviewed). push 전 `head -1 docs/features/<slug>.md` 가 `---` 인지 확인. `.github/workflows/spec-status-check.yml` 가 누락 시 fail → 머지 차단.

### 2-helper (sub-agent, helper 본체가 `Agent` 도구로 launch)

- 작업 가능: helper 본체와 동일 — 사용자 응답 / helper 자체 수정 / discord-reply.sh / tools/discord-daemon 등
- 금지: `backend/**` / `web/**` 도메인 구현 (be/fe 영역, nmae 위임)
- PR 라벨: `session:helper` 명시 부착 (브랜치 prefix 자유 — 자동 부착 룰이 모호)

#### directive 본문 정제 task (spec [[directive-board-template-and-tags]] §5-6)

**helper 본체가 매 turn-start 시 queue 의 `type=directive_polish` pending task 발견 → helper sub-agent batch launch**. 사용자 응답 우선 → 그 후 polish task 처리.

**launch prompt 패턴** (helper 본체가 Agent 도구 호출):
```
공통 룰: docs/ai-harness/actors/sub-agent.md §2-helper. 역할 = helper sub-agent.

Task: directive_polish batch (N건).

pending list (helper-queue.jsonl 의 status=pending + type=directive_polish):
- directive_id=<X>, thread_id=<A>, raw_body="<...>"
- directive_id=<Y>, thread_id=<B>, raw_body="<...>"

각 directive 마다:
1. cycle-status.json 의 최근 사이클 상황 read (컨텍스트 파악)
2. 한 줄 요약 + 1-2 문장 컨텍스트 + category 분류 (🎯 결정 / 🛠️ 작업 / 🐛 사고 / 💡 spec) 생성
3. discord-reply.sh --forum-edit <thread_id> "<정제된 본문 (spec §5-3 정제 후 template)>" 호출
4. discord-reply.sh --forum-retag <thread_id> directive "<category tag>" 호출

완료 후 보고: 처리 N건, OK X건, fail Y건 + fail 사유.
```

**helper 본체가 sub-agent 보고 받은 후 helper-queue 의 처리 완료 task status: pending → done atomic update**.

batch 효과: 1 launch 가 N task 처리 — launch overhead 분담. N=1 도 정상 동작 (overhead 그대로지만 흐름 일관). 한 turn 처리 한도 = max 5 (5+ 이면 다음 turn 에 남은 것 처리).

### 2-기획·이슈 등록

be / fe / rev = **이슈 등록 금지** (nmae 보고만). plan 은 docs/spec/ADR 일환으로 직접 등록 가능.

---

## 3) prompt 예시

좋은 예 (이번 사이클 한정만):

```
공통 룰은 docs/ai-harness/actors/sub-agent.md 따른다. 역할은 be.

이번 사이클:
- 이슈: #92 — RecommendationRequest 캐싱
- 브랜치: feat/recommendation-cache-#92
- 요구사항: Caffeine 적용, TTL 5분, max 1000, E2E cache hit 확인
- 완료 후 PR URL + mergeable + 게이트 통과 여부 보고
```

나쁜 예: 공통 룰 300줄 매번 박기.

---

## 4) 완료 보고 표준 양식

### 4-1) 필수 헤더

- PR URL + draft/ready + mergeable (yes/no/UNKNOWN)
- 변경 한 줄 요약 (코드 dump 금지)
- 품질 게이트 결과 (be: checkstyle+spotless+test / fe: lint+typecheck+test+build / plan: 해당 없음)
- 보호 영역 변경 여부 (정보성 — 라벨 의무 폐지 2026-05-28)

### 4-2) 발견 사항 분류

| 분류 | 의미 | nmae 처리 |
|---|---|---|
| 🔴 | 시급 — 회귀 / 보안 / 결정성 위반 | 즉시 launch 또는 revert |
| 🟡 | 보강 — 컨벤션 / 관측성 / 문서 drift | 백로그 등록 |
| 🟢 | 관찰 — 패턴 / 메타 | 메모리 갱신 또는 ADR 트리거 |

### 4-3) 다음 사이클 후보

같은 도메인 백로그 1~3개 제시 — nmae launch loop 가 idle 워크트리 빠르게 채우도록 ([[feedback-keep-4-cycles-active]]).

---

## 5) 안티패턴

| 안티패턴 | 회피 |
|---|---|
| 다른 워크트리 / 도메인 침범 | prompt 첫 줄 `cd <자기 워크트리>` 박고 그 외 경로 접근 금지. 풀스택 기능은 두 PR 분리. |
| 시크릿 raw 출력 | grep mask / "redacted" 표기 (`04-security-policy.md`) |
| spec 무시 | Feature Spec §3 체크박스 미확인 후 구현 — `CLAUDE.md §7-1` 자기 점검 |
| hook 우회 / 메모리 직접 수정 / nmae 룰 재해석 | 원인 수정 → 재커밋 / 메모리 후보만 보고 / nmae prompt 외 작업은 별도 사이클 후보로 |
| 워크트리 lock 위반 무시 | `git status` 예상과 다르면 즉시 nmae 보고 + turn 종료 |
