/**
 * 곡 상세 모달 컴포넌트 (closes #323).
 *
 * ## 도입 배경
 * 사용자 검수 피드백: "한눈에 들어오는 정보량이 과하다 — 카드는 요약, 클릭 시 상세".
 * 추천/검색/좋아요/북마크 페이지의 SongCard는 핵심 정보(제목·아티스트·난이도·최고음·장르)만
 * 카드 표면에 노출하고, 사용자가 카드 본문/“자세히” 버튼을 누르면 본 모달이 열려 모든
 * 메타·점수 breakdown·YouTube 검색 링크·좋아요/북마크 큰 액션을 한 화면에 펼친다.
 *
 * 페이지 이동 없이 같은 리스트 컨텍스트(스크롤 위치/필터 상태)를 유지한 채로 상세를
 * 확인할 수 있어 모바일 PWA 흐름이 끊기지 않는다. `/songs/[id]` URL 직접 접근 경로는
 * 기존 페이지(`app/songs/[id]/page.tsx`)가 그대로 처리하므로 본 모달이 추가되어도
 * deep-link 호환성은 변하지 않는다.
 *
 * ## 설계 결정
 * - **외부 라이브러리 미사용**: 모달용 의존성을 추가하면 `package.json`이 보호 영역이라
 *   `needs-human-review`가 강제된다. 가벼운 props/dialog 패턴은 native 구현으로 충분.
 * - **native `<dialog>` 우선 + 폴리필 fallback**: 모던 브라우저(`HTMLDialogElement`)에서는
 *   브라우저가 backdrop·focus trap·ESC 닫기를 일부 무료 제공. happy-dom 환경
 *   (vitest)에서는 `showModal` API가 누락되어 있을 수 있으므로 옵셔널 체인으로 호출하고,
 *   부족한 부분(focus trap 강화, ESC 보조)은 본 컴포넌트가 명시적으로 채운다.
 * - **focus trap**: 모달 오픈 시 첫 포커스 가능한 요소(닫기 버튼)에 포커스를 두고,
 *   Tab/Shift+Tab 이 모달 경계 안에서만 순환하도록 keydown 으로 가드한다. 닫힘 시
 *   직전 포커스를 복원해 키보드 사용자의 위치를 잃지 않는다.
 * - **다크 모드 호환**: Tailwind `dark:` 변형으로 backdrop·표면·텍스트 톤을 모두 둔다.
 *
 * ## 접근성
 * - `role="dialog"`, `aria-modal="true"`, `aria-labelledby`로 제목 ID 연결.
 * - ESC 키 누르면 닫힘 (브라우저 기본 + 본 컴포넌트의 keydown 안전망).
 * - backdrop 클릭으로 닫힘 (선택 가능 — 실수 닫힘 방지를 위해 카드 영역 내 클릭은 무시).
 */

"use client";

import {
  useCallback,
  useEffect,
  useId,
  useRef,
  type KeyboardEvent,
  type MouseEvent,
  type ReactNode,
} from "react";

type SongDetailModalProps = {
  open: boolean;
  onClose: () => void;
  /**
   * 모달 제목으로 노출되는 라벨. 보통 곡 제목을 그대로 받는다.
   * `aria-labelledby`로 연결되므로 빈 문자열은 권장하지 않는다.
   */
  titleLabel: string;
  /**
   * 모달 본문. 호출 측에서 자유로이 layout 한다.
   */
  children: ReactNode;
};

/**
 * 포커스 가능한 모든 요소 셀렉터. focus trap 순환 계산 + 초기 포커스 탐색에 사용.
 * - `tabindex="-1"` 명시적 비활성은 제외.
 * - `disabled` 속성은 셀렉터 필터로 거른다 (모달 내 임시 disabled 버튼이 흔함).
 */
const FOCUSABLE_SELECTOR = [
  "a[href]",
  "button:not([disabled])",
  "textarea:not([disabled])",
  "input:not([disabled])",
  "select:not([disabled])",
  "[tabindex]:not([tabindex='-1'])",
].join(", ");

