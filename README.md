# 盲文迁移项目（原林夕读屏 / BrailleBack 定制）

安卓无障碍读屏软件，含专业中文盲文排版。"林"+"夕"相叠为"梦"，寓意让视障朋友"看见"世界、实现梦想。

> 详细说明见：[林夕读屏-项目说明.md](./林夕读屏-项目说明.md)
> 迁移方案见：[林夕读屏-TalkBack迁移方案.md](./林夕读屏-TalkBack迁移方案.md)

## 技术架构
- 基础：Google TalkBack（完整读屏：语音/手势/导航/盲文）
- 核心价值：自研中文盲文引擎
  - jieba 分词 + BrailleSegmenter 规则引擎
  - 虚词分写 / 强制分写词库
  - 数理化增强（化学式/分数/单位/数学符号点位）
  - 通用盲文全字符表（zhcn-cbs-full）
  - 避头点 + 避尾点（国标排版规则）
- 语言：Java 为主，分词引擎纯 Java；liblouis 盲文转换通过 JNI 调用

## 模块
- `brailleback`：主应用（读屏服务 UI）
- `service`：盲文服务（含 liblouis JNI 源码、盲文表、jieba 词库）
- `client`：BrailleBack 客户端库
- `libraries/`：compatutils / utils 工具库

## 构建
环境：AGP 9.2.0 + Gradle 9.4.1（阿里云镜像），JDK 17+，Android SDK 35。

```bash
./gradlew assembleDebug
```
