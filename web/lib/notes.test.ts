/**
 * MIDI ↔ 음표 변환 단위 테스트.
 *
 * 검증 포인트:
 *   - 표준 앵커값(C0, C4, A4, B8)에서 정확한 노트명 반환.
 *   - 옥타브 경계(11→B, 12→C+1) 정상 처리.
 *   - 샤프 노트(C#4) 표기.
 *   - octaveRangeMidis()는 C2(36) ~ C6(84) 닫힌 구간 49개(반음 단위)를 반환.
 */

import { describe, expect, it } from "vitest";

import {
  MAX_MIDI,
  MIN_MIDI,
  midiToCombinedNoteName,
  midiToKoreanNoteName,
  midiToNoteName,
  octaveRangeMidis,
} from "./notes";

describe("midiToNoteName", () => {
  it("MIDI 12를 C0로 변환한다", () => {
    expect(midiToNoteName(12)).toBe("C0");
  });

  it("MIDI 60을 미들 C(C4)로 변환한다", () => {
    expect(midiToNoteName(60)).toBe("C4");
  });

  it("MIDI 69를 A4로 변환한다 (440Hz 기준음)", () => {
    expect(midiToNoteName(69)).toBe("A4");
  });

  it("MIDI 119를 B8로 변환한다 (지원 범위 상한)", () => {
    expect(midiToNoteName(119)).toBe("B8");
  });

  it("샤프 노트도 정확히 표기한다 (MIDI 61 → C#4)", () => {
    expect(midiToNoteName(61)).toBe("C#4");
  });

  it("옥타브 경계(B3→C4)에서 옥타브가 1 증가한다", () => {
    expect(midiToNoteName(59)).toBe("B3");
    expect(midiToNoteName(60)).toBe("C4");
  });
});

describe("octaveRangeMidis", () => {
  it("C2(36) ~ C6(84) 닫힌 구간 49개 노트를 반환한다", () => {
    const midis = octaveRangeMidis();
    expect(midis[0]).toBe(36);
    expect(midis[midis.length - 1]).toBe(84);
    expect(midis.length).toBe(84 - 36 + 1);
  });

  it("결과는 1 semitone 단위로 단조증가한다", () => {
    const midis = octaveRangeMidis();
    for (let i = 1; i < midis.length; i += 1) {
      expect(midis[i]).toBe(midis[i - 1] + 1);
    }
  });

  it("옥타브 시작음(C2/C3/C4/C5/C6)을 모두 포함한다", () => {
    const midis = octaveRangeMidis();
    // 이름은 'OctaveRange'지만 옥타브 경계만이 아닌 그 안의 모든 반음을 포함.
    // 동작 명세를 잠그기 위해 옥타브 시작음 5개가 포함되는지 명시적으로 확인.
    [36, 48, 60, 72, 84].forEach((c) => {
      expect(midis).toContain(c);
    });
  });

  it("호출 간 동일 결과를 반환한다 (참조 가변성 없음)", () => {
    const a = octaveRangeMidis();
    const b = octaveRangeMidis();
    expect(a).toEqual(b);
  });
});

describe("MIDI 상수", () => {
  it("MIN_MIDI=12, MAX_MIDI=119 (C0 ~ B8)", () => {
    expect(MIN_MIDI).toBe(12);
    expect(MAX_MIDI).toBe(119);
    expect(midiToNoteName(MIN_MIDI)).toBe("C0");
    expect(midiToNoteName(MAX_MIDI)).toBe("B8");
  });
});

describe("midiToKoreanNoteName (#318)", () => {
  it("C4 = 도4 (middle C)", () => {
    expect(midiToKoreanNoteName(60)).toBe("도4");
  });

  it("A4 = 라4 (440Hz 기준음)", () => {
    expect(midiToKoreanNoteName(69)).toBe("라4");
  });

  it("D3 = 레3 (이슈 본문 사용자 피드백 케이스)", () => {
    expect(midiToKoreanNoteName(50)).toBe("레3");
  });

  it("E2 = 미2 (이슈 본문 사용자 피드백 케이스)", () => {
    expect(midiToKoreanNoteName(40)).toBe("미2");
  });

  it("샤프 노트는 ♯(U+266F) 사용: MIDI 61 → 도♯4", () => {
    expect(midiToKoreanNoteName(61)).toBe("도♯4");
  });

  it("옥타브 경계(B3 = 시3, C4 = 도4)", () => {
    expect(midiToKoreanNoteName(59)).toBe("시3");
    expect(midiToKoreanNoteName(60)).toBe("도4");
  });

  it("MIN/MAX 끝값도 안전: MIDI 12 → 도0, MIDI 119 → 시8", () => {
    expect(midiToKoreanNoteName(MIN_MIDI)).toBe("도0");
    expect(midiToKoreanNoteName(MAX_MIDI)).toBe("시8");
  });
});

describe("midiToCombinedNoteName (#318 A안 — 한국어 (SPN) 병기)", () => {
  it("MIDI 60 → '도4 (C4)'", () => {
    expect(midiToCombinedNoteName(60)).toBe("도4 (C4)");
  });

  it("MIDI 69 → '라4 (A4)'", () => {
    expect(midiToCombinedNoteName(69)).toBe("라4 (A4)");
  });

  it("샤프 노트 병기 (MIDI 61 → '도♯4 (C#4)')", () => {
    expect(midiToCombinedNoteName(61)).toBe("도♯4 (C#4)");
  });
});
