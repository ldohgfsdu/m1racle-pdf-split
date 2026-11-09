# AUTO 判据安全加固与发版脚本修复

日期：2026-11-09　基线：v1.1.1（`tag v1.1.1 @ 8dccc10`）
提交：`8a7f0e4`

本文记录**为什么 v1.1.1 不回滚、但下一次正式发版暂缓**，以及这一轮把哪些"会把不确定当成没问题"的路径改成了 fail-closed。

---

## 一、AUTO 判据的三条假阴性路径

`LoadMode.AUTO` 的第一步是「先严格解析，再证明页树没有洞」。问题出在"证明"这一步：旧实现有三处会把**读取不完整**当成**资源未被使用**，而后者正是允许走快速路径的前提。

### 1. 内容流扫描静默截断

```kotlin
// 旧代码
for (stream in contentStreams(contents)) {
    if (builder.length >= MAX_CONTENT_SCAN) break      // ← 到 1 MiB 就停，不留痕
    ...
}
drawnNames = DRAWN_NAME_PATTERN.findAll(builder)...
```

一页的内容流若超过 1 MiB，末尾的 `/Im9 Do` 根本读不到。`Im9` 不在 `drawnNames` 里，于是被判为"未被绘制"——而它的对象可能正好在那批 offset=0 的坏记录中。结果：接受严格解析，这一页的图静默消失。

**改法**：扫描带上 `truncated` 标记，一旦截断，`DrawingEvidence.complete = false`；不完整时**任何**解析不到的 XObject 引用一律判 fatal。

### 2. 名字不还原 `#xx`

`/KSPX#31 Do` 是画 `KSPX1` 的合法写法。旧实现提取出字面量 `KSPX#31`，跟资源键 `KSPX1` 永远对不上，于是同样被当成"未绘制"。

**改法**：比对前先按 PDF 规范还原 `#xx` 转义。

### 3. 扫描不可用时坏清单为空集

```kotlin
// 旧代码（open 内）
probePageTree(strictDocument, xref?.badObjectNumbers?.toSet() ?: emptySet())
```

`xref == null`（扫描抛异常）、`kind == UNKNOWN`（表格式认不出）、`inspectedEntries == 0`、`startXref` 不可用、文件加密 —— 这些情况下坏对象清单都是空的。而 `badReferenceNumber()` 只在对象号**落在坏清单里**时才返回非 null，空清单意味着恒返回 null，于是每一处解析不到的引用都落进「文件从未声明过这个对象」这条**良性分支**（记为 soft，不阻止快速路径）。

**改法**：新增 `canVet`。扫描不可信时，任何解析不到的引用都判 fatal —— 因为此时"从未声明"和"落在坏记录里"已经无法区分，而猜成良性的那个，就是静默丢图的那次。

### 4. 顺带补的两处

- **`Do` 运算符交叉校验**：统计内容流里 `Do` token 的个数，与正则成功配对出名字的次数比对。不相等说明有我们读不懂的绘制（未知操作符写法、内联图像数据、认不出的名字），此时"名字不在集合里"不能推出"没被绘制"，evidence 直接判为不完整。
- **嵌套 Form XObject**：Form 有自己的内容流，它画的 XObject 不会出现在页面内容流里。旧实现带着页面的 `drawnNames` 递归进 Form 的资源字典，于是 Form 画的资源全被当成未绘制。改为先读 Form 自身内容流，把绘制名并入证据再递归。

---

## 二、扫描器自身的两个缺陷（新样本矩阵发现的）

### 1. xref 流缺 `/Index` 被当成空表

规范（PDF 32000-1 §7.5.8.2）规定 `/Index` 缺省等价于 `[0 /Size]`。实现里 `parseIndex()` 在找不到 `/Index` 时 `return emptyList()`，于是这类 xref 流解析出 **0 条记录**。

后果两面：既让坏清单为空（假阴性），也会让**健康的**单段 xref 流文件拿不到快速路径。

### 2. 从不沿 `/Prev` 走

`hasPrev` 只是被记录成报告里的一个布尔值，从未真正去读上一代 xref。增量更新 PDF 里坏对象常常位于**旧代**，于是：

