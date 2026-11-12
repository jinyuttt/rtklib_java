package org.rtklib.java.otl;

/**
 * [JAVA-ONLY 原创] 不可变复数，用于表示分潮的复数振幅（余弦/正弦分量对）。
 *
 * <p>复数振幅是本模块把「振幅 + 相位」转成「余弦系数 + 正弦系数」的中间量，
 * 便于在同一阶次、同一次数的项上线性叠加。这是通用的复变函数表示，不构成
 * 任何特定源码的复制。</p>
 */
public final class ComplexNumber {

    public static final ComplexNumber ZERO = new ComplexNumber(0.0, 0.0);

    private final double real;
    private final double imaginary;

    private ComplexNumber(double real, double imaginary) {
        this.real = real;
        this.imaginary = imaginary;
    }

    public static ComplexNumber of(double real, double imaginary) {
        return new ComplexNumber(real, imaginary);
    }

    public ComplexNumber plus(ComplexNumber other) {
        return new ComplexNumber(real + other.real, imaginary + other.imaginary);
    }

    public ComplexNumber times(double scalar) {
        return new ComplexNumber(real * scalar, imaginary * scalar);
    }

    public ComplexNumber negate() {
        return new ComplexNumber(-real, -imaginary);
    }

    public double real() {
        return real;
    }

    public double imaginary() {
        return imaginary;
    }

    /** 模，即分潮振幅。 */
    public double magnitude() {
        return Math.sqrt(real * real + imaginary * imaginary);
    }

    /** 相位 atan2(虚部, 实部)，单位度。 */
    public double phaseDegrees() {
        return Math.toDegrees(Math.atan2(imaginary, real));
    }
}