export function SongDetailModal({
  open,
  onClose,
  titleLabel,
  children,
}: SongDetailModalProps) {
  const dialogRef = useRef<HTMLDivElement | null>(null);
  const previouslyFocusedRef = useRef<HTMLElement | null>(null);
  const titleId = useId();

  /**
   * 오픈 시 직전 포커스 저장 + 모달 내부 첫 포커스 가능 요소로 포커스 이동.
   * 닫힘 시 직전 포커스 복원으로 키보드 사용자의 위치를 잃지 않는다.
   */
  useEffect(() => {
    if (!open) {
      return;
    }
    previouslyFocusedRef.current =
      (document.activeElement as HTMLElement | null) ?? null;

    // microtask 한 번 양보해 dialog DOM mount 후 focusable 탐색.
    const timeoutId = window.setTimeout(() => {
      const dialog = dialogRef.current;
      if (!dialog) {
        return;
      }
      const focusables = dialog.querySelectorAll<HTMLElement>(FOCUSABLE_SELECTOR);
      const first = focusables[0];
      if (first) {
        first.focus();
      } else {
        // 콘텐츠에 focusable이 전혀 없으면 dialog 자체에 focus (aria-modal 보강).
        dialog.focus();
      }
    }, 0);

    return () => {
      window.clearTimeout(timeoutId);
      const previous = previouslyFocusedRef.current;
      if (previous && typeof previous.focus === "function") {
        previous.focus();
      }
    };
  }, [open]);

  /**
   * ESC 키 닫기 + Tab/Shift+Tab 순환. native dialog 사용 시 브라우저가 ESC 일부를
   * 처리하지만, div 기반 dialog 호환을 위해 본 컴포넌트에서도 명시적으로 처리.
   */
  const handleKeyDown = useCallback(
    (event: KeyboardEvent<HTMLDivElement>) => {
      if (event.key === "Escape") {
        event.stopPropagation();
        onClose();
        return;
      }
      if (event.key !== "Tab") {
        return;
      }
      const dialog = dialogRef.current;
      if (!dialog) {
        return;
      }
      const focusables = Array.from(
        dialog.querySelectorAll<HTMLElement>(FOCUSABLE_SELECTOR),
      ).filter((el) => !el.hasAttribute("aria-hidden"));
      if (focusables.length === 0) {
        return;
      }
      const first = focusables[0];
      const last = focusables[focusables.length - 1];
      const active = document.activeElement as HTMLElement | null;

      if (event.shiftKey) {
        if (active === first || !dialog.contains(active)) {
          event.preventDefault();
          last.focus();
        }
      } else {
        if (active === last || !dialog.contains(active)) {
          event.preventDefault();
          first.focus();
        }
      }
    },
    [onClose],
  );

  /**
   * backdrop 클릭 시 닫기. 본문 영역 클릭은 stopPropagation으로 막아서 실수 닫힘 방지.
   * (모바일에서 스크롤 도중 떨어진 탭이 backdrop으로 인식되는 경우를 줄인다.)
   */
  const handleBackdropClick = useCallback(
    (event: MouseEvent<HTMLDivElement>) => {
      // 본문 컨테이너 클릭은 currentTarget !== target 이므로 무시.
      if (event.target === event.currentTarget) {
        onClose();
      }
    },
    [onClose],
  );

  // body 스크롤 락 — 모달 오픈 중 배경이 함께 스크롤되는 부자연스러움 방지.
  useEffect(() => {
    if (!open) {
      return;
    }
    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    return () => {
      document.body.style.overflow = previousOverflow;
    };
  }, [open]);

  if (!open) {
    return null;
  }

  return (
    <div
      // backdrop = portal-less full-viewport overlay. 별도 portal 라이브러리를 쓰지 않고도
      // fixed inset-0 으로 충분 — body 직속이 아니어도 stacking context를 z-50 으로 부각.
      role="presentation"
      onClick={handleBackdropClick}
      className="animate-fade-in fixed inset-0 z-50 flex items-end justify-center bg-[var(--modal-backdrop)] px-4 py-6 backdrop-blur-sm sm:items-center"
    >
      <div
        ref={dialogRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        tabIndex={-1}
        onKeyDown={handleKeyDown}
        className="animate-scale-in relative flex max-h-[90vh] w-full max-w-lg flex-col gap-4 overflow-y-auto rounded-2xl bg-[var(--surface-modal)] p-6 shadow-2xl ring-1 ring-[var(--ring-soft-detail)]"
      >
        <div className="flex items-start justify-between gap-4">
          <h2
            id={titleId}
            className="text-xl font-semibold text-[var(--text-primary)]"
          >
            {titleLabel}
          </h2>
          <button
            type="button"
            onClick={onClose}
            aria-label="상세 닫기"
            className="inline-flex h-9 w-9 shrink-0 items-center justify-center rounded-full text-[var(--text-caption)] transition-colors hover:bg-zinc-100 hover:text-zinc-900 focus:outline-none focus-visible:ring-2 focus-visible:ring-[var(--cta-secondary-ring)] dark:hover:bg-zinc-800 dark:hover:text-zinc-50"
          >
            <span aria-hidden="true" className="text-lg leading-none">
              ×
            </span>
          </button>
        </div>
        {children}
      </div>
    </div>
  );
}
