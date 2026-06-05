/**
 * VoiceRangeSlider 단위 테스트 (이슈 #1706).
 *
 * 두 손잡이 범위 슬라이더의 핵심 계약:
 *  - 두 개의 role="slider" 손잡이 + 한국어 음명 라벨.
 *  - 키보드(화살표/Page/Home/End)로 손잡이 이동 → onChange.
 *  - 최저음 ≤ 최고음 제약(클램프) — 손잡이가 상대를 밀지 않고 경계에서 멈춘다.
 *  - 포인터 드래그로 가장 가까운 손잡이 이동.
 *  - a11y 위반 없음.
 */

import { useState } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

import { VoiceRangeSlider } from "./VoiceRangeSlider";
import { expectNoA11yViolations } from "@/lib/test-helpers/a11y";

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

/** onChange 를 state 에 반영하는 controlled 하네스 — 키보드/드래그 후 재렌더를 검증. */
function Harness({
  initialLow = 48,
  initialHigh = 69,
  onChangeSpy,
}: {
  initialLow?: number;
  initialHigh?: number;
  onChangeSpy?: (next: { lowMidi: number; highMidi: number }) => void;
}) {
  const [range, setRange] = useState({
    lowMidi: initialLow,
    highMidi: initialHigh,
  });
  return (
    <VoiceRangeSlider
      lowMidi={range.lowMidi}
      highMidi={range.highMidi}
      onChange={(next) => {
        onChangeSpy?.(next);
        setRange(next);
      }}
    />
  );
}

function getThumbs() {
  return {
    low: screen.getByRole("slider", { name: "최저음" }),
    high: screen.getByRole("slider", { name: "최고음" }),
  };
}

describe("VoiceRangeSlider 렌더", () => {
  it("두 손잡이를 role=slider 로 렌더하고 aria 값을 노출한다", () => {
    render(<Harness />);
    const { low, high } = getThumbs();

    expect(low).toHaveAttribute("aria-valuenow", "48");
    expect(low).toHaveAttribute("aria-valuemin", "43");
    expect(low).toHaveAttribute("aria-valuemax", "69");
    expect(high).toHaveAttribute("aria-valuenow", "69");
    expect(high).toHaveAttribute("aria-valuemin", "48");
    expect(high).toHaveAttribute("aria-valuemax", "84");
  });

  it("aria-valuetext 에 한국어 음명 + SPN 을 병기한다", () => {
    render(<Harness initialLow={48} initialHigh={60} />);
    const { low, high } = getThumbs();
    // MIDI 48 = 도3 (C3), MIDI 60 = 도4 (C4).
    expect(low).toHaveAttribute("aria-valuetext", "최저음 도3 (C3)");
    expect(high).toHaveAttribute("aria-valuetext", "최고음 도4 (C4)");
  });

  it("min/max 를 커스텀할 수 있다", () => {
    render(
      <VoiceRangeSlider
        lowMidi={40}
        highMidi={50}
        onChange={() => {}}
        minMidi={36}
        maxMidi={96}
      />,
    );
    const high = screen.getByRole("slider", { name: "최고음" });
    expect(high).toHaveAttribute("aria-valuemax", "96");
  });
});

describe("VoiceRangeSlider 키보드", () => {
  it("ArrowRight 로 최저음을 1 반음 올린다", async () => {
    const user = userEvent.setup();
    const onChangeSpy = vi.fn();
    render(<Harness onChangeSpy={onChangeSpy} />);
    const { low } = getThumbs();

    low.focus();
    await user.keyboard("{ArrowRight}");

    expect(onChangeSpy).toHaveBeenCalledWith({ lowMidi: 49, highMidi: 69 });
    expect(getThumbs().low).toHaveAttribute("aria-valuenow", "49");
  });

  it("ArrowLeft 로 최고음을 1 반음 내린다", async () => {
    const user = userEvent.setup();
    render(<Harness />);
    const { high } = getThumbs();

    high.focus();
    await user.keyboard("{ArrowLeft}");

    expect(getThumbs().high).toHaveAttribute("aria-valuenow", "68");
  });

  it("PageUp 으로 12 반음(한 옥타브) 올린다", async () => {
    const user = userEvent.setup();
    render(<Harness initialLow={48} initialHigh={84} />);
    const { low } = getThumbs();

    low.focus();
    await user.keyboard("{PageUp}");

    expect(getThumbs().low).toHaveAttribute("aria-valuenow", "60");
  });

  it("Home 으로 최저음을 트랙 최소값으로 보낸다", async () => {
    const user = userEvent.setup();
    render(<Harness />);
    const { low } = getThumbs();

    low.focus();
    await user.keyboard("{Home}");

    expect(getThumbs().low).toHaveAttribute("aria-valuenow", "43");
  });

  it("End 로 최고음을 트랙 최대값으로 보낸다", async () => {
    const user = userEvent.setup();
    render(<Harness />);
    const { high } = getThumbs();

    high.focus();
    await user.keyboard("{End}");

    expect(getThumbs().high).toHaveAttribute("aria-valuenow", "84");
  });
});

