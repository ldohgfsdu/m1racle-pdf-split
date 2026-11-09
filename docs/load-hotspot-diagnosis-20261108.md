# PDF 加载热点诊断报告（方法级采样）

- 生成时间：2026-11-08 17:10
- 诊断对象：`com.m1racle.reader.pdf`（pdf-tool 模块）加载 1 GB 教材 `26贺银成实践技能应试指南.pdf`
- 诊断口径：**只加日志与 debug-only 基准，拆分算法、probe 触发机制、生产代码均未改动**
- 原始数据：`output/pdf-split-ui-20261108/prof/`（trace / 采样器 / 日志 / xref 解码结果）

---

## 0. 结论摘要

1. **热点不在 PDF 解析逻辑，而在 PDFBox 的「xref 修复」路径。**
   `checkXrefOffsets()` 判定整张 xref 表不可信 → `bfSearchForObjects()` 把 1.04 GB 文件逐字节重扫一遍。这条链占 `initialParse` 的 33.4%，而 `initialParse` 占加载的 95.9%。

2. **根因是 21 条畸形 xref 条目，不是文件损坏、不是 I/O、不是数据量。**
   xref 流里 3163 条表项，其中 **21 条被标成「in-use 但偏移 = 0」**。PDFBox 校验到第一条（对象 3074）就整表否决。
   全表复核：其余 1717 条普通对象偏移 **100% 正确**（0 条失败、0 条需修 generation）。

3. **现有引擎内即可拿到 20 倍收益，无需换引擎。**
   同一份文件、同一内存设置，仅 `setLenient(false)`：**104,450 ms → 5,110 ms**（20.4×）；strict + mixed128 为 **4,649 ms**（22.5×）。
   5 秒里约 78% 是把 989 MB 拷进 scratch 的必要动作，解析器本身已无热点。

---

## 1. 方法与证据口径

### 1.1 采样设置（按批准要求）

| 项 | 取值 |
|---|---|
| 采样方式 | `Debug.startMethodTracingSampling(path, 64 MB, 10 000 µs)` |
| 覆盖范围 | 完整 `PDDocument.load()`（不含 SAF 选文件、不含拆分、不含保存） |
| 未采样基线 | 同文件、同设置跑一轮，记录墙钟 |
| 交叉验证 | 另起一个 10 ms 定频**全 Java 栈**采样器（自写），两套独立来源互校 |
| 线程 | 只取 `m1bench` 线程（加载线程），已剔除采样器自身与其他线程 |

### 1.2 对照样本

| 样本 | 字节 | 页数 | 说明 |
|---|---|---|---|
| 真实教材 | 1,035,745,318 | 420 | 用户原件（`BaiduNetdisk/M1racle/老贺/讲义全套/`），复制进 app 私有目录后用路径加载 |
| 合成件 | 971,326,112 | 399 | 上一轮 `pdfunite` 把 57 页样本拼 7 份，正常 xref，作为「快」的反例 |

### 1.3 采样开销

真实教材 lenient：不带采样 102,971 ms，带采样 104,450 ms → **+1.4%**。采样未改变热点形状（两轮 self 排名一致）。

---

## 2. 热点在哪里

### 2.1 真实教材 / lenient（生产同款路径，104,450 ms）

**Self time（自写全栈采样器，9,693 采样，10 ms）**

| % | 方法 |
|---|---|
| 26.1 | `com.tom_roush.pdfbox.io.RandomAccessBufferedFileInputStream.read` |
| 19.8 | 同上 `.seek` |
| 12.6 | 同上 `.peek` |
| 9.5 | 同上 `.isEOF` |
| 6.0 | `com.tom_roush.pdfbox.pdfparser.COSParser.isString` |
| 5.1 | `RandomAccessBufferedFileInputStream.rewind` |
| 4.4 | `COSParser.bfSearchForObjects` |
| 3.9 | `COSParser.bfSearchForLastEOFMarker` |
| 3.8 | `COSParser.bfSearchForObjStreams` |
| 3.5 | `libcore.io.Linux.readBytes` |
| 1.6 | `libcore.io.Linux.lseek` |
| 0.9 | `libcore.io.Linux.writeBytes` |
| 0.5 | `java.util.BitSet.nextSetBit` |

> 字节扫描层（read + seek + peek + isEOF + rewind + isString + readBytes）合计 **91.7%**。

