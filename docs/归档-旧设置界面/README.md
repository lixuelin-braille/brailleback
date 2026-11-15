# 归档：旧设置界面（androidx.preference 五子屏）

2026-11-15 归档。五个子屏的内容全部由 `brailleback/src/.../brailleback/compose/` 下的独立
Activity 渲染，`res/xml/settings.xml` 只保留主屏与五个入口行，本目录保存旧实现原文，
用于回退与出厂默认值核对。

## 文件

| 文件 | 内容 |
| --- | --- |
| `settings-legacy.xml` | 旧 `settings.xml` 第 34–293 行逐字原文，含五个子屏的全部 `app:key` / `app:defaultValue` / `app:dependency`。根节点是 `<PreferenceScreen>`，五个子屏在其下平级。 |

偏好键（`app:key` 指向的字符串）没有删除，仍在 `res/values/donottranslate.xml`，
服务端与 Compose 两侧读的是同一批 `SharedPreferences` 键，归档界面项不影响取值。

## 回退步骤

1. 打开 `brailleback/res/xml/settings.xml`，把「设置分类」分组里的五个入口行删掉。
2. 从 `settings-legacy.xml` 复制五个子屏整段（`<PreferenceScreen app:key="@string/pref_category_device_key"` 起，
   到开发者子屏的 `</PreferenceScreen>` 止），贴回该分组。
3. 删掉 `SettingsActivity.onPreferenceStartScreen` 里五条 `startActivity(Intent(this, XxxComposeActivity::class.java))` 分支，
   让嵌套子屏重新走 `PreferenceFragmentCompat` 的 rootKey 导航。
4. 从 `SettingsActivity` 的 git 历史里恢复被删的绑定逻辑（`addTableList`、`showVolumeDialog`、
   日志对话框、`onTablesChanged` 等），或直接 `git revert` 归档提交。
5. 把 `ComposeMigrationContractTest` / `SettingsStructureContractTest` 等契约测试里的
   `legacyScreen()` 路径改回 `brailleback/res/xml/settings.xml`。

## 五个子屏各自迁去哪

| 旧子屏 | 现值 | 入口 key |
| --- | --- | --- |
| 设备与连接 | `DeviceConnectionComposeActivity` | `category_device` |
| 按键与手势 | `KeysGesturesComposeActivity` | `category_keys` |
| 盲文显示 | `BrailleDisplayComposeActivity` | `category_braille` |
| 声音与行为 | `SoundBehaviorComposeActivity` | `category_sound_behavior` |
| 开发者选项 | `DeveloperOptionsComposeActivity` | `category_developer` |

各子屏内的偏好键（`connection_protocol`、`braille_table`、`log_level`、`overlay_enabled` …）
一个未删，Compose 侧读写同一份 `SharedPreferences`。

## 修改约束

- 不要改 `settings-legacy.xml` 里的 `app:defaultValue`：`KeysGesturesContractTest`、
  `SoundBehaviorContractTest`、`DeveloperOptionsContractTest`、`BrailleDisplayContractTest`
  都以本文件为准核对 Compose 侧的出厂默认值，改了等于两套默认值悄悄分家。
- 本文件不参与编译（不在 `res/` 下），新增界面项不要写到这里。
