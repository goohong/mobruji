// k6 부하 테스트 — 추천 API (POST /api/v1/recommendations)
//
// 목적: spec `docs/features/recommendation-algorithm-v1.md` §3 비기능 "p95 200ms"의 자동 회귀 가드.
// QA 단발 측정(n=30~50)은 분산이 커서 회귀 감지에 실패한 이력이 있어(rev 사이클 6 80.9ms 미감지),
// 동시 VU + 통계적 임계로 안정적 측정한다.
//
// 시나리오:
//   - VU 10, duration 1m (10s warm-up + 50s steady)
//   - setup: VU 수만큼 voice-range 사전 등록 (POST /api/v1/voice-ranges)
//   - default(loop):
//       * VU별 sessionId 재사용 + voiceRangeLow/High 다양화 + excludeSongIds 변주
//       * Audio Features 미도입 상태이므로 voiceRange 폭/오프셋과 mood 조합으로 일반화
//
// 임계 (k6 thresholds):
//   - http_req_duration{endpoint:recommendation}: p(95) < 200, p(99) < 400
//   - http_req_failed: rate < 0.01
//   - checks: rate > 0.99
//
// 환경변수:
//   - BASE_URL (default http://localhost:8080)
//   - VUS, DURATION, WARMUP — 시나리오 튜닝
//   - K6_OUT_SUMMARY (default summary.json) — workflow 파싱용
//
// 로컬 실행: k6 run scripts/load/recommendation.k6.js

import http from 'k6/http';
import { check, sleep } from 'k6';
import { SharedArray } from 'k6/data';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const VUS = parseInt(__ENV.VUS || '10', 10);
const DURATION = __ENV.DURATION || '60s';
const WARMUP = __ENV.WARMUP || '10s';

