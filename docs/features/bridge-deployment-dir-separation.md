---
feature: Bridge 배포 dir 분리 (옵션 A 근본 fix)
slug: bridge-deployment-dir-separation
status: approved
owner: @mobruji-maestro
scope: infra
related_issues: [1109, 1126]
related_prs: [1126]
last_reviewed: 2026-05-26
---

# Bridge 배포 dir 분리 (옵션 A 근본 fix)

## 1) 개요 (What / Why)

NCP 호스트에서 운영되는 Discord bridge 데몬 (`mobruji-discord-bridge.service`) 은 현재 메인 repo 의 working tree (`/home/mobruji/mobruji`) 에서 실행된다. 메인 repo 는 nmae / helper / sub-agent 가 자유롭게 브랜치 전환을 수행하는 작업장이기 때문에, **임의 브랜치에 묶인 채 봇이 재부팅되면 프로덕션 봇 코드가 그 브랜치 버전으로 오염된다**.

2026-05-26 14:30 KST 사고: stale 브랜치 `feat/writing-marker-reaction-typing-#1095` 에 묶인 채 호스트 재부팅 → 프로덕션 bot.py 가 PR #1095 머지 전 구버전 (auto-ack text mode) 으로 작동. 사용자가 #모부르지 에서 reaction 기반 자동 ack 가 안 보인다고 정정.

근본 원인: **봇 실행 dir 과 작업 dir 이 같은 git working tree**. 옵션 A (사용자 결정 2026-05-26 15:05 KST) 채택 — develop 고정 전용 dir 신설 후 systemd unit 의 WorkingDirectory 를 그쪽으로 이전.

### 적용 대상 / 비대상

- **적용**: bot.py (`mobruji-discord-bridge.service`) — 메인 채널 / forum / cron digest / cycle watchdog 등 사용자에게 직접 가시화되는 모든 bridge 동작.
- **비적용**: helper / nmae claude session 의 작업장 — 메인 repo (`/home/mobruji/mobruji`) 를 그대로 사용. helper / nmae 가 임의 브랜치 전환을 수행하는 것은 의도된 정상 동작이며, 그 효과가 봇에 새지 않도록 봇만 분리한다.

## 2) 사용자 시나리오

- **시나리오 1**: nmae 가 bot.py 변경 PR (`feat/discord-*-#N`) 을 develop 에 머지한 후, NCP 호스트에서 봇 코드 업데이트를 트리거하려고 한다. 전용 dir 에서만 `git fetch && git reset --hard origin/develop && sudo systemctl restart mobruji-discord-bridge` 1회로 반영. 메인 repo 의 현재 브랜치 / working tree 와 무관.
- **시나리오 2**: helper 가 사용자 응답 처리 중 `git checkout fix/foo-#N` 으로 브랜치 전환. 봇은 영향 없음 — 봇은 전용 dir 의 develop checkout 으로 실행 중.
- **시나리오 3**: 호스트 비정상 재부팅 발생 (cron 작업 OOM 등). 봇이 자동 재시작될 때, **항상 develop 의 마지막 deploy 된 commit 으로 기동** 한다. 메인 repo 의 우연한 브랜치 상태에 의존하지 않는다.

## 3) 요구사항

### 기능 요구사항

- [ ] 전용 dir `/home/mobruji/mobruji-bridge` 신설 (clone 권고 — git worktree 는 .git 공유로 인해 브랜치 전환 위험이 잔존).
- [ ] systemd unit `mobruji-discord-bridge.service` 의 `WorkingDirectory` 를 `/home/mobruji/mobruji-bridge` 로 변경. `ExecStart` venv 경로도 전용 dir 기준으로 변경.
- [ ] 전용 dir 의 git 상태는 항상 `origin/develop` 의 최신 commit 에 fast-forward. 다른 브랜치로 전환 금지 (운영 룰).
- [ ] 봇 코드 업데이트는 전용 dir 에서만 `git fetch && git reset --hard origin/develop && sudo systemctl restart mobruji-discord-bridge` 절차로 반영.
- [ ] 메인 repo 의 브랜치 전환이 봇 동작에 영향을 주지 않는다는 사실을 검증 (시나리오 2).

### 비기능 요구사항

- **신뢰성**: 호스트 재부팅 후에도 항상 develop 의 마지막 deploy 된 commit 으로 기동. 우연한 브랜치 상태 의존 X.
- **운영 안전성**: bot.py 코드 변경 PR 머지 → develop 갱신 → 전용 dir reset → 봇 재시작. 4 단계가 명시적으로 분리됨.
- **롤백 가능성**: 전용 dir 신설 실패 / 운영 사고 시 메인 repo 사용 fallback 으로 즉시 복귀 (§6 rollback plan).

