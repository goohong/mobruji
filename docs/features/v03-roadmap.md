---
feature: v0.3 P1~P3 로드맵 (백로그 우선순위 정리)
slug: v03-roadmap
status: draft
owner: @goohong
scope: infra
related_issues: [69, 68, 71, 62, 209, 238, 242, 243]
related_prs: [246, 255, 264, 283, 315, 324, 892, 939]
last_reviewed: 2026-05-24
---

# v0.3 P1~P3 로드맵 (백로그 우선순위 정리)

## 1) 개요 (What / Why)

- v0.2 P0(추천 v1 + voice-range-input + history fe persist) 까지 머지 완료. v0.3 의 P0 는 admin endpoint 게이트(#229) 와 voice-range-progress / recommendation-history 영속화(#231/#233/#237) 로 닫혔다.
- v0.3 의 잔여 작업과 v0.4 후보가 백로그에 누적된 상태 — 의존성/순서가 정리되지 않아 사이클마다 "다음 뭐 할까" 가 흔들린다. 본 문서는 **현재 백로그를 P1~P3 로 묶고 의존성/예상 분량/오너 트랙(be/fe/plan/infra) 을 한 장으로 정리**하는 living 명세서다.
- v0.4 (계정 시스템 등) 는 본 문서 끝의 §5 "Beyond v0.3" 에 메모만 둔다. 정식 spec 은 v0.3 완료 시점에 분리한다.

## 2) v0.3 우선순위 매트릭스

> P1 = 다음 1~2 사이클 안에 끝낸다 / P2 = v0.3 안에 끝낸다 / P3 = v0.3 후반 또는 v0.4 로 미룰 수 있다.

| Priority | 이슈 | 제목 | 트랙 | 분량 | 의존 |
|---|---|---|---|---|---|
| **P1** | [#238](https://github.com/goohong/mobruji/issues/238) | history endpoints session-bound 인증 | be | 1 PR (S~M) | ADR-0011 (본 사이클 머지) |
| **P1** | [#209](https://github.com/goohong/mobruji/issues/209) | sessionId TTL/회전 정책 spec + librosa CI 모니터링 | plan + infra | spec 1 PR + 운영 1 PR | — (단, #238 결정에 영향) |
| **P2** | [#62](https://github.com/goohong/mobruji/issues/62) | recommendation p95 응답시간 회귀 가드 | be (test) | 1 PR (M) | 추천 v1/v2 코드 안정화 (완료) |
| **P2** | [#71](https://github.com/goohong/mobruji/issues/71) | 수기 음역대 큐레이션 30 → 100곡 | song 큐레이션 + plan | 콘텐츠 작업 (다회 PR) | 사용자 검토 필요 |
| **P2** | [#68](https://github.com/goohong/mobruji/issues/68) | MusicBrainz 통합 — 메타 보강 + ISRC | be | 2 PR (M+M) | spec `song-metadata-source` (머지 완료) |
| **P2** | [#69](https://github.com/goohong/mobruji/issues/69) | Spotify Audio Features 통합 + 추천 w4 활성화 | be | 2 PR (M+M) | **#68 선행** (Spotify ID 매칭에 MB ISRC 활용) |
| **P2 (신규)** | [#242](https://github.com/goohong/mobruji/issues/242) | 운영 관측성 베이스라인 — 핵심 카운터/p95 대시보드 | infra | spec 1 PR + 구현 1~2 PR | #62 와 부분 중복 검토 |
| **P2 (신규)** | [`deployment-infrastructure.md`](deployment-infrastructure.md) | 운영 배포 인프라 1차 셋업 (Hetzner CX22 + Vercel + Cloudflare) | infra | spec 1 PR (A) + 구현 6 PR (B~G) | ADR-0012 (관측성), ADR-0013 (TTL) 머지 완료 |
| **P3** | [#70](https://github.com/goohong/mobruji/issues/70) | TJ/금영 노래방 곡번호 ADR (저작권 검토) | plan (ADR) | ADR 1개 + 결정 | 사용자/법무 판단 (wontfix 라벨 — v0.4 로 이동) |
| **P3** | [#226](https://github.com/goohong/mobruji/issues/226) | AudioAnalysisScheduledBackfill selective query 갱신 | be | 1 PR (S) | 머지된 #235 후속 |
| **P3** | [#207](https://github.com/goohong/mobruji/issues/207) | AudioAnalysisRunner Python venv 친화 에러 + @MockBean→@MockitoBean | be | 1 PR (S) | — |
| **P3** | [#240](https://github.com/goohong/mobruji/issues/240) | PR 본문 AS-IS/TO-BE 자동 채움 또는 머지 전 강제 | infra | 1 PR (S) | — |
| **P3 (신규)** | [#243](https://github.com/goohong/mobruji/issues/243) | 사용자 계정/로그인 (v0.4 spec) | plan + be + fe | spec 우선 | sessionId TTL 결정(#209) 선행 |

> 회귀/리뷰 후속(#172/#173/#56 등) 은 본 매트릭스 외 "리뷰 부채" 로 분류, 각 트랙이 사이클 짬에 처리. 우선순위 부여 대상이 아님.

## 3) P1 작업 순서

1. **#238 session-bound 인증 게이트 (be)** — ADR-0011 머지(plan 27) 이후 be 가 `SessionAuthGuard` 1 PR 로 voice-range-history + recommendation-history + like/bookmark endpoint 일괄 게이트. `AdminTokenVerifier` 와 동일 패턴이라 분량 S~M.
2. **#209-A sessionId TTL/회전 spec (plan)** — `docs/features/anonymous-session-lifecycle.md` 신설. TTL/회전 정책 + 만료 후 데이터 처리(cascade vs anonymize) + #238 게이트 통과 만료 sessionId 거부 로직 합의.
3. **#209-B librosa CI 빌드 시간 모니터링 (infra)** — 별 PR 1개. 임계치 초과 시 fail 또는 알림. 분량 S.

## 4) P2 묶음 — 의존성 그래프

```
[P2 묶음]

  #62 p95 회귀 가드 (독립, 언제든 가능)
       │
       └─→ 운영 관측성 베이스라인 (신규 P2)
             ├─ 핵심 카운터 metric 통합 (like.created, recommendation.requested, song.audio.backfill.* 등)
             └─ p95 대시보드 (Prometheus + Grafana? 또는 SaaS — ADR 후보)

  #71 수기 큐레이션 30 → 100 (독립, 콘텐츠 작업 — 사이클마다 +10곡 식)
       └─ 후속: 100곡 시드 안정화 후 추천 신호 검증

  #68 MusicBrainz 통합 — 메타 보강 batch + ISRC
       └─→ #69 Spotify Audio Features (Spotify ID 매칭에 MB ISRC 활용)
             └─ 추천 v2 (#219 머지 완료) 의 w4 (popularity) → mood/valence 가중치로 전환
                + 추천 가중치 변경은 ADR 신설 권장 (`0017-recommendation-mood-signal-source.md`)
```

### 4-1) P2 권장 순서

1. **#62 p95 회귀 가드** 먼저 (1 PR, 의존 없음). 이후 신호/추천 변경이 들어와도 회귀를 잡을 안전망 확보.
2. **#71 큐레이션** 은 백그라운드 콘텐츠 트랙으로 사이클마다 점진 진행 (10곡씩 × 7 사이클).
3. **#68 MusicBrainz** 가 #69 Spotify 의 매칭 키(ISRC) 를 채워야 정확도가 높다. 순서 #68 → #69 권장.
4. **#69 Spotify + 추천 w4 활성화** 는 ADR(`0017-recommendation-mood-signal-source.md` — 본 문서가 후속 트리거) 와 같이 묶어 머지.
5. **운영 관측성 베이스라인** 은 위 4 작업 중 어디서든 metric 카운터 신설이 늘어나기 때문에 P2 초~중반에 한번에 인프라 합의(어떤 도구, 어떤 라벨 컨벤션) 를 보고 후속 PR 들이 그 컨벤션을 따르도록 강제.

## 5) Beyond v0.3 (v0.4 후보 — spec 미작성)

- **계정/로그인 시스템** — sessionId TTL(#209) 결정 후 anonymous-session ↔ account 머지 정책 spec 신설. 신규 이슈 등록 예정 (v0.3 막바지).
- **소셜/공유 기능** — `recommendation-history-and-feedback.md §4 Out of Scope` 에 명시된 친구 좋아요/인기곡 랭킹 등.
- **ML 기반 추천 v3** — like/bookmark 시그널 누적(#236 머지) 이후 결정.
- **노래방 곡번호 ADR (#70)** — wontfix 라벨 유지, 사용자 판단 대기.

## 6) 신규 이슈 등록 (본 PR 와 함께)

본 문서의 "P2 (신규)" / "P3 (신규)" 슬롯 중 spec 결정이 필요한 항목을 본 PR 작성 시 함께 등록했다.

- [x] **운영 관측성 베이스라인** → [#242](https://github.com/goohong/mobruji/issues/242)
- [x] **계정/로그인 v0.4 spec** → [#243](https://github.com/goohong/mobruji/issues/243) (placeholder — v0.3 후반에 본격 진행)

## 7) 결정 로그

- **2026-05-22 (plan 27)**: 초안 작성 (status=draft). #69/#68/#71/#62/#209/#238 을 v0.3 P1/P2/P3 로 분류. #69 는 #68 선행 필요(Spotify ID 매칭). 추천 w4 (popularity) 신호를 mood/valence 로 전환하는 결정은 별도 ADR(`0017-recommendation-mood-signal-source.md`) 신설 권장. #70 은 wontfix 유지 → v0.4 로 이동.
- **2026-05-22 (plan 28)**: #242 운영 관측성 베이스라인 spec/ADR 머지. 베이스라인 = 메트릭 네이밍 컨벤션 + 필수 카운터 표 + p95 매트릭스 + 라벨 화이트리스트 + Grafana Cloud Free + Discord webhook 알림. 수집 스택은 ADR-0012 로 분리. 분산 트레이싱/SaaS 유료/SLO/로그 집계/web RUM 은 v0.4 이후. **§4-1 권장 순서 5번 — 운영 관측성 베이스라인 합의 — 완료.** 후속 PR: be 가 `application.yml` percentiles + MeterRegistry 카운터 통일, infra 가 Grafana Cloud 셋업 + 알림 4 규칙. 추천 mood/valence ADR 슬롯(`0017-recommendation-mood-signal-source.md`) 은 ADR-0012 가 점유하므로 **다음 번호 0013 으로 재할당**.
- **2026-05-22 (plan 29)**: (1) ADR-0011 §Decision 에 "적용 범위 (HTTP method 별 매핑 규칙)" 절 추가 — like/bookmark POST/DELETE 가 본 ADR 적용 대상임을 명문화 + `recommendation-history-and-feedback.md §5-2-1` 표 갱신 + 후속 구현 이슈 신설 (PR F 트래커). (2) **#69 Spotify Audio Features 통합 spec 신설** (`spotify-audio-features-integration.md`) — self-analysis pivot 과의 정합성 확정 (`valence`/`energy` 두 차원만, key/tempo 는 self-analysis 가 담당). PR A (엔티티+마이그레이션) → PR B (Client+backfill+관측성) → PR C (추천 산식 + 신규 mood-signal ADR — plan 33 이후 0017 슬롯) 3 단계 분할. 의존: #68 MusicBrainz ISRC backfill 선행. observability counter (`mobruji.external.spotify.*`) 는 이미 `observability-baseline.md §5-3` 표에 등재.
- **2026-05-22 (plan 33)**: **#209-A sessionId TTL/회전 결정 — ADR-0013 머지** (`docs/decisions/0013-sessionid-ttl-rotation.md`) + 구현 가이드 spec 신설 (`docs/features/anonymous-session-lifecycle.md`). 180일 inactive sliding window + cascade-delete default + 사용자 트리거 회전 endpoint. ADR-0011 §Consequences 부정 #2 (만료 sessionId 무한 접근) 해소. voice-range-progress §8 Q1/Q2 + recommendation-history-and-feedback §8 Q5 닫힘. **§3 P1 작업 순서 2번 항목 완료, #209-B (librosa CI 모니터링) 별 PR 분리 처리.** #243 (v0.4 계정 시스템 spec) 의 선행 의존성 해소 — 머지 정책 결정 트리 ADR-0013 §D-4 가 단순화. (3) 추천 mood/valence ADR 슬롯 번호 정정: ADR-0013 이 sessionid-ttl-rotation 으로 점유됨에 따라 추천 mood signal source ADR 은 **0017 슬롯으로 재할당** (ADR-0014 는 multi-agent-worktree-orchestration 이 점유). spotify-audio-features-integration.md / v03-roadmap §4-1 5번 항목의 `0017-recommendation-mood-signal-source.md` 참조는 본 PR (#885) 에서 0017 로 정정.
- **2026-05-22 (plan 38)**: **v0.4 사용자 계정 spec (#243) 의 단일 진실 분기 머지** — `docs/features/anonymous-to-account-conversion.md` 신설 (비회원/회원 흐름 매트릭스 + 전환 트리거 정책 + sessionId→user_id 머지 알고리즘 + 로그인 모달 UX) + ADR-0013 §D-4 보강 7항목 (머지 트리거 시점/비회원 1순위/실패 처리/owner 컬럼 모델/대상 6 테이블/사용 한도 없음). **핵심 정책**: 비회원이 1순위 default, 좋아요/북마크 클릭만 회원 전환 트리거(모달). 9 PR 분할 (A~I). #243 본문은 placeholder 에서 본 spec 으로 분기. §5 Beyond v0.3 의 "계정/로그인 시스템" 항목이 본 spec 으로 해소 — v0.4 진입 시점에 PR A 부터 순차 진행.

- **2026-05-22 (plan 36)**: **v0.3 P2 마지막 묶음 — 운영 배포 인프라 spec + ADR 머지**. `docs/features/deployment-infrastructure.md` 신설 + ADR-0015 (`docs/decisions/0015-hosting-stack.md`: Hetzner CX22 4GB ARM + Vercel Hobby + Cloudflare 1사 통합) 채택. 예산 ≤ €5/월, 운영자 1인 부담 0, v0.4 사용자 가시 진입 인프라. 7 PR 분할 (A=spec+ADR, B=Dockerfile/systemd, C=GHA CD, D=도메인/SSL 런북, E=Caddy, F=무중단 ADR, G=백업/복구). 결정 근거: Oracle/GCP/AWS 카드 인증 차단 이력 (discord-daemon-hosting spec §1 동일) → Hetzner 가 유일한 €5 이하 4GB+ 옵션. 한국 리전 부재 단점은 Cloudflare KR PoP + 트래픽 미미 단계 (v0.3) 에서 허용, v0.4 NRT/Seoul 이관 별 ADR. hosting ADR 은 0015 점유. 추천 mood/valence ADR 은 0017 슬롯으로 예약 (ADR-0014 multi-agent / ADR-0016 maestro-context 가 그 사이 점유). Discord daemon 동거 거부 (별 호스트 유지 — 장애 격리). multi-AZ/managed DB/RUM 모두 v0.4 후보 §5 Beyond v0.3 에 합류.
