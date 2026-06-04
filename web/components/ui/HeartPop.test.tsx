/**
 * HeartPop 단위 테스트 (closes #1685, UI/UX PR6).
 *
 * 검증:
 *   - tap → `data-active` 토글 (controlled 래퍼로 active 를 flip).
 *   - tap → onToggle 호출 + `navigator.vibrate(10)` (지원 시).
 *   - `navigator.vibrate` 미지원(jsdom 기본) 환경에서도 throw 0 — graceful.
 *   - 좋아요로 켜질 때만 sparkle 입자 렌더(끌 때는 미렌더 — 절제).
 *   - 카드 컨텍스트 이벤트 격리: preventDefault + stopPropagation.
 */

import { useState } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";

import { HeartPop } from "./HeartPop";

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

/**
 * active 를 자체 state 로 들고 onToggle 마다 flip 하는 controlled 래퍼.
 * 실제 사용처(LikeButton)는 mutation 훅이 active 를 내려주므로 같은 단방향 구조.
 */
function ControlledHeartPop({ onToggle }: { onToggle?: () => void }) {
  const [active, setActive] = useState(false);
  return (
    <HeartPop
      active={active}
      onToggle={() => {
        setActive((prev) => !prev);
        onToggle?.();
      }}
      label={active ? "좋아요 취소" : "좋아요"}
      ariaLabel={active ? "테스트 곡 좋아요 취소" : "테스트 곡 좋아요"}
    />
  );
}

describe("HeartPop", () => {
  it("tap 시 data-active / aria-pressed / 라벨이 토글된다", () => {
    render(<ControlledHeartPop />);

    const button = screen.getByRole("button", { name: "테스트 곡 좋아요" });
    expect(button).toHaveAttribute("data-active", "false");
    expect(button).toHaveAttribute("aria-pressed", "false");

    fireEvent.click(button);

    const toggled = screen.getByRole("button", {
      name: "테스트 곡 좋아요 취소",
    });
    expect(toggled).toHaveAttribute("data-active", "true");
    expect(toggled).toHaveAttribute("aria-pressed", "true");
  });

  it("tap 시 onToggle 을 호출한다", () => {
    const onToggle = vi.fn();
    render(<ControlledHeartPop onToggle={onToggle} />);

    fireEvent.click(screen.getByRole("button"));
    expect(onToggle).toHaveBeenCalledTimes(1);
  });

  it("좋아요로 켜질 때만 sparkle 입자를 렌더한다", () => {
    render(<ControlledHeartPop />);
    const button = screen.getByRole("button");

    // 초기(비활성): 입자 미렌더.
    expect(screen.queryByTestId("heart-sparkle-group")).toBeNull();

    // 켜기: sparkle 축포.
    fireEvent.click(button);
    expect(screen.getByTestId("heart-sparkle-group")).toBeInTheDocument();
    expect(
      screen.getByTestId("heart-sparkle-group").querySelectorAll(".heart-sparkle"),
    ).toHaveLength(3);
  });

  it("navigator.vibrate 지원 시 10ms 햅틱을 호출한다", () => {
    const vibrate = vi.fn();
    vi.stubGlobal("navigator", { ...navigator, vibrate });

    render(<ControlledHeartPop />);
    fireEvent.click(screen.getByRole("button"));

    expect(vibrate).toHaveBeenCalledWith(10);
  });

  it("navigator.vibrate 미지원 환경에서도 throw 없이 동작한다", () => {
    // jsdom 기본 navigator 에는 vibrate 가 없다 — optional chain graceful.
    expect(navigator.vibrate).toBeUndefined();

    const onToggle = vi.fn();
    render(<ControlledHeartPop onToggle={onToggle} />);

    expect(() => fireEvent.click(screen.getByRole("button"))).not.toThrow();
    expect(onToggle).toHaveBeenCalledTimes(1);
  });

  it("이벤트 격리: 부모 onClick(카드 Link/모달)으로 전파되지 않는다", () => {
    const parentClick = vi.fn();
    render(
      <div onClick={parentClick}>
        <ControlledHeartPop />
      </div>,
    );

    fireEvent.click(screen.getByRole("button"));
    expect(parentClick).not.toHaveBeenCalled();
  });

  it("disabled=true 면 클릭이 무시되고 aria-busy 가 노출된다", () => {
    const onToggle = vi.fn();
    render(
      <HeartPop
        active={false}
        onToggle={onToggle}
        disabled
        busy
        label="좋아요"
        ariaLabel="테스트 곡 좋아요"
      />,
    );

    const button = screen.getByRole("button");
    expect(button).toBeDisabled();
    expect(button).toHaveAttribute("aria-busy", "true");

    fireEvent.click(button);
    expect(onToggle).not.toHaveBeenCalled();
  });
});
