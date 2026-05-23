# 런북 — fe(web) 의존성 개발 환경 진단

> 관련 이슈: #439
> 적용 대상: `mobruji-fe` worktree (`/home/mobruji/mobruji-fe/web`)
> 작성일: 2026-05-23

## 1) 배경

fe 워크트리에서 `npm test` (vitest run) 실행 시 다음 에러로 fail.

```
Error [ERR_MODULE_NOT_FOUND]: Cannot find package '@vitest/utils' imported from /data/node_modules/fe-web/vitest/dist/cli.js
```

`npm cache clean --force` + `rm -rf node_modules` + `npm ci` 반복 후에도 동일 재현. fe sub-agent 들이 품질 게이트 fail 로 작업 시간 낭비 중이라 진단 spec 신설.

## 2) 환경 토폴로지

NCP 인스턴스(또는 로컬 다중 워크트리)는 디스크 공간 절약을 위해 워크트리별 `node_modules` 를 **외부 디스크에 별도 저장**한다. 본인 자체 점검 결과:

| 항목 | 값 |
| --- | --- |
| Node.js | v22.22.2 |
| npm | 10.9.7 |
| `web/node_modules` 실제 경로 | `/data/node_modules/fe-web/` |
| `/data` 파일시스템 | XFS (20GB, /dev/vdb) |
| vitest binary | `/data/node_modules/fe-web/vitest/dist/cli.js` |

`web/node_modules` 자체는 디렉토리지만, 그 하위 패키지들이 `/data/node_modules/fe-web/` 로 redirect 되는 구조다. (npm `--prefix` 또는 mount 설정 추정 — `.npmrc` 는 부재하므로 외부 install 스크립트 / docker volume 가능성 점검 필요.)

## 3) 재현 절차

```bash
cd /home/mobruji/mobruji-fe/web
npm test
# → ERR_MODULE_NOT_FOUND: '@vitest/utils' from vitest/dist/cli.js
```

## 4) 진단 절차

### 4-1) 패키지 존재 확인

```bash
ls /data/node_modules/fe-web/@vitest/   # utils, expect, runner, ... 모두 존재
ls /data/node_modules/fe-web/@vitest/utils/
cat /data/node_modules/fe-web/@vitest/utils/package.json | jq '.version, .exports'
```

`@vitest/utils` 디렉토리 자체는 존재한다. ESM resolver 가 못 찾는다는 것은 다음 중 하나:

1. **subpath exports 누락** — vitest 가 `@vitest/utils/helpers` 를 import 하지만 utils 의 `package.json` `exports` 필드에 `./helpers` 정의 없음.
2. **버전 불일치** — vitest 4.1.7 이 요구하는 `@vitest/utils` 버전과 hoist 된 버전 불일치 (peer 강제).
3. **nested node_modules 충돌** — `/data/node_modules/fe-web/vitest/node_modules/` 에 자체 의존성 있을 시 resolver 가 거기서 우선 탐색.

### 4-2) vitest 자체 의존성 트리

```bash
cd /home/mobruji/mobruji-fe/web
npm ls vitest @vitest/utils 2>&1 | head -20
ls /data/node_modules/fe-web/vitest/node_modules/ 2>&1
```

### 4-3) ESM resolver dry run

```bash
cd /home/mobruji/mobruji-fe/web
node --input-type=module -e "import('@vitest/utils/helpers').then(m => console.log('OK', Object.keys(m)))" 2>&1
```

OK 면 vitest 자체 bug, fail 면 install 구조 문제.

### 4-4) package-lock 무결성

```bash
cd /home/mobruji/mobruji-fe/web
npm ci --dry-run 2>&1 | tail -20
# 또는
npm audit signatures 2>&1 | tail -20
```

## 5) 복구 후보 (결정 대기)

본 런북은 진단 + 후보 제시만 한다. 실제 적용은 사용자 / 별 PR.

### 후보 A — vitest 4.1.x 핫픽스 대기 / 4.0.x 다운그레이드
- vitest 4.x 가 출시된 지 얼마 안 되었으므로 4.0.x LTS 라인이 안정적일 가능성.
- `web/package.json` 의 `"vitest": "^4.1.7"` → `"vitest": "^3.2.0"` 등 다운그레이드 시도.
- 위험: vitest 4.x API 변경 사용 코드 있으면 회귀.

### 후보 B — npm `overrides` 로 `@vitest/utils` 강제 hoist
```json
{
  "overrides": {
    "@vitest/utils": "$@vitest/utils 의 vitest 4.1.7 peer 버전"
  }
}
```

### 후보 C — `/data` mount 정책 폐기, 워크트리 로컬 `node_modules` 복귀
- NCP 디스크 공간 점검 후 가능 시 가장 안전.
- 단점: 워크트리 4개 x ~500MB = 2GB 추가.

### 후보 D — pnpm 전환 (장기)
- pnpm 의 content-addressable store 가 디스크 절약 + symlink 정합성 보장.
- 별 ADR 필요. 단기 fix 아님.

## 6) 즉시 적용 우회 (sub-agent 게이트 통과용)

품질 게이트 통과가 시급하면 다음 우회:

```bash
cd /home/mobruji/mobruji-fe/web
npm test -- --reporter=verbose 2>&1 | tee /tmp/test-output.log
# vitest 가 실행 자체 못 하면 fe sub-agent 는 게이트 통과 보고 시 본 런북 #439 링크로 wait
```

또는 PR description 에 `[blocked-by #439]` 명시하고 게이트 fail 인 채 머지 대기.

## 7) 후속 작업

- [ ] 4-1 ~ 4-4 진단 결과를 #439 코멘트로 누적
- [ ] 후보 A~D 중 사용자 결정
- [ ] 결정된 후보 별 PR (scope: web 또는 infra)
- [ ] fe 사이클 게이트 통과 확인 후 본 런북 §6 우회 절차 삭제

## 8) 참고

- vitest 4.x 릴리즈 노트: https://github.com/vitest-dev/vitest/releases
- Node.js ESM resolver: https://nodejs.org/api/esm.html#resolution-algorithm
- npm `overrides`: https://docs.npmjs.com/cli/v10/configuring-npm/package-json#overrides
