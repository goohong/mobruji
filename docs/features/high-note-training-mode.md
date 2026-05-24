---
feature: 고음 뚫기 / 발성 연습 추천 모드 (F2)
slug: high-note-training-mode
status: draft
owner: @goohong
scope: recommendation
related_issues: []
related_prs: []
last_reviewed: 2026-05-24
---

# 고음 뚫기 / 발성 연습 추천 모드 (F2)

> 본 spec 은 `user-persona-and-pain-points.md` §5 F2 에서 분기된 **자식 spec stub** 이다. 본격 작성은 다음 plan 사이클에서 확장한다.

## 1) 개요 (What / Why)

- 페르소나 A (연습러) 의 핵심 motivation ("프로처럼 부르고 싶다") 및 페인 P2 ("고음 뚫는 노래 추천이 없네") / P6 (연습 트래커 부재) 를 해결.
- 사용자가 추천 흐름 안에서 **모드 = 일반 / 고음 뚫기 / 발성 연습 입문 / 음역 확장 도전** 4 모드 중 선택 → 모드별 필터 + 가중치 + 결과 카드 활용 팁.

## 2) 사용자 시나리오

1. 페르소나 A 가 "오늘은 고음 뚫기" 선택 → 본인 음역 최고음 + 1-2 키 범위 곡 우선 추천.
2. 결과 카드에 "이 곡 핵심 고음 구간 마디 16-20, 호흡 팁: 단전 호흡 후 가성 전환" 노출.
3. 다음 회 측정 시 비포·애프터 비교 (F5 트래커 연동) — v0.4.

## 3) 요구사항

### 기능 요구사항
- [ ] 추천 흐름에 모드 picker (4 모드)
- [ ] 모드별 큐레이션 태그 부착 (`training:high-note`, `training:vocal-basic`, `training:range-expand`, `mode:general`)
- [ ] 모드별 필터 + 가중치 (BE 추천 알고리즘)
- [ ] 모드별 결과 카드 활용 팁 (고음 구간 마디 / 호흡 팁 / 어려운 부분 mark)

### 비기능 요구사항
- 모드별 추천 후보 ≥ 5 곡 (큐레이션 seed 진행도에 의존 — selection 부족 시 fallback 룰)

## 4) 범위 / 비범위

### 포함
- song-curation seed 곡에 training:* 태그 부착 (수기 vs self-analysis 자동 — Q2)
- be 추천 알고리즘에 mode 가중치 추가
- fe 모드 picker + 결과 카드 확장

### 제외 (Out of Scope)
- 실제 발성 코칭 (외부 보컬 트레이너 영역) — v0.4+
- 비포·애프터 비교 (F5 트래커 — v0.4)
- 측정 알고리즘 자체 개선 (F1 / #209/#207)

## 5) 설계 (stub)

### 5-1) 도메인 모델
- `Song` 엔티티 또는 `SongTag` 조인 테이블에 `training:*` 카테고리 추가 검토 (DB 마이그레이션 — 보호 영역 라벨)

### 5-2) API
- `GET /api/v1/recommendations?mode=<general|high-note|vocal-basic|range-expand>&...` 쿼리 매개변수 추가

### 5-3) 추천 알고리즘 가중치
- (다음 plan 사이클 작성 — `recommendation-algorithm-v2.md` 매트릭스에 mode 행 추가)

### 5-6) 프론트엔드
- `/recommend` 페이지에 mode picker 추가 (mood picker 와 병행 또는 통합)
- `<RecommendationCard>` 에 `trainingTip` slot 추가

## 6) 작업 분할
- [ ] PR 1: `SongTag` 또는 `training:*` 카테고리 도입 + seed 부착 (수기 30곡 → 100곡 확장 사이클 안에 통합 가능, song-curation)
- [ ] PR 2: be 추천 mode 가중치 + filter (be)
- [ ] PR 3: fe mode picker + 결과 카드 활용 팁 slot (fe)
- [ ] PR 4: 결과 카드 활용 팁 컨텐츠 시드 작성 (song-curation, 수기)

## 7) 테스트 전략 — (다음 plan 사이클 작성)

## 8) 오픈 질문
| # | 질문 | 선택지 |
|---|---|---|
| Q1 | 모드 picker 와 mood picker 통합 vs 별도 step | (a) 통합 (b) 별도 step |
| Q2 | training:* 태그 부착 방식 | (a) 수기 (b) self-analysis 기반 자동 (c) 혼합 |
| Q3 | 모드 후보 부족 시 fallback | (a) 일반 모드로 자동 전환 (b) "추천 부족" 메시지 |

## 9) 결정 로그
- **2026-05-24**: stub 신설 (parent `user-persona-and-pain-points.md` F2 에서 분기).
