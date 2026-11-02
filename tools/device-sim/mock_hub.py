"""手机端联调用的最小 mock hub（不是云端实现，只用于测试）。

- 以固定/持久化的测试密钥签发 envelope.v1；
- 提供手机拉取式传输：GET /v1/device/inbox、POST /v1/device/receipts；
- 提供测试入口 POST /test/enqueue，便于手工投递一条意图。

用法：
    uv run --with cryptography python tools/device-sim/mock_hub.py serve --port 8899
    uv run --with cryptography python tools/device-sim/mock_hub.py selftest
    curl -X POST http://127.0.0.1:8899/test/enqueue \
         -H 'Content-Type: application/json' \
         -d '{"intent":"create_event","params":{"title":"评审","begin_ms":1762081200000,"end_ms":1762084800000,"attendees":["a@b.c"]},"level":3}'

服务启动时会打印 hub 公钥 JWK，把它填进手机设置（Hub 公钥），
kid 固定 hub-test-1，令牌默认 test-token。私钥只落在本机临时目录，不进仓库。
"""

import argparse
import base64
import json
import os
import sys
import tempfile
import threading
import time
import uuid
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

HERE = os.path.dirname(os.path.abspath(__file__))
RUNNER = os.path.abspath(os.path.join(HERE, "..", "vector-runner", "py"))
sys.path.insert(0, RUNNER)

from cryptography.hazmat.primitives import hashes  # noqa: E402
from cryptography.hazmat.primitives.asymmetric import ec, utils  # noqa: E402
from jcs import canonicalize  # noqa: E402

KID = "hub-test-1"
DEFAULT_TOKEN = "test-token"
LEVEL_BASE = {
    "chat": 1, "web_search": 1, "set_timer": 1, "save_note": 1, "open_app": 1,
    "set_alarm": 1, "create_event": 2, "task_handoff": 2, "voice_command": 2,
}

STATE_LOCK = threading.Lock()
QUEUE = []
DELIVERED = []
RECEIPTS = []
COUNTER = [0]


def _b64(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).decode().rstrip("=")


def _key_file(path):
    if os.path.isfile(path):
        with open(path, encoding="utf-8") as f:
            raw = json.load(f)
        return ec.derive_private_key(int(raw["d"], 16), ec.SECP256R1()), raw["jwk"]
    private = ec.generate_private_key(ec.SECP256R1())
    numbers = private.public_key().public_numbers()
    jwk = {
        "kty": "EC",
        "crv": "P-256",
        "x": _b64(numbers.x.to_bytes(32, "big")),
        "y": _b64(numbers.y.to_bytes(32, "big")),
    }
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        json.dump({"d": format(private.private_numbers().private_value, "x"), "jwk": jwk}, f)
    return private, jwk


def _sign(private, payload):
    der = private.sign(canonicalize(payload).encode("utf-8"), ec.ECDSA(hashes.SHA256()))
    r, s = utils.decode_dss_signature(der)
    return _b64(r.to_bytes(32, "big") + s.to_bytes(32, "big"))


def _uid():
    COUNTER[0] += 1
    return str(uuid.UUID(int=(int(time.time() * 1000) << 80) | (7 << 76) | (0x8 << 60) | COUNTER[0]))


def build_envelope(private, intent, params, level=None, device="phone-1", ttl_s=300):
    if intent not in LEVEL_BASE:
        raise ValueError("unknown intent: " + intent)
    effective = max(level or LEVEL_BASE[intent], LEVEL_BASE[intent])
    now = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    claims = {
        "v": 1,
        "id": _uid(),
        "ts": now,
        "ttl_s": int(ttl_s),
        "from": {"role": "hub", "kid": KID},
        "to": {"device": device},
        "intent": intent,
        "params": params,
        "origin": {"kind": "user_text", "tainted": False},
        "level": effective,
    }
    claims["sig"] = _sign(private, claims)
    return claims


def enqueue(private, intent, params, level=None, device="phone-1"):
    envelope = build_envelope(private, intent, params, level, device)
    with STATE_LOCK:
        QUEUE.append(envelope)
    return envelope


