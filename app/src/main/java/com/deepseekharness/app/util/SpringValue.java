package com.deepseekharness.app.util;

/**
 * 阻尼弹簧的解析解：给定当前位置、速度与目标值，直接算出 dt 秒之后的状态。
 *
 * <p>为什么不用 {@code ValueAnimator} + 插值器：插值器只描述「从 A 到 B 的形状」，
 * 一旦动画被打断（手指又按下去、目标值中途改了）就只能重头再来，看起来是「跳一下再走」。
 * 弹簧带真实的速度状态，被打断时从当前速度和位置继续，目标值可以每帧改，轨迹始终连续。
 * 欠阻尼（{@code dampingRatio < 1}）在终点附近来回过冲，就是界面上的「果冻」手感。
 *
 * <p>同样重要的是：解析解对帧长不敏感。掉帧时动画只是慢一点，不会因为积分步长变大而发散
 * —— 显式欧拉积分在 {@code dt} 抖动时正是这样炸掉的。
 *
 * <p>本类不持有任何 Android 对象，调用方负责每帧把帧间隔传进 {@link #step(float)}。
 */
public final class SpringValue {

    /** 单帧最大推进步长：超过这个值的 dt 说明刚从后台回来，按 4 帧算，避免一帧跳完整个动画。 */
    private static final float MAX_STEP_SECONDS = 0.064f;

    /**
     * 静止判定的速度系数：速度低于「每帧位移不超过静止阈值」时才算停下。
     * 只看位移不看速度会让弹簧在终点附近被过早掐断，过冲的尾巴就没了。
     */
    private static final float REST_VELOCITY_FACTOR = 62.5f;

    /** 明显过冲的轻柔弹簧，用于按压回弹、果冻拉伸这类需要「弹」的地方。 */
    public static final float DAMPING_GENTLE = 0.62f;

    /** 轻微过冲，用于页面切换、面板展开这类需要「利落但不生硬」的地方。 */
    public static final float DAMPING_STANDARD = 0.85f;

    /** 临界阻尼，不过冲；用于跟随手指、位置吸附这类必须精确落位的地方。 */
    public static final float DAMPING_CRITICAL = 1.0f;

    private float value;
    private float velocity;
    private float target;
    private final float dampingRatio;
    private final float stiffness;
    private final float restThreshold;

    /**
     * @param initial       初始位置
     * @param dampingRatio  阻尼比：小于 1 过冲（果冻），等于 1 临界阻尼，大于 1 迟滞
     * @param stiffness     刚度：越大越快，单位是 1/s²，常用 200–1500
     * @param restThreshold 静止阈值：位移小于它且速度足够低就吸附到目标并停机
     */
    public SpringValue(float initial, float dampingRatio, float stiffness, float restThreshold) {
        this.value = initial;
        this.target = initial;
        this.velocity = 0f;
        this.dampingRatio = dampingRatio > 0f ? dampingRatio : DAMPING_CRITICAL;
        this.stiffness = stiffness > 0f ? stiffness : 1f;
        this.restThreshold = restThreshold > 1e-6f ? restThreshold : 1e-6f;
    }

    /** 常用组合：给定阻尼比和刚度，静止阈值按位移的千分之一取。 */
    public static SpringValue of(float initial, float dampingRatio, float stiffness) {
        return new SpringValue(initial, dampingRatio, stiffness, 0.001f);
    }

    public float value() {
        return value;
    }

    public float velocity() {
        return velocity;
    }

    public float target() {
        return target;
    }

    /** 立刻落位，丢掉速度：用于首次布局、配置变更重建这类不该有动画的场合。 */
    public void snapTo(float next) {
        value = next;
        target = next;
        velocity = 0f;
    }

    /** 设定新目标；从当前速度和位置继续运动。 */
    public void animateTo(float next) {
        target = next;
    }

    /** 带上初速度地改变目标：松手时把手指速度交给弹簧，回弹才「接得住」。 */
    public void animateTo(float next, float initialVelocity) {
        target = next;
        velocity = initialVelocity;
    }

    /** 已经停在目标上。 */
    public boolean isSettled() {
        return value == target && velocity == 0f;
    }

    /**
     * 推进一帧。
     *
     * @param dtSeconds 距上一帧的秒数
     * @return 仍在运动（调用方据此决定是否继续请求下一帧）
     */
    public boolean step(float dtSeconds) {
        if (!(dtSeconds > 0f)) return !isSettled();
        float dt = Math.min(dtSeconds, MAX_STEP_SECONDS);

        double omega0 = Math.sqrt(stiffness);
        double zeta = dampingRatio;
        double y0 = value - target;
        double v0 = velocity;
        double y;
        double v;

        if (zeta < 1.0 - 1e-4) {
            // 欠阻尼：指数衰减包络 × 阻尼振荡。
            double omegaD = omega0 * Math.sqrt(1.0 - zeta * zeta);
            double envelope = Math.exp(-zeta * omega0 * dt);
            double c = Math.cos(omegaD * dt);
            double s = Math.sin(omegaD * dt);
            double b = (v0 + zeta * omega0 * y0) / omegaD;
            double inner = y0 * c + b * s;
            y = envelope * inner;
            v = envelope * (-zeta * omega0 * inner + (-y0 * omegaD * s + b * omegaD * c));
        } else if (zeta > 1.0 + 1e-4) {
            // 过阻尼：两个实指数项，不会过冲。
            double s = Math.sqrt(zeta * zeta - 1.0);
            double r1 = -omega0 * (zeta - s);
            double r2 = -omega0 * (zeta + s);
            double c1 = (v0 - r2 * y0) / (r1 - r2);
            double c2 = y0 - c1;
            double e1 = Math.exp(r1 * dt);
            double e2 = Math.exp(r2 * dt);
            y = c1 * e1 + c2 * e2;
            v = r1 * c1 * e1 + r2 * c2 * e2;
        } else {
            // 临界阻尼：重根，出现 t 的一次项。
            double envelope = Math.exp(-omega0 * dt);
            double c = v0 + omega0 * y0;
            y = envelope * (y0 + c * dt);
            v = envelope * (v0 - omega0 * c * dt);
        }

        value = target + (float) y;
        velocity = (float) v;
        if (!finite(value) || !finite(velocity) || !finite(target)) {
            // 参数被外部写成 NaN/Infinity 时不能让脏值一直传下去，否则整个界面画不出来。
            target = finite(target) ? target : 0f;
            value = target;
            velocity = 0f;
        }

        // 收敛：位移进阈值且速度低到「一帧走不出阈值」，才吸附；否则过冲尾巴会被切掉。
        if (Math.abs(value - target) <= restThreshold
                && Math.abs(velocity) <= restThreshold * REST_VELOCITY_FACTOR) {
            value = target;
            velocity = 0f;
        }
        return !isSettled();
    }

    /** 目标区间内归一化后的进度，用于把弹簧位置映射成 0–1 的视觉量（缩放 / 透明度）。 */
    public static float fraction(float from, float to, float value) {
        float span = to - from;
        return span == 0f ? 0f : clamp01((value - from) / span);
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    /**
     * 不用 {@code Float.isFinite}：它是 API 24 才加进去的静态方法，而兼容版 minSdk 是 23。
     * 用 API 1 就存在的两个判断拼出同样的语义，代价只是一次取反。
     */
    private static boolean finite(float v) {
        return !Float.isNaN(v) && !Float.isInfinite(v);
    }
}
