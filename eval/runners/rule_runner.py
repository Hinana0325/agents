#!/usr/bin/env python3
"""eval 评测基线 runner：数据质量门禁 + 打分后端预留接口。

模式一（默认，无参数）：对 eval/utterances/*.jsonl 与 eval/injection/cases.jsonl
做数据质量校验与统计，输出控制台摘要并落盘 eval/reports/latest.json。
--strict 时任何校验失败或配额不达标都以退出码 1 结束。

模式二（--backend <name>）：打分后端预留接口。
TODO：打分后端等云端大模型网关落地后接入。注意：禁止在本 runner 里复刻
core 的 LocalIntentParser 规则——两套实现必然漂移；话术打分一律走同一网关，
规则快路径的正确性由 protocol/vectors 与本数据集的门禁共同保证。

纯标准库实现，CI 环境不安装任何第三方依赖。
"""

from __future__ import annotations

import argparse
import json
import sys
from collections import Counter
from pathlib import Path
from typing import Any

# ---------------------------------------------------------------- 常量定义

# 9 个协议白名单意图（与 protocol/schema/intents/ 的文件名一一对应），
# 文件名形如 chat.v1.json，加载时取第一段作为意图名，不做硬编码。
SCHEMA_DIRNAME = "protocol/schema/intents"

# 期望值额外放行的两个非执行结果：应澄清 / 应拒绝。
# 它们不在 9 意图白名单内，runner 单独统计。
NON_INTENT_EXPECTATIONS = {"clarify", "refuse"}

# utterances 行的必需字段
UTTERANCE_REQUIRED_FIELDS = ("id", "text", "expect_intent", "expect_params", "tags", "origin")

# injection 行的必需字段
INJECTION_REQUIRED_FIELDS = ("id", "carrier", "text", "technique", "expect")

# carrier 枚举：对齐 DEVELOPMENT.md 第 9 节"网页、备忘、日历标题、文件名里夹带指令"
ALLOWED_CARRIERS = {"note", "calendar_title", "filename", "web_text", "app_name"}

# technique 枚举：注入手法标注，新增手法须先在此登记
ALLOWED_TECHNIQUES = {
    "instruction_override",  # 覆盖指令
    "role_play",             # 角色扮演
    "fake_system",           # 伪造系统消息
    "data_exfil",            # 诱导外发
    "chained_intent",        # 链式意图
    "encoding",              # 编码混淆（base64/URL编码/零宽字符等）
}

# origin 枚举：对齐协议信封的输入来源标记
ALLOWED_ORIGINS = {"user_text", "user_voice"}

# 文本长度上限（与协议对长文本字段的约束量级一致）
MAX_TEXT_LEN = 2000

# --strict 配额门禁
MIN_TOTAL_UTTERANCES = 300
MIN_PER_INTENT = 30
MIN_CLARIFY = 30
MIN_INJECTION = 100


# ---------------------------------------------------------------- 工具类型

class Problem:
    """一条校验问题：文件、行号（0 表示文件级）、描述。"""

    def __init__(self, file: str, line: int, message: str) -> None:
        self.file = file
        self.line = line
        self.message = message

    def __str__(self) -> str:
        return f"{self.file}:{self.line}: {self.message}" if self.line else f"{self.file}: {self.message}"


# ---------------------------------------------------------------- schema 加载

def load_intent_param_keys(repo_root: Path, problems: list[Problem]) -> dict[str, set[str]]:
    """启动时扫描 protocol/schema/intents/*.json，解析每个意图的顶层 properties 键集合。

    键名一律以 schema 为权威来源，本 runner 不维护硬编码键名表。
    """
    keys: dict[str, set[str]] = {}
    schema_dir = repo_root / SCHEMA_DIRNAME
    if not schema_dir.is_dir():
        problems.append(Problem(SCHEMA_DIRNAME, 0, "schema 目录不存在，无法解析意图参数键名"))
        return keys
    for path in sorted(schema_dir.glob("*.json")):
        intent = path.name.split(".")[0]
        try:
            with path.open(encoding="utf-8") as fh:
                schema = json.load(fh)
        except (json.JSONDecodeError, OSError) as exc:
            problems.append(Problem(str(path), 0, f"schema 解析失败：{exc}"))
            continue
        props = schema.get("properties", {})
        if not isinstance(props, dict):
            problems.append(Problem(str(path), 0, "schema 缺少 properties 对象"))
            continue
        keys[intent] = set(props.keys())
    return keys


# ---------------------------------------------------------------- JSONL 读取

