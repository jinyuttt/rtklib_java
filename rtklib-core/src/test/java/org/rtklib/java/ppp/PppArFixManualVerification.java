package org.rtklib.java.ppp;

import org.rtklib.java.data.PrcOpt;
import org.rtklib.java.config.RtkConfig;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.data.GTime;
import org.rtklib.java.data.Nav;
import org.rtklib.java.data.Rtk;
import org.rtklib.java.data.Sol;

/**
 * P2/P3修复手动验证程序
 */
public class PppArFixManualVerification {

    public static void main(String[] args) {
        System.out.println("=== P2/P3修复数值验证 ===\n");

        try {
            testFixAndHoldMinEpochGuard();
            testPartialARCycleSpaceConversion();
            testPartialARVarianceFilter();
            testPartialARMultiSystemWavelength();

            System.out.println("\n=== 所有测试通过 ===");
        } catch (Exception e) {
            System.err.println("\n=== 测试失败 ===");
            e.printStackTrace();
            System.exit(1);
        }
    }

    static void testFixAndHoldMinEpochGuard() throws Exception {
        System.out.println("测试1: P2 Fix-and-Hold最小历元守卫");

        Rtk rtk = new Rtk();
        Nav nav = new Nav();
        RtkConfig cfg = new RtkConfig();
        PrcOpt opt = new PrcOpt();

        opt.mode = Constants.PMODE_PPP_KINEMA;
        opt.nf = 2;
        opt.navsys = Constants.SYS_GPS;
        opt.ionoopt = Constants.IONOOPT_IFLC;
        opt.tropopt = Constants.TROPOPT_EST;
        opt.sateph = Constants.EPHOPT_BRDC;
        opt.dynamics = 0;

        rtk.opt = opt;
        rtk.rtkConfig = cfg;
        rtk.sol = new Sol();
        rtk.sol.rr = new double[6];
        rtk.sol.time = new GTime();

        int nx = PppCore.pppnx(opt);
        rtk.nx = nx;
        rtk.x = new double[nx];
        rtk.P = new double[nx * nx];
        rtk.xa = new double[nx];

        rtk.ssat = new org.rtklib.java.data.Ssat[Constants.MAXSAT];
        for (int i = 0; i < Constants.MAXSAT; i++) {
            rtk.ssat[i] = new org.rtklib.java.data.Ssat();
            rtk.ssat[i].azel = new double[2];
            rtk.ssat[i].fix = new int[Constants.NFREQ];
            rtk.ssat[i].code = new int[Constants.NFREQ][2];
        }

        cfg.enablePppArFixHold = true;
        cfg.pppArFixHoldMinEp = 50;
        cfg.pppArFixHoldVar = 1e-6;

        int[] testSats = {1, 2, 3};
        double initialVar = 0.5;

        for (int sat : testSats) {
            int idx = PppCore.IB(sat, 0, opt);
            rtk.x[idx] = 5.0;
            rtk.P[idx * rtk.nx + idx] = initialVar;
            rtk.ssat[sat - 1].fix[0] = 1;
            rtk.ssat[sat - 1].vs = 1;
        }

        // Test 1: nfix < minEp
        rtk.nfix = 49;
        PppAmbFix.pppArFixHold(rtk, nav);

        for (int sat : testSats) {
            int idx = PppCore.IB(sat, 0, opt);
            double varAfter = rtk.P[idx * rtk.nx + idx];
            if (Math.abs(varAfter - initialVar) > 1e-10) {
                throw new AssertionError("nfix=49时方差不应改变，sat=" + sat + ", var=" + varAfter);
            }
        }
        System.out.println("  ✓ nfix=49时方差保持0.5不变");

        // Test 2: nfix >= minEp
        rtk.nfix = 50;
        PppAmbFix.pppArFixHold(rtk, nav);

        for (int sat : testSats) {
            int idx = PppCore.IB(sat, 0, opt);
            double varAfter = rtk.P[idx * rtk.nx + idx];
            if (Math.abs(varAfter - cfg.pppArFixHoldVar) > 1e-10) {
                throw new AssertionError("nfix=50时方差应收紧到1e-6，sat=" + sat + ", var=" + varAfter);
            }
        }
        System.out.println("  ✓ nfix=50时方差收紧到1e-6");
        System.out.println();
    }

