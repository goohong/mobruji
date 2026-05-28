# Multi-Session Runbook

> Claude/Codex를 여러 세션 동시에 돌릴 때의 셋업·운영 런북.
> `docs/ai-harness/02-agent-workflow.md §10`(다중 AI 운영 룰)을 실행 가능한 형태로 풀어 적은 문서.

## 0) 운영 모드

4개 sub-agent 워크트리(be/fe/rev/plan)를 maestro가 동시 가동하는 방식은 두 가지. **기본은 maestro 오케스트레이션** (§0-10 항시 4 워크트리 가동 룰 참고).

### 0-1) maestro 오케스트레이션 (기본 / 권장)

**maestro(`mobruji` 워크트리)의 Claude 세션 하나**가 오케스트레이터 역할을 한다. be/fe/rev/plan 작업은 maestro가 `Agent` 도구로 `claude` subagent를 `run_in_background: true`로 띄워 각 워크트리에서 실행하게 한다. 사용자는 maestro 한 곳에서 진행 상황을 따라간다.

사이클:
1. 백로그 정해지면 maestro가 각 워크트리에 `cd`해 `new-session-branch.sh`를 실행 → 이슈/브랜치/Draft PR 사전 스캐폴드.
2. maestro가 `Agent` 도구로 be/fe/rev/plan 서브에이전트를 동시 background 가동 (최대 4개 병렬, §0-10 참고).
3. 각 서브에이전트는 자기 워크트리에서 코드/문서 작성 → 품질 게이트 → push → `gh pr ready`.
4. maestro는 완료 통지를 받고 사용자에게 머지 결정 요청 (release는 사용자 확인, develop 머지는 자율).

서브에이전트 프롬프트에 반드시 포함:
- `cd <워크트리 절대경로>`로 시작 강제
- 다른 워크트리/maestro 건드리지 마
- 메모리(`~/.claude/projects/*/memory/`) 쓰기 금지
- `--no-verify`로 hook 우회 금지
- 보호 영역 변경 시 `needs-human-review` 라벨 부여

> 위 5줄 공통 룰은 매번 반복하지 말고 prompt에 다음 한 줄만 박는다:
> `공통 룰은 docs/ai-harness/actors/sub-agent.md 따른다. 역할은 <be|fe|rev|plan>.`
> §12 에는 위 5줄 외에도 다음이 박혀 있다 — 별도 prompt 박지 말 것:
> - NCP Linux 워크트리 절대경로 (`/home/mobruji/mobruji-{be,fe,rev,plan}`)
> - maestro 항시 가동 / 워크트리 lock / 5분 reasoning chunk 룰
> - 역할별 추가 룰 (작업 가능 경로, 품질 게이트, fe `npm install` 1회 룰 등)
> - sub-agent → maestro 완료 보고 표준 양식 (🔴/🟡/🟢 + 다음 사이클 후보)
> - 안티패턴 매트릭스 (워크트리 침범 / 도메인 boundary / 시크릿 raw / hook 우회 등)

**언제 쓰나**: 사용자가 백로그를 maestro에 풀어놓고 한 자리에서 운영하고 싶을 때. 대부분의 경우.

### 0-2) 수동 터미널 (대체)

사용자가 워크트리마다 별도 터미널과 Claude 인스턴스를 띄워 직접 지시한다. maestro는 develop 점유와 공유 영역 관리만 담당.

사이클: §3 (새 작업 시작)과 §4 (rev 운영) 단계를 사용자가 직접 트리거.

**언제 쓰나**:
- 서브에이전트가 의도와 다르게 동작해 maestro에서 개입이 잦아질 때
- maestro가 다른 큰 작업을 동시에 진행 중이라 오케스트레이션 부담이 클 때
- 사람-루프(human-in-the-loop) 빈도를 늘리고 싶을 때

### 0-3) 모드 전환

같은 사이클 중간에 모드 전환은 피한다. 한 사이클(이슈→PR→머지)은 한 모드로 끝내고, 다음 사이클부터 바꾼다. 강제 전환이 필요하면 진행 중 서브에이전트를 정리(`TaskStop` 등) 후 수동 터미널로.

### 0-4) 사이클 명명

maestro task list에서 사이클을 부를 때 **도메인별 카운트**를 유지한다. 단순함 우선.

- `be 사이클 N`, `fe 사이클 N`, `rev 사이클 N`, `plan 사이클 N` (각자 1부터 카운트)
- 도메인 간 비교가 필요하면 PR 번호(`#76`, `#81`)로 지칭. 사이클 번호는 maestro 내부 task tracking 용도.
- 통합 카운트(예: "전체 사이클 12")는 쓰지 않는다. 도메인이 달라 의미 약함.
- 표기 패턴: `<도메인> 사이클 <N> (#<PR>) <작업 한 줄>` — 예: `be 사이클 5 (#74) excludeSongIds 영속화`. task list/사용자 보고/PR 본문 일관 적용.

### 0-5) idle 사이클 룰

세션이 idle 상태(의존 PR 머지 대기, 머지된 PR 없음 등)일 때 maestro는 다음 백로그를 자체 진행하도록 지시한다:

| 세션 | idle 조건 | 자체 백로그 |
|---|---|---|
| **be** | 의존 ADR/spec 머지 대기 | 작은 nit/refactor (Lombok 정리, final 누락 보완, 메서드 네이밍), 백엔드 테스트 회귀 보강(BDD 스타일 누락 케이스) |
| **fe** | API 의존 또는 디자인 결정 대기 | 컴포넌트 테스트 보강, UX 다듬기(loading/error state), a11y 점검 |
| **rev** | 머지된 PR 없음 / 리뷰 큐 빔 | `develop` 전체 QA — BE 회귀(`./gradlew test`), FE 게이트(`npm run lint/typecheck/test/build`), 통합 시나리오(`docker compose up` + bootRun + dev), 발견 시 maestro에 보고 |
| **plan** | 사이클 작업 완료 후 idle | 다음 ADR/spec 후보 발굴, 메모리 → 코드 promote 검토(반복 패턴/preference 코드화), 문서 stale 점검 |

