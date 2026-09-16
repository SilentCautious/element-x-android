#!/usr/bin/env python3
"""Spike 2: when does ntfy parse a JSON publish body, and when does it store it raw?

Spike 1 showed that the gateway body arrives at subscribers as a *string* in the
`message` field rather than as structured top-level fields. This script pins down
ntfy's exact rule so the Kotlin model can be written against real behaviour.

Usage
-----
    python spike/ntfy-json-semantics.py

Throwaway diagnostic. Delete the spike/ folder once the design decision is made.
"""

import json
import os
import sys
import time
import urllib.error
import urllib.request

SERVER = os.environ.get("NTFY_SERVER", "https://ntfy.sh")
TIMEOUT = 20

GATEWAY_BODY = json.dumps({
    "notification": {
        "event_id": "$spikeEventId1234567890",
        "room_id": "!spikeRoom:example.org",
        "counts": {"unread": 1},
        "prio": "high",
    }
})


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


def last_message(topic):
    status, body = http("GET", f"{SERVER}/{topic}/json?poll=1")
    if status != 200:
        return None
    for line in body.splitlines():
        line = line.strip()
        if not line:
            continue
        parsed = json.loads(line)
        if parsed.get("event") == "message":
            return parsed
    return None


def run_case(number, label, url_suffix, payload, content_type):
    topic = f"elementx-sem-{number}-{os.urandom(3).hex()}"
    url = f"{SERVER}/{topic}{url_suffix}"
    status, raw = http("POST", url, payload, content_type)
    time.sleep(0.8)
    message = last_message(topic)
    print(f"case {number}: {label}")
    print(f"  POST {url}")
    print(f"  Content-Type  : {content_type}")
    print(f"  body          : {payload[:110]}")
    print(f"  publish status: {status}")
    if message is None:
        print("  stored message: <none>")
    else:
        print(f"  stored message: {json.dumps(message.get('message'))[:180]}")
        extras = {k: v for k, v in message.items() if k not in ("id", "time", "expires", "event", "topic", "message")}
        print(f"  other fields  : {json.dumps(extras, ensure_ascii=False)[:180]}")
    print()
    return message


def main():
    # Used as the topic when publishing to the root endpoint with topic in the body.
    root_topic_holder = {}

    print("=" * 70)
    print("A. posting to /{topic}")
    print("=" * 70)
    run_case(1, "plain text, no content type", "", "plain-body", None)
    run_case(2, "json with only message", "", json.dumps({"message": "json-message"}), "application/json")
    run_case(3, "json with topic + message", "", json.dumps({"topic": "x", "message": "json-message"}), "application/json")
    run_case(4, "json with message + unknown extra field", "",
             json.dumps({"message": "json-message", "notification": {"event_id": "$e"}}), "application/json")
    run_case(5, "the gateway body", "", GATEWAY_BODY, "application/json")
    run_case(6, "the gateway body as text/plain", "", GATEWAY_BODY, "text/plain")

    print("=" * 70)
    print("B. posting to the root endpoint, topic in the body (documented JSON publish)")
    print("=" * 70)
    topic = f"elementx-sem-root-{os.urandom(3).hex()}"
    root_topic_holder["topic"] = topic
    status, raw = http("POST", SERVER, json.dumps({
        "topic": topic,
        "message": "root-json-message",
        "notification": {"event_id": "$e"},
    }), "application/json")
    time.sleep(0.8)
    message = last_message(topic)
    print(f"case 7: root publish with topic in body")
    print(f"  publish status: {status}")
    print(f"  raw response  : {raw[:160]}")
    print(f"  stored message: {json.dumps(message.get('message')) if message else '<none>'}")
    print()

    print("==== summary ====")
    print("Compare 'stored message' against the posted body: if they are equal, ntfy did")
    print("NOT parse the JSON and the whole body is preserved verbatim in message.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
