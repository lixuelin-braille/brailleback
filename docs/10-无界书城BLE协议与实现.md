# 无界书城（Orbit wujie 30）BLE 协议与实现

本文记录厂商 BLE GATT 协议的逆向结论，以及 BrailleBack 中的对应实现。

## 设备与固件

- 设备名：`Orbit wujie 30`
- 显示：1 行 × 30 方
- 芯片：ESP32 原版（双核 Xtensa LX6，最高 240 MHz，520 KB SRAM），
  不是 ESP32-S3；固件头 `chip_id=0x00`，并含 `esp32/bt.c`、`BTDM`、
  `Enable Classic BT` 等字符串。
- 无线：Classic Bluetooth + BLE 4.2 双模。
- 固件同时提供 HOGP（0x1812）、SPP（0x1101）、电量（0x180F）和厂商自定义
  GATT 服务。系统蓝牙里看到的是双模设备的组合广播。

性能结论：这是低成本、低功耗 MCU 方案。每帧只有 30 字节，主要负载是 BLE
协议栈与点阵刷新，对手机端几乎没有性能要求；普通 Android 手机即可。

## 厂商 GATT 服务

服务 UUID：`12345678-1234-5678-1234-56789abcdef0`

| 特征 | 用途 | 已确认行为 |
| --- | --- | --- |
| `…def1` | 设备 → 手机通知 | 连接后立即订阅；按键通知为 4 字节 ASCII `next` / `prev` |
| `…def2` | 写通道候选 A | 可能是盲文数据通道，运行时按属性确认 |
| `…def3` | 写通道候选 B | 可能是盲文数据通道，运行时按属性确认 |
| `…def4` | 电量 | 可读，1 字节；实测 `0x59` = 89% |
| `…def5` | 规格 | 可读，2 字节 `[1, 30]` |
| `…def6` | 辅助通道 | 可能可写/可通知，运行时探测 |
| `…def7` | 版本 | 可读/可通知，5 字节 ASCII，例如 `1.0.5` |
| `…defa` | 未知 | 仅在 APK 字符串中出现 |

固件在连接后还会主动通知 `def5`、`def7` 的当前值；`def1` 只在按键动作后
发送通知，不发送初始状态。

## 按键通知

按键事件从 `def1` 发出，载荷为固定 4 字节 ASCII：

| 载荷 | 按键 | BrailleBack 命令 |
| --- | --- | --- |
| `next` | 下一页键 | `CMD_NAV_PAN_RIGHT` |
| `prev` | 上一页键 | `CMD_NAV_PAN_LEFT` |

固件没有按下/释放标志，也没有长按/短按区分：一次按键动作完成后只发送一次
通知。启用 `def1` 的 CCCD 订阅时不会发送初始状态，因此连接瞬间不会误触发。
尾随的 `0x00` 会被忽略，其他未知载荷也会被丢弃。

固件源码中的物理按键和载荷对应关系为：

- 上键：`Key: UP -> NEXT`，发送 `prev`
- 下键：`Key: DOWN -> PREV`，发送 `next`
- 两键同时按住 5 秒：断开全部连接

OTA 服务（未接入）：

- `8d44b2a5-8c4a-4a3b-9e2f-1d2c8b7a6e11` notify
- `8d44b2a6-8c4a-4a3b-9e2f-1d2c8b7a6e11` write
- MTU 500、244 字节分包、`WRITE_NO_RESPONSE`

## 盲文帧格式

固件写回调只接受两种长度：

1. 裸 30 字节：每个字节是一方，低 6 位为点 1–6。
2. `0x02` + 30 字节：首字节为报告 ID，其余相同。

固件会做 `0xA0 → 0x00`、其它值 `& 0x3F` 的归一化。其它长度会打印
`Braille data length %u (expected %u)` 并拒绝刷新。

### 5 秒看门狗

连接建立后 5 秒内没有收到盲文输出，固件会清空/提示
`No Braille OUT within 5s...`。因此实现会在 GATT 就绪时立即发送一帧空白。

### marquee 控制帧（暂未使用）

4 字节，首字节 `'M' (0x4D)`，第二字节为 `1..3`，后两字节为参数。

## BrailleBack 实现

