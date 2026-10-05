package com.deepseekharness.app.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;

import androidx.annotation.Nullable;
import androidx.core.widget.NestedScrollView;

import com.deepseekharness.app.util.SpringValue;

/**
 * 滚到头还能继续拖的「果冻」滚动容器。
 *
 * <p>在顶部继续下拉、在底部继续上拉时，内容不是被硬生生卡住，而是整体被拉长：位移越大越沉，
 * 松手后由弹簧弹回原位，弹簧在终点附近轻微过冲 —— 果冻感就来自这里。
 *
 * <p>三条取舍：
 * <ul>
 *   <li><b>只在边界生效</b>：内容还能滚的时候完全不介入，正常滚动的惯性和 fling 一点不受影响。</li>
 *   <li><b>拉伸量饱和</b>：把线性系数换成 {@code max * raw / (raw + falloff)}。线性版本拉一点点
 *       就顶到上限，再拉没反应，手感是硬的；饱和曲线永远还能再拉一点。</li>
 *   <li><b>回弹交给弹簧</b>：{@code OvershootInterpolator} 是固定时长的曲线，轻拉和快甩按同一节奏
 *       弹回；弹簧接得住松手速度，两者手感不同。</li>
 * </ul>
 *
 * <p>进入果冻后本次手势整段归本容器管（反向推只是把拉伸收回去），这样不必去猜父类内部的
 * 拖拽状态是否还对得上，行为对用户也是可预期的。
 *
 * <p>系统「动画时长」为 0 时（开发者选项、省电模式）整个效果自动关闭，退化成普通 ScrollView。
 */
public class JellyScrollView extends NestedScrollView {

    /** 最大拉伸比例：14% 已经很明显，再大文字就会看出变形。 */
    private static final float DEFAULT_MAX_STRETCH = 0.14f;

    /** 拉到「一半最大拉伸」所需的距离（dp）：越小越跟手，越大越沉。 */
    private static final float DEFAULT_FALLOFF_DP = 150f;

    /** 果冻回弹：明显欠阻尼，终点附近过一次冲再停。 */
    private static final float REBOUND_DAMPING = 0.55f;

    private static final float REBOUND_STIFFNESS = 260f;

    private static final float REBOUND_REST = 0.0004f;

    private final SpringValue stretch =
            new SpringValue(0f, REBOUND_DAMPING, REBOUND_STIFFNESS, REBOUND_REST);

    private final SpringTicker ticker = new SpringTicker(this::applyStretch, stretch);

    private final float maxStretch;
    private final float falloffPx;
    private final float touchSlop;
    private final float maxFlingVelocity;

    private VelocityTracker velocityTracker;
    private float pullOriginY;
    private boolean jellyActive;
    private boolean pullingTop;
    private boolean nestedScrollerAtDown;
    private boolean animatorEnabled = true;

    public JellyScrollView(Context context) {
        this(context, null);
    }

