package org.rtklib.java;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.rtklib.java.common.SatUtils;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.coord.CoordTransform;
import org.rtklib.java.data.*;
import org.rtklib.java.pntpos.PntPos;
import org.rtklib.java.pntpos.SppProcessor;
import org.rtklib.java.rinex.RinexParser;
import org.rtklib.java.rtcm.*;
import org.rtklib.java.time.TimeSystem;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WUH20 NTRIP 录制流（RTCM3 MSM7）离线解码 + SPP 测试。
 * <p>
 * 数据：test-data/gnss-validation/rtcm/record_wuh20.msm.rtcm3
 * （2026-10-23 01:45:16 UTC 起 24 s，207 帧，含 1077/1087/1097/1107/1117/1127/1137
 * 共 7 系统 MSM7，以及 1006/1007/1008/1033 站点信息；流内不含广播星历）。
 * </p>
 * <p>
 * 第一部分只验证离线解码：MSM7 的伪距/载波/多普勒/信噪比是否被正确填充。
 * 第二部分做 SPP：广播星历必须来自外部 BRDC（当天文件），路径按以下顺序解析：
 * 环境变量 WUH20_BRDC → test-data/gnss-validation/brdc/ 下含 "296" 的 RINEX nav
 * → test-data/gnss-validation 下含 "296" 的 RINEX nav。找不到时跳过 SPP 用例。
 * 当前 brdc/ 下为 WHU FTP（ftp://igs.gnsswhu.cn/pub/gps/data/hourly/2026/296/）
 * 的 hour2960.26n（2026-10-23 00/02 时段的 GPS 广播星历），仅含 GPS，
 * 因此 SPP 只使用 GPS 卫星解算。
 * </p>
 */
@DisplayName("WUH20 MSM7 离线解码 + SPP")
class Wuh20MsmSppTest {

    /** 录制时刻对应的 GPS 周（2026-10-23 星期五，TOW 438316 s）。 */
    private static final int GPS_WEEK = 2441;

    /**
     * 录制文件路径：环境变量 {@code WUH20_RTCM} 优先，否则取本地测试数据目录的默认位置。
     * 文件不存在时由各用例的 assumeTrue 跳过，CI 上恒为 skip。
     */
    private static File resolveRecord() {
        String env = System.getenv("WUH20_RTCM");
        if (env != null && !env.isEmpty()) return new File(env);
        return new File(TestDataConfig.getTestDataDir()
                + File.separator + "gnss-validation" + File.separator + "rtcm"
                + File.separator + "record_wuh20.msm.rtcm3");
    }

    @Test
    @DisplayName("MSM7 观测值离线解码")
    void decodeMsm7Observations() throws Exception {
        File record = resolveRecord();
        Assumptions.assumeTrue(record.isFile(), "缺少 WUH20 录制文件: " + record);

        byte[] raw = Files.readAllBytes(record.toPath());
        CollectingHandler handler = new CollectingHandler();
        RtcmCallbackDecoder decoder = new RtcmCallbackDecoder(handler);
        decoder.feed(raw, 0, raw.length);
        decoder.finish();

        System.out.println("[WUH20] bytes=" + raw.length
                + " frames=" + handler.typeCounts.values().stream().mapToInt(Integer::intValue).sum()
                + " epochs=" + handler.epochs.size());
        System.out.println("[WUH20] message types: " + handler.typeCounts);
        if (handler.station != null) {
            System.out.println(String.format("[WUH20] 1006 station ECEF: %.4f %.4f %.4f",
                    handler.station.pos[0], handler.station.pos[1], handler.station.pos[2]));
        }

        assertTrue(handler.epochs.size() >= 20, "MSM7 历元数不足: " + handler.epochs.size());
        assertTrue(handler.epochs.get(0).obsList.size() > 0, "首个历元无观测值");

        int nP = 0, nL = 0, nD = 0, nSnr = 0;
        for (ObservationEpoch epoch : handler.epochs) {
            for (Obsd o : epoch.obsList) {
                for (int j = 0; j < Constants.NFREQ; j++) {
                    if (o.P[j] != 0.0) nP++;
                    if (o.L[j] != 0.0) nL++;
                    if (o.D[j] != 0.0f) nD++;
                    if (o.SNR[j] != 0.0f) nSnr++;
                }
            }
        }
        System.out.println("[WUH20] obs counts: P=" + nP + " L=" + nL
                + " D=" + nD + " SNR=" + nSnr);
        assertTrue(nP > 0, "未解出任何伪距");
        assertTrue(nL > 0, "未解出任何载波相位");

        ObservationEpoch last = handler.epochs.get(handler.epochs.size() - 1);
        System.out.println(String.format("[WUH20] last epoch t=%.3f s-of-week, sats=%d",
                TimeSystem.time2gpst(last.time, new int[1]), last.obsList.size()));
        for (Obsd o : last.obsList) {
            System.out.println(String.format("[WUH20]   %s P1=%.3f L1=%.3f D1=%.3f SNR1=%.1f",
                    SatUtils.satno2id(o.sat), o.P[0], o.L[0], o.D[0], o.SNR[0]));
        }
    }

