package org.rtklib.java.data;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * [Java扩展] DIAG_QPOS 协方差数据源切换测试。
 *
 * <p>钉死两条不变量：</p>
 * <ul>
 *   <li>开关关闭（{@code sol.qPos == null}）时，{@code covEcef} 与改造前逐位一致
 *       （仍由 {@code sol.qr} 展开）；</li>
 *   <li>{@link Accuracy} 始终读 {@code sol.qr}，不随 {@code covEcef} 的数据源切换而变。</li>
 * </ul>
 */
class SolDataCovSourceTest {

    private static Sol baseSol() {
        Sol sol = new Sol();
        sol.time = new GTime();
        sol.rr = new double[]{-2.2e6, 4.4e6, 4.6e6, 0, 0, 0};
        sol.type = (byte) 0;
        sol.stat = (byte) SolutionStatus.FIX.code;
        sol.ns = (byte) 12;
        sol.age = 0.0f;
        sol.ratio = 5.0f;
        sol.dtr = new double[]{0};
        sol.qv = new float[]{0, 0, 0, 0, 0, 0};
        return sol;
    }

    private static final float[] QR = {1.0e-4f, 2.0e-4f, 3.0e-4f, -1.0e-5f, 0.0f, 0.0f};

    @Test
    void qPosNullKeepsLegacyQrSource() {
        Sol sol = baseSol();
        sol.qr = QR.clone();
        sol.qPos = null;

        SolData solData = new SolData(sol, PrcOpt.POS_ECEF);

        assertNotNull(solData.covEcef);
        assertEquals(1.0e-4, solData.covEcef[0], 1e-9);
        assertEquals(2.0e-4, solData.covEcef[4], 1e-9);
        assertEquals(3.0e-4, solData.covEcef[8], 1e-9);
        assertEquals(-1.0e-5, solData.covEcef[1], 1e-9);
        assertEquals(-1.0e-5, solData.covEcef[3], 1e-9);
    }

    @Test
    void qPosTakesPriorityOverQr() {
        Sol sol = baseSol();
        sol.qr = QR.clone();
        sol.qPos = new double[]{9.0e-4, 0.0, 0.0, 0.0, 8.0e-4, 0.0, 0.0, 0.0, 7.0e-4};

        SolData solData = new SolData(sol, PrcOpt.POS_ECEF);

        assertNotNull(solData.qPos);
        assertNotNull(solData.covEcef);
        assertArrayEquals(sol.qPos, solData.covEcef, 1e-15);
        assertEquals(9.0e-4, solData.covEcef[0], 1e-15);
        assertNotEquals(1.0e-4, solData.covEcef[0], 1e-12, "不应再来自 qr");
    }

    @Test
    void accuracyStillReadsQrWhenQPosPresent() {
        Sol withQPos = baseSol();
        withQPos.qr = QR.clone();
        withQPos.qPos = new double[]{9.0e-4, 0.0, 0.0, 0.0, 8.0e-4, 0.0, 0.0, 0.0, 7.0e-4};

        Sol withoutQPos = baseSol();
        withoutQPos.qr = QR.clone();
        withoutQPos.qPos = null;

        Accuracy a1 = new SolData(withQPos, PrcOpt.POS_ECEF).getAccuracy(CoordType.ECEF);
        Accuracy a2 = new SolData(withoutQPos, PrcOpt.POS_ECEF).getAccuracy(CoordType.ECEF);

        assertNotNull(a1);
        assertNotNull(a2);
        assertEquals(a2.s1, a1.s1, 0.0, "Accuracy 必须仍来自 qr");
        assertEquals(a2.s2, a1.s2, 0.0);
        assertEquals(a2.s3, a1.s3, 0.0);
        assertEquals(a2.c12, a1.c12, 0.0);
        assertEquals(a2.c23, a1.c23, 0.0);
        assertEquals(a2.c31, a1.c31, 0.0);
    }

    @Test
    void malformedQPosIsIgnored() {
        Sol sol = baseSol();
        sol.qr = QR.clone();
        sol.qPos = new double[]{1.0, 2.0, 3.0};

        SolData solData = new SolData(sol, PrcOpt.POS_ECEF);

        assertEquals(1.0e-4, solData.covEcef[0], 1e-9, "长度非9时回退到 qr");
    }

    @Test
    void solCopyConstructorDeepCopiesQPos() {
        Sol src = baseSol();
        src.qPos = new double[]{1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0};

        Sol copy = new Sol(src);
        assertNotNull(copy.qPos);
        assertArrayEquals(src.qPos, copy.qPos, 0.0);

        copy.qPos[0] = 100.0;
        assertEquals(1.0, src.qPos[0], 0.0, "拷贝必须是深拷贝");
    }

    @Test
    void solDataCovEcefIsIndependentSnapshot() {
        Sol sol = baseSol();
        sol.qr = QR.clone();
        sol.qPos = new double[]{9.0e-4, 0.0, 0.0, 0.0, 8.0e-4, 0.0, 0.0, 0.0, 7.0e-4};

        SolData solData = new SolData(sol, PrcOpt.POS_ECEF);
        sol.qPos[0] = 123.0;

        assertEquals(9.0e-4, solData.covEcef[0], 1e-15,
                "covEcef 必须是构造时的快照，不能被后续写入污染");
    }
}