## 4) 범위 / 비범위 (중요)

### 포함

- bot.py (`mobruji-discord-bridge.service`) 의 WorkingDirectory 이전.
- venv / .env / `~/.mobruji` 심링크 등 동반 의존성의 정합성 확보 (§5-3 gotcha 명시).
- 전용 dir 의 git 운영 룰 (develop 고정).
- 검증 절차 (§5-4) — 메인 repo 브랜치 전환 영향 없음 confirm.
- rollback plan (§6).

### 제외 (Out of Scope)

- 다른 데몬 (`mobruji-helper.service` 등) 의 분리 — 본 spec 은 bridge 에 한정. 동일 패턴 적용은 후속 PR.
- helper / nmae claude session 의 작업장 변경 — 의도된 정상 동작이므로 손대지 않음.
- CI / deploy pipeline 의 자동화 — 본 spec 은 manual 절차 명문화. cron 자동 update 는 후속 (제외).
- Docker / container 화 — 본 spec 은 NCP host native systemd 운영을 전제. 컨테이너 전환은 별도 ADR.

## 5) 설계

### 5-1) 전용 dir 신설 방법 (clone 권고)

후보 2종:

| 방법 | 장점 | 단점 |
|---|---|---|
| **clone** (권고) | `.git` 디렉토리 독립 — 메인 repo 의 브랜치 전환과 완전 격리. config 도 독립. | 디스크 사용량 ↑ (~수십 MB). 첫 clone 시 wall-clock ~수분. |
| git worktree | 디스크 효율 (`.git` 공유). 신설 wall-clock 빠름. | `.git/worktrees/` 가 같은 .git 을 공유 — 메인 repo 의 `git gc` / `git prune` 가 worktree 영향 가능. 또한 같은 .git 의 다른 worktree 가 같은 브랜치를 checkout 하면 충돌. **branch 전환 위험이 미묘하게 잔존**. |

**결정**: clone 채택. 디스크 비용은 무시 가능 수준이며, 격리의 명확성이 운영 안전성을 압도한다.

표준 명령:

```bash
cd /home/mobruji
git clone --branch develop --single-branch https://github.com/<org>/mobruji.git mobruji-bridge
cd mobruji-bridge
git config remote.origin.fetch '+refs/heads/develop:refs/remotes/origin/develop'
```

`--single-branch` 로 develop 만 fetch 하여 실수로 다른 브랜치 checkout 차단 + `git config remote.origin.fetch` 로 fetch 범위 명시 제한.

### 5-2) systemd unit 변경

대상: `/etc/systemd/system/mobruji-discord-bridge.service`.

변경 전 (예상):

```ini
[Service]
WorkingDirectory=/home/mobruji/mobruji
ExecStart=/home/mobruji/mobruji/tools/discord-daemon/venv/bin/python tools/discord-daemon/bot.py
```

변경 후:

```ini
[Service]
WorkingDirectory=/home/mobruji/mobruji-bridge
ExecStart=/home/mobruji/mobruji-bridge/tools/discord-daemon/venv/bin/python tools/discord-daemon/bot.py
```

적용 절차:

```bash
sudo systemctl daemon-reload
sudo systemctl restart mobruji-discord-bridge
sudo journalctl -u mobruji-discord-bridge -n 30   # 새 WorkingDirectory 적용 + 정상 boot 확인
```

`mobruji-discord-bridge.service` 자체는 **보호 영역** (`/etc/systemd/system/`) 이며, 본 spec 자체는 docs 만 변경. unit file 실제 수정은 후속 infra PR 에서 nmae 가 직접 수행한다 (sudo 권한 필요).

### 5-3) 동반 의존성 정합성 (gotcha)

#### 5-3-1) venv