idle 룰 적용 기준:
- be/fe가 의존성 대기로 30분+ idle이면 maestro가 위 백로그 중 하나를 launch
- rev는 머지 즉시 트리거가 기본이지만, 머지된 PR이 1시간+ 없으면 자체 QA 사이클 launch
- plan은 사용자가 운영 사이클 종료를 명시할 때까지 백로그 발굴 진행

### 0-6) 사용자 결정 묶음 질문 패턴

maestro가 사용자에게 결정을 묻는 빈도를 조정해 컨텍스트 스위칭 비용을 줄인다.

**즉시 묻기 (interrupt-driven)**:
- PR 머지 / `develop → main` 릴리즈 머지
- 보호 영역 변경의 사후 리뷰
- 사이클 step change (다음 백로그 우선순위 재정렬)
- 코드/기획 충돌로 사람만 풀 수 있는 결정

**묶어 묻기 (batched)**:
- 작은 의사결정(naming, 사소한 UX 선택, 비기능 옵션 default) 5건 이상 누적 시 한 번에 모아 질문
- 묶음 형식 예시:
  ```
  결정 묶음 4건:
  1. ADR 0006 제목 — "추천 결정성 정책" vs "추천 결정성 + 다양성 정책"?
  2. fe 사이클 9 — 에러 boundary fallback 카피 "다시 시도" vs "재시도"?
  3. ...
  답을 한 번에 주면 maestro가 일괄 적용.
  ```
- 사용자 부재 시: 사용자가 돌아오기 전까지 maestro가 합리적 가정으로 진행 + 가정 명시 + 사후 정정 허용.

### 0-6-1) maestro 닫혀있을 때 모바일 모니터링

maestro Claude 세션을 닫으면 background sub-agent도 모두 종료되어 사이클이 멈춘다. 사용자가 외출 중 사이클 상태를 인지하려면 **Discord webhook 모니터링**을 깐다: PR/이슈/릴리즈 이벤트를 GitHub Actions가 Discord 채널에 push → 모바일 알림. 셋업·운영은 `docs/ai-harness/14-discord-ops.md` 참조. workflow 본체는 `.github/workflows/discord-notify.yml`이며 secret 부재 시 graceful skip.

### 0-6-2) 사이클 트레일 push 룰 (maestro 의무)

maestro가 자율 사이클을 돌릴 때 — 사이클 launch / 오류 / 결정 분기 — 가 Discord #모부르지 채널에 1줄로 expose되도록 **GitHub events로 변환**하는 룰은 `docs/features/discord-status-push.md` 가 정형화한다. 핵심 의무:

| 사이클 단계 | maestro 행동 | webhook 흐름 |
|---|---|---|
| 사이클 launch | `gh issue create` (사이클 1개 = 이슈 1개 1:1) | `issues:opened` → discord-notify.yml |
| sub-agent stall / CI 실패 | 별도 `type:bug` 이슈 등록 | `issues:opened` → discord-notify.yml |
| 결정 분기 | 사이클 이슈에 `decision:pending` 라벨 | 6h 다이제스트 (discord-periodic-summary.yml) |
| PR open/머지/close | sub-agent 또는 maestro가 정상 흐름 | `pull_request:*` → discord-notify.yml |

**금지**: NCP에서 maestro가 Discord webhook URL을 `.env`로 보유하고 curl 직접 호출하는 패턴. secret 이중 보유 회피. webhook URL은 GitHub repo secret `DISCORD_WEBHOOK_URL` 단일 SoT.

### 0-7) 사이클 완료 후 워크트리 정리

PR 한 묶음(예: be+fe+rev 3건)을 머지한 후 maestro는 다음을 호출해 모든 워크트리를 develop 최신으로 detach 시키고 머지된 로컬 branch를 정리한다:

```bash
./scripts/post-merge-cleanup.sh
```

동작:
- maestro + be/fe/rev/plan 워크트리에서 `git fetch origin develop` + `git checkout --detach origin/develop`
- maestro에서 `origin/develop`에 머지된 로컬 branch 일괄 삭제 (develop/main 제외, 다른 워크트리 사용 중인 branch는 skip)

수동으로 maestro에서 `git -C ../mobruji-be reset --hard origin/develop` 호출하던 패턴을 대체한다. 사이클 종료 직후 1번만 호출하면 다음 사이클을 clean 상태에서 시작할 수 있다.

#### 옵션

```
Usage: post-merge-cleanup.sh [--force]
  --force: dirty 워크트리도 강제 reset (작업 분실 위험)
```

- **기본 (안전)**: 워크트리가 dirty(unstaged/staged 변경 또는 untracked 파일)면 detach를 skip + 경고. 사람이 직접 정리.
- **`--force`**: dirty 무시하고 `git reset --hard origin/develop` + `git clean -fd`로 강제 reset. **stash되지 않은 변경은 영구 손실**. maestro가 명시적 결정 후에만 사용.

maestro가 사이클 직전에 발견하지 못한 unstaged 파일(예: 이전 세션이 남긴 임시 산출물)이 detach 실패의 흔한 원인이라, 기본은 보수적으로 skip하고 force가 필요할 때만 명시한다.

#### fe 워크트리 `node_modules` 동기화

`post-merge-cleanup.sh`는 `node_modules`를 **건드리지 않는다** (의도적). git이 추적하지 않는 디렉토리라 detach/reset 영향 밖이고, 매 사이클마다 재설치하면 wall-clock 손해가 크다.