**Inclusive time（同一采样器，按整条栈累计）**

| % | 方法 | 说明 |
|---|---|---|
| 95.9 | `PDFParser.initialParse` | 加载的主体 |
| 63.7 | `COSParser.retrieveTrailer` → `parseXref` → `checkXrefOffsets` | 三者同宽，说明时间全在这一支 |
| 46.1 | `RandomAccessBufferedFileInputStream.isEOF` | 被修复扫描大量调用 |
| 33.4 | `COSParser.bfSearchForObjects` | **暴力重扫** |
| 100.0 | `PDFParser.parse` / `PDDocument.load` | 根 |

**ART 采样 trace 独立复算（8,900 采样，同一线程）**

| % | 方法 |
|---|---|
| 30.0 | `RandomAccessBufferedFileInputStream.read` |
| 23.1 | `.seek` |
| 12.6 | `.isEOF` |
| 11.4 | `.peek` |
| 7.6 | `COSParser.isString` |
| 6.9 | `.rewind` |

两套机制排名与量级一致（read/seek 前二、字节层 ≈ 90%），互为验证。

### 2.2 完整调用链（来自全栈采样器，最热栈原文）

```
RandomAccessBufferedFileInputStream.peek
 ← .isEOF
 ← COSParser.bfSearchForObjects            (COSParser.java:1717)
 ← COSParser.checkXrefOffsets              (COSParser.java:1526)
 ← COSParser.parseXref                     (COSParser.java:409)
 ← COSParser.retrieveTrailer               (COSParser.java:254)
 ← PDFParser.initialParse                  (PDFParser.java:169)
 ← PDFParser.parse                         (PDFParser.java:222)
 ← PDDocument.load                         (PDDocument.java:1096 → 1079 → 1003)
```

第二条最热栈同理，只是叶子换成 `read ← isString ← bfSearchForLastEOFMarker`。

### 2.3 对照：合成件 / lenient（3,771–4,201 ms）

| % | 方法 |
|---|---|
| 26.1 | `libcore.io.Linux.writeBytes` |
| 22.4 | `libcore.io.Linux.readBytes` |
| 13.3 | `java.util.BitSet.nextSetBit` |
| 11.3 | `libcore.io.Linux.lseek` |
| 5.1 | `java.lang.System.arraycopy` |

**没有任何 `bfSearch*`，也没有 `RandomAccessBufferedFileInputStream` 热点** —— 正常路径就是「解析 xref + 把 989 MB 顺序拷进 scratch」。

---

## 3. 为什么慢

### 3.1 触发条件（有日志原文）

`COSParser.checkXrefOffsets()` 的实现（反编译确认）：

```java
if (!isLenient) return;                       // 只有宽松模式才校验
Map<xref> t = xrefTrailerResolver.getXrefTable();
if (!validateXrefOffsets(t)) {                // 全表校验，一票否决
    bfSearchForObjects();                     // 逐字节重扫整份文件
    if (!bfSearch.isEmpty()) { t.clear(); t.putAll(bfSearch); }
}
```

设备日志（`-s PdfBox-Android`，本轮 17:01 实跑）：

```
D/PdfBox-Android: Stop checking xref offsets as at least one (3074 0 R) couldn't be dereferenced
D/PdfBox-Android: Replaced read xref table with the results of a brute force search
```

### 3.2 那一条为什么解引用不了

反解 `startxref` 指向的 XRef 流（`3162 0 obj`，FlateDecode + PNG Up predictor，`/W[1 4 2]`）：

| 项 | 数值 |
|---|---|
| 表项总数 | 3,163（`/Index[0 3163]`） |
| type 1（普通对象） | 1,738 |
| type 2（在对象流 3161 内） | 967 |
| type 0（free） | 458 |
| **type 1 但 offset = 0** | **21** |

那 21 条：

```
1690 2642 3074 3076 3084 3087 3092 3094 3095 3103 3105
3106 3114 3119 3124 3136 3139 3143 3147 3148 3156
```

`validateXrefOffsets()` 对 `offset < 6` 的条目直接返回 null；哈希表遍历先撞上 3074 → 整表否决。

**对照复核（全表，不只抽样）**

- 余下 1,717 条 type 1（offset > 0）：逐条按 `findObjectKey()` 的原始规则（跳空白、回看前导数字、读 `<num> <gen> obj`）比对 → **100% 通过，0 条失败，0 条需要修 generation**。
- 也就是说：**这份 xref 只有 21 个「空指针」式条目，其余完全正确。**