def read_jsonl(path: Path, problems: list[Problem]) -> list[tuple[int, dict[str, Any]]]:
    """逐行读取 JSONL，返回 (行号, 对象) 列表；解析失败记为问题。"""
    rows: list[tuple[int, dict[str, Any]]] = []
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except OSError as exc:
        problems.append(Problem(str(path), 0, f"文件读取失败：{exc}"))
        return rows
    for lineno, raw in enumerate(lines, start=1):
        if not raw.strip():
            continue
        try:
            obj = json.loads(raw)
        except json.JSONDecodeError as exc:
            problems.append(Problem(str(path), lineno, f"JSON 解析失败：{exc}"))
            continue
        if not isinstance(obj, dict):
            problems.append(Problem(str(path), lineno, "行必须是 JSON 对象"))
            continue
        rows.append((lineno, obj))
    return rows


# ---------------------------------------------------------------- utterances 校验

def validate_utterances(
    utt_dir: Path,
    schema_keys: dict[str, set[str]],
    problems: list[Problem],
) -> tuple[Counter, Counter, dict[str, int]]:
    """校验话术集，返回 (每意图条数, tags 分布, 重复文本计数)。"""
    per_intent: Counter = Counter()
    tag_counter: Counter = Counter()
    seen_ids: dict[str, str] = {}      # id -> 首次出现文件，全局唯一
    seen_texts: dict[str, str] = {}    # text -> 首次出现文件，重复只警告
    dup_texts = 0

    expected_files = {name for name in schema_keys} | {"clarify"}
    actual_files = {p.stem for p in sorted(utt_dir.glob("*.jsonl"))} if utt_dir.is_dir() else set()
    if not actual_files:
        problems.append(Problem(str(utt_dir), 0, "utterances 目录不存在或没有任何 .jsonl 文件"))
    for missing in sorted(expected_files - actual_files):
        problems.append(Problem("eval/utterances", 0, f"缺少话术文件：{missing}.jsonl"))

    for path in sorted(utt_dir.glob("*.jsonl")):
        fname = path.name
        for lineno, row in read_jsonl(path, problems):
            loc = f"{fname}:{lineno}"
            # 必需字段齐全
            for field in UTTERANCE_REQUIRED_FIELDS:
                if field not in row:
                    problems.append(Problem(fname, lineno, f"缺少必需字段 {field}"))
            if any(field not in row for field in UTTERANCE_REQUIRED_FIELDS):
                continue
            # id 全局唯一
            rid = row["id"]
            if not isinstance(rid, str) or not rid:
                problems.append(Problem(fname, lineno, "id 必须是非空字符串"))
            elif rid in seen_ids:
                problems.append(Problem(fname, lineno, f"id 重复：{rid}（首次出现在 {seen_ids[rid]}）"))
            else:
                seen_ids[rid] = loc
            # text 非空且长度合理
            text = row["text"]
            if not isinstance(text, str) or not text.strip():
                problems.append(Problem(fname, lineno, "text 必须是非空字符串"))
            elif len(text) > MAX_TEXT_LEN:
                problems.append(Problem(fname, lineno, f"text 超长：{len(text)} > {MAX_TEXT_LEN}"))
            else:
                norm = " ".join(text.split())
                if norm in seen_texts:
                    dup_texts += 1
                    problems.append(
                        Problem(fname, lineno, f"文本与 {seen_texts[norm]} 重复（警告级）：{text[:30]}…")
                    )
                else:
                    seen_texts[norm] = loc
            # expect_intent 白名单：9 意图 + clarify/refuse
            expect = row["expect_intent"]
            if expect not in schema_keys and expect not in NON_INTENT_EXPECTATIONS:
                problems.append(Problem(fname, lineno, f"expect_intent 非白名单值：{expect}"))
                continue
            # expect_params 键名必须 ⊆ 对应 schema properties 键集合
            params = row["expect_params"]
            if not isinstance(params, dict):
                problems.append(Problem(fname, lineno, "expect_params 必须是对象"))
            elif expect in schema_keys:
                illegal = set(params.keys()) - schema_keys[expect]
                if illegal:
                    problems.append(
                        Problem(fname, lineno, f"expect_params 含 schema 之外的键 {sorted(illegal)}（意图 {expect}）")
                    )
            elif params:
                problems.append(Problem(fname, lineno, f"{expect} 的 expect_params 必须为空对象 {{}}"))
            # tags：字符串数组、小写英文
            tags = row["tags"]
            if not isinstance(tags, list) or not all(isinstance(t, str) for t in tags):
                problems.append(Problem(fname, lineno, "tags 必须是字符串数组"))
            else:
                for tag in tags:
                    if tag != tag.lower():
                        problems.append(Problem(fname, lineno, f"tag 必须小写：{tag}"))
                    tag_counter[tag] += 1
            # origin 枚举
            if row["origin"] not in ALLOWED_ORIGINS:
                problems.append(Problem(fname, lineno, f"origin 非法：{row['origin']}（仅允许 {sorted(ALLOWED_ORIGINS)}）"))
            # 统计
            per_intent[expect] += 1
    return per_intent, tag_counter, dup_texts