- `service/src/.../display/WujieGattConnection.java`
  - `connectGatt(..., TRANSPORT_LE)`，请求 MTU 500。
  - 服务发现后打印每个特征的 UUID、properties、permissions。
  - 读取 `def5`（规格）、`def7`（版本）、`def4`（电量）。
  - 订阅 `def1`，以及支持通知的 `def7`/`def4`/`def6`。
  - 把所有可写特征按 `def2 → def3 → def6 → defa → 未知` 排序，
    写入失败时自动切换到下一个候选。
  - 所有 GATT 操作串行执行；盲文帧只保留最新一帧，避免排队延迟。
- `service/src/.../display/WujieBrailleFrameCodec.java`
  - 输出裸 30 字节帧，按固件规则做 `0xA0` 与 `& 0x3F` 归一化。
- `service/src/.../display/WujieKeyEventCodec.java`
  - 解析 `def1` 的 `next` / `prev`，忽略未知载荷和尾随 NUL。
- `DisplayService`
  - 新增 `wujie` 协议分支，与 HID/RFCOMM 互斥。
  - 把 `def1` 按键转换为 `BrailleInputEvent`，校验连接实例后广播给客户端。
  - 连接失败时给出权限、服务缺失、MTU 不足、无可写特征等具体提示。
- 设置项：`connection_protocol` 新增 `wujie`，显示为
  “无界书城 BLE GATT（厂商协议）”。

## 仍需实机确认

- `def2` / `def3` / `def6` 中哪一个是真正的盲文写通道（代码会自动探测）。
- 30 字节内点阵位与点 1–6 的最终对应关系（当前按标准 Braille 编码）。
- 点阵位与标准 Braille 编码在特殊字符上的最终一致性。

## 验证要点

1. 在设置中选择“无界书城 BLE GATT（厂商协议）”，不要同时让系统 HID 占用连接。
2. 过滤日志：`adb logcat -s DisplayService WujieGattConnection`。
3. 期望看到 `Wujie display ready (MTU=...)` 和特征属性列表。
4. 若出现 `Braille data length ...`，说明写通道或帧长不对；
   若出现 `write ... failed`，查看是否已自动切换到下一个候选。

## 标准 Braille HID 报告描述符

固件 `app-1.0.5-30.bin` 偏移 `0x21bd0` 处有一段 140 字节的标准
Braille HID 报告描述符，紧随其后的是 ASCII 字符串
`hid_braille_build_input_report`。关键结构如下：

| 报告 | 类型 | 内容 | 载荷长度 |
| --- | --- | --- | --- |
| Report ID 1 | Input | 32 个路由键位（usage `0x100..0x11F`）+ 16 个按键位（`0x201..0x209`、`0x21A/0x21B`、`0x210..0x214`）+ 8 bit padding | 7 字节 |
| Report ID 2 | Output | 30 个盲文单元（每单元 8 bit） | 30 字节 |
| Report ID 3 | Feature | 16 bit 单元数（`0..0xFFFF`） | 2 字节 |

注意 Report ID 1 由三个 Input 项串接而成（4 + 2 + 1 字节）。HID 规范要求
同一 report ID 的多个 Input 项按顺序拼接为同一个线上报告，因此载荷长度是
**累加**而不是取最大值。`HidInputReportNormalizer` 早期按最大值计算，
把 7 字节误算成 4 字节，导致归一化从不触发；裸 7 字节报告的首字节被
BRLTTY 当作 report ID 0，`handlePressedKeysArray()` 直接丢弃，表现为
“盲文输出正常但所有按键无反应”。现已改为累加，并用上述真实描述符做回归测试。

Android 15+ 的 `BrailleDisplayController` 只会枚举系统已经注册为 HID 输入
设备的显示。若系统蓝牙里没有该设备（`dumpsys bluetooth_manager` 的
`mInputDevices` 为空、`HID_HOST` 连接策略为 `-1`），`connect()` 会返回
错误码 2（`FLAG_ERROR_BRAILLE_DISPLAY_NOT_FOUND`）。此时需要先把显示器切到
HID 模式，再在系统蓝牙中删除旧配对并重新配对；仅切换 App 内的协议选项不够。