- 현재 venv 경로: `/home/mobruji/mobruji/tools/discord-daemon/venv` (메인 repo 내부, gitignore).
- 전용 dir 신설 시 venv 는 자동으로 따라오지 않음 — 옵션 2종:
  - **옵션 A1**: 전용 dir 에 별도 venv 생성. 표준 절차:
    ```bash
    cd /home/mobruji/mobruji-bridge/tools/discord-daemon
    python3 -m venv venv
    venv/bin/pip install -r requirements.txt
    ```
    장점: 완전 독립. 단점: 의존성 업데이트 시 2 venv 동기화 필요.
  - **옵션 A2**: systemd ExecStart 의 venv 경로를 절대경로로 고정 (메인 repo 의 venv 를 그대로 사용).
    ```ini
    ExecStart=/home/mobruji/mobruji/tools/discord-daemon/venv/bin/python /home/mobruji/mobruji-bridge/tools/discord-daemon/bot.py
    ```
    장점: venv 단일 운영. 단점: 메인 repo 의 venv 가 깨지면 봇도 깨짐 (메인 repo 의 가벼운 의존성 사고가 봇에 전파).
- **권고**: 옵션 A1 (별도 venv). 격리 원칙 일관.

#### 5-3-2) .env (BOT_TOKEN 등 시크릿)

- 현재 경로: `/home/mobruji/mobruji/tools/discord-daemon/.env` (gitignore, NCP 호스트에만 존재).
- 옵션 2종:
  - **옵션 B1**: 전용 dir 에 복사.
    ```bash
    cp /home/mobruji/mobruji/tools/discord-daemon/.env /home/mobruji/mobruji-bridge/tools/discord-daemon/.env
    chmod 600 /home/mobruji/mobruji-bridge/tools/discord-daemon/.env
    ```
    장점: 완전 독립. 단점: .env 변경 시 2 곳 동기화 필요 (drift 위험).
  - **옵션 B2**: symlink.
    ```bash
    ln -s /home/mobruji/mobruji/tools/discord-daemon/.env /home/mobruji/mobruji-bridge/tools/discord-daemon/.env
    ```
    장점: 단일 source. 단점: 메인 repo dir 이동 / 삭제 시 symlink 깨짐.
- **권고**: 옵션 B2 (symlink). .env 는 변경 빈도 낮고 (cron 갱신 X), 메인 repo dir 이동 가능성도 거의 없음. drift 위험 회피가 우선.

#### 5-3-3) `~/.mobruji/` 헬퍼 스크립트 심링크

- `discord-reply.sh` / `helper-turn-start.sh` / `nmae-discord-push.sh` 등이 현재 `~/.mobruji/` 아래 심링크 또는 wrapper 로 메인 repo 의 `tools/discord-daemon/*` 또는 `tools/helper/*` 를 가리킨다.
- 봇이 전용 dir 로 이전해도 **helper / nmae claude session 의 헬퍼 스크립트 호출 경로는 메인 repo 그대로** — 봇이 받는 incoming Discord webhook 이 아니라 송신 측 (helper / nmae) 의 의존성이기 때문.
- **단**, 봇 자체가 호출하는 헬퍼 (예: bot.py 내부에서 `subprocess.run(["/home/mobruji/.mobruji/discord-reply.sh", ...])`) 가 있다면, 그 경로는 봇이 어느 dir 에서 실행되든 절대경로이므로 변경 불요.
- **검증 항목** (§5-4):
  - 봇 재기동 후 `~/.mobruji/` 심링크가 가리키는 메인 repo 경로가 깨지지 않았는지 (`ls -la ~/.mobruji/discord-reply.sh` 등).
  - bot.py 가 호출하는 subprocess 의 working dir 이 전용 dir 로 변경되어도 헬퍼가 정상 동작하는지 (e.g., relative path 사용 여부 점검).

#### 5-3-4) 분리 범위 명확화

| 컴포넌트 | 분리 대상 여부 | 운영 dir |
|---|---|---|
| `mobruji-discord-bridge.service` (bot.py) | **분리 대상** | `/home/mobruji/mobruji-bridge` |
| `mobruji-helper.service` (있다면) | **분리 비대상** (본 spec) | `/home/mobruji/mobruji` (또는 별도 helper dir) |
| helper claude session (NCP `tmux helper:0.0` 가 있다면) | **분리 비대상** | 메인 repo (의도된 정상 동작) |
| nmae claude session (NCP `tmux mobruji:0.0`) | **분리 비대상** | 메인 repo (의도된 정상 동작) |
| sub-agent 워크트리 (be/fe/rev/plan) | **분리 비대상** | `/home/mobruji/mobruji-{be,fe,rev,plan}` (이미 분리됨) |

본 spec 은 **bot.py bridge 만** 다룬다. 추후 다른 데몬 분리가 필요해지면 별도 spec.

### 5-4) 검증 절차

전용 dir 신설 + systemd unit 변경 + 봇 재시작 후 다음을 순서대로 confirm:

