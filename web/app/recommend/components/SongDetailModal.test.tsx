/**
 * SongDetailModal 단위 테스트 (closes #323).
 *
 * 검증 범위:
 *  - role/aria 속성: role="dialog", aria-modal="true", aria-labelledby가 제목 노드 ID와 연결.
 *  - 닫기 액션: ESC 키, 닫기 버튼, backdrop 클릭 → onClose 호출.
 *  - body 스크롤 락: 모달 오픈 시 body.style.overflow="hidden" 설정.
 *  - axe-core a11y 자동 검사.
 */

import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

import { SongDetailModal } from "./SongDetailModal";
import { expectNoA11yViolations } from "@/lib/test-helpers/a11y";

afterEach(() => {
  cleanup();
  // 스크롤 락 정리 — 다음 테스트가 영향받지 않도록.
  document.body.style.overflow = "";
});

describe("SongDetailModal", () => {
  it("open=false 면 아무것도 렌더하지 않는다", () => {
    const { container } = render(
      <SongDetailModal open={false} onClose={vi.fn()} titleLabel="테스트 곡">
        <p>본문</p>
      </SongDetailModal>,
    );
    expect(container.firstChild).toBeNull();
  });

  it("open=true 면 dialog/aria-modal/aria-labelledby가 모두 채워진다", () => {
    render(
      <SongDetailModal open onClose={vi.fn()} titleLabel="테스트 곡">
        <p>본문</p>
      </SongDetailModal>,
    );
    const dialog = screen.getByRole("dialog");
    expect(dialog).toHaveAttribute("aria-modal", "true");
    const labelId = dialog.getAttribute("aria-labelledby");
    expect(labelId).toBeTruthy();
    const heading = screen.getByRole("heading", { name: /테스트 곡/ });
    expect(heading).toHaveAttribute("id", labelId!);
  });

  it("닫기 버튼 클릭 시 onClose가 호출된다", async () => {
    const user = userEvent.setup();
    const onClose = vi.fn();
    render(
      <SongDetailModal open onClose={onClose} titleLabel="테스트 곡">
        <p>본문</p>
      </SongDetailModal>,
    );
    await user.click(screen.getByRole("button", { name: /상세 닫기/ }));
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it("ESC 키를 누르면 onClose가 호출된다", async () => {
    const user = userEvent.setup();
    const onClose = vi.fn();
    render(
      <SongDetailModal open onClose={onClose} titleLabel="테스트 곡">
        <p>본문</p>
      </SongDetailModal>,
    );
    // dialog 또는 그 안 요소에 포커스가 있어야 keydown이 라우팅된다.
    screen.getByRole("button", { name: /상세 닫기/ }).focus();
    await user.keyboard("{Escape}");
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it("backdrop 클릭 시 onClose가 호출되고, 본문 클릭 시에는 호출되지 않는다", async () => {
    const user = userEvent.setup();
    const onClose = vi.fn();
    render(
      <SongDetailModal open onClose={onClose} titleLabel="테스트 곡">
        <p>본문 영역</p>
      </SongDetailModal>,
    );
    // 본문(p) 클릭 — 이벤트가 dialog 컨테이너에서 멈춘다.
    await user.click(screen.getByText("본문 영역"));
    expect(onClose).not.toHaveBeenCalled();

    // backdrop = role="presentation" 컨테이너. 직접 dispatch로 currentTarget==target 시뮬레이션.
    const backdrop = document.querySelector('[role="presentation"]');
    expect(backdrop).not.toBeNull();
    // userEvent.click은 inner click을 만들기 때문에, backdrop 자체로 시뮬레이션하려면
    // dispatchEvent 사용.
    backdrop!.dispatchEvent(
      new MouseEvent("click", { bubbles: true, cancelable: true }),
    );
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it("open=true 시 body 스크롤이 잠기고, unmount 시 복원된다", () => {
    document.body.style.overflow = "auto";
    const { unmount } = render(
      <SongDetailModal open onClose={vi.fn()} titleLabel="테스트 곡">
        <p>본문</p>
      </SongDetailModal>,
    );
    expect(document.body.style.overflow).toBe("hidden");
    unmount();
    expect(document.body.style.overflow).toBe("auto");
  });

  it("a11y 위반이 없다", async () => {
    const { container } = render(
      <SongDetailModal open onClose={vi.fn()} titleLabel="테스트 곡">
        <p>본문</p>
      </SongDetailModal>,
    );
    await expectNoA11yViolations(container);
  });

  /**
   * focus trap + 포커스 복원 회귀 가드 (closes #438).
   *
   * 검증 목적: SongDetailModal 의 키보드 접근성 핵심 동작이 추후 리팩터링(셀렉터/
   * useEffect 의존성/FOCUSABLE_SELECTOR 변경 등)으로 깨지지 않도록 가드한다.
   *
   * happy-dom 환경 특성:
   *  - HTMLElement.focus() 호출 후 document.activeElement 갱신은 동기 보장.
   *  - userEvent.tab() 이 disabled 가 아닌 focusable 노드 순회를 시뮬레이션.
   *  - 초기 포커스는 useEffect 안 setTimeout(0) 으로 비동기 — vi.waitFor 로 대기.
   */
  describe("focus trap + 포커스 복원", () => {
    it("오픈 시 첫 포커스 가능 요소(닫기 버튼)로 초기 포커스가 이동한다", async () => {
      render(
        <SongDetailModal open onClose={vi.fn()} titleLabel="테스트 곡">
          <button type="button">본문 버튼 A</button>
          <button type="button">본문 버튼 B</button>
        </SongDetailModal>,
      );
      const closeButton = screen.getByRole("button", { name: /상세 닫기/ });
      // setTimeout(0) 으로 포커스가 다음 tick 에 이동하므로 waitFor 사용.
      await waitFor(() => {
        expect(document.activeElement).toBe(closeButton);
      });
    });

    it("Tab 키가 마지막 요소에서 첫 요소로 순환한다 (forward wrap)", async () => {
      const user = userEvent.setup();
      render(
        <SongDetailModal open onClose={vi.fn()} titleLabel="테스트 곡">
          <button type="button">본문 버튼 A</button>
          <button type="button">본문 버튼 B</button>
        </SongDetailModal>,
      );
      const closeButton = screen.getByRole("button", { name: /상세 닫기/ });
      const buttonB = screen.getByRole("button", { name: /본문 버튼 B/ });

      // FOCUSABLE_SELECTOR 순서: DOM 순서 = [닫기, A, B]. 마지막(B)에 직접 포커스 후 Tab.
      buttonB.focus();
      expect(document.activeElement).toBe(buttonB);

      await user.tab();
      expect(document.activeElement).toBe(closeButton);
    });

    it("Shift+Tab 키가 첫 요소에서 마지막 요소로 순환한다 (backward wrap)", async () => {
      const user = userEvent.setup();
      render(
        <SongDetailModal open onClose={vi.fn()} titleLabel="테스트 곡">
          <button type="button">본문 버튼 A</button>
          <button type="button">본문 버튼 B</button>
        </SongDetailModal>,
      );
      const closeButton = screen.getByRole("button", { name: /상세 닫기/ });
      const buttonB = screen.getByRole("button", { name: /본문 버튼 B/ });

      // 첫 요소(닫기 버튼)에 포커스 후 Shift+Tab → 마지막(B) 로 wrap.
      closeButton.focus();
      expect(document.activeElement).toBe(closeButton);

      await user.tab({ shift: true });
      expect(document.activeElement).toBe(buttonB);
    });

    it("모달 unmount 시 직전 포커스 요소(open trigger)로 포커스가 복원된다", async () => {
      // 모달 외부 trigger 버튼을 미리 두고, 거기에 포커스를 둔 채 모달을 연다.
      const Wrapper = ({ open }: { open: boolean }) => (
        <>
          <button type="button" data-testid="trigger">
            트리거
          </button>
          <SongDetailModal open={open} onClose={vi.fn()} titleLabel="테스트 곡">
            <p>본문</p>
          </SongDetailModal>
        </>
      );

      const { rerender } = render(<Wrapper open={false} />);
      const trigger = screen.getByTestId("trigger");
      trigger.focus();
      expect(document.activeElement).toBe(trigger);

      // 모달 오픈 → 초기 포커스가 닫기 버튼으로 이동할 때까지 대기.
      rerender(<Wrapper open />);
      const closeButton = screen.getByRole("button", { name: /상세 닫기/ });
      await waitFor(() => {
        expect(document.activeElement).toBe(closeButton);
      });

      // 모달 close (open=false) → useEffect cleanup 이 직전 포커스를 복원해야 한다.
      rerender(<Wrapper open={false} />);
      expect(document.activeElement).toBe(trigger);
    });
  });
});
