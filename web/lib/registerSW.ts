/**
 * Service Worker 등록 유틸.
 *
 * - 브라우저가 serviceWorker를 지원하지 않으면 no-op.
 * - production 빌드에서만 등록 (개발 시 HMR과 충돌 방지).
 * - 실패는 console.warn으로 노출하고 throw하지 않는다(앱 자체는 정상 동작).
 *
 * 호출 시점: 클라이언트 마운트 직후 (RootLayout의 클라이언트 컴포넌트).
 */

export const SERVICE_WORKER_PATH = "/sw.js";

export type RegisterServiceWorkerOptions = {
  /** 강제로 production 체크를 우회 (테스트 용도). */
  readonly force?: boolean;
};

export async function registerServiceWorker(
  options: RegisterServiceWorkerOptions = {},
): Promise<ServiceWorkerRegistration | null> {
  const force = options.force ?? false;

  if (typeof window === "undefined") {
    return null;
  }

  if (!("serviceWorker" in navigator)) {
    return null;
  }

  if (!force && process.env.NODE_ENV !== "production") {
    return null;
  }

  try {
    const registration = await navigator.serviceWorker.register(
      SERVICE_WORKER_PATH,
      {
        scope: "/",
      },
    );
    return registration;
  } catch (error) {
    // eslint-disable-next-line no-console
    console.warn("[mobruji] service worker registration failed", error);
    return null;
  }
}
