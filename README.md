# 呆呆面板 Android (Daidai Panel for Android)

> 安装即用，无需 Docker、无需服务器、无需 Root，在手机本机创建、管理和运行面板。

[![Build APK](https://github.com/tall-1997/daidai-panel-android/actions/workflows/build-android.yml/badge.svg)](https://github.com/tall-1997/daidai-panel-android/actions/workflows/build-android.yml)
[![Release](https://img.shields.io/github/v/release/tall-1997/daidai-panel-android)](https://github.com/tall-1997/daidai-panel-android/releases)

## 项目简介

本项目是 [呆呆面板 (daidai-panel)](https://github.com/linzixuanzz/daidai-panel) 的 Android 原生 App 版本。用户只需安装 APK，即可在手机上完整运行面板——包括定时任务调度、Python/Node/Shell 脚本执行、环境变量管理、通知推送等全部功能。

### 核心特性

- **安装即用** — 无需 Root、无需 Docker、无需 Termux
- **开箱即用** — Python 3.14 + Node.js 26 + bash 全部内置
- **本地运行** — 所有数据存储在 App 私有目录，无需外部服务器
- **前台保活** — ForegroundService + WAKE_LOCK 防止进程被杀
- **开机自启** — BOOT_COMPLETED 接收器
- **日志悬浮窗** — 实时查看后端启动日志，方便调试

## 架构

```
┌──────────────────────────────────────────────────────┐
│              呆呆面板 App (APK)                       │
├──────────────────────────────────────────────────────┤
│  Android 原生壳 (Kotlin)                              │
│  ├─ ForegroundService (保活 Go 后端进程)               │
│  ├─ WebView (加载 http://127.0.0.1:5700)              │
│  ├─ 日志悬浮窗 (SYSTEM_ALERT_WINDOW)                   │
│  └─ 开机自启 + 防休眠 + 电池优化白名单                  │
├──────────────────────────────────────────────────────┤
│  Go 后端 (arm64 静态二进制)                            │
│  ├─ Gin HTTP Server (端口 5700)                        │
│  ├─ SQLite (纯 Go, App 私有目录)                       │
│  ├─ 前端静态资源托管                                    │
│  └─ 脚本执行器 (调用内置 python3/node/bash)             │
├──────────────────────────────────────────────────────┤
│  内置运行时 (Termux 预编译包, Bionic libc)              │
│  ├─ Python 3.14.6  (packages.termux.dev)             │
│  ├─ Node.js 26.4.0 (packages.termux.dev)              │
│  ├─ bash 5.3.9 + coreutils 9.11                      │
│  └─ git, curl, grep, sed, gawk 等工具                  │
└──────────────────────────────────────────────────────┘
```

## 下载安装

1. 前往 [Releases](https://github.com/tall-1997/daidai-panel-android/releases) 下载最新 APK
2. 在手机上允许"安装未知来源应用"
3. 安装并打开 App
4. 首次启动约需 30-60 秒（解压内置运行时），请耐心等待

## 构建

本项目通过 GitHub Actions 自动构建，无需本地开发环境。

### 手动触发构建

1. 在仓库页面点击 **Actions** 标签
2. 选择 **Build Android APK** workflow
3. 点击 **Run workflow** 按钮
4. 等待构建完成后从 Artifacts 下载 APK

### 构建流程

| 阶段 | 说明 |
|------|------|
| Build Go Server | 交叉编译 Go 后端为 `GOOS=linux GOARCH=arm64` 静态二进制 |
| Build Frontend | 使用 Vite 构建 Vue3 前端静态资源 |
| Package Runtimes | 从 Termux 官方仓库下载 Python/Node/bash 并打包 |
| Build APK | 组装资源 + Gradle 构建 + 签名 APK |

## 技术实现

### Go 后端适配

原项目后端为 Docker/服务器部署设计，本项目做了以下适配：

- **二进制打包**：Go 后端交叉编译为 arm64 静态二进制，打包为 `libdaidai-server.so` 放入 APK 的 `jniLibs` 目录，Android 自动解压到 `nativeLibraryDir` 并设置可执行权限
- **路径适配**：通过环境变量 `DAIDAI_DATA_DIR`/`DAIDAI_SCRIPTS_DIR`/`DAIDAI_LOG_DIR` 将数据目录指向 App 私有目录
- **解释器查找**：`resolveManagedBinary` 增加 `exec.LookPath` fallback 和 bash→sh 回退
- **Android 检测**：`androidSupported()` 增加 `DAIDAI_ANDROID_APP` 环境变量检测

### 运行时方案

Android 使用 Bionic libc（非 glibc），不能直接运行 Linux 预编译二进制。本项目使用 [Termux](https://termux.dev) 项目为 Android Bionic 编译的包：

- 从 `packages.termux.dev` 下载 `.deb` 包
- 解包到 App 私有目录的 `termux-prefix/usr/` 结构
- 通过 `LD_LIBRARY_PATH` + `PYTHONHOME` + `PATH` 环境变量重定位路径
- `targetSdkVersion=28` 绕过 Android 10+ 的 W^X 限制

## 开源许可与引用声明

### 本项目

本项目基于 [daidai-panel](https://github.com/linzixuanzz/daidai-panel) 改造，遵循其原有许可证。

### 引用的开源项目与库

#### 上游项目

| 项目 | 许可证 | 用途 |
|------|--------|------|
| [daidai-panel](https://github.com/linzixuanzz/daidai-panel) | 原项目许可 | 面板后端 (Go) + 前端 (Vue3) |

#### Go 后端依赖

| 库 | 许可证 | 用途 |
|----|--------|------|
| [Gin](https://github.com/gin-gonic/gin) | MIT | HTTP 框架 |
| [GORM](https://github.com/go-gorm/gorm) | MIT | ORM |
| [glebarez/sqlite](https://github.com/glebarez/sqlite) | MIT | 纯 Go SQLite 驱动 |
| [robfig/cron](https://github.com/robfig/cron) | MIT | Cron 调度 |
| [golang-jwt](https://github.com/golang-jwt/jwt) | MIT | JWT 认证 |
| [yaml.v3](https://github.com/go-yaml/yaml) | Apache-2.0 | YAML 配置 |

#### 前端依赖

| 库 | 许可证 | 用途 |
|----|--------|------|
| [Vue 3](https://vuejs.org) | MIT | 前端框架 |
| [Element Plus](https://element-plus.org) | MIT | UI 组件库 |
| [Vite](https://vitejs.dev) | MIT | 构建工具 |
| [Monaco Editor](https://microsoft.github.io/monaco-editor/) | MIT | 代码编辑器 |
| [ECharts](https://echarts.apache.org) | Apache-2.0 | 图表库 |

#### Android 依赖

| 库 | 许可证 | 用途 |
|----|--------|------|
| AndroidX Core / AppCompat | Apache-2.0 | 兼容性支持 |
| AndroidX WebKit | Apache-2.0 | WebView 支持 |
| Kotlin Coroutines | Apache-2.0 | 异步编程 |

#### 内置运行时

| 项目 | 许可证 | 用途 |
|------|--------|------|
| [Termux packages](https://github.com/termux/termux-packages) | Apache-2.0 | Python / Node / bash 预编译包 |
| [CPython](https://www.python.org) | PSF License | Python 解释器 |
| [Node.js](https://nodejs.org) | MIT License | Node.js 运行时 |
| [GNU Bash](https://www.gnu.org/software/bash/) | GPL-3.0 | Shell 解释器 |
| [GNU Coreutils](https://www.gnu.org/software/coreutils/) | GPL-3.0 | 基础工具 |

### 致谢

- 感谢 [linzixuanzz](https://github.com/linzixuanzz) 创建了优秀的呆呆面板项目
- 感谢 [Termux](https://termux.dev) 团队为 Android 平台编译了丰富的开源软件包
- 感谢所有开源项目的贡献者

## 已知限制

- 仅支持 arm64 架构设备
- `targetSdkVersion=28`（为绕过 Android 10+ W^X 限制，不能上 Google Play）
- Termux 运行时路径硬编码问题通过环境变量重定位，某些特殊包可能有兼容性问题

## 反馈与问题

- [GitHub Issues](https://github.com/tall-1997/daidai-panel-android/issues)
- [构建记录](https://github.com/tall-1997/daidai-panel-android/actions)
