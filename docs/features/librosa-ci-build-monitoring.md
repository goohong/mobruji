---
feature: librosa CI 빌드 시간 모니터링 + 캐싱 전략
slug: librosa-ci-build-monitoring
status: draft
owner: @goohong
scope: infra
related_issues: [180, 207, 209, 242]
related_prs: []
last_reviewed: 2026-05-22
---

# librosa CI 빌드 시간 모니터링 + 캐싱 전략

## 1) 개요 (What / Why)

- `tools/audio-analysis/` 의 Python 분석 파이프라인(`audio-tooling-bootstrap.md` 의 구현체) 이 운영 의존성에 **librosa** + 후속 도입 예정 **spleeter/demucs** 같은 무거운 ML/audio 라이브러리를 포함한다. 이들은 numpy/scipy/scikit-learn/TensorFlow 같은 대형 의존성을 끌어와 CI 의존성 설치 시간이 점점 길어진다.
- 현재 backend CI (`backend-ci.yml`) 만 작동 중이고 audio-analysis 가 별 워크플로우 없이 운영자 수동 빌드 위주라 **CI 빌드 시간/캐시 효율을 측정·관측할 채널이 없다**. 이슈 #209-B 가 이 부재를 지적했고, plan 33 이 분리해 본 spec 으로 처리.
- 본 spec 은 (1) audio-analysis Python CI 워크플로우 신설, (2) 빌드 시간 timing 측정 + GitHub Actions artifacts 누적, (3) 임계치 초과 시 Discord webhook 알림, (4) pip cache + wheel cache 최적화 전략, (5) 대안 라이브러리 (essentia, aubio) 평가 트리거 조건을 결정한다.
- 액터: 인프라/오너 — CI 시간 회귀 감지 / be — 새 Python 의존성 추가 시 빌드 시간 영향 측정.

## 2) 사용자 시나리오

- (S1) **신규 의존성 추가 회귀 감지**: be 가 spleeter 2.4.0 을 추가하는 PR 을 올린다 → audio-analysis CI 가 의존성 설치 시간 90s → 320s 로 증가한 것을 timing artifact 로 감지 → Discord webhook 알림 → 리뷰어가 "캐시 hit rate 낮음, wheel 빌드 캐시 추가 필요" 코멘트.
- (S2) **캐시 만료 회복**: pip cache key (lockfile 해시) 가 변하지 않은 PR 인데 GitHub Actions 캐시가 LRU evict 됐다 → 본 PR 의 CI 시간이 cold start 200s 로 튀어 알림 → 인프라 오너가 다음 PR 머지 후 cache warm-up 워크플로우 trigger.
- (S3) **대안 라이브러리 평가 트리거**: librosa wheel 빌드 + 의존성 합이 400s 를 3주 연속 초과 → §5-5 트리거 조건 충족 → 별도 ADR `0019-audio-analysis-library-alternatives` (가칭) 신설하여 essentia/aubio 와 비교 실험.
- (S4) **사용자 부재 시 자동 백그라운드**: maestro/be 가 다른 작업 진행 중에도 librosa CI 메트릭이 매 PR 자동 수집되고 회귀 시 Discord 알림이 와 사용자 호출 없이 사이클 흐름이 유지됨.

## 3) 요구사항

### 기능 요구사항

- [ ] **audio-analysis-ci.yml 워크플로우 신설** (§5-1): `tools/audio-analysis/**` 변경 PR 에서 트리거. 의존성 설치 + pytest + (옵션) docker 이미지 빌드 단계.
- [ ] **빌드 시간 timing 측정** (§5-2): 각 step 의 elapsed time 을 JSON artifact 로 누적. metric: `audio_analysis_ci.dependency_install_seconds`, `audio_analysis_ci.pytest_seconds`, `audio_analysis_ci.docker_build_seconds`, `audio_analysis_ci.total_seconds`.
- [ ] **임계치 초과 시 Discord webhook 알림** (§5-3): 단일 PR 의 `total_seconds` 가 §5-4 임계치 초과 시 즉시 알림. 3 PR 연속 초과는 P1.
- [ ] **pip cache 최적화** (§5-5): `actions/setup-python` 의 `cache: pip` + `cache-dependency-path: tools/audio-analysis/requirements.txt` 활성화. cache key = lockfile (`requirements.txt`) 해시.
- [ ] **wheel build cache** (§5-5): `numpy`/`scipy` 같은 native wheel 의 사전 빌드 캐시 (`~/.cache/pip/wheels`). cache key 에 OS + Python 버전 + lockfile 해시.
- [ ] **timing artifact 누적 + 시계열 조회** (§5-6): `audio-analysis-ci-timing-history` artifact 에 PR 번호 + 커밋 SHA + timing JSON 을 누적. 사용자가 GitHub UI 또는 `gh run download` 로 30일 retention 내 조회 가능.
- [ ] **대안 라이브러리 평가 ADR 트리거 조건** (§5-7): 3주(또는 N 사이클) 연속 임계 초과 시 자동 이슈 등록 (`infra-audio-analysis-library-eval`).

