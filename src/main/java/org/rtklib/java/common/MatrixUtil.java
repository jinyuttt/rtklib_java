package org.rtklib.java.common;

import org.ejml.simple.SimpleMatrix;

/**
 * 矩阵工具类，封装 EJML SimpleMatrix 操作。
 *
 * <p>提供行优先数组与 EJML SimpleMatrix 之间的转换，以及常用矩阵运算。
 * 所有数组均按<b>行优先（row-major）</b>存储，即 M[row * cols + col]。</p>
 *
 * <h3>为什么统一行优先</h3>
 * <ul>
 *   <li>Java/C++ 惯例是行优先，RTKLIB C 版本使用列优先（Fortran风格）</li>
 *   <li>EJML SimpleMatrix 内部也是行优先，直接映射无需转置</li>
 *   <li>避免行列优先混淆导致的矩阵乘法顺序错误</li>
 * </ul>
 *
 * <h3>与RTKLIB C版本的对应关系</h3>
 * <table>
 *   <tr><th>C版本（列优先）</th><th>Java版本（行优先）</th></tr>
 *   <tr><td>H[k + nv*nx]</td><td>H[nv*nx + k]</td></tr>
 *   <tr><td>R[i + j*ny]</td><td>R[i*ny + j]</td></tr>
 *   <tr><td>P[i + j*nx]</td><td>P[i*nx + j]</td></tr>
 * </table>
 */
public final class MatrixUtil {
    private MatrixUtil() {
    }

    /**
     * 从行优先数组创建 SimpleMatrix。
     *
     * @param data 行优先数据数组，长度必须 >= rows * cols
     * @param rows 行数
     * @param cols 列数
     * @return SimpleMatrix 实例
     */
    public static SimpleMatrix createMatrix(double[] data, int rows, int cols) {
        SimpleMatrix m = new SimpleMatrix(rows, cols);
        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < cols; j++) {
                m.set(i, j, data[i * cols + j]);
            }
        }
        return m;
    }

    /**
     * 将 SimpleMatrix 复制到行优先数组。
     *
     * @param src  源 SimpleMatrix
     * @param dest 目标行优先数组，长度必须 >= rows * cols
     */
    public static void copyMatrix(SimpleMatrix src, double[] dest) {
        int rows = src.numRows();
        int cols = src.numCols();
        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < cols; j++) {
                dest[i * cols + j] = src.get(i, j);
            }
        }
    }

    /**
     * 矩阵转置。
     *
     * @param m 输入矩阵
     * @return 转置矩阵
     */
    public static SimpleMatrix transpose(SimpleMatrix m) {
        return m.transpose();
    }

    /**
     * 矩阵乘法 a * b。
     *
     * <p>注意：矩阵乘法不满足交换律，a*b ≠ b*a</p>
     *
     * @param a 第一个矩阵
     * @param b 第二个矩阵
     * @return 乘积矩阵 a * b
     */
    public static SimpleMatrix multiply(SimpleMatrix a, SimpleMatrix b) {
        return a.mult(b);
    }

    /**
     * 矩阵加法 a + b。
     *
     * @param a 第一个矩阵
     * @param b 第二个矩阵
     * @return 和矩阵 a + b
     */
    public static SimpleMatrix add(SimpleMatrix a, SimpleMatrix b) {
        return a.plus(b);
    }

    /**
     * 矩阵减法 a - b。
     *
     * @param a 第一个矩阵
     * @param b 第二个矩阵
     * @return 差矩阵 a - b
     */
    public static SimpleMatrix subtract(SimpleMatrix a, SimpleMatrix b) {
        return a.minus(b);
    }

    /**
     * 创建单位矩阵。
     *
     * @param n 方阵大小
     * @return n x n 单位矩阵
     */
    public static SimpleMatrix identity(int n) {
        return SimpleMatrix.identity(n);
    }

    /**
     * 求解线性方程组 Ax = b。
     *
     * @param A 系数矩阵
     * @param b 右侧向量
     * @return 解向量 x
     */
    public static SimpleMatrix solve(SimpleMatrix A, SimpleMatrix b) {
        return A.solve(b);
    }

    /**
     * 矩阵求逆。
     *
     * @param A 输入矩阵（必须可逆）
     * @return 逆矩阵 A^-1
     */
    public static SimpleMatrix invert(SimpleMatrix A) {
        return A.invert();
    }

    /**
     * 将 SimpleMatrix 转为行优先数组。
     *
     * @param m 输入矩阵
     * @return 行优先数组
     */
    public static double[] toArray(SimpleMatrix m) {
        int rows = m.numRows();
        int cols = m.numCols();
        double[] result = new double[rows * cols];
        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < cols; j++) {
                result[i * cols + j] = m.get(i, j);
            }
        }
        return result;
    }

    /**
     * 从对角线值创建对角矩阵。
     *
     * @param values 对角线元素
     * @return 对角矩阵
     */
    public static SimpleMatrix diag(double[] values) {
        int n = values.length;
        SimpleMatrix m = new SimpleMatrix(n, n);
        for (int i = 0; i < n; i++) {
            m.set(i, i, values[i]);
        }
        return m;
    }

    /**
     * 计算矩阵迹（对角线元素之和）。
     *
     * @param m 输入矩阵
     * @return 迹
     */
    public static double trace(SimpleMatrix m) {
        double tr = 0.0;
        int n = Math.min(m.numRows(), m.numCols());
        for (int i = 0; i < n; i++) {
            tr += m.get(i, i);
        }
        return tr;
    }
}