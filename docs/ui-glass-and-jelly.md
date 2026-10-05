# 液态玻璃 + 果冻动效（原生 UI 升级）

这轮把参考项目里两种手感搬进了原生界面：**ytbl 的液态玻璃分层** 和 **BouncingJelly 的边界拖拽回弹**。
两者都不是照搬代码（一个是 C++/ImGui，一个依赖 `OvershootInterpolator`），而是把思路重新落成
Android 原生控件。

---

## 一、从 ytbl 学到的：玻璃是**分层**的，不是一个灰值

ytbl 的 `GlassParams` 把一块玻璃拆成表面色 / 受光 / 折射 / 色散 / 阴影 / 鲜艳度等多个量，
分开调。原来的界面只有「纯色 + 1dp 描边」一个量，所以所有卡片看起来是同一张灰纸。

落到 Android 上不需要着色器也能拿到大部分收益，做法是把一层 shape 拆成三层 `layer-list`：

| 层 | 作用 | token |
|---|---|---|
| 表面 | 顶亮底暗的竖向渐变，给卡片刻度感 | `glass_top` → `glass_bottom` |
| 高光 | 从顶边向下渐隐的淡白，光从上方来 | `glass_sheen` |
| 描边 | 独立一层画在最后，亮边压在表面上才是"边缘折射" | `glass_stroke` |

**层次顺序不能调**：`layer-list` 后面的画在上面。描边如果混进表面 shape 里，会被高光盖住，
看着像脏了一条。

覆盖范围（改一处、全局生效）：

- `bg_card.xml` → 15 个布局、40 处卡片
- `bg_btn.xml` → 次级按钮（主按钮 `bg_btn_primary` 保持实心主色，两类按钮必须一眼分得出主次）
- `bg_chip.xml`、`bg_input.xml`
- 新增 `bg_glass_bar.xml`（底栏）/ `bg_glass_bar_header.xml`（顶栏）：受光渐变 + 一条朝内的折射亮线

顶栏和底栏原来是两条独立的 1dp 分割线 View，现在亮线画在玻璃条自己身上，
那两条 View 已删除 —— 两条线并排会看出一条"双线"，像没对齐。

### 没做的部分

ytbl 真正靠的是 AGSL 折射着色器（背景模糊 + 边缘色散）。Android 侧要等价实现需要
`RuntimeShader`（API 33+）+ 手动 backdrop 捕获，代价是每帧一次离屏重绘，而收益主要在
「一块浮在滚动内容之上的玻璃」这种结构上 —— 当前顶栏/底栏是固定的、背后没有内容穿过，
做了也看不出来。所以这轮先在颜色与层次上落地，着色器那部分留给真正的浮动面板。

---

## 二、从 BouncingJelly 学到的：边界拖拽 + 回弹

原实现的骨架是对的（滚到边界继续拖 → 缩放内容 → 松手回弹），但有三处会让手感发硬：

| 原实现 | 问题 | 这轮的改法 |
|---|---|---|
| `offsetScale = abs(dy) / (屏高 × 3)`，上限 0.3 | 线性系数：拉一点点就顶到上限，再拉没反应，是硬边 | 饱和曲线 `max × raw / (raw + falloff)`，永远还能再拉一点 |
| `OvershootInterpolator` + 固定 300ms | 曲线固定：轻拉和快甩按同一节奏弹回 | 解析弹簧，接得住松手速度 |
| `onInterceptTouchEvent` 里用 `getX()` 算垂直 slop；每帧 `Log.e` | 明显 bug 与噪音 | 未沿用该写法 |

另外原实现没有处理的：多点触控、系统「动画时长 = 0」、手指下面还有别的滚动容器。
这三条这轮都补了（见下面的取舍）。

---

## 三、从 ytbl 学到的第二件事：弹簧要**解析解**，不要逐帧积分

`ytbl_spring.h` 的 `SpringFloat` 用阻尼比 + 刚度直接算 t 秒后的状态，而不是每帧累加。
好处是掉帧时动画只是慢一点，不会因为步长变大而发散 —— 显式欧拉积分正是这样炸掉的。

`util/SpringValue.java` 是它的 Java 版，按阻尼比分三支（欠阻尼 / 临界 / 过阻尼），
内部用 double 计算后回写 float。`ui/SpringTicker.java` 负责逐帧推进并起停。

`SpringValue` 是纯逻辑（不 import 任何 Android API），按项目约定放在 `util/` 并配了单测
（`SpringValueTest`，11 个用例）。

---

## 四、按压反馈：两个轴用不同阻尼

ytbl 的 `DampedDragAnimation` 给 `scaleX`（阻尼 0.6）和 `scaleY`（阻尼 0.7）配了不同参数，
所以按下去时两个方向收缩得不一样快 —— 这是"软东西被按下去"的样子，等比缩放看着是硬的。

