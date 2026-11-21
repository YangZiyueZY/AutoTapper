<p align="center">
  <img src="./assets/readme/hero.svg" width="100%" alt="AutoTapper：在任意应用里取点、按顺序轮流点击的 Android 多点连点工具，右侧是它的悬浮控制器面板">
</p>

**AutoTapper** 是一个 Android 多点连点工具：在任意应用里轻触取点，然后按顺序轮流点击。点击由 **无障碍手势** 或 **Shizuku** 执行，二选一；面板、点位和当前进度都浮在被控应用之上。

## 先看效果

<p align="center">
  <img src="./assets/readme/screen-main.png" width="48%" alt="主界面：权限状态、点击方式、点位列表与点击配置">
  <img src="./assets/readme/screen-controller.png" width="48%" alt="悬浮控制器展开态：ADD、DEL、GO 与状态、间隔、时长、轮数、点位读数">
</p>
<p align="center">
  <img src="./assets/readme/screen-pick.png" width="48%" alt="取点浮层：轻触屏幕连续记录点位">
  <img src="./assets/readme/screen-dark.png" width="48%" alt="深色主题下的主界面">
</p>

## 它解决什么

- **多点轮询**：每个点位按顺序各点一次算一轮，当前点位用黄底徽标标出来，轮数与间隔可调。
- **两种授权方式**：无障碍服务开箱即用；Shizuku 授权后由系统 shell 注入点击，不必常开无障碍服务。
- **控制器常驻**：收起是一颗贴边悬浮球，展开是控制面板，取点、开始、停止都不离开被控应用。
- **参数可调**：基础间隔、随机附加延迟、点击时长、轮数、面板透明度，输入合法即保存。

## 怎么用

1. 到 [Releases](https://github.com/YangZiyueZY/AutoTapper/releases/latest) 下载 APK 安装（Android 7.0 及以上）。
2. 授予**悬浮窗权限**，然后在「点击方式」里选一种并完成授权：
   - **无障碍**：点「无障碍服务」右侧的去授权，开启 AutoTapper 无障碍服务；
   - **Shizuku**：先安装并启动 [Shizuku](https://shizuku.rikka.app/)，再点「Shizuku」右侧的去授权。
3. 点「打开悬浮控制器取点」，在屏幕上轻触连续取点，完成后点「完成」。
4. 展开悬浮球，按 `GO` 开始，`STOP` 停止。

> 授权不会让它自动点击：只有按 `GO` 或主界面按钮时才会执行。

## 两种点击方式

| | 无障碍 | Shizuku |
| --- | --- | --- |
| 前置条件 | 开启 AutoTapper 无障碍服务 | 安装并启动 Shizuku，授权 AutoTapper |
| 点击实现 | `dispatchGesture` 手势 | Shizuku 以 shell 身份注入触摸事件 |
| 适合 | 大多数设备，设置最少 | 已装 Shizuku；不想常开无障碍服务 |
| 注意 | 部分系统的后台手势有限制 | Shizuku 进程停止后需重新启动 |

在主界面「点击方式」一栏切换；切换时会先停止正在进行的点击，避免两套执行器同时工作。

## 参数

| 配置项 | 说明 | 默认与范围 |
| --- | --- | --- |
| 基础间隔 | 两次点击之间至少等待多久 | 100 ms，最低 100 ms |
| 随机附加延迟 | 在基础间隔上再加 0 到该值的随机等待 | 80 ms |
| 点击时长 | 每次按下到抬起持续多久 | 50 ms，10–2000 ms |
| 点击轮数 | 完整跑几轮，0 表示一直点到手动停止 | 0 |
| 悬浮窗透明度 | 控制器整体不透明度 | 100%，30%–100% |

## 悬浮控制器

| 控件 | 作用 |
| --- | --- |
| 收起 | 面板收回成贴边的悬浮球 |
| ADD | 进入取点，连续记录位置 |
| DEL | 删除最后一个点位 |
| GO / STOP | 开始 / 停止点击 |
| 关闭控制器 | 结束前台服务，移除全部悬浮窗 |

面板下半部分实时显示状态、间隔、时长、轮数与点位数；取点时面板会先淡出，取完自动回来。

## 工作原理

<p align="center">
  <img src="./assets/readme/workflow.svg" width="100%" alt="使用流程：取点、配置、开始、轮询点击，按点位顺序循环">
</p>

点击循环集中在 `TapEngine`，两种执行方式共用同一套点位顺序、间隔、轮数与状态广播：

- **无障碍**：`AccessibilityService.dispatchGesture` 在点位上发送点击手势；
- **Shizuku**：Shizuku 以 shell 身份拉起 `TapUserService`（AIDL），由它注入触摸事件；注入不可用时回退到 `input` 命令。

## 从源码构建

```bash
# 需要 JDK 17 与 Android SDK 34
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

推送到 `main` 时，GitHub Actions 会自动构建 Debug APK 并发布到 Release。

## 已知限制

- 不支持滑动与复杂动作编排。
- Shizuku 模式需要你自己启动 Shizuku server（无线调试或 adb），应用只负责授权与调用。
- 应用不做任何数据上传，`INTERNET` 权限只用于检查 GitHub 上的新版本。

## 更新日志

见 [CHANGELOG.md](./CHANGELOG.md)。

## 致谢

界面字体 [Saira](https://github.com/googlefonts/saira-font)（SIL OFL 1.1，许可证随包附在 `app/src/main/assets/licenses/`）；视觉规范参考 [endfield-ui](https://github.com/hhikr/endfield-ui-skill)。
