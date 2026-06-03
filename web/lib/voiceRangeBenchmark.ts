/**
 * 음역 벤치마크 + 상대 음역 설명 헬퍼 (voice-range-intuitive-display.md §5-1).
 *
 * 배경:
 *   - 음역대를 `C4`/`A#3` 같은 과학적 음표 표기(SPN)로만 노출하면 비전문 사용자가
 *     "내가 어디까지 부르는가" 를 직관적으로 알기 어렵다.
 *   - 본 헬퍼는 사용자 음역대(low/high MIDI)를 평균 벤치마크와 비교해
 *     "고음이 평균보다 약간 더 올라가요" 같은 한국어 상대 설명을 생성한다.
 *
 * 설계:
 *   - 난이도 mirror 패턴(`difficulty.ts` ↔ `Song.deriveDifficulty`)을 차용한다.
 *     본 spec §5-1 표가 단일 출처(SoT)이고, 후속 BE 분류기가 생기면 파리티 테스트로
 *     동일 규칙을 유지한다 (voice-range-intuitive-display.md §5-7).
 *   - 성별 중립 기본: 현재 성별 미수집(§8 Q1) → 일반 성인 벤치마크를 기본으로 쓴다.
 *     성별 신호가 확보되면 male/female 벤치마크로 분기.
 *   - 벤치마크 시드값은 정밀 통계가 아니라 상대 설명의 기준선 용도 — 튜닝 대상(§8 Q3).
 *
 * 비유한(NaN/Infinity) MIDI 입력은 `notes.ts` 의 `INVALID_MIDI_A11Y_FALLBACK` 와 동일
 * 철학으로 안전 fallback 을 반환한다 (의미 없는 표시 차단).
 */

import { INVALID_MIDI_A11Y_FALLBACK } from "@/lib/notes";

export type VoiceRangeBenchmark = {
  readonly lowMidi: number;
  readonly highMidi: number;
  readonly spanSemitones: number;
};

function buildBenchmark(lowMidi: number, highMidi: number): VoiceRangeBenchmark {
  return { lowMidi, highMidi, spanSemitones: highMidi - lowMidi };
}

/**
 * 음역 벤치마크 시드값 (voice-range-intuitive-display.md §5-1, PoC — 튜닝 대상).
 *
 * 미숙련 성인의 *편안한 발성* 기준 근사값. 정밀 통계가 아니라 상대 설명의 기준선.
 *   - 일반 성인(성별 중립, 기본): A2(45) ~ C4(60), span 15
 *   - 남성(선택, 성별 확보 시): G2(43) ~ B3(59), span 16
 *   - 여성(선택, 성별 확보 시): G3(55) ~ D5(74), span 19
 */
export const NEUTRAL_BENCHMARK = buildBenchmark(45, 60);
export const MALE_BENCHMARK = buildBenchmark(43, 59);
export const FEMALE_BENCHMARK = buildBenchmark(55, 74);

/**
 * 성별 신호(선택). 미수집이면 `undefined` → 성별 중립 벤치마크를 사용한다 (§8 Q1).
 */
export type VoiceGender = "MALE" | "FEMALE";

/**
 * 사용할 벤치마크를 선택한다. 성별 미보유(기본)면 성별 중립 벤치마크.
 */
export function selectBenchmark(gender?: VoiceGender): VoiceRangeBenchmark {
  if (gender === "MALE") {
    return MALE_BENCHMARK;
  }
  if (gender === "FEMALE") {
    return FEMALE_BENCHMARK;
  }
  return NEUTRAL_BENCHMARK;
}

/**
 * 차이 버킷 (voice-range-intuitive-display.md §5-1, 반음 단위):
 *   - |Δ| ≤ 2 → "비슷"
 *   - 3 ≤ |Δ| ≤ 5 → "약간"
 *   - |Δ| ≥ 6 → "훨씬"
 */
type DiffBucket = "similar" | "slight" | "large";

const SLIGHT_MIN_ABS_DELTA = 3;
const LARGE_MIN_ABS_DELTA = 6;

function bucketOf(delta: number): DiffBucket {
  const abs = Math.abs(delta);
  if (abs >= LARGE_MIN_ABS_DELTA) {
    return "large";
  }
  if (abs >= SLIGHT_MIN_ABS_DELTA) {
    return "slight";
  }
  return "similar";
}

/**
 * 상대 음역 설명 (RelativeRangeDescriptor).
 *
 * - `headline`: 고음/저음 중 차이가 큰 축(둘 다 "비슷"이면 폭)을 담은 주 문장.
 * - `detail`: 폭(span) 보조 문장. 폭이 "비슷"이거나 headline 이 이미 폭이면 `null`.
 * - `ariaSummary`: 스크린리더용 단일 요약(시각화 없이도 완결).
 */
export type RelativeRangeDescriptor = {
  readonly headline: string;
  readonly detail: string | null;
  readonly ariaSummary: string;
};

const ALL_SIMILAR_HEADLINE = "평균과 비슷한 음역이에요";

/**
 * 사용자 음역대를 벤치마크와 비교해 상대 설명을 생성한다.
 *
 * 규칙 (voice-range-intuitive-display.md §5-1, 성별 중립):
 *   - 비교 축 3개: 고음(user.high vs benchmark.high) / 저음(user.low vs benchmark.low)
 *     / 폭(user.span vs benchmark.span).
 *   - 방향: 고음 Δ>0 "더 올라가요" / Δ<0 "덜 올라가요". 저음 Δ>0(피치 높음) "덜
 *     내려가요" / Δ<0 "더 내려가요". 폭 Δ>0 "넓은 편" / Δ<0 "좁은 편".
 *   - 출력: 고음·저음 중 |Δ| 가 큰 축을 headline, 폭을 detail. 세 축 모두 "비슷"이면
 *     "평균과 비슷한 음역이에요".
 *
 * 비유한 입력은 a11y fallback 설명을 반환한다.
 */
