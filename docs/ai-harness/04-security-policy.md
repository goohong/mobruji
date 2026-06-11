# Security Policy

## 1) 보호 대상
- 개인정보: 사용자 음역대·취향 기록, 식별 가능 입력값
- 인증/비밀정보: API 키, 토큰, 비밀번호, DB 접속정보
- 라이선스 데이터: 추후 음원/가사 API 연동 시 키 및 사용량 토큰

## 2) 기본 원칙
- 사용자 입력값(음역대·기호)은 로그/코멘트/스크린샷에 원문 노출 금지. 디버깅이 필요해도 마스킹 또는 집계만.
- 시크릿은 코드/문서 하드코딩 금지, 환경변수 또는 비밀 저장소 사용
- 최소 권한 원칙으로 접근 권한을 제한

## 3) 마스킹 규칙
- 사용자 식별 가능 정보는 기본 마스킹 처리
- 디버깅이 필요해도 민감 필드 원문 출력 금지
- 샘플 데이터는 반드시 비식별화 데이터 사용

## 4) 금지 행위
- Secret key를 커밋/PR 본문/이슈에 노출
- 민감정보를 외부 도구/서비스로 무단 전송
- 운영 데이터베이스 덤프를 로컬/공유 채널에 업로드
- public repo 본문/이슈/PR에 prod 식별 가능 값 노출 (실명·user_id·token 등)

## 5) 사고 대응
- 유출 의심 시 즉시 키 폐기/재발급
- 영향 범위 파악 및 로그 보존
- 사고 리포트와 재발방지 액션을 문서화

## 6) PR 보안 체크
- 시크릿 하드코딩 없음
- 민감정보 마스킹 확인
- 외부 연동/전송 경로 변경 시 보안 검토 완료

## 7) Discord bridge 보안 룰

`tools/discord-daemon/bot.py` 는 PR #807 단순화 이후 **routing + cycle digest** 만 담당한다 (maestro pane 캡처 watcher 폐기). helper/maestro 의 응답 push 는 `tools/discord-daemon/discord-reply.sh` 가 Discord REST API 를 직접 호출하는 단방향 경로다. 노출 표면이 좁아진 만큼 본 §7 은 **실제 운영 중인 코드와 1:1 대응되는 룰** 만 남긴다.

### 7-1) Discord push 보안
- helper/maestro 응답은 `tools/discord-daemon/discord-reply.sh` 만 사용한다. MCP Discord plugin / 임시 `curl` 스크립트는 금지 (`.env` 경로 일관성 + 채널 분리 보장).
- `tools/discord-daemon/.env` 파일 권한은 `600` 으로 유지한다 (`chmod 600 tools/discord-daemon/.env`). `setup-launchagent.sh` / `setup-gcp-systemd.sh` 가 첫 셋업 시 자동 적용.
- `DISCORD_BOT_TOKEN` / 채널 id 를 평문으로 git commit 금지. `.gitignore` 의 `.env` 룰을 PR 마다 점검 (`git status` 에 `.env` 가 잡히면 즉시 중단).
- `discord-reply.sh` 는 `.env` 를 읽을 때 `DISCORD_DAEMON_ENV_PATH` 환경변수 override 만 허용. 인자로 토큰을 받지 않는다 (shell history 누출 차단).

### 7-2) Mention sanitization
PR title / open issue title 등이 cycle digest 에 노출될 때 `@everyone` / `@here` / `<@USER_ID>` 토큰이 그대로 출력되면 채널 전체 ping / 사용자 mention 폭주가 발생한다. `bot.py` 의 `sanitize_mentions(text)` 가 prefix 직후에 zero-width space (U+200B) 를 삽입해 mention 발화를 무력화한다.

| 토큰 | 처리 후 (ZWSP 삽입) |
| --- | --- |
| `@everyone` | `@​everyone` |
| `@here` | `@​here` |
| `<@USER_ID>` / `<@!USER_ID>` | `<​@USER_ID>` / `<​@!USER_ID>` |
| `<@&ROLE_ID>` | `<​@&ROLE_ID>` |

적용 대상 (현 bot.py 기준):
- `format_cycle_digest` 에서 cycle-status 의 in-progress / recent 텍스트 출력 직전 (`bot.py:622-623`).

`@` 단일 문자 / `<@>` (digit 없음) 등 benign 토큰은 미변경 보존. 신규 digest 출력 경로를 추가하면 같은 함수를 거치도록 한다.

