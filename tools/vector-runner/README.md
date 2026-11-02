# tools/vector-runner

两侧跑同一份 `protocol/vectors`：

- **Kotlin**：`./gradlew :core:kotlin:test`（测试在 `core/kotlin/src/test`，通过 `vectors.dir` 指向仓库根向量）。
- **Python**：`uv run --with cryptography python tools/vector-runner/py/runner.py`

Python 侧是协议的**参考实现**，不是云端 hub；hub 落地时（另仓）必须跑同一份向量。

## 文件

- `py/jcs.py` — RFC 8785 子集，必须与 `core/kotlin/.../Jcs.kt` 逐字节一致。
- `py/validator.py` — 严格结构校验 + 策略重算 + ES256 验签；
  另有 `verify_cert` / `verify_revocation` 对应 `device-cert.v1` 与 `revocation.v1`。
- `py/runner.py` — 跑向量，失败退出码 1；按目录分派：`cert` / `revocation` 用对应校验器，
  其余类别校验信封。`cert` 用例带 `cert` 字段，`revocation` 用例带 `revocation` 字段。
- `py/gen_vectors.py` — 生成向量（需要 cryptography）。向量是契约，改向量先改 `protocol/spec`。

## 变异测试（验收门禁的阳性对照）

```bash
cp -r protocol/vectors /tmp/vectors-mutated
python - <<'PY'
import json
p='/tmp/vectors-mutated/valid/cases.json'
d=json.load(open(p,encoding='utf-8'))
d['cases'][0]['expect']['level']=99
json.dump(d,open(p,'w',encoding='utf-8'),ensure_ascii=False)
PY
python tools/vector-runner/py/runner.py --vectors /tmp/vectors-mutated   # 必须失败
VECTORS_DIR=/tmp/vectors-mutated ./gradlew :core:kotlin:test             # 必须失败
```

两边都红才说明向量真的在起作用；任一侧仍然绿，说明那条用例没有被校验器覆盖。
