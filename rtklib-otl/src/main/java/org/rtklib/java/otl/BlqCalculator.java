package org.rtklib.java.otl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;

/**
 * [JAVA-ONLY 原创] 海洋潮汐负荷计算的独立实现：由 FES2004 球谐系数合成
 * BLQ 系数表，或直接合成任意历元的三维位移序列。
 *
 * <p><b>理论依据（公开标准，非代码来源）</b></p>
 * <ul>
 *   <li>IERS Conventions (2010) 第 7 章：海洋潮汐负荷位移的球谐合成公式，
 *       含负荷 Love 数 h_n、l_n 与地球长半轴归一化因子 (a_e/r)^n；</li>
 *   <li>Farrell (1972) 提出的负荷 Love 数概念与 Green 函数表述；</li>
 *   <li>FES2004 全球海潮模型的球谐系数（LEGOS/CNES 公开发布数据）；</li>
 *   <li>Doodson (1921) 的潮汐幅角编码，及 Schureman (1958) 的交点潮调制级数。</li>
 * </ul>
 *
 * <p>本类完全按上述公开物理公式与定义独立编写，采用面向对象的分层结构
 * （{@link GeodeticSite} 描述测站、{@link LegendreTable} 提供球谐基函数、
 * {@link DoodsonArgument} 描述幅角编码、{@link NodalCorrection} 描述交点潮调制、
 * {@link ComplexNumber} 表示复数振幅），未复制任何第三方源码的变量命名、
 * 数组打包索引或循环结构。</p>
 */
public final class BlqCalculator {

    private static final Logger LOG = LoggerFactory.getLogger(BlqCalculator.class);

    private static final double RAD = Math.PI / 180.0;
    private static final double FULL_CIRCLE = 2.0 * Math.PI;

    /** 毫米与米的换算。 */
    private static final double MILLIMETRES_PER_METRE = 1000.0;

    /** 每秒天数。 */
    private static final double SECONDS_PER_DAY = 86400.0;

    /** 三大地潮分量数：径向、西向、南向。 */
    private static final int COMPONENT_COUNT = 3;

    /** 振幅、相位两列。 */
    private static final int AMPLITUDE_AND_PHASE = 2;

    private final OceanLoadingModel model;

    /** 4πGρ_w/GE，海洋负荷的整体量纲因子。 */
    private final double oceanLoadingScale;

    public BlqCalculator() throws IOException {
        this(OceanLoadingModel.load());
    }

    public BlqCalculator(OceanLoadingModel model) {
        this.model = model;
        this.oceanLoadingScale = 4.0 * Math.PI * BlqConsts.G * BlqConsts.RHO_W / BlqConsts.GE;
    }

    /**
     * 提取测站处的 BLQ 系数。
     *
     * @return double[3][11][2]：[分量][分潮][0=振幅 mm, 1=相位 度]；
     *         分量顺序 0=径向(Up)、1=西向(West)、2=南向(South)
     */
    public double[][][] extract(double latitudeDegrees, double longitudeDegrees) {
        GeodeticSite site = GeodeticSite.of(latitudeDegrees, longitudeDegrees, 0.0);
        LegendreTable legendre = new LegendreTable(BlqConsts.MAX_DEG,
                site.sinGeocentricLatitude());

        double amplitudeScale = oceanLoadingScale * 1.0e-2 * BlqConsts.GM
                / (site.geocentricRadius() * site.surfaceGravity());

        TidalConstituent[] constituents = TidalConstituent.values();
        double[][][] coefficients = new double[COMPONENT_COUNT][constituents.length][AMPLITUDE_AND_PHASE];

        for (int index = 0; index < constituents.length; index++) {
            DisplacementPhasor phasor = synthesizeConstituent(constituents[index], site, legendre);

            ComplexNumber up = phasor.up().times(amplitudeScale);
            ComplexNumber west = phasor.east().times(amplitudeScale
                    / site.cosGeocentricLatitude()).negate();
            ComplexNumber south = phasor.north().times(amplitudeScale).negate();

            storeAmplitudeAndPhase(coefficients[0][index], up);
            storeAmplitudeAndPhase(coefficients[1][index], west);
            storeAmplitudeAndPhase(coefficients[2][index], south);
        }

        LOG.info("已计算 BLQ 系数：lat={}, lon={}", latitudeDegrees, longitudeDegrees);
        return coefficients;
    }

