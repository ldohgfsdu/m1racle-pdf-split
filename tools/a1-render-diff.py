#!/usr/bin/env python3
"""A1 逐页渲染比对（独立渲染器：poppler / pdftoppm）。

用法：
    python tools/a1-render-diff.py --lenient <L 产物目录> --strict <S 产物目录> \
        --out <报告目录> [--dpi 100] [--top 50]

做的事：
 1. 把 L-/S- 两套产物按页段配对（文件名去掉 L-/S- 前缀后相同即配对）；
 2. 每段用 pdftoppm 渲染成灰度 PGM（独立于 PDFBox 的渲染器）；
 3. 逐页做字节比较：完全相同 → 记 identical；不同 → 计算差异像素数、
    最大通道差、两侧墨量占比；
 4. 输出 CSV（逐页）+ summary.txt（汇总）+ diff/ 下差异页的并排 PNG。

判据由人看：diff_pixels=0 且 ink 占比一致，才说明两套产物像素等价。
"""
from __future__ import annotations

import argparse
import csv
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

POPPLER_NAMES = ("pdftoppm", "pdftoppm.exe")


def find_pdftoppm() -> str:
    for name in POPPLER_NAMES:
        found = shutil.which(name)
        if found:
            return found
    raise SystemExit("找不到 pdftoppm（poppler）。请把它加入 PATH。")


def read_pgm(path: Path) -> tuple[int, int, bytes]:
    data = path.read_bytes()
    if not data.startswith(b"P5"):
        raise ValueError(f"{path} 不是二进制 PGM")
    # 解析 header: P5 <w> <h> <maxval>，允许 # 注释
    index = 2
    fields: list[int] = []
    while len(fields) < 3:
        while index < len(data) and data[index : index + 1].isspace():
            index += 1
        if data[index : index + 1] == b"#":
            while index < len(data) and data[index] not in (10, 13):
                index += 1
            continue
        start = index
        while index < len(data) and not data[index : index + 1].isspace():
            index += 1
        fields.append(int(data[start:index]))
    index += 1  # 单个空白分隔符
    width, height, maxval = fields
    if maxval != 255:
        raise ValueError(f"{path} maxval={maxval}，只支持 255")
    return width, height, data[index:]


def diff_stats(left: bytes, right: bytes) -> tuple[int, int, int, int]:
    """返回 (差异像素数, 最大差, 左墨量, 右墨量)；阈值 8 与设备侧一致。"""
    if len(left) != len(right):
        raise ValueError("两页像素数不同")
    diff_pixels = 0
    max_diff = 0
    ink_left = 0
    ink_right = 0
    for a, b in zip(left, right):
        delta = a - b
        if delta < 0:
            delta = -delta
        if delta:
            if delta > 8:
                diff_pixels += 1
            if delta > max_diff:
                max_diff = delta
        if a < 245:
            ink_left += 1
        if b < 245:
            ink_right += 1
    return diff_pixels, max_diff, ink_left, ink_right


def pair_parts(lenient_dir: Path, strict_dir: Path) -> list[tuple[Path, Path]]:
    def index(directory: Path, prefix: str) -> dict[str, Path]:
        table: dict[str, Path] = {}
        for path in sorted(directory.glob("*.pdf")):
            name = path.name
            if name.startswith(prefix):
                name = name[len(prefix) :]
            table[name] = path
        return table

    left = index(lenient_dir, "L-")
    right = index(strict_dir, "S-")
    common = sorted(set(left) & set(right))
    missing = sorted(set(left) ^ set(right))
    if missing:
        print(f"[警告] 只有一侧存在的段：{missing}", file=sys.stderr)
    if not common:
        raise SystemExit("没有可配对的页段（检查 L-/S- 前缀是否一致）")
    return [(left[name], right[name]) for name in common]


