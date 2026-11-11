package org.rtklib.java.ppp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FIX-ALG-061 回归测试：初始模糊度 sanity 门限的解析与判定。
 *
 * <p>覆盖三件事：</p>
 * <ol>
 *   <li>缺省（属性未设置）必须回退 500 m，保证与 FIX-ALG-044 默认路径逐位一致；</li>
 *   <li>非法属性（非数字、非正、NaN/Inf）必须回退 500 m 并告警，不允许把门限
 *       置成 0 或负值导致全星被判粗差；</li>
 *   <li>判定边界：{@code bias == 0} 表示无观测（不算粗差），恰好等于门限不算越界。</li>
 * </ol>
 *
 * <p>门限只在类加载时解析一次（{@link PppCore#initBiasSanityLimit()}），故本测试
 * 只验证纯函数 {@code resolveInitBiasSanityLimit(String)} 与
 * {@code isInitBiasOutlier(double, double)}，不改动运行期静态值。</p>
 */
public class PppInitBiasSanityTest {

    @Test
    @DisplayName("缺省门限：未设置属性时回退 500 m")
    void defaultLimitWhenPropertyAbsent() {
        assertEquals(PppCore.INIT_BIAS_SANITY_DEFAULT, PppCore.resolveInitBiasSanityLimit(null));
        assertEquals(PppCore.INIT_BIAS_SANITY_DEFAULT, PppCore.resolveInitBiasSanityLimit(""));
        assertEquals(PppCore.INIT_BIAS_SANITY_DEFAULT, PppCore.resolveInitBiasSanityLimit("   "));
        assertEquals(PppCore.INIT_BIAS_SANITY_DEFAULT, PppCore.initBiasSanityLimit());
    }

    @Test
    @DisplayName("合法覆盖：正数属性生效，含前后空白")
    void validOverride() {
        assertEquals(100.0, PppCore.resolveInitBiasSanityLimit("100"));
        assertEquals(100.0, PppCore.resolveInitBiasSanityLimit(" 100 "));
        assertEquals(250.5, PppCore.resolveInitBiasSanityLimit("250.5"));
        assertEquals(PppCore.INIT_BIAS_SANITY_CANDIDATE, PppCore.resolveInitBiasSanityLimit("100"));
    }

    @Test
    @DisplayName("非法覆盖：非数字/非正/NaN/Inf 一律回退 500 m")
    void invalidOverrideFallsBack() {
        assertEquals(PppCore.INIT_BIAS_SANITY_DEFAULT, PppCore.resolveInitBiasSanityLimit("abc"));
        assertEquals(PppCore.INIT_BIAS_SANITY_DEFAULT, PppCore.resolveInitBiasSanityLimit("0"));
        assertEquals(PppCore.INIT_BIAS_SANITY_DEFAULT, PppCore.resolveInitBiasSanityLimit("-5"));
        assertEquals(PppCore.INIT_BIAS_SANITY_DEFAULT, PppCore.resolveInitBiasSanityLimit("NaN"));
        assertEquals(PppCore.INIT_BIAS_SANITY_DEFAULT, PppCore.resolveInitBiasSanityLimit("Infinity"));
    }

    @Test
    @DisplayName("判定边界：0 不算粗差，等于门限不算越界，符号对称")
    void outlierBoundary() {
        assertFalse(PppCore.isInitBiasOutlier(0.0, 500.0), "无观测（bias=0）不应判为粗差");
        assertFalse(PppCore.isInitBiasOutlier(0.0, 100.0), "无观测（bias=0）不应判为粗差");

        assertFalse(PppCore.isInitBiasOutlier(500.0, 500.0), "恰好等于门限不算越界");
        assertTrue(PppCore.isInitBiasOutlier(500.1, 500.0));

        assertFalse(PppCore.isInitBiasOutlier(-500.0, 500.0));
        assertTrue(PppCore.isInitBiasOutlier(-500.1, 500.0));
    }

    @Test
    @DisplayName("sat=107 场景：372 m 在 500 m 下放行、在 100 m 下越界")
    void sat107Case() {
        double sat107Bias = 372.43;
        assertFalse(PppCore.isInitBiasOutlier(sat107Bias, PppCore.INIT_BIAS_SANITY_DEFAULT),
            "500 m 门限放行 372 m —— 故 FIX-ALG-038 实际生效的是 rejc 冻结而非 500 m 校验");
        assertTrue(PppCore.isInitBiasOutlier(sat107Bias, PppCore.INIT_BIAS_SANITY_CANDIDATE),
            "100 m 门限应拦截 372 m");
    }

    @Test
    @DisplayName("正常量级：5 m 量级在两种门限下均放行")
    void normalMagnitudeAccepted() {
        for (double v : new double[]{1.0, 5.0, 20.0, 50.0}) {
            assertFalse(PppCore.isInitBiasOutlier(v, PppCore.INIT_BIAS_SANITY_CANDIDATE),
                "正常量级 " + v + " m 不应被 100 m 门限误杀");
        }
    }
}