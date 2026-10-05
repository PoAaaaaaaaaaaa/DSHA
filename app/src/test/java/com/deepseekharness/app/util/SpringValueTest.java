package com.deepseekharness.app.util;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SpringValueTest {

    private static final float DT = 1f / 60f;

    @Test public void criticalDampingSettlesWithoutOvershoot() {
        SpringValue spring = new SpringValue(0f, SpringValue.DAMPING_CRITICAL, 500f, 0.001f);
        spring.animateTo(100f);
        float previous = 0f;
        for (int i = 0; i < 600 && !spring.isSettled(); i++) {
            spring.step(DT);
            assertTrue("临界阻尼不能越过目标，实际 " + spring.value(), spring.value() <= 100.001f);
            assertTrue("必须单调靠近目标，实际 " + spring.value(), spring.value() >= previous - 0.001f);
            previous = spring.value();
        }
        assertTrue("必须在有限帧内停下", spring.isSettled());
        assertEquals(100f, spring.value(), 0f);
    }

    @Test public void gentleDampingOvershootsThenSettles() {
        SpringValue spring = new SpringValue(0f, SpringValue.DAMPING_GENTLE, 400f, 0.0005f);
        spring.animateTo(100f);
        float peak = 0f;
        for (int i = 0; i < 600 && !spring.isSettled(); i++) {
            spring.step(DT);
            peak = Math.max(peak, spring.value());
        }
        assertTrue("欠阻尼必须过冲，否则没有果冻感；峰值 " + peak, peak > 100f);
        assertTrue("过冲幅度要克制，峰值 " + peak, peak < 130f);
        assertEquals(100f, spring.value(), 0f);
    }

    @Test public void overdampingApproachesSlowlyWithoutOvershoot() {
        SpringValue spring = new SpringValue(0f, 1.8f, 500f, 0.001f);
        spring.animateTo(100f);
        for (int i = 0; i < 600 && !spring.isSettled(); i++) {
            spring.step(DT);
            assertTrue("过阻尼不能过冲，实际 " + spring.value(), spring.value() <= 100.001f);
        }
        assertEquals(100f, spring.value(), 0f);
    }

    @Test public void reTargetingKeepsMomentumInsteadOfJumping() {
        SpringValue spring = new SpringValue(0f, SpringValue.DAMPING_STANDARD, 600f, 0.001f);
        spring.animateTo(100f);
        for (int i = 0; i < 5; i++) spring.step(DT);
        assertTrue("起步阶段应已有正向速度", spring.velocity() > 0f);

        // 改目标只改「去哪儿」，不改「现在在哪」：位置必须原地不动，否则界面会跳一下。
        float atSwitch = spring.value();
        spring.animateTo(0f);
        assertEquals("改目标本身不能移动位置", atSwitch, spring.value(), 0f);

        // 而速度会带着往前走一帧，所以下一步仍是连续位移，不是瞬移。
        spring.step(DT);
        assertTrue("单帧位移不能突变，实际 " + Math.abs(spring.value() - atSwitch),
                Math.abs(spring.value() - atSwitch) < 20f);
    }

    @Test public void initialVelocityIsHandedOver() {
        SpringValue spring = new SpringValue(0f, SpringValue.DAMPING_STANDARD, 600f, 0.001f);
        spring.animateTo(0f, 900f);
        spring.step(DT);
        assertTrue("带着初速度抛出必须真的往前走，实际 " + spring.value(), spring.value() > 5f);
    }

    @Test public void snapToDropsVelocity() {
        SpringValue spring = new SpringValue(0f, SpringValue.DAMPING_STANDARD, 600f, 0.001f);
        spring.animateTo(100f);
        spring.step(DT);
        spring.snapTo(42f);
        assertEquals(42f, spring.value(), 0f);
        assertEquals(0f, spring.velocity(), 0f);
        assertTrue(spring.isSettled());
        assertFalse("已经停下就不该再请求下一帧", spring.step(DT));
    }

    @Test public void longFrameDoesNotDiverge() {
        SpringValue spring = new SpringValue(0f, SpringValue.DAMPING_GENTLE, 500f, 0.001f);
        spring.animateTo(100f);
        // 从后台回来时 dt 可能是好几秒：解析解必须仍然收敛，而不是炸成天文数字。
        for (int i = 0; i < 60 && !spring.isSettled(); i++) spring.step(2f);
        assertEquals(100f, spring.value(), 0f);
    }

    @Test public void nonPositiveFrameIsIgnoredButReported() {
        SpringValue spring = new SpringValue(0f, SpringValue.DAMPING_STANDARD, 500f, 0.001f);
        spring.animateTo(100f);
        assertTrue("还没走完就该继续", spring.step(0f));
        assertTrue(spring.step(-1f));
        assertEquals("dt 非法时位置不能变", 0f, spring.value(), 0f);
    }

    @Test public void nonFiniteTargetIsContained() {
        SpringValue spring = new SpringValue(0f, SpringValue.DAMPING_STANDARD, 500f, 0.001f);
        spring.animateTo(Float.NaN);
        spring.step(DT);
        assertTrue("NaN 目标必须被收敛掉", Float.isFinite(spring.value()));
        assertTrue(Float.isFinite(spring.velocity()));
        assertTrue(spring.isSettled());
    }

    @Test public void fractionMapsAndClamps() {
        assertEquals(0f, SpringValue.fraction(0f, 100f, -50f), 0f);
        assertEquals(0.5f, SpringValue.fraction(0f, 100f, 50f), 0f);
        assertEquals(1f, SpringValue.fraction(0f, 100f, 250f), 0f);
        assertEquals(0f, SpringValue.fraction(10f, 10f, 10f), 0f);
    }

    @Test public void invalidDampingAndStiffnessFallBackToUsableValues() {
        // 外部（XML 属性、配置）传进来的参数不可信：0 刚度会让 omega0=0，弹簧永远不动。
        SpringValue spring = new SpringValue(0f, 0f, 0f, 0f);
        spring.animateTo(10f);
        for (int i = 0; i < 60; i++) spring.step(DT);
        assertTrue("参数非法也必须真的动起来，实际 " + spring.value(), spring.value() > 1f);
        assertTrue("方向必须朝目标走，实际 " + spring.value(), spring.value() <= 10.001f);
    }
}
