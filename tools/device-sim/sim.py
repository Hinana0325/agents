"""device-sim：以“云端 hub”的身份签发信封，投递到电脑端的本地收件箱。

云端 hub 就绪前用它做端到端联调；它只依赖 protocol/vectors 旁边的 JCS 实现，
签名格式与 protocol/spec/signing.md 一致（ES256 + JCS）。

用法（需要 cryptography）：
    uv run --with cryptography python tools/device-sim/sim.py init
    uv run --with cryptography python tools/device-sim/sim.py install --desktop-home ~/.dpa-desktop
    uv run --with cryptography python tools/device-sim/sim.py send --intent save_note \
        --params '{"text":"买牛奶","title":"购物"}' [--level 1]
    uv run --with cryptography python tools/device-sim/sim.py receipts
"""

import argparse
import base64
import datetime as dt
import json
import os
import pathlib
import secrets
import sys
import uuid

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "vector-runner" / "py"))

from jcs import canonicalize  # noqa: E402

DEFAULT_SIM_HOME = pathlib.Path.home() / ".dpa-sim"
DEFAULT_DESKTOP_HOME = pathlib.Path.home() / ".dpa-desktop"


def b64(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).decode().rstrip("=")


def b64d(text: str) -> bytes:
    return base64.urlsafe_b64decode(text + "=" * ((4 - len(text) % 4) % 4))


def load_or_create_key(home: pathlib.Path):
    from cryptography.hazmat.primitives import serialization
    from cryptography.hazmat.primitives.asymmetric import ec

    home.mkdir(parents=True, exist_ok=True)
    key_file = home / "hub-key.pem"
    if key_file.exists():
        return serialization.load_pem_private_key(key_file.read_bytes(), password=None)
    key = ec.generate_private_key(ec.SECP256R1())
    key_file.write_bytes(
        key.private_bytes(
            serialization.Encoding.PEM,
            serialization.PrivateFormat.PKCS8,
            serialization.NoEncryption(),
        )
    )
    return key


def public_jwk(key) -> dict:
    nums = key.public_key().public_numbers()
    return {
        "kty": "EC",
        "crv": "P-256",
        "x": b64(nums.x.to_bytes(32, "big")),
        "y": b64(nums.y.to_bytes(32, "big")),
    }


def sign(key, payload: dict) -> str:
    from cryptography.hazmat.primitives import hashes
    from cryptography.hazmat.primitives.asymmetric import ec, utils

    der = key.sign(canonicalize(payload).encode("utf-8"), ec.ECDSA(hashes.SHA256()))
    r, s = utils.decode_dss_signature(der)
    return b64(r.to_bytes(32, "big") + s.to_bytes(32, "big"))


def uuid7() -> str:
    value = uuid.uuid4()
    raw = bytearray(value.bytes)
    raw[6] = (raw[6] & 0x0F) | 0x70
    raw[8] = (raw[8] & 0x3F) | 0x80
    return str(uuid.UUID(bytes=bytes(raw)))


