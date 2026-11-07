# BrailleBack app proguard rules (R8 试点 2026-09-03)

# ── 项目自身类全保留 ─────────────────────────────────────────────
# 排查结论（试点前的反射/JNI/AIDL 面排查）：
# 1. JNI 回调：BrlttyWrapper.c:575 直接 FindClass 查找项目类
#    com/googlecode/eyesfree/braille/display/BrailleInputEvent；
#    LibLouisWrapper.c:281 存在通用 FindClass 路径。
#    类名/成员名一旦被 R8 重命名，native 侧反查即失败（运行时才炸，难归因）。
# 2. AIDL：client 模块 10 个 .aidl（IBrailleService / ITranslatorService 等
#    Stub 与 Parcelable 序列化契约）。
# 3. 反射工具：CompatUtils（Class.forName / getDeclaredMethod / getDeclaredField）
#    就在本包内，虽当前调用目标均为系统类，仍一并保留。
# 项目自身 dex 仅 ~1.5 MB，不做混淆/重命名的体积损失极小；
# R8 的体积收益来自 androidx 未用代码的裁剪（androidx 自带 consumer rules）。
-keep class com.googlecode.eyesfree.** { *; }

# 双保险：所有含 native 方法的类保类名与方法名（JNI 注册依赖符号名）
-keepclasseswithmembernames class * {
    native <methods>;
}

# ── 移植的 TalkBack 盲文子系统（addon/ 下 6 个模块）────────────────
# 必须整包保留，理由：
# 1. JNI：LouisTranslation 的 native 符号硬编码为
#    Java_com_google_android_accessibility_braille_translate_liblouis_LouisTranslation_*，
#    包名/类名被重命名后 native 侧找不到实现，运行时才崩。
# 2. 输入法与设置页由系统按类名反射实例化（manifest 中的 android:name 字符串），
#    Activity/Service/Receiver 类名一旦变化即无法启动。
# 3. AutoValue 生成类（AutoValue_*）通过构造器直接 new，混淆后行为未验证。
# 4. 这些类合计体积很小（dex 增量 < 1 MB），保留代价可忽略。
-keep class com.google.android.accessibility.** { *; }
