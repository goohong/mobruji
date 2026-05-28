---
feature: discord-reply.sh length 2000 초과 split + retry
slug: discord-reply-length-split
status: draft
owner: @mobruji-maestro
scope: infra
related_issues: [1109]
related_prs: []
last_reviewed: 2026-05-26
---

# discord-reply.sh length 2000 초과 split + retry

## 1) 개요 (What / Why)
- Discord message content 는 2000자 제한. `discord-reply.sh` 가 본답 길이 ≥ 2000 인 payload 전송 시 `400 Bad Request` + helper 본답 silent loss. 현재는 helper 본체가 길이를 의식해서 직접 자르거나 thread 로 분산해야 — 학습 의존 + 정정 빈발.
- 사용자 2026-05-26 정정: "본답 길이 over 2000 무한 발생 — script 자체에서 split 해". `discord-reply.sh` 본답 모드에 자동 split 도입: 1번째 메시지 = 본답 첫 1900자 (margin 100, ZWSP+\n 포함) + `(1/N)` suffix, 2번째 이상 = 같은 thread 또는 같은 채널 연속 push (`(2/N)` ... `(N/N)`). 같은 사용자 메시지에 reply chain.
- 대상 액터: helper 본체 push 도구. nmae / sub-agent 도 `discord-reply.sh` 호출 시 동일 적용.

## 2) 사용자 시나리오
- helper 본체가 본답 3500자 push → 자동 2 메시지 split: 1900자 `(1/2)` + 1600자 `(2/2)`.
- 사용자가 채널에서 두 메시지 연속 확인. 첫 메시지 = 원본 사용자 메시지에 reply, 둘째 = 첫 메시지에 reply (chain). 가독성 유지.
- thread 모드 (`--auto-thread` / `--thread`) 도 동일 — thread 안 메시지가 split, suffix 동일.

## 3) 요구사항
### 기능 요구사항
- [ ] `discord-reply.sh` length 측정: payload body byte length 가 `DISCORD_REPLY_SPLIT_THRESHOLD` (default 1900) 초과 시 split trigger.
- [ ] split 알고리즘:
  - [ ] 1차 split 단위: 문단 (`\n\n`) — 가능하면 문단 boundary 보존.
  - [ ] 2차: 문장 (마침표 / 물음표 / 느낌표 / `다.` `요.` 한국어 종결).
  - [ ] 3차: 강제 byte cut (utf-8 multi-byte 안전 — `head -c` 대신 awk 사용).
  - [ ] 각 chunk 끝에 `(i/N)` suffix append (margin 10 byte 확보).
- [ ] 본답 모드 자동 split: 본문 시작 ZWSP+\n prepend 는 1번째 chunk 만, 2번째 이상은 prepend X.
- [ ] reply chain: 1번째 chunk = 원본 메시지에 reply (`message_reference`). 2번째 이상 = 직전 chunk 의 message_id 에 reply (chain).
- [ ] 429 retry: 기존 `send_with_retry` 로 chunk 별 독립 retry. chunk N 실패 시 stderr warn + 다음 chunk 시도 (graceful continue).
- [ ] 모든 chunk 실패 시 exit 1 (현재 동작 유지). 일부 chunk 만 실패 시 exit 0 + 실패 chunk index stderr.
- [ ] thread 모드 (`--auto-thread` / `--thread`) 도 동일 — thread channel id 안에서 split.
- [ ] env toggle: `DISCORD_REPLY_SPLIT_ENABLED=1` default.
- [ ] env: `DISCORD_REPLY_SPLIT_THRESHOLD` (default 1900) / `DISCORD_REPLY_SPLIT_RETRY_MAX` (default 3, chunk 별 재시도).

### 비기능 요구사항
- 가독성: 문단 boundary 보존 우선. 단어 중간 cut 회피 (한국어 음절 boundary 도 utf-8 safe).
- 성능: chunk 당 ~300ms (Discord rate limit 5 req / 5sec 안전). 5 chunk 까지 ~1.5초.
- 멱등성: 같은 호출 2회 실행 시 chunk 수 동일.
- 관측성: stderr log `[reply-split] N chunks, total <bytes>B, first chunk <preview>`.

## 4) 범위 / 비범위
### 포함
- `discord-reply.sh` 본답 모드 + thread 모드 자동 split.
- chunk 1+에 `(i/N)` suffix.
- reply chain (chunk 간 message_reference).

### 제외 (Out of Scope)
- markdown table / code block boundary 보존 — boundary 추적 비용 대비 효익 낮음, v1 은 문단/문장만.
- 첨부 file 분할 — Discord file 첨부는 별 API, 본 spec 대상 아님.
- 다른 도구 (nmae-discord-push.sh / push API 직접 호출) split — `discord-reply.sh` 만, 호출하는 다른 script 는 자동 적용 (wrapper).
- 사용자 측 reading 보조 (TL;DR 자동 생성 / 요약) — split 만, 본문 가공 X.

## 5) 설계
### 5-1) 도메인 모델
- 인프라 도메인 (`scope: infra`). `discord-reply.sh` 영역.

### 5-2) API 엔드포인트
N/A (Discord REST 외부).

### 5-3) 외부 연동
- **Discord REST**: `POST /channels/<channel_id>/messages` chunk 별 1회. `message_reference.message_id` 로 chain.