### 비기능 요구사항

- **결정성**: timing 측정은 runner 환경 (ubuntu-22.04) 단일 고정. runner 변경 시 baseline 갱신.
- **응답시간 영향**: timing 측정 자체 오버헤드는 step 당 +0.5s 이내 (`time` 명령 또는 `actions/timer` 활용).
- **설정 외부화**: 임계치 (`MOBRUJI_AUDIO_CI_TOTAL_THRESHOLD_SEC`, default 300), Discord webhook URL (`MOBRUJI_ALERT_WEBHOOK_URL` — observability-baseline 과 공유) 모두 GitHub Actions secrets 또는 repository variables.
- **관측성**: CI 메트릭은 GitHub Actions artifacts + Discord 알림으로 표현. Grafana Cloud 로 push 는 별 spec (observability-baseline 의 Phase 2 후보).
- **보안**: webhook URL 은 secrets, timing JSON 에 PII/secret 미포함 (PR 번호/커밋 SHA/타이밍만).

## 4) 범위 / 비범위

### 포함

- audio-analysis Python CI 워크플로우 신설 (`audio-analysis-ci.yml`)
- 빌드 시간 timing artifact + 임계치 알림
- pip + wheel cache 최적화
- timing 시계열 조회 가이드 (`docs/ai-harness/10-observability.md §11` 추가)
- 대안 라이브러리 평가 ADR 트리거 조건 (조건만 — 실제 평가는 별 ADR)

### 제외 (Out of Scope)

