package com.deepseekharness.app.ui;

import android.animation.StateListAnimator;
import android.view.View;
import android.view.ViewGroup;

import com.deepseekharness.app.util.SpringValue;

/**
 * 按压缩放反馈：手指按下时控件轻微收缩，两个轴刻意用不同阻尼，收缩过程中带一点不同步的
 * 「挤压」感 —— 这是一块软东西被按下去的样子。等比缩放看着是硬的，因为真实物体受力时
 * 两个方向的形变从来不一样快。
 *
 * <p>挂在 {@code stateListAnimator} 上，<b>不碰触摸事件</b>：用 OnTouchListener 去拦截会给
 * 每个控件加一层事件转发，还得小心别把原来的监听器覆盖掉；而按下 / 抬起本来就体现为
 * {@code state_pressed}，直接响应 drawable state 更省事，也不会影响点击判定、长按、涟漪。
 *
 * <p>已经被别人设置过 stateListAnimator 的控件一律跳过：Material 按钮用它做海拔动画，
 * 抢过来会让按钮"按下去不抬起来"。
 */
public final class PressSpringAnimator extends StateListAnimator {

    /** 按下去的收缩比例。再大就会让相邻元素看起来在错位。 */
    private static final float PRESSED_SCALE = 0.96f;

    /** 两轴不同阻尼：X 收得比 Y 急一点，才有"挤"的感觉。 */
    private static final float DAMPING_X = 0.60f;

    private static final float DAMPING_Y = 0.70f;

    private static final float STIFFNESS = 520f;

    private static final float REST = 0.0008f;

    private final View target;
    private final SpringValue scaleX = new SpringValue(1f, DAMPING_X, STIFFNESS, REST);
    private final SpringValue scaleY = new SpringValue(1f, DAMPING_Y, STIFFNESS, REST);
    private final SpringTicker ticker = new SpringTicker(this::apply, scaleX, scaleY);

    private PressSpringAnimator(View target) {
        this.target = target;
        target.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View view) {
            }

            @Override public void onViewDetachedFromWindow(View view) {
                // 视图被移除时必须停表并落位：Choreographer 的回调会一直拽着 target 不放。
                settle();
            }
        });
    }

    /** 给单个可点击控件挂上；不满足条件的直接返回，调用方不需要先判断。 */
    public static void attach(View view) {
        if (view == null || !view.isClickable()) return;
        if (view.getStateListAnimator() != null) return;
        if (!UiAnimations.enabled(view.getContext())) return;
        view.setStateListAnimator(new PressSpringAnimator(view));
    }

    /**
     * 递归给整棵子树里的可点击控件挂上。
     *
     * <p>调用时机是「视图刚创建完」，此时子树很小；RecyclerView 的 item 是之后才填充的，
     * 所以列表项需要在各自的绑定处单独调用 {@link #attach(View)}。
     */
    public static void applyTo(View root) {
        if (root == null) return;
        attach(root);
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                applyTo(group.getChildAt(i));
            }
        }
    }

    @Override
    public void setState(int[] state) {
        boolean pressed = false;
        boolean enabled = true;
        for (int value : state) {
            if (value == android.R.attr.state_pressed) pressed = true;
            else if (value == -android.R.attr.state_enabled) enabled = false;
        }
        float goal = pressed && enabled ? PRESSED_SCALE : 1f;
        // drawable state 会因为聚焦、悬停、激活等一堆原因变化，目标没变就别重启弹簧。
        if (scaleX.target() == goal && scaleY.target() == goal) return;
        // 按下那一刻才读系统设置：drawable state 的变化非常频繁，每次都读会拖慢主线程。
        // 放在这里也顺带解决了「用户中途把动画关掉 / 打开」—— 下次按下就跟上了。
        if (pressed && enabled && !UiAnimations.enabled(target.getContext())) return;
        scaleX.animateTo(goal);
        scaleY.animateTo(goal);
        ticker.request();
    }

    @Override
    public void jumpToCurrentState() {
        // 列表快速滚动、页面切换时系统会让动画直接落位。弹簧没有"跳到终点"的语义，
        // 这里必须落到目标值并停表，否则会留下一个半缩着的控件。
        settle();
    }

    private void settle() {
        ticker.cancel();
        scaleX.snapTo(scaleX.target());
        scaleY.snapTo(scaleY.target());
        apply();
    }

    private void apply() {
        target.setScaleX(scaleX.value());
        target.setScaleY(scaleY.value());
    }
}
