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
