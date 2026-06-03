---
feature: Cycle forum 운영 모델 — 모든 작업 individual thread + 4 tag 자동 전이
slug: cycle-forum-operation
status: approved
owner: @mobruji-maestro
scope: infra
related_issues: [1514]
related_prs: [1306, 1515]
last_reviewed: 2026-06-03
---

# Cycle forum 운영 모델

## 1) 개요 (What / Why)

cycle forum (BE/FE/REV/PLAN) thread 가 launch 시점에 1회 tag 부착 + 본문 raw template → 사용자가 sidebar 에서 진행 상황 즉시 파악 불가. 사용자 정정 (2026-05-28): "rev 포럼이나 그 외 sub-agent forum 들이 다 초기 라벨 그대로인 것 같아. 진행 상황 반영이 잘 안되는 거 같은데".

본 spec = **cycle forum 의 통합 운영 모델**. 모든 작업 = individual thread + 4 tag 자동 전이 + 본문 template 표준화.

## 2) 사용자 시나리오

- **시나리오 1 (대기 작업 등록)**: nmae 가 directive backlog 에서 작업 1개 선정 → `agent-launch-wrapper.sh --register-pending <cycle> "<title>"` 호출 → cycle forum 에 🟡 대기 thread 신설. 사용자가 sidebar 에서 "BE 대기 작업 3건" filter 가능.
- **시나리오 2 (launch)**: nmae 가 🟡 대기 thread 의 작업 시작 → wrapper 가 기존 thread 재사용 + tag 🟡 → ⏳ retag + 본문 진행 [x] launch update.
- **시나리오 3 (PR 머지 자동 완료)**: sub-agent PR 머지 → bot.py `cycle_thread_complete_on_merge_loop` 가 5분 polling 으로 detect → tag ⏳ → ✅ retag + 본문 ✅ 결과 섹션 자동 PATCH.
- **시나리오 4 (실패 / HOLD)**: rev 가 ❌ 발견 또는 사용자 HOLD 결정 → `discord-reply.sh --forum-retag <id> <cycle> "실패" --reason "<사유>"` 호출 (사유 필수). 본문 `❌ 종결 사유: <reason>` 자동 부착.
- **시나리오 5 (사용자 forum 회고)**: 한 달 뒤 사용자가 BE forum sidebar 에서 ✅ 완료 filter → 머지된 작업 list + ❌ 실패 → 종결 사유까지 회고 가능.

## 3) 요구사항

### 기능 요구사항

- [x] **모든 cycle 작업 = individual thread** — backlog single thread (`[BACKLOG] <cycle>`) 폐기 (2026-06-03 구현 — discord-reply.sh `--cycle-backlog-upsert` no-op + wrapper `--refresh-backlog` skip + upsert.sh no-op).
- [ ] **tag 4종**:
  - 🟡 대기 (launch 전)
  - ⏳ 진행 (launch 후)
  - ✅ 완료 (PR 머지)
  - ❌ 실패 (rev / 사용자 HOLD)
- [ ] **template (확장)** — 단계 / 결과 / 시간 / 다음 단계 + 관련 PR / directive 4 종 정보 cover.
- [ ] **자동 전이**:
  - 🟡 신설: `wrapper --register-pending <cycle> "<title>"` 호출 — nmae 가 backlog 등록 시.
  - 🟡 → ⏳: wrapper launch 시 기존 🟡 thread 재사용 + retag (코드 강제).
  - ⏳ → ✅: bot.py polling (`cycle_thread_complete_on_merge_loop`, PR B 자매) — PR 머지 detect (학습 의존 X).
- [ ] **❌ 학습 의존 + 사유 강제**: `discord-reply.sh --forum-retag <id> <cycle> "실패" --reason "<사유>"` 호출. `--reason` 누락 시 exit 1 + 본문 `❌ 종결 사유: <reason>` 자동 PATCH.
- [x] **backlog deprecate** (2026-06-03): `tools/cycle-backlog/upsert.sh` no-op + `discord-reply.sh --cycle-backlog-upsert` no-op (thread-creating tool 단 차단) + `agent-launch-wrapper.sh --refresh-backlog` warning + skip. 기존 `[BACKLOG]` thread (be/fe/plan) archive+lock 완료.