### 3.3 这 21 条里 3 条真被页面引用，且对象根本不存在

在 1 GB 原文里检索 `N 0 R`：

| 对象 | 被谁引用 | 文件中是否存在 `N 0 obj` |
|---|---|---|
| 3095 | 页面 3093 `/XObject /KSPX4` | **不存在** |
| 3106 | 页面 3104 `/XObject /KSPX6` | **不存在** |
| 3148 | 页面 3146 `/XObject /KSPX1` | **不存在** |
| 其余 18 条 | 无任何引用 | 不存在 |

所以这 3 页在现有（lenient）实现下**本来就是缺图**——brute force 也找不到不存在的对象，只是被静默容忍了。

### 3.4 为什么一次重扫要 30 秒以上

`bfSearchForObjects()` 的循环体是逐字节推进，且每一步都调用流的分层原语：

- `isString(OBJ_MARKER)`：每字符 `read()` + `rewind()`（内部即 `seek()`）
- `isEOF()`：每次 `peek()` + `rewind()`
- `findObjectKey()` 的「跳空白 / 回看数字」：同样逐字节 `seek()`

实测有效扫描速率：修复段约 1.04 GB / 约 35 s ≈ **30 MB/s**；而同一设备同一文件顺序读 **2.2 GB/s**、私有目录写 **2.0 GB/s**。**慢 70 倍全部是 CPU（每次读写都是虚调用 + 缓冲区重定位），不是磁盘。**

### 3.5 决定性实验：把校验关掉

同文件、同 `tempFileOnly`、只把 `isLenient` 设为 false（→ `checkXrefOffsets()` 第一行直接 return）：

| 模式 | loadMs | 线程 CPU | scratch | 结果 |
|---|---|---|---|---|
| lenient（生产现状） | 139,144 / 104,450 / 102,971 | 134,853 / 100,885 / 102,261 | 1,037,303,808 B | 420 页 |
| **strict** | **5,110** | 4,348 | 1,037,303,808 B | 420 页 |
| **strict + mixed128** | **4,649** | 4,288 | 1,037,303,808 B | 420 页 |
| 合成件 lenient | 4,201 / 3,771 | 3,678 / 3,641 | 978,190,336 B | 399 页 |

strict 的热点形状与合成件**完全同形**（readBytes 22.2%、writeBytes 21.3%、lseek 12.9%、BitSet 12.4%、arraycopy 6.6%），即「正常路径只值 4 秒」。

---

## 4. 能怎样优化（三档，标注实测/未实测）

### A. 现有引擎内可处理

**A1　strict 解析 + 失败回退（实测 20.4×，推荐先做验证）**

- 做法：`PDFParser(...).setLenient(false)`；解析或后续校验失败则回退现有 lenient 流程。
- 实测：104,450 → 5,110 ms；strict+mixed128 → 4,649 ms。
- 风险（必须验证后再上线）：
  1. strict 关闭的是**全部**宽松容忍，不止 xref 校验。对其它畸形 PDF 可能从「能拆」变成「报错」。回退机制可兜住，但回退等于回到慢路径。
  2. 本书 3 个页面引用了**不存在的** XObject（3095/3106/3148）。strict 下这些引用在 `importPage` / `materializeInheritedResources` / 保存时是否抛异常，**尚未验证**（这是唯一阻塞项）。
  3. strict 下 PDFBox 不再自动修 generation，遇到 gen 更高的条目会怎样需实测。
- 结论：**收益最大、改动最小，但必须先做一次 strict 全 420 页拆分回归**，确认 21/21 产物与现有输出一致（或差异仅限那 3 页的缺图）。

**A2　增量修复 xref（未实施，预期与 A1 同级）**

- 做法：在 `stageSource()` 已经复制好的 cache 副本**尾部追加**一个新的 XRef 流（把这 21 条写成 free）+ 新 `startxref` + `%%EOF`。PDFBox 读最后一个 `startxref`，校验通过，走快路径；其余仍是 lenient，保留全部容忍度。
- 优点：不碰用户原文件；不改拆分逻辑；对「21 条空条目」这一类畸形有通用性。
- 成本/风险：需自写 xref 序列化（Deflater + PNG predictor + `/W` 表），约一个批次工作量；写错会让文件更糟，需要 fail-closed 校验（新 xref 写完先自检再交给 PDFBox）。
- 收益：**未实测**，但机制上与 A1 打掉的是同一段代码，预期同样 5 秒量级。

