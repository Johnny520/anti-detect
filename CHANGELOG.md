# Changelog

## v1.1.0
- 重构为 **LSPosed 模块 APK**（包名 `com.antidetect.app`），可在 LSPosed 中勾选作用域使用，不再依赖 Zygisk。
- 用 Xposed 钩子在 Java 层实现等价反检测：隐藏 su/magisk 等路径与命令、伪造系统属性、伪装 Build 标记、向目标 App 隐藏 Root/框架类 App。
- 新增设置界面：总开关 + 白名单（包名），配置写入模块私有目录供钩子读取。
- 原 Zygisk 原生实现归档至 `legacy-zygisk/`，保留参考。
- 作者署名：文强哥（Johnny520），MIT 许可证。

## v1.0.0
- 初始版本：基于官方 Zygisk API 的 PLT hook 反检测模块（C++ 原生）。
