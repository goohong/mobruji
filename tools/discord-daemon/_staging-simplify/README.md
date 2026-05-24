# mmae discord 스크립트 단순화본 staging

본 디렉토리는 mmae 가 2026-05-24 15:29 `/tmp/mmae-simplify/` 에 staging 한 단순화본을 워크트리 안에 보존한 사본입니다. 이슈 #1058 (회귀 검토) 결과 **prod 교체는 보류**되었습니다.

## 단순화 요약

| 파일 | origin/develop (837/129 line) | 단순화본 (344/114 line) | 압축률 |
|---|---|---|---|
| `discord-reply.sh` | 837 줄 | 344 줄 | 59% |
| `helper-turn-start.sh` | 129 줄 | 114 줄 | 12% |
| `tests/test_helper_ux.py` | 1350 줄 | 1349 줄 | 거의 동일 |

## 의도된 폐기 (mmae tests 코멘트 line 1163 인용)

> "사용자 판단에 따라 5단 → 2단으로 단순화 (helper-current-target.txt freeze, HELPER_TURN_TARGET_MSG_ID env, helper-queue.jsonl pending 폐기)."

- `#987 race condition fix` reply target resolve chain: 5단 → 2단.
- helper-turn-start.sh §1 target msg freeze 액션 제거 (5 → 4 액션).
- 사유 (단순화본 주석): "5+분 turn 에선 stale 만 되어 useful 하지 않음".

## 의도 불명 (mmae staging 후 origin/develop 가 진화)

단순화본 base 는 `7430a4f feat(infra): ... --cycle-channel ... (#1049)` 머지 시점 (15:21) 직전 또는 직후. 단순화본은 다음 기능 누락:

| 누락 기능 | 머지 PR | 운영 영향 |
|---|---|---|
| `--cycle-channel <be\|fe\|rev\|plan>` | #1049 (origin/develop 머지 완료) | nmae P12 per-cycle 라우팅 즉시 silent fail. **운영 사고 즉발**. |
| `--directive-board` mode | #1042 (open) | 머지 전 영향 적음 |
| forum API (`--forum-post/comment/edit/retag`) | #1056 (open) | 머지 전 영향 적음 |

## 검증 결과 (be sub-agent)

- syntax check `bash -n`: 두 파일 모두 OK.
- 단순화본 prod 교체 후 기존 prod tests 실행 결과:
  ```
  Ran 50 tests in 0.887s — FAILED (failures=3)
  - test_env_var_overrides_file_and_last_id (#987 race)
  - test_queue_last_pending_used_when_target_file_missing (#987 race)
  - test_target_file_freeze_overrides_last_id_race (#987 race)
  ```
- 단순화본 + 단순화본 tests 조합 (드롭 feature 일치) 자체는 self-consistent.
- 단순화본 운영 호환성 sanity:
  ```
  $ /tmp/test-simplified.sh --cycle-channel be "test"
  discord-reply.sh: 알 수 없는 옵션 --cycle-channel
  ```
  → nmae `nmae-discord-push.sh --cycle be` / `agent-launch-wrapper.sh` 호출 즉시 실패.

## 사용자 결정 필요 항목 (이슈 #1058 본문 참조)

1. **#987 race 5단 → 2단 폐기 의도 확정**: tests 코멘트 "사용자 판단" 인용 — 사용자가 폐기 결정했는지 nmae 가 자율 결정했는지 모호.
2. **#1049 cycle-channel 보존 여부**: 운영 중인 P12 per-cycle 라우팅 보존 필요.
3. **#1042 / #1056 open PR 머지 순서**: 단순화 시점을 #1042/#1056 머지 후로 미룰지.

## 처분

- 본 디렉토리는 **prod 교체 보류 staging 사본** — `bot.py` / `cycle_idle_watch_loop` 등 어떤 호출자도 이 경로를 import 하지 않습니다.
- 사용자 결정 후 다음 분기:
  - **단순화 확정 + cycle-channel backport**: mmae v2 단순화본 작성 후 prod 교체.
  - **race 폐기 reject**: 단순화본 폐기. 본 staging 디렉토리 삭제.
  - **부분 단순화 (helper-turn-start.sh 만)**: cycle-channel 의존 없는 helper-turn-start.sh 만 4 액션 단순화 적용. discord-reply.sh 는 보존.
