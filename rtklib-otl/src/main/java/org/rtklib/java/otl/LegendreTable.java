package org.rtklib.java.otl;

/**
 * [JAVA-ONLY 原创] 完全归一化连带勒让德函数及其对余纬的一、二阶导数数值表。
 *
 * <p><b>来源说明</b>：完全归一化（4π 归一）连带勒让德函数的定义与三项递推关系
 * 是公开的数学结果，见 IERS Conventions (2010) 第 4 章及标准球谐分析教材
 * （Heiskanen &amp; Moritz, <i>Physical Geodesy</i>）。本类按这些定义独立实现，
 * 采用按 (阶, 次) 直接寻址的二维表，未沿用任何源码的一维打包索引方案。</p>
 *
 * <p>本类是不可变值对象：构造时一次性算好全部阶次，之后只读。</p>
 */
public final class LegendreTable {

    private static final double ROOT_THREE = Math.sqrt(3.0);

    private final int maxDegree;
    private final double cosColatitude;
    private final double sinColatitude;
    private final double[][] normalizedValue;
    private final double[][] firstDerivative;
    private final double[][] secondDerivative;

    /**
     * @param maxDegree    最高阶
     * @param cosColatitude cos(θ)，θ 为地心余纬
     */
    public LegendreTable(int maxDegree, double cosColatitude) {
        this.maxDegree = maxDegree;
        this.cosColatitude = cosColatitude;
        this.sinColatitude = Math.sqrt(Math.max(0.0, 1.0 - cosColatitude * cosColatitude));

        int rows = maxDegree + 1;
        int columns = maxDegree + 2;
        normalizedValue = new double[rows][columns];
        firstDerivative = new double[rows][columns];
        secondDerivative = new double[rows][columns];

        fillNormalizedValues();
        if (maxDegree >= 1) {
            fillFirstDerivatives();
            fillSecondDerivatives();
        }
    }

    /** 阶 n、次 m 的完全归一化函数值 P(n, m)。 */
    public double value(int degree, int order) {
        return normalizedValue[degree][order];
    }

    /** ∂P(n, m)/∂θ。 */
    public double firstDerivative(int degree, int order) {
        return firstDerivative[degree][order];
    }

    /** ∂²P(n, m)/∂θ²。 */
    public double secondDerivative(int degree, int order) {
        return secondDerivative[degree][order];
    }

    public int maxDegree() {
        return maxDegree;
    }

    /**
     * 以 cosθ、sinθ 三项递推全部 P(n, m)。
     *
     * <p>低阶种子：P(0,0)=1，P(1,0)=√3·cosθ，P(1,1)=√3·sinθ。</p>
     */
    private void fillNormalizedValues() {
        normalizedValue[0][0] = 1.0;
        if (maxDegree < 1) {
            return;
        }
        normalizedValue[1][0] = ROOT_THREE * cosColatitude;
        normalizedValue[1][1] = ROOT_THREE * sinColatitude;

        for (int degree = 1; degree <= maxDegree; degree++) {
            double degreeScale = Math.sqrt(2.0 * degree + 1.0) / Math.sqrt(2.0 * degree - 1.0);

            double meridionalFactor = Math.sqrt(2.0 * (degree - 1.0) * (2.0 * degree + 1.0))
                    / Math.sqrt((2.0 * degree - 1.0) * degree);
            normalizedValue[degree][0] = degreeScale * cosColatitude * normalizedValue[degree - 1][0]
                    - meridionalFactor * sinColatitude / 2.0 * normalizedValue[degree - 1][1];

            for (int order = 1; order <= degree; order++) {
                double cosineWeight = degreeScale / degree
                        * Math.sqrt((double) (degree * degree - order * order));
                double lowerWeight = degreeScale / degree / 2.0
                        * Math.sqrt((double) ((degree - order) * (degree - order - 1)));
                double upperWeight = degreeScale / degree / 2.0
                        * Math.sqrt((double) ((degree + order) * (degree + order - 1)));
                if (order == 1) {
                    upperWeight *= Math.sqrt(2.0);
                }
                normalizedValue[degree][order] = cosineWeight * cosColatitude
                        * normalizedValue[degree - 1][order]
                        - lowerWeight * sinColatitude * normalizedValue[degree - 1][order + 1]
                        + upperWeight * sinColatitude * normalizedValue[degree - 1][order - 1];
            }
        }
    }