1. **봇 정상 boot 확인**:
   ```bash
   sudo journalctl -u mobruji-discord-bridge -n 50 --no-pager | grep -E "(ERROR|started|connected)"
   ```
   - `Bot connected to Discord` 또는 동등 log line 확인. ERROR 없음.
2. **새 WorkingDirectory 적용 확인**:
   ```bash
   sudo systemctl show mobruji-discord-bridge -p WorkingDirectory
   ```
   - 출력: `WorkingDirectory=/home/mobruji/mobruji-bridge`.
3. **전용 dir 의 git 상태 확인**:
   ```bash
   cd /home/mobruji/mobruji-bridge && git branch --show-current && git log -1 --oneline
   ```
   - 출력: `develop` + 최신 develop commit.
4. **메인 repo 브랜치 전환 영향 없음 confirm** (핵심):
   ```bash
   cd /home/mobruji/mobruji && git checkout -b throwaway-test/bridge-isolation-check && touch /tmp/test-isolation && git status
   # 봇은 영향 없어야 함
   sudo journalctl -u mobruji-discord-bridge --since "30 seconds ago" | grep -E "(ERROR|crash|stopped)"
   # 출력: 비어있어야 함
   cd /home/mobruji/mobruji && git checkout develop && git branch -D throwaway-test/bridge-isolation-check
   ```
5. **봇 코드 업데이트가 전용 dir 갱신 시에만 반영됨 confirm**:
   - develop 에 bot.py 의 minor docstring 변경 PR 머지 후
   - 메인 repo 에서 develop pull 만 했을 때 봇 동작 변화 X (전용 dir 미갱신)
   - 전용 dir 에서 `git fetch && git reset --hard origin/develop && sudo systemctl restart mobruji-discord-bridge` 한 후에만 변화 반영
   - 이 차이가 명확히 보여야 분리 성공.

### 5-5) 데이터 흐름 / 시퀀스

```text
nmae develop 머지
        │
        ▼
[메인 repo /home/mobruji/mobruji] develop pull           ← helper / sub-agent 작업장 (영향 없음)
        │
        │  (별도 명령)
        ▼
[전용 dir /home/mobruji/mobruji-bridge] git fetch && git reset --hard origin/develop
        │
        ▼
sudo systemctl restart mobruji-discord-bridge
        │
        ▼
journalctl -u mobruji-discord-bridge -n 30   ← 새 코드 boot 검증
```

## 6) rollback plan

전용 dir 신설 / unit 변경이 실패하거나 운영 사고를 일으킬 경우 메인 repo 사용 fallback 으로 즉시 복귀:

```bash
# 1. unit 원복
sudo sed -i 's|/home/mobruji/mobruji-bridge|/home/mobruji/mobruji|g' /etc/systemd/system/mobruji-discord-bridge.service
sudo systemctl daemon-reload
sudo systemctl restart mobruji-discord-bridge

# 2. (선택) 전용 dir 정리
# 보존 권고 — 다음 시도 시 재사용 가능. 디스크 부담 미미.
```

rollback 후 무엇이 실패했는지 사후 분석. 본 spec 의 §5 절을 그에 맞춰 보강.

## 7) 작업 분할 (예상 PR 리스트)