### 비기능 요구사항

- **graceful**: 전이 실패 (Discord 4xx / token 부재) → stderr warning + 다음 iter 재시도. sub-agent / wrapper 흐름 차단 X.
- **사용자 가시화 일관**: 4 tag = sidebar filter 즉시. 단계별 정밀 추적 = 본문 체크박스.
- **운영 부담 최소**: 자동 전이 3개 (🟡 신설 / 🟡→⏳ / ⏳→✅) 코드 강제. ❌ 학습 의존 1개.

## 4) 범위 / 비범위

### 포함

- cycle forum thread 의 운영 모델 (라이프사이클 / tag / template / 전이 trigger).
- wrapper / bot.py / discord-reply.sh / sub-agent.md 룰 변경.
- backlog deprecate path.

### 제외 (Out of Scope)

- directive forum 의 운영 (이미 [[directive-board-template-and-tags]] SoT).
- cycle 별 forum 채널 신설 / Discord 채널 구조 변경 (기존 BE/FE/REV/PLAN forum 유지).
- 사용자가 직접 forum thread 안 댓글 → 새 directive 등록 흐름 (별도 spec PR E §5-6).

## 5) 설계

### 5-1) Tag 체계

| tag | 의미 | 전이 trigger | 자동화 |
|---|---|---|---|
| 🟡 대기 | nmae 가 작업 등록 (launch 전) | `wrapper --register-pending <cycle> "<title>"` | ✅ 코드 강제 |
| ⏳ 진행 | sub-agent launch | `wrapper launch` 가 기존 🟡 thread retag → ⏳ | ✅ 코드 강제 |
| ✅ 완료 | be/fe/plan: PR 머지 / rev: 사이클 idle 진입 | be/fe/plan = `bot.py cycle_thread_complete_on_merge_loop` polling · rev = `update.sh rev set-idle` 가 active thread retag (§5-8) | ✅ 코드 강제 |
| ❌ 실패 | rev ❌ 발견 / 사용자 HOLD | sub-agent / rev 가 `discord-reply.sh --forum-retag <id> <cycle> "실패" --reason "..."` 호출 | ⚠️ 학습 의존 + 사유 강제 |

mutually exclusive. 한 thread = 한 tag.

### 5-2) Thread template (확정, PR D 보강)

```text
🛠️ **{title}**

💬 작업 / 의도
{description — nmae 위임 사유, directive 본문 일부}

🆔 사이클: `{cycle}` · 📋 PR: #{pr_num | "—"}
🕐 launch: {launch_ts | "대기 중"} · ⏱️ 진행 {elapsed | "—"}

📋 진행 ({현재 단계})
- [ ] launch (nmae 위임)
- [ ] 분석 / 설계
- [ ] 구현 — branch=`{branch}`
- [ ] 검증 (lint / test / typecheck)
- [ ] PR 생성
- [ ] PR 머지

✅ 결과 *(종결 시점에만 채워짐)*
{머지 후 요약 / 실패 사유 / HOLD 사유}

⏭️ 다음 단계
{즉시 다음 액션 1줄}

🔖 관련
- PR: #{pr_num}
- directive: `{id}` (있으면)

---
_갱신: {ts} · 자동 PATCH by `{actor}`_
```

**필드 설명**:
- `{title}` = 작업 제목 (직관적).
- `{description}` = 작업 의도 / nmae 위임 사유 / 관련 directive 본문 일부.
- `{cycle}` = be / fe / rev / plan.
- `{pr_num}` = 관련 PR 번호 (launch 전엔 `—`).
- `{launch_ts}` = launch 시각 (KST). 🟡 대기 상태에서 `대기 중`.
- `{elapsed}` = launch 후 경과 (분). `—` 시 launch 전.
- 진행 6 단계: launch → 분석 → 구현 → 검증 → PR 생성 → PR 머지. 각 단계에서 sub-agent / wrapper 가 [x] update.
- `✅ 결과`: 종결 시점만. PR 머지면 머지 commit 요약. 실패면 사유. HOLD 면 보류 사유.
- `⏭️ 다음 단계`: 1줄. sub-agent 가 milestone 갱신 시 update.

