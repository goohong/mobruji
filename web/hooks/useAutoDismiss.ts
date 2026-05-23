/**
 * 일정 시간 후 콜백을 한 번 실행하는 공용 hook.
 *
 * 이슈 #295 항목 1 (rev 19 후속): SongCard / SongDetailContent 의
 * Like / Bookmark 버튼이 각각 거의 동일한 `useEffect + setTimeout` 으로
 * "errorMessage 3초 뒤 자동 dismiss" 패턴을 복붙 사용했다.
 * 향후 공유·신고 등 카드 인터랙션에서 같은 피드백이 더 늘어날 가능성이
 * 있어 단일 hook 으로 모았다.
 *
 * 동작:
 *   - `enabled === false` 면 타이머 등록 자체를 안 함 (no-op).
 *   - `enabled === true` 면 마운트 / `delayMs` 변화 / `enabled` 변화 / callback 변화
 *     시점에 타이머를 다시 건다.
 *   - 언마운트 또는 의존성 변경 시 `clearTimeout` 으로 정리.
 *
 * 호출 측 패턴:
 *   ```ts
 *   const [errorMessage, setErrorMessage] = useState<string | null>(null);
 *   useAutoDismiss(() => setErrorMessage(null), DURATION_MS, errorMessage !== null);
 *   ```
 *
 * 주의:
 *   - callback 은 매 렌더 새로 생성되어도 안전 — 타이머가 새 callback 으로
 *     다시 걸린다. 호출 측 의도(메시지 변경 시점부터 다시 카운트)와 일치한다.
 *   - SSR 환경에서는 effect 가 실행되지 않으므로 `window` 가드 불필요.
 */

import { useEffect } from "react";

export function useAutoDismiss(
  callback: () => void,
  delayMs: number,
  enabled: boolean,
): void {
  useEffect(() => {
    if (!enabled) {
      return;
    }
    const timeoutId = window.setTimeout(() => {
      callback();
    }, delayMs);
    return () => {
      window.clearTimeout(timeoutId);
    };
  }, [callback, delayMs, enabled]);
}
