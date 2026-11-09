# A2 · 自适应加载（AUTO）上线与产物复验报告

日期：2026-11-09
包：`com.m1racle.reader.pdf` / versionCode 4 / versionName **1.1.1**
APK：`m1racle-pdf-split-release.apk`，4,581,198 B，发布件 SHA-256 `a0444d7362dbbb709c1c4313bab28bdff27ecf2dd4f201bcf0430d5cadeb7c90`
签名：APK Signature Scheme v2 通过，证书 SHA-256 `2d9daba0f92c1d19d290dd58657391a71c16c3008e8a2b90065ba7e8602bf9d1`（生产证书，可覆盖安装）
真机：Xiaomi 25098PN5AC / Android 16
样本：`/sdcard/Download/A1源/src.pdf`，420 页 / 1,035,745,318 B（988 MB），纯图像型（每页 1 张 JPEG，600 ppi）

## 一、本轮改动

| 项 | 内容 |
| --- | --- |
| 新增 | `PdfStructureScanner`：不依赖 PDFBox 的 xref 扫描器（经典表 / xref 流 / PNG predictor / 对象流条目） |
| 新增 | `LoadMode.AUTO`：先 strict，再做分层可达性探测，fatal 才回退 lenient |
| 改造 | 5 个完整加载入口统一走 `PdfBoxSetup.open(file, AUTO)`：probe、正文扫描、书签读取、页码标签、拆分 worker |
| 改造 | 源文件卡片直读展示「快速解析 / 兼容解析 + 耗时」；结构异常提示条按实际路径分别措辞 |
| 未改 | 拆分算法、页树遍历顺序、内链重映射、并发策略（`HARD_CAP = 1`）一行未动 |

## 二、加载性能与决策日志（真机原文）

```
probe stageMs=594 loadMs=2887 loadCpuMs=3220
  decision=[strict scanMs=15 zeroOffset=21 badHeader=0 entries=3163 softMiss=1
            first=第 15 页 XObject/COSName{KSPX1} 引用了坏对象 3148，但未被绘制]
  xref kind=STREAM startxref=1035741885 valid=true entries=3163/3163 inUse=2705
       zeroOffset=21 badHeader=0 objStream=967 unverified=0 encrypted=false prev=false
       totalMs=3673 fileBytes=1035745318
```

| 指标 | lenient（改前） | AUTO/strict（改后） | 倍数 |
| --- | --- | --- | --- |
| 加载 | 57,537 ms | **2,887 ms** | **19.9×** |
| 独立扫描器 | — | 15 ms | — |
| 整轮 probe（含建缓存） | — | 3,673 ms | — |

判据生效的直接证据：`zeroOffset=21` 没有触发回退，`softMiss=1` 正确识别「第 15 页 `/KSPX1` → 坏对象 3148，但未被绘制」，因此保留快速路径。首版判据曾把「资源解析为空」一律判 fatal，导致本教材仍回退 lenient（`loadMs=60391`）；本次修正为按「是否被 `Do` 绘制」区分 fatal / soft。

## 三、全量拆分

两次独立运行，均为 `concurrency=1`、`deepVerify=false`、`loadMode=AUTO`、每份 20 页 → 21 段 / 420 页：

| 运行 | 版本 | 结果 | 总耗时 |
| --- | --- | --- | --- |
| A2-1 | 1.1.0 | `finished=21/21` | 7,926 ms（worker 加载 3,068 ms / STRICT） |
| A2-2 | 1.1.1 | `finished=21/21` | 6,560 ms（worker 加载 2,541 ms / STRICT） |

两轮各段 `import/link/verify/save` 均正常，`tempC=95.0`（机身温度保护阈值未触发降频失败）。

## 四、独立解析器 byte 级复验（核心证据）

**方法与工具**：全程不用 PDFBox，改用 poppler 25.07.0（`pdfimages`）。该教材每页 1 张 JPEG，共 423 个图像流（含第 15 页、第 328 页各 1 个 `smask`，故 420 页对应 423 条图像记录）。

按 21 段顺序拼接后与源文件逐条对齐：

| 检查项 | 结果 |
| --- | --- |
| 图像结构表（type / 宽 / 高 / 色彩空间 / 分量 / bpc / 编码 / 流字节长度） | 423 条 **逐条一致，差异 0** |
| 图像流字节 SHA-256 | 423 张 **逐张一致，差异 0** |
| 覆盖图像流总长 | ≈ 0.96 GiB |
| 页数与页面尺寸 | 21 × 20 = 420 页，均 1124.28 × 735.12 pts（与源一致） |

1.1.0 与 1.1.1 两版产物**各自独立跑通**上述两条判据（1.1.1 为最终发版包）。两次产物总字节数亦完全相同（1,030,595,782 B）。

### 一条必须记住的否定结论

**拆分产物的整包 SHA-256 天然不可复现**：PDFBox 保存时写入随机 trailer `/ID`，同一输入两次运行整包哈希必然不同。因此：

- 不能用整包哈希判断「两次拆分是否一致」；
- 不能用整包哈希做回归基线；
- 有效判据只有页级 / 流级指纹（A1 的语义指纹）或独立解析器抽取比对（本报告）。

本轮最初正是拿整包哈希做对比，看到 21/21 全部不同，差点误判为回归；换成图像流哈希后差异立刻归零。记入已知坑位。

## 五、UI 呈现

源文件卡片：`PDF · 420 页 · 988 MB · 有书签 · 快速解析 2.9s`

结构异常提示条（按实际路径分支，不再一律写「已走兼容解析」）：

> 文件结构异常：交叉引用表 3163 条记录中有 21 条指向偏移 0，但没有任何一页真正绘制它们，已正常走快速解析；拆分结果不受影响。

若真的回退，则显示：

> ……且被页面实际引用，已自动切换到兼容解析；拆分结果不受影响，但解析会比正常文件慢。

## 六、单测

`./gradlew testDebugUnitTest`：**54 tests / 0 failures / 0 errors / 0 skipped**。

其中 `PdfStructureScannerTest` 12 个用例用手写真实 PDF 字节构造器覆盖：健康经典表、offset=0、错误偏移、`startxref` 越界、尾部垃圾、加密、xref 流 predictor 1 与 12、流内 offset=0、对象流条目、非 PDF 字节、空文件。

## 七、未闭合项

1. `probePageTree` 对 420 页逐页解码内容流，本机 `stageMs=594`；页数更多或内容流更复杂的文件需再测，必要时按需化（只对含坏引用的页解码）。
2. 扩展测试集（正常 PDF / 损坏 xref / 增量更新 / 对象流 / 加密文件）仍缺，当前只有这一本真实样本。
3. 拆分产物命名在同名文件已存在时会追加 `_2` 序号（本轮 1.1.1 复跑即出现）。属避让策略，非缺陷，但依赖 SAF/MediaStore 的可见性，若用 shell 删除文件需等媒体库刷新。
4. 未发版到 GitHub Release（本轮只做本地构建与真机验收）。
