# 林夕读屏 · TalkBack 迁移方案（待定）

> 状态：**待定** —— 等现有软件稳定以后再考虑迁移
> 更新：2026-08-15

## 一、背景
现有"林夕读屏"基于 Google BrailleBack 定制（纯盲文辅助，无语音朗读）。
为升级为**完整读屏软件**（语音/手势/导航/盲文全），规划迁移到 Google TalkBack 为基础。
TalkBack 已将 BrailleBack 功能并入（brailledisplay/brailleime），且是完整读屏。

## 二、为什么选 TalkBack 为基础（方案决策记录）
| 方案 | 结论 |
|---|---|
| A. 直接套用 TalkBack | ✅ 推荐：读屏核心（语音/手势/导航）用 Google 成熟方案，我们把中文盲文引擎作为增强 |
| B. 拉 TalkBack 源码合并进现有项目 | ❌ 等于重写整个读屏架构，难度高、收益低 |

## 三、待迁移组件清单（我们自研的价值）
| 组件 | 说明 | 迁移难度 |
|---|---|---|
| BrailleSegmenter.java | 中文盲文分词规则引擎 | 低（纯 Java，零依赖）|
| JiebaDict.java | jieba 分词器（34.9 万词） | 低 |
| assets/jiebadt.txt | 分词词库 | 低（资源拷贝）|
| assets/sunshine_split.txt | 阳光强制分写词库（1,439 条）| 低 |
| assets/zhcn-cbs-full.ctb | 通用盲文全字符表（含生僻字同音覆盖）| 低 |
| 数理化增强 | 化学式/分数/单位/365 符号点位 | 低-中 |
| 避头点/避尾点 | 国标排版规则（折行层）| 中（需适配 TalkBack 显示层）|

## 四、迁移步骤（规划）
1. 以 TalkBack master 建新项目（已编译跑通：BUILD SUCCESSFUL 3m22s）
2. 移植 BrailleSegmenter + JiebaDict + 词库/表（纯 Java + 资源，零改写）
3. 定位 TalkBack `brailletranslate` 翻译接口，把引擎接入（替换/增强 liblouis）
4. 适配避头点/避尾点到 TalkBack 显示层
5. 实机验证 + 回归测试

## 五、语言策略
- 分词引擎保持 **Java**（TalkBack 主体 98.3% Java，零 interop 风险）
- 新写的集成层/界面可用 Kotlin（按需）

## 六、风险与注意
- TalkBack master 是开发版，可能不稳定
- 依赖 AspectJ / NDK（BRLTTY），本地环境已就绪
- 迁移后需完整回归（TalkBack 功能多）
- **迁移前先确保现有"林夕读屏"稳定可用**

## 七、前置条件（迁移前必须满足）
- [ ] 现有林夕读屏（BrailleBack 定制）在真机稳定运行
- [ ] 中文盲文引擎经充分测试（分词/排版/数理化/生僻字）
- [ ] 点显器连接（蓝牙/HID）验证通过
- [ ] 版本备份完整（现项目 + TalkBack 源码）

---
*本迁移方案为"待定"状态，仅作规划参考，不启动实施。*
