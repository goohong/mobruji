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
import { cleanup, render, screen } from "@testing-library/react";
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
});
