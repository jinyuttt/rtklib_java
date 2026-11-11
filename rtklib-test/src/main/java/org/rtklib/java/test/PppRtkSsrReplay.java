package org.rtklib.java.test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import org.rtklib.java.ai.OnnxAiProvider;
import org.rtklib.java.ai.spi.AiHooks;
import org.rtklib.java.common.ObsCode;
import org.rtklib.java.common.SatUtils;
import org.rtklib.java.config.RtkConfig;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.coord.CoordTransform;
import org.rtklib.java.data.GTime;
import org.rtklib.java.data.Nav;
import org.rtklib.java.data.Obs;
import org.rtklib.java.data.Obsd;
import org.rtklib.java.data.PrcOpt;
import org.rtklib.java.data.Rtk;
import org.rtklib.java.data.Sol;
import org.rtklib.java.data.Eph;
import org.rtklib.java.data.Ssr;
import org.rtklib.java.ppp.PppCore;
import org.rtklib.java.ppp.PppProcessor;
import org.rtklib.java.rinex.RinexParser;
import org.rtklib.java.rtcm.Rtcm;
import org.rtklib.java.time.TimeSystem;

public final class PppRtkSsrReplay {

    /** SSR 目录与 RINEX 根目录均无内置默认值，必须由系统属性或命令行参数提供。 */
    private static final String DEFAULT_SSR_DIR = "";
    private static final String DEFAULT_RINEX_ROOT = "";
    private static final String DEFAULT_STATION = "";
    /** RINEX 小时文件按本地时（UTC+8）命名，SSR 按 UTC 小时命名，两者相差 8 小时。 */
    private static final int LOCAL_HOUR_OFFSET = 8;

    /** SSR 文件目录（UTC 小时命名的 HH.rtcm3）。 */
    private static String ssrDir = System.getProperty("rtk.ssrDir", DEFAULT_SSR_DIR);
    /** RINEX 根目录，含义随 {@link #rinexLayout} 变化。 */
    private static String rinexRoot = System.getProperty("rtk.rinexRoot", DEFAULT_RINEX_ROOT);
    /**
     * RINEX 目录布局：
     * <ul>
     *   <li>{@code hour}：{@code <root>\<station>\<localHH>\<station>.obs}（外部抓取产物）</li>
     *   <li>{@code post}：{@code <root>\<station>\<dateDir>\<localHH>.obs}（AutoRtkPostService 实时落盘产物）</li>
     * </ul>
     */
    private static String rinexLayout = System.getProperty("rtk.rinexLayout", "hour");
    /** post 布局下的日期子目录名（如 {@code 2026-10-31}）。 */
    private static String dateDir = System.getProperty("rtk.dateDir", "");

    /** 测站名：命令行第 5 个参数 > 环境变量 RTK_STATION > 系统属性 rtk.station（无内置默认值）。 */
    private static String station = DEFAULT_STATION;

    private static int hourStart = 0;
    private static int hourEnd = 15;

