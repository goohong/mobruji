"use client";

/**
 * React Query QueryClientProvider 셋업.
 *
 * ADR 0004 결정에 따라 서버 상태는 TanStack Query로 일원화한다.
 * App Router 환경에서 클라이언트 컴포넌트로 분리하고 layout.tsx에서 래핑한다.
 */

import { ReactNode, useState } from "react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";

type ProvidersProps = {
  children: ReactNode;
};

export function Providers({ children }: ProvidersProps) {
  const [queryClient] = useState(
    () =>
      new QueryClient({
        defaultOptions: {
          queries: {
            retry: 1,
            refetchOnWindowFocus: false,
            staleTime: 30_000,
          },
          mutations: {
            retry: 0,
          },
        },
      }),
  );

  return (
    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  );
}
