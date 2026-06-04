"use client";

/**
 * 회원가입 화면 (`/signup`, closes #1800).
 *
 * 이메일+비밀번호로 POST /api/v1/users/signup 을 호출하고, 성공 시 발급 토큰/프로필을
 * auth store(setSession)에 영속한다(가입 즉시 로그인 상태). 음역대·성별은 선택 입력
 * 이라 가입 화면에서는 받지 않고, 기존 익명 흐름(측정·추천)을 그대로 둔다 — 가입은
 * 익명 세션을 건드리지 않으므로 측정 기록도 유지된다.
 *
 * 성공하면 선택형 온보딩("/onboarding", closes #1814)으로 보내 나이대·성별·분위기를
 * (각 스킵 가능) 한 번 받아 추천 기본값으로 영속한다.
 */

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useMutation } from "@tanstack/react-query";

import { signup, type AuthResponse, type SignupRequest } from "@/lib/api/auth";
import { ApiError } from "@/lib/api/client";
import { useAuthStore } from "@/store/auth";
import { AuthForm, type AuthFormValues } from "@/app/components/AuthForm";

export default function SignupPage() {
  const router = useRouter();
  const setSession = useAuthStore((state) => state.setSession);

  const mutation = useMutation<AuthResponse, Error, SignupRequest>({
    mutationFn: (request) => signup(request),
    onSuccess: (session) => {
      setSession(session);
      router.push("/onboarding");
    },
  });

  function handleSubmit({ email, password }: AuthFormValues) {
    mutation.mutate({ email, password });
  }

  return (
    <AuthForm
      heading="회원가입"
      description="이메일과 비밀번호만 있으면 가입할 수 있어요. 측정한 음역대는 그대로 유지됩니다."
      submitLabel="회원가입"
      submittingLabel="가입 중..."
      passwordAutoComplete="new-password"
      loading={mutation.isPending}
      error={toSignupError(mutation.error)}
      onSubmit={handleSubmit}
      footer={
        <p>
          이미 계정이 있으신가요?{" "}
          <Link
            href="/login"
            className="font-medium text-[var(--brand-600)] underline-offset-2 hover:underline"
          >
            로그인
          </Link>
        </p>
      }
    />
  );
}

/** 이메일 중복(409)은 사용자 친화 문구로, 그 외 오류는 메시지를 그대로 노출한다. */
function toSignupError(error: Error | null): string | null {
  if (error === null) {
    return null;
  }
  if (error instanceof ApiError && error.status === 409) {
    return "이미 가입된 이메일입니다. 로그인해주세요.";
  }
  return `회원가입에 실패했습니다. ${error.message}`;
}
