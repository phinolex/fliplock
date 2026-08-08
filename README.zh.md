# FlipLock — 合上无磁铁翻盖保护套时自动锁屏

**你的皮套 / 翻盖 / 钱包式保护套合上时屏幕不会熄灭，因为它里面没有磁铁。FlipLock 解决这个问题。**
合上翻盖 → 手机锁屏。打开翻盖 → 屏幕重新亮起。

无需磁铁，无需 root，无需 ADB，无需 Shizuku，无需服务器，无需联网。

**[⬇ 下载 APK](../../releases/latest)** · [FAQ（英文）](docs/FAQ.md)

**语言：** [English](README.md) · [Français](README.fr.md) · 简体中文 · [한국어](README.ko.md) · [日本語](README.ja.md)

<p align="center">
  <img src="docs/flow.svg" alt="合上锁屏，打开亮屏" width="100%">
</p>

## 你遇到的是这个问题吗？

你买了非原厂的翻盖保护套，然后发现：

- 合上翻盖**屏幕不会熄灭** —— 手机在包里一直亮着
- 打开翻盖**不会自动亮屏**
- 三星的「智能保护套」/「Cover screen」设置毫无作用，或者根本找不到这个选项
- 手机在口袋里发烫、掉电快，因为屏幕从来没关过
- 找到的应用只提供「双击息屏」，或者要求 root / ADB / Shizuku
- 「口袋模式」类应用**只要环境一变暗就锁屏**，反而更糟

**原因在这里。** 原厂翻盖保护套里藏着一块**磁铁**，手机内部有**霍尔传感器**负责感应它。整个机制就是这样。
第三方保护套几乎都没有这块磁铁，所以 Android 根本无从得知盖子合上了 —— 没有信号可以检测。
任何设置都修不好，因为硬件信号本身不存在。

**FlipLock 换了一条路。** 它监视手机正面的**环境光传感器**。不透光的翻盖盖下来时，光照度会骤降。
FlipLock 寻找的是这个**事件** —— 一次又快、又深、又持续的下降 —— 而不是某个固定的 lux 数值。
这个区别就是全部的关键：黑暗的房间、掠过的手、日落、走进室内，最终的照度都一样低，但它们都不会锁屏。

<p align="center">
  <img src="docs/how-it-works.svg" alt="固定阈值无法区分合盖与房间变暗，但事件可以" width="100%">
</p>

## 界面

| 主界面 | 传感器诊断 | 高级设置 |
|:---:|:---:|:---:|
| <img src="docs/screenshots/home.png" width="240"> | <img src="docs/screenshots/diagnostics.png" width="240"> | <img src="docs/screenshots/advanced.png" width="240"> |

下面这张才是最关键的 —— 引擎**拒绝**锁屏的时候：

<p align="center">
  <img src="docs/screenshots/rejected.png" width="270">
</p>

光照度确实降到了 **0.0 lux**，下降幅度确实是 **100 %**。FlipLock 依然拒绝了：
`drop too gradual`（下降太缓慢，不是合盖）。因为上一次明亮读数是 900 毫秒之前的事，
这是房间变暗，不是翻盖落下。正是这一个判断，让你的手机不会整天自己锁屏。

## 判定条件

锁屏需要**同时**满足以下全部条件：

| 条件 | 作用 |
|---|---|
| `lux ≤ 合盖阈值` | 已经足够暗 |
| 上一次明亮读数在 900 毫秒以内 | **速度** —— 缓慢变暗的房间会被排除 |
| 基准值 ≥ 最小可用基准值，且至少 2 个采样 | **黑暗房间** —— 仅凭光线无法判断时拒绝动作 |
| 绝对下降 ≥ 5 lux | 绝对幅度 |
| 相对下降 ≥ 85 % | 相对幅度 |
| 持续 300 毫秒 | **时长** —— 排除掠过的手和阴影 |
| 锁屏后冷却 1500 毫秒 | 避免连续触发 |
| `PowerManager.isInteractive` | 屏幕已灭时绝不重复动作 |

## 安装

1. 在手机上打开 APK 并安装。若系统询问，请**仅对此应用**允许来源。
2. 打开 FlipLock，点「启用权限」，开启无障碍服务。
3. 进入**传感器诊断**，合上翻盖，确认 lux 数值会变化。
4. 点**校准我的保护套**，然后**应用这些设置**。
5. 打开 FlipLock 开关。

**三星 One UI。** 如果出现「受限设置」，请用主界面的**打开「应用信息」**按钮，
然后点右上角 ⋮ →「允许受限设置」。不要全局关闭 Auto Blocker。

另外请到*设置 → 电池 → 后台使用限制 → 永不休眠的应用*里加入 FlipLock，
否则 One UI 可能在几天后终止服务。

## 隐私

APK 中声明的权限（可用 `aapt2 dump permissions` 自行核实）：

```
FOREGROUND_SERVICE, FOREGROUND_SERVICE_SPECIAL_USE, POST_NOTIFICATIONS, WAKE_LOCK
```

前三项只服务于可选的常驻服务，`WAKE_LOCK` 只服务于可选的「打开时唤醒」。

**没有**网络、摄像头、麦克风、通讯录、短信、电话、位置、文件、账户、蓝牙权限。
没有统计分析，没有 Firebase，没有遥测，没有服务器，没有云备份。
清单里根本没有 `INTERNET` 权限，因此这个应用在物理上无法发起任何网络请求。

无障碍服务被限制到极致：`accessibilityEventTypes` **未声明**（默认为 0，因此收不到任何无障碍事件），
`canRetrieveWindowContent="false"`。它只调用一个系统 API：`performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)`。

## 已知限制

- **全黑环境下不工作。** 完全黑暗时，合盖与不合盖都是 0 lux，没有可用信息。FlipLock 宁可不动作，也不猜。
- 如果你的距离传感器对翻盖没有反应，混合模式帮不上忙。校准会明确告诉你结果。
- **不会上架 Google Play。** 谷歌禁止将无障碍 API 用于非无障碍用途。请从 Releases 安装，或使用 Obtainium 跟踪更新。

## 反馈问题

进入**传感器诊断** → **实测全部传感器** → **复制诊断信息**，粘贴到
[新建 issue](../../issues/new)。报告包含机型、Android 版本、全部传感器的实测结果和测量数值，
不含任何个人数据。

---

MIT 许可证。完整技术文档见 [English README](README.md)。