export function describeRelative(
  lowMidi: number,
  highMidi: number,
  benchmark: VoiceRangeBenchmark = NEUTRAL_BENCHMARK,
): RelativeRangeDescriptor {
  if (!Number.isFinite(lowMidi) || !Number.isFinite(highMidi)) {
    return {
      headline: INVALID_MIDI_A11Y_FALLBACK,
      detail: null,
      ariaSummary: INVALID_MIDI_A11Y_FALLBACK,
    };
  }

  const highDelta = highMidi - benchmark.highMidi;
  const lowDelta = lowMidi - benchmark.lowMidi;
  const spanDelta = highMidi - lowMidi - benchmark.spanSemitones;

  const highBucket = bucketOf(highDelta);
  const lowBucket = bucketOf(lowDelta);
  const spanBucket = bucketOf(spanDelta);

  const spanSentence = describeSpan(spanDelta, spanBucket);

  if (
    highBucket === "similar" &&
    lowBucket === "similar" &&
    spanBucket === "similar"
  ) {
    return {
      headline: ALL_SIMILAR_HEADLINE,
      detail: null,
      ariaSummary: ALL_SIMILAR_HEADLINE,
    };
  }

  // 고음·저음 모두 "비슷"이면 폭 차이가 가장 두드러진 특징 → 폭을 headline 으로.
  if (highBucket === "similar" && lowBucket === "similar") {
    return {
      headline: spanSentence,
      detail: null,
      ariaSummary: spanSentence,
    };
  }

  // 고음·저음 중 |Δ| 가 큰 축을 headline (동률이면 고음 우선).
  const headline =
    Math.abs(highDelta) >= Math.abs(lowDelta)
      ? describeHigh(highDelta, highBucket)
      : describeLow(lowDelta, lowBucket);

  const detail = spanBucket === "similar" ? null : spanSentence;
  const ariaSummary = detail ? `${headline} ${detail}` : headline;

  return { headline, detail, ariaSummary };
}

function intensityWord(bucket: DiffBucket): string {
  return bucket === "large" ? "훨씬" : "약간";
}

function describeHigh(delta: number, bucket: DiffBucket): string {
  if (bucket === "similar") {
    return "고음은 평균과 비슷해요";
  }
  const direction = delta > 0 ? "더 올라가요" : "덜 올라가요";
  return `고음이 평균보다 ${intensityWord(bucket)} ${direction}`;
}

function describeLow(delta: number, bucket: DiffBucket): string {
  if (bucket === "similar") {
    return "저음은 평균과 비슷해요";
  }
  // Δ>0 = 사용자 최저음 피치가 더 높음 = 평균만큼 못 내려감 = "덜 내려가요".
  const direction = delta > 0 ? "덜 내려가요" : "더 내려가요";
  return `저음이 평균보다 ${intensityWord(bucket)} ${direction}`;
}

function describeSpan(delta: number, bucket: DiffBucket): string {
  if (bucket === "similar") {
    return "음역 폭은 평균과 비슷해요";
  }
  const direction = delta > 0 ? "넓은 편" : "좁은 편";
  return `음역 폭이 ${intensityWord(bucket)} ${direction}이에요`;
}

/**
 * 음역 분류 보조 라벨 (VocalRegister) — 일상어 밴드 (§5-1, 성별 중립).
 *
 * 1차 PoC 는 정식 성악 명칭(테너/소프라노 등)을 쓰지 않는다 — 비전문 사용자에게 또
 * 다른 전문 용어가 되기 때문. 중심음((low+high)/2) 위치 + 폭 기준의 일상어 밴드만 둔다.
 * 정식 명칭 매핑은 후속(§8 Q4).
 *
 * 시드 경계(튜닝 대상):
 *   - 폭 ≥ 24반음(2옥타브) → "넓은 음역" (중심음보다 폭이 우선)
 *   - 중심음 < C3(48) → "낮은 음역" / 중심음 ≥ C4(60) → "높은 음역" / 그 외 "중간 음역"
 */
export type VocalRegister =
  | "낮은 음역"
  | "중간 음역"
  | "높은 음역"
  | "넓은 음역"
  | "음역 정보 없음";

const WIDE_SPAN_MIN_SEMITONES = 24;
const LOW_REGISTER_MAX_CENTER = 48; // C3
const HIGH_REGISTER_MIN_CENTER = 60; // C4

export function classifyRegister(
  lowMidi: number,
  highMidi: number,
): VocalRegister {
  if (!Number.isFinite(lowMidi) || !Number.isFinite(highMidi)) {
    return "음역 정보 없음";
  }
  const span = highMidi - lowMidi;
  if (span >= WIDE_SPAN_MIN_SEMITONES) {
    return "넓은 음역";
  }
  const center = (lowMidi + highMidi) / 2;
  if (center < LOW_REGISTER_MAX_CENTER) {
    return "낮은 음역";
  }
  if (center >= HIGH_REGISTER_MIN_CENTER) {
    return "높은 음역";
  }
  return "중간 음역";
}
