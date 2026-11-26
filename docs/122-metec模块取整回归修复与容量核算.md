# 122 · Metec 模块数取整回归修复与容量核算

## 现象

Metec 40+2（共 42 格）有线设备上，行尾别格与状态格发不出去。驱动
`service/jni/brlttywrapper/third_party/brltty/Drivers/Braille/Metec/braille.c`
的 `setCellCount()` 里，`moduleCount` 一度被改成整除（向下取整）：

```c
brl->data->moduleCount = (brl->data->cellCount = count) / MT_MODULE_SIZE;
```

42 / 8 整除得 5 个模块，只发 5×8=40 字节，第 41、42 格（含 2 格状态格）落在发送范围外。
22 格设备同理：22 / 8 整除得 2 模块（16 字节），末格被挤掉。

## 改动

同文件 `setCellCount()`：

- 恢复向上取整公式：

  ```c
  brl->data->moduleCount =
      ((brl->data->cellCount = count) + MT_MODULE_SIZE - 1) / MT_MODULE_SIZE;
  ```

- 按旧工程原文恢复修复注释（说明 42 → 6 模块 / 48 字节、缓冲区与数组容量、
  末模块补零由硬件忽略、22 → 3 模块同源修复）。
- 保留新增的越界守卫：`count > MT_CELLS_MAXIMUM` 时记 `LOG_WARNING` 并 `return 0`；
  调用方（`brl_construct` 读状态包后）本来就按返回值走初始化失败分支，语义闭环。

## 容量核算

源码常量：

| 常量 | 值 | 用途 |
|:---|:---|:---|
| `MT_MODULE_SIZE` | 8 | 每模块 8 格 / 8 字节 |
| `MT_MODULES_MAXIMUM` | 10 | `writeModule[]` 数组项数上限 |
| `MT_CELLS_MAXIMUM` | 80 | `newCells[]` / `oldCells[]` 字节数上限 |

`newCells[MT_CELLS_MAXIMUM]` = 80 字节，`writeModule[MT_MODULES_MAXIMUM]` = 10 项。

| 格数 | 模块数（向上取整） | 占用字节 | 占 writeModule | 结论 |
|:---|:---|:---|:---|:---|
| 22 | 3 | 24 | 3 / 10 | 在容量内 |
| 40 | 5 | 40 | 5 / 10 | 在容量内（整除边界） |
| 42 | 6 | 48 | 6 / 10 | 在容量内，尾部别格与状态格全覆盖 |
| 80 | 10 | 80 | 10 / 10 | 合法上限，恰好顶满 |
| 81+ | — | — | — | 守卫拒绝（`return 0`），不写 `moduleCount` |

末模块超出实际格数的补零字节：`brl_construct()` 里数据结构 `malloc` 后
`memset(..., 0, sizeof(*brl->data))` 清零，补零恒为 0，硬件忽略即可。
42 格的 48 字节 < 80 字节、6 项 < 10 项，没有越界写风险；守卫边界 80 是合法值，
不会把 42 这类合法格数误拒。

## 测试分层

- **门禁内（JVM/Kotlin）**：`brailleback/src/test/kotlin/com/linxi/brailleback/MetecCellCountContractTest.kt`。
  直接读 `braille.c` 源码，常量（模块大小 / 模块上限 / 格数上限）一律从源码解析，
  不在 Kotlin 里另抄一份；断言：向上取整公式在位、无整除回归写法、越界守卫与
  `return 0` 在位、恢复的注释锚点在位、数组声明在位；映射表 42→6 / 22→3 / 40→5
  复算并复核字节与项数容量；80 合法边界 → 10 模块顶满、81 越界拒绝。共 3 例。
- **host C 可执行程序**：`tools/metec_cellcount_host_test.c`。把不依赖
  `brl_driver.h` 的守卫 + 取整算术单独抽出，用 NDK clang 18 编出 host Windows
  可执行程序跑断言（42→6、22→3、40→5、81 拒绝，外加 80 边界与两条容量复核，
  共 15 条检查）。编译命令见文件头注释；退出码 0 全绿、1 有失败。
  曾用整除变体反跑：42→5、22→2 两条按预期 FAIL，退出码 1，证明断言能抓住回归。

## 验证结果

- `:brailleback:testDebugUnitTest --offline -x verifyNativeLibs`：**990 例 / 0 失败 / 0 错误**
  （基线 987 + 本次新增 3）。
- `:brailleback:assembleDebug --offline -x verifyNativeLibs`：BUILD SUCCESSFUL，
  debug APK 产出。
- `-x verifyNativeLibs` 是项目既有约定（README / 各稳定性文档一致）：该任务比对
  jni 源码 mtime 与预编译 `.so` mtime，全新 worktree 检出会产生时间差漂移，
  CI 同样跳过它。

## 未验（等摸到真机）

- **真机前置（不完成则装包不生效）**：预编译 `.so` 本次**没有重编**，改动只在 C
  源码，进包的仍是 `service/jniLibs` 里的旧 `.so`。也就是说，当前分支打出来的 APK
  在真机上**不会**带这次取整修复的效果。真机验收前必须先用 ndk-build 重编
  arm64-v8a / armeabi-v7a 两条 ABI 并覆盖 `service/jniLibs`，再出包安装；
  不能假设装了现有 APK 就已经修好。
- 真实 Metec 42 格设备上的实测：连接后末两格与状态格是否上屏、尾部格的路由键
  映射是否正确，需要插设备确认；host 测试与契约测试覆盖不到硬件时序与回包协议。