다만 다음 상황에서 `node_modules`가 stale 상태가 된다:
- fe 사이클이 처음 launch될 때 워크트리에 `node_modules`가 아예 없음
- web deps를 추가한 PR이 머지된 직후 (예: PR #150 `pitchy`, PR #194 PWA service-worker)
- `package.json` / `package-lock.json`이 develop에서 갱신되었는데 fe 워크트리의 설치본은 이전 버전

##### 누가 install 하는가 — sub-agent는 직접 install 금지 (핵심 룰)

fe sub-agent의 prompt에는 **`npm install` / `npm ci` / `pnpm install` / `yarn` 등 의존성 설치 명령을 직접 실행하지 않는다**를 명시한다. 의존성 누락(`Cannot find module 'pitchy'` 등)을 발견하면 **maestro에 보고하고 사이클을 일시 멈춘다**. maestro가 직접 install을 실행하거나 사용자 결정 후 진행한다.

이유: fe 워크트리의 `web/node_modules`는 외부 데이터 디스크 symlink로 운영되는 경우가 많다(아래 §node_modules 저장 위치 참조). sub-agent가 `--no-save`/`--legacy-peer-deps` 등 옵션으로 무심코 `npm install`을 돌리면 npm이 symlink를 일반 디렉토리로 재생성하면서 외부 디스크 마운트가 끊긴다. 한 번 깨지면 모든 fe 워크트리에 영향을 주고 복구는 maestro 권한에서 swap + symlink 재생성이 필요하다 (아래 §복구 절차).

maestro 권한에서 install이 필요한 경우:

```bash
# maestro 워크트리에서 (fe 워크트리 아님)
cd /home/mobruji/mobruji/web && npm install
```

`mobruji` 워크트리의 `web/node_modules`도 같은 외부 디스크 symlink (`/data/node_modules/web`)를 가리키도록 셋업되어 있으면, 한 번의 install이 모든 fe 워크트리에 즉시 반영된다.

##### `node_modules` 저장 위치 (외부 디스크 운영 시)

NCP maestro 등 루트 디스크가 작은 환경에서는 `node_modules`를 별도 데이터 디스크에 두고 워크트리에서 symlink로 참조한다.

```bash
# 한 번 셋업
sudo mkdir -p /data/node_modules/web /data/node_modules/fe-web
sudo chown -R mobruji:mobruji /data/node_modules

# 각 워크트리에서 symlink 연결
ln -snf /data/node_modules/web      ~/mobruji/web/node_modules
ln -snf /data/node_modules/fe-web   ~/mobruji-fe/web/node_modules
```

확인:
```bash
ls -la ~/mobruji-fe/web/node_modules
# lrwxrwxrwx ... web/node_modules -> /data/node_modules/fe-web
```

##### 복구 절차 — symlink가 풀렸을 때

증상:
- `ls -la web/node_modules`가 디렉토리(`drwx...`)로 보임 (symlink였어야 함)
- 또는 외부 디스크 사용량은 그대론데 워크트리 루트 디스크가 갑자기 부풀어 오름
- sub-agent 로그에 `npm install` 또는 `npm ci` 실행 흔적

복구 (maestro 권한):

```bash
# 1. 깨진 디렉토리를 외부 디스크로 이동 (이미 install된 내용 보존)
mv ~/mobruji-fe/web/node_modules /data/node_modules/fe-web-recovered
rm -rf /data/node_modules/fe-web
mv /data/node_modules/fe-web-recovered /data/node_modules/fe-web

# 2. symlink 재생성
ln -snf /data/node_modules/fe-web ~/mobruji-fe/web/node_modules

# 3. 확인
ls -la ~/mobruji-fe/web/node_modules
# lrwxrwxrwx ... -> /data/node_modules/fe-web
```

복구 후 sub-agent prompt에 "이전 사이클에서 symlink 사고가 있었음. 의존성 누락 시 maestro 보고만"이라고 명시해 재발 방지.

##### maestro가 fe sub-agent prompt에 박을 한 줄

`cd web && npm install` 직접 실행 금지. 의존성 누락 시 maestro에 보고. 직전 사이클에서 web deps 변경 PR이 머지됐고 누락이 의심되면 그것도 보고.

변경이 없는 사이클에서는 install 자체가 불필요하므로 sub-agent는 평소대로 `npm run lint / typecheck / test`만 돌리면 된다.

### 0-8) 통지 우선 처리

maestro는 자기 작업 도중 sub-agent 완료 통지를 받으면 **자기 작업의 현재 도구 호출 단위를 마치고 통지 처리부터** 한다. wall-clock 최소화 + 다음 사이클 launch 지연 방지 목적.

처리 순서:
1. 결과 보고 — 사용자에게 한두 줄로 요약 (PR 번호 + mergeable 상태 정도)
2. rev 코멘트 자동 등록 — §0-9 절차 따라 이슈 등록 (rev 완료 통지일 때만)
3. 다음 sub-agent scaffold + launch — 백로그가 살아있으면 즉시
4. 자기 작업으로 복귀 — 컨텍스트 회복 후 멈춘 지점에서 계속

maestro 작업은 **호흡당 1~2 도구 호출** 단위로 쪼갠다. 긴 단위로 묶으면 통지 도착해도 처리 지연이 늘어난다.

통지 동시 도착 시 우선순위:
1. 🔴 발견 (spec/비기능 위반) — 즉시 다음 사이클 fix 트리거 결정 필요
2. release / `develop → main` 머지 결정 — 사용자 컨펌 대기
3. 일반 보고 (✅ OK, 🟡 개선)

### 0-9) rev 코멘트 자동 등록

rev sub-agent 완료 통지를 받으면 maestro는 발견 항목을 **GitHub 이슈로 자동 등록**한다. 사용자가 일일이 트리아지하지 않아도 다음 사이클 백로그가 자동으로 쌓이는 구조.

분류 기준:
- 🔴 **spec/비기능 위반** — 각각 **단독 이슈**로 등록. 같은 사이클에 다음 be/fe sub-agent로 즉시 fix 트리거 가능한 것은 1~2건 한정.
- 🟡 **개선/drift/nit** — **PR 단위 묶음 1개 이슈**. 7건 이상이면 우선순위 상위만 등록 + 나머지는 `backlog` 라벨로 stash.
- 🟢 **OK / 합격** — 등록하지 않는다.

이슈 본문 필수 포함:
- rev 코멘트 URL 인용 (`gh pr view <N> --comments`로 확인)
- 영향 받는 spec/스코프 (`docs/features/*.md` 또는 ADR 번호)
- 추정 작업 시간 (sub-agent 1사이클 안에 끝낼 수 있는지)

라벨:
- 🔴 → `type:fix` + 해당 `scope:*` + `session:backend|frontend` 후보
- 🟡 묶음 → `type:refactor` 또는 `type:chore` + `backlog`

가장 시급한 🔴은 같은 사이클에 다음 be/fe sub-agent로 **즉시 트리거**. 묶음 처리하면 회귀 누적되니 spec 위반은 핫라인 처리.

누적 패턴/메타 인사이트(예: "최근 5사이클 연속 같은 final 누락 패턴")는 **별 docs PR 후보**로 따로 모은다. 이슈 등록과 docs promote는 분리.

### 0-10) 항시 4 워크트리 가동 룰

maestro는 **be/fe/rev/plan 4 워크트리에 sub-agent 1개씩 가동을 항상 유지**한다. 1개 sub-agent 완료 통지가 들어오면 같은 워크트리에 **즉시** 다음 백로그를 launch한다. idle 워크트리를 두지 않는 게 default — 자체 reasoning 만 돌리면 컨텍스트 폭증 + 병렬 처리 부재 + 사용자 의도(4 워크트리 분리) 위반 (사용자 명시 2026-05-23: "4개 사이클 가동하고 유지해", "아예 launch를 까먹은거잖아 그러면 안 돼. 절대로").

#### maestro 점검 의무

| 시점 | 점검 항목 |
|---|---|
| **매 turn 시작** | 사용자 메시지 받자마자 — 백로그에 be/fe/rev/plan 가능 작업 있나? 있으면 maestro 자체 작업 전에 sub-agent launch (`run_in_background=true`). |
| **매 turn 끝** | 응답 직전 마지막 점검 — 이 turn에 발견된 작업 중 sub-agent 가능한 것 즉시 launch. |
| **wake fire 시** | autonomous wake (`feedback-autonomous-wake-pattern`) 발화 시 4 워크트리 모두 가동 중인지 확인. idle 있으면 즉시 launch. |
| **완료 통지 수신** | sub-agent return 받자마자 — PR 검토/보고 정리 (maestro 메타) → 같은 워크트리 다음 백로그 즉시 launch (§0-8 통지 우선 처리와 동일 순서). |

#### maestro 자체 작업 default

maestro가 직접 코드/테스트를 작성하는 패턴은 **금지에 가깝게 제한**. maestro default는 메타:
- spec / ADR / 메모리 작성·갱신
- 핸드오프 / 사용자 보고 / orchestration
- Discord 답변, 이슈 트리아지
- 보호 영역(`docs/ai-harness/**`, CLAUDE.md, root 설정)의 일회성 보수 (sub-agent에 위임할 수 없는 영역)

코드/테스트/문서 본문 작성은 **모두 sub-agent default**. maestro가 직접 하면 4 워크트리 중 하나가 idle.

#### 백로그 고갈 시 발굴

idle 워크트리가 발생하면 maestro는 다음 순서로 발굴:

1. `gh issue list --state open --label "scope:<domain>"` — 미할당 이슈
2. `docs/features/` — 미구현/얇은 spec
3. `docs/ai-harness/` — stale/누락 룰
4. rev QA 리포트 / 메모리 (`feedback-*`) — 코드 promote 후보
5. TODO / FIXME 코멘트 회수
6. 작은 리팩터 / 테스트 보강 / 회귀 가드 신설 / 보안 audit

**가치 점검 필수** — 발굴된 작업이 release 가치(회귀 가드 / 보안 / 디스크 / 가시성 / 정합성 중 1개 이상)를 만족하지 못하면 launch하지 않고 메타(문서/메모리/백로그 청소)로 위임. 사이클 수 채우려고 가치 없는 작업 양산 금지.

#### 워크트리 lock

같은 워크트리에 동시 2 sub-agent launch 금지 (`feedback-worktree-lock`). 이전 sub-agent 머지/종료 통지를 받은 뒤 다음 launch. 따라서 동시 가동 최대치는 **4** (4 워크트리 × 1).

#### 5분 룰 병행

maestro turn 자체가 5분 초과(`feedback-reasoning-chunk-limit`)하면 turn을 분할. 분할된 다음 turn에서 idle 워크트리 launch를 다시 점검.

#### 위반 시 자기 점검

maestro가 sub-agent launch를 1회라도 까먹은 채 maestro 자체 작업을 진행했다면 즉시 사용자에게 인정 + 사과 + 룰 재확인. 메모리(`feedback-sub-agent-launch-mandatory`)와 본 §0-10이 source of truth — 충돌 시 본 문서 우선.

### 0-11) cycle-status tooling — nmae 의무 (2026-05-24 박제)

§0-10 의 4 워크트리 가동 룰을 실제 enforce 하는 외부 안전망. **메모리/룰 위반 시도 정정**. nmae 가 본 절차 모르면 watchdog inject 무한 loop 발생 (실 사고 #970).

#### bot.py `cycle_idle_watch_loop` (외부 watchdog)
- **5분 polling** `~/.mobruji/cycle-status.json` → 4 워크트리(be/fe/rev/plan) `in_progress` 검사
- idle 발견 시 nmae tmux pane (`mobruji:0.0`) 에 `[watchdog ...] cycle-status.json idle 발견 — <ws>. ...` inject + Discord `DIGEST_CHANNEL_ID` push (#1019 rename — 기존 `NOTIFY_CHANNEL_ID` 도 backward-compat 인식)
- **escalation**: 같은 워크트리 inject 3회 연속 + `in_progress` 여전히 NULL → `MOBRUJI_CHANNEL_ID` (사용자 채널) 에 `🚨 nmae 무응답` 직접 push (debounce 1h)
- spec: `docs/features/nmae-cycle-watchdog.md`, CLAUDE.md §14

#### nmae inject 대응 의무 (4단계 — 한 단계라도 누락 시 무한 loop)
nmae 가 watchdog inject 받으면 **다음 turn 시작 즉시**:

1. 백로그 후보 1개 선정 (이슈 / PR follow-up / docs drift)
2. `bash /home/mobruji/mobruji/tools/cycle-status/update.sh <ws> set-active --title "<후보 한 줄>"`
3. `Agent` tool 로 sub-agent launch (`/home/mobruji/mobruji-<ws>`)
4. `bash /home/mobruji/.mobruji/discord-reply.sh "<ws> 사이클 재개 — <후보>"`

상세: `docs/ai-harness/actors/sub-agent.md §1 nmae watchdog inject 대응`.

#### cycle-status.json 갱신 의무 (수동 편집 금지)
nmae 가 매 sub-agent launch / 완료 / idle 진입 시 **반드시 헬퍼 스크립트 경유**:

```bash
# active 갱신 (in_progress 채움, idle_since clear)
bash tools/cycle-status/update.sh <ws> set-active --title "<후보>" [--task "<상세>"]

# idle 갱신 (in_progress null, note 의무)
bash tools/cycle-status/update.sh <ws> set-idle --note "<사유 또는 다음 launch 후보>"

# 완료 기록
bash tools/cycle-status/update.sh <ws> set-completed --pr "#NNN" --title "<요약>"
```

- 수동 JSON 편집 금지 (timezone bug / atomic write race / 스키마 drift 위험)
- idle 시 `note` 필드 의무 — 빈 string → watchdog STRICT relaunch prompt 즉시 inject
- 검증: `bash tools/cycle-status/validate.sh` — idle 워크트리 note 누락 detect (nmae self-check 용)
- 스키마: `tools/cycle-status/README.md`

#### 3중 안전망 (CLAUDE.md §14 와 정합)
1. **bot.py watchdog** — 외부 데몬, 최후 보루
2. **nmae 매 turn 종료 직전 자기 점검** — cycle-status.json 4 워크트리 active 검증
3. **helper 우연 발견 시 직접 inject** — 같은 서버라 `tmux send-keys` 가능 ([[feedback-helper-role-boundary]] 위임 영역)

### 0-12) rev 큐 스크립트 — rev sub-agent 매 사이클 첫 액션 (2026-05-24 박제)

rev sub-agent 가 매 사이클 시작 시 `bash tools/rev-queue/rev-queue.sh all` **첫 액션 의무**. 메모리/룰 학습 의존 X — **GitHub 라벨 + 본 스크립트 = single source of truth**.

| Stage | 의미 | 후보 필터 |
|---|---|---|
| stage1 | PR 머지 전 (단계 1 e2e) | `reviewed:claude` 라벨 없는 open PR |
| stage2 | develop 머지 1h+ 후 (단계 2 사후) | `rev-post-merge-pass` 라벨 없는 merged PR |
| stage3 | 최근 release tag PR (단계 3 production) | `rev-prod-pass` 라벨 없는 release PR |

처리 절차 (§E-2 / §E-3): rev sub-agent prompt `docs/ai-harness/actors/sub-agent.md §2 rev / §E-3` 참조. 라벨 부착 후 다음 rev-queue 호출에서 자동 제외 (멱등성).

**GitHub Actions 게이트**: `.github/workflows/rev-gate.yml` 이 `reviewed:claude` 라벨 + 단계 1 코멘트 부재 시 머지 차단. whitelist: `needs-human-review` / `type:release`.

상세: `tools/rev-queue/README.md`, `docs/features/rev-e2e-3-stages.md`, CLAUDE.md §4 품질 게이트.

## 1) 셋업 (최초 1회)

### 1-1) 워크트리 4개 생성

```bash
# maestro는 ~/workspace/github/mobruji (또는 NCP 환경의 ~/mobruji) 그대로
git worktree add --detach ../mobruji-be
git worktree add --detach ../mobruji-fe
git worktree add --detach ../mobruji-rev
git worktree add --detach ../mobruji-plan
```

총 5개 디렉토리(maestro 1 + sub-agent 워크트리 4)가 셋업된다. **plan 워크트리**는 ADR/spec/`docs/ai-harness/` 갱신 전담(§2 참조). v0.2 메타 전환 이후 maestro 부담을 덜기 위해 도입됐다 (`feedback-plan-session-option`, `project-plan-session-active`).

`--detach`인 이유: git은 같은 브랜치(develop)를 여러 워크트리에서 동시에 체크아웃 못 함. detached로 만들면 각 세션에서 `new-session-branch.sh`가 `origin/develop`을 기준으로 새 브랜치를 만들어 작업한다.

확인:
```bash
git worktree list
# /home/mobruji/mobruji         <sha> [develop]
# /home/mobruji/mobruji-be      <sha> (detached HEAD)
# /home/mobruji/mobruji-fe      <sha> (detached HEAD)
# /home/mobruji/mobruji-rev     <sha> (detached HEAD)
# /home/mobruji/mobruji-plan    <sha> (detached HEAD)
```

> NCP maestro VM 기준 경로 예시. 사용자 macOS 셋업은 `/Users/<id>/workspace/github/mobruji*`. 경로만 다르고 셋업 절차는 동일.

### 1-2) 메모리 디렉토리 공유 (선택)

Claude는 워크트리 경로별로 별 메모리를 갖는다. maestro 메모리(사용자 선호·feedback)를 모든 세션에서 공유하고 싶으면 symlink:

```bash
BASE=~/.claude/projects/-Users-goohong-workspace-github-mobruji/memory
for w in be fe rev plan; do
    target=~/.claude/projects/-Users-goohong-workspace-github-mobruji-$w/memory
    mkdir -p "$(dirname "$target")"
    ln -snf "$BASE" "$target"
done
```

NCP maestro VM 기준 경로 prefix는 `-home-mobruji-mobruji`로 다르다. 셋업 환경별로 prefix만 맞춰 동일 루프 사용.

> ⚠️ **메모리 race 주의**
>
> 본 symlink는 3개 세션이 **같은** 메모리 디렉토리(특히 `MEMORY.md`)를 공유하게 만든다. 두 세션이 동시에 같은 파일을 쓰면 마지막 write가 이전 write를 덮어쓴다.
>
> - **안전한 패턴**: 1인이 한 번에 1세션과만 대화 (사용자 입력 단위로 자연 직렬화).
> - **위험한 패턴**: `/loop` 같은 자동 스케줄러로 여러 세션을 동시에 작업하게 둘 때, 또는 세 세션을 동시에 같은 토픽으로 직접 입력할 때.
> - **회피책**: 메모리 갱신이 잦은 세션은 1개로 제한하거나, 세션별 memory 디렉토리를 분리(symlink 대신 별 디렉토리)해서 사용. 두 번째 패턴은 maestro 메모리 공유 이점을 잃으므로 첫 번째를 권장.

### 1-3) 라벨 적용

```bash
bash scripts/setup-labels.sh
```

`session:backend`, `session:frontend`, `session:review`, `reviewed:claude` 추가됨.

### 1-4) Projects v2 보드 (사람용 대시보드)

**현재 셋업된 보드**: https://github.com/users/goohong/projects/5 (mobruji)

#### 최초 셋업 (이미 완료, 재현용 참고)
```bash
# 토큰 scope 갱신 (1회)
gh auth refresh -s project,read:project

# 보드 생성
gh project create --owner goohong --title "mobruji"

# Session 필드 추가
gh project field-create <number> --owner goohong \
    --name "Session" --data-type SINGLE_SELECT \
    --single-select-options "backend,frontend,review,infra,release"

# repo variable 등록 (auto-add workflow가 참조)
gh variable set PROJECT_URL --body "https://github.com/users/goohong/projects/5" \
    --repo goohong/mobruji
```

#### auto-add workflow 동작 조건

`.github/workflows/auto-add-to-project.yml`이 PR/이슈를 자동 등록한다. 단:

- **User-owned project**(현재 상태)는 `github.token`으로 add 불가 (GitHub 제약).
- → `repo` + `project` scope의 **Personal Access Token (Classic)** 을 발급해 repo secret `PROJECT_TOKEN`으로 등록해야 작동.

PAT 발급:
1. https://github.com/settings/tokens (classic) → Generate new token
2. Scopes: `repo`, `project`
3. 토큰을 repo secret으로 등록:
   ```bash
   gh secret set PROJECT_TOKEN --repo goohong/mobruji
   # (붙여넣기 프롬프트에 토큰 입력)
   ```
4. 이후 PR/이슈 열릴 때 자동으로 보드에 추가됨.

PAT 없이도 워크플로우는 살아있고 `skipping`으로 graceful fail. 사람이 보드에 수동 추가하면 동일 효과.

#### 수동 추가 (PAT 미사용 시)
```bash
gh project item-add 5 --owner goohong --url https://github.com/goohong/mobruji/issues/<N>
gh project item-add 5 --owner goohong --url https://github.com/goohong/mobruji/pull/<N>
```

#### Status / Session 필드 운영
- 기본 `Status`: Todo / In Progress / Done. UI에서 칸반 보드로 자동 표시.
- `Session` 필드: backend / frontend / review / infra / release 중 하나로 분류.
- 자동 전이:
  - `Status`는 `auto-update-project-status.yml`이 PR ready/draft/issue reopen 이벤트로 전이.
  - `Session`은 `auto-set-session-on-project.yml`이 PR/이슈 open/reopen/labeled 이벤트로 분류 시도.

##### auto-set-session 매핑 룰
신규 PR/이슈가 보드에 add되면 (`auto-add-to-project.yml`이 먼저 add) workflow가 다음 순서로 Session을 자동 설정한다:

1. `session:backend|frontend|review` 라벨이 있으면 그 값 (rev 세션이 이슈 등록 시 명시하는 패턴 우선).
2. 라벨이 없으면 폴백:
   - PR 제목이 `release:`로 시작 → `release`
   - 라벨 `scope:web` → `frontend`
   - 라벨 `scope:song|voice|recommendation` → `backend`
   - 라벨 `scope:infra` → `infra`
3. 위 어느 룰에도 걸리지 않으면 Session은 빈 상태로 둔다(manual 분류 필요).

PROJECT_TOKEN secret 미설정 시 graceful skip한다(`auto-add-to-project.yml`와 동일 패턴). 즉 보드 셋업 전이거나 토큰을 회수해도 workflow는 살아 있다.

##### 수동 분류가 필요한 경우
auto-set-session이 매핑에 실패해 Session 필드가 비어 있는 카드는 보드 UI에서 직접 옵션을 선택하거나 다음 명령으로 갱신:

```bash
gh project item-edit \
  --project-id PVT_kwHOBRYNe84BYVlg \
  --field-id PVTSSF_lAHOBRYNe84BYVlgzhTcJwU \
  --id <project-item-id> \
  --single-select-option-id <option-id>
```

옵션 ID: backend `aabcec2d`, frontend `0c6191d7`, review `bf8ab92d`, infra `c697cb09`, release `41252f0d`.

`<project-item-id>`는 `gh api graphql` projectItems 쿼리 또는 UI의 카드 상세에서 확인. 일반적으로는 적절한 `session:*` 또는 `scope:*` 라벨을 PR/이슈에 부여하면 workflow가 다시 트리거되어 자동 분류된다(라벨 추가 → labeled 이벤트). 라벨로 표현 가능한 케이스는 라벨을 먼저 시도하고, 표현 불가한 경우만 직접 옵션 설정으로 처리한다.

### 1-5) rev 세션 push 차단 hook 설치

`mobruji-rev` 워크트리는 리뷰 전용이라 코드를 push할 일이 없다. 실수로 변경 후 push하는 사고를 git hook 단에서 차단한다.

```bash
# 어느 워크트리에서 실행해도 됨. core.hooksPath는 모든 워크트리에 공유 적용된다.
git config core.hooksPath scripts/git-hooks
```

확인:
```bash
cd ../mobruji-rev
git commit --allow-empty -m "test" && git push 2>&1 | head -5
# → [BLOCK] rev 세션 워크트리에서는 push 금지. ... 가 출력되고 push 차단됨
```

hook 스크립트는 `scripts/git-hooks/pre-push`. 워크트리 basename이 `mobruji-rev`일 때만 차단하고, maestro/be/fe는 통과한다. 진짜 필요할 때만 `git push --no-verify`로 우회 가능(사후 보고 필요).

> ⚠️ `core.hooksPath`를 바꾸면 기존 `.git/hooks/` 안의 hook은 더 이상 실행되지 않는다. 다른 hook을 쓰고 있었다면 `scripts/git-hooks/`로 옮긴다.

## 2) 세션별 역할

| 세션 | 워크트리 | 역할 | 만질 수 있는 파일 | 금지 |
|---|---|---|---|---|
| **maestro** | `mobruji` | 오케스트레이션·**기획·이슈 등록·백로그 우선순위**·공유 영역 관리·develop 점유 | `CLAUDE.md`, `AGENTS.md`, `docs/ai-harness/**`, root 설정, 일회성 인프라 보수 PR | 다른 세션 브랜치 체크아웃(=develop 점유 해제) |
| **be** | `mobruji-be` | 백엔드 **구현 전용** | `backend/**`, `docs/features/*.md`(backend 부분), `docs/ai-harness/06-domain-model.md` §5/§6 (Spring entity 변경 시) | `web/**`, 다른 세션의 브랜치, **기획/이슈 등록(maestro에 보고만)** |
| **fe** | `mobruji-fe` | 프론트엔드 **구현 전용** | `web/**`, `docs/features/*.md`(UI 부분) | `backend/**`, 다른 세션의 브랜치, **기획/이슈 등록(maestro에 보고만)** |
| **rev** | `mobruji-rev` | 사후 감사 + **QA 실행 검증** (read + PR 코멘트만, 파일 수정 금지) | (없음 — `pre-push` hook으로 push 차단됨) | 모든 직접 수정. 이슈 등록은 maestro에 보고 |

### rev 세션의 QA 책임 (정형화)
rev는 read-only 감사 **외에 실 QA 실행 검증도 담당**한다 (사용자 결정 2026-05-22, "에러를 막는 것이 1순위"). 코드 리뷰만으로는 런타임 에러 / 환경 의존 / API 통합 실패가 잡히지 않기 때문.

**정형 spec**: `docs/features/rev-qa-protocol.md` — QA 결정 트리, smoke 시나리오, 환경, 도구, 결과 형식이 모두 한 곳에 있다. rev sub-agent는 prompt 외 추가 질문 없이 이 spec만 보고 QA를 수행할 수 있어야 한다.

**PR 범주별 QA 강제 매트릭스** (`rev-qa-protocol.md` §5-1 발췌):

| 범주 | 판별 기준 | QA 단계 |
|---|---|---|
| **A. 코드 변경 (BE)** | `backend/**` 변경 | `./gradlew test` + bootRun + 변경 endpoint smoke (curl) |
| **B. 코드 변경 (FE)** | `web/**` 변경 | `npm run lint/typecheck/test/build` + `npm run dev` + 라우트 smoke |
| **C. 통합 (FE+BE)** | A+B 동시 또는 contract 변경 | A + B + 도메인 smoke 시나리오 (음역/추천/좋아요/이력) |
| **D. auth & security** | SessionAuthGuard / AuthFilter / security yml | A 또는 B + 헤더 누락/위조로 401/403 검증 |
| **E. DB 마이그레이션** | `db/migration/**` 추가 | A + Flyway clean→migrate 재현 |
| **F. spec & docs only** | `docs/**`만 변경 | **QA 생략**, 감사만 |
| **G. CI/infra only** | workflows / docker-compose만 변경 | dry-run 또는 라인 리뷰 |
| **H. 비기능 spec 위반 위험** | 결정성/p95/관측성 도메인 (recommendation, voice) | A + spec §3 비기능 한 줄씩 검증 |

한 PR이 여러 범주에 걸치면 모두 수행 (상위 알파벳 우선 평가).

**결과 형식** (`rev-qa-protocol.md` §5-6 발췌) — PR 코멘트에 다음 마커로 시작:
- 🟢 **PASS** — 모든 시나리오 통과
- 🟡 **NOTE** — minor drift/nit. release gate 통과 가능하지만 다음 사이클 fix 권장
- 🔴 **BLOCK** — 런타임 에러, spec 위반, 회귀. release gate 차단.

형식:
```
QA: 🟢/🟡/🔴 <STATUS> — [범주 X] <시나리오 + 결과 1줄>
```

**release gate**: `develop → main` release 머지 전 rev가 미QA PR(reviewed:claude 라벨 없음) 일괄 QA 수행. 🔴가 1건이라도 있으면 release 차단 + maestro에 fix 사이클 launch 요청. QA pass PR에는 `reviewed:claude` 라벨 부여.

**워크트리 파일 수정 금지**: `scripts/git-hooks/pre-push`로 강제. 임시 스크립트는 `/tmp/rev-qa-*.sh` 또는 stdin heredoc(`bash <<'EOF' ... EOF`)으로 실행. 워크트리 안에 어떤 파일도 신규 생성·수정하지 않는다.

**환경**: 기본은 로컬 3-tier (`docs/runbooks/local-3tier-setup.md`). dev/staging은 인프라 spec 머지 후 추가. 외부 API 의존 PR은 현재 mock 또는 "외부 의존 검증 보류" 🟡 처리.

**공통 룰**:
- be/fe 세션은 `origin/develop`에서 분기 (워크트리는 detached HEAD라 develop을 체크아웃하지 않는다).
- 한 세션의 브랜치에 다른 세션이 직접 push 금지.
- 공유 영역(`CLAUDE.md`, `AGENTS.md`, `docs/ai-harness/`, root 설정) 변경은 maestro에서 처리.
- maestro는 항상 `develop` 브랜치에 머물러야 한다. maestro에서 다른 세션 브랜치를 체크아웃하면 develop 점유가 해제돼 다른 세션이 stale 참조하는 사고가 생긴다. maestro에서 일회성 PR을 만들어야 할 때는 임시 브랜치 분기 후 머지 즉시 `develop`으로 복귀.
- **자율 운영**: 사용자 부재 시에도 maestro는 sub-agent 완료 통지 → 백로그 정리 → 다음 사이클 launch 루프를 자체 진행. release(`develop → main`) 머지만 사용자 확인.

## 3) 새 작업 시작 (be/fe 세션)

해당 워크트리 디렉토리에서:

```bash
./scripts/new-session-branch.sh <session> <type> <scope> <slug> [issue_title]

# 예시:
./scripts/new-session-branch.sh be feat voice voice-range-import "외부 API에서 음역대 import"
./scripts/new-session-branch.sh fe feat web voice-range-input-page "음역대 입력 페이지"
```

스크립트가 한 번에:
1. `origin/develop` 동기화 (`git fetch origin develop` — 워크트리는 detached HEAD라 `git pull`을 쓰지 않는다)
2. 이슈 생성 (라벨: task, type:*, scope:*, ai-generated, ai:claude, session:*)
3. 브랜치 분기 (`<branch_type>/<slug>-#<issue_num>`, `origin/develop` 기준)
4. 빈 스캐폴드 커밋 + push
5. Draft PR 생성 + 라벨 부여

작업 끝나면:
```bash
git push
gh pr ready <PR번호>   # draft → ready for review
```

## 4) 리뷰 세션 (rev) 운영

워크트리 `mobruji-rev`에서 Claude 세션 실행. 사용자가 한 번 트리거하거나 `/loop`로 주기 실행:

```
/loop 15m PR 리뷰 한 번 돌려
```

또는 수동 트리거 시 다음 패턴:

```
다음 작업을 진행해줘:
1. gh pr list --search "is:open draft:false -label:reviewed:claude" --json number,title
2. 각 PR마다:
   - gh pr view <N> --json title,body,labels
   - gh pr diff <N> 으로 변경 확인
   - 관련 spec(docs/features/*.md) Read해서 정합성 확인
   - 코드 컨벤션(docs/ai-harness/08-code-conventions.md) 위반 체크
   - 0~N개 review 코멘트 작성: gh pr review <N> --comment --body "..."
   - 끝나면 라벨 부여: gh pr edit <N> --add-label reviewed:claude
3. 결과 요약
```

리뷰 세션은 **절대 코드/파일을 수정하지 않는다**. PR 코멘트만.

## 5) 동기화 / 충돌 처리

### 자연스러운 동기화
- 모든 세션은 GitHub state(PR/이슈/라벨)를 같은 source로 봄.
- be/fe 워크트리는 detached HEAD 상태이므로 **`git checkout develop`을 쓰지 않는다.** develop은 maestro가 점유 중이라 다른 워크트리에서 체크아웃하면 충돌한다.
- 작업 시작 직전: 해당 워크트리에서 `git fetch origin develop` → `new-session-branch.sh`가 `origin/develop` 기준으로 새 브랜치를 만든다.
- 머지 후 동기화:
  - **maestro** 워크트리: `git pull --ff-only`로 develop 최신화.
  - **be/fe** 워크트리: `git fetch origin develop`만. 이미 작업 브랜치에서 작업 중이라면 필요 시 `git rebase origin/develop`.

### 자동 충돌 감지
`.github/workflows/session-collision-check.yml`이 PR 열릴 때:
- 다른 open non-draft PR과 변경 파일이 겹치는지 검사
- 겹치면 PR에 경고 코멘트

코멘트가 뜨면:
- 두 세션 작업 중 어느 쪽이 후순위가 될지 사용자가 결정
- 후순위는 선순위 머지 후 rebase

### 사람 중재가 필요한 케이스
- 공유 영역(`CLAUDE.md` 등) 변경
- spec(`docs/features/*.md`) 동시 수정
- 도메인 모델 §5/§6 동시 수정
- 다른 세션의 브랜치 hotfix가 필요한 경우

## 6) Troubleshooting

### worktree에서 `gh` 명령이 안 됨
같은 repo이므로 작동해야 함. 토큰 scope 확인: `gh auth status`.

### Claude 세션이 메모리 못 읽음
worktree 경로가 정확한지 확인. `.claude/projects/<encoded-path>/memory/` 가 존재해야 함. symlink 셋업했으면 link target 확인.

### 동시 세션 두 개가 같은 브랜치를 만지려 함
규칙 위반. 한 세션이 stop. 사용자가 누가 진행할지 결정.

### Projects v2에 자동 등록 안 됨
- `PROJECT_URL` repo variable 확인: `gh variable list`
- 워크플로우 로그 확인: `gh run list --workflow=auto-add-to-project.yml`
- 토큰 권한 부족이면 PAT를 `PROJECT_TOKEN` secret으로 추가

## 7) 정리 / 폐기

세션 끝내고 워크트리 제거:
```bash
git worktree remove ../mobruji-be
# 메모리 디렉토리는 직접 정리
rm -rf ~/.claude/projects/-Users-goohong-workspace-github-mobruji-be
```

영구 셋업이라면 그대로 두고 사용.

## 8) 본진 가볍게 유지 (nmae lightweight orchestration)

사용자 2026-05-26 ~16:00 정정: "동시 사이클 4개 유지. 과부하 대응 = 본진 context 가벼이 유지 + 위임."

### 룰
- 동시 사이클 수는 **4 (be/fe/rev/plan) 고정**. 과부하 시에도 줄이지 않는다.
- 본진(nmae) 책임 = **오케스트레이션 only**.
  - launch / 위임 prompt 작성
  - 완료 통지 수신 + cycle-status.json 갱신 + 다음 백로그 launch
  - PR 머지 결정 (가능하면 머지 실행 자체도 sub-agent 위임 또는 cron auto-merge)
  - cron digest 가 cover 안 하는 1회성 사용자 보고
- 본진 직접 수행 **금지** — 코드 변경, 길어진 git 작업, gradle/npm 실행, 대량 로그 분석. 이 모두는 sub-agent 또는 helper-launched sub-agent 영역.
- 본진 context% marker (CLAUDE.md §14) 75% 이상 → 본진 추가 작업 자제 + sub-agent 위임 + 다음 turn `/clear` 후보.

### 왜
- 본진이 직접 구현하면 1 사이클 turn 이 길어진다 → 다른 3 사이클 launch 가 끊긴다.
- 본진 context 가 폭증하면 `/clear` 빈도가 늘어나며, `/clear` 직전 doc-check (CLAUDE.md §15) 도 누락 위험이 커진다.
- 위임은 sub-agent 워크트리 격리 + 코드 변경 트래킹 가능 (PR / git log) 이라 감사 trail 도 확보된다.

### 검증
- nmae turn 종료 시점에 cycle-status.json 4 워크트리 모두 `in_progress` 또는 `last_completed.completed_at` 이 최근 10 분 안인지 확인.
- nmae context% marker 가 매 turn 마지막 emit 되고 75% 이상 시 자율 정리 트리거됐는지 확인.
- cron digest 가 본진 활동 vs sub-agent 활동을 분리 표시하는지 확인 (본진 직접 머지 / 본진 직접 코드 변경 = 위반 후보).

관련: CLAUDE.md §11-5 (4 사이클 동시 launch + 본진 오케스트레이션 only), `docs/features/autonomous-cycle-orchestration.md`.
