# Morg

**为你正在用的应用再开一个实例。**

[English](README.en.md) · [源码](https://github.com/Aiordin/morg) · [下载](../../releases/latest) · [MIT 许可](LICENSE)

Morg 是一个 Android 多开窗口工具，能够为正在运行的应用新开一个task.

> 0.1.0，实验性。核心行为依赖各家 ROM 的窗口管理实现，请先读[限制](#限制)。

## 限制

- **多开是多个 Task，不是多个进程。** 同一应用的实例共享一个进程，只是各自拥有
  返回栈与 Activity。
- **小窗需要系统支持。** ColorOS 使用原生小窗（`windowingMode=100`）；其他设备
  需要开发者选项里的「可自由调整的窗口」/「强制允许 Activity 可调整大小」
  （`windowingMode=5`，观感不同）。两者都没有时只能全屏。
- **最低 Android 10（API 29）。** 目标版本为 Android 14（API 34）。
- 只在 OnePlus PJF110 / ColorOS 14 上做过真机验证；其他 ROM 可能表现不同。

## 权限

| 权限 | 用途 | 必需 |
|---|---|---|
| 显示在其他应用上层 | 绘制边缘手柄 | 是 |
| 使用情况访问 | 判断当前前台应用，决定手柄是否显示 | 是 |
| 通知 | 前台服务的常驻通知，内含「停止」按钮 | 否（拒绝后手柄照常工作） |

## 安装

1. 从 [Releases](../../releases/latest) 下载 `Morg-*.apk`，或自行构建。
2. 首次打开会看到一张欢迎页，介绍三个开关；点「开始使用」进入设置页。
3. 打开「边缘手柄」开关，按引导授予两项必需权限。
4. 「打开方式」默认全屏；需要小窗时打开该开关，首次会引导你用一扇真实的小窗确认能力。
5. 服务开启后还可以打开「显示工具栏」，屏幕底部会出现一个悬浮按钮，点一下即可多开。

## 构建

要求：

- JDK 17
- Android SDK：Platform 35、Build-Tools 35.0.0
- 项目自带 Gradle Wrapper（Gradle 9.3.1）


## 项目结构

```
app/src/main/kotlin/aiordin/morg/
├── MorgApp.kt                 应用入口 + 手写依赖装配
├── MainActivity.kt            唯一 Activity：引导 / 设置页
├── core/                      设置、权限、引导规则、构建信息格式化
├── launch/                    窗口模式、启动参数、小窗自检
├── overlay/                   手柄与工具栏窗口、手势几何、前台识别、前台服务
├── qs/                        控制中心磁贴
└── ui/                        Compose 界面、首次引导与主题
app/src/test/kotlin/aiordin/morg/   JVM 单元测试
gradle/libs.versions.toml       依赖版本集中管理
flake.nix                       开发环境
```

**纯逻辑与 Android API 分离**是项目的硬约定：窗口几何、前台判定、引导规则、
小窗判定都不引用 Android 类型，因此可以直接在 JVM 上单测。需要矩形时用项目自己的
`WindowBounds`，而不是 `android.graphics.Rect`（后者在单测里是空实现）。

## 贡献

欢迎 issue 和 pull request。涉及多开机制或窗口行为的改动，请先在 issue 里讨论，
并说明实测设备与 ROM 版本。

## 许可

[MIT](LICENSE)