    @Test
    @DisplayName("MSM7 观测 + BRDC 星历解算 SPP")
    void solveSppWithBroadcastEphemeris() throws Exception {
        File record = resolveRecord();
        Assumptions.assumeTrue(record.isFile(), "缺少 WUH20 录制文件: " + record);

        File navFile = findBrdc();
        Assumptions.assumeTrue(navFile != null,
                "缺少 2026-296 广播星历；请将 BRDC00IGS_R_20262960000_01D_MN.rnx 放到 "
                        + "test-data/gnss-validation/brdc/ 或设置环境变量 WUH20_BRDC");

        byte[] raw = Files.readAllBytes(record.toPath());
        CollectingHandler handler = new CollectingHandler();
        RtcmCallbackDecoder decoder = new RtcmCallbackDecoder(handler);
        decoder.feed(raw, 0, raw.length);
        decoder.finish();

        RinexParser navParser = new RinexParser();
        assertTrue(navParser.parseNav(navFile.getAbsolutePath()), "BRDC 解析失败: " + navFile);
        Nav nav = navParser.nav;
        System.out.println("[WUH20] BRDC=" + navFile.getName() + " eph=" + nav.n
                + " geph=" + nav.ng + " gal=" + nav.ne);

        PrcOpt opt = SppProcessor.createDefaultOpt();
        opt.navsys |= Constants.SYS_QZS;

        List<double[]> solved = new ArrayList<>();
        Ssat[] ssat = new Ssat[Constants.MAXSAT];
        for (int i = 0; i < ssat.length; i++) {
            ssat[i] = new Ssat();
        }
        for (ObservationEpoch epoch : handler.epochs) {
            int[] wk = new int[1];
            double tow = TimeSystem.time2gpst(epoch.time, wk);
            if (wk[0] != GPS_WEEK) {
                GTime fixed = TimeSystem.gpst2time(GPS_WEEK, tow);
                epoch.time = fixed;
                for (Obsd o : epoch.obsList) {
                    o.time = new GTime(fixed);
                }
            }

            Obsd[] obs = epoch.obsList.toArray(new Obsd[0]);
            Sol sol = new Sol();
            double[] azel = new double[obs.length * 2];
            if (PntPos.pntpos(obs, obs.length, nav, opt, sol, azel, ssat) == 1) {
                solved.add(new double[]{sol.rr[0], sol.rr[1], sol.rr[2]});
            }
        }

        System.out.println("[WUH20] SPP solved " + solved.size() + "/" + handler.epochs.size()
                + " epochs");
        assertTrue(solved.size() >= 10, "SPP 成功历元数过少: " + solved.size());

        double[] mean = new double[3];
        for (double[] p : solved) {
            mean[0] += p[0];
            mean[1] += p[1];
            mean[2] += p[2];
        }
        mean[0] /= solved.size();
        mean[1] /= solved.size();
        mean[2] /= solved.size();

        double sumSq = 0.0;
        for (double[] p : solved) {
            double dx = p[0] - mean[0], dy = p[1] - mean[1], dz = p[2] - mean[2];
            sumSq += dx * dx + dy * dy + dz * dz;
        }
        double rms = Math.sqrt(sumSq / solved.size());

        double[] pos = new double[3];
        CoordTransform.ecef2pos(mean, pos);
        System.out.println(String.format("[WUH20] SPP mean ECEF: %.3f %.3f %.3f",
                mean[0], mean[1], mean[2]));
        System.out.println(String.format("[WUH20] SPP mean LLH: lat=%.8f deg lon=%.8f deg h=%.3f m",
                pos[0] / Constants.D2R, pos[1] / Constants.D2R, pos[2]));
        System.out.println(String.format("[WUH20] epoch-to-epoch 3D scatter RMS: %.3f m", rms));

        double[] lastSol = solved.get(solved.size() - 1);
        CoordTransform.ecef2pos(lastSol, pos);
        System.out.println(String.format("[WUH20] last epoch LLH: lat=%.8f lon=%.8f h=%.3f m",
                pos[0] / Constants.D2R, pos[1] / Constants.D2R, pos[2]));

        if (handler.station != null) {
            double[] truth = handler.station.pos;
            double[] posT = new double[3];
            CoordTransform.ecef2pos(truth, posT);
            double[] enu = new double[9];
            CoordTransform.xyz2enu(posT, enu);
            double[] d = {mean[0] - truth[0], mean[1] - truth[1], mean[2] - truth[2]};
            double e = enu[0] * d[0] + enu[1] * d[1] + enu[2] * d[2];
            double n = enu[3] * d[0] + enu[4] * d[1] + enu[5] * d[2];
            double u = enu[6] * d[0] + enu[7] * d[1] + enu[8] * d[2];
            System.out.println(String.format(
                    "[WUH20] mean - 1006 station: E=%.3f N=%.3f U=%.3f m, 3D=%.3f m",
                    e, n, u, Math.sqrt(e * e + n * n + u * u)));
        }
    }