def utc_now() -> str:
    return dt.datetime.now(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def read_json(path: pathlib.Path, default):
    if not path.exists():
        return default
    return json.loads(path.read_text(encoding="utf-8"))


def write_json(path: pathlib.Path, data) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def cmd_init(args) -> int:
    home = pathlib.Path(args.home).expanduser()
    key = load_or_create_key(home)
    print(json.dumps({"key_file": str(home / "hub-key.pem"), "public_key_jwk": public_jwk(key)},
                     ensure_ascii=False, indent=2))
    return 0


def cmd_install(args) -> int:
    home = pathlib.Path(args.home).expanduser()
    key = load_or_create_key(home)
    desktop_home = pathlib.Path(args.desktop_home).expanduser()
    config_path = desktop_home / "config.json"
    config = read_json(config_path, None)
    if config is None:
        print("没有找到 %s；先运行一次电脑端 status 生成配置。" % config_path, file=sys.stderr)
        return 2
    config["hub_pubkey"] = public_jwk(key)
    config.setdefault("hub_kid", "hub-test-1")
    if args.dry_run:
        config["dry_run"] = True
    write_json(config_path, config)
    print("已把 hub 公钥写入 %s（kid=%s）" % (config_path, config.get("hub_kid")))
    return 0


def cmd_send(args) -> int:
    home = pathlib.Path(args.home).expanduser()
    key = load_or_create_key(home)
    desktop_home = pathlib.Path(args.desktop_home).expanduser()
    config = read_json(desktop_home / "config.json", {})
    device = args.device or config.get("device_id")
    if not device:
        print("不知道目标设备：传 --device 或先配置电脑端", file=sys.stderr)
        return 2
    params = json.loads(args.params)
    envelope = {
        "v": 1,
        "id": uuid7(),
        "ts": utc_now(),
        "ttl_s": args.ttl,
        "from": {"role": "hub", "kid": "hub-test-1"},
        "to": {"device": device},
        "intent": args.intent,
        "params": params,
        "origin": {"kind": args.origin, "tainted": args.tainted},
        "level": args.level,
    }
    if args.trace:
        envelope["trace"] = args.trace
    envelope["sig"] = sign(key, envelope)
    inbox = desktop_home / "inbox"
    inbox.mkdir(parents=True, exist_ok=True)
    path = inbox / (envelope["id"] + ".json")
    write_json(path, envelope)
    print(json.dumps({"written": str(path), "id": envelope["id"], "intent": args.intent,
                      "level": args.level}, ensure_ascii=False))
    return 0


def cmd_receipts(args) -> int:
    desktop_home = pathlib.Path(args.desktop_home).expanduser()
    outbox = desktop_home / "outbox"
    if not outbox.is_dir():
        print("还没有回执。")
        return 0
    files = sorted(outbox.glob("*.json"))
    if not files:
        print("还没有回执。")
        return 0
    for file in files:
        receipt = json.loads(file.read_text(encoding="utf-8"))
        code = (receipt.get("error") or {}).get("code")
        print("%s  %-16s %s" % (receipt.get("envelope_id"), receipt.get("status"),
                                code or ""))
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description="device-sim：本地信封通道的发送端")
    parser.add_argument("--home", default=str(DEFAULT_SIM_HOME), help="sim 数据目录（私钥）")
    sub = parser.add_subparsers(dest="command", required=True)

    p_init = sub.add_parser("init", help="生成/查看 hub 测试密钥")
    p_init.set_defaults(func=cmd_init)

    p_install = sub.add_parser("install", help="把 hub 公钥写进电脑端 config.json")
    p_install.add_argument("--desktop-home", default=str(DEFAULT_DESKTOP_HOME))
    p_install.add_argument("--dry-run", action="store_true", help="同时设置电脑端 dry_run=true")
    p_install.set_defaults(func=cmd_install)

    p_send = sub.add_parser("send", help="签发一条信封并投递到电脑收件箱")
    p_send.add_argument("--desktop-home", default=str(DEFAULT_DESKTOP_HOME))
    p_send.add_argument("--device", default=None, help="目标 device_id（默认取电脑端配置）")
    p_send.add_argument("--intent", required=True)
    p_send.add_argument("--params", default="{}", help="JSON 对象")
    p_send.add_argument("--level", type=int, default=1)
    p_send.add_argument("--ttl", type=int, default=300)
    p_send.add_argument("--origin", default="user_text",
                        choices=["user_text", "user_voice", "scheduled", "derived"])
    p_send.add_argument("--tainted", action="store_true")
    p_send.add_argument("--trace", default=None)
    p_send.set_defaults(func=cmd_send)

    p_receipts = sub.add_parser("receipts", help="列出电脑端回执")
    p_receipts.add_argument("--desktop-home", default=str(DEFAULT_DESKTOP_HOME))
    p_receipts.set_defaults(func=cmd_receipts)

    args = parser.parse_args()
    return args.func(args)


if __name__ == "__main__":
    raise SystemExit(main())
