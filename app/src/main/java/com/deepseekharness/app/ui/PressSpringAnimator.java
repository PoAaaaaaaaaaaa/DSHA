package com.deepseekharness.app.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.StateListAnimator;
import android.animation.ValueAnimator;
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
 * <p><b>为什么用 addState 而不是覆写 setState</b>：{@code StateListAnimator.setState(int[])}
 * 在 AOSP 里标了 {@code @hide}，SDK 的 android.jar 里根本没有它，子类写 {@code @Override}
 * 直接编译失败。能用的公开入口只有 {@link #addState(int[], Animator)}，所以这里挂两个
 * 占位 Animator —— 它们不负责插值（1ms 就走完），唯一作用是在状态切换时被启动一下，
 * 借那一刻把弹簧指向新目标，之后的形变完全由弹簧自己跑。
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
        // 顺序即优先级：setState 取第一个匹配上的 spec，所以「按下」必须排在默认项前面。
        // 空 spec 匹配任何状态，当兜底用。
        addState(new int[]{android.R.attr.state_pressed}, starter(PRESSED_SCALE));
        addState(new int[]{}, starter(1f));
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

    /**
     * 造一个「状态切换信号器」：它自己不做任何视觉变化，只借 onAnimationStart 那一帧
     * 把弹簧推向目标。StateListAnimator 需要它来驱动状态匹配，弹簧则独立跑自己的帧。
     */
    private Animator starter(float goal) {
        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(1);
        animator.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationStart(Animator animation) {
                // 按下那一刻才读系统设置：drawable state 的变化非常频繁，每次都读会拖慢主线程。
                // 放在这里也顺带解决了「用户中途把动画关掉 / 打开」—— 下次按下就跟上了。
                if (goal < 1f && !UiAnimations.enabled(target.getContext())) return;
                if (scaleX.target() == goal && scaleY.target() == goal) return;
                scaleX.animateTo(goal);
                scaleY.animateTo(goal);
                ticker.request();
            }
        });
        return animator;
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
