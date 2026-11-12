package org.rtklib.java.otl;

/**
 * [JAVA-ONLY 原创] 本模块输出 BLQ 文件时所需的 11 个主分潮。
 *
 * <p><b>来源说明</b>：分潮名称与 Doodson 编码取自公开发表的
 * IERS Conventions (2010) 第 7 章（海洋潮汐负荷）表 7.1，以及
 * Schureman (1958) <i>Manual of Harmonic Analysis and Prediction of Tides</i>。
 * 两者均为公开标准/文献，不构成任何特定软件实现的代码来源。</p>
 *
 * <p>Doodson 编码在此以整数表示（如 M2 为 {@code 255555}，对应
 * Doodson 数 255.555），整数形式便于逐位分解出六个基本天文幅角的乘子。</p>
 */
public enum TidalConstituent {

    M2("M2", 255555),
    S2("S2", 273555),
    N2("N2", 245655),
    K2("K2", 275555),
    K1("K1", 165555),
    O1("O1", 145555),
    P1("P1", 163555),
    Q1("Q1", 135655),
    MF("Mf", 75555),
    MM("Mm", 65455),
    SSA("Ssa", 57555);

    /** Doodson 编码以整数存储，小数点后三位隐含于此比例因子中。 */
    private static final double CODE_SCALE = 1000.0;

    /** 海潮模型文件中的 Doodson 数与本表编码的匹配容差。 */
    private static final double MATCH_TOLERANCE = 5.0e-4;

    private final String label;
    private final int doodsonCode;

    TidalConstituent(String label, int doodsonCode) {
        this.label = label;
        this.doodsonCode = doodsonCode;
    }

    public String label() {
        return label;
    }

    public int doodsonCode() {
        return doodsonCode;
    }

    /** Doodson 数的小数形式，例如 M2 返回 255.555。 */
    public double doodsonValue() {
        return doodsonCode / CODE_SCALE;
    }

    /**
     * 判断海潮模型记录中的 Doodson 数是否属于本分潮。
     *
     * @param modelDoodsonValue 模型文件中的 Doodson 数（如 255.555）
     */
    public boolean matches(double modelDoodsonValue) {
        return Math.abs(modelDoodsonValue - doodsonValue()) < MATCH_TOLERANCE;
    }
}