def render_segment(pdftoppm: str, pdf: Path, target_dir: Path, dpi: int) -> list[Path]:
    target_dir.mkdir(parents=True, exist_ok=True)
    prefix = target_dir / "page"
    subprocess.run(
        [pdftoppm, "-gray", "-r", str(dpi), str(pdf), str(prefix)],
        check=True,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.PIPE,
    )
    return sorted(target_dir.glob("page*.pgm"))


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--lenient", required=True, type=Path)
    parser.add_argument("--strict", required=True, type=Path)
    parser.add_argument("--out", required=True, type=Path)
    parser.add_argument("--dpi", type=int, default=100)
    parser.add_argument("--top", type=int, default=50, help="报表里列出的差异页数量")
    args = parser.parse_args()

    pdftoppm = find_pdftoppm()
    args.out.mkdir(parents=True, exist_ok=True)
    pairs = pair_parts(args.lenient, args.strict)
    rows: list[dict[str, object]] = []
    page_number = 0

    with tempfile.TemporaryDirectory(prefix="a1-render-") as temp:
        temp_dir = Path(temp)
        for part_index, (left_pdf, right_pdf) in enumerate(pairs, start=1):
            left_pages = render_segment(pdftoppm, left_pdf, temp_dir / f"L{part_index}", args.dpi)
            right_pages = render_segment(pdftoppm, right_pdf, temp_dir / f"S{part_index}", args.dpi)
            if len(left_pages) != len(right_pages):
                rows.append(
                    {
                        "page": f"{left_pdf.name}",
                        "segment": part_index,
                        "status": "page-count-mismatch",
                        "lenient_pages": len(left_pages),
                        "strict_pages": len(right_pages),
                    }
                )
                continue
            for offset, (left_page, right_page) in enumerate(zip(left_pages, right_pages)):
                page_number += 1
                left_bytes = left_page.read_bytes()
                right_bytes = right_page.read_bytes()
                if left_bytes == right_bytes:
                    rows.append(
                        {
                            "page": page_number,
                            "segment": part_index,
                            "status": "identical",
                            "diff_pixels": 0,
                            "max_diff": 0,
                            "ink_lenient": "",
                            "ink_strict": "",
                        }
                    )
                    continue
                window, height, left_pixels = read_pgm(left_page)
                _, _, right_pixels = read_pgm(right_page)
                diff_pixels, max_diff, ink_left, ink_right = diff_stats(
                    left_pixels, right_pixels
                )
                rows.append(
                    {
                        "page": page_number,
                        "segment": part_index,
                        "status": "diff",
                        "diff_pixels": diff_pixels,
                        "max_diff": max_diff,
                        "ink_lenient": ink_left,
                        "ink_strict": ink_right,
                        "size": f"{window}x{height}",
                    }
                )
                if diff_pixels:
                    diff_dir = args.out / "diff"
                    diff_dir.mkdir(exist_ok=True)
                    shutil.copy(left_page, diff_dir / f"p{page_number:04d}-L.pgm")
                    shutil.copy(right_page, diff_dir / f"p{page_number:04d}-S.pgm")
            print(
                f"[{part_index}/{len(pairs)}] {left_pdf.name}: {len(left_pages)} 页",
                flush=True,
            )

    csv_path = args.out / "render-diff.csv"
    with csv_path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(
            handle,
            fieldnames=[
                "page",
                "segment",
                "status",
                "diff_pixels",
                "max_diff",
                "ink_lenient",
                "ink_strict",
                "size",
                "lenient_pages",
                "strict_pages",
            ],
        )
        writer.writeheader()
        for row in rows:
            writer.writerow(row)

    identical = sum(1 for row in rows if row["status"] == "identical")
    differing = [row for row in rows if row["status"] == "diff" and row.get("diff_pixels")]
    summary = [
        f"dpi={args.dpi}",
        f"段数={len(pairs)}",
        f"页数={page_number}",
        f"字节级完全一致={identical}",
        f"像素有差异={len(differing)}",
        "",
        f"前 {args.top} 个差异页：",
    ]
    for row in differing[: args.top]:
        summary.append(
            f"  第 {row['page']} 页 段{row['segment']} diffPx={row['diff_pixels']} "
            f"maxDiff={row['max_diff']} ink={row['ink_lenient']}/{row['ink_strict']}"
        )
    text = "\n".join(summary)
    (args.out / "summary.txt").write_text(text, encoding="utf-8")
    print(text)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
