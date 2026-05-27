---
feature: Discord Forum-Driven Operations v2 (Task Queue & Issue Isolation)
slug: discord-forum-ops-v2
status: proposed
owner: @goohong
scope: infra
related_issues: []
related_prs: []
last_reviewed: 2026-05-27
---

# Discord Forum-Driven Operations v2 (Task Queue & Issue Isolation)

## 1) 개요 (What / Why)
기존의 단일 채널, `send-keys` 방식의 운영을 **Discord Forum 쓰레드**와 **SQLite Task Queue** 기반으로 전면 개편한다. 작업 단위로 대화와 실행 환경을 격리하여 안정성과 정확도를 극대화하는 것이 목표이다.

## 2) 사용자 시나리오
1. **요청 발의**: 사용자가 Forum 채널에서 새 포스트를 작성하거나 메인 채널에서 명령을 내린다.
2. **쓰레드 생성**: Dispatcher(Bot)가 해당 요청을 위한 독립적인 쓰레드를 생성한다.
3. **작업 큐잉**: Bot이 SQLite DB에 작업을 `PENDING` 상태로 등록한다.
4. **워커 투입**: 대기 중인 Worker(nmae)가 작업을 인지하고, 전용 tmux pane으로 이동하여 작업을 시작한다.
5. **결과 보고**: 작업이 완료되면 Worker가 해당 쓰레드에 결과를 요약해서 답변하고, Bot이 쓰레드 제목에 `[Done]` 태그를 붙인다.

## 3) 요구사항
### 기능 요구사항
- [ ] **F1. Forum 쓰레드 매핑**: 각 Discord 쓰레드를 고유한 `task_id`와 연결.
- [ ] **F2. SQLite Task Queue**: `tasks` 테이블(id, thread_id, payload, status, created_at) 운영.
- [ ] **F3. Dispatcher 로직**: 사용자 메시지 파싱 → 큐 등록 → 쓰레드 생성/관리.
- [ ] **F4. Worker (Polling)**: 주기적으로 큐를 확인하여 자기 도메인의 작업을 수행.
- [ ] **F5. 상태 동기화**: 작업 진행률(%) 및 에이전트 로그를 쓰레드에 실시간/주기적 업데이트.

### 비기능 요구사항
- **신뢰성**: 시스템 재시작 시에도 큐에 담긴 작업은 보존되어야 함.
- **성능**: 큐 등록 후 워커 인지까지 지연 시간 < 5s.
- **편의성**: 폰에서 쓰레드 목록만 봐도 전체 프로젝트의 '진척 상황'이 한눈에 보여야 함.

## 4) 설계 (Draft)

### 4-1) DB 스키마 (SQLite)
```sql
CREATE TABLE tasks (
    task_id INTEGER PRIMARY KEY AUTOINCREMENT,
    discord_thread_id TEXT NOT NULL,
    requester_id TEXT NOT NULL,
    payload TEXT NOT NULL,          -- JSON 지시 사항
    status TEXT DEFAULT 'PENDING',  -- PENDING, WORKING, COMPLETED, FAILED
    result TEXT,                    -- 최종 요약 결과
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
```

### 4-2) 컴포넌트 구조
1. **Dispatcher (bot.py)**: Discord Gateway 수신부. 큐 관리 및 쓰레드 관리.
2. **Issue Worker (nmae-loop.sh)**: 큐를 감시하다가 작업이 들어오면 `claude`를 특정 모드로 실행.
3. **Feedback Bridge**: 워커의 stdout 마커를 읽어 Discord 쓰레드에 push.

## 5) 작업 계획 (Implementation Phases)

### Phase 1: 인프라 구축 (Foundations)
- [ ] SQLite 테이블 설계 및 `bot.py` 큐잉 로직 추가.
- [ ] Discord Forum 채널 설정 및 쓰레드 생성 기능 구현.

### Phase 2: 워커 개편 (Worker Overhaul)
- [ ] `nmae`가 큐에서 작업을 꺼내어 수행하는 Polling 루프 구현.
- [ ] `send-keys` 의존성 제거.

### Phase 3: UX 고도화 (Refinement)
- [ ] 쓰레드 제목 자동 업데이트 (이슈 요약 + 상태 태그).
- [ ] Lobby Helper의 쓰레드 안내 로직 추가.