// UUIDv4 생성 헬퍼 (#948).
// VoiceRangeCreateRequest.sessionId @Pattern(SessionIdPatterns.UUID_V4) 강제 — 비-UUIDv4 입력은 400.
// k6 setup() 의 POST /api/v1/voice-ranges 가 본 헬퍼로 sessionId 를 발급해야 시나리오가 통과한다.
// SessionRotateRequest 와 동일 형식. crypto.randomUUID 미지원 환경(k6) 대응을 위해 Math.random 기반 생성.
function uuidV4() {
    const bytes = new Array(16);
    for (let i = 0; i < 16; i++) {
        bytes[i] = Math.floor(Math.random() * 256);
    }
    // RFC 4122 §4.4 — version(0100) + variant(10xx) nibble 셋팅
    bytes[6] = (bytes[6] & 0x0f) | 0x40;
    bytes[8] = (bytes[8] & 0x3f) | 0x80;
    const hex = bytes.map((b) => b.toString(16).padStart(2, '0')).join('');
    return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20, 32)}`;
}

const MOODS = ['UPBEAT', 'CALM', 'EMOTIONAL', 'POWERFUL', 'GROOVY', 'NOSTALGIC', null];

// preferredBpm 변주 — v2(#218) tempoMatch 회귀 가드(#274).
// 일정 확률(BPM_INJECTION_RATE)로 [BPM_MIN, BPM_MAX] 임의 정수 주입, 나머지는 미주입(mood default BPM 경로 유지).
// 범위 60~200은 RecommendationCreateRequest @Min(30) @Max(300) 안쪽이며 일반 가요 템포 분포에 정렬.
const BPM_INJECTION_RATE = parseFloat(__ENV.BPM_INJECTION_RATE || '0.5');
const BPM_MIN = parseInt(__ENV.BPM_MIN || '60', 10);
const BPM_MAX = parseInt(__ENV.BPM_MAX || '200', 10);

function randomPreferredBpm() {
    if (Math.random() >= BPM_INJECTION_RATE) {
        return null;
    }
    return Math.floor(Math.random() * (BPM_MAX - BPM_MIN + 1)) + BPM_MIN;
}

// 음역대 변주 — MIDI 12~119 범위 내에서 일반적인 가창 음역(C3~C5 부근) 중심.
// 폭 12~24 반음, low 48~64에서 추첨.
const VOICE_RANGE_VARIANTS = new SharedArray('voiceRangeVariants', function () {
    const variants = [];
    for (let low = 48; low <= 64; low += 2) {
        for (let width = 12; width <= 24; width += 4) {
            variants.push({ low, high: low + width });
        }
    }
    return variants;
});

// 추천에서 제외할 songId 후보. 실제 시드 ID에 의존하지 않고
// 1..30 범위로 임의 선택 — 미존재 ID여도 application에서 null-safe로 처리됨.
function randomExcludeSongIds() {
    const count = Math.floor(Math.random() * 4); // 0~3개
    const ids = new Set();
    while (ids.size < count) {
        ids.add(Math.floor(Math.random() * 30) + 1);
    }
    return Array.from(ids);
}

export const options = {
    scenarios: {
        recommendation_load: {
            executor: 'ramping-vus',
            startVUs: 0,
            stages: [
                { duration: WARMUP, target: VUS },
                { duration: DURATION, target: VUS },
                { duration: '5s', target: 0 },
            ],
            gracefulRampDown: '5s',
            exec: 'recommendationFlow',
        },
    },
    thresholds: {
        // spec p95 200ms 회귀 가드
        'http_req_duration{endpoint:recommendation}': ['p(95)<200', 'p(99)<400'],
        // 전체 요청 에러율 — voice-range setup 포함 1% 이하
        http_req_failed: ['rate<0.01'],
        // assertion 실패율 — 정상 응답 형식 보존
        checks: ['rate>0.99'],
    },
    summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(50)', 'p(90)', 'p(95)', 'p(99)'],
};

// setup: VU별 voice-range 등록 후 sessionId 배열 반환
export function setup() {
    const sessions = [];
    for (let i = 0; i < VUS; i++) {
        // #948: VoiceRangeCreateRequest.sessionId @Pattern(UUIDv4) — UUIDv4 생성 필수.
        // 이전 `k6-load-${Date.now()}-${i}` 패턴은 400 (UUIDv4 format required) 회귀.
        const sessionId = uuidV4();
        const variant = VOICE_RANGE_VARIANTS[i % VOICE_RANGE_VARIANTS.length];
        const res = http.post(
            `${BASE_URL}/api/v1/voice-ranges`,
            JSON.stringify({
                sessionId,
                lowestNoteMidi: variant.low,
                highestNoteMidi: variant.high,
                sourceMethod: 'OCTAVE_PICK',
            }),
            {
                // SessionAuthGuard(#924, F4) — POST /api/v1/voice-ranges 는 body sessionId
                // vs X-Session-Id 헤더 상수시간 일치 검증. 헤더 누락 시 401.
                // bootstrap 옵션 (a): anonymous_session 행 미존재여도 통과 → 별도 세션 생성
                // endpoint 불필요. SessionActivityTracker 가 lazy 등록 (spec §5-2, §5-5-1).
                headers: { 'Content-Type': 'application/json', 'X-Session-Id': sessionId },
                tags: { endpoint: 'voice_range_setup' },
            },
        );
        if (res.status !== 201) {
            // setup 실패는 본 측정 무의미 → 즉시 abort
            throw new Error(`voice-range setup 실패 status=${res.status} body=${res.body}`);
        }
        sessions.push(sessionId);
    }
    return { sessions };
}

export function recommendationFlow(data) {
    const vuIndex = (__VU - 1) % data.sessions.length;
    const sessionId = data.sessions[vuIndex];
    const variant = VOICE_RANGE_VARIANTS[Math.floor(Math.random() * VOICE_RANGE_VARIANTS.length)];
    const mood = MOODS[Math.floor(Math.random() * MOODS.length)];

    const payload = {
        sessionId,
        voiceRangeLow: variant.low,
        voiceRangeHigh: variant.high,
        excludeSongIds: randomExcludeSongIds(),
    };
    if (mood !== null) {
        payload.mood = mood;
    }
    const preferredBpm = randomPreferredBpm();
    if (preferredBpm !== null) {
        payload.preferredBpm = preferredBpm;
    }

    const res = http.post(`${BASE_URL}/api/v1/recommendations`, JSON.stringify(payload), {
        // POST /api/v1/recommendations 자체는 현재(2026-05-24) SessionAuthGuard 미적용이지만,
        // session-bound endpoint 동등 처리를 위해 헤더를 함께 전송 (가드 확장 시 회귀 방지).
        headers: { 'Content-Type': 'application/json', 'X-Session-Id': sessionId },
        tags: { endpoint: 'recommendation' },
    });

    check(res, {
        'status is 201': (r) => r.status === 201,
        'has requestId': (r) => {
            try {
                return r.json('requestId') !== null && r.json('requestId') !== undefined;
            } catch (e) {
                return false;
            }
        },
        'has recommendations array': (r) => {
            try {
                const recs = r.json('recommendations');
                return Array.isArray(recs);
            } catch (e) {
                return false;
            }
        },
    });

    // VU당 1~2 req/sec 페이싱 — 무한 burst 방지, 측정 안정화
    sleep(0.5 + Math.random() * 0.5);
}

// 워크플로우에서 파싱하기 좋은 JSON 요약 별도 출력.
// stdout 텍스트 요약은 그대로 두고, summary.json만 추가 emit.
export function handleSummary(data) {
    const summaryPath = __ENV.K6_OUT_SUMMARY || 'summary.json';
    return {
        stdout: textSummary(data),
        [summaryPath]: JSON.stringify(data, null, 2),
    };
}

// k6 내장 text summary 의존 회피 — 단순 stdout 텍스트 직접 구성.
function textSummary(data) {
    const lines = [];
    lines.push('');
    lines.push('=== k6 추천 API 부하 테스트 요약 ===');
    const recDuration = data.metrics['http_req_duration{endpoint:recommendation}'];
    if (recDuration && recDuration.values) {
        const v = recDuration.values;
        lines.push(`[recommendation] p50=${fmt(v['p(50)'])}ms p95=${fmt(v['p(95)'])}ms p99=${fmt(v['p(99)'])}ms avg=${fmt(v.avg)}ms max=${fmt(v.max)}ms`);
    }
    const reqs = data.metrics.http_reqs;
    const failed = data.metrics.http_req_failed;
    if (reqs && reqs.values) {
        lines.push(`[throughput] total=${reqs.values.count} rps=${fmt(reqs.values.rate)}`);
    }
    if (failed && failed.values) {
        lines.push(`[errors] rate=${(failed.values.rate * 100).toFixed(2)}%`);
    }
    lines.push('');
    return lines.join('\n');
}

function fmt(n) {
    if (n === undefined || n === null || Number.isNaN(n)) return 'n/a';
    return Number(n).toFixed(2);
}
