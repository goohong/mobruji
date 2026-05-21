/**
 * 오프라인 fallback 페이지.
 *
 * Service Worker가 navigation 요청 실패 시 이 페이지를 응답한다.
 * 네트워크가 복구되면 사용자가 다시 시도하도록 안내한다.
 */

import type { Metadata } from "next";

export const metadata: Metadata = {
  title: "오프라인 — 모부르지",
  description: "네트워크 연결이 끊어졌습니다.",
};

export default function OfflinePage() {
  return (
    <main
      className="flex flex-1 flex-col items-center justify-center gap-4 px-6 py-12 text-center"
      aria-labelledby="offline-heading"
    >
      <h1 id="offline-heading" className="text-2xl font-bold">
        오프라인 상태입니다
      </h1>
      <p className="text-sm text-gray-600">
        네트워크 연결을 확인한 뒤 새로고침 해주세요.
      </p>
    </main>
  );
}
