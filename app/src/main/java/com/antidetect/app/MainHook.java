package com.antidetect.app;

import android.content.pm.PackageManager;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Anti-Detect LSPosed 模块
 *
 * 用 Xposed 钩子实现与原 Zygisk 模块等价的反检测逻辑：
 *  - 隐藏 su / magisk / kernelsu 等路径与命令（Runtime.exec / ProcessBuilder / File.exists）
 *  - 伪造系统属性（ro.debuggable / ro.secure / ro.build.type / ro.boot.* 等）
 *  - 伪装 Build.TAGS / Build.TYPE 为出厂状态
 *  - 对目标 App 隐藏 Root / 框架类 App（Magisk / LSPosed / Shizuku…）
 */
public class MainHook implements IXposedHookLoadPackage {
    private static final String TAG = "AntiDetect";
    private static final String SELF_PKG = "com.antidetect.app";
    private static final String CONFIG_PATH = "/data/data/com.antidetect.app/files/antidetect_config.txt";

    // 路径里出现这些特征 → 伪装成“文件不存在”
    private static final String[] HIDDEN_PATHS = {
            "/sbin/su", "/system/bin/su", "/system/xbin/su", "/su/bin/su",
            "/data/local/su", "/data/local/bin/su", "/data/local/xbin/su",
            "/system/xbin/daemonsu", "/system/bin/.ext", "/system/app/superuser",
            "busybox", "/data/adb/magisk", "/sbin/.magisk", ".magisk",
            "/data/adb/modules", "/data/adb/modules_update", "libxposed",
            "/data/adb/lspd", "/data/adb/lsposed", "kernelsu",
            "/data/adb/ksu", "/data/adb/.ksu", "supersu", "daemonsu",
            "magisk", "xposed", "lposed"
    };

    // 命令里出现这些关键字 → 伪装成“命令不存在”
    private static final String[] HIDDEN_CMDS = {
            "su", "magisk", "kernelsu", "busybox", "daemonsu", "supersu"
    };

    // 需要伪造的系统属性：让“已 root / 已解锁 / debug 版”看起来是出厂状态
    private static final String[][] SPOOF_PROPS = {
            {"ro.debuggable", "0"},
            {"ro.secure", "1"},
            {"ro.build.type", "user"},
            {"ro.build.tags", "release-keys"},
            {"ro.boot.flash.locked", "1"},
            {"ro.boot.verifiedbootstate", "green"},
            {"ro.boot.veritymode", "enforcing"},
            {"ro.boot.warranty_bit", "0"},
            {"ro.warranty_bit", "0"},
            {"ro.vendor.boot.warranty_bit", "0"},
            {"ro.boot.fused_vbmeta_init_done", "1"},
            {"ro.kernel.qemu", "0"},
            {"ro.build.selinux", "enforcing"}
    };

    // 需要向目标 App 隐藏的 Root / 框架类 App
    private static final String[] HIDDEN_PKGS = {
            "com.topjohnwu.magisk", "com.topjohnwu.magisk.debug", "io.github.vvb2060.magisk",
            "me.weishu", "me.weishu.kernelsu", "me.weishu.magisk",
            "org.lsposed.manager", "org.lsposed.manager.debug",
            "com.rikka.shizuku", "moe.shizuku.privileged.api", "rikka.shizuku",
            "com.tsng.hidemyapplist", "com.kieronquinn.app.smartspacer",
            "de.robv.android.xposed.installer", "org.meowcat.edxposed.manager",
            "com.solohsu.android.edxp.manager", "com.bugparty.lspatch",
            "io.github.lsposed.lspatch", "org.lsposed.lspatch"
    };

    // 运行时配置缓存
    private static boolean sEnabled = true;
    private static final Set<String> sWhitelist = new HashSet<>();

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        String pkg = lpparam.packageName;
        if (SELF_PKG.equals(pkg)) return;

        loadConfig();
        if (!sEnabled) return;
        if (sWhitelist.contains(pkg)) return;

        try { spoofBuildFields(lpparam.classLoader); } catch (Throwable t) { Log.w(TAG, "spoofBuild", t); }
        try { hookSystemProperties(lpparam.classLoader); } catch (Throwable t) { Log.w(TAG, "hookSP", t); }
        try { hookRuntimeExec(lpparam.classLoader); } catch (Throwable t) { Log.w(TAG, "hookExec", t); }
        try { hookProcessBuilder(lpparam.classLoader); } catch (Throwable t) { Log.w(TAG, "hookPB", t); }
        try { hookFileExists(lpparam.classLoader); } catch (Throwable t) { Log.w(TAG, "hookFile", t); }
        try { hookPackageManager(lpparam.classLoader); } catch (Throwable t) { Log.w(TAG, "hookPM", t); }