### 5-3) wrapper — `--register-pending` mode 신설

```bash
# nmae 가 backlog 등록 시:
bash tools/agent-launch-wrapper.sh --register-pending <cycle> "<title>" \
  [--description "..."] [--directive-id "..."]
```

동작:
1. 해당 cycle 의 forum 채널 (`BE_FORUM_ID` 등) 에 새 thread post.
2. 본문 = template (단계 6 체크박스 모두 unchecked, 결과/다음 빈, 🆔 cycle 정보).
3. tag = `🟡 대기` 부착 (`discord-reply.sh --forum-post-auto-tag` 의 fallback chain 에 `대기` 우선).
4. stdout: `PENDING_THREAD_ID=<id>` (nmae 가 cache 가능).

> **fallback chain 가드 (PR #1306)**: `PENDING_THREAD_ID` / `LAUNCH_THREAD_ID` 가 `LaunchThreadCacheFile` (`~/.mobruji/last-launch-thread.txt`) 로 atomic write 되는 경로는 PlaceholderThreadId (예: `99999`) 오염에 노출됩니다. 4 갈래 가드 (F-1 write 검증 / F-2 auto-ack 추출 검증 / F-3 reader quarantine / F-4 wrapper stale invalidate / F-5 test fixture env override / F-6 hook mock isolation 검증) 는 `docs/features/cycle-forum-placeholder-guard.md` SoT (도메인 용어: `06-domain-model.md §4` PlaceholderThreadId 항목).

### 5-4) wrapper — launch mode 가 기존 🟡 thread 재사용

```bash
bash tools/agent-launch-wrapper.sh <cycle> --title "..." [--pending-thread-id <id>]
```

동작:
1. `--pending-thread-id` 명시 시 → 기존 thread 재사용 + retag 🟡 → ⏳.
2. 미명시 시 → 새 thread 신설 (현재 동작 — 🟡 단계 없이 바로 ⏳).
3. 본문 update — `[x] launch` 체크, `launch_ts` 박힘.

### 5-5) bot.py — `cycle_thread_complete_on_merge_loop` 신설

PR B (`directive_complete_on_merge_loop`) 의 자매 loop:
- 5분 polling
- `gh pr list --base develop --state merged --search 'merged:>1h ago' --json number,url,body,headRefName`
- branch ↔ thread_id cache (`~/.mobruji/cycle-launch-cache.jsonl`) lookup
- 매칭 시 `discord-reply.sh --forum-retag <thread_id> <cycle> "완료"` 호출 + 본문 PATCH (`[x] PR 머지` + `✅ 결과` 섹션)

cache 메커니즘:
- wrapper launch 시 `{branch, thread_id, cycle, launched_at}` jsonl append
- loop 가 PR 머지 detect → branch lookup → thread_id → retag
- cache cap 200 (FIFO truncate)

### 5-6) discord-reply.sh `--reason` 검증

```bash
discord-reply.sh --forum-retag <thread_id> <cycle> "실패" --reason "<사유>"
```

동작:
1. `"실패"` tag 시 `--reason` 필수 — 누락 → exit 1 + stderr "실패 사유 명시 의무, --reason 필수".
2. `--reason` 있으면 본문에 `❌ 종결 사유: <reason>` line 자동 PATCH (기존 `✅ 결과` 섹션 교체).
3. ✅ / 🟡 / ⏳ tag 시 `--reason` 옵션 (선택).

### 5-7) backlog deprecate path (구현 완료 2026-06-03)

- `tools/cycle-backlog/upsert.sh` — deprecation warning stderr emit 후 no-op exit 0 (호환). 디버깅용 escape: `CYCLE_BACKLOG_FORCE=1` 시에만 markdown 빌드 (그래도 discord push 는 아래 차단으로 no-op).
- `discord-reply.sh --cycle-backlog-upsert` — **thread-creating tool 단에서 차단**: 신설/PATCH 안 하고 deprecation warning + no-op exit 0. 어느 호출자(wrapper / upsert.sh / 수동 / agent)가 호출해도 새 `[BACKLOG]` 스레드가 생기지 않는다 (회귀 root-cause 차단 지점).
- `agent-launch-wrapper.sh --refresh-backlog` flag / `CYCLE_BACKLOG_REFRESH_DEFAULT` → warning + skip (인자 파싱 호환 유지).
- 기존 `[BACKLOG] <cycle>` thread (be/fe/plan, rev 는 부재) = archive+lock 완료 (history 보존, 삭제 X). bot.py 자동 삭제 X.

### 5-8) rev 사이클 완료 retag (#1514)

**문제 (evidence)**: §5-5 의 `cycle_thread_complete_on_merge_loop` 는 ✅ 완료 전이를
머지된 PR 본문의 `cycle-forum: <cycle>:<thread_id>` 교차참조에만 의존한다. rev 사이클은
be/fe 의 PR 을 리뷰·QA 할 뿐 `cycle-forum: rev:<thread_id>` 를 담은 자체 PR 을 머지하지
않으므로, rev launch thread 의 thread_id 는 어떤 머지 PR 본문에도 등장하지 않아 ⏳ 진행에
영구 고정된다. 추가로 `agent-launch-wrapper.sh` 는 launch 직후 pending cache 를 삭제하므로
완료 시점에 thread 핸들조차 남지 않는다.

**fix**:
1. `agent-launch-wrapper.sh` launch retag(🟡→⏳) 시점에 active thread_id 를
   `~/.mobruji/cycle-active-thread/<cycle>.txt` 로 atomic 기록 (모든 cycle 공통).
2. `update.sh <cycle> set-idle` 후속 단계에서 `cycle == rev` 일 때만 위 파일을 읽어
   `discord-reply.sh --forum-retag <thread_id> rev "완료"` 호출 + 파일 정리.

**스코프 / 근거**:
- rev 의 실제 완료 신호는 사이클 idle 진입(`set-idle`)이다 (PR 머지가 아님).
- be/fe/plan 은 기존 PR 머지 retag(§5-5)를 유지한다 — set-idle 은 PR 머지 전에도
  발생할 수 있어 조기 완료 오전이를 막기 위해 rev 로 한정.
- graceful: retag 실패가 set-idle 자체(cycle-status 갱신)를 막지 않는다.
- active thread 파일 부재 / 비-snowflake 시 silent skip.

## 6) rollback plan

각 PR 단위 revert 가능 — 흐름 별 독립:
- spec / sub-agent.md / nmae.md 룰 revert → 학습 의존 fallback (sub-agent 가 매번 호출 룰 따라).
- wrapper `--register-pending` mode revert → 기존 launch 시 신설 흐름으로 회귀 (🟡 단계 사라짐).
- bot.py `cycle_thread_complete_on_merge_loop` revert → 자동 ✅ 안 됨. sub-agent 명시 호출 의존.
- discord-reply.sh `--reason` 검증 revert → ❌ 명시 시 사유 누락 가능.

graceful degradation — 부분 revert OK.

## 7) 작업 분할

- [x] **PR cf-1 (본 PR)**: spec + sub-agent.md §1-11 룰 update + actors/nmae.md §11-? register-pending 호출 의무.
- [ ] **PR cf-2**: agent-launch-wrapper.sh — `--register-pending` mode 신설 + launch mode 가 기존 🟡 thread 재사용 + template 확장.
- [ ] **PR cf-3**: bot.py `cycle_thread_complete_on_merge_loop` 신설 + discord-reply.sh `--reason` 검증 + 본문 자동 PATCH.
- [x] **PR cf-4 (cleanup, 2026-06-03)**: backlog deprecate — `--cycle-backlog-upsert`/`upsert.sh`/`--refresh-backlog` no-op + 기존 `[BACKLOG] <cycle>` thread (be/fe/plan) archive+lock. 사용자 directive 마다 옛 양식 스레드 재생성하던 회귀 차단.

## 8) 테스트 전략

### 단위 (PR cf-2, cf-3)
- wrapper `--register-pending` mode: 🟡 tag 부착 / template body 검증.
- wrapper launch + `--pending-thread-id`: 기존 thread retag.
- bot.py: `parse_branch_from_pr_body` + cache lookup + retag.
- discord-reply.sh: `--reason` 누락 시 exit 1 / 있으면 본문 PATCH.

### 통합 (수동, NCP 배포 후)
1. nmae 가 작업 등록 → 🟡 thread 신설 확인.
2. sub-agent launch → 같은 thread retag ⏳ 확인.
3. PR 머지 → 5분 안에 ✅ 자동 retag + 본문 PATCH 확인.
4. ❌ 명시 호출 → `--reason` 누락 fail / 있으면 본문 PATCH.

## 9) 결정 로그

- 2026-05-28 — 사용자 정정: cycle forum 진행 상황 가시화 부족. **결정**: 통합 운영 모델 도입 (모든 작업 individual thread + 4 tag 자동 전이 + template 표준).
- 2026-05-28 — **결정 1 (사용자, 옵션 Y)**: backlog single thread 폐기, 모든 작업 individual thread. 사유: sidebar filter 일관, nmae 부담 ↓.
- 2026-05-28 — **결정 2**: tag 4종 (🟡/⏳/✅/❌). 사용자 정정 "시작 안한 애들도 고려" 반영 — 🟡 대기 신규.
- 2026-05-28 — **결정 3**: ❌ 학습 의존 + `--reason` 필수 강제 (사용자: "실패의 경우에는 사유를 꼭 명시하도록 하자").
- 2026-05-28 — **결정 4**: ⏳ → ✅ 자동화 = branch ↔ thread_id cache (옵션 B) + bot.py polling (PR B 패턴 재사용). sub-agent 학습 의존 0.

## 10) 자율 결정 (사유)

- backlog thread archive 처리 — bot.py 자동 삭제 X (사용자 결정 보존). 사유: Discord history 가치 + 운영 안전.
- ETA (예상 종료) 미박음 (사용자 결정: "불명확하면 C"). 사유: history 평균 자동 추정도 정확도 낮음, TBD 누락이 명확.
- 결과 종결 시점에만 박음 (사용자 결정: "A"). 사유: 매 milestone 부담 ↑, 가독성 ↓.

## 11) 사용자 확인 필요

- (선택) 기존 `[BACKLOG] <cycle>` thread 4개 (BE/FE/REV/PLAN) 삭제할지 보존할지 — 운영 후 결정.
- Discord forum 채널의 `available_tags` 4종 (🟡 대기 / ⏳ 진행 / ✅ 완료 / ❌ 실패) 추가 — 사용자 manual 1회.

## 12) References

- [[directive-board-template-and-tags]] — directive forum 운영 SoT. cycle forum 과 유사 패턴.
- `tools/agent-launch-wrapper.sh` — launch hook (PR cf-2 변경 대상).
- `tools/discord-daemon/bot.py` — `directive_complete_on_merge_loop` (PR B), `rev_post_merge_audit_loop` 패턴 재사용 (PR cf-3).
- `tools/discord-daemon/discord-reply.sh` — `--forum-retag` 확장 (PR cf-3).
- `docs/ai-harness/actors/sub-agent.md §1-11` — milestone PATCH 룰 (PR D + cf-1 보강).
- 메모리: [[feedback-evidence-based-root-cause]] / [[feedback-autonomous-default]].

## 13) 변경 이력

- 2026-05-28 — 초안 작성 + PR cf-1 (spec + sub-agent.md / nmae.md 룰 update). status=approved (사용자 직접 정정 응답).
