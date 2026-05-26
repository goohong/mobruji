---
feature: mobruji-helper.service Type=simple + Restart=always
slug: systemd-restart-always
status: draft
owner: @mobruji-maestro
scope: infra
related_issues: [1109]
related_prs: []
last_reviewed: 2026-05-26
---

# mobruji-helper.service Type=simple + Restart=always

## 1) 개요 (What / Why)
- 현재 `mobruji-helper.service` 가 `Type=oneshot` 으로 운영 중 — 프로세스 한 번 실행 후 종료 시 systemd 가 unit 을 `inactive (dead)` 로 마크하고 재시작하지 않음. helper 본체가 OOM / panic / claude process crash 로 죽으면 사용자 메시지 도착해도 무응답.
- 사용자 2026-05-26 정정: "helper service 가 멈춰있는걸 사용자가 직접 알아채야 하는 구조 — Restart=always 가 base line". `Type=simple` + `Restart=always` + `RestartSec=10` + `StartLimitBurst=5` / `StartLimitIntervalSec=600` 으로 변경.
- 대상 액터: helper 본체 운영. nmae(`tmux mobruji:0.0`) / bot.py (`mobruji-discord-bot.service`) 는 본 spec 대상 아님 (별 unit).

## 2) 사용자 시나리오
- helper 본체가 OOM 으로 죽음 → systemd 10초 후 자동 재시작 → 사용자 채널에 다음 메시지 도착 시점에 정상 응답 가능.
- crash loop (5회 / 10분) 도달 시 systemd 가 자동 재시작 중단 + `failed` 상태 → cron digest 가 `mobruji-helper.service: failed (StartLimitBurst)` push → 사용자 가시.
- 정상 sleep / idle 시점 systemd 가 helper turn 강제 종료 X — `Type=simple` 의 main process 가 살아있으면 active.

## 3) 요구사항
### 기능 요구사항
- [ ] `/etc/systemd/system/mobruji-helper.service` `Type=oneshot` → `Type=simple` 변경.
- [ ] `Restart=always` 추가.
- [ ] `RestartSec=10` (재시작 사이 최소 10초 — Discord rate limit 회피).
- [ ] `StartLimitBurst=5` + `StartLimitIntervalSec=600` (10분에 5회 crash → 자동 stop).
- [ ] `ExecStart=` 그대로 (helper 본체 tmux attach + claude code 실행 명령). main process 가 die 시 systemd 가 unit failed → restart.
- [ ] `ExecStartPre=` 옵션: 재시작 직전 `tmux kill-session helper 2>/dev/null || true` 로 stale session 정리.
- [ ] `WantedBy=multi-user.target` 유지.
- [ ] 변경 후 `sudo systemctl daemon-reload && sudo systemctl restart mobruji-helper.service` 1회 manual 실행 (배포 절차 README 추가).
- [ ] cron digest 에 `mobruji-helper.service` 상태 1줄 추가 (`systemctl is-active`).

### 비기능 요구사항
- 신뢰성: crash 후 평균 복구 시간 ≤ 30초 (RestartSec 10 + claude code 부팅 ~15-20초).
- 관측성: `sudo journalctl -u mobruji-helper.service -n 100` 으로 crash 직전 stderr 확인 가능.
- 안전: StartLimitBurst 로 무한 crash loop 차단 (1000회 / 분 시 systemd 부하).
- 보안: `User=mobruji` 유지 — root 권한 안 함. helper 환경변수 (BOT_TOKEN 등) 는 `EnvironmentFile=/home/mobruji/.mobruji/.env` 로 sourcing (기존 유지).

## 4) 범위 / 비범위
### 포함
- `mobruji-helper.service` unit 단일 변경.
- ADR-0023 `workflow-main-sync.md` 와 별개 (보호 영역 변경이 main 도달 필요한 경우 별 spec 참조).
- cron digest 한 줄 표시 추가.

