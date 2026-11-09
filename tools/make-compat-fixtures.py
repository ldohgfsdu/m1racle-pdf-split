"""生成 AUTO 兼容性测试样本矩阵。

每个样本针对一条具体的判据风险，全部手写 PDF 字节，不依赖任何 PDF 库：

| 样本 | 针对的判据 |
| --- | --- |
| 01-healthy          | 基线：正常经典 xref，AUTO 应走 strict |
| 02-big-content-tail | 内容流 > 1 MiB，末尾 `/Im9 Do` 绘制坏对象。旧实现在此假阴性 |
| 03-escaped-name     | `/KSPX#31 Do` 绘制坏对象。旧实现名字不还原 → 误判未绘制 |
| 04-nested-form      | 坏对象由 Form XObject 自身内容流绘制，旧实现只看页面内容流 |
| 05-unpaired-do      | 有正则读不出的 `Do`，判据必须整体失效为"不可证" |
| 06-xref-garbage     | xref 关键字被破坏，扫描器给 UNKNOWN，不得当成"没有坏对象" |
| 07-incremental      | 增量更新（两代 xref + /Prev） |
| 08-objstream        | xref 流 + 对象流（/ObjStm） |
| 09-encrypted        | 带 /Encrypt 的文件，扫描器应报 encrypted |
| 10-undrawn-broken   | 反向对照：坏对象从未被绘制且内容流完整 → 必须仍走 strict |

用法:
    python tools/make-compat-fixtures.py --out fixtures/compat-20261109

注意：这些是**判据样本**，用来驱动扫描器与 AUTO 闸门，不追求印刷级合法。
09 的加密是"有 /Encrypt 条目"的最小形态，不是可解密的真实加密文件。
"""

import argparse
import os
import struct
import zlib

XREF_ENTRY = 20          # 经典 xref 每条固定 20 字节


class PdfBuilder:
    def __init__(self):
        self.objects = {}     # number -> payload bytes, or None to reserve a broken slot

    def add(self, number, payload):
        self.objects[number] = payload

    def reserve(self, number):
        self.objects[number] = None

    def build_classic(self, *, root=1, trailer_extra=b"", break_xref_keyword=False):
        out = bytearray(b"%PDF-1.7\n%\xe2\xe3\xcf\xd3\n")
        offsets = {}
        for number in sorted(self.objects):
            payload = self.objects[number]
            if payload is None:
                continue
            offsets[number] = len(out)
            out += b"%d 0 obj\n" % number + payload + b"\nendobj\n"

        xref_pos = len(out)
        keyword = b"xrEf" if break_xref_keyword else b"xref"
        count = max(self.objects) + 1
        out += keyword + b"\n0 %d\n" % count
        if break_xref_keyword:
            # 被破坏的关键字后面同样写条目，便于扫描器判断"这不是 xref 表"
            out += b"0000000000 65535 f \n" * count
        else:
            out += b"0000000000 65535 f \n"
            for number in range(1, count):
                if number in offsets:
                    out += b"%010d 00000 n \n" % offsets[number]
                else:
                    out += b"0000000000 65535 f \n"
        out += b"trailer\n<< /Size %d /Root %d 0 R %s>>\n" % (count, root, trailer_extra)
        out += b"startxref\n%d\n%%%%EOF\n" % xref_pos
        return bytes(out)

    def build_xref_stream(self, *, root=1, bad_numbers=(), encrypt_ref=b"", compressed=()):
        """compressed: (objnum, objstm_number, index_in_objstm) → type 2 条目。"""
        out = bytearray(b"%PDF-1.7\n%\xe2\xe3\xcf\xd3\n")
        offsets = {}
        for number in sorted(self.objects):
            payload = self.objects[number]
            if payload is None:
                continue
            offsets[number] = len(out)
            out += b"%d 0 obj\n" % number + payload + b"\nendobj\n"

        stream_number = max(self.objects) + 1
        xref_pos = len(out)
        offsets[stream_number] = xref_pos
        count = stream_number + 1

        packed = {num: (stm, idx) for num, stm, idx in compressed}
        rows = bytearray(bytes([0]) + struct.pack(">I", 0) + struct.pack(">H", 65535))
        for number in range(1, count):
            if number in packed:
                stm, idx = packed[number]
                rows += bytes([2]) + struct.pack(">I", stm) + struct.pack(">H", idx)
            elif number in bad_numbers:
                rows += bytes([1]) + struct.pack(">I", 0) + struct.pack(">H", 0)
            elif number in offsets:
                rows += bytes([1]) + struct.pack(">I", offsets[number]) + struct.pack(">H", 0)
            else:
                rows += bytes([0]) + struct.pack(">I", 0) + struct.pack(">H", 65535)

        compressed = zlib.compress(bytes(rows))
        out += (
            b"%d 0 obj\n<< /Type /XRef /Size %d /W [1 4 2] /Root %d 0 R%s"
            b" /Filter /FlateDecode /Length %d >>\nstream\n"
        ) % (stream_number, count, root, encrypt_ref, len(compressed))
        out += compressed + b"\nendstream\nendobj\n"
        out += b"startxref\n%d\n%%%%EOF\n" % xref_pos
        return bytes(out)


