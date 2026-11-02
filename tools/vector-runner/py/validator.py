"""协议 v1 的参考校验器与策略引擎（纯逻辑，无 IO）。

与 core/kotlin 的实现逐条对应；两侧必须对 protocol/vectors 给出完全一致的结果。
"""

import base64
import datetime as _dt
import hashlib
import json
import re
from urllib.parse import urlsplit

from jcs import canonicalize

INTENTS = [
    "open_app", "set_alarm", "create_event", "set_timer",
    "web_search", "save_note", "chat", "task_handoff", "voice_command",
]
HANDOFF_PROPOSAL_INTENTS = [
    "open_app", "set_alarm", "create_event", "set_timer",
    "web_search", "save_note", "chat",
]
ROLES = ["hub", "phone", "watch", "pc"]
ORIGIN_KINDS = ["user_text", "user_voice", "scheduled", "derived"]
LEVEL_BASE = {
    "chat": 1, "web_search": 1, "set_timer": 1, "save_note": 1, "open_app": 1,
    "set_alarm": 1, "create_event": 2, "task_handoff": 2, "voice_command": 2,
}
TARGETS = {
    "chat": ["phone", "watch", "pc"],
    "web_search": ["phone", "pc"],
    "set_timer": ["phone", "watch", "pc"],
    "save_note": ["phone", "pc"],
    "open_app": ["phone", "pc"],
    "set_alarm": ["phone"],
    "create_event": ["phone"],
    "task_handoff": ["phone", "watch", "pc"],
    "voice_command": ["phone", "watch", "pc"],
}
ENVELOPE_FIELDS = {"v", "id", "trace", "ts", "ttl_s", "from", "to", "intent", "params", "origin", "level", "parent", "sig"}
REQUIRED_FIELDS = ["v", "id", "ts", "ttl_s", "from", "to", "intent", "params", "origin", "level", "sig"]

