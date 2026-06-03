/**
 * SongDetailSheet 단위 테스트 (closes #1696, 구 SongDetailModal.test.tsx — #323).
 *
 * `docs/features/ui-ux-redesign-pr-4-bottom-sheet.md` §5-3 회귀 가드 매트릭스
 * G1~G12 중 컴포넌트 레벨 가드를 검증한다. 제스처 거리/velocity 분기(G6~G9)의
 * 정밀 검증은 `hooks/useBottomSheet.test.ts` 가 담당하고, 본 파일은 마운트/
 * a11y/focus trap/scroll lock/ESC/backdrop + drag handle 존재를 가드한다.
 */

import { useState } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import userEvent from "@testing-library/user-event";

import { SongDetailSheet } from "./SongDetailSheet";
import { expectNoA11yViolations } from "@/lib/test-helpers/a11y";

afterEach(() => {
  cleanup();
  // scroll lock 잔여 정리 — 다음 테스트 격리.
  document.body.style.overflow = "";
  document.body.style.position = "";
  document.body.style.top = "";
  document.body.style.width = "";
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

describe("SongDetailSheet (#1696)", () => {
  it("open=false 면 아무것도 렌더하지 않는다", () => {
    const { container } = render(
      <SongDetailSheet open={false} onClose={vi.fn()} titleLabel="테스트 곡">
        <p>본문</p>
      </SongDetailSheet>,
    );
    expect(container.firstChild).toBeNull();
  });

  // G10: role / aria 속성.
  it("open=true 면 dialog/aria-modal/aria-labelledby가 모두 채워진다", () => {
    render(
      <SongDetailSheet open onClose={vi.fn()} titleLabel="테스트 곡">
        <p>본문</p>
      </SongDetailSheet>,
    );
    const dialog = screen.getByRole("dialog");
    expect(dialog).toHaveAttribute("aria-modal", "true");
    const labelId = dialog.getAttribute("aria-labelledby");
    expect(labelId).toBeTruthy();
    const heading = screen.getByRole("heading", { name: /테스트 곡/ });
    expect(heading).toHaveAttribute("id", labelId!);
  });

  // drag handle: spec §3 — role="separator" + aria-orientation="horizontal".
  it("drag handle bar 가 role=separator + aria-orientation=horizontal 로 렌더된다", () => {
    render(
      <SongDetailSheet open onClose={vi.fn()} titleLabel="테스트 곡">
        <p>본문</p>
      </SongDetailSheet>,
    );
    const handle = screen.getByRole("separator");
    expect(handle).toHaveAttribute("aria-orientation", "horizontal");
  });

  it("두 시트 동시 오픈 시 aria-labelledby ↔ heading id 가 인스턴스별로 고유 매칭된다", () => {
    render(
      <>
        <SongDetailSheet open onClose={vi.fn()} titleLabel="첫 번째 곡">
          <p>본문 1</p>
        </SongDetailSheet>
        <SongDetailSheet open onClose={vi.fn()} titleLabel="두 번째 곡">
          <p>본문 2</p>
        </SongDetailSheet>
      </>,
    );
    const dialogs = screen.getAllByRole("dialog");
    expect(dialogs).toHaveLength(2);
    const labelIds = dialogs.map((d) => d.getAttribute("aria-labelledby"));
    expect(labelIds[0]).toBeTruthy();
    expect(labelIds[1]).toBeTruthy();
    expect(labelIds[0]).not.toBe(labelIds[1]);
    for (const labelId of labelIds) {
      const matched = document.querySelectorAll(`#${CSS.escape(labelId!)}`);
      expect(matched).toHaveLength(1);
      expect(matched[0].tagName).toBe("H2");
    }
  });

  it("닫기 버튼 클릭 시 onClose가 호출된다", async () => {
    const user = userEvent.setup();
    const onClose = vi.fn();
    render(
      <SongDetailSheet open onClose={onClose} titleLabel="테스트 곡">
        <p>본문</p>
      </SongDetailSheet>,
    );
    await user.click(screen.getByRole("button", { name: /상세 닫기/ }));
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  // G2: ESC close.
  it("ESC 키를 누르면 onClose가 호출된다", async () => {
    const user = userEvent.setup();
    const onClose = vi.fn();
    render(
      <SongDetailSheet open onClose={onClose} titleLabel="테스트 곡">
        <p>본문</p>
      </SongDetailSheet>,
    );
    screen.getByRole("button", { name: /상세 닫기/ }).focus();
    await user.keyboard("{Escape}");
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  // G3: backdrop click close.
  it("backdrop 클릭 시 onClose가 호출되고, 본문 클릭 시에는 호출되지 않는다", async () => {
    const user = userEvent.setup();
    const onClose = vi.fn();
    render(
      <SongDetailSheet open onClose={onClose} titleLabel="테스트 곡">
        <p>본문 영역</p>
      </SongDetailSheet>,
    );
    await user.click(screen.getByText("본문 영역"));
    expect(onClose).not.toHaveBeenCalled();

    const backdrop = document.querySelector('[role="presentation"]');
    expect(backdrop).not.toBeNull();
    backdrop!.dispatchEvent(
      new MouseEvent("click", { bubbles: true, cancelable: true }),
    );
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  // G4 + G5: body scroll lock (overflow hidden + position fixed + top) + 복원.
  it("open=true 시 body scroll lock(overflow/position/top) 이 걸리고 unmount 시 복원된다", () => {
    Object.defineProperty(window, "scrollY", {
      value: 150,
      writable: true,
      configurable: true,
    });
    const scrollToSpy = vi
      .spyOn(window, "scrollTo")
      .mockImplementation(() => {});
    document.body.style.overflow = "auto";

    const { unmount } = render(
      <SongDetailSheet open onClose={vi.fn()} titleLabel="테스트 곡">
        <p>본문</p>
      </SongDetailSheet>,
    );
    expect(document.body.style.overflow).toBe("hidden");
    expect(document.body.style.position).toBe("fixed");
    expect(document.body.style.top).toBe("-150px");
    expect(document.body.style.width).toBe("100%");

    unmount();
    expect(document.body.style.overflow).toBe("auto");
    expect(document.body.style.position).toBe("");
    expect(scrollToSpy).toHaveBeenCalledWith(0, 150);
  });

  it("a11y 위반이 없다", async () => {
    const { container } = render(
      <SongDetailSheet open onClose={vi.fn()} titleLabel="테스트 곡">
        <p>본문</p>
      </SongDetailSheet>,
    );
    await expectNoA11yViolations(container);
  });

  // Escape 회귀 가드 (구 #509): 닫힘 상태 no-op + 비-ESC 키 분기.
  describe("Escape 키 회귀 가드", () => {
    it("open=false 면 ESC 를 눌러도 onClose 가 호출되지 않는다", async () => {
      const user = userEvent.setup();
      const onClose = vi.fn();
      render(
        <SongDetailSheet open={false} onClose={onClose} titleLabel="테스트 곡">
          <p>본문</p>
        </SongDetailSheet>,
      );
      await user.keyboard("{Escape}");
      expect(onClose).not.toHaveBeenCalled();
    });

    it("ESC 이외 키('a')는 onClose 를 호출하지 않는다", async () => {
      const user = userEvent.setup();
      const onClose = vi.fn();
      render(
        <SongDetailSheet open onClose={onClose} titleLabel="테스트 곡">
          <p>본문</p>
        </SongDetailSheet>,
      );
      screen.getByRole("button", { name: /상세 닫기/ }).focus();
      await user.keyboard("a");
      expect(onClose).not.toHaveBeenCalled();
    });
  });

  // G1 + G12: focus trap + 포커스 복원.
  describe("focus trap + 포커스 복원", () => {
    it("오픈 시 첫 포커스 가능 요소(닫기 버튼)로 초기 포커스가 이동한다", async () => {
      render(
        <SongDetailSheet open onClose={vi.fn()} titleLabel="테스트 곡">
          <button type="button">본문 버튼 A</button>
          <button type="button">본문 버튼 B</button>
        </SongDetailSheet>,
      );
      const closeButton = screen.getByRole("button", { name: /상세 닫기/ });
      await waitFor(() => {
        expect(document.activeElement).toBe(closeButton);
      });
    });

    it("Tab 키가 마지막 요소에서 첫 요소로 순환한다 (forward wrap)", () => {
      render(
        <SongDetailSheet open onClose={vi.fn()} titleLabel="테스트 곡">
          <button type="button">본문 버튼 A</button>
          <button type="button">본문 버튼 B</button>
        </SongDetailSheet>,
      );
      const closeButton = screen.getByRole("button", { name: /상세 닫기/ });
      const buttonB = screen.getByRole("button", { name: /본문 버튼 B/ });
      buttonB.focus();
      fireEvent.keyDown(buttonB, { key: "Tab" });
      expect(document.activeElement).toBe(closeButton);
    });

    it("Shift+Tab 키가 첫 요소에서 마지막 요소로 순환한다 (backward wrap)", async () => {
      const user = userEvent.setup();
      render(
        <SongDetailSheet open onClose={vi.fn()} titleLabel="테스트 곡">
          <button type="button">본문 버튼 A</button>
          <button type="button">본문 버튼 B</button>
        </SongDetailSheet>,
      );
      const closeButton = screen.getByRole("button", { name: /상세 닫기/ });
      const buttonB = screen.getByRole("button", { name: /본문 버튼 B/ });
      closeButton.focus();
      await user.tab({ shift: true });
      expect(document.activeElement).toBe(buttonB);
    });

    function FocusRestoreHarness() {
      const [open, setOpen] = useState(false);
      return (
        <>
          <button
            type="button"
            data-testid="trigger"
            onClick={() => setOpen(true)}
          >
            트리거
          </button>
          <SongDetailSheet
            open={open}
            onClose={() => setOpen(false)}
            titleLabel="테스트 곡"
          >
            <p>본문</p>
          </SongDetailSheet>
        </>
      );
    }

    it("ESC 로 닫으면 trigger 로 포커스가 복귀한다", async () => {
      const user = userEvent.setup();
      render(<FocusRestoreHarness />);
      const trigger = screen.getByTestId("trigger");
      await user.click(trigger);
      const closeButton = await screen.findByRole("button", {
        name: /상세 닫기/,
      });
      await waitFor(() => expect(document.activeElement).toBe(closeButton));
      await user.keyboard("{Escape}");
      expect(document.activeElement).toBe(trigger);
    });

    it("X 버튼 클릭으로 닫으면 trigger 로 포커스가 복귀한다", async () => {
      const user = userEvent.setup();
      render(<FocusRestoreHarness />);
      const trigger = screen.getByTestId("trigger");
      await user.click(trigger);
      const closeButton = await screen.findByRole("button", {
        name: /상세 닫기/,
      });
      await waitFor(() => expect(document.activeElement).toBe(closeButton));
      await user.click(closeButton);
      expect(document.activeElement).toBe(trigger);
    });

    it("외부 element focus + Tab 시 시트 첫 요소로 강제 복귀한다", () => {
      render(
        <>
          <button type="button" data-testid="outside">
            외부
          </button>
          <SongDetailSheet open onClose={vi.fn()} titleLabel="테스트 곡">
            <button type="button">본문 버튼</button>
          </SongDetailSheet>
        </>,
      );
      const closeButton = screen.getByRole("button", { name: /상세 닫기/ });
      const outside = screen.getByTestId("outside");
      outside.focus();
      fireEvent.keyDown(screen.getByRole("dialog"), { key: "Tab" });
      expect(document.activeElement).toBe(closeButton);
    });

    it("외부 element focus + Shift+Tab 시 시트 마지막 요소로 강제 복귀한다", () => {
      render(
        <>
          <button type="button" data-testid="outside">
            외부
          </button>
          <SongDetailSheet open onClose={vi.fn()} titleLabel="테스트 곡">
            <button type="button">본문 버튼</button>
          </SongDetailSheet>
        </>,
      );
      const bodyButton = screen.getByRole("button", { name: /본문 버튼/ });
      const outside = screen.getByTestId("outside");
      outside.focus();
      fireEvent.keyDown(screen.getByRole("dialog"), {
        key: "Tab",
        shiftKey: true,
      });
      expect(document.activeElement).toBe(bodyButton);
    });

    it("시트 close 시 직전 포커스 요소(open trigger)로 포커스가 복원된다", async () => {
      const Wrapper = ({ open }: { open: boolean }) => (
        <>
          <button type="button" data-testid="trigger">
            트리거
          </button>
          <SongDetailSheet open={open} onClose={vi.fn()} titleLabel="테스트 곡">
            <p>본문</p>
          </SongDetailSheet>
        </>
      );

      const { rerender } = render(<Wrapper open={false} />);
      const trigger = screen.getByTestId("trigger");
      trigger.focus();
      expect(document.activeElement).toBe(trigger);

      rerender(<Wrapper open />);
      const closeButton = screen.getByRole("button", { name: /상세 닫기/ });
      await waitFor(() => {
        expect(document.activeElement).toBe(closeButton);
      });

      rerender(<Wrapper open={false} />);
      expect(document.activeElement).toBe(trigger);
    });
  });

  // G11: prefers-reduced-motion 시 drag gesture 비활성 (translateY 변동 없음).
  describe("prefers-reduced-motion", () => {
    beforeEach(() => {
      vi.stubGlobal(
        "matchMedia",
        vi.fn().mockReturnValue({ matches: true }) as unknown,
      );
    });

    it("reduced-motion 시 touch drag 가 시트 transform 을 변경하지 않는다", () => {
      const onClose = vi.fn();
      render(
        <SongDetailSheet open onClose={onClose} titleLabel="테스트 곡">
          <p>본문</p>
        </SongDetailSheet>,
      );
      const dialog = screen.getByRole("dialog");
      fireEvent.touchStart(dialog, { touches: [{ clientY: 0 }] });
      fireEvent.touchMove(dialog, { touches: [{ clientY: 300 }] });
      fireEvent.touchEnd(dialog);
      expect(onClose).not.toHaveBeenCalled();
      expect(dialog.style.transform).toBe("");
    });
  });
});
