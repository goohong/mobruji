# 런북 — fe(web) 의존성 개발 환경 진단 / 복구

> 관련 이슈: #439 (최초 진단), #736 (본 spec 갱신)
> 적용 대상: `mobruji-fe` worktree (`/home/mobruji/mobruji-fe/web`)
> 최초 작성: 2026-05-23
> 최종 갱신: 2026-05-23

## 0) TL;DR

- **증상**: `npm test` (vitest run) 가 `ERR_MODULE_NOT_FOUND: '@vitest/utils'` 로 fail.
- **잘못된 진단** (2026-05-23 초기): vitest 4.x bug / 다운그레이드 / overrides / mount 폐기. **모두 폐기.**
- **실제 원인**: 외부 `node_modules` 가 **symlink** 로 노출되었고, Node ESM resolver 가 **동일 패키지를 두 가지 realpath 로 중복 해석**해 `@vitest/utils` 의 subpath import 가 무효화됨.
- **실제 해결**: 외부 target 디렉토리 **이름 자체를 rename** (`/data/node_modules/fe-web/` → `/data/node_modules/`)해 symlink resolution 의 realpath 일관성을 확보. 이후 `npm ci` 재실행.
- **임시 우회**: `NODE_OPTIONS=--preserve-symlinks` (PR #734). 정상화 이후 cleanup 후보.

## 1) 배경

fe 워크트리에서 `npm test` (vitest run) 실행 시 다음 에러로 fail.

```
Error [ERR_MODULE_NOT_FOUND]: Cannot find package '@vitest/utils' imported from /data/node_modules/fe-web/vitest/dist/cli.js
```

`npm cache clean --force` + `rm -rf node_modules` + `npm ci` 반복 후에도 동일 재현. fe sub-agent 들이 품질 게이트 fail 로 작업 시간을 낭비해 진단 spec 신설.

## 2) 환경 토폴로지

NCP 인스턴스(또는 로컬 다중 워크트리)는 디스크 공간 절약을 위해 워크트리별 `node_modules` 를 **외부 디스크 + symlink** 로 운영한다.

| 항목 | 값 |
| --- | --- |
| Node.js | v22.22.2 |
| npm | 10.9.7 |
| 워크트리 내부 경로 | `/home/mobruji/mobruji-fe/web/node_modules` |
| 외부 실제 경로 (구) | `/data/node_modules/fe-web/` |
| 외부 실제 경로 (신, 해결 후) | `/data/node_modules/` |
| `/data` 파일시스템 | XFS (20GB, /dev/vdb) |
| 노출 방식 | `web/node_modules` → 외부 경로 symlink |

## 3) 실제 원인 — symlink + 디렉토리 이름 충돌

Node.js ESM resolver 는 import 의 base URL 을 `realpath` 로 normalize 한 뒤 패키지 lookup 을 수행한다. `/data/node_modules/fe-web/` 라는 **외부 이름이 워크트리 내부 `node_modules` 와 다른 prefix** 를 가지면 다음이 발생한다.

1. `vitest/dist/cli.js` 는 `web/node_modules` 를 통해 import 됨 → resolver 가 본인 위치를 `/data/node_modules/fe-web/vitest/dist/cli.js` 로 `realpath` 해석.
2. cli.js 가 `@vitest/utils` 를 import → resolver 가 `/data/node_modules/fe-web/` 에서 `@vitest/utils` 를 찾으려 함.
3. 그런데 일부 transitive 의존성은 워크트리 prefix 로 hoist 되어 두 가지 realpath 가 공존, ESM resolver 의 패키지 식별 (name + realpath) 이 깨져 `subpath exports` lookup 실패.

→ ESM resolver 가 `@vitest/utils` 의 subpath (예: `@vitest/utils/helpers`) 를 못 찾는 것으로 표면화. 이전에 의심한 vitest 4.x bug / subpath exports 누락 / 버전 불일치는 **모두 무관**했다.

## 4) 실제 해결 — 외부 target 디렉토리 rename

워크트리 내부 `node_modules` 가 가리키는 외부 target 의 **이름 자체를 단순화**해 symlink resolution 의 realpath 일관성을 확보.

```bash
# (구) /data/node_modules/fe-web/  →  (신) /data/node_modules/
mv /data/node_modules/fe-web /tmp/_old-fe-web   # backup
# 또는 직접 rename 후 워크트리 symlink 재연결
```

이후 워크트리에서:

```bash
cd /home/mobruji/mobruji-fe/web
rm -rf node_modules
ln -s /data/node_modules node_modules
npm ci
npm test   # → PASS
```

본 변경은 **fe 워크트리 시스템 설정**(symlink target) 변경이며, repo 코드 변경 없이 해결된다. NCP 인스턴스 provisioning 스크립트가 있다면 거기 반영 필요.

## 5) 폐기된 진단 후보 (참고용 — 적용 금지)

초기 진단에서 제시됐으나 모두 실제 원인과 무관했음. 동일 함정 재발 방지 목적으로만 남긴다.

| 후보 | 내용 | 폐기 사유 |
| --- | --- | --- |
| A | vitest 4.x → 3.x 다운그레이드 | vitest 자체 bug 아님. ESM resolver 의 realpath 문제. |
| B | `npm overrides` 로 `@vitest/utils` 강제 hoist | hoist 위치와 무관. 두 realpath 가 공존하는 한 동일 fail. |
| C | `/data` mount 정책 폐기 + 워크트리 로컬 `node_modules` 복귀 | symlink 자체를 없애면 해결되지만, target rename 만으로 충분하므로 과한 변경. |
| D | pnpm 전환 (장기) | 별 ADR 으로 검토 가능하나 본 fail 의 해결책은 아님. |

## 6) 임시 우회 — `NODE_OPTIONS=--preserve-symlinks`

PR #734 (`fix(web): NODE_OPTIONS=--preserve-symlinks 4 scripts 추가`) 가 머지된 시점은 root cause 파악 전이었다. `--preserve-symlinks` 는 resolver 가 realpath 변환을 생략하게 만들어 두 realpath 가 분리되지 않게 한다.

- 실제 해결 (§4) 적용 후 `--preserve-symlinks` 는 **불필요**.
- cleanup 후보: `web/package.json` 4 scripts 에서 `NODE_OPTIONS=--preserve-symlinks` 제거.
- 단, `web/package.json` 은 **보호 영역**이므로 cleanup PR 시 `needs-human-review` 라벨 필수.

## 7) 후속 작업

- [ ] NCP provisioning 스크립트에 `/data/node_modules/` 표준 경로 반영
- [ ] `web/package.json` `NODE_OPTIONS=--preserve-symlinks` 제거 cleanup PR (보호 영역, `needs-human-review`)
- [ ] 신규 워크트리 (be/rev/plan) 의 `node_modules` symlink 도 동일 구조인지 확인 — 다른 워크트리는 vitest 실행 안 하므로 영향 없을 가능성 높음

## 8) 참고

- Node.js ESM resolver: https://nodejs.org/api/esm.html#resolution-algorithm
- `--preserve-symlinks`: https://nodejs.org/api/cli.html#--preserve-symlinks
- 관련 PR: #442 (최초 spec), #734 (우회 적용), #736 (본 spec 갱신)
