"""协议 v1 属性测试（Hypothesis）：DEVELOPMENT.md 第 10 节不变量的 Python 侧镜像。

与 core/kotlin 的 kotest 属性测试（core/kotlin/src/test/kotlin/.../property/）逐条对应：

1. 接受则 schema 合法、意图在白名单、无未知字段（未知字段拒绝 + 意图白名单）；
2. 加 taint 或换成语音来源，级别只升不降，且上限 3；
3. 任何一位变异则验签失败（自生成 P-256 密钥，变异签名/负载字节）；
4. handoff 提案深度不超过 1（proposal 不得是 task_handoff / voice_command）。

运行：
    uv run --with hypothesis --with cryptography python tools/vector-runner/py/property_test.py

失败时进程退出码非 0；复现失败用 hypothesis 打印的 seed 与 falsifying example。
"""

import base64
import copy
import unittest

from hypothesis import given, settings
from hypothesis import strategies as st

from jcs import canonicalize
from validator import (
    HANDOFF_PROPOSAL_INTENTS,
    INTENTS,
    compute_level,
    envelope_hash,
    validate,
    verify_es256,
)

# CI 慢机器上不因单例超时而误报；例数取 150（100~200 区间中值）。
settings.register_profile("default", max_examples=150, deadline=None)
settings.load_profile("default")

# ---------------------------------------------------------------- 基建

# 每个意图的最小合法 params，构造信封变体时复用（与 core 侧 PropertyFixtures 对齐）。
MINIMAL_PARAMS = {
    "chat": {"text": "你好"},
    "web_search": {"query": "天气"},
    "save_note": {"text": "笔记"},
    "set_timer": {"seconds": 60},
    "open_app": {"app_id": "com.example.app"},
    "set_alarm": {"hour": 7, "minute": 30},
    "create_event": {"title": "评审", "begin_ms": 1, "end_ms": 2},
    "task_handoff": {
        "target_device_id": "pc-1",
        "proposal": {"intent": "chat", "params": {"text": "你好"}, "level": 1},
    },
    "voice_command": {"transcript": "明天早上七点叫我"},
}


def make_envelope(intent, params, level=1, tainted=False, kind="user_text"):
    """构造一份结构合法的信封（sig 为占位串；默认上下文不验签）。"""
    return {
        "v": 1,
        "id": "0193f0a8-9c1b-7001-8001-000000000001",
        "ts": "2026-11-02T10:00:00Z",
        "ttl_s": 300,
        "from": {"role": "hub", "kid": "hub-test-1"},
        "to": {"device": "phone-1"},
        "intent": intent,
        "params": copy.deepcopy(params),
        "origin": {"kind": kind, "tainted": tainted},
        "level": level,
        "sig": "A" * 86,
    }


def _b64url(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).decode("ascii").rstrip("=")


def _b64url_decode(text: str) -> bytes:
    pad = "=" * ((4 - len(text) % 4) % 4)
    return base64.urlsafe_b64decode(text + pad)


@st.composite
def _intent_with_params(draw):
    """随机挑一个意图并配套最小合法 params，保证 compute_level 可安全调用。"""
    intent = draw(st.sampled_from(INTENTS))
    return intent, copy.deepcopy(MINIMAL_PARAMS[intent])


# ---------------------------------------------------------------- 不变量 1

class UnknownFieldRejection(unittest.TestCase):
    """不变量 1：接受则无未知字段（严格解码，ignoreUnknownKeys=false 语义）。"""

    @given(
        suffix=st.text(alphabet="abcdefghijklmnopqrstuvwxyz", min_size=1, max_size=12),
        value=st.one_of(st.integers(), st.booleans(), st.text(max_size=20)),
        location=st.sampled_from(["envelope", "params", "origin", "from", "to"]),
    )
    def test_unknown_field_is_rejected(self, suffix, value, location):
        env = make_envelope("chat", {"text": "你好"})
        # 阳性对照：干净信封必须通过。
        self.assertTrue(validate(env).ok)

        field = "zz_" + suffix + "x"  # zz_ 前缀保证不与任何白名单字段冲突
        target = {
            "envelope": env,
            "params": env["params"],
            "origin": env["origin"],
            "from": env["from"],
            "to": env["to"],
        }[location]
        target[field] = value

        result = validate(env)
        self.assertFalse(result.ok, msg=f"location={location} field={field} 被接受了")
        self.assertEqual(result.code, "UNKNOWN_FIELD")

    @given(suffix=st.text(alphabet="abcdefghijklmnopqrstuvwxyz", min_size=1, max_size=12))
    def test_unknown_field_in_handoff_proposal_is_rejected(self, suffix):
        """proposal 层同样严格（对应 task_handoff.v1.json 的 additionalProperties=false）。"""
        env = make_envelope("task_handoff", MINIMAL_PARAMS["task_handoff"], level=2)
        self.assertTrue(validate(env).ok)
        env["params"]["proposal"]["zz_" + suffix + "x"] = 1
        result = validate(env)
        self.assertFalse(result.ok)
        self.assertEqual(result.code, "UNKNOWN_FIELD")


