/**
 * voiceRangeBenchmark 단위 테스트 (voice-range-intuitive-display.md §7).
 *
 * 차이 버킷 경계값(2/3/5/6 반음) · 방향 문구 · "비슷"만일 때 폴백 · NaN/Infinity 가드 ·
 * classifyRegister 일상어 밴드를 검증한다.
 */

import { describe, expect, it } from "vitest";

import {
  FEMALE_BENCHMARK,
  MALE_BENCHMARK,
  NEUTRAL_BENCHMARK,
  classifyRegister,
  describeRelative,
  selectBenchmark,
} from "./voiceRangeBenchmark";

describe("벤치마크 시드값", () => {
  it("성별 중립 벤치마크는 A2(45)~C4(60), span 15", () => {
    expect(NEUTRAL_BENCHMARK).toEqual({
      lowMidi: 45,
      highMidi: 60,
      spanSemitones: 15,
    });
  });

  it("남성/여성 벤치마크 span 은 high-low 로 일관", () => {
    expect(MALE_BENCHMARK.spanSemitones).toBe(
      MALE_BENCHMARK.highMidi - MALE_BENCHMARK.lowMidi,
    );
    expect(FEMALE_BENCHMARK.spanSemitones).toBe(
      FEMALE_BENCHMARK.highMidi - FEMALE_BENCHMARK.lowMidi,
    );
  });

  it("selectBenchmark 는 성별 미보유 시 성별 중립", () => {
    expect(selectBenchmark()).toBe(NEUTRAL_BENCHMARK);
    expect(selectBenchmark("MALE")).toBe(MALE_BENCHMARK);
    expect(selectBenchmark("FEMALE")).toBe(FEMALE_BENCHMARK);
  });
});

describe("describeRelative — 차이 버킷 경계", () => {
  // 벤치마크와 동일한 음역대 → 세 축 모두 "비슷" → 폴백.
  it("벤치마크와 동일하면 평균과 비슷한 음역이에요", () => {
    const result = describeRelative(45, 60, NEUTRAL_BENCHMARK);
    expect(result.headline).toBe("평균과 비슷한 음역이에요");
    expect(result.detail).toBeNull();
  });

  it("고음 Δ=2(경계)는 '비슷' 버킷", () => {
    // high 62 → Δ=+2, low 45 → Δ=0, span 17 → Δ=+2 (모두 비슷)
    const result = describeRelative(45, 62, NEUTRAL_BENCHMARK);
    expect(result.headline).toBe("평균과 비슷한 음역이에요");
  });

  it("고음 Δ=3(경계)는 '약간' 버킷 + 더 올라가요", () => {
    // high 63 → Δ=+3, low 45 → Δ=0
    const result = describeRelative(45, 63, NEUTRAL_BENCHMARK);
    expect(result.headline).toBe("고음이 평균보다 약간 더 올라가요");
  });

  it("고음 Δ=5(경계)는 여전히 '약간'", () => {
    const result = describeRelative(45, 65, NEUTRAL_BENCHMARK);
    expect(result.headline).toBe("고음이 평균보다 약간 더 올라가요");
  });

  it("고음 Δ=6(경계)는 '훨씬' 버킷", () => {
    const result = describeRelative(45, 66, NEUTRAL_BENCHMARK);
    expect(result.headline).toBe("고음이 평균보다 훨씬 더 올라가요");
  });

  it("고음이 평균보다 낮으면 덜 올라가요", () => {
    // high 54 → Δ=-6
    const result = describeRelative(45, 54, NEUTRAL_BENCHMARK);
    expect(result.headline).toBe("고음이 평균보다 훨씬 덜 올라가요");
  });
});

describe("describeRelative — 저음 방향", () => {
  it("저음 피치가 높으면(Δ>0) 덜 내려가요", () => {
    // low 49 → Δ=+4(약간), high 60 → Δ=0 → 저음이 headline
    const result = describeRelative(49, 60, NEUTRAL_BENCHMARK);
    expect(result.headline).toBe("저음이 평균보다 약간 덜 내려가요");
  });

  it("저음 피치가 낮으면(Δ<0) 더 내려가요", () => {
    // low 39 → Δ=-6(훨씬), high 60 → Δ=0
    const result = describeRelative(39, 60, NEUTRAL_BENCHMARK);
    expect(result.headline).toBe("저음이 평균보다 훨씬 더 내려가요");
  });
});

