# tools/cycle-status

`~/.mobruji/cycle-status.json` 갱신 + watchdog 룰 도구.

spec: `docs/features/nmae-cycle-watchdog.md`

## 스키마 (확장 — 2026-05-24 PR #956)

```json
{
  "be": {
    "in_progress": null,                                // dict 또는 null
    "last_completed": {
      "pr": "#937",
      "title": "...",
      "completed_at": "2026-05-24T01:58:00Z"            // ISO8601
    },
    "note": "idle reason or next launch candidate",     // idle 시 의무
    "idle_since": "2026-05-24T02:14:00Z"                // idle 진입 시각 (auto)
  },
  "fe": { ... },
  "rev": { ... },
  "plan": { ... }
}
```

### 필드 의미

| 필드 | 타입 | 의무 | 설명 |
|---|---|---|---|
| `in_progress` | dict\|null | yes | active sub-agent task 메타 (`task`/`title`/`started_at`). null = idle |
| `last_completed` | dict | yes | 직전 완료 sub-agent (`pr`/`title`/`completed_at`) |
| `note` | str | **idle 시 의무** | 사유 명시 또는 다음 launch 후보. **없으면 watchdog STRICT relaunch 강제** |
| `idle_since` | ISO8601 | optional | idle 진입 시각 (수동/자동 기록) |

### 룰

1. `in_progress` 가 non-null → `note` 무관 (active 워크트리는 사유 불필요).
2. `in_progress` 가 null → `note` **의무**. 미명시 시 watchdog `cycle_idle_watch_loop` 가 STRICT prompt inject (즉시 다음 백로그 launch + note 기록 의무).
3. `note` 가 빈 string / whitespace → 미명시와 동일.
4. `note` 예시:
   - `"PR #857 docs 사전 보강 audit 대기"` (다음 launch 후보)
   - `"be cache eviction 완료 후 audit"` (의존 작업 대기)
   - `"context 94% 정리 사이클 임박"` (외부 사유)

## 갱신 스크립트

`tools/cycle-status/update.sh` — nmae 가 sub-agent launch/완료 시 호출.

```bash
# active 갱신 (in_progress 채움, note clear, idle_since clear)
update.sh be set-active --title "PR #937 follow-up cache eviction" --task "SessionActivityTracker LRU 상한"

# idle 갱신 (in_progress null, note 의무)
update.sh fe set-idle --note "다음 launch 후보: PR #856 score-breakdown fix"

# 완료 기록 (last_completed 갱신)
update.sh rev set-completed --pr "#940" --title "audit PASS — k6-load p95 21.62ms"
```

위반 시 watchdog 가 정정 — STRICT relaunch inject.

## 검증 스크립트

`tools/cycle-status/validate.sh [--path ~/.mobruji/cycle-status.json]`

idle 워크트리 중 `note` 누락 detect → exit 1 + 누락 목록 stderr. pre-commit / CI 용도가 아닌, nmae self-check 용.
