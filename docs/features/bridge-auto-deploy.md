---
feature: mobruji-bridge 자동 배포 고리
slug: bridge-auto-deploy
status: implementing
owner: @goohong
scope: infra
related_issues: [1475, 1479]
related_prs: []
last_reviewed: 2026-06-02
---

# mobruji 체크아웃 자동 배포 고리 (bridge + agent)

## 1) 개요 (What / Why)

`mobruji-discord-bridge.service`(bot.py)는 NCP 의 전용 develop-고정 체크아웃 `/home/mobruji/mobruji-bridge` 에서 실행된다([[project_bridge_deploy_decoupling]]). 그런데 이 체크아웃을 **develop 최신으로 당기는 자동 고리가 없어**, 2026-06-02 기준 develop 보다 13커밋 뒤처진 채 옛 코드를 실행하고 있었다. 그 결과:

- 단계 2 용어 정정(#1462)·directive id 매칭 fix(#1474) 등이 bot 에 반영 안 됨.
- directive 오링크 사고(#1473 — 산문 속 directive id 오매칭)가 fix 배포 전까지 지속.

본 기능은 bridge 체크아웃을 **주기적으로 develop 최신과 동기**하고, bot 이 실행하는 코드가 바뀐 경우에만 서비스를 재시작하는 자동 배포 고리다.

## 2) 설계 결정

- **방식 = NCP systemd timer** (GitHub Actions SSH 배포 아님). 사유: 핵심 동작이 "로컬 systemd 서비스 재시작"이라 root 가 자연스럽고, SSH 경유 `sudo systemctl` 의 sudoers 설정·원격 권한 복잡도를 피한다. NCP 과부하(#1453)로 GitHub→NCP 네트워크가 흔들려도 로컬 타이머는 독립 동작한다.
- **두 체크아웃을 같은 스크립트로 처리(#1479)**. bridge 체크아웃(`/home/mobruji/mobruji-bridge` → `mobruji-discord-bridge`)과 agent 체크아웃(`/home/mobruji/mobruji` → `mobruji-agent`)은 각각 별도 timer 를 갖되, 동기·조건부 재시작 로직은 `bridge-auto-deploy.sh` **단일 스크립트를 매개변수(체크아웃/서비스/감시경로)로 재사용**한다. 스크립트 복제로 인한 드리프트(한쪽만 고쳐지는 사고)를 막는다. 인자 없이 호출하면 bridge 기본값이라 기존 bridge timer 는 무변경.
  - **계기**: agent 체크아웃에 자동 배포가 없어 2026-06-02 develop 보다 16커밋 뒤처진 채 옛 코드를 실행, #1455 fix 등이 라이브 미반영(#1479).
  - agent timer 의 ExecStart 는 **bridge 체크아웃의 스크립트**(항상 최신 동기)를 호출해 자기 체크아웃을 갱신하는 순환 의존을 피한다.
- **코드 변경 시에만 재시작**: 변경 파일에 `tools/discord-daemon/` 또는 `tools/agent/`(bot 이 실행/참조하는 코드)가 포함될 때만 `systemctl restart`. web/backend/docs 만 바뀐 develop 머지로는 체크아웃만 갱신하고 Discord 브리지를 끊지 않는다.
- **fast-forward 전용**: 로컬이 develop 에서 갈라졌으면(조상 아님) 손대지 않고 경고 후 exit — 강제 reset 으로 작업 유실/오염하지 않는다.
- **권한**: timer service 는 root, git 작업은 `runuser -u mobruji` 로 위임(체크아웃 소유권 보존).
- **주기 = 5분**. bot 의 다른 loop(directive/cycle complete 5분 폴링)과 동일 cadence. 즉시성보다 안정성 우선(코드 변경 시에만 재시작이라 잦은 중단 없음).

## 3) 구성 요소

- `tools/discord-daemon/bridge-auto-deploy.sh` — 동기 + 조건부 재시작 로직. **매개변수화** `[CHECKOUT_DIR] [SERVICE] [WATCH_RE] [RUN_AS]`, 인자 없으면 bridge 기본값.
- `tools/discord-daemon/mobruji-bridge-autodeploy.{service,timer}` — bridge 용. oneshot(root), 인자 없이 호출. `OnBootSec=3min` / `OnUnitActiveSec=5min`.
- `tools/agent/mobruji-agent-autodeploy.{service,timer}` — agent 용(#1479). ExecStart = bridge 체크아웃 스크립트에 `(/home/mobruji/mobruji  mobruji-agent.service  "^tools/agent/")` 인자. `OnBootSec=4min`(bridge 와 어긋나게) / `OnUnitActiveSec=5min`.

## 4) 설치 (NCP)

```bash
# 본 PR 머지 + bridge 체크아웃 pull 후 (스크립트가 체크아웃에 존재해야 함)
sudo cp /home/mobruji/mobruji-bridge/tools/discord-daemon/mobruji-bridge-autodeploy.service /etc/systemd/system/
sudo cp /home/mobruji/mobruji-bridge/tools/discord-daemon/mobruji-bridge-autodeploy.timer   /etc/systemd/system/
sudo chmod +x /home/mobruji/mobruji-bridge/tools/discord-daemon/bridge-auto-deploy.sh
sudo systemctl daemon-reload
sudo systemctl enable --now mobruji-bridge-autodeploy.timer

# agent autodeploy (#1479) — 유닛은 agent 디렉토리에 있으나 ExecStart 는 bridge 체크아웃 스크립트 사용
sudo cp /home/mobruji/mobruji/tools/agent/mobruji-agent-autodeploy.service /etc/systemd/system/
sudo cp /home/mobruji/mobruji/tools/agent/mobruji-agent-autodeploy.timer   /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now mobruji-agent-autodeploy.timer
```

부트스트랩 자동화는 `tools/deploy/ncp-bootstrap-dev.sh` 후속 통합 후보(§6).

## 5) 검증

- 설치 직후 `sudo systemctl start mobruji-bridge-autodeploy.service` 1회 수동 실행 → `journalctl -u mobruji-bridge-autodeploy` 에 `up-to-date` 또는 `pulled ... restarted` 로그 확인.
- 의도적으로 bot 코드 변경 PR 을 develop 머지 → 5분 내 bridge HEAD 가 develop tip 으로 이동 + 서비스 재시작 + active 확인.
- web/docs 만 바뀐 머지 → 체크아웃만 갱신, 재시작 생략 로그 확인(불필요 중단 없음).
- `systemctl list-timers | grep bridge-autodeploy` 로 다음 실행 예정 확인.

## 6) 비범위 / 후속

- `mobruji-agent` 체크아웃 자동 배포는 #1479 로 본 spec 에 통합 완료. `mobruji-maestro`(nmae 인터랙티브 세션)는 자동 재시작 대상이 아니라 범위 밖.
- venv 의존성(pip) 자동 갱신은 비범위 — 코드 pull 만. 신규 의존성 추가 PR 은 수동 설치 필요(드묾).
- `ncp-bootstrap-dev.sh` 에 두 timer 설치 단계 통합(재프로비저닝 시 자동) — 후속.
- 재시작 직전 진행 중 사이클 영향 최소화(graceful drain) — 현재는 bot 재시작 ~8s 로 짧고 sub-agent(tmux)는 별도 생존이라 보류.

## 7) 관련

- 체크아웃 분리 사유: [[project_bridge_deploy_decoupling]]
- 배포 패턴 참고: `.github/workflows/cd-dev.yml`(앱 컨테이너 SSH 배포) / `tools/deploy/ncp-bootstrap-dev.sh`
- 계기 사고: 이슈 #1473 (directive id 오매칭 — bridge stale 로 fix 지연) / NCP 과부하 #1453