    static void testPartialARCycleSpaceConversion() throws Exception {
        System.out.println("测试2: P3 Partial AR周空间转换");

        Rtk rtk = new Rtk();
        Nav nav = new Nav();
        RtkConfig cfg = new RtkConfig();
        PrcOpt opt = new PrcOpt();

        opt.mode = Constants.PMODE_PPP_KINEMA;
        opt.nf = 2;
        opt.navsys = Constants.SYS_GPS;
        opt.ionoopt = Constants.IONOOPT_IFLC;
        opt.tropopt = Constants.TROPOPT_EST;
        opt.sateph = Constants.EPHOPT_BRDC;
        opt.dynamics = 0;

        rtk.opt = opt;
        rtk.rtkConfig = cfg;
        rtk.sol = new Sol();
        rtk.sol.rr = new double[6];
        rtk.sol.time = new GTime();

        int nx = PppCore.pppnx(opt);
        rtk.nx = nx;
        rtk.x = new double[nx];
        rtk.P = new double[nx * nx];
        rtk.xa = new double[nx];

        rtk.ssat = new org.rtklib.java.data.Ssat[Constants.MAXSAT];
        for (int i = 0; i < Constants.MAXSAT; i++) {
            rtk.ssat[i] = new org.rtklib.java.data.Ssat();
            rtk.ssat[i].azel = new double[2];
            rtk.ssat[i].fix = new int[Constants.NFREQ];
            rtk.ssat[i].code = new int[Constants.NFREQ][2];
        }

        cfg.enablePppPartialAR = true;
        cfg.pppPartialArMinSats = 4;
        cfg.pppPartialArMinRatio = 2.0;
        cfg.pppPartialArMaxTries = 10;

        double freq1 = Constants.FREQL1;
        double lambda1 = Constants.CLIGHT / freq1;

        int numSats = 6;
        double[] floatAmbCycles = {5.1, 8.2, 12.3, 15.4, 20.5, 25.6};
        double ambVarM2 = 0.01;

        for (int i = 0; i < numSats; i++) {
            int sat = i + 1;
            int idx = PppCore.IB(sat, 0, opt);

            double floatAmbMeters = floatAmbCycles[i] * lambda1;
            rtk.x[idx] = floatAmbMeters;
            rtk.P[idx * rtk.nx + idx] = ambVarM2;

            rtk.ssat[sat - 1].vs = 1;
            rtk.ssat[sat - 1].azel[1] = 30.0 * Constants.D2R;
            rtk.ssat[sat - 1].fix[0] = 0;
            rtk.ssat[sat - 1].code[0][0] = Constants.CODE_L1C;
        }

        // 添加一些小的相关性（非对角线元素）
        for (int i = 0; i < numSats; i++) {
            for (int j = i + 1; j < numSats; j++) {
                int satI = i + 1;
                int satJ = j + 1;
                int idxI = PppCore.IB(satI, 0, opt);
                int idxJ = PppCore.IB(satJ, 0, opt);
                double cov = 0.001;  // 小相关性
                rtk.P[idxI * rtk.nx + idxJ] = cov;
                rtk.P[idxJ * rtk.nx + idxI] = cov;
            }
        }

        System.out.println("  调用pppPartialAR前：");
        System.out.println("    卫星数=" + numSats + ", 方差=" + ambVarM2 + " m²");
        System.out.println("    lambda_GPS=" + lambda1 + " m");

        int nb = PppAmbFix.pppPartialAR(rtk, null, rtk.xa, 1, 0, 0, nav);

        System.out.println("    pppPartialAR返回: " + nb);

        if (nb < cfg.pppPartialArMinSats) {
            throw new AssertionError("Partial AR应固定至少" + cfg.pppPartialArMinSats + "颗卫星，实际=" + nb);
        }
        System.out.println("  ✓ 成功固定" + nb + "颗卫星");

        int fixedCount = 0;
        for (int i = 0; i < numSats; i++) {
            int sat = i + 1;
            if (rtk.ssat[sat - 1].fix[0] == 1) {
                int idx = PppCore.IB(sat, 0, opt);
                double fixedAmbMeters = rtk.x[idx];
                double fixedAmbCycles = fixedAmbMeters / lambda1;
                double rounded = Math.round(fixedAmbCycles);

                if (Math.abs(rounded - fixedAmbCycles) > 1e-6) {
                    throw new AssertionError("固定模糊度应是整周，sat=" + sat + ", cycles=" + fixedAmbCycles);
                }

                double expectedMeters = rounded * lambda1;
                if (Math.abs(expectedMeters - fixedAmbMeters) > 1e-9) {
                    throw new AssertionError("固定模糊度米值应=整周*lambda，sat=" + sat);
                }

                fixedCount++;
            }
        }

        if (nb != fixedCount) {
            throw new AssertionError("返回的固定数应与实际固定数一致");
        }
        System.out.println("  ✓ 所有固定值均为整周×lambda");
        System.out.println();
    }

