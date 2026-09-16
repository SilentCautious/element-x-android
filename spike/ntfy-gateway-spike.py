#!/usr/bin/env python3
"""Spike 3: end-to-end simulation of the Matrix -> push gateway -> ntfy -> subscriber chain.

Skips only the homeserver: it POSTs the exact body a homeserver sends to a push gateway
(with a real pushkey and a real client secret in devices[].data), and checks what ends up
on the ntfy topic.

What it proves
--------------
1. ntfy exposes a built-in Matrix push gateway at /_matrix/push/v1/notify.
2. It accepts a pushkey pointing at one of its own topics.
3. It forwards the whole body, so devices[].data.cs (the client secret that
   DefaultPusherSubscriber sets via defaultPayload) reaches the subscriber.
4. Whether the gateway reports the pushkey as rejected.

Usage
-----
    python spike/ntfy-gateway-spike.py

Throwaway diagnostic. Delete the spike/ folder once the design decision is made.
"""

import json
import os
import sys
import time
import urllib.error
import urllib.request

SERVER = os.environ.get("NTFY_SERVER", "https://ntfy.sh")
TIMEOUT = 25

GATEWAY_URL = f"{SERVER}/_matrix/push/v1/notify"
APP_ID = "io.element.android.libraries.pushproviders.ntfy"
CLIENT_SECRET = "spike-client-secret-9f3a"
EVENT_ID = "$spikeEvent:example.org"
ROOM_ID = "!spikeRoom:example.org"


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


def read_topic(topic, seconds=10):
    """Poll the topic until a message shows up, to absorb the gateway hop latency."""
    deadline = time.time() + seconds
    while time.time() < deadline:
        status, body = http("GET", f"{SERVER}/{topic}/json?poll=1")
        messages = []
        if status == 200:
            for line in body.splitlines():
                line = line.strip()
                if not line:
                    continue
                parsed = json.loads(line)
                if parsed.get("event") == "message":
                    messages.append(parsed)
        if messages:
            return messages
        time.sleep(1)
    return []


def main():
    topic = f"elementx-gw-{os.urandom(4).hex()}"
    push_key = f"{SERVER}/{topic}"

    print(f"server     : {SERVER}")
    print(f"topic      : {topic}")
    print(f"pushkey    : {push_key}")
    print(f"gateway    : {GATEWAY_URL}")
    print()

    # ---- 1. gateway discovery ----------------------------------------------
    status, body = http("GET", GATEWAY_URL)
    print(f"[1] gateway discovery GET {GATEWAY_URL}")
    print(f"    -> HTTP {status} {body.strip()[:200]!r}")
    discovery_ok = status == 200 and "matrix" in body
    print(f"    discovery ok: {discovery_ok}")
    print()

    # ---- 2. register a subscriber on the topic ------------------------------
    # ntfy rejects pushkeys of topics that never had a subscriber, so make sure this
    # topic has a "rate visitor" before the gateway is used.
    status, _ = http("GET", f"{SERVER}/{topic}/json?poll=1")
    print(f"[2] visited the topic to register a rate visitor -> HTTP {status}")
    print()

    # ---- 3. POST what a homeserver sends to the gateway --------------------
    gateway_request = {
        "notification": {
            "event_id": EVENT_ID,
            "room_id": ROOM_ID,
            "counts": {"unread": 1},
            "prio": "high",
            "devices": [
                {
                    "app_id": APP_ID,
                    "pushkey": push_key,
                    "pushkey_ts": int(time.time()),
                    "data": {"cs": CLIENT_SECRET},
                    "tweaks": {"sound": "default"},
                }
            ],
        }
    }
    payload = json.dumps(gateway_request)
    status, body = http("POST", GATEWAY_URL, payload, "application/json")
    print("[3] POST /_matrix/push/v1/notify")
    print(f"    request body : {payload[:200]}")
    print(f"    -> HTTP {status} {body.strip()[:300]!r}")
    rejected = CLIENT_SECRET  # placeholder, replaced below if body is json
    try:
        parsed_response = json.loads(body)
        rejected = parsed_response.get("rejected") or []
    except (json.JSONDecodeError, TypeError):
        parsed_response = None
    print(f"    rejected pushkeys: {rejected}")
    print()

    # ---- 4. what the subscriber gets ---------------------------------------
    messages = read_topic(topic)
    print(f"[4] messages on the topic: {len(messages)}")
    for message in messages:
        print(f"    envelope: {json.dumps(message, ensure_ascii=False)[:260]}")
    print()

    # ---- 5. app-side decode, mirroring NtfyMessage.toPushData() -------------
    print("[5] app-side decode")
    client_secret = None
    event_id = None
    room_id = None
    unread = None
    for message in messages:
        body_field = message.get("message") or ""
        try:
            decoded = json.loads(body_field)
        except json.JSONDecodeError:
            print("    message field is not JSON, skipping")
            continue
        notification = (decoded or {}).get("notification") or {}
        event_id = notification.get("event_id")
        room_id = notification.get("room_id")
        unread = (notification.get("counts") or {}).get("unread")
        devices = notification.get("devices") or []
        if devices:
            client_secret = (devices[0].get("data") or {}).get("cs")
        break

    print(f"    event_id      : {event_id}")
    print(f"    room_id       : {room_id}")
    print(f"    unread        : {unread}")
    print(f"    client secret : {client_secret}")
    print()

    # ---- verdict ----------------------------------------------------------
    checks = [
        ("gateway discovery works", discovery_ok),
        ("pushkey accepted (not rejected)", not rejected),
        ("event_id recovered", event_id == EVENT_ID),
        ("room_id recovered", room_id == ROOM_ID),
        ("unread recovered", unread == 1),
        ("client secret recovered from devices[].data.cs", client_secret == CLIENT_SECRET),
    ]
    print("==== verdict ====")
    for label, ok in checks:
        print(f"  {'PASS' if ok else 'FAIL'}  {label}")
    print()
    if all(ok for _, ok in checks):
        print("The chain homeserver -> gateway -> ntfy topic -> subscriber carries everything")
        print("PushData needs, including the client secret. No topic/session lookup needed.")
    else:
        print("At least one link does not work as assumed, see the FAIL lines above.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