- [x] PR 1 (plan PR #1126): docs/features/bridge-deployment-dir-separation.md 작성.
- [x] PR 1b (be PR — 본 spec 동반): bot.py 에 ``verify_deploy_dir`` fail-fast 검증 + ``tools/discord-daemon/deploy.sh`` helper 추가. spec 본문도 develop 도달 보장. 코드 변경만 — sudo / systemd 변경 없음.
- [ ] PR 2 (infra, nmae 직접): NCP 호스트에서 전용 dir clone + venv 신설 + .env symlink + unit file 수정. 사용자 sudo 권한 필요 → nmae 가 검증 단계까지 가시화. ``deploy.sh`` 첫 실행 검증 포함.
- [ ] PR 3 (docs, plan follow-up): 검증 결과 본 spec §9 결정 로그 추가. status approved → implementing → shipped 전이.

## 8) 테스트 전략

- **검증 항목**: §5-4 의 5 단계 모두 통과.
- **failure mode 분석**:
  - venv 누락 → bot 기동 실패 (`ModuleNotFoundError`). § 5-3-1 옵션 A1 확실히 적용.
  - .env symlink 깨짐 → BOT_TOKEN 환경변수 누락 (`KeyError: 'BOT_TOKEN'`). §5-3-2 옵션 B2 적용 후 `ls -la` 로 symlink 유효 확인.
  - 전용 dir 의 develop 이 stale (fetch 안 함) → 봇 코드가 옛 버전. §5-4 의 5단계 검증.
- **회귀 점검**: 본 spec 채택 후 1 주일간 매 develop 머지 → 봇 재시작 사이클이 정상 작동하는지 nmae 가 모니터링.

## 9) 결정 로그

- 2026-05-26 — 초안 작성 (plan sub-agent, 이슈 #1109). status=approved. 사용자 결정 (2026-05-26 15:05 KST): 옵션 A (전용 dir 신설) 채택. 본 spec 의 §5-3 gotcha 별 권고 (venv 옵션 A1 / .env 옵션 B2 / symlink 보존) 는 plan sub-agent 자율 결정 — nmae 가 PR 2 진행 시 검증 후 반영 권고. follow-up 이슈로 사용자 재확인 가능.
- 2026-05-26 — be sub-agent (PR 1b) 가 spec 동반 코드 추가. (a) ``bot.verify_deploy_dir`` — bot.py main() 시작부에서 ``__file__`` 의 ancestor 가 ``MOBRUJI_BRIDGE_DEPLOY_DIR`` (default ``/home/mobruji/mobruji-bridge``) 인지 확인. mode: off / warn (default) / strict — 마이그레이션 중에는 warn 으로 dual-run, NCP 전환 완료 후 strict 전환. (b) ``tools/discord-daemon/deploy.sh`` — ``git fetch && git reset --hard origin/develop && sudo systemctl restart`` 절차 자동화 + 안전 가드 (.git 존재 / 브랜치 검증 / dry-run / journal 출력). 결정 사유: NCP 호스트 전환 (PR 2) 이전에도 develop 에 코드가 도달하면 즉시 마이그레이션 가능 + verify 함수가 추후 rollback / fallback 시 잘못된 dir 실행 사고를 가시화. 5-3 gotcha 별 권고값과 충돌 없음.

## 10) 자율 결정 (사유)

- 결정 1: clone 방식 채택 (worktree 대신).
  - 사유: worktree 는 같은 .git 을 공유하므로 메인 repo 에서 `git gc` 또는 다른 worktree 가 같은 브랜치 checkout 시 충돌 가능. 격리의 명확성이 디스크 비용 (~수십 MB) 을 압도. 운영 안전성 우선.
- 결정 2: venv 옵션 A1 (별도 venv) 권고.
  - 사유: 격리 원칙 일관. 의존성 동기화 부담은 `requirements.txt` 정합으로 cover 가능.
- 결정 3: .env 옵션 B2 (symlink) 권고.
  - 사유: .env 변경 빈도가 낮고, drift 위험이 디스크 격리 가치보다 큼. 메인 repo dir 이동 가능성은 거의 없음.

위 3 결정 모두 follow-up 이슈로 사용자 재검토 가능 (현재 결정으로 PR 2 진행 가능).

## 11) 사용자 확인 필요

- 없음. 사용자 옵션 A 결정 이후 plan sub-agent 자율 채택 가능한 항목으로 구성.

## 12) References

- 이슈 #1109 — 5 spec + 1 ADR + 1 sweep spec bundle (직전 PR f139504, plan 사이클).
- ADR-0023 — workflow / unit file main 미동기화 사고 박제 + sync 전략. 본 spec 의 unit file 분리 후 정식 release main 도달 절차에 의존.
- `docs/features/systemd-restart-always.md` — 다른 systemd unit 운영 spec (분리 비대상이나 패턴 참고).
- `CLAUDE.md §4 비협상 룰` — main 직접 push 금지 + 보호 영역 라벨.
- `docs/ai-harness/02-agent-workflow.md §8` — release 절차.
- 메모리: [[feedback-verify-and-iterate]] / [[feedback-autonomous-default]] / [[feedback-keep-promises]].

## 13) 변경 이력

- 2026-05-26 — 초안 작성 (plan sub-agent, 이슈 #1109). status=approved. 사용자 옵션 A 결정 박제 + 5 검증 단계 + rollback plan + 3 gotcha 권고. follow-up PR 2 (infra, nmae 직접) 의존성 명시.
- 2026-05-26 — be sub-agent (PR 1b) 동반 코드 추가: ``bot.verify_deploy_dir`` (off / warn / strict 3-mode), ``tools/discord-daemon/deploy.sh`` helper. 작업 분할 §7 에 PR 1b 추가. related_issues 에 #1126 / related_prs 에 #1126 박제.
