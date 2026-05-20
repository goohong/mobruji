---
id: 0001
title: 기술 스택 및 모노레포 구조 채택
status: accepted
date: 2026-05-20
deciders: [@goohong]
---

# 0001. 기술 스택 및 모노레포 구조 채택

## Context
mobruji는 노래방 추천 서비스의 신규 프로젝트로, 백엔드 + 프론트엔드가 모두 필요한 풀스택 토이 프로젝트다. 1인 개발 + AI(Claude Code/Codex) 보조 사이클 학습이 부수 목표이며, 운영 부담을 최소화하면서 추후 확장 여지를 남겨야 한다.

## Decision
- **Backend**: Spring Boot 3.5.3 / Java 21 / Gradle. 기존 `ppiyaki-backend` 하네스·컨벤션·CI 패턴을 재활용한다.
- **Frontend**: Next.js (App Router) / TypeScript / Tailwind. SEO·모바일 PWA 친화 + Spring 백엔드와 분리도 깔끔.
- **DB**: MySQL 8.4. 로컬은 docker-compose, 운영은 추후 ADR로 결정.
- **저장 형태**: 단일 모노레포 (`backend/`, `web/`, `docs/`).

## Consequences
### 긍정적
- 하나의 PR/이슈 트래커에서 풀스택 변경을 추적 가능
- 피야키 하네스 그대로 가져와 셋업 속도 가장 빠름
- backend↔web 타입/DTO 동기화 비용 낮음 (한 저장소 안에서 cross-reference)

### 부정적
- backend와 web의 CI 트리거를 path filter로 분리해야 함 (`backend/**`, `web/**`)
- 두 빌드 도구(Gradle, npm)가 한 레포에 공존 → 빌드 환경 셋업 복잡도 증가
- 향후 팀 분리·서비스 분리 시 멀티 레포 분리 비용 발생

## Alternatives (considered)
- **(A) 백/프론트 분리 레포**: 1인 개발 단계에선 트래커 분산이 더 큰 비용. 모노레포가 압도적으로 편함.
- **(B) Next.js fullstack (API Routes)**: 백엔드 분리 안 함으로 인프라 최소화 가능하지만, Spring/Java 학습 트랙·기존 하네스 재활용 불가. 채택하지 않음.
- **(C) NestJS 백엔드**: 프론트와 언어 통일(TS) 매력 있으나 피야키 하네스 재활용 불가, 셋업 비용 큼.

## References
- 피야키 하네스 원본: `https://github.com/goohong/ppiyaki-backend` (Spring Boot + MySQL 패턴 출처)
- `CLAUDE.md` §2 기술 스택
