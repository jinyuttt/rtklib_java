package org.rtklib.java.otl;

/**
 * [JAVA-ONLY 原创] Doodson 数的分解结果：六个基本天文幅角的整数乘子。
 *
 * <p><b>来源说明</b>：编码规则见 Doodson (1921) 及
 * IERS Conventions (2010) 第 7 章。六位数字 d1…d6 依次对应
 * (d1, d2−5, d3−5, d4−5, d5−5, d6−5)，首位为平太阴时 τ 的乘子。
 * 这是公开的编码约定，本类按该定义独立实现。</p>
 *
 * <p>本类是不可变值对象，构造后仅提供只读访问与相位合成。</p>
 */
public final class DoodsonArgument {

    private static final int DIGIT_COUNT = 6;
    private static final int LEADING_PLACE_VALUE = 100000;
    private static final int PLACE_RADIX = 10;
    /** 除首位外，其余各位以 5 为零点偏移。 */
    private static final int DIGIT_OFFSET = 5;

    private final int[] multipliers;

    private DoodsonArgument(int[] multipliers) {
        this.multipliers = multipliers;
    }

    /**
     * 分解整数形式的 Doodson 编码（例如 M2 为 255555）。
     *
     * @param doodsonCode 整数形式 Doodson 编码
     */
    public static DoodsonArgument of(int doodsonCode) {
        int[] multipliers = new int[DIGIT_COUNT];
        int remainder = doodsonCode;
        int placeValue = LEADING_PLACE_VALUE;

        for (int position = 0; position < DIGIT_COUNT; position++) {
            multipliers[position] = remainder / placeValue;
            remainder -= placeValue * multipliers[position];
            placeValue /= PLACE_RADIX;
            if (position > 0) {
                multipliers[position] -= DIGIT_OFFSET;
            }
        }
        return new DoodsonArgument(multipliers);
    }

    /** 第 {@code position} 个幅角（0 起，0 为平太阴时）的乘子。 */
    public int multiplier(int position) {
        return multipliers[position];
    }

    /** 按 Σ multiplierᵢ · angleᵢ 合成相位，单位弧度。 */
    public double phaseRad(AstronomicalArgument argument) {
        double phase = 0.0;
        for (int position = 0; position < DIGIT_COUNT; position++) {
            phase += multipliers[position] * argument.angleRad(AstronomicalArgument.Slot.at(position));
        }
        return phase;
    }
}