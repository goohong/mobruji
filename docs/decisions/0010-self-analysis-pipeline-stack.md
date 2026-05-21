---
id: 0010
title: 자체 곡 분석 파이프라인 실행 stack — Python worker + Spring ProcessBuilder
status: accepted
date: 2026-05-21
deciders: [@goohong]
---

# 0010. 자체 곡 분석 파이프라인 실행 stack — Python worker + Spring ProcessBuilder

## Context
`docs/features/song-self-analysis-pipeline.md`(draft) Q2 — JVM(Spring Boot) 백엔드에서 자체 분석 파이프라인(yt-dlp + Spleeter + Librosa)을 어떻게 호출할지 stack을 결정해야 한다. audio 출처는 ADR 0006에 따라 YouTube extract로 확정. Spleeter/Librosa는 Python 생태계가 사실상 표준이며 JVM 음향 ML 도구는 부족하다. v0.2 PoC 단계에서 운영 부담을 최소화하면서 표준 도구를 쓸 stack이 필요하다.

후보:
- (A) **Python worker** (Spleeter/Librosa) + Spring `ProcessBuilder` 호출 — 표준 음향 ML stack
- (B) **JVM 자체 구현** (TarsosDSP 등) — 단일 stack, 음향 도구 부족
- (C) **Microservice 분리** (Python FastAPI + Docker) — 운영 부담 ↑
- (D) **AWS Lambda Layer** — 운영 의존, vendor lock-in

## Decision
PoC(v0.2) 단계의 분석 실행 stack은 **(A) Python worker + Spring ProcessBuilder**로 한다.

- `backend/scripts/analysis/` 또는 `tools/audio-analysis/` Python 디렉토리를 신설하고 `requirements.txt`(yt-dlp, spleeter, librosa, numpy)로 의존성을 잠근다.
- Spring application 계층에 `AudioAnalysisRunner`를 두어 `ProcessBuilder`로 Python entry script를 호출하고 stdout JSON 결과를 파싱해 도메인 모델로 매핑한다.
- v0.3 이상에서 분석량/운영 환경이 확장되면 (C) microservice 분리를 재검토한다. 본 ADR을 그대로 운영 단계로 끌고 가지 않는다.

## Consequences
### 긍정적
- Spleeter/Librosa의 Python 생태 표준 자산을 그대로 활용. JVM 포팅 비용/리스크 0.
- monorepo 단일 배포 단위 유지. PoC 단계에서 추가 서비스 운영(컨테이너/네트워크/배포 파이프라인) 부담 없음.
- 분석 batch가 Spring 트랜잭션과 분리되어 Python 프로세스 실패가 JVM 힙을 오염시키지 않음.
- 사용자 결정 로그/스펙 §5-3과 Q2를 한 ADR로 봉합 가능.

### 부정적
- JVM과 Python 두 런타임을 동시에 관리(local dev/CI/배포 환경에 Python + system deps 필요). Spleeter는 TF/PyTorch 의존성이 무거움.
- `ProcessBuilder` 기반 호출은 동시성/스트리밍 제어가 빈약 — 대량 배치/실시간 분석에는 부적합. 본 ADR은 PoC 한정.
- 결과 전달이 stdout JSON 기반이라 schema 변경 시 양쪽 동기화 필요.
- Python 의존성 보안 패치/업데이트가 별 사이클로 떨어짐.

## Alternatives (considered)
- **(B) JVM 자체 구현 (TarsosDSP 등)** — 단일 stack의 매력이 크나 vocal source separation에 해당하는 Spleeter 등가 JVM 도구가 사실상 부재. pitch detection만으론 vocal/반주가 섞여 PoC 정확도 목표 미달 우려. 채택하지 않음.
- **(C) Microservice 분리 (Python FastAPI + Docker)** — 장기적으로 가장 깔끔하나 PoC 단계에서 배포 단위 추가, 네트워크/인증/관측성 셋업 비용이 과함. v0.3 확장 시 재평가. 채택하지 않음.
- **(D) AWS Lambda Layer** — Spleeter/TF 의존성 패키지 크기가 Lambda 한도와 충돌. cold start, vendor lock-in, PoC에 과한 운영 의존. 채택하지 않음.

## References
- `docs/features/song-self-analysis-pipeline.md` §5-3 도구 스택, Q2 — 본 ADR이 결정.
- `docs/decisions/0006-audio-source-youtube.md` — audio 출처(YouTube extract).
- 이슈 #162, PR #163.
- 사용자 결정 2026-05-21 (plan 세션).