### 5-4) 데이터 흐름 / 시퀀스
```
[discord-reply.sh body 본문 진입]
  1. body byte length 측정 (wc -c 또는 bash ${#var})
  2. if length <= THRESHOLD: 기존 단일 push
  3. else:
       a. split_body(body, THRESHOLD - SUFFIX_MARGIN) → array[N]
          - 1차: split on "\n\n"
          - 2차: split on 문장 boundary
          - 3차: byte cut (utf-8 safe)
       b. for i in 1..N:
            chunk_i = parts[i] + " (i/N)"
            if i == 1: reply_to = $TARGET_MSG_ID
            else: reply_to = prev_chunk_msg_id
            response = send_with_retry(channel, chunk_i, reply_to)
            prev_chunk_msg_id = response.id
       c. stderr log: "[reply-split] N chunks"
```

### 5-5) DB 마이그레이션
없음.

### 5-6) 프론트엔드 화면
없음 (Discord 가시).

### 5-7) split 알고리즘 (의사 코드)

```bash
split_body() {
  local body="$1" threshold="$2"
  local parts=() current=""

  # 1차: 문단 (\n\n)
  IFS=$'\n\n' read -ra paragraphs <<< "$body"
  for p in "${paragraphs[@]}"; do
    if (( ${#current} + ${#p} + 2 <= threshold )); then
      current+="${current:+\n\n}$p"
    else
      [[ -n "$current" ]] && parts+=("$current")
      if (( ${#p} <= threshold )); then
        current="$p"
      else
        # 2차/3차: 문장 + byte cut
        sub_parts=$(split_sentence "$p" "$threshold")
        for sp in "${sub_parts[@]}"; do parts+=("$sp"); done
        current=""
      fi
    fi
  done
  [[ -n "$current" ]] && parts+=("$current")

  printf '%s\n' "${parts[@]}"
}
```

### 5-8) suffix 형식

- `(1/N)` 형태, 마지막 줄 뒤 공백 1개 + `(i/N)`.
- N ≤ 1 인 경우 suffix 생략 (단일 메시지).
- 한국어 본문 가독성 — suffix 는 ASCII parens 사용.

예시:
```
첫 chunk 본문...
계속...

(1/3)
```

## 6) 작업 분할 (예상 PR 리스트)
- [ ] PR 1: `discord-reply.sh` split 알고리즘 + reply chain + suffix + 단위 테스트 (bash test, fixture 본문 1000자 / 2500자 / 5000자 / 1자 / utf-8 한국어 3000자).
- [ ] PR 2 (옵션): `nmae-discord-push.sh` wrapper 가 본 split 을 자동 활용하는지 검증 — 호출 chain 통해 자동 적용 확인.

## 7) 테스트 전략
- 단위 테스트 (bash test):
  - threshold 1900 / body 1500 → 1 chunk (split X).
  - body 2500 → 2 chunk, suffix `(1/2)` `(2/2)`.
  - body 5000 → 3 chunk.
  - utf-8 한국어 3000자 (자모 boundary safe) — byte cut 시 multi-byte 깨짐 없음.
  - 문단 boundary preferred — `\n\n` 위치에서 split.
  - 429 retry — chunk 1 성공 / chunk 2 429 → retry 3회 후 성공 / chunk 3 정상.
  - chunk 일부 실패 → exit 0 + stderr 실패 index.
- E2E: NCP 서버 실 호출 1회 (3000자 본문) → Discord 채널 2 메시지 chain 확인.

## 8) 오픈 질문
| # | 질문 | 선택지 | 담당/기한 |
|---|---|---|---|
| Q1 | suffix 형식 — `(1/N)` vs `[1/N]` vs `— 1/N` | (a) parens / (b) brackets / (c) em dash | @user / TBD |
| Q2 | reply chain — 모든 chunk 가 원본 메시지에 reply vs chain (chunk i → chunk i-1) | (a) all → root / (b) chain | @user / TBD |
| Q3 | 문단 boundary 보존 우선순위 — code block 안 `\n\n` 가 false split 트리거? | (a) v1 무시 (out-of-scope) / (b) v2 code block 인식 | @user / TBD |

## 9) 관련 spec / ADR / 메모리

- `docs/features/discord-realtime-bidirectional.md` — `discord-reply.sh` 인프라.
- `docs/features/discord-message-style.md` — 본답 가독성 / 정중체 / ZWSP+\n prepend.
- 메모리: [[feedback-helper-discord-newline]] (ZWSP+\n) / [[feedback-discord-reply-script]] / [[feedback-verify-and-iterate]].
- CLAUDE.md §12-3 step 6 — 본답 push 의 ZWSP+\n + writing marker. 본 spec 이 push 자체 robust 화.

## 10) 결정 로그
- 2026-05-26: 초안 작성 (status=draft). 이슈 #1109 plan 사이클. B-5 task brief. 구현은 be 사이클 별 PR.
- 2026-05-26: threshold default 1900 — Discord limit 2000 - margin 100 (suffix + safety). env override 가능.
- 2026-05-26: chunk 별 독립 retry — chunk N 실패가 chunk M 차단 안 함. partial success 허용 (정보 누락 < 전부 silent loss).
