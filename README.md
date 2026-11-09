# M1racle PDF Split

按页区间 / 章节 / 等分拆 PDF 的 Android 小工具。**独立工程**：与 M1racle Reader 共享 0 行代码，不依赖其 EPUB、批注、Room、阅读器 UI。

- 目标设备：Android 12+（`minSdk 31` / `targetSdk 35` / `compileSdk 37`）
- 包名：`com.m1racle.reader.pdf`（历史原因保留，与 Reader 主包不同）
- 引擎：[PDFBox-Android 2.0.27.0](https://github.com/TomRoush/PdfBox-Android)（`com.tom_roush.*`）
- UI：Jetpack Compose

## 它能做什么

| 能力 | 说明 |
| --- | --- |
| 按区间拆 | `1-5,8,12-20` 这类表达式；支持开放区间与倒序归一化 |
| 按章节拆 | 读 PDF 内嵌书签（outline）自动成段；无书签时按页数启发式 |
| 等分拆 | 每 N 页一段 / 固定 M 段 |
| 输出控制 | SAF 目录；自动命名 `原名-起始页-结束页.pdf`；同名冲突规避 |
| 保真 | 复制页面几何（MediaBox/CropBox/Bleed/Trim/Art/Rotation/userUnit）、继承资源物化、页面内链目标重映射、注释与文本层保留 |
| 失败可观测 | 内存校验 + 落盘后回读校验；逐段失败可精确定位到页码 |

## 构建

```bash
# 需要 JDK 17+、Android SDK（compileSdk 37 / build-tools 36.0.0）
export ANDROID_HOME=/path/to/android-sdk
./gradlew testDebugUnitTest assembleDebug
```

产物：`build/outputs/apk/debug/app-debug.apk`

**Release 构建被签名门禁保护**，四个环境变量必须同时提供，否则 `preReleaseBuild` 直接失败（禁止回落到 debug 或未签名产物）：

```bash
export M1RACLE_RELEASE_STORE_FILE=/abs/path/release.p12
export M1RACLE_RELEASE_STORE_PASSWORD=...
export M1RACLE_RELEASE_KEY_ALIAS=...
export M1RACLE_RELEASE_KEY_PASSWORD=...
./gradlew assembleRelease
```

## 拆分流水线

```
选文件 → stageSource（复制进 app 私有缓存，规避 SAF 随机读）
       → load（PDFBox PDDocument.load，本文档下半段的性能主角）
       → 规划（按页数降序派发，避免长尾）
       → 每段：importPage → preserveGeometry → materializeInheritedResources
              → PageLinkIsolation.isolate（内链重映射）
              → verifyInMemory → destination.save(SAF sink)
       → 全部落盘后可选回读校验
```

`ConcurrencyPolicy.HARD_CAP = 1`：并发在本工作负载上是**负优化**（实测多协程并行读同一 ScratchFile 产生锁竞争，CPU 时间 482s→61.85s，墙钟 138.5s→59.3s）。

## 加载性能：一个可复现的 PDFBox 恢复路径陷阱

同一份 420 页 / 988 MB 扫描教材：

| 模式 | 加载耗时 |
| --- | --- |
| 默认（lenient） | **104.45 s** |
| `PDFParser(..., isLenient = false)` | **5.11 s** |

根因不是文件大，也不是 I/O：

- 该文件的 xref 流共 3,163 条记录，其中 **21 条 `in-use` 但 offset = 0**（对象号 1690、2642、3074、3076、3084、3087、3092、3094、3095、3103、3105、3106、3114、3119、3124、3136、3139、3143、3147、3148、3156）。
- PDFBox lenient 校验遇到第一条（3074）无法解引用，即否决整张 xref 表，日志：
  `Stop checking xref offsets as at least one (3074 0 R) couldn't be dereferenced`
  → `Replaced read xref table with the results of a brute force search`。
- 暴力重扫逐字节遍历 1.04 GB，有效扫描速率约 30 MB/s（纯 CPU，非磁盘瓶颈）。采样剖析 self time 91.7% 落在 `RandomAccessBuffer.read/seek/peek/isEOF/isString/rewind`。
- 其余 1,717 条偏移**全部指向正确位置，0 条解引用失败** → 能得出的结论只是「文件存在结构异常，但绝大多数有效对象偏移正确」，**不等于文件没坏**。

详细证据链见 [`docs/load-hotspot-diagnosis-20261108.md`](docs/load-hotspot-diagnosis-20261108.md)。

**当前状态：strict 未设为默认。** 加载策略仍为 lenient，`LoadMode.STRICT` 只被 A1 验证台使用。原因见下节。

## A1 正确性验证台

包内另有一个桌面入口 **「A1 校验」**（`verify.VerifyActivity`），用于回答「strict 快 20 倍，那它有没有悄悄丢内容」：

1. 同一源文件、同一页码规划、同一输出设置，lenient 与 strict 各全量拆一遍（`L-*` / `S-*` 前缀）。
2. strict 整本失败时自动**逐段重试**，把失败精确点到具体页段。
3. 对**落盘产物重新打开**取指纹逐页比对（不复用拆分时的内存对象，避免自证）：几何、内容流 SHA-256 + 字节数、资源字典逐条（含对象号与解析状态）、注释、内链目标页 / 外链 URI、文本层 SHA-256。
4. strict 侧资源缺失清单：记录**源文件对象号 + 引用页 + 该名字是否真被内容流（含一层 Form XObject）`Do` 调用**，用以区分「悬空引用没人画」与「页面真掉图」。
5. 输出 `A1-验证报告-<ts>.txt`（人读）+ `.json`（逐页数据），同时备份到 app 外部目录 `a1-verify/`。

**防假阳性**：每页计算「证据不足」标记（任一侧内容流取不出、或两侧资源字典皆空），`weakPages > 0` 时「完全一致」不作为内容正确的证据。

PC 侧独立渲染器比对（不依赖 PDFBox）：[`tools/a1-render-diff.py`](tools/a1-render-diff.py)，用 poppler `pdftoppm` 灰度渲染后先字节比对、差异页再算像素差与墨量。

## 已知未闭合项

- 对象 3095 / 3106 / 3148 被页面 3093 / 3104 / 3146 当作 XObject 引用，但**对象不存在** → 已确认的**悬空资源引用**；这三页是否存在可见内容缺失**尚未验证**，待 A1 逐页比对。不做「本就缺图」的断言。
- strict 下全 420 页的产物一致性结论，待 A1 验证台报告回传后固化。
- 扩展测试集（正常 PDF / 损坏 xref / 增量更新 / 对象流 / 加密）尚未覆盖。
- `/proc/self/io` 在本机 app 进程读回全 0，I/O 字节计数缺失。

## 目录

```
src/main/java/com/m1racle/reader/pdfsplit/
  MainActivity.kt          入口
  split/                   拆分核心（规划、几何、资源、链接、加载策略、SAF 输出）
  ui/                      Compose 界面
  verify/                  A1 正确性验证台
src/test/                  JVM 单测（42 例，全部纯逻辑，不依赖设备）
src/debug/                 debug-only 加载基准页（LoadBenchActivity）
docs/                      设计、诊断与交接文档
tools/                     PC 侧独立渲染比对脚本
```
