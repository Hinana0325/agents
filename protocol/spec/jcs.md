# JCS 规范化（本项目使用的子集）

签名对象是除 `sig` 外全部字段的规范 JSON。规则按 RFC 8785 执行，但本项目只使用它的子集：

- **不允许浮点数。** 信封里的数值只有整数（时间用 RFC 3339 字符串或 epoch 毫秒整数）。任何浮点数字面量在解析阶段就判 `BAD_TYPE`。
- **对象按键排序**，比较单位是 UTF-16 code unit（Java/Kotlin 的 `String.compareTo` 天然如此；Python 用 `s.encode("utf-16-be")` 作为键）。
- **无空白**：`{"a":1,"b":2}`，不产生空格或换行。
- **字符串转义**：只转义 `"`、`\` 与 U+0000–U+001F。其中 `\b \t \n \f \r` 用短形式，其余控制字符用 `\u00XX`（小写十六进制）。非 ASCII（含中文）**不转义**，按 UTF-8 原样输出；`/` 不转义。
- **布尔与 null**：`true` / `false` / `null`。
- **数组**保持原顺序，不排序。

实现位于 `core/kotlin/.../Jcs.kt` 与 `tools/vector-runner/py/jcs.py`。两边的输出必须逐字节一致，向量 `signing/` 用固定密钥对做交叉验证。

不作为签名对象的内容：`sig` 本身。`approval.v1.json` 的签名对象是其 6 个字段（`envelope_hash, decision, level, ts, nonce` 加 `v`）的 JCS 编码，不含 `sig`。