`ui/PressSpringAnimator.java` 用了同一手法，但**挂在 `stateListAnimator` 上，不碰触摸事件**：
按下/抬起本来就体现为 `state_pressed`，直接响应 drawable state 更省事，也不会影响点击判定、
长按和涟漪。已经被别人设置过 `stateListAnimator` 的控件（Material 按钮用它做海拔动画）一律跳过。

挂载点是两个已有的全局生命周期回调，不需要在每个页面里写：

- `ModernAndroidUi.onActivityPostCreated` → 覆盖直接 `setContentView` 的 Activity
- `MainActivity` 的 `FragmentLifecycleCallbacks.onFragmentViewCreated` → 覆盖各 Fragment

> 列表项（RecyclerView 的 item）是之后才填充的，上面两个时机都覆盖不到，
> 需要在各自的 `onBindViewHolder` 里单独调 `PressSpringAnimator.attach()`。

---

## 五、文件清单

**新增**

| 文件 | 职责 |
|---|---|
| `util/SpringValue.java` | 阻尼弹簧的解析解（纯逻辑） |
| `test/.../SpringValueTest.java` | 弹簧数学的单测 |
| `ui/SpringTicker.java` | Choreographer 逐帧驱动 + 自动停表 |
| `ui/JellyScrollView.java` | 边界拖拽果冻滚动容器 |
| `ui/PressSpringAnimator.java` | 按压缩放反馈 |
| `ui/UiAnimations.java` | 系统「动画时长」开关 |
| `res/values/glass.xml`、`res/values-night/glass.xml` | 玻璃 token（各 8 个，日夜一一对应） |
| `res/drawable/bg_glass_bar.xml`、`bg_glass_bar_header.xml` | 顶栏 / 底栏玻璃底 |

**修改**

| 文件 | 改动 |
|---|---|
| `res/drawable/bg_card.xml`、`bg_chip.xml`、`bg_btn.xml`、`bg_input.xml` | 改成多层玻璃 |
| `res/drawable/bg_device_card.xml` | 同上。它原先自己写死 `@color/card` + `@color/line`，绕过了 `drawable_aliases.xml` 的统一机制，而设备卡片与普通卡片是同页面并排的 —— 只改 `bg_card` 会立刻看出两种灰 |
| `res/layout/activity_main.xml` | 顶栏底栏改玻璃，删掉两条分割线 View |
| 15 个布局 | `<ScrollView>` → `<com.deepseekharness.app.ui.JellyScrollView>` |
| `ui/MainActivity.java` | 新增 `onFragmentViewCreated` 回调 |
| `ui/ModernAndroidUi.java` | 新增 `applyPressFeedback` |
| `ui/InstallFragment.java` | 去掉一处 `(ScrollView)` 强转（容器类已变） |

---

## 六、取舍与已知边界

- **跳过终端页**（`fragment_terminal.xml`）：终端是工具界面，输出跟随滚动频繁，
  装饰性拉伸只会干扰判断，不加。
- **跳过 WebView 错误面板**（`activity_web_preview.xml` 的 `web_error_panel`）：
  它是提示面板不是滚动容器。
- **手指落在别的滚动容器上时不启动果冻**：安装页和启动页中部的日志窗口自己会滚，
  父容器一旦抢走事件就没法补救。判据用 `canScrollVertically/Horizontally` 而不是
  「是不是 ScrollView」—— 内容没填满或已滚到头的日志窗口本来就不该拦住果冻。
  这次判断只在 `ACTION_DOWN` 做一次，不在每帧 MOVE 上走整棵树。
- **果冻是手势级的**：进入之后本次手势整段归它管（反向推只是把拉伸收回去），
  不去猜父类内部的拖拽状态是否还对得上，行为对用户可预期。
- **系统「动画时长 = 0」时全部关闭**：拉伸和按压都是纯装饰，不该跟系统设置对着干。
- **没做列表项按压反馈**：RecyclerView 的 item 需要各自在绑定处挂载，这轮未动。
- **没做弹窗玻璃**：`DshaDialogBuilder` 的卡片仍是原样，留给下一轮。

## 七、调参位置

| 想要的效果 | 改哪里 |
|---|---|
| 玻璃更亮 / 更透 | `res/values/glass.xml` 与 `values-night/glass.xml` 的 `glass_top`、`glass_bottom` |
| 卡片高光更明显 | `glass_sheen`（两边都要改） |
| 果冻拉得更长 | `JellyScrollView.DEFAULT_MAX_STRETCH`（当前 0.14） |
| 果冻更跟手 / 更沉 | `JellyScrollView.DEFAULT_FALLOFF_DP`（当前 150dp，越小越跟手） |
| 回弹更弹 / 更收敛 | `JellyScrollView.REBOUND_DAMPING`（当前 0.55，越接近 1 越不过冲） |
| 按压收缩更明显 | `PressSpringAnimator.PRESSED_SCALE`（当前 0.96） |