UUID7 = re.compile(r"^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
TS = re.compile(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$")
APP_ID = re.compile(r"^[a-z][a-z0-9_.-]{1,63}$")
URL_OK = re.compile(r"^https://[^\s]{1,512}$")
B64URL = re.compile(r"^[A-Za-z0-9_-]+$")
LANG = re.compile(r"^[a-z]{2}(-[A-Z]{2})?$")


class Result:
    def __init__(self, ok, level=None, code=None, executable=True):
        self.ok = ok
        self.level = level
        self.code = code
        self.executable = executable

    def as_dict(self):
        d = {"ok": self.ok}
        if self.level is not None:
            d["level"] = self.level
        if self.code is not None:
            d["code"] = self.code
        if not self.executable:
            d["executable"] = False
        return d


def _is_int(v):
    return isinstance(v, int) and not isinstance(v, bool)


def _fail(code):
    return Result(False, code=code)


def _check_str(v, lo, hi):
    if not isinstance(v, str):
        return "BAD_TYPE"
    if len(v) < lo or len(v) > hi:
        return "BAD_RANGE"
    return None


def _check_int(v, lo, hi):
    if not _is_int(v):
        return "BAD_TYPE"
    if v < lo or v > hi:
        return "BAD_RANGE"
    return None


def _check_keys(obj, allowed, required):
    for k in obj:
        if k not in allowed:
            return "UNKNOWN_FIELD"
    for k in required:
        if k not in obj:
            return "MISSING_FIELD"
    return None


def _validate_params(intent, params):
    if not isinstance(params, dict):
        return "BAD_TYPE"

    def keys(allowed, required):
        return _check_keys(params, allowed, required)

    if intent == "chat":
        err = keys({"text"}, ["text"])
        if err:
            return err
        return _check_str(params["text"], 1, 4000)

    if intent == "web_search":
        err = keys({"query"}, ["query"])
        if err:
            return err
        return _check_str(params["query"], 1, 200)

    if intent == "save_note":
        err = keys({"text", "title"}, ["text"])
        if err:
            return err
        err = _check_str(params["text"], 1, 2000)
        if err:
            return err
        if "title" in params:
            return _check_str(params["title"], 1, 80)
        return None

    if intent == "set_timer":
        err = keys({"seconds", "label"}, ["seconds"])
        if err:
            return err
        err = _check_int(params["seconds"], 1, 86400)
        if err:
            return err
        if "label" in params:
            return _check_str(params["label"], 1, 40)
        return None

    if intent == "set_alarm":
        err = keys({"hour", "minute", "label", "repeat", "days"}, ["hour", "minute"])
        if err:
            return err
        for name, hi in (("hour", 23), ("minute", 59)):
            err = _check_int(params[name], 0, hi)
            if err:
                return err
        if "label" in params:
            err = _check_str(params["label"], 1, 40)
            if err:
                return err
        if "repeat" in params and params["repeat"] not in ("none", "daily", "weekdays"):
            return "BAD_ENUM"
        if "days" in params:
            days = params["days"]
            if not isinstance(days, list):
                return "BAD_TYPE"
            if len(days) > 7:
                return "BAD_RANGE"
            for d in days:
                if not _is_int(d):
                    return "BAD_TYPE"
                if d < 1 or d > 7:
                    return "BAD_RANGE"
            if len(set(days)) != len(days):
                return "BAD_PARAM"
        return None

    if intent == "open_app":
        err = keys({"app_id", "url"}, ["app_id"])
        if err:
            return err
        if not isinstance(params["app_id"], str):
            return "BAD_TYPE"
        if not APP_ID.match(params["app_id"]):
            return "BAD_FORMAT"
        if "url" in params:
            if not isinstance(params["url"], str):
                return "BAD_TYPE"
            if not URL_OK.match(params["url"]):
                return "BAD_FORMAT"
        return None

    if intent == "create_event":
        err = keys({"title", "begin_ms", "end_ms", "description", "location", "attendees"},
                   ["title", "begin_ms", "end_ms"])
        if err:
            return err
        err = _check_str(params["title"], 1, 200)
        if err:
            return err
        for name in ("begin_ms", "end_ms"):
            err = _check_int(params[name], 1, 2 ** 62)
            if err:
                return err
        if params["end_ms"] <= params["begin_ms"]:
            return "BAD_PARAM"
        if "description" in params:
            err = _check_str(params["description"], 0, 2000)
            if err:
                return err
        if "location" in params:
            err = _check_str(params["location"], 0, 200)
            if err:
                return err
        if "attendees" in params:
            att = params["attendees"]
            if not isinstance(att, list):
                return "BAD_TYPE"
            if len(att) > 20:
                return "BAD_RANGE"
            for a in att:
                err = _check_str(a, 3, 200)
                if err:
                    return err
        return None

    if intent == "task_handoff":
        err = keys({"target_device_id", "proposal"}, ["target_device_id", "proposal"])
        if err:
            return err
        err = _check_str(params["target_device_id"], 1, 64)
        if err:
            return err
        proposal = params["proposal"]
        if not isinstance(proposal, dict):
            return "BAD_TYPE"
        err = _check_keys(proposal, {"intent", "params", "level"}, ["intent", "params", "level"])
        if err:
            return err
        p_intent = proposal["intent"]
        if not isinstance(p_intent, str):
            return "BAD_TYPE"
        if p_intent in ("task_handoff", "voice_command"):
            return "HANDOFF_DEPTH"
        if p_intent not in HANDOFF_PROPOSAL_INTENTS:
            return "UNKNOWN_INTENT"
        err = _check_int(proposal["level"], 1, 3)
        if err:
            return err
        return _validate_params(p_intent, proposal["params"])

    if intent == "voice_command":
        err = keys({"transcript", "lang"}, ["transcript"])
        if err:
            return err
        err = _check_str(params["transcript"], 1, 2000)
        if err:
            return err
        if "lang" in params:
            if not isinstance(params["lang"], str):
                return "BAD_TYPE"
            if not LANG.match(params["lang"]):
                return "BAD_FORMAT"
        return None

    return "UNKNOWN_INTENT"


def compute_level(intent, params, env, ctx):
    level = LEVEL_BASE[intent]
    if intent == "open_app":
        catalog = ctx.get("app_catalog") or {}
        app_class = (catalog.get(params["app_id"]) or {}).get("class", "general")
        if app_class in ("finance", "payment", "system_settings"):
            level = max(level, 2)
        if "url" in params:
            host = urlsplit(params["url"]).hostname or ""
            if host not in (ctx.get("url_allowlist") or []):
                level = max(level, 2)
    elif intent == "set_alarm":
        if params.get("repeat", "none") != "none" or int(ctx.get("recent_alarms_24h") or 0) > 5:
            level = max(level, 2)
    elif intent == "create_event":
        if params.get("attendees"):
            level = 3
    elif intent == "task_handoff":
        level = max(2, params["proposal"]["level"])
    if env["origin"]["tainted"]:
        level = min(3, level + 1)
    return level


def _parse_ts(text):
    return _dt.datetime.strptime(text, "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=_dt.timezone.utc)


def _b64url_decode(s):
    pad = '=' * ((4 - len(s) % 4) % 4)
    return base64.urlsafe_b64decode(s + pad)


def verify_es256(env, pubkey_jwk):
    """验签：ES256 over JCS(env minus sig)，raw r||s 的 base64url。"""
    try:
        from cryptography.hazmat.primitives import hashes
        from cryptography.hazmat.primitives.asymmetric import ec, utils
    except ImportError as exc:  # pragma: no cover - 环境缺库时明确报错
        raise RuntimeError("需要 cryptography 库才能验签：uv run --with cryptography ...") from exc

    payload = {k: v for k, v in env.items() if k != "sig"}
    data = canonicalize(payload).encode("utf-8")
    raw = _b64url_decode(env["sig"])
    if len(raw) != 64:
        return False
    r = int.from_bytes(raw[:32], "big")
    s = int.from_bytes(raw[32:], "big")
    x = int.from_bytes(_b64url_decode(pubkey_jwk["x"]), "big")
    y = int.from_bytes(_b64url_decode(pubkey_jwk["y"]), "big")
    try:
        pub = ec.EllipticCurvePublicNumbers(x, y, ec.SECP256R1()).public_key()
        pub.verify(utils.encode_dss_signature(r, s), data, ec.ECDSA(hashes.SHA256()))
        return True
    except Exception:
        return False


def envelope_hash(env):
    payload = {k: v for k, v in env.items() if k != "sig"}
    return hashlib.sha256(canonicalize(payload).encode("utf-8")).hexdigest()


def validate(env, ctx=None):
    """校验一条信封。返回 Result；顺序即 error-codes.md 的优先级。"""
    ctx = ctx or {}
    if not isinstance(env, dict):
        return _fail("BAD_TYPE")

    if "v" not in env:
        return _fail("MISSING_FIELD")
    if not _is_int(env["v"]):
        return _fail("BAD_TYPE")
    if env["v"] != 1:
        return _fail("UNSUPPORTED_VERSION")

    err = _check_keys(env, ENVELOPE_FIELDS, REQUIRED_FIELDS)
    if err:
        return _fail(err)

    if not isinstance(env["id"], str):
        return _fail("BAD_TYPE")
    if not UUID7.match(env["id"]):
        return _fail("BAD_FORMAT")
    if "trace" in env:
        err = _check_str(env["trace"], 1, 64)
        if err:
            return _fail(err)
    if not isinstance(env["ts"], str):
        return _fail("BAD_TYPE")
    if not TS.match(env["ts"]):
        return _fail("BAD_FORMAT")
    err = _check_int(env["ttl_s"], 1, 86400)
    if err:
        return _fail(err)

    frm = env["from"]
    if not isinstance(frm, dict):
        return _fail("BAD_TYPE")
    err = _check_keys(frm, {"role", "kid"}, ["role", "kid"])
    if err:
        return _fail(err)
    if not isinstance(frm["role"], str) or frm["role"] not in ROLES:
        return _fail("BAD_ENUM" if isinstance(frm["role"], str) else "BAD_TYPE")
    err = _check_str(frm["kid"], 1, 64)
    if err:
        return _fail(err)

    to = env["to"]
    if not isinstance(to, dict):
        return _fail("BAD_TYPE")
    err = _check_keys(to, {"device"}, ["device"])
    if err:
        return _fail(err)
    err = _check_str(to["device"], 1, 64)
    if err:
        return _fail(err)

    intent = env["intent"]
    if not isinstance(intent, str):
        return _fail("BAD_TYPE")
    if intent not in INTENTS:
        return _fail("UNKNOWN_INTENT")

    params = env["params"]
    if not isinstance(params, dict):
        return _fail("BAD_TYPE")

    origin = env["origin"]
    if not isinstance(origin, dict):
        return _fail("BAD_TYPE")
    err = _check_keys(origin, {"kind", "via", "tainted"}, ["kind", "tainted"])
    if err:
        return _fail(err)
    if not isinstance(origin["kind"], str) or origin["kind"] not in ORIGIN_KINDS:
        return _fail("BAD_ENUM" if isinstance(origin["kind"], str) else "BAD_TYPE")
    if not isinstance(origin["tainted"], bool):
        return _fail("BAD_TYPE")
    if "via" in origin:
        err = _check_str(origin["via"], 1, 64)
        if err:
            return _fail(err)

    err = _check_int(env["level"], 1, 3)
    if err:
        return _fail(err)

    if "parent" in env:
        if not isinstance(env["parent"], str):
            return _fail("BAD_TYPE")
        if not UUID7.match(env["parent"]):
            return _fail("BAD_FORMAT")

    sig = env["sig"]
    if not isinstance(sig, str):
        return _fail("BAD_TYPE")
    if not B64URL.match(sig) or len(sig) < 86 or len(sig) > 88:
        return _fail("BAD_FORMAT")

    err = _validate_params(intent, params)
    if err:
        return _fail(err)

    computed = compute_level(intent, params, env, ctx)
    if env["level"] < computed:
        return _fail("LEVEL_BELOW_POLICY")
    effective = max(env["level"], computed)

    role = ctx.get("device_role")
    if role and role not in TARGETS[intent]:
        return _fail("TARGET_NOT_ALLOWED")

    now_text = ctx.get("now")
    if now_text:
        now = _parse_ts(now_text)
        offset = int(ctx.get("clock_offset_s") or 0)
        if abs(offset) > 60:
            return _fail("CLOCK_SKEW")
        now = now + _dt.timedelta(seconds=offset)
        expires = _parse_ts(env["ts"]) + _dt.timedelta(seconds=env["ttl_s"])
        if now > expires:
            return _fail("EXPIRED")

    if env["id"] in (ctx.get("seen_ids") or []):
        return _fail("ID_REPLAYED")

    pubkey = ctx.get("pubkey_jwk")
    if pubkey and not verify_es256(env, pubkey):
        return _fail("BAD_SIG")

    return Result(True, level=effective, executable=(intent != "voice_command"))