        Log.i(TAG, "AntiDetect 已加载 - " + pkg);
    }

    /* ===================== 配置读取 ===================== */
    private void loadConfig() {
        sEnabled = true;
        sWhitelist.clear();
        try (BufferedReader br = new BufferedReader(new FileReader(CONFIG_PATH))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                if (line.startsWith("enabled=")) {
                    sEnabled = !line.substring("enabled=".length()).trim().startsWith("0");
                } else if (line.startsWith("whitelist=")) {
                    for (String p : line.substring("whitelist=".length()).split(",")) {
                        p = p.trim();
                        if (!p.isEmpty()) sWhitelist.add(p);
                    }
                } else {
                    sWhitelist.add(line);
                }
            }
        } catch (Throwable t) {
            // 配置文件不可读 → 默认开启、无白名单
        }
    }

    /* ===================== Build 字段伪装 ===================== */
    private void spoofBuildFields(ClassLoader cl) {
        Class<?> build = XposedHelpers.findClass("android.os.Build", cl);
        setStatic(build, "TAGS", "release-keys");
        setStatic(build, "TYPE", "user");
    }

    private void setStatic(Class<?> c, String field, Object val) {
        try { XposedHelpers.setStaticObjectField(c, field, val); } catch (Throwable ignored) {}
    }

    /* ===================== 系统属性伪造 ===================== */
    private void hookSystemProperties(ClassLoader cl) {
        Class<?> sp = XposedHelpers.findClass("android.os.SystemProperties", cl);
        XC_MethodHook hook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Object res = param.getResult();
                if (res instanceof String) {
                    String key = (String) param.args[0];
                    String spoof = spoofProp(key);
                    if (spoof != null) param.setResult(spoof);
                }
            }
        };
        XposedBridge.hookAllMethods(sp, "get", hook);
    }

    private String spoofProp(String key) {
        if (key == null) return null;
        for (String[] p : SPOOF_PROPS) {
            if (p[0].equals(key)) return p[1];
        }
        return null;
    }

    /* ===================== 执行命令隐藏 ===================== */
    private void hookRuntimeExec(ClassLoader cl) {
        Class<?> rt = XposedHelpers.findClass("java.lang.Runtime", cl);
        XC_MethodHook hook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                String cmd = cmdOf(param.args);
                if (cmd != null && containsCmd(cmd)) {
                    param.setThrowable(new IOException(
                            "Cannot run program \"" + firstWord(cmd) + "\": error=2, No such file or directory"));
                }
            }
        };
        XposedBridge.hookAllMethods(rt, "exec", hook);
    }

    private void hookProcessBuilder(ClassLoader cl) {
        Class<?> pb = XposedHelpers.findClass("java.lang.ProcessBuilder", cl);
        XposedBridge.hookAllMethods(pb, "start", new XC_MethodHook() {
            @Override
            @SuppressWarnings("unchecked")
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    ProcessBuilder b = (ProcessBuilder) param.thisObject;
                    List<String> cmds = b.command();
                    if (cmds != null && containsCmd(String.join(" ", cmds))) {
                        param.setThrowable(new IOException("No such file or directory"));
                    }
                } catch (Throwable ignored) {}
            }
        });
    }

    private String cmdOf(Object[] args) {
        if (args == null || args.length == 0) return null;
        if (args[0] instanceof String) return (String) args[0];
        if (args[0] instanceof String[]) return String.join(" ", (String[]) args[0]);
        return null;
    }

    /* ===================== 文件路径隐藏 ===================== */
    private void hookFileExists(ClassLoader cl) {
        Class<?> file = XposedHelpers.findClass("java.io.File", cl);
        XposedBridge.hookAllMethods(file, "exists", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    File f = (File) param.thisObject;
                    String path = f.getPath();
                    if (path != null && containsPath(path)) param.setResult(false);
                } catch (Throwable ignored) {}
            }
        });
    }

    /* ===================== 隐藏 Root / 框架 App ===================== */
    private void hookPackageManager(ClassLoader cl) {
        Class<?> pm = XposedHelpers.findClass("android.app.ApplicationPackageManager", cl);

        XposedBridge.hookAllMethods(pm, "getPackageInfo", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                String p = (String) param.args[0];
                if (p != null && isHiddenPkg(p)) {
                    param.setThrowable(new PackageManager.NameNotFoundException("Package " + p + " not found"));
                }
            }
        });

        XC_MethodHook filter = new XC_MethodHook() {
            @Override
            @SuppressWarnings("unchecked")
            protected void afterHookedMethod(MethodHookParam param) {
                Object res = param.getResult();
                if (res instanceof List) {
                    ((List<Object>) res).removeIf(o -> {
                        try {
                            String p = (String) XposedHelpers.getObjectField(o, "packageName");
                            return isHiddenPkg(p);
                        } catch (Throwable t) { return false; }
                    });
                }
            }
        };
        XposedBridge.hookAllMethods(pm, "getInstalledPackages", filter);
        XposedBridge.hookAllMethods(pm, "getInstalledApplications", filter);
    }

    /* ===================== 工具 ===================== */
    private boolean containsPath(String s) {
        String l = s.toLowerCase();
        for (String h : HIDDEN_PATHS) if (l.contains(h)) return true;
        return false;
    }

    private boolean containsCmd(String s) {
        String l = s.toLowerCase();
        for (String h : HIDDEN_CMDS) if (l.contains(h)) return true;
        return false;
    }

    private boolean isHiddenPkg(String p) {
        if (p == null) return false;
        for (String h : HIDDEN_PKGS) {
            if (h.equals(p) || p.contains(h)) return true;
        }
        return false;
    }

    private String firstWord(String cmd) {
        int i = cmd.indexOf(' ');
        return i < 0 ? cmd : cmd.substring(0, i);
    }
}
