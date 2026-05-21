import Link from "next/link";

export default function Home() {
  return (
    <main className="flex flex-1 flex-col items-center justify-center bg-zinc-50 px-6 py-16 dark:bg-zinc-950">
      <div className="w-full max-w-md flex flex-col items-center gap-8 text-center">
        <div className="space-y-3">
          <p className="text-sm font-medium uppercase tracking-widest text-zinc-500 dark:text-zinc-400">
            mobruji
          </p>
          <h1 className="text-3xl font-semibold leading-tight text-zinc-900 dark:text-zinc-50 sm:text-4xl">
            오늘 노래방, 뭐 부르지?
          </h1>
          <p className="text-base text-zinc-600 dark:text-zinc-400">
            내 음역대만 알려주면, 부르기 편한 곡을 추천해드려요.
          </p>
        </div>

        <div className="flex w-full flex-col gap-3">
          <Link
            href="/voice-range"
            className="inline-flex h-12 w-full items-center justify-center rounded-full bg-zinc-900 px-6 text-base font-medium text-white transition-colors hover:bg-zinc-700 dark:bg-zinc-50 dark:text-zinc-900 dark:hover:bg-zinc-200"
          >
            음역대 입력으로 시작
          </Link>
          <Link
            href="/songs"
            className="inline-flex h-12 w-full items-center justify-center rounded-full border border-zinc-300 bg-white px-6 text-base font-medium text-zinc-900 transition-colors hover:bg-zinc-100 dark:border-zinc-700 dark:bg-zinc-900 dark:text-zinc-50 dark:hover:bg-zinc-800"
          >
            곡 검색하기
          </Link>
        </div>

        <p className="text-xs text-zinc-500 dark:text-zinc-500">
          익명 세션으로 동작합니다. 회원가입 없음.
        </p>
      </div>
    </main>
  );
}