    private PppRtkSsrReplay() {
    }

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "diag";
        double ratioLimit = args.length > 1 ? Double.parseDouble(args[1]) : -1.0;
        if (args.length > 2) hourStart = Integer.parseInt(args[2]);
        if (args.length > 3) hourEnd = Integer.parseInt(args[3]);
        station = resolveStation(args.length > 4 ? args[4] : null);
        if (ssrDir.isEmpty() || rinexRoot.isEmpty() || station.isEmpty()) {
            System.out.println("缺少必要输入：");
            System.out.println("  -Drtk.ssrDir=<ssr-dir>       SSR 目录（UTC 小时命名的 HH.rtcm3）");
            System.out.println("  -Drtk.rinexRoot=<rinex-root> RINEX 根目录");
            System.out.println("  station                      第 5 个参数 / 环境变量 RTK_STATION / -Drtk.station=");
            return;
        }
        if ("diag".equals(mode)) {
            diagSsr();
        } else if ("run".equals(mode)) {
            replay(ratioLimit);
        } else {
            System.out.println("usage: diag | run [ratioLimit] [hourStart] [hourEnd] [station]");
            System.out.println("       station 也可用环境变量 RTK_STATION 或 -Drtk.station= 传入");
        }
    }

    private static String resolveStation(String cliValue) {
        if (cliValue != null && !cliValue.isEmpty()) return cliValue;
        String env = System.getenv("RTK_STATION");
        if (env != null && !env.isEmpty()) return env;
        String prop = System.getProperty("rtk.station");
        if (prop != null && !prop.isEmpty()) return prop;
        return DEFAULT_STATION;
    }

    /**
     * 解析 RINEX 小时文件路径。
     *
     * <p>AutoRtkPostService 的落盘布局是「路径按北京时间、内容按 UTC」：
     * 目录 {@code 2026-10-31} 是北京日期，文件名 {@code 8.obs} 是北京小时，
     * 但文件内首历元是 UTC {@code 2026-10-31 00:00}。因此 UTC 小时 h 对应
     * 北京小时 h+8；超过 23 时日期目录要加一天（UTC 16:00 落在北京次日 00:00）。
     * </p>
     */
    private static String hourFilePath(int utcHour, String ext) {
        int localHour = utcHour + LOCAL_HOUR_OFFSET;
        String dir;
        int hh;
        if ("post".equalsIgnoreCase(rinexLayout)) {
            dir = shiftDate(dateDir, localHour / 24);
            hh = localHour % 24;
            return String.format(Locale.US, "%s\\%s\\%s\\%d.%s", rinexRoot, station, dir, hh, ext);
        }
        dir = String.valueOf(localHour);
        hh = localHour;
        return String.format(Locale.US, "%s\\%s\\%02d\\%s.%s", rinexRoot, station, hh, station, ext);
    }

    private static String shiftDate(String date, int days) {
        if (days == 0 || date.isEmpty()) return date;
        return LocalDate.parse(date).plusDays(days).toString();
    }

    private static final int BDS_SLOT1_CODE = resolveBdsSlot1Code();

    private static int resolveBdsSlot1Code() {
        String v = System.getProperty("rtk.bdsSecond", "6I").toUpperCase(Locale.US);
        if ("NONE".equals(v)) return 0;
        return ObsCode.obs2code(v);
    }

    /**
     * 严格模式：{@code -Drtk.bdsStrict=true} 时，缺失目标第二频点的卫星会清空 slot1，
     * 保证同一星座内只存在一种信号对（B1I/B3I）。默认关闭——关闭时这类卫星会由
     * {@code mwFreqIdx} 回退到其它可用信号对（B1I/B2I、B1I/B2b），观测数更多，
     * 但同一星座内宽巷波长不再统一。
     */
    private static final boolean BDS_SLOT1_STRICT = "true".equalsIgnoreCase(
        System.getProperty("rtk.bdsStrict", "false"));

    private static void promoteBdsSlot1(Obsd[] arr) {
        if (BDS_SLOT1_CODE <= 0) return;
        for (Obsd o : arr) {
            int from = -1;
            for (int s = 2; s < o.code.length; s++) {
                if (o.code[s] == BDS_SLOT1_CODE) {
                    from = s;
                    break;
                }
            }
            if (from < 0) {
                /* 目标第二频点缺失：严格模式下清空 slot1。否则同一星座内会同时存在
                 * B1I/B3I 与 B1I/B2I 两种信号对，二者宽巷波长不同，而 WL 整数基准按
                 * 星座求中位数（PppAmbFix），混对会让少数派卫星的宽巷小数被当成离群。 */
                if (!BDS_SLOT1_STRICT || o.code[1] == 0) continue;
                o.code[1] = 0;
                o.P[1] = 0.0;
                o.L[1] = 0.0;
                o.D[1] = 0.0f;
                o.SNR[1] = 0.0f;
                o.LLI[1] = 0;
                if (o.Pstd != null) o.Pstd[1] = 0.0f;
                if (o.Lstd != null) o.Lstd[1] = 0.0f;
                continue;
            }
            if (from == 1) continue;
            if (o.code[1] != 0 && !BDS_SLOT1_STRICT) continue;
            o.code[1] = o.code[from];
            o.P[1] = o.P[from];
            o.L[1] = o.L[from];
            o.D[1] = o.D[from];
            o.SNR[1] = o.SNR[from];
            o.LLI[1] = o.LLI[from];
            if (o.Pstd != null) o.Pstd[1] = o.Pstd[from];
            if (o.Lstd != null) o.Lstd[1] = o.Lstd[from];
            o.code[from] = 0;
            o.P[from] = 0.0;
            o.L[from] = 0.0;
            o.D[from] = 0.0f;
            o.SNR[from] = 0.0f;
            o.LLI[from] = 0;
            if (o.Pstd != null) o.Pstd[from] = 0.0f;
            if (o.Lstd != null) o.Lstd[from] = 0.0f;
        }
    }

    private static String obsPath(int utcHour) {
        return hourFilePath(utcHour, "obs");
    }

    private static String navPath(int utcHour) {
        return hourFilePath(utcHour, "nav");
    }

    private static String ssrPath(int utcHour) {
        return String.format(Locale.US, "%s\\%02d.rtcm3", ssrDir, utcHour);
    }

    private static GTime firstObsTime() {
        RinexParser parser = new RinexParser();
        parser.parseObs(obsPath(hourStart));
        Obs obs = parser.obs;
        if (obs == null || obs.n == 0) throw new IllegalStateException("no obs in " + obsPath(hourStart));
        return new GTime(obs.data[0].time);
    }

    private static void diagSsr() throws IOException {
        GTime ref = firstObsTime();
        System.out.println("REFERENCE first obs epoch: " + ref);

        Rtcm rtcm = new Rtcm();
        rtcm.setReferenceTime(ref);

        Map<Integer, Integer> counts = new TreeMap<>();
        for (int h = hourStart; h <= hourEnd; h++) {
            byte[] data = Files.readAllBytes(Paths.get(ssrPath(h)));
            int pos = 0;
            while (pos < data.length) {
                int consumed = rtcm.input(data, pos, data.length - pos);
                if (consumed > 0) {
                    counts.merge(rtcm.type, 1, Integer::sum);
                    pos += consumed;
                } else if (consumed == 0) {
                    break;
                } else {
                    pos++;
                }
            }
        }
        System.out.println("SSR message inventory (00~15.rtcm3):");
        for (Map.Entry<Integer, Integer> e : counts.entrySet()) {
            System.out.println(String.format(Locale.US, "  type=%d count=%d", e.getKey(), e.getValue()));
        }

        Nav nav = rtcm.nav;
        System.out.println("BDS SSR state (sat | t_eph t_clk t_bias t_pbias | deph(m) dclk(m) | cbias | pbias):");
        for (int sat = 1; sat <= Constants.MAXSAT; sat++) {
            if (nav.ssr == null || sat - 1 >= nav.ssr.length) break;
            Ssr s = nav.ssr[sat - 1];
            if (s == null) continue;
            if (SatUtils.satsys(sat, null) != Constants.SYS_CMP) continue;
            StringBuilder cb = new StringBuilder();
            for (int c = 1; c <= Constants.MAXCODE; c++) {
                if (s.cbias[c - 1] != 0.0f) {
                    cb.append(codeDesc(sat, c)).append(String.format(Locale.US, "=%+.3f ", s.cbias[c - 1]));
                }
            }
            StringBuilder pb = new StringBuilder();
            for (int c = 1; c <= Constants.MAXCODE; c++) {
                if (s.pbias[c - 1] != 0.0) {
                    pb.append(codeDesc(sat, c)).append(String.format(Locale.US, "=%+.4f ", s.pbias[c - 1]));
                }
            }
            System.out.println(String.format(Locale.US,
                "  sat=%d t_eph=%s t_clk=%s t_pbias=%s | deph=[%.3f %.3f %.3f] dclk=[%.3f %.3f %.3f] | cbias[%s] pbias[%s]",
                sat, s.t0[0], s.t0[1], s.t0[5],
                s.deph[0], s.deph[1], s.deph[2], s.dclk[0], s.dclk[1], s.dclk[2],
                cb.toString().trim(), pb.toString().trim()));
        }

        RinexParser parser = new RinexParser();
        parser.parseObs(obsPath(hourStart));
        System.out.println("RINEX obs code slots (first 3 epochs, first 4 sats):");
        int printed = 0;
        for (int i = 0; i < parser.obs.n && printed < 12; i++) {
            Obsd o = parser.obs.data[i];
            if (SatUtils.satsys(o.sat, null) != Constants.SYS_CMP) continue;
            StringBuilder sb = new StringBuilder();
            for (int f = 0; f < Constants.NFREQ; f++) {
                if (o.code[f] == 0) continue;
                double frq = SatUtils.sat2freq(o.sat, o.code[f], null);
                sb.append(String.format(Locale.US, "slot%d=%s(%.3fMHz) ", f, ObsCode.code2obs(o.code[f]), frq / 1e6));
            }
            System.out.println(String.format(Locale.US, "  sat=%d %s", o.sat, sb.toString().trim()));
            printed++;
        }
    }

    private static String codeDesc(int sat, int code) {
        String obs = ObsCode.code2obs(code);
        double frq = SatUtils.sat2freq(sat, code, null);
        return String.format(Locale.US, "%s(%.2fM)", obs, frq / 1e6);
    }

    private static final class SsrFrame {
        final int offset;
        final int length;
        final GTime time;

        SsrFrame(int offset, int length, GTime time) {
            this.offset = offset;
            this.length = length;
            this.time = time;
        }
    }

    private static final class SsrStream {
        byte[] data;
        final List<SsrFrame> frames = new ArrayList<>();
    }

    private static SsrStream scanSsr(int utcHour, GTime ref) throws IOException {
        SsrStream st = new SsrStream();
        st.data = Files.readAllBytes(Paths.get(ssrPath(utcHour)));
        Rtcm scratch = new Rtcm();
        scratch.setReferenceTime(ref);
        int pos = 0;
        while (pos < st.data.length) {
            int consumed = scratch.input(st.data, pos, st.data.length - pos);
            if (consumed > 0) {
                st.frames.add(new SsrFrame(pos, consumed, new GTime(scratch.time)));
                pos += consumed;
            } else if (consumed == 0) {
                break;
            } else {
                pos++;
            }
        }
        return st;
    }

    private static List<List<Obsd>> groupEpochs(Obs obs) {
        List<List<Obsd>> groups = new ArrayList<>();
        List<Obsd> cur = null;
        GTime curTime = null;
        for (int i = 0; i < obs.n; i++) {
            Obsd o = obs.data[i];
            if (curTime == null || TimeSystem.timediff(o.time, curTime) != 0.0) {
                cur = new ArrayList<>();
                groups.add(cur);
                curTime = o.time;
            }
            cur.add(o);
        }
        return groups;
    }

    private static void replay(double ratioLimit) throws IOException {
        PrcOpt opt = PppProcessor.createDefaultOpt();
        opt.mode = Constants.PMODE_PPP_KINEMA;
        opt.sateph = Constants.EPHOPT_BRDC;
        opt.ionoopt = Integer.getInteger("rtk.ionoopt", Constants.IONOOPT_IFLC);
        opt.tropopt = Constants.TROPOPT_EST;
        opt.navsys = Integer.getInteger("rtk.navsys", Constants.SYS_CMP);
        opt.nf = Integer.getInteger("rtk.nf", 2);

        OnnxAiProvider aiProvider = installAiIfConfigured();

        PppProcessor proc = new PppProcessor(opt);
        RtkConfig cfg = new RtkConfig();
        cfg.pppRtk.enable = true;
        cfg.pppRtk.enableAR = true;
        cfg.pppAr.enableAR = true;
        if (ratioLimit > 0.0) {
            cfg.pppAr.arRatioWl = ratioLimit;
            cfg.pppAr.arRatioNl = ratioLimit;
            cfg.pppRtk.arRatio = ratioLimit;
        }
        proc.setRtkConfig(cfg);

        RinexParser parser0 = new RinexParser();
        parser0.parseObs(obsPath(hourStart));
        GTime ref = new GTime(parser0.obs.data[0].time);
        proc.setReferenceTime(ref);

        Rtk rtk = proc.getRtk();
        if (parser0.sta != null && parser0.sta.pos != null) {
            rtk.sol.rr[0] = parser0.sta.pos[0];
            rtk.sol.rr[1] = parser0.sta.pos[1];
            rtk.sol.rr[2] = parser0.sta.pos[2];
        }
        rtk.sol.stat = Constants.SOLQ_SINGLE;
        double[] llh0 = new double[3];
        CoordTransform.ecef2pos(rtk.sol.rr, llh0);

        System.out.println(String.format(Locale.US,
            "CONFIG ionoopt=%d tropopt=%d sateph=%d nf=%d navsys=0x%X elmin=%.1f | arRatioWl=%.3f arRatioNl=%.3f pppRtk.arRatio=%.3f | bdsSlot1=%s strict=%s",
            opt.ionoopt, opt.tropopt, opt.sateph, opt.nf, opt.navsys, opt.elmin * Constants.R2D,
            cfg.pppAr.arRatioWl, cfg.pppAr.arRatioNl, cfg.pppRtk.arRatio,
            ObsCode.code2obs(BDS_SLOT1_CODE), BDS_SLOT1_STRICT));
        System.out.println(String.format(Locale.US,
            "APPPOS lat=%.7f lon=%.7f h=%.3f",
            llh0[0] * Constants.R2D, llh0[1] * Constants.R2D, llh0[2]));

        List<Double> resRmsList = new ArrayList<>();
        List<double[]> fixPositions = new ArrayList<>();
        int totalEpochs = 0;
        int fixEpochs = 0;
        int pppEpochs = 0;
        double maxDelta = 0.0;
        double sumDelta = 0.0;
        int nDelta = 0;
        int nsMax = 0;
        int nsSum = 0;

        for (int h = hourStart; h <= hourEnd; h++) {
            RinexParser parser = new RinexParser();
            if (!parser.parseObs(obsPath(h))) {
                System.out.println("SKIP hour " + h + " (no obs)");
                continue;
            }
            proc.loadNav(navPath(h));

            SsrStream stream = scanSsr(h, ref);
            int fi = 0;
            List<List<Obsd>> groups = groupEpochs(parser.obs);
            String span = stream.frames.isEmpty() ? "none"
                    : stream.frames.get(0).time + " ~ " + stream.frames.get(stream.frames.size() - 1).time;
            System.out.println(String.format(Locale.US,
                "HOUR utc=%02d obs=%d epochs=%d ssrFrames=%d ssrSpan=[%s]",
                h, parser.obs.n, groups.size(), stream.frames.size(), span));

            for (List<Obsd> g : groups) {
                GTime t = g.get(0).time;
                while (fi < stream.frames.size() && stream.frames.get(fi).time.compareTo(t) <= 0) {
                    SsrFrame f = stream.frames.get(fi++);
                    proc.feed(stream.data, f.offset, f.length);
                }
                Obsd[] arr = g.toArray(new Obsd[0]);
                promoteBdsSlot1(arr);
                proc.processEpochObs(arr, arr.length);
                totalEpochs++;

                Sol sol = rtk.sol;
                if (sol.stat == Constants.SOLQ_FIX) {
                    fixEpochs++;
                    fixPositions.add(new double[]{sol.rr[0], sol.rr[1], sol.rr[2]});
                } else if (sol.stat == Constants.SOLQ_PPP) {
                    pppEpochs++;
                }
                if (sol.ns > 0) {
                    nsSum += sol.ns;
                    nsMax = Math.max(nsMax, sol.ns);
                }

                double[] xa = rtk.xa;
                double delta = 0.0;
                if (xa != null && rtk.nx > 0) {
                    for (int i = 0; i < 3; i++) {
                        delta = Math.max(delta, Math.abs(sol.rr[i] - xa[i]));
                    }
                }
                if (sol.stat == Constants.SOLQ_FIX) {
                    maxDelta = Math.max(maxDelta, delta);
                    sumDelta += delta;
                    nDelta++;
                }

                double resRms = residualRms(rtk, opt);
                resRmsList.add(resRms);

                int[] probe = ambiguityProbe(rtk, opt, proc.getNav(), arr, arr.length, totalEpochs,
                        totalEpochs <= 6 || (totalEpochs >= 88 && totalEpochs <= 100));
                double[] llh = new double[3];
                CoordTransform.ecef2pos(sol.rr, llh);
                System.out.println(String.format(Locale.US,
                    "EPOCH t=%s ns=%d stat=%d ratio=%.2f rr=[%.4f %.4f %.4f] llh=[%.7f %.7f %.3f] xaDelta=%.6f resRms=%.4f nObs=%d ambAlive=%d ambDead=%d vsat0=%d diag=[noPair=%d bzero=%d b500=%d slip=%d]",
                    t, sol.ns, sol.stat, sol.ratio, sol.rr[0], sol.rr[1], sol.rr[2],
                    llh[0] * Constants.R2D, llh[1] * Constants.R2D, llh[2], delta, resRms,
                    probe[0], probe[1], probe[2], probe[3], probe[4], probe[5], probe[6], probe[7]));
            }
        }

        System.out.println("SUMMARY");
        System.out.println(String.format(Locale.US,
            "  epochs=%d fix=%d ppp=%d fixRate=%.1f%%",
            totalEpochs, fixEpochs, pppEpochs,
            totalEpochs > 0 ? 100.0 * fixEpochs / totalEpochs : 0.0));
        System.out.println(String.format(Locale.US,
            "  visibleSats nsMax=%d nsAvg=%.2f", nsMax,
            totalEpochs > 0 ? (double) nsSum / totalEpochs : 0.0));
        System.out.println(String.format(Locale.US,
            "  updateStat clobber: n=%d meanDelta=%.6f maxDelta=%.6f m", nDelta,
            nDelta > 0 ? sumDelta / nDelta : 0.0, maxDelta));
        printFixQuality(fixPositions);
        printResidualStats(resRmsList);

        AiHooks.clear();
        if (aiProvider != null) {
            aiProvider.close();
        }
    }

    private static OnnxAiProvider installAiIfConfigured() {
        String dir = System.getProperty("rtk.ai", "");
        if (dir.isEmpty()) {
            System.out.println("AI3 disabled (未指定 -Drtk.ai)");
            return null;
        }
        OnnxAiProvider provider = new OnnxAiProvider(Paths.get(dir));
        if (!provider.isObsCorrectorAvailable()) {
            System.out.println("AI3 NOT available (模型加载失败，等价未注入): " + dir);
            provider.close();
            return null;
        }
        AiHooks.install(provider);
        System.out.println("AI3 installed: modelDir=" + dir);
        return provider;
    }

    private static void printFixQuality(List<double[]> fixPositions) {
        if (fixPositions.size() < 3) {
            System.out.println("  fixQuality: FIX 历元过少，跳过错误固定统计");
            return;
        }
        double[] center = new double[3];
        for (double[] p : fixPositions) {
            for (int i = 0; i < 3; i++) center[i] += p[i];
        }
        for (int i = 0; i < 3; i++) center[i] /= fixPositions.size();

        double[] llh = new double[3];
        CoordTransform.ecef2pos(center, llh);
        double[] u = new double[3];
        double[] rr = new double[3];

        double[] hDev = new double[fixPositions.size()];
        double[] vDev = new double[fixPositions.size()];
        double sumH = 0.0;
        double sumV = 0.0;
        int i = 0;
        for (double[] p : fixPositions) {
            for (int k = 0; k < 3; k++) rr[k] = p[k] - center[k];
            CoordTransform.ecef2enu(llh, rr, u);
            hDev[i] = Math.sqrt(u[0] * u[0] + u[1] * u[1]);
            vDev[i] = Math.abs(u[2]);
            sumH += hDev[i];
            sumV += vDev[i];
            i++;
        }
        Arrays.sort(hDev);
        Arrays.sort(vDev);
        int errFix = 0;
        for (double d : hDev) {
            if (d > 0.5) errFix++;
        }
        System.out.println(String.format(Locale.US,
            "  fixQuality vsFixCentroid: errFix(>0.5m)=%d/%d (%.1f%%)",
            errFix, fixPositions.size(), 100.0 * errFix / fixPositions.size()));
        System.out.println(String.format(Locale.US,
            "  fixQuality h: mean=%.4f p50=%.4f p90=%.4f p99=%.4f max=%.4f m",
            sumH / hDev.length, hDev[(int) (0.50 * (hDev.length - 1))],
            hDev[(int) (0.90 * (hDev.length - 1))], hDev[(int) (0.99 * (hDev.length - 1))],
            hDev[hDev.length - 1]));
        System.out.println(String.format(Locale.US,
            "  fixQuality v: mean=%.4f p50=%.4f p90=%.4f p99=%.4f max=%.4f m",
            sumV / vDev.length, vDev[(int) (0.50 * (vDev.length - 1))],
            vDev[(int) (0.90 * (vDev.length - 1))], vDev[(int) (0.99 * (vDev.length - 1))],
            vDev[vDev.length - 1]));
    }

    private static int[] ambiguityProbe(Rtk rtk, PrcOpt opt, Nav nav, Obsd[] obs, int n,
                                        int epoch, boolean detail) {
        int alive = 0, dead = 0, vsat0 = 0, noPair = 0, bzero = 0, b500 = 0, slipCnt = 0;
        for (int i = 0; i < n && i < Constants.MAXOBS; i++) {
            int sat = obs[i].sat;
            if (rtk.ssat[sat - 1].vs == 0) continue;
            int idx = PppCore.IB(sat, 0, opt);
            if (rtk.ssat[sat - 1].vsat[0] != 0) vsat0++;
            if (idx < rtk.nx && rtk.x[idx] != 0.0) {
                alive++;
                continue;
            }
            dead++;
            double[] Lc = new double[1];
            double[] Pc = new double[1];
            double[] L = new double[Constants.NFREQ];
            double[] P = new double[Constants.NFREQ];
            double[] dantr = new double[Constants.NFREQ];
            double[] dants = new double[Constants.NFREQ];
            PppCore.corrMeas(obs[i], nav, rtk.ssat[sat - 1].azel, opt, dantr, dants, 0.0, L, P, Lc, Pc);
            double b = Lc[0] - Pc[0];
            String tag;
            if (Lc[0] == 0.0 && Pc[0] == 0.0) {
                noPair++;
                tag = "NOPAIR";
            } else if (b == 0.0) {
                bzero++;
                tag = "BZERO";
            } else if (Math.abs(b) > 500.0) {
                b500++;
                tag = "B500";
            } else {
                tag = "OTHER";
            }
            if (rtk.ssat[sat - 1].slip[0] != 0) slipCnt++;
            if (detail) {
                StringBuilder sb = new StringBuilder();
                for (int f = 0; f < Constants.NFREQ; f++) {
                    if (obs[i].code[f] == 0) continue;
                    sb.append(f).append(':').append(ObsCode.code2obs(obs[i].code[f]))
                      .append('/').append(String.format(Locale.US, "%.1f", obs[i].SNR[f])).append(' ');
                }
                System.out.println(String.format(Locale.US,
                    "  DIAG ep=%d sat=%d %s x=%.3f b=%.3f resc=%.3f resp=%.3f vsat0=%d rejc=%d outc=%d slip=%d azel=%.1f codes[%s]",
                    epoch, sat, tag, idx < rtk.nx ? rtk.x[idx] : 0.0, b,
                    rtk.ssat[sat - 1].resc[0], rtk.ssat[sat - 1].resp[0],
                    rtk.ssat[sat - 1].vsat[0], rtk.ssat[sat - 1].rejc[0],
                    rtk.ssat[sat - 1].outc[0], rtk.ssat[sat - 1].slip[0],
                    rtk.ssat[sat - 1].azel[1] * Constants.R2D, sb.toString().trim()));
            }
        }
        return new int[]{n, alive, dead, vsat0, noPair, bzero, b500, slipCnt};
    }

    private static double residualRms(Rtk rtk, PrcOpt opt) {
        double sum = 0.0;
        int n = 0;
        int nf = opt.nf;
        for (int sat = 1; sat <= Constants.MAXSAT; sat++) {
            if (rtk.ssat[sat - 1].vsat[0] == 0) continue;
            for (int f = 0; f < nf && f < Constants.NFREQ; f++) {
                if (rtk.ssat[sat - 1].vsat[f] == 0) continue;
                double r = rtk.ssat[sat - 1].resc[f];
                if (r == 0.0) continue;
                sum += r * r;
                n++;
            }
        }
        return n > 0 ? Math.sqrt(sum / n) : 0.0;
    }

    private static void printResidualStats(List<Double> vals) {
        if (vals.isEmpty()) return;
        double[] a = new double[vals.size()];
        for (int i = 0; i < a.length; i++) a[i] = vals.get(i);
        Arrays.sort(a);
        double sum = 0.0;
        for (double v : a) sum += v;
        System.out.println(String.format(Locale.US,
            "  phaseResRms(m): mean=%.4f p50=%.4f p90=%.4f p99=%.4f max=%.4f",
            sum / a.length, a[(int) (0.50 * (a.length - 1))], a[(int) (0.90 * (a.length - 1))],
            a[(int) (0.99 * (a.length - 1))], a[a.length - 1]));
        int[] bins = new int[5];
        String[] labels = {"<0.05", "0.05~0.1", "0.1~0.2", "0.2~0.5", ">=0.5"};
        for (double v : a) {
            if (v < 0.05) bins[0]++;
            else if (v < 0.1) bins[1]++;
            else if (v < 0.2) bins[2]++;
            else if (v < 0.5) bins[3]++;
            else bins[4]++;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < bins.length; i++) {
            sb.append(String.format(Locale.US, "%s=%d ", labels[i], bins[i]));
        }
        System.out.println("  phaseResRms bins: " + sb.toString().trim());
    }
}