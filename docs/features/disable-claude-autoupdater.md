# Feature — Claude Code auto-updater 끄기 (helper / nmae TUI 차단 영구 방지)

- **status**: shipping (2026-05-29, PR fix/disable-claude-autoupdater)
- **scope**: infra
- **owner**: nmae

## 1) 배경

사용자 정정 (2026-05-29):
> "helper는 왜 답이 없을까"

NCP helper tmux pane 캡쳐 결과:
```
✗ Auto-update failed · Try claude doctor or npm i -g @anthropic-ai/claude-co…
────────────────────────────────────────────────────────────────────────────────
❯ 진행 상황 확인해줘
────────────────────────────────────────────────────────────────────────────────
  ⏵⏵ bypass permissions on (shift+tab to cycle) · ← for agents
```

Claude Code TUI 가 자동 update 시도 → 실패 prompt 표시 → input mode 차단. 사용자가 helper 에 메시지 보내도 input box 에 적힌 채 Enter 키 처리 안 됨 → helper 응답 없음.

helper 재시작으로 임시 복구 가능하지만, 다음 update 시도 시 재발. nmae 도 동일 위험.

## 2) Fix

### 2-1) Claude Code env var

Claude Code 는 `DISABLE_AUTOUPDATER=1` env var 로 auto-update path 차단 가능 (claude doctor 보조 문서 + 환경 변수 표준).

### 2-2) systemd Environment 추가

- `tools/discord-daemon/mobruji-helper.service` — Environment=DISABLE_AUTOUPDATER=1
- `tools/discord-daemon/mobruji-maestro.service` — 동일 (nmae)

운영 본체 `/etc/systemd/system/*.service` 도 동시 갱신 + `sudo systemctl daemon-reload` + restart 필요.

## 3) 추가 조치 (수동)

NCP 에서 즉시 update 실행 — 다음 prompt 가 안 뜨도록:
```bash
# 권한 충돌 시 npm 권한 확인
sudo -u mobruji /usr/bin/claude update
# or fallback
sudo -u mobruji npm i -g @anthropic-ai/claude-code
```

## 4) 검증

- helper restart 후 pane 에 "Auto-update failed" prompt 없는지 확인.
- /proc/$PID/environ 에 `DISABLE_AUTOUPDATER=1` 적용 확인.

## 5) follow-up

- 정기 update path 별도 — cron 또는 manual.
- update 실패 시 alert (지속적 fail 시 nmae 알림).