> 사용자 입력 본문을 Discord embed 등으로 inline 노출하지 않는다 (현 단순화본은 embed 미사용). 신규 embed 도입 시 본 문서에 입력 sanitize 룰을 추가한 뒤 구현.

### 7-3) Discord 채널 ID 분리
사용자 응답과 자동 알림 (digest) 을 분리해 노이즈/오발송을 차단한다.

| env | 용도 | 비고 |
| --- | --- | --- |
| `MOBRUJI_CHANNEL_ID` | #모부르지. **사용자 응답 전용** (helper/maestro → 사용자). | `discord-reply.sh` 기본 송신 채널. |
| `DIGEST_CHANNEL_ID` | #알림. **cycle digest 전용**. | 미설정 시 `MOBRUJI_CHANNEL_ID` fallback (단일 채널 운영). #1019 에서 기존 `NOTIFY_CHANNEL_ID` 를 rename — 기존 이름은 backward-compat 으로 fallback 인식 (deprecation warning 1회). |

- 두 채널을 분리 운영할 때 `DIGEST_CHANNEL_ID` 를 반드시 명시한다. fallback 인지 않고 운영하면 digest 가 사용자 응답 채널에 섞여 push 된다.
- `discord-reply.sh` 가 사용자 응답을 digest 채널로 보내지 않도록 `MOBRUJI_CHANNEL_ID` 우선순위를 유지한다 (`discord-reply.sh:48-56` 참조).

### 7-4) cycle-status.json 보안
`~/.mobruji/cycle-status.json` 은 nmae 4 워크트리 (be/fe/rev/plan) 상태를 bot.py digest 가 읽는 호스트 로컬 파일이다.

- 파일 권한은 `644` 로 유지 (host owner 만 쓰기, daemon 사용자가 같은 owner). 다중 사용자 호스트에서는 `600` 으로 좁힐 것.
- **sessionId / 사용자 PII / DB 식별자 / 토큰 평문 저장 금지**. 진행 상황은 PR 번호 + 한 줄 요약만.
- 외부에 공유할 때는 `cycle-status.json` 을 그대로 업로드하지 말고 발췌 (sub-agent 이름 + 상태) 만 전달.

### 7-5) 폐기 항목 (PR #807 단순화, 이력 보존)
다음 함수/env/카운터는 PR #807 단순화에서 **bot.py 에서 제거됨**. 본 문서 과거판 (§7-1 ~ §7-4) 에 명시되어 있던 룰의 실코드 참조점이 사라졌으므로 신규 sub-agent 가 호출/참조하지 않도록 명시한다.

- `sanitize_chunk` pipeline (`strip_ansi → mask_secrets → sanitize_mentions` 3-step) — pane 캡처 watcher 자체가 폐기되어 사용처 없음.
- `mask_secrets` / `_mask_env_style_secret` / `SECRET_MASK_PATTERNS` — bot.py grep 0건. 폐기.
- `MAESTRO_WATCHER_MAX_BUFFER_LEN` / `MAESTRO_WATCHER_SEND_RETRY_BACKOFF_BASE` — watcher 폐기로 무의미.
- `maestro_watcher_counters` (`send_drop` / `buffer_overflow_drop`) — 카운터 자체 제거.
- exponential backoff (watcher 전용) — watcher 폐기.

> watcher 가 다시 도입되면 본 §7-5 를 deprecation note 로 옮기고 §7-1 ~ §7-4 사이에 신규 룰 섹션을 추가한다. 코드 추가 없이 본 문서만 부활시키지 않는다 (스펙-코드 drift 재발 차단).

### 7-6) 변경 시 의무
- `bot.py` / `discord-reply.sh` 변경 PR 은 `tools/discord-daemon/tests/` 하위 회귀 또는 신규 unit test 추가를 권장. routing/digest 경로 변경 시 필수.
- 머지 후 daemon restart:
  - **Linux/GCP/NCP**: `sudo systemctl restart mobruji-discord-daemon` (+ 필요 시 `mobruji-discord-bridge`).
  - **macOS LaunchAgent**: `launchctl kickstart -k gui/$(id -u)/com.mobruji.discord-daemon`.
- mention sanitize 패턴 / 채널 ID 룰 변경 시 본 문서 §7-2 / §7-3 을 같은 PR 에서 갱신한다.
- 신규 secret mask 가 필요하다고 판단되면 (예: 새 외부 API 키) 본 §7 에 패턴을 추가하고 `bot.py` / `discord-reply.sh` 의 실제 push 경로에 구현 코드를 같은 PR 에서 함께 넣는다 — 문서만 갱신 금지.
