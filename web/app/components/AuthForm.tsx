"use client";

/**
 * 회원가입/로그인 공통 폼 (이메일 + 비밀번호) — closes #1800.
 *
 * 두 화면(/signup, /login)이 이메일·비밀번호 입력·제출·에러 표시를 동일하게 가지므로
 * presentational 폼을 한곳에 모은다. 제출 동작(어떤 API 를 호출하고 성공 후 어디로
 * 보낼지)은 호출 페이지가 `onSubmit` 으로 주입한다 — 폼은 입력값만 모아 넘긴다.
 *
 * - 이메일은 `type="email"` + required, 비밀번호는 `type="password"` + required 로
 *   브라우저 기본 검증에 맡긴다(서버가 이메일 중복 409 / 자격 불일치 401 을 최종 판정).
 * - 제출 중에는 버튼이 loading(disabled + aria-busy)으로 더블 submit 을 막는다.
 * - 에러 메시지는 `role="alert"` 로 SR 사용자에게 즉시 announce 한다.
 */

import { FormEvent, useState, type ReactNode } from "react";

import { Button, Input } from "@/components/ui";

export type AuthFormValues = {
  email: string;
  password: string;
};

type AuthFormProps = {
  heading: string;
  description?: ReactNode;
  submitLabel: string;
  submittingLabel: string;
  passwordAutoComplete: "current-password" | "new-password";
  loading: boolean;
  error: string | null;
  onSubmit: (values: AuthFormValues) => void;
  footer?: ReactNode;
};

const AUTH_FORM_ERROR_ID = "auth-form-error";

export function AuthForm({
  heading,
  description,
  submitLabel,
  submittingLabel,
  passwordAutoComplete,
  loading,
  error,
  onSubmit,
  footer,
}: AuthFormProps) {
  const [email, setEmail] = useState<string>("");
  const [password, setPassword] = useState<string>("");

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    onSubmit({ email, password });
  }

  return (
    <main className="flex flex-1 flex-col items-center bg-[var(--bg-subtle)] px-[var(--page-padding-x)] py-[var(--page-padding-y)]">
      <div className="w-full max-w-md flex flex-col gap-8">
        <header className="space-y-2">
          <h1 className="text-2xl font-semibold text-[var(--text-primary)]">
            {heading}
          </h1>
          {description ? (
            <p className="text-sm text-[var(--text-secondary)]">{description}</p>
          ) : null}
        </header>

        <form
          onSubmit={handleSubmit}
          className="flex flex-col gap-5 rounded-[var(--radius-lg)] bg-[var(--bg-base)] p-6 shadow-[var(--shadow-sm)] ring-1 ring-[var(--border)]"
        >
          <Input
            label="이메일"
            type="email"
            name="email"
            autoComplete="email"
            required
            value={email}
            onChange={(event) => setEmail(event.target.value)}
            placeholder="you@example.com"
          />
          <Input
            label="비밀번호"
            type="password"
            name="password"
            autoComplete={passwordAutoComplete}
            required
            value={password}
            onChange={(event) => setPassword(event.target.value)}
            placeholder="비밀번호"
          />

          {error ? (
            <p
              id={AUTH_FORM_ERROR_ID}
              role="alert"
              className="text-sm text-[var(--danger-fg-soft)]"
            >
              {error}
            </p>
          ) : null}

          <Button
            type="submit"
            variant="primary"
            size="lg"
            fullWidth
            loading={loading}
          >
            {loading ? submittingLabel : submitLabel}
          </Button>
        </form>

        {footer ? (
          <div className="text-center text-sm text-[var(--text-secondary)]">
            {footer}
          </div>
        ) : null}
      </div>
    </main>
  );
}