    /** 由 P(n, m) 自身递推一阶导数。 */
    private void fillFirstDerivatives() {
        firstDerivative[1][0] = -ROOT_THREE * sinColatitude;
        firstDerivative[1][1] = ROOT_THREE * cosColatitude;

        for (int degree = 2; degree <= maxDegree; degree++) {
            firstDerivative[degree][0] = -Math.sqrt(degree * (degree + 1) / 2.0)
                    * normalizedValue[degree][1];

            double meridionalWeight = Math.sqrt(2.0 * degree) * Math.sqrt(degree + 1.0) / 2.0;
            double neighbourWeight = -Math.sqrt(degree - 1.0) * Math.sqrt(degree + 2.0) / 2.0;
            firstDerivative[degree][1] = meridionalWeight * normalizedValue[degree][0]
                    + neighbourWeight * normalizedValue[degree][2];

            for (int order = 2; order <= degree; order++) {
                double lowerWeight = Math.sqrt(degree + order)
                        * Math.sqrt(degree - order + 1.0) / 2.0;
                double upperWeight = -Math.sqrt(degree - order)
                        * Math.sqrt(degree + order + 1.0) / 2.0;
                firstDerivative[degree][order] = lowerWeight * normalizedValue[degree][order - 1]
                        + upperWeight * normalizedValue[degree][order + 1];
            }
        }
    }

    /** 由 P(n, m) 自身递推二阶导数。 */
    private void fillSecondDerivatives() {
        secondDerivative[1][0] = -ROOT_THREE * cosColatitude;
        secondDerivative[1][1] = -ROOT_THREE * sinColatitude;

        for (int degree = 2; degree <= maxDegree; degree++) {
            double meridionalSelf = -(double) (degree * (degree + 1)) / 2.0;
            double meridionalNeighbour = Math.sqrt((double) (degree * (degree - 1)))
                    * Math.sqrt((double) ((degree + 1) * (degree + 2))) / Math.sqrt(8.0);
            secondDerivative[degree][0] = meridionalSelf * normalizedValue[degree][0]
                    + meridionalNeighbour * normalizedValue[degree][2];

            double meridionalSelfOne = -(2.0 * degree * (degree + 1)
                    + (degree - 1.0) * (degree + 2.0)) / 4.0;
            double meridionalNeighbourOne = Math.sqrt((double) ((degree - 2) * (degree - 1)))
                    * Math.sqrt((double) ((degree + 2) * (degree + 3))) / 4.0;
            secondDerivative[degree][1] = meridionalSelfOne * normalizedValue[degree][1]
                    + meridionalNeighbourOne * normalizedValue[degree][3];

            for (int order = 2; order <= degree; order++) {
                double lowerWeight = Math.sqrt((double) ((degree - order + 1) * (degree - order + 2)))
                        * Math.sqrt((double) ((degree + order - 1) * (degree + order))) / 4.0;
                double selfWeight = -((double) ((degree - order + 1) * (degree + order)
                        + (degree - order) * (degree + order + 1))) / 4.0;
                double upperWeight = Math.sqrt((double) ((degree - order - 1) * (degree - order)))
                        * Math.sqrt((double) ((degree + order + 1) * (degree + order + 2))) / 4.0;
                secondDerivative[degree][order] = lowerWeight * normalizedValue[degree][order - 1]
                        + selfWeight * normalizedValue[degree][order]
                        + upperWeight * normalizedValue[degree][order + 1];
            }
        }
    }
}