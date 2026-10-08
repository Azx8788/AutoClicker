# 自动点击器 (AutoClicker)

[![build](https://github.com/Azx8788/AutoClicker/actions/workflows/build-debug.yml/badge.svg)](https://github.com/Azx8788/AutoClicker/actions/workflows/build-debug.yml)

一款轻量的 Android 自动点击器：基于无障碍服务（AccessibilityService）的手势注入实现，**无需 Root**。支持多点独立连点、拖动定位、悬浮窗控制与 Shizuku 一键授权。

## 特性

- 🎯 **拖动即定位**：添加"点击器"后是屏幕上一个带编号的圆点，**拖到哪就点哪**，所见即所得
- 🧩 **多点独立**：每个点击器独立设置 间隔 / 时长 / 次数（次数填 0 = 不限次数），各点互不干扰
- 🎨 **状态一眼看清**：圆点颜色实时反馈 —— 🔵 蓝 = 待机（已启用未运行）、🟢 绿 = 正在点击、⚪ 灰 = 已停用
- ⏸️ **启用 / 停用（多选）**：临时关闭部分点击器，**保留位置与参数**，随时恢复；删除、批量改参数同样支持多选
- 🪟 **悬浮窗控制条**：▶ 开始/停止、＋ 添加、－ 减少、⚙ 设置、☰ 主界面、✕ 关闭；**竖排 / 横排 / 极简** 三种排列；大小 60%~150%、透明度均可调；悬浮位置记忆
- 🛑 **四层紧急停止**（高速连点时的"保命"设计）：
  - 按【音量减键】立即停止 —— 物理按键通道，最可靠
  - 点击 / 触摸屏幕立即停止（可在设置中关闭，避免误触发）
  - 按电源键息屏，自动停止
  - 下拉通知栏点「停止连点」
- ⚡ **Shizuku 一键授权**：一次点击自动开启 **无障碍 + 悬浮窗** 两项权限；采用追加方式开启，**不覆盖其他无障碍服务**（如 GKD）
- 🔧 **全面的大小调节**：点击器圆点 28~80dp、悬浮窗 60%~150%、透明度 10%~100%

## 环境要求

| 项 | 要求 |
|---|---|
| 系统 | Android 7.0+（API 24） |
| 权限 | 无障碍服务（必需，用于注入点击）、悬浮窗（使用悬浮窗时必需） |
| Shizuku | 可选。安装并启动后可一键授权，省去手动操作 |

## 安装

1. 从本仓库 [Releases](../../releases) 下载最新 APK
2. 允许安装未知来源应用后安装
3. 后续版本可直接**覆盖安装**升级（APK 使用固定签名）

> 本项目定位为个人实用工具，发布管道产出的是固定签名的 debug 构建，便于直接安装与升级。

## 使用指南

1. 打开 App，点【⚡ Shizuku 一键授权】（未安装 Shizuku 则手动授予悬浮窗 + 无障碍权限）
2. 点【＋ 添加点击器】，屏幕上出现编号圆点，把它**拖到要点击的位置**
3. 点【⚙ 参数】调整每个（或批量多选）点击器的 间隔 / 时长 / 次数
4. 点【显示悬浮窗】调出控制条，按 ▶ 开始连点，按 ■ 停止
5. 需要在本次运行中临时关掉某些点击器时，用【● 启用/停用】勾选取消

## 常见问题

**点了开始没反应？**
检查无障碍服务是否开启 —— 应用内状态栏会显示「无障碍服务：已连接 ✓ / 未开启」。

**连点太快停不下来？**
按【音量减键】。手势注入会取消屏幕上的触摸操作，可能导致悬浮窗按钮短暂失灵，而音量键走系统按键通道，不受影响。

**快速连点时点击屏幕会"误停"？**
设置里可关闭「点击屏幕立即停止连点」；音量键、触碰悬浮窗、息屏、通知栏四个停止通道依然有效。

**会影响其他无障碍服务（如 GKD）吗？**
不会。授权采用追加方式写入 `enabled_accessibility_services`，不会覆盖已有服务。

**应用被杀后台？**
系统可能回收悬浮窗服务，可在系统设置中允许自启动 / 后台运行。

## 工作原理（技术细节）

- **点击注入**：`AccessibilityService.dispatchGesture()`（API 24+）。单队列串行派发 + 40ms 沉降间隔 + 有界队列背压，避免高速连点时的伪取消误判与队列积压漂移
- **悬浮窗**：`TYPE_APPLICATION_OVERLAY` 窗口；运行时点击器圆点自动切换 `FLAG_NOT_TOUCHABLE`，让注入的点击"穿透"到下层应用
- **一键授权**：通过 Shizuku（shell 身份）执行 `pm grant` / `appops set` / `settings put`，把 无障碍 + 悬浮窗 + WRITE_SECURE_SETTINGS 一次配齐
- **配置持久化**：SharedPreferences（JSON），点击器位置、参数、开关全部记忆
- **发布**：GitHub Actions 云端构建（推送 `main` 或 `v*` 标签），CI 内置 apksigner 签名比对校验

技术栈：Kotlin · AGP 8.10.1 · minSdk 24 / targetSdk 35 · AndroidX + Material 3 + Shizuku API

## 从源码构建

```bash
./gradlew :app:assembleDebug
```

产物位于 `app/build/outputs/apk/debug/`。也可以直接使用仓库自带的 GitHub Actions（推送分支或 `v*` 标签自动构建，见 Actions 页面；打标签会自动创建 Release 并附带 APK）。

## 免责声明

本项目仅供学习研究与个人自动化使用。请遵守您所使用应用的服务条款，勿用于破坏公平性或任何违规用途。

## English

A lightweight Android auto-clicker built on `AccessibilityService` gesture injection (no root). Add numbered clickers, drag them to the target positions, then hit start. Features: multi-point independent clicking (interval / press duration / repeat count), enable/disable with multi-select, live status colors, a compact floating control panel with 3 layouts and adjustable size/opacity, and four emergency-stop channels (volume-down key, screen touch, screen-off, notification action). Shizuku one-click permission grant supported. Download the APK from [Releases](../../releases).
