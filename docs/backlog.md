# Backlog (mobruji)

> 현재 진행 중·예정 작업의 한눈 뷰. PR/이슈/spec 상태를 통합한 living doc.
> 갱신은 PR 머지 시점에 같이 한다 (자동화는 추후).
> 정확한 상태는 GitHub Issues/PRs가 source of truth — 이 파일은 흐름 파악용.

## 진행 중 (In Flight)

| PR | 제목 | 타입/스코프 | 상태 | 비고 |
|----|------|------------|------|------|
| — | (없음) | — | — | — |

## Spec 상태

| Spec | Status | 첫 구현 PR |
|------|--------|------------|
| `voice-range-input` | approved | 대기 |
| `song-metadata-source` | approved | 대기 |
| `recommendation-algorithm-v1` | approved | 대기 |

## 다음 PR 큐 (예정 순서)

1. **PR `chore(infra)`**: 백엔드 품질 게이트 wiring + RestAssured (PR #8 기존)
2. **PR `chore(infra)`**: 4종 GitHub Actions (auto-label, auto-close issues, pr-size-check, spec-status-check)
3. **PR `chore(infra)`**: H2 테스트 프로파일 + application-test.yml
4. **PR `feat(voice)`**: VoiceRange 엔티티 + 익명 세션 등록/조회 API + 도메인 모델 §5/§6 갱신
5. **PR `feat(song)`**: Song 엔티티 + 시드 데이터 100곡 + 검색/조회 API
6. **PR `feat(recommendation)`**: 추천 v1 score 함수 + API
7. **PR `release`**: develop → main v0.1.0 (Merge commit)

## 보류 / 미정

- 프론트엔드(Next.js) 화면 — Codex 합류 후 영역 분담 적용. 백엔드 MVP 머지 후 진행.
- D4(회원/익명) 본격 spec — PoC 단계는 익명 세션으로 결정됨(voice-range-input.md Q2). 후속 spec은 회원 도입 시점에 작성.
- v2 추천(임베딩/LLM) — v1 hand-eval 결과 누적 후 spec 작성.

## 갱신 규칙

- PR 머지 시 "다음 PR 큐"에서 해당 항목 제거.
- spec status 전이 시(approved → implementing 등) "Spec 상태" 갱신.
- 새 보류 사항이 생기면 "보류 / 미정"에 추가.