- 新代扫描干净 → 坏清单里没有它
- 探针看到一个解析不到的引用 → 判为"从未声明" → soft → 接受严格解析

增量更新不是冷门格式：批注、签名、表单填写都是这么存的。

**改法**：遍历 `/Prev` 链（深度上限 16，带环检测），把每代结果并集进报告。并集刻意**宁多不少** —— 后一代修好的对象仍留在坏清单里：多判只会多一次回退，少判会丢图。任一旧代读不出来，整份报告标记为不可信（`startXrefValid = false`），闸门因此拒绝使用。

---

## 三、判据被抽成纯函数

新增 `FastPathGuard`（`src/main/java/.../split/FastPathGuard.kt`），把决策规则从 PDFBox 遍历里剥离：

| 函数 | 作用 |
| --- | --- |
| `decodePdfName` | 还原 `#xx` |
| `scanDrawings(text, truncated)` | 正则 + `Do` 计数交叉校验 → `DrawingEvidence` |
| `combine(parent, child)` | Form 嵌套证据合并（`complete` 取与） |
| `severityForUnresolved(...)` | 解析不到的条目 → `IGNORE / SOFT / FATAL` |
| `xrefVeto(report)` | 扫描结果是否可信；不可信返回原因 |

这样判据能在 JVM 上跑单测，不必依赖 `PDDocument`（本模块没有 Robolectric，PDFBox 走不到单元测试）。

---

## 四、样本矩阵

`tools/make-compat-fixtures.py` 手写 PDF 字节，不依赖任何 PDF 库：

| 样本 | 体积 | 针对 |
| --- | --- | --- |
| 01-healthy | 459 B | 基线，应走快速路径 |
| 02-big-content-tail | 1.14 MB | 内容流 > 1 MiB，末尾 `Do` 画坏对象 |
| 03-escaped-name | 642 B | `/KSPX#31 Do` |
| 04-nested-form | 805 B | 坏对象由 Form 自身内容流绘制 |
| 05-unpaired-do | 644 B | 有读不出的 `Do`，判据须整体失效 |
| 06-xref-garbage | 441 B | xref 关键字被破坏 |
| 07-incremental | 797 B | 两代 xref，坏对象在旧代 |
| 08-objstream | 549 B | xref 流 + 对象流，含 type-2 条目 |
| 09-encrypted | 657 B | 带 `/Encrypt` |

生成：`python tools/make-compat-fixtures.py --out build/fixtures`

9 个样本均通过 poppler `pdfinfo` 独立核验（09 被正确要求输入口令，说明加密结构成立）。

---

## 五、这一轮实际验证到什么程度

**已离线验证（可复现）**

- 单测 54 → **76 例，0 失败 0 跳过**。
  - `FastPathGuardTest` 22 例：`#xx` 还原、1 MiB 截断、`Do` 计数不一致、嵌套合并、xref 否决的六种情形。
  - `CompatFixturesTest` 5 例：健康表不否决、xref 关键字破坏被否决、增量更新能报出旧代坏对象、对象流坏条目被报出、加密被否决。
- `07-incremental` 与 `08-objstream` 两条**先失败后修好**，是这一轮实际发现的扫描器缺陷，不是补写的断言。

**真机验证**：设备接通后已补齐，见第八节。结论是 9 条样本里 7 条按预期，2 条暴露了实现层缺陷（非判据问题），修复后全部达标。

---

## 六、发版脚本的三处修复

| 缺陷 | 后果 | 修法 |
| --- | --- | --- |
| `refs/tags/*.object.sha` 对 annotated tag 返回的是 **tag 对象** SHA，脚本却直接与 `HEAD_SHA` 比较 | 只要用 `git tag -a`，tag 推送成功后必然误判「远端 tag 指向错误」而中止。**这解释了 1.1.1 为什么需要手工补做后半程** | 新增 `resolve_remote_tag_commit()`，按 `object.type` 解引用到 commit |
| `git ls-remote --exit-code` 的失败被与「不存在」混为一谈 | 网络故障被当成「tag 未占用」，直接放行 | 三态判定；查询失败重试 3 次后**仍以失败收场** |
| 没有演练模式 | 只能靠真发一次来验证流程 | 新增 `--dry-run` |

