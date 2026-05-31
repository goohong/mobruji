#!/usr/bin/env python3
"""directive forum starter 본문의 상태 전이를 '내용 보존형'으로 수술 갱신한다 (#1419).

배경: 과거 discord-reply.sh --update-status 는 forum starter message 본문을
"**상태**: X / **갱신**: Y" 2~3 줄로 통째 PATCH 했다. 그 결과 📌 제목, 💬 요약,
🔖 관련 등 사용자/helper 가 작성한 내용이 매 상태 전이마다 소멸했다
(2026-05-31 사용자 정정: "내용 다 죽이고 완료라고 하면 뭐해").

본 스크립트는 기존 starter 본문(stdin)을 읽어:
  - `📋 진행 (...)` 헤더의 상태만 새 상태로 교체
  - `완료` 시 체크박스(- [ ])를 모두 체크(- [x])
  - PR URL 이 주어지면 `🔖 관련` 섹션에 PR 줄 보강(중복 방지)
  - 말미 `_갱신: ..._` 줄 갱신(없으면 추가)
하여 나머지 내용(제목/요약/관련/사용자 추가분)은 전부 보존한다.

템플릿 마커(`📋 진행`)가 없는 legacy/빈 본문이면 기존 내용을 `💬 요약` 으로
보존한 채 상태 섹션을 새로 구성한다.

입력:  stdin = 현재 starter content
환경:  STATUS (필수, 한국어: 대기|진행 중|완료|취소|차단|결정 대기)
       TS     (필수, KST timestamp 문자열)
       PR     (선택, PR URL)
출력:  stdout = 갱신된 starter content
"""
from __future__ import annotations

import os
import re
import sys

# 본문 템플릿(build_template_body)과 동일한 진행 상태 emoji 컨벤션.
STATUS_EMOJI = {
    "대기": "🟡",
    "진행 중": "🔵",
    "완료": "🟢",
    "취소": "❌",
    "차단": "🚫",
    "결정 대기": "🟣",
}


def transform(content: str, status: str, ts: str, pr: str) -> str:
    emoji = STATUS_EMOJI.get(status, "🔵")
    status_disp = f"{emoji} {status}"
    pr = (pr or "").strip()

    if "📋 진행" in content:
        # 1) 진행 헤더 상태 교체 (첫 매칭만).
        content = re.sub(
            r"📋 진행 \([^)]*\)", f"📋 진행 ({status_disp})", content, count=1
        )
        # 2) 완료 시 체크박스 전부 체크.
        if status == "완료":
            content = content.replace("- [ ]", "- [x]")
        # 3) PR 줄 보강 (🔖 관련 섹션).
        if pr and pr not in content:
            no_rel = re.search(r"🔖 관련 \*\(없음[^\n]*\)\*", content)
            if no_rel:
                content = content.replace(no_rel.group(0), f"🔖 관련\n- PR: {pr}", 1)
            else:
                content = re.sub(
                    r"(🔖 관련[^\n]*\n)", rf"\1- PR: {pr}\n", content, count=1
                )
        # 4) 갱신 줄 갱신(없으면 말미 추가).
        if re.search(r"_갱신:[^\n]*_", content):
            content = re.sub(
                r"_갱신:[^\n]*_", f"_갱신: {ts} · 상태 갱신_", content, count=1
            )
        else:
            content = content.rstrip() + f"\n\n---\n_갱신: {ts} · 상태 갱신_"
        return content

    # legacy / 빈 본문 — 기존 내용을 요약으로 보존하고 상태 섹션 신규 구성.
    original = content.strip()
    summary_block = f"💬 요약\n{original}\n\n" if original else ""
    pr_block = f"\n🔖 관련\n- PR: {pr}" if pr else ""
    return (
        f"{summary_block}"
        f"📋 진행 ({status_disp})"
        f"{pr_block}\n\n"
        f"---\n_갱신: {ts} · 상태 갱신_"
    )


def main() -> int:
    content = sys.stdin.read()
    status = os.environ.get("STATUS", "").strip()
    ts = os.environ.get("TS", "").strip()
    pr = os.environ.get("PR", "")
    if not status:
        sys.stderr.write("directive_starter_status: STATUS env 필수\n")
        return 1
    sys.stdout.write(transform(content, status, ts, pr))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
