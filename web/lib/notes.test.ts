/**
 * MIDI ↔ 음표 변환 단위 테스트.
 *
 * 검증 포인트:
 *   - 표준 앵커값(C0, C4, A4, B8)에서 정확한 노트명 반환.
 *   - 옥타브 경계(11→B, 12→C+1) 정상 처리.
 *   - 샤프 노트(C#4) 표기.
 *   - octaveAnchorMidis()는 C2(36) ~ C6(84) 닫힌 구간을 반환.
 */

import { describe, expect, it } from "vitest";

import { MAX_MIDI, MIN_MIDI, midiToNoteName, octaveAnchorMidis } from "./notes";

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

describe("octaveAnchorMidis", () => {
  it("C2(36) ~ C6(84) 닫힌 구간 49개 노트를 반환한다", () => {
    const anchors = octaveAnchorMidis();
    expect(anchors[0]).toBe(36);
    expect(anchors[anchors.length - 1]).toBe(84);
    expect(anchors.length).toBe(84 - 36 + 1);
  });

  it("결과는 단조증가한다", () => {
    const anchors = octaveAnchorMidis();
    for (let i = 1; i < anchors.length; i += 1) {
      expect(anchors[i]).toBe(anchors[i - 1] + 1);
    }
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