- **대안 라이브러리(essentia/aubio) 비교 실험 자체**: §5-7 트리거 조건 충족 시 별 ADR (`0019-audio-analysis-library-alternatives`) 와 별 spec 으로 분리. 본 spec 은 트리거 조건만.
- **Grafana Cloud 로 CI 메트릭 push**: observability-baseline 의 Phase 2 후보. 현 spec 은 GitHub Actions artifacts + Discord 알림만.
- **운영 환경 audio analysis 메트릭**: `mobruji.song.audio.analysis.duration` 은 observability-baseline §5-3 표가 단일 진실 (운영 메트릭). 본 spec 은 **CI 메트릭** 전용.
- **AudioAnalysisRunner @MockBean → @MockitoBean (#207)**: 별 이슈, 별 PR. 본 spec 범위 외.
- **운영 audio backfill 스케줄러 (`song.audio.backfill.*`, #226/#235)**: 운영 메트릭, observability-baseline 관할.

## 5) 설계

### 5-1) audio-analysis-ci.yml 워크플로우

```yaml
name: Audio Analysis CI - mobruji

on:
  pull_request:
    branches: [ "develop", "main" ]
    types: [ opened, synchronize, reopened ]
    paths:
      - 'tools/audio-analysis/**'
      - '.github/workflows/audio-analysis-ci.yml'

permissions:
  contents: read
  pull-requests: write

concurrency:
  group: ci-audio-analysis-${{ github.ref }}
  cancel-in-progress: true

jobs:
  build-test-time:
    runs-on: ubuntu-22.04
    defaults:
      run:
        working-directory: tools/audio-analysis
    steps:
      - uses: actions/checkout@v4

      - name: Set up Python 3.11
        id: setup-python
        uses: actions/setup-python@v5
        with:
          python-version: '3.11'
          cache: 'pip'
          cache-dependency-path: tools/audio-analysis/requirements.txt

      - name: Install ffmpeg (system dep for yt-dlp)
        run: sudo apt-get update -qq && sudo apt-get install -y -qq ffmpeg

      - name: Restore pip wheel cache
        id: wheel-cache
        uses: actions/cache@v4
        with:
          path: ~/.cache/pip/wheels
          key: pip-wheels-${{ runner.os }}-py3.11-${{ hashFiles('tools/audio-analysis/requirements.txt') }}
          restore-keys: |
            pip-wheels-${{ runner.os }}-py3.11-

      - name: Install dependencies (timed)
        run: |
          START=$(date +%s)
          pip install --upgrade pip
          pip install -r requirements.txt
          END=$(date +%s)
          echo "dependency_install_seconds=$((END - START))" >> $GITHUB_ENV

      - name: Run pytest (timed)
        run: |
          START=$(date +%s)
          pytest -q
          END=$(date +%s)
          echo "pytest_seconds=$((END - START))" >> $GITHUB_ENV

      - name: Build Docker image (timed)
        run: |
          START=$(date +%s)
          docker build -t mobruji/audio-analysis:ci .
          END=$(date +%s)
          echo "docker_build_seconds=$((END - START))" >> $GITHUB_ENV

      - name: Compose timing JSON
        run: |
          TOTAL=$((${dependency_install_seconds:-0} + ${pytest_seconds:-0} + ${docker_build_seconds:-0}))
          cat > timing.json <<EOF
          {
            "pr_number": "${{ github.event.pull_request.number }}",
            "commit_sha": "${{ github.sha }}",
            "dependency_install_seconds": ${dependency_install_seconds:-0},
            "pytest_seconds": ${pytest_seconds:-0},
            "docker_build_seconds": ${docker_build_seconds:-0},
            "total_seconds": ${TOTAL},
            "cache_hit_wheels": "${{ steps.wheel-cache.outputs.cache-hit }}",
            "cache_hit_pip": "${{ steps.setup-python.outputs.cache-hit }}",
            "runner_os": "${{ runner.os }}",
            "python_version": "3.11",
            "recorded_at": "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
          }
          EOF
          cat timing.json

      - name: Upload timing artifact
        uses: actions/upload-artifact@v4
        with:
          name: audio-analysis-ci-timing-${{ github.run_id }}
          path: tools/audio-analysis/timing.json
          retention-days: 30

      - name: Alert on threshold breach
        if: env.total_seconds && env.total_seconds > vars.MOBRUJI_AUDIO_CI_TOTAL_THRESHOLD_SEC
        env:
          WEBHOOK_URL: ${{ secrets.MOBRUJI_ALERT_WEBHOOK_URL }}
        run: |
          PAYLOAD=$(jq -n \
            --arg pr "${{ github.event.pull_request.number }}" \
            --arg total "${total_seconds}" \
            --arg threshold "${{ vars.MOBRUJI_AUDIO_CI_TOTAL_THRESHOLD_SEC }}" \
            --arg url "${{ github.event.pull_request.html_url }}" \
            '{content: "audio-analysis CI threshold breach — PR #\($pr): total=\($total)s (threshold=\($threshold)s) \($url)"}')
          curl -fsSL -X POST -H "Content-Type: application/json" -d "$PAYLOAD" "$WEBHOOK_URL" || true
```

### 5-2) 측정 메트릭

| Metric | 단위 | 의미 |
|---|---|---|
| `dependency_install_seconds` | 초 | `pip install -r requirements.txt` 소요 |
| `pytest_seconds` | 초 | `pytest -q` 소요 |
| `docker_build_seconds` | 초 | `docker build` 소요 (옵션 — Dockerfile 변경 시만) |
| `total_seconds` | 초 | 위 3개 합 |
| `cache_hit_pip` | bool | `actions/setup-python` 의 pip 캐시 히트 |
| `cache_hit_wheels` | bool | wheel 빌드 캐시 히트 |

### 5-3) 알림 정책

| 규칙 | 트리거 | 채널 | 우선순위 |
|---|---|---|---|
| 단일 PR 임계 초과 | `total_seconds > MOBRUJI_AUDIO_CI_TOTAL_THRESHOLD_SEC` (default 300s) | Discord webhook (`MOBRUJI_ALERT_WEBHOOK_URL`) | P2 |
| 3 PR 연속 임계 초과 | 직전 3 PR 모두 위 조건 충족 | Discord webhook + `infra-audio-analysis-library-eval` 이슈 자동 등록 | P1 |
| pip cache miss | `cache_hit_pip == false` AND `requirements.txt` 미변경 PR | Discord webhook | P3 (정보성) |

- 1번/3번 규칙은 워크플로우 step 안에서 즉시 평가.
- 2번 규칙은 본 spec PR 2 에서 별 워크플로우 (`audio-analysis-ci-trend.yml` — 매일 1회 timing artifact 30일치 조회 후 추세 계산) 로 처리. 본 spec 의 PR 1 범위 외.

### 5-4) 임계치 default 값 근거

- **300s** = 5분 — 현재 audio-analysis tooling 의 requirements 가 librosa + numpy + soundfile + pytest 만이고 cold-start 가 약 90~120s 로 측정됨 (수동, plan 33 시점). spleeter 도입 시 220~280s 추정. 5분이면 약 +20% 여유 — spleeter 도입 후 점진적 회귀를 감지하는 1차 임계.
- 임계치 조정은 GitHub `vars.MOBRUJI_AUDIO_CI_TOTAL_THRESHOLD_SEC` 1개 값만 변경 (워크플로우/spec 변경 불요).
- 운영 데이터 누적 후 (4주) p95 + 1.5σ 로 자동 갱신하는 별 워크플로우 고려 — v0.4 후보.

### 5-5) pip cache + wheel cache 최적화

- **pip cache** (level 1): `actions/setup-python@v5` 의 `cache: 'pip'`. cache key = lockfile (`requirements.txt`) 해시. 동일 lockfile PR 은 install 가속.
- **wheel cache** (level 2): `~/.cache/pip/wheels` 를 별도 `actions/cache@v4` 로 보존. 대형 native wheel (`numpy`, `scipy`, `numba`) 의 컴파일 결과를 캐시. cache key = OS + Python 버전 + lockfile 해시.
- **GitHub Actions cache 한도**: repo 당 10GB. wheel cache 가 ~500MB 이내 권장 — librosa 의존성 합이 약 200MB 라 충분.
- **cache evict 회복**: cache miss 가 잦으면 (§5-3 P3 규칙) cache warm-up 워크플로우 (`audio-analysis-cache-warm.yml`) 를 cron 으로 매일 1회 trigger 하여 evict 방지. 본 spec PR 2 후보.

### 5-6) timing artifact 조회 가이드

```bash
# 최근 PR 의 timing JSON 1개 조회
gh run list --workflow=audio-analysis-ci.yml --limit 5
gh run download <run-id> --name audio-analysis-ci-timing-<run-id>
cat timing.json | jq

# 30일치 추세 (별 워크플로우 PR 2 에서 자동 집계)
gh run list --workflow=audio-analysis-ci-trend.yml --limit 1
gh run download <run-id> --name trend-summary
cat trend.json | jq '.total_seconds_p95'
```

- `docs/ai-harness/10-observability.md` 에 §11 "CI 빌드 timing (audio-analysis)" 추가 — 본 spec 후속 PR 에서 룰 문서 갱신.

### 5-7) 대안 라이브러리 평가 ADR 트리거 조건

본 spec 은 평가 자체는 하지 않고 **트리거 조건만 정의**한다 — 정량 조건이 충족되면 별 ADR `docs/decisions/0019-audio-analysis-library-alternatives.md` 신설:

- (조건 A) `total_seconds` 3주 (또는 9 PR) 연속 임계 초과
- (조건 B) wheel cache hit 율이 1개월 평균 < 30% (cache 효과 미미)
- (조건 C) spleeter / demucs 도입 PR 에서 의존성 설치 단독 > 5분
- (조건 D) 운영 (operational) `mobruji.song.audio.analysis.duration` p95 > 60s (observability-baseline §5-3 — 운영 측정에서 librosa 의 처리 속도 자체 회귀)

위 4 조건 중 1개 충족 시 자동 이슈 등록 (`infra-audio-analysis-library-eval`). 이슈는 별 ADR 의 input.

대안 후보 (ADR 신설 시 비교 차원):

| 후보 | 강점 | 약점 |
|---|---|---|
| **librosa** (현행) | Python 생태계 표준, pyin/chroma 검증, 자료 풍부 | numpy/scipy/numba 의존 → 빌드 시간 |
| **essentia** | C++ core + 가벼움 + 빠름 + Spotify 일부 사용 | 설치 복잡 (apt 의존), Python binding 빌드 까다로움, 한국어 자료 적음 |
| **aubio** | C 기반 가벼움, pitch detection 단순 | feature 적음 (chroma/key 부족), 정확도 trade-off |
| **torchaudio + pretrained pitch** | PyTorch 생태계 호환 — ML 추천 v3 와 통합 가능 | PyTorch 자체가 무거움 (~1GB) → CI 시간 회귀 더 심각, 도입 의도와 모순 |

본 spec 머지 시점 default = librosa 유지. 트리거 충족 후 별 ADR 에서 비교 실험.

### 5-8) 보호 영역 라벨링

- 본 spec 의 PR 2 (`audio-analysis-ci.yml` 신설) 는 CLAUDE.md §4 의 보호 영역 (`.github/workflows/**`) 변경 → `needs-human-review` 라벨 강제.
- 본 spec(PR 1) 자체는 docs 만이라 보호 영역 미해당.

## 6) 작업 분할 (예상 PR 리스트)

- [ ] **PR 1 (현 PR, plan 33)**: 본 spec(`docs/features/librosa-ci-build-monitoring.md`) + observability-baseline.md cross-reference (운영 vs CI 메트릭 분리 표기). **본 PR**.
- [ ] **PR 2 (infra)**: `audio-analysis-ci.yml` 워크플로우 신설 + GitHub `vars.MOBRUJI_AUDIO_CI_TOTAL_THRESHOLD_SEC` 등록 + `MOBRUJI_ALERT_WEBHOOK_URL` secret 재사용. 보호 영역(`.github/workflows/**`) → `needs-human-review` 라벨.
- [ ] **PR 3 (infra)**: 추세 계산 워크플로우 (`audio-analysis-ci-trend.yml`, 매일 1회 cron) + 3 PR 연속 초과 시 자동 이슈 등록 로직. 보호 영역 라벨 동일.
- [ ] **PR 4 (infra)**: cache warm-up 워크플로우 (`audio-analysis-cache-warm.yml`, 매일 1회 cron). cache evict 빈도 측정 후 필요 시 도입 — 옵션, default off.
- [ ] **PR 5 (docs)**: `docs/ai-harness/10-observability.md` §11 "CI 빌드 timing (audio-analysis)" 절 추가 — 조회 가이드 + 알림 룰 요약.
- [ ] **PR 6 (조건부)**: §5-7 트리거 조건 충족 시 ADR-0019 (`0019-audio-analysis-library-alternatives.md`) + 별 spec. (ADR-0015 는 hosting-stack 점유)

## 7) 테스트 전략

- **워크플로우 dry-run**: PR 2 머지 전 fork 또는 별 브랜치에서 `act` 로 로컬 실행 또는 `pull_request` 트리거로 검증.
- **timing 정확성**: 동일 PR 을 2회 push → cache hit 인 두 번째 실행이 첫 번째보다 의미 있게 빠른지 확인.
- **알림 발화**: PR 2 머지 후 인위적으로 `MOBRUJI_AUDIO_CI_TOTAL_THRESHOLD_SEC=10` 으로 낮춰 1회 알림 발화 확인 → 원복.
- **artifact 누적**: 5 PR 머지 후 `gh run list` 로 timing artifact 5건 모두 30일 retention 내 조회 가능 확인.

## 8) 오픈 질문

| # | 질문 | 선택지 | 담당/기한 |
|---|---|---|---|
| Q1 | docker build step 을 CI 에 항상 포함? | (a) 항상 — timing 측정 일관성 / (b) `Dockerfile` 변경 PR 만 — CI 시간 절감 | @goohong / PR 2 |
| Q2 | wheel cache 크기 한도 | (a) 500MB / (b) 1GB / (c) 무제한 (GitHub 10GB 한도까지) | @goohong / PR 2 |
| Q3 | 추세 워크플로우의 trigger | (a) cron 매일 1회 / (b) workflow_dispatch 만 (수동) / (c) PR 머지마다 후속 trigger | @goohong / PR 3 |
| Q4 | 대안 라이브러리 평가 ADR 트리거의 §5-7 조건 D (운영 메트릭 회귀) 의 임계 | (a) 60s p95 (현 spec) / (b) 30s p95 / (c) 별 ADR 에서 결정 | @goohong / PR 6 진입 시 |
| Q5 | 임계 초과 알림을 fe `web/` CI 와 통합? | (a) audio-analysis 별 알림 (현 spec) / (b) `MOBRUJI_CI_ALERT_WEBHOOK_URL` 공용 | @goohong / PR 2 |

## 9) 결정 로그

- **2026-05-22 (plan 33, 본 PR)**: 초안 작성 (status=draft). 이슈 #209 의 B 항목 (librosa CI 빌드 시간 모니터링) 을 sessionId TTL (A) 와 분리해 별 spec 으로 처리. timing 측정 = GitHub Actions step 내 `date +%s` + JSON artifact (30일 retention), 임계치 default 300s, pip + wheel 2단 캐시, 대안 라이브러리 ADR 트리거 조건 4개 정의 (조건 충족 시 자동 이슈 등록). 운영 메트릭은 observability-baseline §5-3 `mobruji.song.audio.analysis.duration` 가 단일 진실 — 본 spec 은 CI 메트릭 전용으로 명시 분리.
