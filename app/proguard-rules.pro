# Anti-Detect LSPosed 模块：保留 Xposed 入口与钩子类
-keep class com.antidetect.app.** { *; }
-keep class * implements de.robv.android.xposed.IXposedModLoadPackage { *; }
-keep class * implements de.robv.android.xposed.IXposedHookLoadPackage { *; }