describe("VoiceRangeSlider 제약(최저 ≤ 최고)", () => {
  it("최저음을 최고음 너머로 올려도 최고음에서 클램프된다", async () => {
    const user = userEvent.setup();
    render(<Harness initialLow={67} initialHigh={69} />);
    const { low } = getThumbs();

    low.focus();
    await user.keyboard("{ArrowRight}{ArrowRight}{ArrowRight}{ArrowRight}");

    // 67 → 69 까지만 오르고 더는 올라가지 않는다.
    expect(getThumbs().low).toHaveAttribute("aria-valuenow", "69");
    expect(getThumbs().high).toHaveAttribute("aria-valuenow", "69");
  });

  it("최고음을 최저음 아래로 내려도 최저음에서 클램프된다", async () => {
    const user = userEvent.setup();
    render(<Harness initialLow={48} initialHigh={50} />);
    const { high } = getThumbs();

    high.focus();
    await user.keyboard("{ArrowLeft}{ArrowLeft}{ArrowLeft}{ArrowLeft}");

    expect(getThumbs().high).toHaveAttribute("aria-valuenow", "48");
    expect(getThumbs().low).toHaveAttribute("aria-valuenow", "48");
  });
});

describe("VoiceRangeSlider disabled", () => {
  it("disabled 면 손잡이가 포커스 불가(tabIndex=-1)이고 키보드로 변경되지 않는다", async () => {
    const user = userEvent.setup();
    const onChangeSpy = vi.fn();
    render(
      <VoiceRangeSlider
        lowMidi={48}
        highMidi={69}
        onChange={onChangeSpy}
        disabled
      />,
    );
    const low = screen.getByRole("slider", { name: "최저음" });
    expect(low).toHaveAttribute("tabindex", "-1");
    expect(low).toHaveAttribute("aria-disabled", "true");

    low.focus();
    await user.keyboard("{ArrowRight}");
    expect(onChangeSpy).not.toHaveBeenCalled();
  });
});

describe("VoiceRangeSlider 포인터 드래그", () => {
  it("트랙 클릭/드래그로 가장 가까운 손잡이를 이동한다", () => {
    // happy-dom 은 레이아웃을 계산하지 않으므로 트랙 폭을 mock 한다.
    vi.spyOn(Element.prototype, "getBoundingClientRect").mockReturnValue({
      left: 0,
      top: 0,
      right: 480,
      bottom: 40,
      width: 480,
      height: 40,
      x: 0,
      y: 0,
      toJSON: () => ({}),
    } as DOMRect);

    const onChangeSpy = vi.fn();
    render(<Harness onChangeSpy={onChangeSpy} />);
    const track = screen.getByTestId("voice-range-track");

    // 트랙 폭 480 → 43..84 (41 반음). clientX 0 = MIDI 43, 480 = MIDI 84.
    // 최저음(48)에 가까운 좌측 클릭으로 최저음을 끌어내린다.
    fireEvent.pointerDown(track, { clientX: 0, pointerId: 1 });

    expect(onChangeSpy).toHaveBeenCalledWith({ lowMidi: 43, highMidi: 69 });
  });
});

describe("VoiceRangeSlider a11y", () => {
  it("a11y 위반이 없다", async () => {
    const { container } = render(<Harness />);
    await expectNoA11yViolations(container);
  });
});