    public JellyScrollView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public JellyScrollView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        float density = getResources().getDisplayMetrics().density;
        maxStretch = DEFAULT_MAX_STRETCH;
        falloffPx = DEFAULT_FALLOFF_DP * density;
        ViewConfiguration configuration = ViewConfiguration.get(context);
        touchSlop = configuration.getScaledTouchSlop();
        maxFlingVelocity = configuration.getScaledMaximumFlingVelocity();
        animatorEnabled = UiAnimations.enabled(context);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            // 每个手势开头读一次系统设置。View 会被复用（Fragment 换页后仍留在树里），
            // 只在构造时读一次的话，用户后来改了开发者选项里的开关也不会生效。
            animatorEnabled = UiAnimations.enabled(getContext());
            if (animatorEnabled) {
                startTracking(event);
                pullOriginY = event.getY();
                jellyActive = false;
                // 同一时刻只查一次：手指落在哪块区域在按下那一刻就定了，
                // 而 MOVE 每帧都查等于每帧走一遍整棵子树。
                nestedScrollerAtDown = overScrollableChild(event.getX(), event.getY());
            }
            return super.dispatchTouchEvent(event);
        }
        if (!animatorEnabled) return super.dispatchTouchEvent(event);

        switch (action) {
            case MotionEvent.ACTION_MOVE:
                if (trackVelocity(event)) {
                    if (jellyActive) {
                        float raw = pulledDistance(event.getY());
                        setStretchInstant(raw > 0f ? damped(raw) : 0f);
                        return true;
                    }
                    if (shouldStartJelly(event)) {
                        jellyActive = true;
                        // 从「刚越过阈值」的位置重新起算，拉伸从 0 开始，不会先跳一下。
                        pullOriginY = event.getY();
                        return true;
                    }
                }
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                // 多指手势交给父类判断意图（缩放、父容器横滑），果冻只服务单指拖拽。
                if (jellyActive) release();
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (jellyActive) {
                    // 顺序不能反：release 要读松手速度来带动回弹，而 stopTracking 会把
                    // VelocityTracker 回收掉 —— 先 stop 再 release 的话 fling 永远是 0，
                    // 「接住速度弹回去」就静默失效了（不报错，只是手感变木）。
                    release();
                    stopTracking();
                    return true;
                }
                stopTracking();
                break;
            default:
                break;
        }
        return super.dispatchTouchEvent(event);
    }

    /** 当前手指相对起点的「向外」位移：下拉为正、上拉为正，往回推为负。 */
    private float pulledDistance(float currentY) {
        return pullingTop ? currentY - pullOriginY : pullOriginY - currentY;
    }

    /**
     * 是否该进入果冻：必须在滚动边界，且这一下的方向是继续往外拉。
     * 内容还能滚的时候一概不碰，否则会把正常滚动的第一帧吃掉。
     */
    private boolean shouldStartJelly(MotionEvent event) {
        float dy = event.getY() - pullOriginY;
        if (Math.abs(dy) < touchSlop) return false;
        // 手指下面若有「自己就能滚」的子容器（安装页和启动页中部的日志窗口），
        // 这一下多半是冲它去的：父容器一旦把事件抢走就没有补救办法，
        // 所以宁可少一次果冻，也不能让日志拖不动。
        if (nestedScrollerAtDown) return false;
        if (dy > 0f && !canScrollVertically(-1)) {
            pullingTop = true;
            return true;
        }
        if (dy < 0f && !canScrollVertically(1)) {
            pullingTop = false;
            return true;
        }
        return false;
    }

    /** 按下点是否落在某个「自己就能滚」的后代上。 */
    private boolean overScrollableChild(float x, float y) {
        View content = getChildAt(0);
        if (content == null) return false;
        // 事件坐标是相对本容器的，先换算进内容层的坐标系（本容器自己的滚动量要加回去）。
        return hitsScrollable(content,
                x + getScrollX() - content.getLeft(),
                y + getScrollY() - content.getTop());
    }

    /**
     * 递归命中测试：找到触摸点下面第一个「自己就能滚」的 View。
     *
     * <p>判据用 {@code canScrollVertically/Horizontally} 而不是「是不是 ScrollView」：
     * 内容还没填满、或者已经滚到头的日志窗口本来就不该拦住果冻，硬按类型判断会把
     * 这两种情况一起挡掉。
     */
    private static boolean hitsScrollable(View view, float x, float y) {
        if (x < 0f || y < 0f || x >= view.getWidth() || y >= view.getHeight()) return false;
        if (view.canScrollVertically(1) || view.canScrollVertically(-1)
                || view.canScrollHorizontally(1) || view.canScrollHorizontally(-1)) return true;
        if (!(view instanceof android.view.ViewGroup)) return false;
        android.view.ViewGroup group = (android.view.ViewGroup) view;
        for (int i = group.getChildCount() - 1; i >= 0; i--) {
            View child = group.getChildAt(i);
            if (child.getVisibility() != VISIBLE) continue;
            if (hitsScrollable(child,
                    x - child.getLeft() + group.getScrollX(),
                    y - child.getTop() + group.getScrollY())) return true;
        }
        return false;
    }

    /** 饱和阻尼：越拉越沉，但永远还能再拉一点，不会有顶死的硬边。 */
    private float damped(float rawPx) {
        return maxStretch * rawPx / (rawPx + falloffPx);
    }

    private void release() {
        jellyActive = false;
        float fling = 0f;
        if (velocityTracker != null) {
            velocityTracker.computeCurrentVelocity(1000, maxFlingVelocity);
            float py = pullingTop ? velocityTracker.getYVelocity() : -velocityTracker.getYVelocity();
            // 位移→拉伸的斜率：把手指速度换算成拉伸比例的速度，松手才不会「一顿」。
            fling = py * (maxStretch / falloffPx);
        }
        stretch.animateTo(0f, fling);
        ticker.request();
    }

    /** 手指拖动期间直接落位（不经过弹簧），保证跟手。 */
    private void setStretchInstant(float value) {
        stretch.snapTo(value);
        applyStretch();
    }

    private void applyStretch() {
        View content = getChildAt(0);
        if (content == null) return;
        float value = stretch.value();
        if (value <= 0f) {
            content.setScaleX(1f);
            content.setScaleY(1f);
            return;
        }
        // pivot 放在被拉的那条边上：顶部下拉时顶边固定向下长，底部上拉时底边固定向上长。
        content.setPivotX(getWidth() / 2f);
        content.setPivotY(pullingTop ? 0f : content.getHeight());
        content.setScaleX(1f);
        content.setScaleY(1f + value);
    }

    /** 记录这一笔用于算松手速度。按下时没能建立 tracker 就返回 false，调用方据此跳过。 */
    private boolean trackVelocity(MotionEvent event) {
        if (velocityTracker == null) return false;
        velocityTracker.addMovement(event);
        return true;
    }

    private void startTracking(MotionEvent event) {
        stopTracking();
        velocityTracker = VelocityTracker.obtain();
        velocityTracker.addMovement(event);
    }

    private void stopTracking() {
        if (velocityTracker != null) {
            velocityTracker.recycle();
            velocityTracker = null;
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        // 页面被替换掉时动画可能还在跑：必须停表并落位，否则回调会一直拽着这个 View 不放。
        ticker.cancel();
        jellyActive = false;
        stopTracking();
        stretch.snapTo(0f);
        applyStretch();
        super.onDetachedFromWindow();
    }
}