    private static File findBrdc() {
        String env = System.getenv("WUH20_BRDC");
        if (env != null && !env.isEmpty()) {
            File f = new File(env);
            if (f.isFile()) return f;
        }
        File base = new File(TestDataConfig.getTestDataDir(), "gnss-validation");
        File brdcDir = new File(base, "brdc");
        // 录制日 DOY 296 的星历优先；当天文件若尚未生成则退到 DOY 295 的日文件
        for (String doy : new String[]{"296", "295"}) {
            File found = scan(brdcDir, doy);
            if (found == null) found = scan(base, doy);
            if (found != null) return found;
        }
        return null;
    }

    private static File scan(File dir, String doy) {
        if (dir == null || !dir.isDirectory()) return null;
        File[] files = dir.listFiles();
        if (files == null) return null;
        for (File f : files) {
            String name = f.getName().toUpperCase();
            if (name.contains(doy) && (name.endsWith(".RNX") || name.endsWith(".NAV")
                    || name.endsWith(".26N") || name.endsWith(".26C"))) {
                return f;
            }
        }
        for (File f : files) {
            if (f.isDirectory()) {
                File r = scan(f, doy);
                if (r != null) return r;
            }
        }
        return null;
    }

    private static final class CollectingHandler implements RtcmDataHandler {
        final List<ObservationEpoch> epochs = new ArrayList<>();
        final Map<Integer, Integer> typeCounts = new TreeMap<>();
        Sta station;

        @Override
        public void onStation(Sta sta) {
            this.station = sta;
        }

        @Override
        public void onSsr(Ssr ssr) {
        }

        @Override
        public void onEph(Eph eph) {
        }

        @Override
        public void onGeph(Geph geph) {
        }

        @Override
        public void onObservationEpoch(ObservationEpoch epoch) {
            epochs.add(epoch);
        }

        @Override
        public void onAuxData(AuxData aux) {
        }

        @Override
        public void onFinish() {
        }

        @Override
        public void onRtcmMessageType(int type, int length) {
            typeCounts.merge(type, 1, Integer::sum);
        }
    }
}

