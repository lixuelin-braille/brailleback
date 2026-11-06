# 模块文档 · `:client`（客户端库）

> 目录：`client/`
> 规模：Java 18 + **AIDL 10**
> 职责：跨进程通信（IPC）契约层 —— 定义 `:brailleback` 与 `:service` 之间的 AIDL 接口和数据类

---

## 一、模块定位

`:client` **不含业务逻辑**，它是「契约 + 客户端封装」：

- **AIDL 接口**：编译期生成 Java 桩，供两侧（brailleback 调用方 / service 实现方）共享
- **数据类**：AIDL 需要的 Parcelable 数据载体
- **Client 封装**：封装绑定/解绑、回调分发等样板代码，给 `:brailleback` 直接用

> 由于 `:service` 以 AAR 形式打进 `:brailleback`（**同进程**），
> 这些 AIDL 调用实际是**进程内 Binder 调用**（不会有真正跨进程序列化开销），
> 但保留了接口边界，将来若拆成独立进程也无需改代码。

---

## 二、三组 AIDL 契约

### 2.1 显示（display）—— 点显器连接

| 文件 | 类型 | 说明 |
|:---|:---|:---|
| `IBrailleService.aidl` | 接口 | 点显器服务接口（`DisplayService` 实现） |
| `IBrailleServiceCallback.aidl` | 接口 | 服务→客户端回调（连接状态、输入事件） |
| `BrailleDisplayProperties.aidl` | Parcelable | 点显器属性（cell 数、支持特性等） |
| `BrailleInputEvent.aidl` | Parcelable | **点显器输入事件**（按键/滚轮/命令） |

**数据类**
| 类 | 说明 |
|:---|:---|
| `BrailleDisplayProperties.java` | 点显器规格（行列数等） |
| `BrailleInputEvent.java` | 输入事件（含命令枚举、参数） |
| `BrailleKeyBinding.java` | 键位绑定（点显器按键 → 动作） |
| `BluetoothDeviceEntry.java` | 蓝牙设备条目（供 UI 选择） |
| `UsbDeviceEntry.java` | USB 设备条目 |

**客户端封装**
| 类 | 说明 |
|:---|:---|
| `Display.java` | 显示相关常量/工具 |
| `DisplayClient.java` | **绑定 `DisplayService` 的客户端**，`brailleback` 侧主要入口 |

---

### 2.2 翻译（translate）—— 文本 ↔ 盲文点位

| 文件 | 类型 | 说明 |
|:---|:---|:---|
| `ITranslatorService.aidl` | 接口 | 翻译服务接口（`TranslatorService` 实现） |
| `ITranslatorServiceCallback.aidl` | 接口 | 初始化完成回调 |
| `TableInfo.aidl` | Parcelable | 盲文表信息（id/文件名/显示名） |
| `TranslationResult.aidl` | Parcelable | **翻译结果**：cells + 双向位置映射 + 光标 |

**`TranslationResult` 关键字段**（见 `service` 模块的映射修正逻辑）
| 字段 | 说明 |
|:---|:---|
| `byte[] cells` | 盲文点位序列（每个 byte = 一个 cell 的点位） |
| `int[] textToBraillePositions` | 原文字符下标 → 盲文 cell 下标 |
| `int[] brailleToTextPositions` | 盲文 cell 下标 → 原文字符下标 |
| `int cursorPosition` | 光标对应的 cell 位置 |

**客户端封装**
| 类 | 说明 |
|:---|:---|
| `TranslatorClient.java` | **绑定 `TranslatorService` 的客户端** |
| `BrailleTranslator.java` | 翻译相关常量/辅助 |

---

### 2.3 SelfBraille —— 外部写点显器

| 文件 | 类型 | 说明 |
|:---|:---|:---|
| `ISelfBrailleService.aidl` | 接口 | 自建盲文服务接口（`SelfBrailleService` 实现） |
| `WriteData.aidl` | Parcelable | 一次写入的数据（文本/点位等） |

**客户端封装**：`SelfBrailleClient.java`（对应 `brailleback/SelfBrailleManager.kt` 的底层）

**安全约束**：`SelfBrailleService` 虽然 `exported="true"`，但要求
`com.googlecode.eyesfree.braille.selfbraille.PERMISSION_WRITE`
（`signature` 级）。`SelfBrailleClient` 的 manifest 已声明该权限；
调用方还必须与 BrailleBack 使用同一签名，服务端会再次校验调用 UID 的签名。

---

## 三、典型调用链

```
brailleback 侧
  TranslatorManager.kt
      └─ TranslatorClient (client 模块)
              └─ ITranslatorService.translate(text, tableId, cursor, computerBraille)
                      └─ TranslatorService.ServiceImpl (service 模块)
                              └─ BrailleSegmenter → LibLouisWrapper → TranslationResult

  DisplayManager.java
      └─ DisplayClient (client 模块)
              ├─ IBrailleService.setCallback(IBrailleServiceCallback)
              ├─ IBrailleService.display(cells)      输出
              └─ IBrailleServiceCallback.onInputEvent(BrailleInputEvent)  输入回调

  SelfBrailleManager.kt
      └─ SelfBrailleClient (client 模块)
              └─ ISelfBrailleService.write(WriteData)
```

---

## 四、扩展点

| 想加什么 | 改哪里 |
|:---|:---|
| 新 IPC 方法 | 在对应 `.aidl` 加方法 + 在 `service`/`brailleback` 侧实现/调用 |
| 新数据字段 | 改 `.aidl` 的 Parcelable 定义 + 对应 `.java` 的 `writeToParcel`/`CREATOR` |
| 新点显器输入命令 | `BrailleInputEvent.java` 加命令常量 + `DisplayService`/`ReadThread` 处理 |
| 新点显器属性 | `BrailleDisplayProperties` 加字段 |

> ⚠️ AIDL 改动需**同步两侧**（接口定义 + 服务端实现 + 客户端调用），
> 因为同进程，序列化出错不会在编译期暴露，容易漏改。