def force_bad_entry(raw, objnum):
    """把经典 xref 表里 objnum 那条的偏移改写为 0，类型仍为 in-use。

    这正是那本 988 MB 教材的缺陷形态：记录写着"在用"，偏移却是 0。
    """
    out = bytearray(raw)
    marker = b"\nxref\n"
    idx = out.rfind(marker)
    if idx < 0:
        raise ValueError("no classic xref table found")
    pos = idx + len(marker)
    header_end = out.find(b"\n", pos) + 1
    first, count = (int(x) for x in out[pos:header_end].split())
    if not (first <= objnum < first + count):
        raise ValueError(f"object {objnum} not in xref subsection {first}..{first + count - 1}")
    entry = header_end + (objnum - first) * XREF_ENTRY
    out[entry:entry + 10] = b"0000000000"
    return bytes(out)


PAGE_TREE = b"<< /Type /Catalog /Pages 2 0 R >>"
PAGES = b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>"


def page(content_ref, resources):
    return (
        b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 300] "
        b"/Contents %d 0 R /Resources %s >>" % (content_ref, resources)
    )


def stream(data, extra=b""):
    return b"<< /Length %d %s>>\nstream\n" % (len(data), extra) + data + b"\nendstream"


def image_stub():
    return b"<< /Type /XObject /Subtype /Image /Width 1 /Height 1 /ColorSpace /DeviceGray /BitsPerComponent 8 /Length 1 >>\nstream\n\x00\nendstream"


def single_page(content, resources=b"<< >>"):
    b = PdfBuilder()
    b.add(1, PAGE_TREE)
    b.add(2, PAGES)
    b.add(3, page(4, resources))
    b.add(4, stream(content))
    return b


# ── samples ────────────────────────────────────────────────────────────────

def fixture_healthy():
    return single_page(b"q 1 0 0 1 10 10 cm Q\n").build_classic()


def fixture_big_content_tail():
    padding = b"q 1 0 0 1 0 0 cm Q\n" * 60_000        # ≈ 1.2 MiB
    content = padding + b"/Im9 Do\n"
    assert len(content) > (1 << 20)

    b = single_page(content, b"<< /XObject << /Im9 5 0 R >> >>")
    b.add(5, image_stub())
    return force_bad_entry(b.build_classic(), 5)


def fixture_escaped_name():
    b = single_page(b"/KSPX#31 Do\n", b"<< /XObject << /KSPX1 5 0 R >> >>")
    b.add(5, image_stub())
    return force_bad_entry(b.build_classic(), 5)


def fixture_nested_form():
    b = PdfBuilder()
    b.add(1, PAGE_TREE)
    b.add(2, PAGES)
    b.add(3, page(4, b"<< /XObject << /Fm1 5 0 R >> >>"))
    b.add(4, stream(b"/Fm1 Do\n"))
    b.add(
        5,
        stream(
            b"/Im7 Do\n",
            b"/Type /XObject /Subtype /Form /BBox [0 0 300 300] "
            b"/Resources << /XObject << /Im7 6 0 R >> >> ",
        ),
    )
    b.add(6, image_stub())
    return force_bad_entry(b.build_classic(), 6)


def fixture_unpaired_do():
    """内容流里有一个正则读不出名字的 Do，同时存在一个**未被绘制**的坏对象。

    这是判据的分水岭：
    - 若把 evidence 当作完整（旧实现），坏对象 /Im2 不在 drawnNames 里 → soft → 走 strict
    - 正确行为：有读不懂的 Do，就无法断言 /Im2 没被画 → 必须回退 lenient
    """
    b = single_page(
        b"/\xe4\xb8\xad Do\n/Im1 Do\n",
        b"<< /XObject << /Im1 5 0 R /Im2 6 0 R >> >>",
    )
    b.add(5, image_stub())
    b.add(6, image_stub())
    return force_bad_entry(b.build_classic(), 6)


