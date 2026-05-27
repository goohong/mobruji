# AI Harness Volume 16: Helper Agent Runbook

> NCP 상주 `helper` 에이전트의 설정 및 운영 지침입니다.

## 1. 개요
Helper Agent는 사용자 Discord 메시지를 1차 수신하여 즉각적으로 응답(1초 Ack, 10초 자체 답)하고, 필요한 경우 nmae(Maestro)에게 작업을 위임하는 역할을 수행합니다. 이를 통해 오너는 mac이 꺼져 있는 상황에서도 폰만으로 서비스 운영을 지속할 수 있습니다.

## 2. 인프라 구성
- **호스트**: NCP Maestro VM
- **프로세스**: tmux session `helper` (claude CLI 상주)
- **모니터링**: systemd `mobruji-helper-tmux.service` (Restart=always)
- **통신**:
  - 수신: `bot.py`가 Discord 메시지를 `helper` tmux pane으로 `send-keys`
  - 송신: `helper`가 `./tools/discord-daemon/discord-reply.sh`를 통해 Discord REST API로 직접 push

## 3. 초기 설정 (Setup)

### 1) tmux 세션 생성 및 로그인
```bash
./tools/deploy/setup-helper-tmux.sh
```

### 2) systemd 서비스 등록
```bash
sudo cp tools/discord-daemon/mobruji-helper-tmux.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable mobruji-helper-tmux.service
sudo systemctl start mobruji-helper-tmux.service
```

## 4. 운영 시나리오 검증 (Smoke Test)

Helper가 정상 작동하는지 확인하려면 아래 시나리오를 Discord에서 직접 테스트합니다.

1. **Ack 및 자체 답**: Discord에 "현재 상황 어때?" 전송
   - 1초 내에 "확인하고 있습니다" 도착 확인
   - 10초 내에 현재 워크트리 현황 보고 도착 확인
2. **위임 테스트**: Discord에 "PR #790 머지 검토해줘" 전송
   - 1~2단계 응답 확인
   - nmae tmux pane (`tmux attach -t mobruji`)에서 helper가 보낸 지시어가 입력되었는지 확인
3. **보강 push**: nmae 작업 완료 후 helper가 결과를 요약해서 Discord에 추가로 올리는지 확인

## 5. 문제 해결 (Troubleshooting)

- **응답 없음**:
  - `journalctl -u mobruji-helper-tmux -f` 로 로그 확인
  - `tmux ls` 로 `helper` 세션 생존 확인
- **중복 응답**:
  - mmae(Mac Maestro)가 켜져 있는지 확인. mmae의 Discord polling을 끄거나 read-only로 전환해야 함.
- **권한 오류 (gh/git)**:
  - helper 세션 내부에서 `gh auth status` 확인