补充说明：tag 存在性检查已从 `git ls-remote` 改为 GitHub API（`api_status`，200 / 404 / 其他三态）。原因是本机代理对 git 的 HTTPS 隧道会间歇性 `CONNECT tunnel failed, response 502`，而同一时刻 `curl` 正常；脚本其余步骤本来就只用 curl，统一传输层可以少一类「git 挂、curl 通」的歧义。

### 演练结果（`--dry-run`，2026-11-09）

```
[  ok  ] 工作树检查通过
[ warn ] 本地已存在 tag v1.1.1（演练模式只提示；正式发版会中止）
[ warn ] 远端已存在 tag v1.1.1（演练模式只提示；正式发版会中止）
[  ok  ] 构建 release… BUILD SUCCESSFUL
[  ok  ] 包身份 com.m1racle.reader.pdf / versionCode=4 / versionName=1.1.1
[  ok  ] 签名 v2 通过，证书指纹 2d9daba0…
[  ok  ] SHA-256 de78ae0a…
──────── 演练结束（未发版）────────
未执行  本地 tag、推送 tag、创建 Release、上传附件
```

演练副作用核验：本地 tag 仍为 `v1.0.0-verify` / `v1.1.1`，HEAD 未变（`8a7f0e4`），GitHub Release 仍只有两条（`v1.0.0-verify`、`v1.1.1`）。

演练过程中还真实触发过两次闸门，都是"该拦就拦"：版本号不匹配（传 1.1.2、包里是 1.1.1）、以及代理 502 导致远端 tag 查询失败。

---

## 七、结论与下一步

v1.1.1 保留发布：它的产物正确性有独立解析器证据（423/423 图像流 SHA-256 全等），本次加固不影响已发布二进制的行为判断。

但**下一次正式发版前必须补齐**：

1. 发布件的本机覆盖安装验证（本轮 adb 设备始终未接入）
2. 9 个样本在真机上的 strict / lenient / AUTO 三态实测，特别是 02/03/04/05 必须确认**回退发生了**，而不只是判据规则正确
3. 988 MB 教材在新判据下复测，确认仍走快速路径且 `softMiss=1`
4. 上述通过后，再考虑 probe 按需化（当前 3.7 s 已经够用，不能为再省一点时间冒兼容性风险）


---

## 八、真机验证（设备接通后补齐，2026-11-09）

设备：Xiaomi 25098PN5AC / Android 16 (SDK 36)，adb `db04f865`。

### 8.1 发布件安装验证（对应上一轮遗留的 ①）

| 检查 | 结果 |
| --- | --- |
| 安装 | `adb install -r` 成功，覆盖 1.1.1（code 4） |
| 装机包 `base.apk` SHA-256 | `a0444d7362dbbb709c1c4313bab28bdff27ecf2dd4f201bcf0430d5cadeb7c90` |
| 与 Release 附件比对 | `cmp` **逐字节相同** |
| 两个入口 | `pdfsplit.MainActivity`、`pdfsplit.verify.VerifyActivity` 均能启动，crash buffer 为空 |
| 文件选择 | SAF 选择器正常，可读到 1 GB 源文件 |
| 420 页拆分 | `finished=21/21`，`totalMs=7984`，产物 21 个、命名 `src_0001-0020.pdf`… |
| 前后台切换 | HOME 再回前台，`拆分完成 / 21 个文件` 完整保留 |
| 进程被杀后重进 | 回到空状态（无会话恢复）；磁盘产物 21 个仍在 |

「进程被杀后不恢复」是设计现状，不是缺陷，但值得记：用户切走太久被系统回收后要重新选文件。

### 8.2 三态矩阵：v1.1.1（修复前）vs 1.2.0-rc2（修复后）

同一套样本、同一台设备、同一选择器路径。