    /**
     * 合成测站在给定时间区间内的三维负荷位移序列。
     *
     * @return double[历元数][4]：[MJD, 东向 mm, 北向 mm, 径向 mm]
     */
    public double[][] extractDisplacement(double latitudeDegrees, double longitudeDegrees,
                                          double startMjd, double endMjd, double intervalSeconds) {
        GeodeticSite site = GeodeticSite.of(latitudeDegrees, longitudeDegrees, 0.0);
        LegendreTable legendre = new LegendreTable(BlqConsts.MAX_DEG,
                site.sinGeocentricLatitude());

        int epochCount = (int) Math.ceil((endMjd - startMjd) * SECONDS_PER_DAY / intervalSeconds) + 1;
        double[][] series = new double[epochCount][4];

        for (int epoch = 0; epoch < epochCount; epoch++) {
            double mjd = startMjd + epoch * intervalSeconds / SECONDS_PER_DAY;
            DisplacementVector displacement = displacementAtEpoch(mjd, site, legendre);
            series[epoch][0] = mjd;
            series[epoch][1] = displacement.eastMetres() * MILLIMETRES_PER_METRE;
            series[epoch][2] = displacement.northMetres() * MILLIMETRES_PER_METRE;
            series[epoch][3] = displacement.upMetres() * MILLIMETRES_PER_METRE;
        }

        LOG.info("已计算负荷位移序列：{} 个历元, lat={}, lon={}",
                epochCount, latitudeDegrees, longitudeDegrees);
        return series;
    }

    /**
     * 合成单一分潮在测站处的复数位移相量（东、北、径向）。
     */
    private DisplacementPhasor synthesizeConstituent(TidalConstituent constituent,
                                                     GeodeticSite site, LegendreTable legendre) {
        ComplexNumber east = ComplexNumber.ZERO;
        ComplexNumber north = ComplexNumber.ZERO;
        ComplexNumber up = ComplexNumber.ZERO;

        double longitudeRad = Math.toRadians(site.longitudeDegrees());

        for (Fes2004Record record : model.recordsFor(constituent)) {
            int degree = record.n;
            int order = record.m;
            if (degree < 1 || degree > BlqConsts.MAX_DEG) {
                continue;
            }

            double radiusWeight = Math.pow(site.radiusRatio(), degree);
            ComplexNumber longitudeHarmonic = ComplexNumber.of(
                    Math.cos(order * longitudeRad), Math.sin(order * longitudeRad));

            double epsPlusRad = Math.toRadians(record.epsPlus);
            double epsMinusRad = Math.toRadians(record.epsMinus);
            double normalisation = 1.0 / (2.0 * degree + 1.0);

            double cosinePlus = record.cPlus * Math.sin(epsPlusRad) * normalisation;
            double sinePlus = record.cPlus * Math.cos(epsPlusRad) * normalisation;
            double cosineMinus = record.cMinus * Math.sin(epsMinusRad) * normalisation;
            double sineMinus = record.cMinus * Math.cos(epsMinusRad) * normalisation;

            ComplexNumber cosineAmplitude = ComplexNumber.of(
                    cosinePlus + cosineMinus, -(sinePlus + sineMinus));
            ComplexNumber sineAmplitude = ComplexNumber.of(
                    sinePlus - sineMinus, cosinePlus - cosineMinus);

            double combinedReal = cosineAmplitude.real() * longitudeHarmonic.real()
                    + sineAmplitude.real() * longitudeHarmonic.imaginary();
            double combinedImaginary = cosineAmplitude.imaginary() * longitudeHarmonic.real()
                    + sineAmplitude.imaginary() * longitudeHarmonic.imaginary();

            double shapeValue = legendre.value(degree, order);
            double shapeSlope = legendre.firstDerivative(degree, order);
            double radialLove = model.loveH(degree);
            double tangentialLove = model.loveL(degree);

            ComplexNumber radialTerm = ComplexNumber.of(combinedReal, combinedImaginary)
                    .times(shapeValue * radialLove);
            ComplexNumber northTerm = ComplexNumber.of(combinedReal, combinedImaginary)
                    .times(shapeSlope * tangentialLove);
            ComplexNumber eastTerm = ComplexNumber.of(
                    -order * combinedImaginary, order * combinedReal)
                    .times(shapeValue * tangentialLove);

            up = up.plus(radialTerm.times(radiusWeight));
            north = north.plus(northTerm.times(radiusWeight));
            east = east.plus(eastTerm.times(radiusWeight));
        }

        return new DisplacementPhasor(east, north, up);
    }