### 제외 (Out of Scope)
- nmae(`mobruji.service` 또는 tmux 직접 운영) — 별 unit, 본 spec 대상 아님.
- bot.py (`mobruji-discord-bot.service`) — 이미 `Restart=always` 운영 중 (PR #여러 건).
- helper 본체 process tree 분리 — claude code + tmux 통째로 `Type=simple` main 로 두고, main 죽으면 unit fail.
- crash 원인 분석 자동화 — journalctl tail 만, root cause LLM 분석 X.

## 5) 설계
### 5-1) 도메인 모델
- 인프라 도메인 (`scope: infra`). systemd unit.

### 5-2) API 엔드포인트
N/A.

### 5-3) 외부 연동
- **systemd** — unit file 변경, daemon-reload + restart.
- **tmux** — `ExecStartPre` 의 `tmux kill-session helper` 으로 stale 정리.
- **cron** — digest cron 에 `systemctl is-active mobruji-helper.service` 한 줄 추가.

### 5-4) 데이터 흐름 / 시퀀스
```
[helper crash 시나리오]
  1. helper main process die (OOM / panic / claude code exit)
  2. systemd: Type=simple main died → unit status = activating (auto-restart)
  3. RestartSec=10 대기
  4. ExecStartPre: tmux kill-session helper (stale)
  5. ExecStart: tmux new-session + claude code 부팅
  6. unit 상태 → active
  7. helper turn-start wrapper 첫 호출 시 정상 동작

[crash loop 시나리오]
  - 10분 안 5회 crash → systemd auto-stop, unit status = failed
  - cron digest: "🚨 mobruji-helper.service: failed (StartLimit reached)" push
  - 사용자 / 운영자 manual `sudo systemctl reset-failed && systemctl start mobruji-helper.service`
```

### 5-5) DB 마이그레이션
없음.

### 5-6) 프론트엔드 화면
없음.

### 5-7) unit file (변경 diff 예시)

before:
```ini
[Unit]
Description=mobruji helper claude code
After=network-online.target

[Service]
Type=oneshot
User=mobruji
EnvironmentFile=/home/mobruji/.mobruji/.env
ExecStart=/home/mobruji/.mobruji/start-helper.sh
RemainAfterExit=yes

[Install]
WantedBy=multi-user.target
```

after:
```ini
[Unit]
Description=mobruji helper claude code
After=network-online.target
StartLimitBurst=5
StartLimitIntervalSec=600

[Service]
Type=simple
User=mobruji
EnvironmentFile=/home/mobruji/.mobruji/.env
ExecStartPre=/bin/bash -c 'tmux kill-session -t helper 2>/dev/null || true'
ExecStart=/home/mobruji/.mobruji/start-helper.sh
Restart=always
RestartSec=10

[Install]
WantedBy=multi-user.target
```

### 5-8) 배포 절차
1. `sudo cp mobruji-helper.service /etc/systemd/system/`
2. `sudo systemctl daemon-reload`
3. `sudo systemctl restart mobruji-helper.service`
4. `sudo systemctl status mobruji-helper.service` — active 확인
5. `sudo journalctl -u mobruji-helper.service -n 30` — 부팅 로그 확인

### 5-9) ADR 동반 (ADR-0023)
본 spec 의 unit file 변경은 `.github/CODEOWNERS` 또는 `infra/` 디렉토리 변경 + 보호 영역 라벨 + main 머지 의무. 워크플로우 main-sync ADR 과 별개로 본 spec 자체는 systemd unit 변경 결정의 trade-off 만 다룬다. 본 변경의 ADR 후보:

- 위치: `docs/decisions/00XX-helper-systemd-restart-always.md` (별 ADR — 필요 시).
- 결정: Type=simple + Restart=always + StartLimitBurst=5 채택.
- 대안: (a) Type=oneshot 유지 + cron 으로 systemctl start 강제 / (b) supervisord 도입 / (c) docker container 화 + restart policy.
- 선택 사유: systemd native 가 운영 도구 단순화 + 기존 unit infra 일관성.

본 spec 은 동반 ADR 작성 시점에 cross-ref. v1 은 unit 변경 1 PR 로 진행.

## 6) 작업 분할 (예상 PR 리스트)
- [ ] PR 1: `mobruji-helper.service` unit 변경 (`Type=simple` / `Restart=always` / StartLimitBurst) + README 배포 절차 추가 + cron digest 한 줄 추가. **보호 영역 라벨 필수** (systemd unit = infra 변경).
- [ ] PR 2 (옵션, 동반 ADR): `docs/decisions/00XX-helper-systemd-restart-always.md` — supervisord/docker 대안 trade-off 박제.

## 7) 테스트 전략
- 수동 검증:
  - `systemctl stop mobruji-helper.service && sleep 15 && systemctl status` — 자동 재시작 확인.
  - `kill -9 <helper_main_pid>` — crash 후 자동 재시작 확인.
  - 5회 연속 crash → StartLimit 도달 → unit failed → cron digest push 확인.
- 단위 테스트: unit file syntax 검증 (`systemd-analyze verify mobruji-helper.service`).
- E2E: NCP 서버 실 helper 환경에서 1회 crash + 1회 graceful restart 모두 검증.

## 8) 오픈 질문
| # | 질문 | 선택지 | 담당/기한 |
|---|---|---|---|
| Q1 | RestartSec — 10초 vs 30초 | (a) 10 (빠른 복구) / (b) 30 (Discord rate limit 안전) | @user / TBD |
| Q2 | StartLimitBurst — 5 vs 3 | (a) 5 (관대) / (b) 3 (tight, crash loop 빠른 정지) | @user / TBD |
| Q3 | claude code resume 정책 — `--continue` vs 신규 세션 | (a) `--continue` 컨텍스트 유지 / (b) 신규 (메모리 reload) | @user / TBD |
| Q4 | 동반 ADR 별도 작성 필요? | (a) 별 ADR (trade-off 박제) / (b) 본 spec 만 | @user / TBD |

## 9) 관련 spec / ADR / 메모리

- `docs/features/discord-daemon-hosting.md` — bot.py (`mobruji-discord-bot.service`) 가 이미 `Restart=always` 운영 중. 본 spec 은 helper unit 동등화.
- `docs/decisions/0015-hosting-stack.md` — NCP + systemd hosting 결정.
- `docs/decisions/0023-workflow-main-sync.md` (본 사이클 동반 ADR) — workflow / unit file 등 main 도달 필요 변경의 sync 전략. 본 spec 의 PR 도 develop → main sync 절차 필요.
- 메모리: [[feedback-verify-and-iterate]] (daemon 변경 후 journal 확인 의무) / [[feedback-keep-promises]] (Restart=always 약속 즉시 적용).

## 10) 결정 로그
- 2026-05-26: 초안 작성 (status=draft). 이슈 #1109 plan 사이클. B-3 task brief.
- 2026-05-26: Type=oneshot → Type=simple 변경 — RemainAfterExit 은 main process 가 활성 신호 안 되므로 부적합. simple 로 main process die 시 unit 도 die → Restart=always 가 정상 동작.
- 2026-05-26: ExecStartPre `tmux kill-session helper` 추가 — crash 직후 잔존 tmux session 정리 (claude code 가 같은 session 이름 attach 실패 회피).