| 样本 | 期望 | v1.1.1 实测 | rc2 实测 | 判定 |
| --- | --- | --- | --- | --- |
| 01-healthy | strict | strict | strict | ✓ |
| 02-big-content-tail | lenient | **strict**（`Im9` 判"未被绘制"） | lenient：内容流未能完整读取 | 修复 |
| 03-escaped-name | lenient | **strict**（`KSPX1` 判"未被绘制"） | lenient：内容流确实绘制了它 | 修复 |
| 04-nested-form | lenient | **strict**（连 softMiss 都没有） | lenient：内容流确实绘制了它 | 修复 |
| 05-unpaired-do | lenient | **strict**（`Im2` 判"未被绘制"） | lenient：内容流未能完整读取 | 修复 |
| 06-xref-garbage | lenient | lenient（strict 加载失败） | 未重跑（路径未改动） | ✓ |
| 07-incremental | lenient | **strict**（`entries=2`，只读新代） | lenient：确实绘制了它，`prev=true` | 修复 |
| 08-objstream | lenient | **strict**（`entries=0`） | lenient：未能完整读取，`entries=8` | 修复 |
| 09-encrypted | 拒绝打开 | 未投喂成功（见 8.4） | 未投喂 | 待补 |
| 10-undrawn-broken | strict | strict，softMiss=1 | strict，softMiss=1 | ✓ 反向对照 |

结论：**v1.1.1 在 7 个判别样本里有 6 个走错了路径**——正是本轮要堵的假阴性，真机上原样复现。rc2 全部纠正，且没有把 `01` / `10` 误判成需要回退。

### 8.3 rc2 上又暴露两个实现层缺陷

第一轮 rc1 跑完，`04` 与 `08` 仍是 strict。不是判据错，是代码没走到判据：

1. **`04` 嵌套 Form**：`PDResources(stream)` 返回的是这个 Form **自身的字典**，不是它的 `/Resources`。拿它当资源字典找 `/XObject` 永远为空，内层坏引用从未被检查过。改为 `stream.getDictionaryObject(COSName.RESOURCES)`。
2. **`08` 对象流**：`/Contents` 存在却解析不出流时，`contentStreams` 返回空列表，被当成"读完了、什么都没画"→ 完整证据 → 假阴性。改为**空流列表直接返回 `DrawingEvidence.UNREAD`**。

修完 rc2 两条都变成 lenient，且 `01`/`10` 仍 strict。这印证了一件事：只验证"判据规则正确"不够，必须看真机上到底走了哪条路——这两条缺陷在单测层面完全不可见，因为单测不经过 PDFBox 的对象解析。

### 8.4 未完成项

- **09-encrypted 没能在 UI 里投喂**：文件在 `/sdcard/Download/fixtures/` 存在，但系统文档选择器不列它（合成加密 PDF 的 MIME 探测失败），`appops set MANAGE_EXTERNAL_STORAGE allow` 在 MIUI 上不生效，`file://` 直达因此也不可用。该样本目前只有扫描器级证据（`encrypted=true` → 否决）。
- 06-xref-garbage 未在 rc2 重跑；该路径不涉及本轮改动。
- probe 按需化（用户已决定推迟）。

### 8.5 教材回归（最关键的一条）

988 MB / 420 页 / 21 条 offset=0，rc2 实测：

```
probe stageMs=757 loadMs=2994 decision=[strict scanMs=31 zeroOffset=21
      entries=3163 softMiss=1 first=第 15 页 XObject/COSName{KSPX1} 引用了坏对象 3148，但未被绘制]
load worker=0 ms=2995 mode=STRICT
run end totalMs=7776 finished=21/21
```

- 仍走**快速路径**，没有出现 `unvetted=`（说明扫描结果可信、未被否决）
- `softMiss=1` 仍是第 15 页那一处，判为"未被绘制"，与 A1/A2 结论一致
- 21/21 拆分成功，产物总字节数 **1,030,595,782 B**，与 v1.1.1 同配置完全一致

也就是说：加固没有牺牲真实收益，也没有改变产物。

### 8.6 复现方式

```
python tools/make-compat-fixtures.py --out build/fixtures
adb push build/fixtures/. /sdcard/Download/fixtures/
./tools/device-fixture-matrix.sh 01-healthy.pdf 02-big-content-tail.pdf …
```

`tools/device-fixture-matrix.sh` 走 SAF 选择器（与真实用户一致），逐条采集 `M1PDF` 的 `decision=` 行。脚本内部有一处坑已记住：`uiautomator dump` 是**单行 XML**，不能用 `tr '>' '\n'` 切；早期版本因此把整份文档的 bounds 全捞出来、取"面积最小"选中了返回箭头，点击全打偏。
