# 触觉图 Python 独立复算校验器

## 它解决什么问题

`TactileRasterizer`（栅格化）+ `TactileBmpEncoder`（8 位灰度 BMP 编码）是纯 JVM 内核，
仓库里的单测只能证明「Kotlin 和他们自己的期望一致」。本工具用一份**完全独立的 Python 实现**
按同一规格重算，再与内核真实输出做**逐字节**比对，用来回答：图片翻译成触觉图这一步，
尺寸换算、面积平均重采样、三种抬升策略、翻转、留白、BMP 布局里有没有算错。

## 校验内容

1. **BMP 结构**：`BM` 魔数、像素偏移 1078（14+40+256×4）、BITMAPINFOHEADER 长 40、
   宽高、平面数 1、位深 8、BI_RGB、图像字节数、x/y ppm 一致、行 4 字节对齐且填充全 0、
   256 项恒等灰度调色板（B=G=R=v，第 4 字节 0）。
2. **像素**：Python 复算的抬升等级 → 编码极性（像素 = 255 − level）→ 与 BMP 实际像素逐点比。
3. **字节**：Python 重新编码整份 BMP，SHA256 必须与内核输出完全相同（连头部、调色板、
   自下而上的行序都一致）。

## 用法

```powershell
# 1) 把内核真实输出导出来（临时用例，跑完可从 src/test 删掉）
Copy-Item tools\tactile-verify\harness\TactilePythonDumpTest.kt `
          brailleback\src\test\kotlin\com\linxi\brailleback\tactile\
.\gradlew.bat :brailleback:testDebugUnitTest `
    --tests 'com.linxi.brailleback.tactile.TactilePythonDumpTest' --offline --console=plain
# 产物在 %TEMP%\tactile-dump\<用例>.bmp / .src / .meta

# 2) Python 复算并比对（numpy 即可；本机用 D:\软件\brailix-test\venv）
python tools\tactile-verify\verify_tactile.py --dump-dir "$env:TEMP\tactile-dump" `
    --device-src <源图.bmp> --device-out <手机导出的.bmp>
```

退出码 0 = 全部通过；非 0 = 有失败项，逐条打印原因（尺寸不符 / 像素差 / 字节不一致 / 头字段错）。

## 已覆盖的用例与实测结论（2026-11-24）

`harness/TactilePythonDumpTest.kt` 覆盖 9 个情形：A4 竖版阈值/灰度/边缘三种模式、
阈值 200 + 翻转、阈值 0、阈值 255、边缘阈值 200、非整数缩放（37×23 → 50×40 mm，591×472 px）、
双侧毫米为 0 的自动尺寸（长边 160 mm，1890×1417 px）。

结果：**9 个内核用例 + 1 个设备实链路全部 PASS，像素差 0，整份 BMP SHA256 完全一致。**

设备实链路是 `emulator-5554` 上「相册 BMP（2480×3508，8 位灰度）→ 应用导出」的真实产物：
`tactile_1000000153_20261123_2323.bmp`，A4 竖版 300 dpi、阈值 128，与 Python 复算逐字节相同。

## 与 Kotlin 的对应关系

| 环节 | Kotlin | 本脚本 |
|---|---|---|
| 毫米→像素 | `TactileProfile.mmToPx` = round(mm·dpi/25.4) | `mm_to_px` |
| 自动尺寸 | `derivePhysicalSizeMm`（双侧 0 → 长边 160 mm） | `derive_box` |
| 等比贴合 | `fitContent`（两轴取较小缩放比、居中、留白=0） | `fit_content` |
| 面积平均重采样 | `resampleByArea` / `areaAverage` | `resample_area`（权重矩阵） |
| 阈值 / 灰度 / 边缘 | `when (s.mode)`、`edgeLevel`（Sobel 截顶 1020） | `rasterize` / `sobel_levels` |
| 翻转 | 只翻内容，留白不翻 | 同 |
| BMP 编码 | `TactileBmpEncoder`（像素=255−level，自下而上） | `encode_bmp` |

## 已知边界

- 脚本输入是**已算好的亮度平面**（`.src`），不覆盖 `AndroidImageLoader` 的
  RGB→luma（BT.601 加权 + 四舍五入）。设备实链路那一条会间接校验它：
  源 BMP 调色板恒等灰度，所以 luma 必须等于像素值，否则逐字节不可能相同。
- 重采样用矩阵乘法实现，求和顺序与 Kotlin 的逐像素累加不完全相同；
  浮点末尾的差异在取整后没有造成任何像素差（9 例均 0 差异）。
