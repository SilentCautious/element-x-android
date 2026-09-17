#!/usr/bin/env python3
"""Spike 4: can a WebSocket subscriber receive a message forwarded by the ntfy Matrix gateway?

Reproduces exactly what the app does:
  1. open a WebSocket on /{topic}/ws and keep it open
  2. POST a push to https://ntfy.sh/_matrix/push/v1/notify with pushkey = that topic
  3. report whether the WebSocket received it live, and whether it landed in the cache

This tells apart "the gateway never forwarded it" from "it arrived but the app mishandles it".

Usage
-----
    python spike/ntfy-live-delivery-spike.py

Throwaway diagnostic. Delete the spike/ folder once the design decision is made.
"""

import base64
import json
import os
import socket
import ssl
import sys
import threading
import time
import urllib.error
import urllib.request

SERVER = os.environ.get("NTFY_SERVER", "https://ntfy.sh")
HOST = "ntfy.sh"
TIMEOUT = 25

# The exact payload DefaultTestPush / PushGatewayNotifyRequest sends.
TEST_EVENT_ID = "$THIS_IS_A_FAKE_EVENT_ID"
TEST_ROOM_ID = "!room:domain"
FAKE_SECRET = "A_FAKE_SECRET"


def http(method, url, body=None, content_type=None):
    data = body.encode() if isinstance(body, str) else body
    request = urllib.request.Request(url, data=data, method=method)
    if content_type:
        request.add_header("Content-Type", content_type)
    request.add_header("User-Agent", "elementx-ntfy-spike/1.0")
    try:
        with urllib.request.urlopen(request, timeout=TIMEOUT) as response:
            return response.status, response.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as error:
        return error.code, error.read().decode("utf-8", "replace")
    except Exception as error:  # noqa: BLE001 - spike, report anything
        return None, f"{type(error).__name__}: {error}"


class WebSocketReader:
    """Minimal WebSocket client that reads frames until stopped, mirroring the app's client."""

    def __init__(self, path):
        self.path = path
        self.frames = []
        self.status_line = None
        self.error = None
        self._stop = threading.Event()
        self._thread = None

    def start(self):
        self._thread = threading.Thread(target=self._run, daemon=True)
        self._thread.start()
        # Give the handshake time to complete before the first publish.
        for _ in range(50):
            if self.status_line is not None or self.error is not None:
                break
            time.sleep(0.1)

    def stop(self):
        self._stop.set()
        if self._thread is not None:
            self._thread.join(timeout=3)

    def _run(self):
        key = base64.b64encode(os.urandom(16)).decode()
        request = (
            f"GET {self.path} HTTP/1.1\r\n"
            f"Host: {HOST}\r\n"
            "Upgrade: websocket\r\n"
            "Connection: Upgrade\r\n"
            f"Sec-WebSocket-Key: {key}\r\n"
            "Sec-WebSocket-Version: 13\r\n"
            "User-Agent: elementx-ntfy-spike/1.0\r\n\r\n"
        )
        try:
            context = ssl.create_default_context()
            with socket.create_connection((HOST, 443), timeout=TIMEOUT) as raw:
                with context.wrap_socket(raw, server_hostname=HOST) as tls:
                    tls.sendall(request.encode())
                    handshake = b""
                    while b"\r\n\r\n" not in handshake:
                        chunk = tls.recv(4096)
                        if not chunk:
                            self.error = "closed during handshake"
                            return
                        handshake += chunk
                    self.status_line = handshake.split(b"\r\n", 1)[0].decode("utf-8", "replace")
                    tls.settimeout(1.0)
                    while not self._stop.is_set():
                        try:
                            header = tls.recv(2)
                        except socket.timeout:
                            continue
                        if len(header) < 2:
                            return
                        opcode = header[0] & 0x0F
                        length = header[1] & 0x7F
                        if length == 126:
                            length = int.from_bytes(tls.recv(2), "big")
                        elif length == 127:
                            length = int.from_bytes(tls.recv(8), "big")
                        payload = b""
                        while len(payload) < length:
                            part = tls.recv(length - len(payload))
                            if not part:
                                break
                            payload += part
                        if opcode == 0x1:
                            self.frames.append(payload.decode("utf-8", "replace"))
                        elif opcode == 0x8:
                            return
        except Exception as error:  # noqa: BLE001 - spike, report anything
            self.error = f"{type(error).__name__}: {error}"


def main():
    topic = f"elementx-live-{os.urandom(4).hex()}"
    publish_url = f"{SERVER}/{topic}"

    print(f"topic       : {topic}")
    print(f"pushkey     : {publish_url}")
    print()

    reader = WebSocketReader(f"/{topic}/ws")
    reader.start()
    print(f"[1] WebSocket handshake: {reader.status_line} (error={reader.error})")
    if reader.status_line is None or "101" not in reader.status_line:
        print("    cannot continue without a WebSocket")
        return 1
    print()

    # A rate visitor is registered by actually subscribing; keep the socket open and also make sure
    # the topic is known before the gateway is called.
    http("GET", f"{SERVER}/{topic}/json?poll=1")
    time.sleep(1)
    print(f"[2] subscriber is live, frames so far: {len(reader.frames)}")
    print()

    gateway_body = json.dumps({
        "notification": {
            "event_id": TEST_EVENT_ID,
            "room_id": TEST_ROOM_ID,
            "counts": {"unread": 1},
            "prio": "high",
            "devices": [
                {
                    "app_id": "io.element.android.libraries.pushproviders.ntfy",
                    "pushkey": publish_url,
                    "pushkey_ts": int(time.time()),
                    "data": {"cs": FAKE_SECRET},
                }
            ],
        }
    })
    status, body = http("POST", f"{SERVER}/_matrix/push/v1/notify", gateway_body, "application/json")
    print("[3] POST /_matrix/push/v1/notify")
    print(f"    -> HTTP {status} {body.strip()[:200]!r}")
    print()

    # Wait for the gateway hop, then look at what arrived on the socket.
    time.sleep(5)
    print(f"[4] WebSocket frames received while connected: {len(reader.frames)}")
    for frame in reader.frames:
        print(f"    {frame[:240]}")
    print()

    reader.stop()

    status, body = http("GET", f"{SERVER}/{topic}/json?poll=1")
    cached = []
    for line in body.splitlines():
        line = line.strip()
        if line:
            try:
                parsed = json.loads(line)
            except json.JSONDecodeError:
                continue
            if parsed.get("event") == "message":
                cached.append(parsed)
    print(f"[5] messages in cache: {len(cached)}")
    for message in cached:
        print(f"    {json.dumps(message)[:240]}")
    print()

    live_ok = any(TEST_EVENT_ID in frame for frame in reader.frames)
    cached_ok = any(TEST_EVENT_ID in json.dumps(message) for message in cached)

    print("==== verdict ====")
    print(f"  {'PASS' if live_ok else 'FAIL'}  delivered live over the WebSocket")
    print(f"  {'PASS' if cached_ok else 'FAIL'}  stored on the topic (cache)")
    print()
    if live_ok:
        print("The gateway and the topic are fine: a live subscriber does receive the push.")
        print("The problem is therefore on the app side, after the frame is received.")
    elif cached_ok:
        print("The push reaches the topic but NOT the live WebSocket.")
        print("The subscription itself is the problem (path, query parameters, or the client).")
    else:
        print("The push never reaches the topic.")
        print("The gateway is not forwarding it: check the pushkey prefix rule and the rate visitor rule.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
