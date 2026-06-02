# mobruji-agent NCP 배포 절차 (Phase 3)

Phase 2 완료 후 NCP 운영 본체 에 배포. legacy 시스템 (helper / maestro) 그대로
작동 — dual write 시기 (1-2주 운영 검증 후 Phase 4 cutover).

## 사전 조건

- Phase 2.1 ~ 2.4 PR 모두 develop 머지 (확인: `git log --oneline develop | head`)
- NCP server SSH 접근 (`ssh mobruji-ncp`)
- Discord bot token 등 .env 변경 없음 (기존 mobruji-bridge .env 공유)

## 1단계 — NCP repo pull

```bash
ssh mobruji-ncp
sudo -u mobruji bash <<'SSH'
cd /home/mobruji/mobruji
git pull --ff-only origin develop
SSH
```

## 2단계 — venv 생성 + dependencies 설치

```bash
ssh mobruji-ncp
sudo -u mobruji bash <<'SSH'
cd /home/mobruji/mobruji/tools/agent
python3 -m venv venv
./venv/bin/pip install --upgrade pip
./venv/bin/pip install -e .
./venv/bin/python -m pytest tests/ -v
SSH
```

기대: 28 tests pass. claude-agent-sdk 설치 확인.

## 3단계 — agent SQLite DB 초기화 (자동)

```bash
ssh mobruji-ncp
sudo -u mobruji ls -la /home/mobruji/.mobruji/agent.sqlite
```

bot.py 가 Phase 2.1 부터 INSERT 호출 시 자동 schema 생성. 별도 init 불필요.

## 4단계 — systemd unit install

```bash
ssh mobruji-ncp
sudo cp /home/mobruji/mobruji/tools/agent/mobruji-agent.service \
    /etc/systemd/system/mobruji-agent.service
sudo systemctl daemon-reload
sudo systemctl enable mobruji-agent.service
```

## 5단계 — bot.py restart (Phase 2.1 ~ 2.3 변경 적용)

```bash
ssh mobruji-ncp
cd /home/mobruji/mobruji-bridge
sudo -u mobruji git pull --ff-only origin develop  # bot.py 변경 가져오기
sudo systemctl restart mobruji-discord-bridge.service
sudo journalctl -u mobruji-discord-bridge -n 30 --no-pager
```

기대: `agent_outbox_loop launched: interval=1s polling` 로그.

## 6단계 — agent service 시작

```bash
ssh mobruji-ncp
sudo systemctl start mobruji-agent.service
sudo journalctl -u mobruji-agent.service -n 30 --no-pager
```

기대: `mobruji-agent 시작 (Phase 1.4)` + `agent_loop started` 로그.

## 7단계 — dual write 검증 (사용자 메시지 1건)

사용자가 Discord `#모부르지` 채널 에 테스트 메시지 ("agent test") 보냄.

```bash
ssh mobruji-ncp
sudo journalctl -u mobruji-discord-bridge --since "1 min ago" 2>&1 | grep -iE "메시지 수신|agent dual"
sudo journalctl -u mobruji-agent --since "1 min ago" 2>&1 | grep -iE "user_message|SDK query"
sqlite3 /home/mobruji/.mobruji/agent.sqlite "SELECT id, kind, consumed_by FROM events ORDER BY id DESC LIMIT 5"
```

기대:
- bridge: `메시지 수신` + (agent dual write 실패 메시지 없음)
- agent: `user_message → SDK query: ...` 또는 SDK 미설치 fallback log
- events table: `user_message` row consumed_by='agent' (처리 완료) + 가능하면 `agent_reply` row consumed_by='bot'

## 8단계 — legacy 동작 확인 (Phase 3 안에 영향 0 확인)

helper / nmae 그대로 작동 — Discord 사용자 메시지가 legacy path 로도 처리됨.

## rollback

Phase 3 안에서 사고 발생 시:
```bash
sudo systemctl stop mobruji-agent.service
sudo systemctl disable mobruji-agent.service
# bot.py 의 append_agent_event 실패는 이미 graceful — bot 도 그대로 작동.
```

## Phase 4 cutover (1-2주 dual write 안정 운영 후)

- helper.service / maestro.service `sudo systemctl disable` + `stop`
- bot.py 의 legacy path (tmux_send_payload, write_last_user_msg_id) 폐기 PR 별도
- legacy 코드 폐기는 추가 PR (rollback 가능 위해 stage 별 진행)