**A3　probe 按需化（已实测收益，批准暂缓）**

- 现状：选文件即完整 load 一次，实测 `probe totalMs=58,583`（stage 1,133 + load 57,271 + 取书签/页标签 2 ms）。
- 收益：**每次选文件省 58.6 s**，且避免 probe 与 split 并发抢 CPU。
- 代价：书签/页标签改为「点逐章拆分时再取」。已批准暂缓，不影响本次结论。

### B. 加载架构调整

**B1　probe 与 split 共享同一个 `PDDocument`**
省掉第二次 54 s。但 `PDDocument` 非线程安全、跨 Activity/ViewModel 生命周期持有 1 GB 文档，内存与生命周期风险中高；且 A1/A2 落地后这 54 s 本身只剩 5 s，收益反而变小。**建议在 A1/A2 之后再评估。**

**B2　「已修复副本」落盘缓存**
首次打开后把修好的副本（或修好的 xref）缓存，同一本书后续打开直接复用。依赖 A2；对「同一本书反复拆」场景收益最大。

### C. 可能需要替换引擎

**C1　MuPDF / pdfium**
- 现在**不建议**。理由：A1 已把 104 s 压到 5 s，而 5 s 里约 78% 是「989 MB 拷进 scratch」这一必要动作（`tempFileOnly` 语义），换引擎能省的只是其中约 2–3 s。
- 成本：JNI 封装 + 许可审查（MuPDF 为 AGPL/商业双许可）；且拆分产物一致性要重新做一轮回归。
- 若将来要处理「大量不同畸形 PDF 且不能回退慢路径」，再考虑。

---

## 5. 未验证项与边界（必须与结论一起读）

1. **strict 的端到端正确性未验证**：只验证了「420 页可打开」，没验证 21 段拆分产物与现有输出一致。这是 A1 上线前的唯一硬门槛。
2. **本次全部是 debug 包数据**：同一文件 release 实测 54,437 ms ↔ debug 91,888 ms（同设置，倍率 1.69×）。按此推算 release 下 strict ≈ 3 s 量级（**估算，未实测**）。
3. **ART trace 解析有已知限制**：记录 14 B/条、字段布局无官方文档，我按 (u16 线程, u32 方法, u32, u32 时间) 拟合，方法 id 需 ±1/±4 归一。lenient 轮里 trace 栈深度只有 4.1 帧（strict 16.9 帧），**祖先帧被低估**，所以「调用链」结论一律取自自写全栈采样器，不用 trace。
4. **`/proc/self/io` 在 app 进程读回全 0**（上一轮已记录），I/O 字节计数仍缺；本轮改用 CPU/墙钟比 + 热点形状替代。
5. **热节流**：设备长时间处于 95 °C 附近，绝对值为最差情况；所有对照都在同一状态下取得，相对结论成立。

---

## 6. 原始数据清单

| 文件 | 大小 | 内容 |
|---|---|---|
| `prof/trace-real-sampled.trace` | 640,564 B | ART 采样 trace，真书 lenient（8,900 采样） |
| `prof/trace-real-strict.trace` | 134,431 B | ART 采样 trace，真书 strict |
| `prof/trace-synth-sampled.trace` | 101,776 B | ART 采样 trace，合成件 |
| `prof/sampler-real-sampled-tempFileOnly.txt` | 24,239 B | 全栈采样器明细（self/incl/栈） |
| `prof/sampler-real-strict-tempFileOnly.txt` | 31,197 B | 同上，strict |
| `prof/sampler-synth-sampled-tempFileOnly.txt` | 32,744 B | 同上，合成件 |
| `prof/prof-*.log` | — | 各轮 logcat（含 `PdfBox-Android` 关键行） |
| `prof/xref-tail.bin` | 3,433 B | `startxref` 指向的 XRef 流原始字节 |
| `prof/xref-entries.json` | 53,146 B | 解码后的 3,163 条 xref 表项 |
| `prof/trace-report.json` | — | trace 复算结果（self/incl/rollup） |

代码改动范围：**仅** `pdf-tool/src/debug/java/.../bench/LoadBenchActivity.kt`（debug-only，不进 release 包）。生产代码、拆分算法、probe 触发路径 0 改动。