# ---------------------------------------------------------------- injection 校验

def validate_injection(
    cases_path: Path,
    problems: list[Problem],
) -> tuple[Counter, Counter, int]:
    """校验注入集，返回 (每 carrier 条数, 每 technique 条数, 重复文本计数)。"""
    per_carrier: Counter = Counter()
    per_technique: Counter = Counter()
    seen_ids: dict[str, str] = {}
    seen_texts: dict[str, str] = {}
    dup_texts = 0

    if not cases_path.is_file():
        problems.append(Problem(str(cases_path), 0, "注入集文件不存在"))
        return per_carrier, per_technique, dup_texts

    for lineno, row in read_jsonl(cases_path, problems):
        for field in INJECTION_REQUIRED_FIELDS:
            if field not in row:
                problems.append(Problem(cases_path.name, lineno, f"缺少必需字段 {field}"))
        if any(field not in row for field in INJECTION_REQUIRED_FIELDS):
            continue
        # id 全局唯一
        rid = row["id"]
        if not isinstance(rid, str) or not rid:
            problems.append(Problem(cases_path.name, lineno, "id 必须是非空字符串"))
        elif rid in seen_ids:
            problems.append(Problem(cases_path.name, lineno, f"id 重复：{rid}（首次出现在行 {seen_ids[rid]}）"))
        else:
            seen_ids[rid] = str(lineno)
        # text 非空且长度合理
        text = row["text"]
        if not isinstance(text, str) or not text.strip():
            problems.append(Problem(cases_path.name, lineno, "text 必须是非空字符串"))
        elif len(text) > MAX_TEXT_LEN:
            problems.append(Problem(cases_path.name, lineno, f"text 超长：{len(text)} > {MAX_TEXT_LEN}"))
        else:
            norm = " ".join(text.split())
            if norm in seen_texts:
                dup_texts += 1
                problems.append(Problem(cases_path.name, lineno, f"文本与行 {seen_texts[norm]} 重复（警告级）"))
            else:
                seen_texts[norm] = str(lineno)
        # carrier 枚举
        if row["carrier"] not in ALLOWED_CARRIERS:
            problems.append(Problem(cases_path.name, lineno, f"carrier 非法：{row['carrier']}（仅允许 {sorted(ALLOWED_CARRIERS)}）"))
        # technique 枚举
        if row["technique"] not in ALLOWED_TECHNIQUES:
            problems.append(Problem(cases_path.name, lineno, f"technique 非法：{row['technique']}（仅允许 {sorted(ALLOWED_TECHNIQUES)}）"))
        # expect.auto_exec 必须显式为 false——注入文本作为不可信上下文，
        # 不得导致任何 L2+ 动作自动执行，也不得被快路径解析成可执行意图。
        expect = row["expect"]
        if not isinstance(expect, dict) or expect.get("auto_exec") is not False:
            problems.append(Problem(cases_path.name, lineno, "expect.auto_exec 必须显式为 false"))
        per_carrier[row["carrier"]] += 1
        per_technique[row["technique"]] += 1
    return per_carrier, per_technique, dup_texts


# ---------------------------------------------------------------- 报告输出

def build_report(
    per_intent: Counter,
    tag_counter: Counter,
    utt_dup: int,
    per_carrier: Counter,
    per_technique: Counter,
    inj_dup: int,
) -> dict[str, Any]:
    """汇总统计为可落盘的 JSON 报告结构。"""
    total_utt = sum(per_intent.values())
    total_inj = sum(per_carrier.values())
    clarify = per_intent.get("clarify", 0) + per_intent.get("refuse", 0)
    return {
        "utterances": {
            "total": total_utt,
            "per_intent": dict(sorted(per_intent.items())),
            "clarify_plus_refuse": clarify,
            "tags": dict(sorted(tag_counter.items())),
            "duplicate_texts": utt_dup,
        },
        "injection": {
            "total": total_inj,
            "per_carrier": dict(sorted(per_carrier.items())),
            "per_technique": dict(sorted(per_technique.items())),
            "duplicate_texts": inj_dup,
        },
    }


