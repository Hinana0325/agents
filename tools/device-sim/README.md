# tools/device-sim

以“云端 hub”的身份签发信封，投递到电脑端的本地收件箱。云端 hub 与手机传输层就绪前，
用它在单机上端到端验证设备侧：验签、策略重算、执行、L2 审批、回执。

签名格式与 `protocol/spec/signing.md` 一致：ES256 + JCS(envelope minus sig)，
raw `r||s` 的 base64url。

## 用法

```bash
# 生成/查看 hub 测试密钥（~/.dpa-sim/hub-key.pem）
uv run --with cryptography python tools/device-sim/sim.py init

# 把 hub 公钥写进电脑端配置（--dry-run 同时让电脑端不真的开浏览器/起进程）
uv run --with cryptography python tools/device-sim/sim.py install \
    --desktop-home ~/.dpa-desktop --dry-run

# 发一条信封
uv run --with cryptography python tools/device-sim/sim.py send \
    --intent save_note --params '{"text":"买牛奶","title":"购物"}' --level 1

# 看回执
uv run --with cryptography python tools/device-sim/sim.py receipts
```

## 参数

- `--device`：目标 device_id，默认取电脑端 `config.json` 里的 `device_id`。
- `--level`：信封携带的确认级；低于设备重算结果会被拒（`LEVEL_BELOW_POLICY`）。
- `--origin` / `--tainted`：验证污点加级。
- `--ttl`：默认 300 秒。

## 手机端 mock hub

`mock_hub.py` 用同一套签名格式提供一个最小 HTTP hub，供手机拉取：

```bash
# 启动（打印 JWK；令牌默认 test-token，kid 固定 hub-test-1）
uv run --with cryptography python tools/device-sim/mock_hub.py serve --host 0.0.0.0 --port 8899

# 先自测：签发一条信封并用 Python 参考校验器验证
uv run --with cryptography python tools/device-sim/mock_hub.py selftest

# 投递一条（device 用手机配对页显示的 device id）
curl -X POST http://127.0.0.1:8899/test/enqueue -H 'Content-Type: application/json' \
  -d '{"intent":"chat","params":{"text":"hello"},"device":"phone-bbb3f3c4"}'
# 查看队列、已投递与回执
curl http://127.0.0.1:8899/test/state
```

手机设置里填 `http://10.0.2.2:8899`（模拟器）或电脑局域网地址、Bearer 令牌、启动时打印的 JWK。
`GET /v1/device/inbox` 与 `POST /v1/device/receipts` 需要令牌。

## 注意

- 测试私钥只在 `~/.dpa-sim/` 与系统临时目录，不进仓库；它只是测试 hub，不是真实云端密钥。
- 本地目录通道与 mock hub 都是开发联调用的，不是安全传输；不要在生产环境使用。
