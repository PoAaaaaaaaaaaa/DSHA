package com.deepseekharness.app.ui;

import android.view.Choreographer;

import com.deepseekharness.app.util.SpringValue;

/**
 * 用 Choreographer 推进一组弹簧，全部静止后自动摘掉回调。
 *
 * <p>抽出来是因为「弹簧 + 逐帧驱动 + 起停管理」在每个用到弹簧的控件里都一模一样，
 * 抄两遍就会在两个地方各错一次：dt 忘了钳制、detach 时忘了摘回调、同一帧重复 post。
 * 这些错都不会崩，只会让动画偶尔抖一下或者一直在后台烧电，很难查到。
 */
final class SpringTicker {

    /** 拿不到上一帧时间（第一帧、或刚被取消过）时按 60fps 估一帧。 */
    private static final float DEFAULT_FRAME_SECONDS = 1f / 60f;

    private final SpringValue[] springs;
    private final Runnable apply;

    private boolean posted;
    private float lastFrameSeconds;

    private final Choreographer.FrameCallback stepper = new Choreographer.FrameCallback() {
        @Override public void doFrame(long frameTimeNanos) {
            posted = false;
            float now = frameTimeNanos / 1_000_000_000f;
            float dt = lastFrameSeconds == 0f ? DEFAULT_FRAME_SECONDS : now - lastFrameSeconds;
            lastFrameSeconds = now;

            boolean moving = false;
            for (SpringValue spring : springs) {
                // 刻意不用 ||= / 短路写法：短路会让第一个还在动的弹簧把后面的步进整个跳过，
                // 表现是「只有 X 轴在动，Y 轴不动」。
                boolean stillMoving = spring.step(dt);
                moving = moving || stillMoving;
            }
            apply.run();
            if (moving) {
                post();
            } else {
                lastFrameSeconds = 0f;
            }
        }
    };

    /**
     * @param apply   每帧把弹簧当前值写回 View；弹簧已经步进完了才会被调用
     * @param springs 一起推进的弹簧，任何一个还在动就继续下一帧
     */
    SpringTicker(Runnable apply, SpringValue... springs) {
        this.apply = apply;
        this.springs = springs;
    }

    /** 请求下一帧；已经在跑就什么都不做（重复 post 会让一帧内推进多次）。 */
    void request() {
        if (posted) return;
        posted = true;
        Choreographer.getInstance().postFrameCallback(stepper);
    }

    /** 摘掉回调并复位计时：View 被移除或效果被关闭时必须调用。 */
    void cancel() {
        if (posted) {
            Choreographer.getInstance().removeFrameCallback(stepper);
            posted = false;
        }
        lastFrameSeconds = 0f;
    }
}
