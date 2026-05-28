---
# Frontmatter 필드 룰 (자세한 내용 — `docs/features/README.md §10` 참조)
# - feature/slug/status/owner/scope: 필수, 변경 시 PR 본문에 사유 명시
# - status: 유효 값 = draft|approved|implementing|shipped|deprecated|blocked
# - related_issues: 정수 배열 (`[123, 456]`). `#` prefix 금지
# - related_prs: 정수 배열 (`[123, 456]`). `#` prefix 금지.
#   *PR 머지 시 본 spec 본문에 영향을 미친 PR 번호를 즉시 추가할 의무*
#   (rev 가 머지 직전 단계 1 audit 에서 누락 발견 시 fail 처리 가능 — README.md §10)
# - last_reviewed: 마지막 종합 리뷰 일자 (YYYY-MM-DD). 부분 갱신만 한 PR 은 갱신 불요.
#
# ⛔ Legacy 키 사용 금지 (사례 박제: PR #1169 / #1167)
#   다음 legacy 키는 README §10 표준이 정착되기 이전 형태입니다. 신규 spec / 갱신 모두 금지:
#     - `name`            → `feature` (사람이 읽는 이름) + `slug` (파일명) 로 분리
#     - `owners`          → `owner` (단수, `@<github-handle>` 형식)
#     - `related-issues`  → `related_issues` (snake_case)
#     - `related-prs`     → `related_prs` (snake_case)
#     - `["#882"]`        → `[882]` (정수, `#` prefix 금지, 문자열 quote 금지)
#   `.github/workflows/spec-status-check.yml` 이 legacy 키 발견 시 hard fail 합니다
#   (`docs/features/spec-status-check-legacy-key-fail.md` 참조).
feature: <기능 이름>
slug: <파일명과 동일>
status: draft
owner: @<github-handle>
scope: <user|song|recommendation|voice|infra|web|feedback>
related_issues: []
related_prs: []
last_reviewed: YYYY-MM-DD
---

# <기능 이름>

## 1) 개요 (What / Why)
- 이 기능이 무엇을 하고, 왜 필요한가. 3~5줄.
- 대상 사용자(액터)와 해결하려는 문제.

## 2) 사용자 시나리오
- 주요 유스케이스 1~3개를 행동 중심으로 기술.
- 예: "사용자는 노래방에 들어가 …한 상황에서 …을 하기 위해 …한다."

## 3) 요구사항
### 기능 요구사항
- [ ] 반드시 해야 할 동작 목록
### 비기능 요구사항
- 성능, 보안, 신뢰성, 관측성 등

## 4) 범위 / 비범위 (중요)
### 포함
- 이번 기능에서 반드시 다루는 것
### 제외 (Out of Scope)
- 유사하지만 **이번에 하지 않기로** 명시한 것. 스코프 크립 방지의 핵심.

## 5) 설계
### 5-1) 도메인 모델
- 어떤 엔티티/컨텍스트를 건드리는가. `docs/ai-harness/06-domain-model.md`의 섹션 참조.

### 5-2) API 엔드포인트
| Method | Path | 설명 | 인증 | Req | Res |
|---|---|---|---|---|---|
| POST | /api/v1/... | ... | 필수 | DTO | DTO |

### 5-3) 외부 연동
- 사용하는 외부 서비스/라이브러리, API 키 관리 방식, 실패 처리

### 5-4) 데이터 흐름 / 시퀀스
- 필요 시 Mermaid sequence diagram 또는 단계별 설명

### 5-5) DB 마이그레이션
- 필요한 테이블/컬럼 변경. `docs/ai-harness/06-domain-model.md` §5와 일관 유지

### 5-6) 프론트엔드 화면 (해당 시)
- 라우트, 주요 컴포넌트, 상태 관리 흐름

## 6) 작업 분할 (예상 PR 리스트)
- [ ] PR 1: ...
- [ ] PR 2: ...

## 7) 테스트 전략
- 단위/통합/E2E 어떤 범위로 테스트할지
- 외부 연동 mock 전략

## 8) 오픈 질문
> 구현 전에 답이 나와야 하는 것들. 해소되면 §9 결정 로그로 이동.

| # | 질문 | 선택지 | 담당/기한 |
|---|---|---|---|
| Q1 | ... | (a) ... / (b) ... | @owner / YYYY-MM-DD |

## 9) 결정 로그
> 연대기 순. "YYYY-MM-DD: 결정 / 이유 / 출처(PR 번호 등)"

- YYYY-MM-DD: 초안 작성 (status=draft)
