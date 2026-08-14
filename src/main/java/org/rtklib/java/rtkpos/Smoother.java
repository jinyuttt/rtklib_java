package org.rtklib.java.rtkpos;

import org.ejml.simple.SimpleMatrix;
import org.rtklib.java.common.MatrixUtil;

public final class Smoother {

    private Smoother() {
    }

    public static int smooth(double[] xf, double[] Qf, double[] xb, double[] Qb,
                             int n, double[] xs, double[] Qs) {
        SimpleMatrix QfMat = new SimpleMatrix(n, n);
        SimpleMatrix QbMat = new SimpleMatrix(n, n);
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                QfMat.set(i, j, Qf[i * n + j]);
                QbMat.set(i, j, Qb[i * n + j]);
            }
        }

        SimpleMatrix QfInv, QbInv;
        try {
            QfInv = QfMat.invert();
            QbInv = QbMat.invert();
        } catch (Exception e) {
            return -1;
        }

        SimpleMatrix QsInv = QfInv.plus(QbInv);
        SimpleMatrix QsMat;
        try {
            QsMat = QsInv.invert();
        } catch (Exception e) {
            return -1;
        }

        SimpleMatrix xfMat = new SimpleMatrix(n, 1);
        SimpleMatrix xbMat = new SimpleMatrix(n, 1);
        for (int i = 0; i < n; i++) {
            xfMat.set(i, 0, xf[i]);
            xbMat.set(i, 0, xb[i]);
        }

        SimpleMatrix QfInvXf = QfInv.mult(xfMat);
        SimpleMatrix QbInvXb = QbInv.mult(xbMat);
        SimpleMatrix xx = QfInvXf.plus(QbInvXb);
        SimpleMatrix xsMat = QsMat.mult(xx);

        for (int i = 0; i < n; i++) {
            xs[i] = xsMat.get(i, 0);
        }
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                Qs[i * n + j] = QsMat.get(i, j);
            }
        }

        return 0;
    }

    public static double[] buildCovMatrix(float[] qr) {
        double[] Q = new double[9];
        Q[0] = qr[0]; Q[1] = qr[3]; Q[2] = qr[5];
        Q[3] = qr[3]; Q[4] = qr[1]; Q[5] = qr[4];
        Q[6] = qr[5]; Q[7] = qr[4]; Q[8] = qr[2];
        return Q;
    }

    public static void extractCovMatrix(double[] Q, float[] qr) {
        qr[0] = (float) Q[0];
        qr[1] = (float) Q[4];
        qr[2] = (float) Q[8];
        qr[3] = (float) Q[1];
        qr[4] = (float) Q[5];
        qr[5] = (float) Q[2];
    }
}