class IntentWhitelist(unittest.TestCase):
    """不变量 1：意图必须在白名单内。"""

    @given(intent=st.sampled_from(INTENTS))
    def test_whitelisted_intent_passes_structure(self, intent):
        # create_event/task_handoff/voice_command 基础级别为 2（LEVEL_BASE）。
        level = 2 if intent in ("create_event", "task_handoff", "voice_command") else 1
        result = validate(make_envelope(intent, MINIMAL_PARAMS[intent], level=level))
        self.assertTrue(result.ok, msg=f"intent={intent} code={result.code}")

    @given(bogus=st.text(alphabet="abcdefghijklmnopqrstuvwxyz", min_size=1, max_size=20))
    def test_unknown_intent_is_rejected(self, bogus):
        intent = bogus + "x"  # 追加 x 保证一定不在白名单内
        result = validate(make_envelope(intent, {"text": "你好"}))
        self.assertFalse(result.ok)
        self.assertEqual(result.code, "UNKNOWN_INTENT")


# ---------------------------------------------------------------- 不变量 2

class TaintMonotonicity(unittest.TestCase):
    """不变量 2：加 taint 或换成语音来源，级别只升不降，且上限 3。"""

    @given(data=_intent_with_params())
    def test_tainted_level_only_rises_and_caps_at_3(self, data):
        intent, params = data
        base = compute_level(intent, params, {"origin": {"kind": "user_text", "tainted": False}}, {})
        tainted = compute_level(intent, params, {"origin": {"kind": "user_text", "tainted": True}}, {})
        voice = compute_level(intent, params, {"origin": {"kind": "user_voice", "tainted": False}}, {})

        self.assertGreaterEqual(tainted, base, msg=f"intent={intent} taint 后级别下降")
        self.assertLessEqual(tainted, 3, msg=f"intent={intent} taint 后超过上限 3")
        self.assertGreaterEqual(voice, base, msg=f"intent={intent} 语音来源级别下降")
        self.assertGreaterEqual(base, 1)

    @given(data=_intent_with_params(), envelope_level=st.integers(1, 3))
    def test_envelope_level_below_policy_is_rejected(self, data, envelope_level):
        """信封自带 level 低于 taint 后的重算值时必须整条拒绝（LEVEL_BELOW_POLICY）。"""
        intent, params = data
        env = make_envelope(intent, params, level=envelope_level, tainted=True)
        computed = compute_level(intent, params, env, {})
        result = validate(env)
        if envelope_level < computed:
            self.assertFalse(result.ok)
            self.assertEqual(result.code, "LEVEL_BELOW_POLICY")
        else:
            self.assertTrue(result.ok, msg=result.code)
            self.assertEqual(result.level, max(envelope_level, computed))


# ---------------------------------------------------------------- 不变量 3

def _int_to_32(value: int) -> bytes:
    return value.to_bytes(32, "big")


