package org.rtklib.java.ppp;

import org.rtklib.java.common.SatUtils;
import org.rtklib.java.config.RtkConfig;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.data.Nav;

/**
 * PPP OSB（Observable-Specific Bias）偏差改正模型。
 *
 * <p>OSB模型为每个观测值类型提供独立偏差改正，取代传统DCB。
 * 在IF（无电离层）组合观测值中，OSB改正按频率加权组合：
 * <pre>
 *   osb_IF = α * osb_f1 + β * osb_f2
 *   其中 α = f1²/(f1²-f2²), β = -f2²/(f1²-f2²)
 * </pre>
 *
 * <p>OSB数据由OsbReader读取Bias-SINEX格式文件，相位OSB存入nav.fcbWl，伪距OSB存入nav.cbias。
 *
 * <p>对应C版：RTKLIB使用DCB改正，本模块为v2.3.0新增OSB模型
 */
public final class PppOsbModel {
    private PppOsbModel() {}

    /**
     * 单频OSB相位偏差改正（从nav.fcbWlByCode按信号码读取）。
     *
     * @param sat   卫星编号
     * @param freq  频率索引（0=freq1, 1=freq2）
     * @param code  观测码编号（未使用，保留接口兼容）
     * @param nav   导航数据
     * @param time  GPS秒（未使用，单天产品无时间插值）
     * @param cfg   配置
     * @return OSB偏差改正值(米)
     */
    public static double osbCorrection(int sat, int freq, int code, Nav nav, double time, RtkConfig cfg) {
        if (!cfg.enableOsb) return 0.0;
        if (nav.fcbWlByCode == null || sat <= 0 || sat > Constants.MAXSAT) return 0.0;
        if (code < 0 || code > Constants.MAXCODE) return 0.0;
        return nav.fcbWlByCode[sat - 1][code];
    }

    /**
     * IF组合观测值的OSB偏差改正。
     * 按各系统默认码类型从nav.fcbWl查询OSB，然后按IF组合权重合并。
     *
     * @param sat  卫星编号
     * @param nav  导航数据
     * @param time GPS秒
     * @param cfg  配置
     * @return IF组合OSB偏差改正值(米)
     */
    public static double osbCorrectionIfComb(int sat, int code0, int code1, Nav nav, double time, RtkConfig cfg) {
        if (!cfg.enableOsb) return 0.0;
        if (nav.fcbWlByCode == null || sat <= 0 || sat > Constants.MAXSAT) return 0.0;

        double osb1 = (code0 >= 0 && code0 <= Constants.MAXCODE) ? nav.fcbWlByCode[sat - 1][code0] : 0.0;
        double osb2 = (code1 >= 0 && code1 <= Constants.MAXCODE) ? nav.fcbWlByCode[sat - 1][code1] : 0.0;

        double freq1 = SatUtils.sat2freq(sat, code0, nav);
        double freq2 = SatUtils.sat2freq(sat, code1, nav);
        if (freq1 == 0.0 || freq2 == 0.0) return 0.0;

        double f1sq = freq1 * freq1;
        double f2sq = freq2 * freq2;
        double alpha = f1sq / (f1sq - f2sq);
        double beta = -f2sq / (f1sq - f2sq);

        return alpha * osb1 + beta * osb2;
    }
}