def fixture_undrawn_broken():
    """反向对照：内容流完整、坏对象从未被 Do 绘制 → 必须仍走 strict。

    没有这条，fail-closed 的改动可能一路走到"全都回退"而没人发现。
    """
    b = single_page(
        b"q 1 0 0 1 0 0 cm Q\n",
        b"<< /XObject << /Im1 5 0 R >> >>",
    )
    b.add(5, image_stub())
    return force_bad_entry(b.build_classic(), 5)


def fixture_xref_garbage():
    return single_page(b"q Q\n").build_classic(break_xref_keyword=True)


def fixture_incremental():
    base = bytearray(b"%PDF-1.7\n%\xe2\xe3\xcf\xd3\n")
    objs = {
        1: PAGE_TREE,
        2: PAGES,
        3: page(4, b"<< /XObject << /Im1 5 0 R >> >>"),
        4: stream(b"/Im1 Do\n"),
        5: image_stub(),
    }
    offsets = {}
    for number in sorted(objs):
        offsets[number] = len(base)
        base += b"%d 0 obj\n" % number + objs[number] + b"\nendobj\n"

    first_xref = len(base)
    base += b"xref\n0 6\n0000000000 65535 f \n"
    for number in range(1, 6):
        off = 0 if number == 5 else offsets[number]     # 5 号是坏记录
        base += b"%010d 00000 n \n" % off
    base += b"trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n%d\n%%%%EOF\n" % first_xref

    new_off = len(base)
    base += b"6 0 obj\n<< /Producer (incremental) >>\nendobj\n"
    second_xref = len(base)
    base += b"xref\n0 1\n0000000000 65535 f \n6 1\n%010d 00000 n \n" % new_off
    base += (
        b"trailer\n<< /Size 7 /Root 1 0 R /Prev %d >>\nstartxref\n%d\n%%%%EOF\n"
        % (first_xref, second_xref)
    )
    return bytes(base)


def fixture_objstream():
    """内容流对象 4 被打包进对象流 6（type 2 条目），5 号是 offset=0 的坏记录。"""
    b = PdfBuilder()
    b.add(1, PAGE_TREE)
    b.add(2, PAGES)
    b.add(3, page(4, b"<< /XObject << /Im1 5 0 R >> >>"))

    body_obj = stream(b"/Im1 Do\n")
    header = b"4 0 "                       # (objnum, offset) 对，/First 之后是对象体
    body = header + body_obj
    b.add(
        6,
        b"<< /Type /ObjStm /N 1 /First %d /Length %d >>\nstream\n" % (len(header), len(body))
        + body
        + b"\nendstream",
    )
    b.reserve(5)
    return b.build_xref_stream(root=1, bad_numbers=(5,), compressed=[(4, 6, 0)])


def fixture_encrypted():
    b = single_page(b"q Q\n")
    b.add(
        5,
        b"<< /Filter /Standard /V 1 /R 2 /O <%s> /U <%s> /P -4 >>"
        % (b"00" * 32, b"00" * 32),
    )
    return b.build_xref_stream(root=1, encrypt_ref=b" /Encrypt 5 0 R")


FIXTURES = {
    "01-healthy": fixture_healthy,
    "02-big-content-tail": fixture_big_content_tail,
    "03-escaped-name": fixture_escaped_name,
    "04-nested-form": fixture_nested_form,
    "05-unpaired-do": fixture_unpaired_do,
    "06-xref-garbage": fixture_xref_garbage,
    "07-incremental": fixture_incremental,
    "08-objstream": fixture_objstream,
    "09-encrypted": fixture_encrypted,
    "10-undrawn-broken": fixture_undrawn_broken,
}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", default="fixtures/compat-20261109")
    args = parser.parse_args()

    os.makedirs(args.out, exist_ok=True)
    for name, factory in FIXTURES.items():
        data = factory()
        path = os.path.join(args.out, name + ".pdf")
        with open(path, "wb") as handle:
            handle.write(data)
        print(f"{name:22s} {len(data):>9,d} B  {path}")


if __name__ == "__main__":
    main()
