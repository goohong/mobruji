/**
 * 일정 시간 후 메시지를 자동으로 사라지게 하는 hook (rev 19 / #295 항목 1 후속).
 *
 * SongCard 의 LikeButton / BookmarkButton 이 거의 동일한 `useState + useEffect + setTimeout`
 * 패턴으로 인터랙션 에러 안내를 자동 dismiss 시키고 있었다. 향후 공유/신고 등 다른
 * 카드 인터랙션 피드백에도 같은 동작이 필요할 가능성이 커, 한 곳에 추출해 재사용한다.
 *
 * 동작:
 *  - `message` 가 truthy 로 바뀌면 `durationMs` 뒤에 자동 클리어.
 *  - 동작 중 새 메시지가 세팅되면 이전 타이머는 cleanup 으로 취소되고 새 타이머가 걸린다.
 *  - 사용자가 새 시도를 시작해 즉시 클리어해야 하는 경우 `clear()` 를 직접 호출한다.
 *  - SSR/test 환경에서 `window` 가 없으면 noop (등록/해제 모두 건너뜀).
 *
 * 호출 측 사용 예:
 *   const { message, setMessage, clear } = useAutoDismissMessage(3000);
 *   setMessage("실패했어요"); // 3초 뒤 자동으로 null
 */

import { useCallback, useEffect, useState } from "react";

export type UseAutoDismissMessageResult = {
  message: string | null;
  setMessage: (next: string | null) => void;
  clear: () => void;
};

export function useAutoDismissMessage(
  durationMs: number,
): UseAutoDismissMessageResult {
  const [message, setMessage] = useState<string | null>(null);

  useEffect(() => {
    if (!message) {
      return;
    }
    if (typeof window === "undefined") {
      return;
    }
    const timeoutId = window.setTimeout(() => {
      setMessage(null);
    }, durationMs);
    return () => {
      window.clearTimeout(timeoutId);
    };
  }, [message, durationMs]);

  const clear = useCallback(() => {
    setMessage(null);
  }, []);

  return { message, setMessage, clear };
}