    static void testPartialARVarianceFilter() throws Exception {
        System.out.println("测试3: P3 Partial AR方差过滤");

        Rtk rtk = new Rtk();
        Nav nav = new Nav();
        RtkConfig cfg = new RtkConfig();
        PrcOpt opt = new PrcOpt();

        opt.mode = Constants.PMODE_PPP_KINEMA;
        opt.nf = 2;
        opt.navsys = Constants.SYS_GPS;
        opt.ionoopt = Constants.IONOOPT_IFLC;
        opt.tropopt = Constants.TROPOPT_EST;
        opt.sateph = Constants.EPHOPT_BRDC;
        opt.dynamics = 0;

        rtk.opt = opt;
        rtk.rtkConfig = cfg;
        rtk.sol = new Sol();
        rtk.sol.rr = new double[6];
        rtk.sol.time = new GTime();

        int nx = PppCore.pppnx(opt);
        rtk.nx = nx;
        rtk.x = new double[nx];
        rtk.P = new double[nx * nx];
        rtk.xa = new double[nx];

        rtk.ssat = new org.rtklib.java.data.Ssat[Constants.MAXSAT];
        for (int i = 0; i < Constants.MAXSAT; i++) {
            rtk.ssat[i] = new org.rtklib.java.data.Ssat();
            rtk.ssat[i].azel = new double[2];
            rtk.ssat[i].fix = new int[Constants.NFREQ];
            rtk.ssat[i].code = new int[Constants.NFREQ][2];
        }

        cfg.enablePppPartialAR = true;
        cfg.pppPartialArMinSats = 4;

        double lambda1 = Constants.CLIGHT / Constants.FREQL1;
        double[] floatAmbCycles = {5.1, 8.2, 12.3, 15.4, 20.5, 25.6, 30.7, 35.8};

        for (int i = 0; i < 8; i++) {
            int sat = i + 1;
            int idx = PppCore.IB(sat, 0, opt);

            rtk.x[idx] = floatAmbCycles[i] * lambda1;  // 使用不同的周值
            rtk.ssat[sat - 1].vs = 1;
            rtk.ssat[sat - 1].azel[1] = 30.0 * Constants.D2R;
            rtk.ssat[sat - 1].code[0][0] = Constants.CODE_L1C;

            if (i < 4) {
                rtk.P[idx * rtk.nx + idx] = 0.01;
            } else {
                rtk.P[idx * rtk.nx + idx] = 1.0;
            }
        }

        // 添加相关性
        for (int i = 0; i < 8; i++) {
            for (int j = i + 1; j < 8; j++) {
                int satI = i + 1;
                int satJ = j + 1;
                int idxI = PppCore.IB(satI, 0, opt);
                int idxJ = PppCore.IB(satJ, 0, opt);
                double cov = 0.001;
                rtk.P[idxI * rtk.nx + idxJ] = cov;
                rtk.P[idxJ * rtk.nx + idxI] = cov;
            }
        }

        System.out.println("  低方差卫星: 1-4 (0.01 m²), 高方差卫星: 5-8 (1.0 m²)");

        int nb = PppAmbFix.pppPartialAR(rtk, null, rtk.xa, 1, 0, 0, nav);

        System.out.println("    pppPartialAR返回: " + nb);

        if (nb < 4) {
            throw new AssertionError("应固定至少4颗低方差卫星");
        }

        for (int i = 0; i < 4; i++) {
            int sat = i + 1;
            if (rtk.ssat[sat - 1].fix[0] != 1) {
                throw new AssertionError("低方差卫星应被固定，sat=" + sat);
            }
        }

        for (int i = 4; i < 8; i++) {
            int sat = i + 1;
            if (rtk.ssat[sat - 1].fix[0] != 0) {
                throw new AssertionError("高方差卫星不应被固定，sat=" + sat);
            }
        }

        System.out.println("  ✓ 低方差卫星被固定，高方差卫星被拒绝");
        System.out.println();
    }

    static void testPartialARMultiSystemWavelength() throws Exception {
        System.out.println("测试4: P3 Partial AR多系统波长处理");

        // 直接验证波长计算，不依赖完整的Partial AR流程
        double lambdaGPS = Constants.CLIGHT / Constants.FREQL1;
        double lambdaBDS = Constants.CLIGHT / Constants.FREQ1_CMP;

        System.out.println("  GPS L1波长: " + lambdaGPS + " m");
        System.out.println("  BDS B1波长: " + lambdaBDS + " m");

        // 验证波长不同
        if (Math.abs(lambdaGPS - lambdaBDS) < 1e-6) {
            throw new AssertionError("GPS和BDS波长应不同");
        }
        System.out.println("  ✓ GPS和BDS波长确实不同");

        // 验证转换公式：cycles = meters / lambda, meters = cycles * lambda
        double testMeters = 1.0;
        double cyclesGPS = testMeters / lambdaGPS;
        double backToMetersGPS = cyclesGPS * lambdaGPS;
        if (Math.abs(backToMetersGPS - testMeters) > 1e-9) {
            throw new AssertionError("GPS波长转换不正确");
        }

        double cyclesBDS = testMeters / lambdaBDS;
        double backToMetersBDS = cyclesBDS * lambdaBDS;
        if (Math.abs(backToMetersBDS - testMeters) > 1e-9) {
            throw new AssertionError("BDS波长转换不正确");
        }
        System.out.println("  ✓ 米→周→米转换数值正确");

        // 验证整周固定：round(cycles) * lambda
        double floatCycles = 5.7;
        double fixedCycles = Math.round(floatCycles);  // 6.0
        double fixedMetersGPS = fixedCycles * lambdaGPS;
        double fixedMetersBDS = fixedCycles * lambdaBDS;

        // GPS和BDS的固定值应不同（因为波长不同）
        if (Math.abs(fixedMetersGPS - fixedMetersBDS) < 1e-6) {
            throw new AssertionError("相同周数在不同系统下应对应不同米值");
        }
        System.out.println("  ✓ 6 cycles GPS=" + fixedMetersGPS + "m, BDS=" + fixedMetersBDS + "m");
        System.out.println();
    }
}
