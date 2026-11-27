# 参与贡献 / Contributing

这个仓库是一个 Android 点显器（盲文显示器）支持项目，主要作者本人是盲人用户，靠点显器与读屏软件工作。
以下几点请先知道，能省很多来回。

---

## 1 先设好署名，否则作者会丢

**这是最容易忘的一步。** 没设 `user.name` / `user.email` 就提交，补丁收进来之后作者会变成别人。

```powershell
git config user.name  "你的名字"
git config user.email "你的邮箱"
```

核对一下确实写进去了：

```powershell
git config --get user.name
git config --get user.email
```

---

## 2 怎么拿代码

### 2.1 有网络、能走 SSH

```powershell
git clone git@github.com:lixuelin-braille/brailleback.git
```

### 2.2 没有 GitHub 账号，或网络到不了 github.com

**不要用 HTTPS clone**：已知在部分网络环境下 `https://github.com/...` 会 SSL 失败、卡约 24 秒后放弃，这不是你配置错了。

改用压缩包：请维护者给一份仓库 zip，解压后就在本地改，最后导出补丁（见第 3 节）。不需要账号、不需要联网。

---

## 3 怎么把改动交回来：优先补丁文件

改完、提交好之后，导出一个补丁发回来。**这样你的作者信息会自动保留。**

单个提交：

```powershell
git format-patch -1 HEAD --stdout > my-fix.patch
```

多个提交：

```powershell
git format-patch origin/main --stdout > my-fix.patch
```

维护者侧收进仓库：

```powershell
git am my-fix.patch
git log -1 --format="%an <%ae>"   # 核对作者是不是你
```

如果只是零散改动、不想写提交信息，也可以发 `git diff > my-fix.diff`，但这样作者信息会记成维护者，**所以更推荐 `format-patch`**。

---

## 4 禁入区：这些地方不要动

### 4.1 四条红线

破了任何一条，用户当天就没法用手机。所以：**宁可慢，不许破。**

1. **盲文输出的正确性**——点位、分词连写、避头尾点；
2. **断连恢复**——点显器断了，App 必须知道并恢复；
3. **TalkBack 朗读语义**——读屏读出来的话不能变味；
4. **已支持点显器的兼容性**——已经能用的设备不能因为改动而失效。

### 4.2 具体禁改的文件与目录

- `keymap/`（按键映射）
- `ime/`（输入法攒点与回显）
- `DisplayManager` 的内部逻辑
- `service/jni/brlttywrapper/third_party/brltty/` 下的 brltty 上游代码：**只许在其外加包装层，不许改上游**

如果你认为某处非改不可，请在补丁说明里写清「改了什么、为什么不得不在禁入区改、可能影响哪条红线」，由维护者判断。

---

## 5 提交前请自己跑完四道门禁

四道全过才算合格。命令是 Windows PowerShell 口径，在仓库根目录执行。

先让 `git` 可用（如果你机器上 git 没进 PATH）：

```powershell
$env:Path += ';C:\Program Files\Git\cmd'
```

### 门禁 1 构建

```powershell
.\gradlew.bat :brailleback:assembleDebug --rerun-tasks
```

必须 `BUILD SUCCESSFUL`。`--rerun-tasks` 不要省，否则可能全是 UP-TO-DATE、根本不出包。

### 门禁 2 单元测试

```powershell
.\gradlew.bat :brailleback:testDebugUnitTest :service:testDebugUnitTest
```

必须全绿（`0 failures`、`0 errors`）。**判据不是某个固定数字**，而是「相对你改动前的自身基线，用例数不减、不新增失败」——因为这个仓库的用例总数会随分支特性增长。

### 门禁 3 静态检查

```powershell
.\gradlew.bat :brailleback:lintDebug :service:lintDebug
```

必须 `0 error`。警告可以留（仓库里有存量警告），但**不许为了让 lint 变绿去关掉规则或加 `@Suppress`**。

### 门禁 4 模拟器冒烟

```powershell
& "$env:ANDROID_HOME\emulator\emulator.exe" -avd brailleback_api35
adb install -r .\brailleback\build\outputs\apk\debug\brailleback-debug.apk
adb shell am start -n com.linxi.brailleback/.HomeContainerActivity
adb shell uiautomator dump /sdcard/ui.xml
adb pull /sdcard/ui.xml
```

要求：装得上、起得来、界面树抓得下来、`logcat` 里没有 `FATAL` / `ANR`。
**模拟器结论不等于真机结论**；涉及点显器手感、播报听感的问题，模拟器验不了，请如实写「未验」。

---

## 6 署名与回滚

### 6.1 版权头

新增源文件请保留项目现有的版权头（Apache 2.0），不要删、不要换。这是许可证要求。

### 6.2 多人协作署名

一个提交有多人参与时，用 `Co-authored-by:` 追加在提交信息末尾：

```
修复翻页边界

Co-authored-by: 甲 <jia@example.com>
Co-authored-by: 乙 <yi@example.com>
```

格式必须是 `Co-authored-by: 名字 <邮箱>`，一行一个人，放在提交信息最后。

### 6.3 回滚

每个功能提交都应当能整体回滚。我们收改动时要求「一处改动一个提交」，就是为了这条。

```powershell
git revert <提交哈希>
```

`revert` 会产生一个反向提交，不改写历史——**这是唯一允许的回滚方式**。
请不要用 `reset --hard`、不要 `push --force`、不要 `rebase` 已经推上去的分支：远端一旦被改写，所有人手里的克隆都废了。

---

## 7 提交信息的写法

一句中文说清做了什么，必要时在正文补充：

```
修复：翻页边界多翻一页

原因：末行孤字时页码计算把下一行算进来了。
影响：仅翻页判据，未改点位输出。
验证：新增 2 条用例，模拟器冒烟通过。
```

如果改动涉及删除功能，请在正文附上回滚方法，例如：

```
回滚：git revert <hash> 可整体恢复
```

---

## 8 有疑问在哪问

在补丁说明或 issue 里直接写清楚：你想改什么、为什么、你验证到哪一步、哪一步没条件验证。
**「没条件验证」是可以接受的答案，「假装验证过」不可以。** 这个项目的用户靠它读东西，一个没验的改动可能让他一整天没法用手机。
