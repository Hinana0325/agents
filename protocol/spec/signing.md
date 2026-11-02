# 签名与密钥

- 算法：ES256（ECDSA P-256 + SHA-256）。P-256 是 Android Keystore / StrongBox、Windows TPM、Secure Enclave 的最大公约数。
- 签名编码：JWS 风格 raw `r || s`（各 32 字节，共 64 字节），base64url 无 padding。
- 签名对象：`JCS(envelope 去掉 sig)`。验签前必须先做严格 schema 校验——不可信输入不进签名路径。
- `from.kid` 指向签名密钥；设备维护受信 kid 列表（hub 公钥配对时固定，设备公钥由手机签发）。
- 设备侧同时要求：`kid` 未吊销、`ts + ttl_s` 未过期、`id` 未处理过（幂等键）。
- 时钟：握手估计偏移，偏移超过 60 秒拒收并提示校时（`CLOCK_SKEW`）。

## 测试密钥

`protocol/vectors/signing/` 使用一把固定的 P-256 测试私钥（标量硬编码在 `tools/vector-runner/py/gen_signing_vectors.py`），**只用于向量，任何环境都不得使用**。对应的公钥 JWK 写在向量文件的 `context.pubkey_jwk` 里。

## 审批证明

L3 的审批由手机上的审批密钥签名，每次使用要求生物识别。证明绑定 `envelope_hash`（SHA-256 of JCS(envelope minus sig)）与一次性 `nonce`，执行端校验哈希、级别、受信公钥、过期与重放。云端无法伪造或挪用审批。
