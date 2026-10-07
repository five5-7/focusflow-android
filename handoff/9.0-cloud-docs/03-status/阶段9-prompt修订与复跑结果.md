# prompt 修订（方案①）实施与复跑结果

日期：2026-10-02
候选：`origin/stage9/regression-freeze` @ `03cd477` — rc.27 / versionCode 561
模型：`Qwen/Qwen3-VL-8B-Instruct` · 超时 120 秒 · 真实课表照片

---

## 一、本轮改动范围（严格遵守）

**唯一代码改动：`CourseVisionRecognizer.buildStructuredPrompt()`。**

- 把密集的散文键列表换成**合法、完整的 JSON 示例**（覆盖整份响应契约，各键均出现，
  `geometry.evidence` 与相邻键以同等形式出现）；
- 保留原有语义约束（9 个数字、坐标 0..1、box 的 left<right 与 top<bottom、
  `source` 只能是 detected、看不清用 null、不猜测坐标、候选上限、无候选返回空数组）；
- **schema、校验门禁、审核流程、写入边界一律未动**；
- 方案②（独立 schema 设计方案）**未实施**。

示例的静态校验（提交前完成）：

| 检查 | 结果 |
| --- | --- |
| 示例本身可被 JSON 解析器接受 | ✅ OK |
| root 键 = {geometry, candidates} | ✅ OK |
| geometry 键 = {width,height,originalToWorking,weekdays,periods,**evidence**} | ✅ OK |
| weekdays/periods 每项 = {index,start,end,source} | ✅ OK |
| candidate 键 = 12 项契约全集 | ✅ OK |
| box 键 = {left,top,right,bottom} | ✅ OK |
| originalToWorking 长度 = 9 | ✅ OK |

---

## 二、复跑结果：拒绝**未被消除，而是前移了**

同一张真实照片，连跑两次，结果一致：

| 指标 | 旧 prompt | **新 prompt（本轮）** |
| --- | --- | --- |
| 响应长度 | 5740 / 5812 | **4202 / 4204** |
| `jsonKind` | `json-object` | **`invalid-syntax`** |
| 失败门禁 | `geometry:keys=height,originalToWorking,periods,weekdays,width`（缺 evidence） | **`jsonSyntax`** |
| `JSONObject()` 解析 | 成功 | **抛 JSONException** |
| 进入审核对话框 | ❌ | ❌ |

两次日志：

```
stage=response elapsedMs=51996 outcome=text rawLength=4202
stage=parse result=reject jsonKind=invalid-syntax jsonParseError=VisionJsonSyntax.valid=false
            schemaFailedField=jsonSyntax rawLength=4202
stage=shape shape-error:JSONException

stage=response elapsedMs=54048 outcome=text rawLength=4204
stage=parse result=reject jsonKind=invalid-syntax ... schemaFailedField=jsonSyntax rawLength=4204
```

**结论：方案①没有让视觉导入通过，而且失败点从「schema 键集合」前移到了「严格 JSON 语法」。**

---

## 三、必须声明的两点（避免误读）

### 1. `geometry.evidence` 是否补上，**本轮无法判定**

`VisionResponseParser.parse()` 的**语法门禁在键集合门禁之前**：

```kotlin
if (!cleaned.startsWith("{")) return null          // 通过
require(VisionJsonSyntax.valid(cleaned))           // ← 本轮停在这里
...
require(geometryObject.keys().toSet() == geometryKeys)   // ← 根本没走到
```

响应在语法校验就被拒绝，**从未进入键集合检查**。
因此「evidence 是否已由 JSON 示例补上」**既未被证实也未被否定**，只是被更早的失败**遮蔽**了。

### 2. 复跑同样**不能**证明「示例本身有错」

`VisionJsonSyntax.valid` 是严格校验器（拒绝尾逗号、注释、未加引号的键、NaN、前导零等）。
本轮诊断只记录了「语法不合法」，**没有记录不合法出在哪一位**——因为诊断字段止于
「JSON 解析失败原因」，而该原因是布尔的 `valid=false`，不含偏移量。
**因此无法区分**：是模型抄示例时产生了格式错误，还是示例的排版方式诱发了错误。

---

## 四、状态与边界

| 项 | 状态 |
| --- | --- |
| 视觉导入 | ❌ **仍未通过** |
| 阶段8 批次B（B1/B2） | ❌ 未覆盖 |
| 阶段8 批次C（C1–C7） | ❌ 未覆盖 |
| 候选冻结 | **仍不满足条件** |
| 合并 / tag / Release | 维持「已批准但暂缓执行」，远端未动 |
| 方案② `schema 设计方案` | 独立保留，**未实施** |

**设备状态**：复跑期间装过带诊断的 debug 包，**已恢复为稳定签名 release 包**
（561 / rc.27，非 debuggable，`firstInstallTime` 保持 2026-09-23）。

**代码状态**：本轮改动位于验证 worktree `D:\focusflow\.tmp\wt-stage9-verify`，**未提交**。
其中 `CourseVisionRecognizer.kt` 同时含有上一轮（已授权）的诊断日志调用；
`buildStructuredPrompt()` 的改写可单独回退。

---

## 五、下一步的两个可选方向（未选定）

1. **继续收敛 prompt**：例如把示例改成单行紧凑形式、去掉换行缩进，
   排除「多行示例诱导格式错误」的可能；
2. **先补诊断再动手**：把 `VisionJsonSyntax` 的失败位置（偏移/原因）纳入诊断，
   先确认不合法出在哪一位，再决定改 prompt 还是改示例排版。

两个方向都**不需要改动 schema 或校验门禁**，也不涉及方案②。
