"""A2 全量复验：比对源 PDF 与 21 个拆分产物的内嵌图像结构表。

判据：把每个产物 20 页的图像行（type/width/height/color/comp/bpc/enc/size）
按全局页码偏移拼接，与源文件 420 页的清单逐行比对。
`size` 是图像流字节长度；长度、尺寸、编码三者同时相等可判定流内容未变。

用法：python a2-image-table-diff.py <src.pdf> <parts_dir> <pages_per_part>
"""

import re
import subprocess
import sys
from pathlib import Path

ROW = re.compile(
    r"^\s*(\d+)\s+(\d+)\s+(\S+)\s+(\d+)\s+(\d+)\s+(\S+)\s+(\d+)\s+(\d+)\s+"
    r"(\S+)\s+(\S+)\s+.*?(\d+)([KMB])\s+[\d.]+%\s*$"
)


def list_images(pdf: Path) -> list:
    out = subprocess.run(
        ["pdfimages", "-list", str(pdf)],
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    rows = []
    for line in out.stdout.splitlines():
        m = ROW.match(line)
        if m:
            page, num, typ, w, h, color, comp, bpc, enc, interp, size_n, size_u = m.groups()
            size_bytes = int(size_n) * {"B": 1, "K": 1024, "M": 1024 ** 2}[size_u]
            rows.append(
                (int(page), int(num), typ, int(w), int(h), color,
                 int(comp), int(bpc), enc, size_bytes)
            )
    return rows


def main() -> int:
    src = Path(sys.argv[1])
    parts_dir = Path(sys.argv[2])
    per_part = int(sys.argv[3])

    src_rows = list_images(src)
    print(f"源文件图像行数: {len(src_rows)}")
    if not src_rows:
        print("FAIL: 源文件未能解析出任何图像行")
        return 2

    parts = sorted(parts_dir.glob("*.pdf"))
    print(f"产物数量: {len(parts)}")

    combined = []
    for idx, part in enumerate(parts):
        base_page = idx * per_part
        rows = list_images(part)
        for r in rows:
            combined.append((r[0] + base_page,) + r[1:])
        print(f"  {part.name}: {len(rows)} 行 (页码偏移 {base_page})")

    print(f"拼接后图像行数: {len(combined)}")

    if len(combined) != len(src_rows):
        print(f"FAIL: 行数不一致 源={len(src_rows)} 产物={len(combined)}")
        return 1

    diffs = []
    for i, (a, b) in enumerate(zip(src_rows, combined)):
        if a[0] != b[0]:
            diffs.append((i, "page", a[0], b[0]))
        if a[2:] != b[2:]:
            diffs.append((i, "geom/enc/size", a[2:], b[2:]))

    print()
    if not diffs:
        print(f"PASS: {len(src_rows)} 条图像记录逐条一致，差异 0")
        total = sum(r[9] for r in src_rows)
        print(f"  覆盖图像流总长 ≈ {total / (1024 ** 3):.2f} GiB")
        return 0

    print(f"FAIL: 发现 {len(diffs)} 处差异，前 40 条：")
    for d in diffs[:40]:
        print("  ", d)
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
