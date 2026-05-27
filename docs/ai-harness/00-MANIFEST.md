# AI Harness Volume 0: MANIFEST & ROUTING

> Maestro(`nmae`)가 하위 에이전트의 페르소나를 설정하고 필요한 컨텍스트(Harness Volumes)를 주입할 때 사용하는 라우팅 지도입니다.

---

## 1. 에이전트별 주입 문서 (Injection Map)

Maestro는 각 서브 에이전트를 `Agent` 도구로 깨울 때, 작업의 성격에 따라 아래 볼륨들을 선택적으로 읽게 합니다.

| 에이전트 역할 | 필수 볼륨 (Primary) | 보조 볼륨 (Secondary) | 주요 작업 범위 |
|---|---|---|---|
| **Plan** | Vol 1 (Governance), Vol 2 (Architect) | Vol 3 (Standards - 형식 참조용) | 이슈 분석, ADR/Spec 작성, 도메인 모델 설계 |
| **BE** | Vol 1 (Governance), Vol 3 (Standards) | Vol 2 (BC/엔티티 참조용) | `backend/**` 구현, 단위/통합 테스트 |
| **FE** | Vol 1 (Governance), Vol 3 (Standards) | - | `web/**` UI 구현, 컴포넌트 테스트 |
| **Rev** | Vol 1 (Governance), Vol 3 (Standards) | Vol 2 (설계 의도 확인용) | 코드 리뷰, 보안 점검, 런타임 QA 검증 |
| **Helper** | Vol 16 (Helper Runbook) | Vol 1 (Governance - 말투 참조) | 사용자 응답, nmae 작업 위임, 현황 보고 |

## 2. 작업 경로별 지능형 라우팅 (Triage Logic)

Maestro는 사용자 지시를 분석하여 다음 두 경로 중 하나를 결정합니다.

### A. Standard Track (설계 우선)
- **조건**: 신규 기능, 도메인 변경, 복잡한 비즈니스 로직, 외부 시스템 연동.
- **프로세스**: `Plan` (Spec/ADR) → 사용자 승인 → `BE/FE` (구현) → `Rev` (검증).
- **주입**: 모든 에이전트에게 해당 볼륨 전체 주입.

### B. Fast Track (구현 우선)
- **조건**: 명확한 버그 수정, 단순 UI/텍스트 변경, 기존 패턴의 반복 작업, 파일 변경 < 3개.
- **프로세스**: `BE/FE` (즉시 수정) → `Rev` (검증).
- **주입**: `Vol 1`과 `Vol 3`의 관련 섹션만 발췌하여 주입 (토큰 절약).

## 3. 에이전트 보고 프로토콜 (Handoff)
- 모든 서브 에이전트는 turn 종료 시 `01-GOVERNANCE.md §2`의 보고 양식을 준수한다.
- Maestro는 보고서의 **발견 사항(🔴/🟡/🟢)**을 즉시 분석하여 다음 사이클의 백로그로 등록하거나 즉시 보완 작업을 할당한다.

---
**단일 진실 원칙 (SSOT)**: 
개별 `01, 02...` 문서와 본 `Volumes` 간에 내용 충돌이 있을 경우, 본 **Volumes(01~03)의 내용을 최신**으로 간주한다.
