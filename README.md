# Anti-Detect 反检测模块（LSPosed）

> **作者**：文强哥（Johnny520）· GitHub：<https://github.com/Johnny520> · 许可证：MIT

一句话：**装上这个 LSPosed 模块，你勾选的 App 就探测不到手机被 Root 过、装了 Magisk / Xposed / LSPosed / KernelSU、解锁了 Bootloader、或者改过系统文件。** 它们看到的是一台「干干净净的正常用户机」。

---

## 一、它到底挡住了什么

| 检测方式 | 原来会暴露 | 现在返回 |
|---------|-----------|---------|
| 找 su 文件 | `/system/bin/su` 等存在 | 「文件不存在」(`File.exists` 返回 false) |
| 执行 su / magisk 命令 | 能拿到 Root | 伪装成「命令不存在」(`Runtime.exec`/`ProcessBuilder` 抛异常) |
| 读系统属性 | `ro.debuggable=1`、Bootloader 解锁状态等 | 伪造为「未解锁的用户机」 |
| 读 `Build.TAGS` / `Build.TYPE` | `test-keys` / `eng` | 伪造为 `release-keys` / `user` |
| 查已装应用 | 看到 Magisk / LSPosed / Shizuku 等 | 对这些 App「查无此包」 |

实现方式：用 LSPosed（Xposed）API 在**目标 App 进程**里钩住 `Runtime.exec`、`ProcessBuilder.start`、`File.exists`、`SystemProperties.get`、`Build` 静态字段、`PackageManager` 等，命中特征就伪装。

> 默认对**所有勾选了作用域的 App** 生效。模块自身的设置 App（`com.antidetect.app`）和白名单里的包名不隐藏，避免你自己的 Root 工具被误伤。

---

## 二、怎么用（LSPosed）

前提：手机已安装 **LSPosed（基于 Magisk / KernelSU 的 Zygisk）**。

1. 下载 Release 里的 `app-release.apk`（见第三节，推到 GitHub 后 Actions 自动构建并签名）。
2. 安装 APK。
3. 打开 **LSPosed Manager → 模块**，勾选 **Anti-Detect 反检测**，在「作用域」里选中需要隐藏 Root 的目标 App。
4. 打开 Anti-Detect App 可设置总开关与白名单，**重启目标 App** 生效。

### 设置说明
- **总开关**：关闭后对所有 App 停止反检测。
- **白名单**：一行一个包名（如 `com.tencent.mm`），这些 App 不会被隐藏，方便你自己用 Root 工具。
- 配置文件位于模块私有目录 `files/antidetect_config.txt`，钩子进程会自动读取。

---

## 三、怎么编译（GitHub Actions 云端自动构建 + 签名）

本项目使用 Gradle（wrapper 已内置），推上 GitHub 后 Actions 会自动用仓库 **Secrets 里的签名密钥** 构建出**正式签名版** `app-release.apk`。

### 方式 A：打 tag 自动发 Release（推荐）
```bash
git tag v1.1.0
git push origin v1.1.0
```
Actions 跑完，在仓库 **Releases** 里就能下载签名后的 `app-release.apk`。

### 方式 B：手动触发（不用打 tag）
进仓库 **Actions → Build Anti-Detect (LSPosed Module) → Run workflow**，跑完在 **Artifacts** 里下载 APK。

### 本机编译（可选）
```bash
./gradlew assembleRelease
# 本机未配置签名密钥时不会产出可安装 release；CI 中用 Secrets 签名
```

---

## 四、文件结构

```
anti-detect/
├── app/                          # LSPosed 模块 Android 工程（产出 APK）
│   ├── src/main/
│   │   ├── AndroidManifest.xml   # xposedmodule 元数据
│   │   ├── assets/xposed_init    # 模块入口类
│   │   └── java/com/antidetect/app/
│   │       ├── MainHook.java      # 反检测核心：Xposed 钩子
│   │       └── MainActivity.java  # 开关 / 白名单设置界面
│   └── build.gradle.kts          # 含从环境变量读取的签名配置
├── legacy-zygisk/                # 旧版 Zygisk 原生实现（已归档，仅供参考）
│   ├── jni/                      # module.cpp + zygisk.hpp + Makefile
│   └── module.prop / *.sh        # Magisk 模块元信息与脚本
├── .github/workflows/build.yml   # 云端构建 + 签名 + 发布工作流
├── CHANGELOG.md
├── LICENSE
└── README.md
```

---

## 五、已知范围与说明

- **覆盖面**：拦截 App 通过 Java / framework 发起的文件、执行、系统属性、已装应用查询——覆盖绝大多数 Root / 框架 / 解锁检测。
- **不在范围内**：`system_server` 等系统进程不挂钩；App 通过 native 层直接 `open`/`stat` 且绕过 Java 的检测，LSPosed（Java 层）可能漏掉——这类极少见。
- **想加自己的规则**：直接改 `app/src/main/java/com/antidetect/app/MainHook.java` 里的 `HIDDEN_PATHS` / `HIDDEN_CMDS` / `SPOOF_PROPS` / `HIDDEN_PKGS`，重新构建即可。

---

> 仅供学习和个人隐私保护用途。请勿用于绕过付费、作弊或违反平台规则的行为。