class SignatureMutation(unittest.TestCase):
    """不变量 3：任何一位变异则验签失败（ES256 over JCS(env minus sig)）。"""

    @classmethod
    def setUpClass(cls):
        from cryptography.hazmat.primitives import hashes
        from cryptography.hazmat.primitives.asymmetric import ec, utils

        cls._ec = ec
        cls._hashes = hashes
        cls._utils = utils
        cls._priv = ec.generate_private_key(ec.SECP256R1())
        pub = cls._priv.public_key().public_numbers()
        cls._jwk = {
            "kty": "EC",
            "crv": "P-256",
            "x": _b64url(_int_to_32(pub.x)),
            "y": _b64url(_int_to_32(pub.y)),
        }

    def _sign(self, env):
        """用自生成私钥对 JCS(env minus sig) 做 ES256 签名，raw r||s 的 base64url。"""
        payload = {k: v for k, v in env.items() if k != "sig"}
        der = self._priv.sign(
            canonicalize(payload).encode("utf-8"),
            self._ec.ECDSA(self._hashes.SHA256()),
        )
        r, s = self._utils.decode_dss_signature(der)
        env["sig"] = _b64url(_int_to_32(r) + _int_to_32(s))

    @given(
        mutation=st.sampled_from(
            ["sig_bit_flip", "sig_byte_xor", "text_append", "level_change", "ts_append"]
        ),
        bit_index=st.integers(0, 511),
        noise=st.text(alphabet="abcdefghijklmnopqrstuvwxyz0123456789", min_size=1, max_size=16),
    )
    def test_any_single_mutation_breaks_verification(self, mutation, bit_index, noise):
        env = make_envelope("chat", {"text": "属性-" + noise})
        self._sign(env)
        # 阳性对照：未变异信封必须验签通过，且 envelope_hash 稳定。
        self.assertTrue(verify_es256(env, self._jwk))
        original_hash = envelope_hash(env)

        mutated = copy.deepcopy(env)
        if mutation in ("sig_bit_flip", "sig_byte_xor"):
            raw = bytearray(_b64url_decode(mutated["sig"]))
            if mutation == "sig_bit_flip":
                raw[bit_index // 8] ^= 1 << (bit_index % 8)  # 翻转 1 个比特
            else:
                raw[bit_index % 64] ^= 0x5A  # 异或 1 个字节
            mutated["sig"] = _b64url(bytes(raw))
        elif mutation == "text_append":
            mutated["params"]["text"] += "x"  # 必然改变 JCS 字节
            self.assertNotEqual(envelope_hash(mutated), original_hash)
        elif mutation == "level_change":
            mutated["level"] = 2  # 1 -> 2，必然改变 JCS 字节
            self.assertNotEqual(envelope_hash(mutated), original_hash)
        else:  # ts_append
            mutated["ts"] += "0"  # 必然改变 JCS 字节
            self.assertNotEqual(envelope_hash(mutated), original_hash)

        self.assertFalse(
            verify_es256(mutated, self._jwk),
            msg=f"mutation={mutation} bit={bit_index} 变异后验签仍然通过",
        )

    @given(noise=st.text(alphabet="abcdefghijklmnopqrstuvwxyz", min_size=1, max_size=16))
    def test_forged_random_signature_fails(self, noise):
        """随机 64 字节假签名必须验签失败。"""
        env = make_envelope("chat", {"text": "伪造-" + noise})
        self._sign(env)
        self.assertTrue(verify_es256(env, self._jwk))

        forged = copy.deepcopy(env)
        forged["sig"] = _b64url(noise.encode("utf-8").ljust(64, b"\x00")[:64])
        self.assertNotEqual(forged["sig"], env["sig"])
        self.assertFalse(verify_es256(forged, self._jwk))


# ---------------------------------------------------------------- 不变量 4

class HandoffDepth(unittest.TestCase):
    """不变量 4：handoff 提案深度不超过 1。"""

    @given(p_intent=st.sampled_from(INTENTS), p_level=st.integers(1, 3))
    def test_proposal_depth_at_most_one(self, p_intent, p_level):
        # 外层 level 取 max(2, p_level)，避免 LEVEL_BELOW_POLICY 干扰本性质。
        params = {
            "target_device_id": "pc-1",
            "proposal": {
                "intent": p_intent,
                "params": copy.deepcopy(MINIMAL_PARAMS[p_intent]),
                "level": p_level,
            },
        }
        env = make_envelope("task_handoff", params, level=max(2, p_level))
        result = validate(env)
        if p_intent in ("task_handoff", "voice_command"):
            # 深度 2：proposal 里再嵌套 task_handoff / voice_command 必须拒绝。
            self.assertFalse(result.ok, msg=f"proposal intent={p_intent} 竟被接受")
            self.assertEqual(result.code, "HANDOFF_DEPTH")
        else:
            # 深度恰好 1 的合法提案必须接受，重算级别 = max(2, proposal.level)。
            self.assertTrue(result.ok, msg=f"proposal intent={p_intent} code={result.code}")
            self.assertEqual(result.level, max(2, p_level))

    @given(bogus=st.text(alphabet="abcdefghijklmnopqrstuvwxyz", min_size=1, max_size=20))
    def test_proposal_intent_outside_whitelist_rejected(self, bogus):
        """提案意图不在 7 个白名单内时以 UNKNOWN_INTENT 拒绝（防绕过深度检查）。"""
        params = {
            "target_device_id": "pc-1",
            "proposal": {"intent": bogus + "x", "params": {"text": "你好"}, "level": 1},
        }
        result = validate(make_envelope("task_handoff", params, level=3))
        self.assertFalse(result.ok)
        self.assertEqual(result.code, "UNKNOWN_INTENT")


# ---------------------------------------------------------------- 入口

def main():
    unittest.main(verbosity=2)


if __name__ == "__main__":
    main()
