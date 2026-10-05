package com.deepseekharness.app.ui;

import android.content.Context;
import android.provider.Settings;

/**
 * 系统「动画时长」开关（开发者选项、省电模式会把属性动画时长设成 0）。
 *
 * <p>自绘的弹簧动画不受系统直接管，所以必须自己问一次：用户明确关掉了动画，
 * 我们再弹回去就是对着干。读设置失败按「开」处理 —— 读不到不等于用户想关。
 *
 * <p>这里看的是 {@code ANIMATOR_DURATION_SCALE}（属性动画那一档），与
 * {@link UiMotion} 看的 {@code TRANSITION_ANIMATION_SCALE}（窗口转场那一档）
 * 是系统里两个独立开关，各自管各自的东西，别合并。
 */
final class UiAnimations {

    private UiAnimations() {
    }

    static boolean enabled(Context context) {
        try {
            return Settings.Global.getFloat(context.getContentResolver(),
                    Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f;
        } catch (RuntimeException ignored) {
            return true;
        }
    }
}
