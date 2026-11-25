# 84 - debug 包加 adb 直达入口（真机冒烟用）

- 日期：2026-11-25
- 背景：视口这几轮的改动都在界面层，真机上要么靠手点、要么根本进不去；盲人用户没法靠点屏幕做回归。

## 1. 做法

新增 `brailleback/src/debug/AndroidManifest.xml`，只在 debug 构建里把「点显图片」页
（`.tactile.TactileStripActivity`）改成 `exported=true`（`tools:replace`），这样 adb 能直接带图进去：

```
adb shell am start -n com.linxi.brailleback/.tactile.TactileStripActivity \
  --es image_file <图片绝对路径>
```

release 构建不合并这个文件，主清单仍是 `exported=false`。

**踩坑**：debug 清单里必须保留 `package="com.linxi.brailleback"`，否则相对类名解析不了，
报的却是 "Error parsing"；另外 XML 注释里不能出现连续两个减号（命令行示例写成 `--es` 会让
清单直接解析失败）。

## 2. 图片路径怎么给

Scoped storage 下 `/sdcard/...` 的 `file://` 读不到（`EACCES`）。冒烟用两步拷进应用私有目录：

```
adb shell cp /sdcard/Download/x.png /data/local/tmp/x.png
adb shell chmod 644 /data/local/tmp/x.png
adb shell run-as com.linxi.brailleback cp /data/local/tmp/x.png files/x.png
adb shell am start ... --es image_file /data/user/0/com.linxi.brailleback/files/x.png
```

## 3. 冒烟结果（小米 24117RK2CC，Android 17）

- 启动无崩溃，`topResumedActivity` 落在 `TactileStripActivity`；
- `rebuild start: uri=tt.png cells=40 lines=1 dots=8 norm=AUTO g1=false` → `route=viewport`；
- `stage=decode viewport gray=1080x2400`；
- `bind=viewport`，随后界面进入视口态，状态行出现「第 1/12 块，第 1/4 屏 · 点显未连接」。

即：选图 → 解码 → 分块 → 视口绑定整条链路在真机上跑通，且没点显器时不崩。

## 4. 没覆盖的

- 点显器按键 / 音量键、图例翻页、独占冻结这三条都要设备在手，冒烟只能验到「不崩」；
- 界面文字节点数会随状态变化，是否还会把文字翻上点显器，得插上设备实测。
