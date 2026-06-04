"use client";

/**
 * 로그인 화면 (`/login`, closes #1800).
 *
 * 이메일+비밀번호로 POST /api/v1/users/login 을 호출하고, 성공 시 발급 토큰/프로필을
 * auth store(setSession)에 영속한다. 이후 모든 apiFetch 가 Authorization Bearer 를
 * 자동 첨부하고(재방문 시 AuthSessionRestorer 가 자동로그인), 익명 세션은 건드리지
 * 않아 그대로 유지된다.
 *
 * 성공하면 홈("/")으로 라우팅한다.
 */

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useMutation } from "@tanstack/react-query";

import { login, type AuthResponse, type LoginRequest } from "@/lib/api/auth";
import { ApiError } from "@/lib/api/client";
import { useAuthStore } from "@/store/auth";
import { AuthForm, type AuthFormValues } from "@/app/components/AuthForm";

export default function LoginPage() {
  const router = useRouter();
  const setSession = useAuthStore((state) => state.setSession);

  const mutation = useMutation<AuthResponse, Error, LoginRequest>({
    mutationFn: (request) => login(request),
    onSuccess: (session) => {
      setSession(session);
      router.push("/");
    },
  });

  function handleSubmit({ email, password }: AuthFormValues) {
    mutation.mutate({ email, password });
  }

  return (
    <AuthForm
      heading="로그인"
      description="가입한 이메일과 비밀번호로 로그인하면 음역대·취향이 기기를 옮겨도 따라옵니다."
      submitLabel="로그인"
      submittingLabel="로그인 중..."
      passwordAutoComplete="current-password"
      loading={mutation.isPending}
      error={toLoginError(mutation.error)}
      onSubmit={handleSubmit}
      footer={
        <p>
          아직 계정이 없으신가요?{" "}
          <Link
            href="/signup"
            className="font-medium text-[var(--brand-600)] underline-offset-2 hover:underline"
          >
            회원가입
          </Link>
        </p>
      }
    />
  );
}

/** 자격 불일치(401)는 사용자 친화 문구로, 그 외 오류는 메시지를 그대로 노출한다. */
function toLoginError(error: Error | null): string | null {
  if (error === null) {
    return null;
  }
  if (error instanceof ApiError && error.status === 401) {
    return "이메일 또는 비밀번호가 올바르지 않습니다.";
  }
  return `로그인에 실패했습니다. ${error.message}`;
}
