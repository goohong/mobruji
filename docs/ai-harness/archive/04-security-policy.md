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

## 7) Discord bridge / maestro watcher 보안 룰

`tools/discord-daemon/bot.py` 는 maestro tmux pane 캡처를 Discord 채널 (#모부르지) 에 push 한다. claude TUI 가 `gh auth`, `curl -H 'Authorization: ...'`, `env`, `mysql` 등 명령을 echo 하면 raw PAT/토큰/패스워드/mention 이 그대로 노출될 위험이 있어 다음 룰을 강제한다.

### 7-1) sanitize_chunk pipeline (필수 순서)
모든 Discord push 경로는 ANSI 제거 직후, 라인 압축·길이 자르기·dedup 이전에 다음 순서로 sanitize 한다:

1. `strip_ansi` — ANSI escape 제거
2. `mask_secrets` — 7-2 패턴 적용
3. `sanitize_mentions` — 7-3 패턴 적용

chunk 가 잘리거나 dedup 되어도 raw 시크릿/mention 이 send 되지 않도록 **압축·자르기 이전 단계 적용** 이 핵심.

### 7-2) SECRET_MASK_PATTERNS (PR #751/#771)

| 패턴 | placeholder | 비고 |
| --- | --- | --- |
| `ghp_[A-Za-z0-9]{36,}` | `ghp_***` | GitHub classic PAT |
| `github_pat_[A-Za-z0-9_]{50,}` | `github_pat_***` | GitHub fine-grained PAT |
| Discord bot token `[MN]…{23}.…{6}.…{27+}` | `discord_token_***` | |
| env-style `*_TOKEN/SECRET/KEY/PASSWORD=value` | `{KEY}=***` | 7-2-a 예외 적용 |
| `password=value` (case-insensitive) | `password=***` | |

#### 7-2-a) env-style 예외 (false positive narrow, PR #771)
env-style 광역 패턴이 공개 의도 변수를 마스킹하는 회귀를 막기 위해 `_mask_env_style_secret` 콜백에서 다음을 skip 한다:

- **키 이름 블록리스트 포함 시 skip**: `PUBLIC` / `VERSION` / `COUNT` / `LIMIT` / `INDEX` / `SIZE` / `LENGTH`
  - 예: `NEXT_PUBLIC_API_KEY=...`, `PUBLIC_KEY=...`, `API_VERSION_KEY=...`, `SHARD_COUNT_KEY=...`
- **값 길이 12자 미만 skip** — 일반 PAT/API key 는 32+ 가 표준. 짧은 메타값은 false positive 차단 우선.

위 블록리스트/임계는 가독성 회귀 방지 목적이며, **특정 패턴(`ghp_*`, `github_pat_*`, Discord token, `password=`) 은 length/blocklist 무관 항상 마스킹** 한다.

### 7-3) MENTION_SANITIZE_PATTERNS (PR #767)

PR/issue title 또는 maestro pane chunk 에 Discord mention 토큰이 포함되면 알림 폭주 + 채널 전체 ping 위험이 있다. `sanitize_mentions(text)` 가 prefix 직후에 zero-width space (U+200B) 를 삽입해 mention 발화를 무력화한다.

| 토큰 | 처리 후 (ZWSP 삽입) |
| --- | --- |
| `@everyone` | `@​everyone` |
| `@here` | `@​here` |
| `<@USER_ID>` / `<@!USER_ID>` | `<@​USER_ID>` / `<@​!USER_ID>` |
| `<@&ROLE_ID>` | `<@​&ROLE_ID>` |

적용 대상:
- `build_digest_payload` 의 머지 PR / open PR / bug issue 3 카테고리 title 출력
- `sanitize_chunk` pipeline (7-1) 모든 watcher push 경로

`@` 단일 문자 / `<@>` (digit 없음) 등 benign 토큰은 미변경 보존.

### 7-4) buffer cap + drop counter (PR #785)

Discord 장기 outage 또는 transient 5xx/429 폭주 시 메모리/관측 안전을 보장한다.

- **exponential backoff**: 재시도 실패 직후 `await sleep(BASE^retry_count)`. 환경변수 `MAESTRO_WATCHER_SEND_RETRY_BACKOFF_BASE` (default `2.0`) 로 조정. transient outage 의 burst drop 방지.
- **buffer cap**: `MAESTRO_WATCHER_MAX_BUFFER_LEN = MAX_CHUNK_LEN * 10` (=18000자) 초과 시 앞쪽(oldest) 절반 drop + WARN log + counter 증가. **무한 누적 절대 금지** — 메모리 폭주 방지.
- **drop counter**: 모듈 전역 `maestro_watcher_counters = {'send_drop': 0, 'buffer_overflow_drop': 0}` 노출. send retry 초과 / buffer overflow 둘 다 카운트. 향후 metric export (`/healthz` 등) 시 그대로 재사용.

### 7-5) 변경 시 의무
- `tools/discord-daemon/bot.py` 변경 PR 은 `tools/discord-daemon/tests/` 하위 회귀 + 신규 unit test 추가 필수.
- 머지 후 **bridge restart 필요**: `sudo systemctl restart mobruji-discord-bridge.service`.
- secret mask / mention sanitize 패턴 추가/수정 시 본 문서 §7-2 / §7-3 을 같은 PR 에서 갱신.
