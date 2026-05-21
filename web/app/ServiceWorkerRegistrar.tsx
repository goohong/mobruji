"use client";

/**
 * 클라이언트 마운트 시 Service Worker를 등록한다.
 * 렌더링은 하지 않는다(부수 효과 전용).
 */

import { useEffect } from "react";

import { registerServiceWorker } from "@/lib/registerSW";

export function ServiceWorkerRegistrar() {
  useEffect(() => {
    void registerServiceWorker();
  }, []);

  return null;
}
