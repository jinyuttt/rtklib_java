package org.rtklib.java.research.common;

import org.ejml.simple.SimpleMatrix;

/**
 * 矩阵运算封装（research模块自有）。
 *
 * <p>基于EJML SimpleMatrix，提供GNSS计算常用矩阵操作。</p>
 */
public final class MatrixOps {
    private MatrixOps() {}

    public static int rows(SimpleMatrix m) {
        return m.getDDRM().numRows;
    }

    public static int cols(SimpleMatrix m) {
        return m.getDDRM().numCols;
    }

    public static SimpleMatrix identity(int n) {
        return SimpleMatrix.identity(n);
    }

    public static SimpleMatrix zeros(int rows, int cols) {
        return new SimpleMatrix(rows, cols);
    }

    public static SimpleMatrix diag(double... values) {
        SimpleMatrix m = new SimpleMatrix(values.length, values.length);
        for (int i = 0; i < values.length; i++) {
            m.set(i, i, values[i]);
        }
        return m;
    }

    public static SimpleMatrix fromColumnMajor(double[] data, int rows, int cols) {
        SimpleMatrix m = new SimpleMatrix(rows, cols);
        for (int c = 0; c < cols; c++) {
            for (int r = 0; r < rows; r++) {
                m.set(r, c, data[c * rows + r]);
            }
        }
        return m;
    }

    public static SimpleMatrix fromRowMajor(double[] data, int rows, int cols) {
        SimpleMatrix m = new SimpleMatrix(rows, cols);
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                m.set(r, c, data[r * cols + c]);
            }
        }
        return m;
    }

    public static double[] toRowMajor(SimpleMatrix m) {
        int r = rows(m);
        int c = cols(m);
        double[] data = new double[r * c];
        for (int i = 0; i < r; i++) {
            for (int j = 0; j < c; j++) {
                data[i * c + j] = m.get(i, j);
            }
        }
        return data;
    }

    public static SimpleMatrix blockDiag(SimpleMatrix... matrices) {
        int totalRows = 0, totalCols = 0;
        for (SimpleMatrix m : matrices) {
            totalRows += rows(m);
            totalCols += cols(m);
        }
        SimpleMatrix result = new SimpleMatrix(totalRows, totalCols);
        int rowOff = 0, colOff = 0;
        for (SimpleMatrix m : matrices) {
            result.insertIntoThis(rowOff, colOff, m);
            rowOff += rows(m);
            colOff += cols(m);
        }
        return result;
    }

    public static SimpleMatrix extractSubvector(SimpleMatrix v, int start, int length) {
        return v.extractMatrix(start, start + length, 0, 1);
    }
}