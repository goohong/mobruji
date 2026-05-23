---
id: 0017
title: Spring Boot 3.5 EOL 대응 — 3.6 라인 채택
status: proposed
date: 2026-05-24
deciders: [TBD]
---

# 0017. Spring Boot 3.5 EOL 대응 — 3.6 라인 채택

## Context

Spring Boot 3.5.x OSS 지원 라인은 2026-06-30 EOL 이다 (오늘 2026-05-24 기준 ~37일 후). EOL 이후 OSS 채널에서는 patch/security fix 가 더 이상 제공되지 않으며, 전이 의존성(Tomcat, Netty, Thymeleaf, Jetty, pgjdbc 등)에서 zombie CVE 노출이 시작된다. 본 프로젝트(`backend/build.gradle`) 는 현재 `org.springframework.boot 3.5.3` 을 사용하며, PR #905(가정) 로 3.5 라인 마지막 패치(3.5.12 예상) 까지 끌어올린 상태다.

EOL 시점이 한 달 남짓 남은 시점에서 다음 세 가지 선택지를 평가하고 하나를 결정해야 한다:

1. Spring Boot **3.6 라인**으로 사전 마이그레이션 (OSS 후속 라인 따라가기)
2. 3.5 라인 유지 + **HeroDevs Never-Ending Support (NES)** 등 유료 OSS 연장 지원 구독
3. **Spring Boot 4.0 GA** 출시를 기다려 직접 점프

이 결정은 ADR-0001 ("Spring Boot 3.5.3 채택") 의 후속이며, CLAUDE.md §2 의 "Java 21 (LTS)" 일관성과 맞물린다.

## Decision

**옵션 A: Spring Boot 3.6 라인 채택 (선결정 권장).**

- 3.6.x release 출시 일정 확인 후 6월 중 마이그레이션 PR 한 건으로 이관한다.
- 마이그레이션 spec 은 별도 Feature Spec (`docs/features/spring-boot-upgrade.md`) 으로 분리하여 deprecation list / breaking change 를 trace 한다.
- 본 ADR 은 `proposed` 로 둔 채 사용자(`deciders`) 확정 후 `accepted` 전환한다. 옵션 B / C 가 선택될 경우 본 ADR 을 `superseded by NNNN` 으로 닫고 새 ADR 을 작성한다.

근거: LTS-스러운 라인 따라가기가 CLAUDE.md §2 의 Java 21 LTS 선호 정책과 일관되며, 1인 개발 + AI 보조 사이클 운영 컨텍스트에서 운영 부담(zombie CVE alert / 별도 구독 관리) 을 최소화한다.

## Consequences

### 긍정적
- EOL 이후 OSS 채널에서 patch/security fix 를 지속 수신 (zombie dep CVE 노출 차단).
- ADR-0001 의 "OSS Spring Boot + LTS Java 21" 정책과 일관.
- HeroDevs 등 유료 라이센스 비용 0.
- 3.5 → 3.6 minor 점프는 일반적으로 breaking change 가 적어 마이그레이션 PR 1건으로 처리 가능.

### 부정적
- 3.6 deprecation/API 변경에 대응하는 마이그레이션 작업 (소규모로 예상되나 실측 필요).
- Spring Boot 3.6 자체 EOL 도 12 개월 사이클 — 1년 내 같은 결정(4.0 vs 3.7 등) 재방문 필요.
- 3.6 GA 가 EOL 시점(2026-06-30) 보다 늦게 나올 경우 일시적 gap 발생 → 3.6 GA 시점에 맞춘 마이그레이션 PR window 조정 필요.

## Alternatives (considered)

- **(B) Spring Boot 3.5.x 유지 + HeroDevs NES (유료 OSS 연장)** — 코드 변경 0 이지만 유료 라이센스 비용 발생. 1인 토이 프로젝트 컨텍스트에서 비용 정당화 어려움. AGPL-3.0 (ADR-0002) 라이센스 정책과의 충돌 검토 별도 필요. 거절.
- **(C) Spring Boot 4.0 GA 대기 후 직접 점프** — 출시 일정 불확실 (현재 마일스톤 미공개) + major 점프(3.x → 4.x)는 breaking change 폭이 큼 (Servlet API / Jakarta EE 버전, Spring Framework 7 의존성 등). EOL 시점에 GA 가 못 맞으면 zombie 노출 기간 발생. 거절.
- **(D) 아무 것도 하지 않음 (EOL 이후 3.5.x 유지)** — 1~3 개월 운영 가능하나 CVE 노출 + dependency 업데이트 채널 차단. 명시적으로 거절.

| 옵션 | 코드 변경 | 비용 | EOL 이후 patch | 결정 일정 |
|---|---|---|---|---|
| A (3.6 채택) — 추천 | 소 (1 PR) | 0 | OSS | 6월 중 |
| B (HeroDevs) | 0 | 유료 | 유료 | EOL 직전 |
| C (4.0 대기) | 대 (major) | 0 | OSS (GA 후) | GA 시점 미정 |
| D (방치) | 0 | 0 | **없음** | — |

## References

- 본 ADR 발견 출처: rev sub-agent backend dependencies audit (2026-05-23), 이슈 #906.
- ADR-0001: 기술 스택 및 모노레포 구조 (Spring Boot 3.5.3 채택 — 본 ADR이 후속).
- CLAUDE.md §2: Java 21 (LTS) — 본 ADR의 LTS 선호 근거.
- Spring Boot 지원 정책: <https://spring.io/projects/spring-boot#support> (옵션 A/B/C 채택 시 본 ADR `accepted` 전환과 함께 정확한 EOL/GA URL 보강).
- 후속 작업: `docs/features/spring-boot-upgrade.md` (마이그레이션 spec — 옵션 A 확정 후 작성).
