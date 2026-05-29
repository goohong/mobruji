#!/bin/bash
# mobruji-maestro.service 가 호출하는 런처.
# (1) 재시작/재부팅 후 fresh 세션에 핸드오프 resume 를 자동 inject 하는 watcher 를
#     백그라운드(setsid 로 detach — exec 후에도 생존)로 기동.
# (2) 역할 system prompt 를 주입한 채 claude 를 exec.
# 안정 경로(~/.mobruji)라 repo 브랜치 전환에 안 흔들린다.
#
# 2026-05-29 (PR fix/maestro-launch-bootstrap-merge): 기존 NCP-only 본체와
# repo 사본이 분기돼 있던 것을 union 으로 통합 + DISABLE_AUTOUPDATER export 추가.
# deploy.sh HOOK_NAMES 에 maestro-launch.sh 도 추가해 NCP 본체를 symlink 로 강제.

# bootstrap watcher (NCP 본체에서 통합). maestro-bootstrap.sh 부재 시 graceful skip.
if [[ -x /home/mobruji/.mobruji/maestro-bootstrap.sh ]]; then
  setsid /home/mobruji/.mobruji/maestro-bootstrap.sh >/dev/null 2>&1 &
fi

exec /usr/bin/claude --dangerously-skip-permissions --append-system-prompt "$(cat /home/mobruji/.mobruji/nmae-role.md)"
