# 版本协商

- `v` 是大版本，只有 1。不认识的 `v` 一律 `UNSUPPORTED_VERSION`，不做兼容猜测。
- 小版本只允许新增**可选**字段；设备在 capability 里报告 `proto_minor`，云端只发设备认识的字段。
- 设备对未知字段一律拒绝（`UNKNOWN_FIELD`）——这是安全特性，不是兼容性缺陷。新增字段必须同时改 schema、策略、向量、两端校验器。
- 协议升级演练要求 N-1 设备连 N 版云端能正常工作。
- 本仓库 `protocol/` 是唯一契约来源；`core/kotlin` 与 `tools/vector-runner/py` 是它的两份实现，`tools/` 的实现同时作为未来云端 hub 的参考实现。
