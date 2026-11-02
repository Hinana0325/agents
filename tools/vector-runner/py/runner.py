"""跑金标准向量：Python 参考实现。

用法：
    uv run --with cryptography python runner.py [--vectors DIR]
退出码 0 = 全部通过；1 = 有失败；2 = 环境错误。
"""

import argparse
import glob
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

from validator import validate, verify_approval, verify_cert, verify_receipt, verify_revocation  # noqa: E402


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--vectors", default=os.path.abspath(os.path.join(HERE, "..", "..", "..", "protocol", "vectors")))
    parser.add_argument("--quiet", action="store_true")
    args = parser.parse_args()

    paths = sorted(glob.glob(os.path.join(args.vectors, "*", "cases.json")))
    if not paths:
        print("no vector files under %s" % args.vectors, file=sys.stderr)
        return 2

    total = failed = 0
    for path in paths:
        category = os.path.basename(os.path.dirname(path))
        with open(path, encoding="utf-8") as f:
            cases = json.load(f)["cases"]
        cat_failed = 0
        for case in cases:
            total += 1
            try:
                if category == "cert":
                    got = verify_cert(case["cert"], case.get("context")).as_dict()
                elif category == "revocation":
                    got = verify_revocation(case["revocation"], case.get("context")).as_dict()
                elif category == "approval":
                    got = verify_approval(case["approval"], case["envelope"], case.get("context")).as_dict()
                elif category == "receipt":
                    got = verify_receipt(case["receipt"]).as_dict()
                else:
                    got = validate(case["envelope"], case.get("context")).as_dict()
            except Exception as exc:  # 解析/环境异常按失败计，不中断整轮
                got = {"ok": False, "code": "RUNNER_ERROR", "detail": str(exc)}
            exp = case["expect"]
            same = got.get("ok") == exp["ok"]
            if same and exp["ok"]:
                if category not in ("cert", "revocation", "approval", "receipt"):
                    same = got.get("level") == exp.get("level") and got.get("executable", True) == exp.get("executable", True)
            elif same:
                same = got.get("code") == exp.get("code")
            if not same:
                failed += 1
                cat_failed += 1
                print("FAIL [%s] %s\n  expect %s\n  got    %s" % (category, case["name"], exp, got))
        if not args.quiet:
            print("%-10s %3d cases, %d failed" % (category, len(cases), cat_failed))

    print("TOTAL %d cases, %d failed" % (total, failed))
    return 0 if failed == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
