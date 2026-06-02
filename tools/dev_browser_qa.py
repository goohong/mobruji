#!/usr/bin/env python3
"""CDP (Chrome DevTools Protocol) WebSocket wrapper — python stdlib only.

dev-browser-qa.sh test-darkmode 가 호출. light vs dark mode 의 page visit + computed style +
screenshot 비교로 다크모드 적용 여부 evidence 박제.

spec: docs/features/dev-browser-qa-env.md §3-3 (test-darkmode 흐름)
"""
from __future__ import annotations

import argparse
import base64
import json
import os
import socket
import struct
import sys
import time
import urllib.request


def ws_connect(url: str) -> socket.socket:
    """RFC 6455 client handshake — websocket-client 의존 회피."""
    assert url.startswith("ws://"), url
    host_port, _, path = url[5:].partition("/")
    host, port = host_port.split(":")
    s = socket.create_connection((host, int(port)))
    key = base64.b64encode(os.urandom(16)).decode()
    req = (
        f"GET /{path} HTTP/1.1\r\nHost: {host_port}\r\n"
        f"Upgrade: websocket\r\nConnection: Upgrade\r\n"
        f"Origin: http://localhost\r\n"
        f"Sec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n\r\n"
    )
    s.send(req.encode())
    buf = b""
    while b"\r\n\r\n" not in buf:
        buf += s.recv(4096)
    return s


def ws_send(s: socket.socket, msg: str) -> None:
    data = msg.encode()
    if len(data) < 126:
        header = bytes([0x81, 0x80 | len(data)])
    elif len(data) < 65536:
        header = bytes([0x81, 0x80 | 126]) + struct.pack(">H", len(data))
    else:
        header = bytes([0x81, 0x80 | 127]) + struct.pack(">Q", len(data))
    mask = os.urandom(4)
    s.send(header + mask + bytes(b ^ mask[i % 4] for i, b in enumerate(data)))


def ws_recv_one(s: socket.socket, timeout: float = 10) -> str | None:
    s.settimeout(timeout)
    header = s.recv(2)
    if len(header) < 2:
        return None
    plen = header[1] & 0x7F
    if plen == 126:
        plen = struct.unpack(">H", s.recv(2))[0]
    elif plen == 127:
        plen = struct.unpack(">Q", s.recv(8))[0]
    data = b""
    while len(data) < plen:
        chunk = s.recv(min(8192, plen - len(data)))
        if not chunk:
            break
        data += chunk
    return data.decode("utf-8", errors="replace")


def cdp(s: socket.socket, method: str, params: dict | None = None, id_: int = 1, timeout: float = 10) -> dict:
    """id 일치하는 응답만 반환 — CDP browser event 무시."""
    msg = {"id": id_, "method": method}
    if params:
        msg["params"] = params
    ws_send(s, json.dumps(msg))
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            r = ws_recv_one(s, max(0.5, deadline - time.time()))
            if not r:
                continue
            d = json.loads(r)
            if d.get("id") == id_:
                return d
        except (socket.timeout, json.JSONDecodeError):
            continue
    return {"error": "timeout"}


def get_value(resp: dict) -> object:
    return resp.get("result", {}).get("result", {}).get("value")


def test_darkmode(cdp_port: int, dev_port: int, route: str, out_prefix: str) -> int:
    tabs_url = f"http://localhost:{cdp_port}/json/list"
    tabs = json.loads(urllib.request.urlopen(tabs_url).read())
    tab = next((t for t in tabs if "about:blank" in t.get("url", "")
                or f"localhost:{dev_port}" in t.get("url", "")), tabs[0])
    ws = ws_connect(tab["webSocketDebuggerUrl"])

    cdp(ws, "Page.enable", id_=1)
    url = f"http://localhost:{dev_port}{route}"

    # 1. light mode (localStorage clear → THEME_INIT_SCRIPT system → prefers-color-scheme: light)
    cdp(ws, "Page.navigate", {"url": url}, id_=2)
    time.sleep(3)
    cdp(ws, "Runtime.evaluate", {"expression": "localStorage.removeItem('mobruji-theme')"}, id_=3)
    cdp(ws, "Page.reload", id_=4)
    time.sleep(3)
    light_dark_class = get_value(cdp(ws, "Runtime.evaluate", {"expression": "document.documentElement.classList.contains('dark')"}, id_=5))
    light_bg = get_value(cdp(ws, "Runtime.evaluate", {"expression": "getComputedStyle(document.body).backgroundColor"}, id_=6))
    light_var_bg = get_value(cdp(ws, "Runtime.evaluate", {"expression": "getComputedStyle(document.documentElement).getPropertyValue('--background').trim()"}, id_=7))
    light_shot = cdp(ws, "Page.captureScreenshot", {"format": "png"}, id_=8, timeout=20)

    # 2. dark mode (localStorage = 'dark' → THEME_INIT_SCRIPT → html.dark)
    cdp(ws, "Runtime.evaluate", {"expression": "localStorage.setItem('mobruji-theme','dark')"}, id_=11)
    cdp(ws, "Page.reload", {"ignoreCache": True}, id_=12)
    time.sleep(4)
    dark_dark_class = get_value(cdp(ws, "Runtime.evaluate", {"expression": "document.documentElement.classList.contains('dark')"}, id_=13))
    dark_bg = get_value(cdp(ws, "Runtime.evaluate", {"expression": "getComputedStyle(document.body).backgroundColor"}, id_=14))
    dark_var_bg = get_value(cdp(ws, "Runtime.evaluate", {"expression": "getComputedStyle(document.documentElement).getPropertyValue('--background').trim()"}, id_=15))
    dark_shot = cdp(ws, "Page.captureScreenshot", {"format": "png"}, id_=16, timeout=20)

    ws.close()

    light_png_path = f"{out_prefix}_light.png"
    dark_png_path = f"{out_prefix}_dark.png"
    if "result" in light_shot:
        with open(light_png_path, "wb") as f:
            f.write(base64.b64decode(light_shot["result"]["data"]))
    if "result" in dark_shot:
        with open(dark_png_path, "wb") as f:
            f.write(base64.b64decode(dark_shot["result"]["data"]))

    light_size = os.path.getsize(light_png_path) if os.path.exists(light_png_path) else 0
    dark_size = os.path.getsize(dark_png_path) if os.path.exists(dark_png_path) else 0
    byte_diff = abs(light_size - dark_size)

    print(f"=== darkmode QA: {url} ===")
    print(f"light: dark_class={light_dark_class} body_bg={light_bg!r} --background={light_var_bg!r} png={light_size}b")
    print(f"dark:  dark_class={dark_dark_class} body_bg={dark_bg!r} --background={dark_var_bg!r} png={dark_size}b")
    print(f"byte_diff: {byte_diff} (>500 시각 변화 발생 / <100 = 시각 차이 없음 (사고))")

    ok = bool(dark_dark_class) and (light_bg != dark_bg) and byte_diff > 200
    print(f"verdict: {'✅ OK' if ok else '❌ 사고 — dark 적용 X'}")
    return 0 if ok else 1


def main() -> None:
    p = argparse.ArgumentParser()
    p.add_argument("--cdp-port", type=int, default=9222)
    p.add_argument("--dev-port", type=int, default=4322)
    p.add_argument("--route", default="/")
    p.add_argument("--out-prefix", default="/tmp/dev-browser-qa/darkmode")
    args = p.parse_args()
    sys.exit(test_darkmode(args.cdp_port, args.dev_port, args.route, args.out_prefix))


if __name__ == "__main__":
    main()
