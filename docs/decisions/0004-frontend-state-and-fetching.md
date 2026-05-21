---
id: 0004
title: 프론트엔드 상태 관리 및 데이터 페칭 스택
status: accepted
date: 2026-05-21
deciders: [@goohong]
---

# 0004. 프론트엔드 상태 관리 및 데이터 페칭 스택

## Context
`web/`은 create-next-app 초기 상태로 BE API와 미연결이었다. 음역대 입력 → 추천 결과의 첫 E2E 흐름을 구현하면서 서버 상태(원격 API 캐싱·재시도·로딩)와 클라이언트 상태(익명 세션 ID, 입력 폼 임시 값)의 책임 경계를 한 번 정해 둘 필요가 있다. `CLAUDE.md §4 코드 컨벤션`은 이미 React Query를 권장하고 있으나 클라이언트 상태 라이브러리는 미정이었다.

## Decision
- **서버 상태**: TanStack Query (`@tanstack/react-query`). API 호출 캐싱·재시도·로딩 상태 일원화.
- **클라이언트 상태**: Zustand (+ `persist` 미들웨어). 익명 세션 ID 등 전역 클라이언트 상태에만 사용.
- **API 클라이언트**: `web/lib/api/`에 fetch 래퍼(`client.ts`) + 도메인별 함수 파일을 모아둔다. base URL은 `NEXT_PUBLIC_API_BASE_URL`, 기본값 `http://localhost:8080`.
- **스타일**: Tailwind v4 (이미 셋업, 변경 없음).
- 폼 상태/로컬 UI 상태는 React `useState`로 유지하고 Zustand에 넣지 않는다.

## Consequences
### 긍정적
- 서버/클라이언트 상태 책임 분리가 명확해진다.
- React Query devtools 없이도 로딩/에러 상태가 표준화돼 페이지마다 boilerplate가 줄어든다.
- Zustand는 보일러플레이트가 매우 적어 1인 PoC 단계의 인지 부담이 낮다.

### 부정적
- 의존성 2개 추가(`@tanstack/react-query`, `zustand`)로 번들 크기 증가.
- 팀이 커지면 클라이언트 상태 패턴(Redux/Jotai 등)으로 재논의 비용 발생 가능.

## Alternatives (considered)
- **(A) SWR** — 충분히 가볍지만, mutation/캐시 무효화 API 표현력은 TanStack Query가 우위. PoC 이후 추천 결과 페이지 캐시 무효화 등 사용처가 예상돼 보류.
- **(B) Redux Toolkit + RTK Query** — 단일 스토어 일원화 장점은 있으나 1인 PoC 단계엔 과한 보일러플레이트.
- **(C) Jotai/Recoil** — atom 모델이 훌륭하지만 학습 비용이 Zustand보다 미세하게 높고 현재 전역 상태가 사실상 세션 ID 하나뿐이라 과한 도구.
- **(D) Context API만 사용** — 세션 ID 정도엔 충분하지만 `persist` 직접 구현이 필요해 결국 라이브러리 도입과 큰 차이가 없음.

## References
- `CLAUDE.md` §4 (React Query 권장, Zustand/Jotai 후보)
- PR #39
- `docs/features/voice-range-input.md`
- `docs/features/recommendation-algorithm-v1.md`
