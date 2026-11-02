# ADR 0002：协议格式 —— JSON Schema + JCS + ES256

状态：已采纳（2026-11-02）

## 背景

设备之间只交换结构化意图信封，不传自由执行指令。需要一个能被 Android/Kotlin、Python、未来 C# 共同实现、且签名可验的格式。

## 决定

- 结构：JSON，JSON Schema 2020-12 描述；意图名用小写（`open_app` 等 9 种），params 一律 `additionalProperties: false`。
- 规范化：RFC 8785 JCS 的子集——**禁止浮点数**，时间用 RFC 3339 字符串，整数键按 UTF-16 code unit 排序，最小转义。
- 签名：ES256（P-256 + SHA-256），raw `r||s` 的 base64url，签名对象为除 `sig` 外全部字段的 JCS 编码。
- 设备端不用通用 JSON Schema 库，用手写严格解码（kotlinx.serialization，未知字段拒绝），靠向量与 schema 对齐。Python 侧同理，手写校验。

## 后果

- 任何浮点数、未知字段、未知意图都会被拒绝；升级必须走 schema+向量+两端实现。
- 因为不用通用 schema 库，两侧实现可能漂移；`protocol/vectors` 的 180 条用例是唯一判据，CI 阻断漂移。