describe("describeRelative — headline 축 선택 + 폭 보조", () => {
  it("고음·저음 중 |Δ| 가 큰 축을 headline", () => {
    // low 42 → Δ=-3(약간), high 70 → Δ=+10(훨씬) → 고음이 headline
    const result = describeRelative(42, 70, NEUTRAL_BENCHMARK);
    expect(result.headline).toBe("고음이 평균보다 훨씬 더 올라가요");
  });

  it("동률이면 고음 우선", () => {
    // low 42 → Δ=-3, high 63 → Δ=+3 (둘 다 |3|) → 고음
    const result = describeRelative(42, 63, NEUTRAL_BENCHMARK);
    expect(result.headline).toBe("고음이 평균보다 약간 더 올라가요");
  });

  it("폭이 다르면 detail 에 폭 문장", () => {
    // low 45 → Δ=0, high 70 → Δ=+10, span 25 → Δ=+10 → 넓은 편
    const result = describeRelative(45, 70, NEUTRAL_BENCHMARK);
    expect(result.headline).toBe("고음이 평균보다 훨씬 더 올라가요");
    expect(result.detail).toBe("음역 폭이 훨씬 넓은 편이에요");
    expect(result.ariaSummary).toContain("음역 폭이");
  });

  it("폭이 '비슷'이면 detail 은 null", () => {
    // low 49 → Δ=+4, high 64 → Δ=+4, span 15 → Δ=0 (폭 비슷)
    const result = describeRelative(49, 64, NEUTRAL_BENCHMARK);
    expect(result.detail).toBeNull();
  });

  it("고음·저음 모두 비슷하지만 폭이 다르면 폭을 headline", () => {
    // low 46 → Δ=+1, high 59 → Δ=-1, span 13 → Δ=-2 ... 모두 비슷
    // 폭만 다르게: low 44 → Δ=-1, high 61 → Δ=+1, span 17 → Δ=+2 (비슷)
    // 폭 차이를 크게: low 46 Δ=+1, high 61 Δ=+1 이면 span 15 Δ=0
    // 의도적으로 high/low 비슷(±2) + span large 만들기:
    // low 47 → Δ=+2(비슷), high 58 → Δ=-2(비슷), span 11 → Δ=-4(약간 좁음)
    const result = describeRelative(47, 58, NEUTRAL_BENCHMARK);
    expect(result.headline).toBe("음역 폭이 약간 좁은 편이에요");
    expect(result.detail).toBeNull();
  });
});

describe("describeRelative — 비유한 입력 가드", () => {
  it("NaN low 는 a11y fallback", () => {
    const result = describeRelative(NaN, 60, NEUTRAL_BENCHMARK);
    expect(result.headline).toBe("음정 정보 없음");
    expect(result.detail).toBeNull();
    expect(result.ariaSummary).toBe("음정 정보 없음");
  });

  it("Infinity high 는 a11y fallback", () => {
    const result = describeRelative(45, Infinity, NEUTRAL_BENCHMARK);
    expect(result.headline).toBe("음정 정보 없음");
  });
});

describe("classifyRegister — 일상어 밴드", () => {
  it("폭 ≥ 24반음은 넓은 음역(중심음보다 우선)", () => {
    // low 40, high 64 → span 24, center 52
    expect(classifyRegister(40, 64)).toBe("넓은 음역");
  });

  it("중심음 < C3(48)은 낮은 음역", () => {
    // low 36, high 50 → center 43
    expect(classifyRegister(36, 50)).toBe("낮은 음역");
  });

  it("중심음 ≥ C4(60)은 높은 음역", () => {
    // low 55, high 70 → center 62.5, span 15
    expect(classifyRegister(55, 70)).toBe("높은 음역");
  });

  it("그 외는 중간 음역", () => {
    // low 48, high 62 → center 55, span 14
    expect(classifyRegister(48, 62)).toBe("중간 음역");
  });

  it("비유한 입력은 음역 정보 없음", () => {
    expect(classifyRegister(NaN, 60)).toBe("음역 정보 없음");
    expect(classifyRegister(45, Infinity)).toBe("음역 정보 없음");
  });
});
