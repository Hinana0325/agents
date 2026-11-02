#!/usr/bin/env bash
# eval 夜跑入口：数据质量门禁 + 报告落盘（eval/reports/latest.json）
set -euo pipefail

cd "$(dirname "$0")/.."

echo "[nightly] 运行 eval 数据质量门禁（--strict）……"
python3 eval/runners/rule_runner.py --strict

echo "[nightly] 门禁通过。报告已落盘：eval/reports/latest.json"
