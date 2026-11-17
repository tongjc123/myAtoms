"""Check document traceability and Trellis task contracts without network access."""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
errors: list[str] = []
notes: list[str] = []
expected_requirements = {f"R{number:02}" for number in range(1, 29)}


def check(condition: bool, message: str) -> None:
    if not condition:
        errors.append(message)


def text(path: Path) -> str:
    return path.read_text(encoding="utf-8-sig")


def read_json(path: Path) -> dict:
    try:
        value = json.loads(text(path))
        if not isinstance(value, dict):
            raise ValueError("expected an object")
        return value
    except (ValueError, OSError) as error:
        errors.append(f"{path.relative_to(ROOT)}: {error}")
        return {}


product_path = ROOT / "Atoms-Demo-产品需求文档.md"
design_path = ROOT / "Atoms-Demo-实现设计文档.md"
audit_path = ROOT / "Atoms-Demo-文档核查报告.md"

for path in (product_path, design_path):
    content = text(path)
    check(bool(re.search(r"版本[：:]\s*(?:\*\*)?3\.0", content)), f"{path.name}: missing v3.0")
    rows = re.findall(r"^\|\s*(R\d{2})\s*\|.*$", content, re.MULTILINE)
    check(set(rows) == expected_requirements, f"{path.name}: R01-R28 table is incomplete")
    check(len(rows) == 28, f"{path.name}: duplicate requirement definitions")
    check(
        {f"T{n:02}" for n in range(1, 29)} <= set(re.findall(r"\bT\d{2}\b", content)),
        f"{path.name}: missing acceptance IDs",
    )
    for name in ("Mike", "Emma", "Bob", "Alex", "David", "Iris", "Sarah", "Adrian"):
        check(name in content, f"{path.name}: missing agent {name}")

for row in re.findall(r"^\|\s*R\d{2}\s*\|.*$", text(design_path), re.MULTILINE):
    requirement = re.search(r"R(\d{2})", row)
    if requirement:
        check(f"T{requirement.group(1)}" in row, f"Incorrect test mapping: {row}")

for path in (product_path, design_path, audit_path, ROOT / "README.md"):
    content = text(path)
    check(content.count("```") % 2 == 0, f"{path.name}: unclosed code fence")
    for target in re.findall(r"\[[^\]]*\]\(([^)]+)\)", content):
        target = target.strip("<>").split("#", 1)[0]
        if not target or re.match(r"^[a-z]+://", target, re.I):
            continue
        windows_absolute = bool(re.match(r"^[A-Za-z]:[/\\]", target))
        # Historical audit screenshots live outside the checkout on the research machine.
        historical_evidence = (
            path == audit_path
            and windows_absolute
            and "/.codex/visualizations/" in target.replace("\\", "/")
        )
        if historical_evidence:
            if sys.platform != "win32" or not Path(target).exists():
                notes.append(f"{path.name}: external historical screenshot unavailable: {target}")
            continue
        if windows_absolute and sys.platform != "win32":
            continue
        resolved = Path(target) if windows_absolute else path.parent / target
        check(resolved.exists(), f"{path.name}: missing local link {target}")

task_root = ROOT / ".trellis/tasks"
task_files = list(task_root.glob("*/task.json"))
task_files += list((task_root / "archive").glob("*/*/task.json"))
tasks: dict[str, tuple[Path, dict]] = {}
for metadata_path in task_files:
    metadata = read_json(metadata_path)
    directory = metadata_path.parent
    check(bool(metadata.get("description")), f"{directory.name}: empty description")
    check(bool(metadata.get("status")), f"{directory.name}: missing status")
    tasks[directory.name] = (directory, metadata)
    check((directory / "prd.md").exists(), f"{directory.name}: missing PRD")
    for artifact in ("design.md", "implement.md"):
        check((directory / artifact).exists(), f"{directory.name}: missing {artifact}")
    for action in ("implement", "check"):
        manifest = directory / f"{action}.jsonl"
        if not manifest.exists():
            errors.append(f"{directory.name}: missing {action} context")
            continue
        lines = [line for line in text(manifest).splitlines() if line.strip()]
        check(bool(lines), f"{directory.name}: empty {action} context")
        for line in lines:
            try:
                entry = json.loads(line)
                relative = entry["file"]
                check(bool(entry.get("reason")), f"{directory.name}: context reason missing")
                check((ROOT / relative).exists(), f"{directory.name}: missing context {relative}")
            except (ValueError, KeyError, TypeError) as error:
                errors.append(f"{manifest.relative_to(ROOT)}: invalid entry: {error}")

for name, (_, metadata) in tasks.items():
    parent = metadata.get("parent")
    if parent:
        parent_name = Path(parent).name
        check(parent_name in tasks, f"{name}: missing parent {parent}")
        if parent_name in tasks:
            children = tasks[parent_name][1].get("children", [])
            check(any(Path(child).name == name for child in children), f"{name}: parent link not reciprocal")
    for child in metadata.get("children", []):
        child_name = Path(child).name
        check(child_name in tasks, f"{name}: missing child {child}")
        if child_name in tasks:
            check(Path(tasks[child_name][1].get("parent") or "").name == name, f"{name}: child link not reciprocal")

check("session_auto_commit: false" in text(ROOT / ".trellis/config.yaml"), "Automatic commits must be disabled")
limit = re.search(r"^\s*max_file_bytes:\s*(\d+)", text(ROOT / ".trellis/config.yaml"), re.MULTILINE)
check(bool(limit), "Missing explicit context file limit")
if limit and int(limit.group(1)):
    for path in (product_path, design_path):
        check(path.stat().st_size <= int(limit.group(1)), f"{path.name}: Trellis context would be truncated")
for ignored in (".tools/", ".cache/", ".env"):
    check(ignored in text(ROOT / ".gitignore").splitlines(), f"Missing ignore rule: {ignored}")

for note in notes:
    print(f"NOTE: {note}")
if errors:
    for error in errors:
        print(f"FAIL: {error}")
    raise SystemExit(1)
print(f"PASS: v3 documents, 28 requirement/test mappings, local links and {len(tasks)} Trellis tasks.")