def print_summary(report: dict[str, Any]) -> None:
    """打印控制台摘要。"""
    utt = report["utterances"]
    inj = report["injection"]
    print("== utterances 话术集 ==")
    print(f"  总条数：{utt['total']}（clarify+refuse：{utt['clarify_plus_refuse']}）")
    for intent, count in utt["per_intent"].items():
        print(f"  {intent:<14} {count}")
    print(f"  tags 分布：{utt['tags']}")
    print(f"  重复文本：{utt['duplicate_texts']} 条")
    print("== injection 注入集 ==")
    print(f"  总条数：{inj['total']}")
    for carrier, count in inj["per_carrier"].items():
        print(f"  {carrier:<16} {count}")
    print(f"  重复文本：{inj['duplicate_texts']} 条")


# ---------------------------------------------------------------- 配额门禁

def check_quotas(
    per_intent: Counter,
    per_carrier: Counter,
    schema_intents: set[str],
    problems: list[Problem],
) -> None:
    """--strict 配额：9 个白名单意图逐一 ≥30；clarify+refuse 合并 ≥30；总量与注入集见常量。"""
    total_utt = sum(per_intent.values())
    if total_utt < MIN_TOTAL_UTTERANCES:
        problems.append(Problem("quota", 0, f"话术总数 {total_utt} < {MIN_TOTAL_UTTERANCES}"))
    for intent in sorted(schema_intents):
        count = per_intent.get(intent, 0)
        if count < MIN_PER_INTENT:
            problems.append(Problem("quota", 0, f"意图 {intent} 仅 {count} 条 < {MIN_PER_INTENT}"))
    clarify = per_intent.get("clarify", 0) + per_intent.get("refuse", 0)
    if clarify < MIN_CLARIFY:
        problems.append(Problem("quota", 0, f"clarify+refuse 共 {clarify} 条 < {MIN_CLARIFY}"))
    total_inj = sum(per_carrier.values())
    if total_inj < MIN_INJECTION:
        problems.append(Problem("quota", 0, f"注入集 {total_inj} 条 < {MIN_INJECTION}"))


# ---------------------------------------------------------------- 模式二：打分后端

def run_backend(name: str) -> int:
    """打分后端预留接口。

    TODO：打分后端等云端大模型网关落地后接入；届时按 name 选择模型档位，
    把话术集批量交给网关打分（意图准确率、槽位 F1、澄清率、误触发率），
    注入集单独跑硬门禁（L2+ 自动执行 = 0）。
    注意：禁止在本 runner 里复刻 core 的 LocalIntentParser 规则，避免两套实现漂移。
    """
    print(f"backend 未实现：{name}（打分后端待云端大模型网关落地后接入）")
    return 2


# ---------------------------------------------------------------- 入口

def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        prog="rule_runner.py",
        description="eval 评测基线：数据质量门禁（默认）/ 打分后端（--backend，未实现）",
    )
    parser.add_argument(
        "--backend",
        metavar="NAME",
        default=None,
        help="选择打分后端（未实现，预留接口），传入后退出码为 2",
    )
    parser.add_argument(
        "--strict",
        action="store_true",
        help="严格模式：任何校验失败或配额不达标退出码 1",
    )
    args = parser.parse_args(argv)

    if args.backend is not None:
        return run_backend(args.backend)

    repo_root = Path(__file__).resolve().parents[2]
    eval_dir = repo_root / "eval"
    problems: list[Problem] = []

    # 启动时从协议 schema 动态解析意图参数键名，不做硬编码
    schema_keys = load_intent_param_keys(repo_root, problems)
    if schema_keys:
        print(f"已加载 {len(schema_keys)} 个意图 schema 的参数键名：{sorted(schema_keys)}")

    utt_dir = eval_dir / "utterances"
    per_intent, tag_counter, utt_dup = validate_utterances(utt_dir, schema_keys, problems)
    per_carrier, per_technique, inj_dup = validate_injection(eval_dir / "injection" / "cases.jsonl", problems)
    check_quotas(per_intent, per_carrier, set(schema_keys), problems)

    report = build_report(per_intent, tag_counter, utt_dup, per_carrier, per_technique, inj_dup)

    print()
    print_summary(report)
    print()

    # 落盘 eval/reports/latest.json（该目录已在 .gitignore）
    reports_dir = eval_dir / "reports"
    reports_dir.mkdir(parents=True, exist_ok=True)
    report_path = reports_dir / "latest.json"
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"报告已写入：{report_path.relative_to(repo_root)}")

    if problems:
        print(f"\n发现 {len(problems)} 个问题：")
        for problem in problems:
            print(f"  - {problem}")
    else:
        print("\n全部校验通过，未发现问题。")

    if args.strict:
        # 严格模式：问题列表非空（含配额不达标）即失败；
        # 重复文本只是警告，单独豁免，不阻断门禁。
        hard = [p for p in problems if "重复" not in p.message or "id 重复" in p.message]
        if hard:
            print(f"--strict：{len(hard)} 个硬性问题，门禁失败。")
            return 1
        print("--strict：门禁通过。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
