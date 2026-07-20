# Anti-Detect 反检测模块（Zygisk）

> **作者**：文强哥（Johnny520）· GitHub：<https://github.com/Johnny520> · 许可证：MIT

一句话：**装上这个模块，别的 App 就探测不到你的手机被 Root 过、装了 Magisk / Xposed / LSPosed / KernelSU、解锁了 Bootloader、或者改过系统文件。** 它们看到的是一台「干干净净的正常用户机」。

---

## 一、它到底挡住了什么

| 检测方式 | 原来会暴露 | 现在返回 |
|---------|-----------|---------|
| 找 su 文件 | `/system/bin/su` 等存在 | 「文件不存在」 |
| 找 Magisk / 框架目录 | `/data/adb/magisk`、`/data/adb/modules`、`/data/adb/lspd`… | 「文件不存在」 |
| 找 busybox / 框架特征 | `busybox`、`libxposed`、`kernelsu`… | 「文件不存在」 |
| 执行 su / magisk 命令 | 能拿到 Root | 伪装成「命令不存在」 |
| 读系统属性 | `ro.debuggable=1`、Bootloader 解锁状态等 | 伪造为「未解锁的用户机」 |

实现方式：用官方 Zygisk API 的 **PLT hook**，在**每个 App 进程里**拦截它调用的 `open/openat/fopen/opendir/access/stat/lstat/fstatat/execve/system/posix_spawn` 以及 `__system_property_get`，命中特征就伪装。Java 层的 `File.exists()`、`System.getProperty()`、`Runtime.exec("su")` 最终都走这些 native 函数，所以一并被挡。

> 默认对**所有用户 App** 生效。自身设置 App（`com.antidetect.app`）和 `white.list` 里的包名不隐藏，避免你自己的 Root 工具被误伤。

---

## 二、怎么装（最直接的办法）

前提：手机已装 **Magisk（并开启 Zygisk）**，或 **KernelSU（开启 Zygisk 接口）**。

1. 下载构建产物 `anti_detect.zip`（见第三节，推到 GitHub 后 Actions 自动编出来）。
2. Magisk → 模块 → 从本地安装 → 选 `anti_detect.zip` → 重启。
3. （可选）装 `AntiDetectApp.apk` 做开关/白名单管理；不装也能用，手动改配置文件即可。
4. 改完**重启一下被测的 App** 就生效。

### 不装 App 也能管（手动改配置）

配置都在 `/data/adb/anti_detect/`（模块刷入时自动建好）：

- `enabled`：写 `1` 开启、`0` 关闭（首字符为 0 即关）。
- `white.list`：一行一个包名，写进去的 App 就不对它隐藏。

例如用 Root 终端放行微信：
```bash
echo "com.tencent.mm" >> /data/adb/anti_detect/white.list
```

---

## 三、怎么编译（GitHub Actions 云端自动构建）

本项目**完全自包含**：官方 `zygisk.hpp` 已 vendored 在 `jni/`，仓库根就是 Magisk 模块根，推上 GitHub 后 Actions 会自动 `ndk-build` 并打包成 `anti_detect.zip`。你不需要在本机装 NDK。

### 方式 A：打 tag 自动发 Release（推荐）
```bash
git tag v1.0.0
git push origin v1.0.0
```
Actions 跑完，在仓库 **Releases** 里就能下载 `anti_detect.zip`。

### 方式 B：手动触发（不用打 tag）
进仓库 **Actions → Build Anti-Detect Module → Run workflow**，跑完在 **Artifacts** 里下载 `anti_detect.zip`。

### 本机编译（可选，需自备 NDK r21+）
```bash
export NDK=/你的/ndk/路径
$NDK/ndk-build NDK_PROJECT_PATH=. APP_BUILD_SCRIPT=./jni/Android.mk NDK_APPLICATION_MK=./jni/Application.mk
# 打包
mkdir -p out/zygisk
cp module.prop customize.sh post-fs-data.sh service.sh uninstall.sh out/
for abi in arm64-v8a armeabi-v7a x86 x86_64; do
  [ -f libs/$abi/libanti_detect.so ] && cp libs/$abi/libanti_detect.so out/zygisk/$abi.so
done
cd out && zip -r ../anti_detect.zip . && cd ..
```

---

## 四、文件结构

```
anti-detect/
├── module.prop / customize.sh / post-fs-data.sh / service.sh / uninstall.sh   # Magisk 模块元信息与脚本
├── jni/
│   ├── module.cpp        # 反检测核心：PLT hook 文件/执行/属性函数
│   ├── zygisk.hpp        # 官方 Zygisk API v4（已 vendored，无需外部模板）
│   ├── Android.mk
│   └── Application.mk
├── app/                  # 简易设置 App（Kotlin，可选；用 Android Studio 单独编）
├── .github/workflows/build.yml   # 云端构建 + 发布工作流
└── README.md
```

---

## 五、已知范围与说明

- **覆盖面**：拦截 App 通过 Java / framework 库（`libandroid_runtime` 等）发起的文件、执行、属性读取——这覆盖了绝大多数 Root / 框架 / 解锁检测 App。
- **不在范围内**：`system_server` 不挂钩（避免影响系统稳定性）；App 自己 `dlopen` 进来、且在 `preAppSpecialize` 之后才加载的 native 库里的检测，PLT hook 可能漏掉——这类极少见，多为 Java 侧检测。
- **想加自己的隐藏规则**：直接改 `jni/module.cpp` 里的 `HIDDEN_PATHS`（路径特征）、`SPOOF_PROPS`（伪造属性）、`HIDDEN_CMDS`（命令关键字），重新构建即可。

---

> 仅供学习和个人隐私保护用途。请勿用于绕过付费、作弊或违反平台规则的行为。