class Handler(BaseHTTPRequestHandler):
    server_version = "MockHub/1.0"
    token = DEFAULT_TOKEN
    private = None
    jwk = None

    def log_message(self, fmt, *args):
        print("[hub] " + (fmt % args), flush=True)

    def _send(self, code, payload):
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _authorized(self):
        return self.headers.get("Authorization") == "Bearer " + self.token

    def do_GET(self):
        parsed = urlparse(self.path)
        if parsed.path == "/v1/device/inbox":
            if not self._authorized():
                return self._send(401, {"error": "unauthorized"})
            device = parse_qs(parsed.query).get("device_id", [""])[0]
            with STATE_LOCK:
                mine = [e for e in QUEUE if e.get("to", {}).get("device") == device]
                for envelope in mine:
                    QUEUE.remove(envelope)
                    DELIVERED.append(envelope)
            return self._send(200, {"envelopes": mine, "cursor": None})
        if parsed.path == "/test/state":
            with STATE_LOCK:
                return self._send(200, {
                    "queued": QUEUE, "delivered": DELIVERED, "receipts": RECEIPTS,
                })
        return self._send(404, {"error": "not found"})

    def do_POST(self):
        length = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(length) if length else b"{}"
        if self.path == "/v1/device/receipts":
            if not self._authorized():
                return self._send(401, {"error": "unauthorized"})
            try:
                receipt = json.loads(raw.decode("utf-8"))
            except Exception:
                return self._send(400, {"error": "bad json"})
            from validator import verify_receipt

            checked = verify_receipt(receipt)
            if not checked.ok:
                print("[hub] INVALID receipt %s %s" % (receipt.get("envelope_id"), checked.code), flush=True)
                return self._send(400, {"error": checked.code})
            with STATE_LOCK:
                RECEIPTS.append(receipt)
            print("[hub] receipt %s %s" % (receipt.get("envelope_id"), receipt.get("status")), flush=True)
            return self._send(200, {"ok": True})
        if self.path == "/test/enqueue":
            try:
                body = json.loads(raw.decode("utf-8"))
                envelope = enqueue(
                    self.private,
                    body["intent"],
                    body.get("params") or {},
                    body.get("level"),
                    body.get("device") or "phone-1",
                )
            except Exception as exc:
                return self._send(400, {"error": str(exc)})
            print("[hub] enqueued %s %s" % (envelope["id"], envelope["intent"]), flush=True)
            return self._send(200, {"envelope": envelope})
        return self._send(404, {"error": "not found"})


def run_server(args):
    key_path = args.key_file or os.path.join(tempfile.gettempdir(), "dpa-mock-hub-key.json")
    private, jwk = _key_file(key_path)
    Handler.private = private
    Handler.jwk = jwk
    Handler.token = args.token
    server = ThreadingHTTPServer((args.host, args.port), Handler)
    print("mock hub listening on http://%s:%d" % (args.host, args.port), flush=True)
    print("kid=%s token=%s key=%s" % (KID, args.token, key_path), flush=True)
    print("JWK=" + json.dumps(jwk, ensure_ascii=False), flush=True)
    server.serve_forever()


def selftest(args):
    private, jwk = _key_file(args.key_file or os.path.join(tempfile.gettempdir(), "dpa-mock-hub-key.json"))
    from validator import validate

    envelope = build_envelope(private, "chat", {"text": "hi"})
    result = validate(envelope, {"now": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
                                 "pubkey_jwk": jwk, "device_role": "phone"})
    if not result.ok:
        print("selftest FAILED:", result.as_dict(), file=sys.stderr)
        return 1
    print("selftest OK:", envelope["id"])
    return 0


def main():
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="command", required=True)
    serve = sub.add_parser("serve")
    serve.add_argument("--host", default="127.0.0.1")
    serve.add_argument("--port", type=int, default=8899)
    serve.add_argument("--token", default=DEFAULT_TOKEN)
    serve.add_argument("--key-file", default=None)
    serve.set_defaults(func=run_server)
    test = sub.add_parser("selftest")
    test.add_argument("--key-file", default=None)
    test.set_defaults(func=selftest)
    args = parser.parse_args()
    return args.func(args)


if __name__ == "__main__":
    raise SystemExit(main() or 0)
