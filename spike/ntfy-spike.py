#!/usr/bin/env python3
"""Spike: can the Matrix push gateway payload survive a round trip through ntfy?

Purpose
-------
The ntfy push provider plan assumes the app can self-subscribe to a ntfy topic and
recover, from the received message, everything PushData needs:
event_id, room_id, counts.unread and the client secret identifying the session.

The UnifiedPush gateway POSTs this body to the distributor endpoint:

    {"notification": {"event_id": "$...", "room_id": "!...", "counts": {"unread": 1}, "prio": "high"}}

This script publishes that exact body to a real ntfy topic, then reads back what a
subscriber actually receives (HTTP JSON stream and WebSocket), and reports which
fields survived.

Usage
-----
    python spike/ntfy-spike.py

Throwaway diagnostic. Delete the spike/ folder once the design decision is made.
"""

import base64
import json
import os
import socket
import ssl
import sys
import time
import urllib.error
import urllib.request

SERVER = os.environ.get("NTFY_SERVER", "https://ntfy.sh")
TIMEOUT = 20

room_id = "!spikeRoom:example.org"
event_id = "$spikeEventId1234567890"


def http(method, url, body=None, content_type=None):
    """Returns (status, response_body) or (None, error_message)."""
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


def websocket_read(host, path, seconds=6):
    """Minimal WebSocket client: handshake + read server frames (unmasked).

    Enough to prove the endpoint path and see the real message format.
    """
    key = base64.b64encode(os.urandom(16)).decode()
    request = (
        f"GET {path} HTTP/1.1\r\n"
        f"Host: {host}\r\n"
        "Upgrade: websocket\r\n"
        "Connection: Upgrade\r\n"
        f"Sec-WebSocket-Key: {key}\r\n"
        "Sec-WebSocket-Version: 13\r\n"
        "User-Agent: elementx-ntfy-spike/1.0\r\n\r\n"
    )
    context = ssl.create_default_context()
    with socket.create_connection((host, 443), timeout=TIMEOUT) as raw:
        with context.wrap_socket(raw, server_hostname=host) as tls:
            tls.sendall(request.encode())
            handshake = b""
            while b"\r\n\r\n" not in handshake:
                chunk = tls.recv(4096)
                if not chunk:
                    return None, "connection closed during handshake", []
                handshake += chunk
            status_line = handshake.split(b"\r\n", 1)[0].decode("utf-8", "replace")
            if "101" not in status_line:
                return status_line, "handshake refused", []

            frames = []
            deadline = time.time() + seconds
            tls.settimeout(2.0)
            while time.time() < deadline:
                try:
                    header = tls.recv(2)
                except socket.timeout:
                    continue
                if len(header) < 2:
                    break
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
                    frames.append(payload.decode("utf-8", "replace"))
                elif opcode == 0x8:
                    break
            return status_line, None, frames


def main():
    topic = f"elementx-spike-{os.urandom(4).hex()}"
    publish_url = f"{SERVER}/{topic}"
    print(f"server      : {SERVER}")
    print(f"topic       : {topic}")
    print(f"publish url : {publish_url}")
    print()

    # ---- Connectivity -------------------------------------------------------
    status, body = http("GET", f"{SERVER}/{topic}/json?poll=1")
    print(f"[connectivity] GET {topic}/json?poll=1 -> {status}")
    if status is None:
        print("  -> no outbound network, aborting")
        print(f"  -> {body}")
        return 1
    print()

    # ---- Publish the shapes that matter -------------------------------------
    gateway_body = json.dumps({
        "notification": {
            "event_id": event_id,
            "room_id": room_id,
            "counts": {"unread": 1},
            "prio": "high",
        }
    })

    variants = [
        ("A. gateway body, application/json (what the gateway really sends)",
         gateway_body, "application/json"),
        ("B. gateway body, text/plain",
         gateway_body, "text/plain"),
        ("C. top-level message + nested notification extra field",
         json.dumps({
             "topic": topic,
             "message": "New message",
             "notification": {"event_id": event_id, "room_id": room_id},
         }), "application/json"),
        ("D. flat body with matrix fields as top-level extras",
         json.dumps({
             "topic": topic,
             "message": "New message",
             "event_id": event_id,
             "room_id": room_id,
             "counts": {"unread": 1},
         }), "application/json"),
    ]

    for label, payload, content_type in variants:
        status, body = http("POST", publish_url, payload, content_type)
        print(f"[publish] {label}")
        print(f"          Content-Type: {content_type}")
        print(f"          -> HTTP {status} {body.strip()[:200]!r}")
        time.sleep(0.6)
    print()

    # ---- What a subscriber receives (HTTP JSON stream) ----------------------
    status, body = http("GET", f"{SERVER}/{topic}/json?poll=1")
    print(f"[read back] GET {topic}/json?poll=1 -> HTTP {status}")
    received = []
    for line in body.splitlines():
        line = line.strip()
        if not line:
            continue
        try:
            received.append(json.loads(line))
        except json.JSONDecodeError:
            print(f"  (unparsable line) {line[:120]}")
    print(f"  {len(received)} message(s) returned")
    print()

    # ---- Field survival analysis -------------------------------------------
    raw_dump = json.dumps(received, indent=2, ensure_ascii=False)
    print("---- raw messages ----")
    print(raw_dump)
    print()

    # The body of a publish to /{topic} is stored verbatim in the message string, so the
    # matrix fields are searched in the decoded message payloads, not in the envelope.
    blob = json.dumps(received, ensure_ascii=False)
    blob += "\n" + "\n".join(str(m.get("message") or "") for m in received)

    def contains(value):
        return blob.find(value) != -1

    print("---- field survival ----")
    checks = [
        ("room_id survived", room_id),
        ("event_id survived", event_id),
        ("unread count survived", '"unread"'),
        ("topic returned in message", topic),
    ]
    for label, needle in checks:
        print(f"  {'YES' if contains(needle) else 'NO '}  {label}")
    print()

    # ---- WebSocket endpoint ------------------------------------------------
    print(f"[websocket] handshake wss://ntfy.sh/{topic}/ws")
    status_line, error, frames = websocket_read("ntfy.sh", f"/{topic}/ws?since=all")
    print(f"  status: {status_line}")
    if error:
        print(f"  error : {error}")
    print(f"  frames: {len(frames)}")
    for frame in frames[:6]:
        print(f"    {frame[:220]}")
    print()

    # ---- Verdict -----------------------------------------------------------
    data_survived = contains(room_id) and contains(event_id)
    print("==== verdict ====")
    if data_survived:
        print("PASS  the matrix identifiers survive the round trip through ntfy.")
    else:
        print("FAIL  the matrix identifiers do NOT survive ntfy's JSON publish parsing.")
        print("      The gateway body is mapped to ntfy's message fields and the")
        print("      extra fields are dropped, so a self-subscribed WebSocket cannot")
        print("      recover event_id/room_id. The design needs a custom relay.")
    print(f"ws endpoint /{{topic}}/ws reachable : {'YES' if not error and status_line and '101' in status_line else 'NO'}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