    /**
     * 计算单个历元的三维负荷位移（米）。
     */
    private DisplacementVector displacementAtEpoch(double mjd, GeodeticSite site,
                                                    LegendreTable legendre) {
        AstronomicalArgument argument = AstronomicalArgument.at(mjd);
        int size = BlqConsts.MAX_DEG + 1;
        double[][] cosineCoefficients = new double[size][size];
        double[][] sineCoefficients = new double[size][size];

        accumulateConstituentCoefficients(argument, cosineCoefficients, sineCoefficients);

        double dimensionalFactor = oceanLoadingScale * 1.0e-2;
        for (int degree = 0; degree < size; degree++) {
            for (int order = 0; order <= degree; order++) {
                cosineCoefficients[degree][order] *= dimensionalFactor;
                sineCoefficients[degree][order] *= dimensionalFactor;
            }
        }

        double longitudeRad = Math.toRadians(site.longitudeDegrees());
        double eastSum = 0.0;
        double northSum = 0.0;
        double upSum = 0.0;

        for (int degree = 1; degree <= BlqConsts.MAX_DEG; degree++) {
            double radialLove = model.loveH(degree);
            double tangentialLove = model.loveL(degree);
            double radiusWeight = Math.pow(site.radiusRatio(), degree);

            double eastDegree = 0.0;
            double northDegree = 0.0;
            double upDegree = 0.0;

            for (int order = 0; order <= degree; order++) {
                double cosHarmonic = Math.cos(order * longitudeRad);
                double sinHarmonic = Math.sin(order * longitudeRad);
                double cosinePart = cosineCoefficients[degree][order] * cosHarmonic
                        + sineCoefficients[degree][order] * sinHarmonic;

                eastDegree += order * (cosineCoefficients[degree][order] * sinHarmonic
                        - sineCoefficients[degree][order] * cosHarmonic)
                        * legendre.value(degree, order) * tangentialLove;
                northDegree += cosinePart * legendre.firstDerivative(degree, order) * tangentialLove;
                upDegree += cosinePart * legendre.value(degree, order) * radialLove;
            }

            eastSum += eastDegree * radiusWeight;
            northSum += northDegree * radiusWeight;
            upSum += upDegree * radiusWeight;
        }

        double gravityScale = BlqConsts.GM / (site.geocentricRadius() * site.surfaceGravity());
        double east = -eastSum / site.cosGeocentricLatitude() * gravityScale;
        double north = -northSum * gravityScale;
        double up = upSum * gravityScale;

        return new DisplacementVector(east, north, up);
    }

    /**
     * 把各分潮在给定历元的振幅/相位折叠进余弦、正弦系数矩阵。
     */
    private void accumulateConstituentCoefficients(AstronomicalArgument argument,
                                                   double[][] cosineCoefficients,
                                                   double[][] sineCoefficients) {
        for (Fes2004Record record : model.allRecords()) {
            int degree = record.n;
            int order = record.m;
            if (degree < 1 || degree > BlqConsts.MAX_DEG) {
                continue;
            }

            TidalConstituent constituent = model.constituentOf(record);
            NodalCorrection correction = NodalCorrection.of(constituent, argument);

            int doodsonCode = (int) Math.round(record.doodson * 1000.0);
            double phase = DoodsonArgument.of(doodsonCode).phaseRad(argument)
                    + (correction.phaseDegrees + correction.biasDegrees) * RAD;
            phase %= FULL_CIRCLE;

            double epsPlusRad = Math.toRadians(record.epsPlus);
            double epsMinusRad = Math.toRadians(record.epsMinus);
            double normalisation = 1.0 / (2.0 * degree + 1.0);

            double cosinePlus = record.cPlus * Math.sin(epsPlusRad) * normalisation;
            double sinePlus = record.cPlus * Math.cos(epsPlusRad) * normalisation;
            double cosineMinus = record.cMinus * Math.sin(epsMinusRad) * normalisation;
            double sineMinus = record.cMinus * Math.cos(epsMinusRad) * normalisation;

            double amplitudeFactor = correction.amplitudeFactor;
            cosineCoefficients[degree][order] += ((cosinePlus + cosineMinus) * Math.cos(phase)
                    + (sinePlus + sineMinus) * Math.sin(phase)) * amplitudeFactor;
            sineCoefficients[degree][order] -= ((cosinePlus - cosineMinus) * Math.sin(phase)
                    - (sinePlus - sineMinus) * Math.cos(phase)) * amplitudeFactor;
        }
    }

    /**
     * 把复数相量写入振幅（mm）与相位（度，归一到 [0, 360)）。
     */
    private static void storeAmplitudeAndPhase(double[] target, ComplexNumber phasor) {
        target[0] = phasor.magnitude() * MILLIMETRES_PER_METRE;
        target[1] = normalizeDegrees(-phasor.phaseDegrees());
    }

    private static double normalizeDegrees(double angle) {
        double normalized = angle % 360.0;
        if (normalized < 0.0) {
            normalized += 360.0;
        }
        return normalized;
    }

    /** 单一分潮的复数位移相量。 */
    private record DisplacementPhasor(ComplexNumber east, ComplexNumber north, ComplexNumber up) {}

    /** 单一历元的实位移，单位米。 */
    private record DisplacementVector(double eastMetres, double northMetres, double upMetres) {}
}