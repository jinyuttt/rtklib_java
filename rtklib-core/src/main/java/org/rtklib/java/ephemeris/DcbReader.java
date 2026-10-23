package org.rtklib.java.ephemeris;

import org.rtklib.java.common.ObsCode;
import org.rtklib.java.common.SatUtils;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.data.Nav;
import org.rtklib.java.data.Sta;
import org.rtklib.java.pntpos.SppCore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.Arrays;

/**
 * DCB 码偏差文件读取。
 *
 * <p>对应 C 版：RTKLIB preceph.c readdcb() → readdcbf()（传统 DCB）/ readbiaf()（BIA/BSX）。
 * 支持三种格式：
 * <ul>
 *   <li>传统 DCB（.DCB）：P1-C1/P2-C2 差分码偏差，卫星 + 接收机（C readdcbf，L378-417）</li>
 *   <li>BIA（.BIA）：Bias-SINEX OSB/DSB 记录，仅卫星码偏差（C readbiaf，L449-502）</li>
 *   <li>BSX（.BSX）：Bias-SINEX OSB/DSB 记录，仅卫星码偏差（C readbiaf）</li>
 * </ul>
 * 所有偏差值单位 ns，加载时统一 ×1E-9×CLIGHT 转为 m（C 语义）。
 *
 * <p>与 C 的差异：C postpos.c 先把 cbias 置 -1（FIXME），readdcb 失败时 corr_meas
 * 会应用 -1 m 的错误偏移；Java 失败时保持 0（无偏移），不复制该缺陷。
 */
public final class DcbReader {
    private DcbReader() {
    }

    private static final Logger LOG = LoggerFactory.getLogger(DcbReader.class);

    /* C preceph.h: MAX_CODE_BIAS_FREQS=2, MAX_CODE_BIASES=3 */
    private static final int MAX_CODE_BIAS_FREQS = 2;
    private static final int MAX_CODE_BIASES = 3;

    /**
     * 读取单个 DCB/BIA/BSX 文件（对应 C readdcb(file, nav, sta)）。
     * 入口先清零 nav.cbias（C readdcb L523-525 语义），再按扩展名分发解析。
     *
     * @param file DCB/BIA/BSX 文件路径
     * @param nav  导航数据（nav.cbias 被填充，单位 m）
     * @param stas 测站信息（接收机 DCB；null=不使用，对应 C sta=NULL）
     * @return true=成功解析出至少一个偏差值
     */
    public static boolean readdcb(String file, Nav nav, Sta[] stas) {
        if (file == null || file.isEmpty()) return false;
        initCbias(nav);
        return readDcbFile(file, nav, stas);
    }

    /**
     * 读取多个 DCB 文件（对应 C readdcb 通配符展开后逐文件处理的语义：全部处理，不短路）。
     */
    public static boolean readdcbMulti(String[] files, Nav nav, Sta[] stas) {
        if (files == null || files.length == 0) return false;
        initCbias(nav);
        boolean dcbOk = false;
        for (String file : files) {
            if (file == null || file.isEmpty()) continue;
            if (readDcbFile(file, nav, stas)) dcbOk = true;
        }
        return dcbOk;
    }

    private static boolean readDcbFile(String file, Nav nav, Sta[] stas) {
        String upper = file.toUpperCase();
        if (upper.endsWith(".BIA") || upper.endsWith(".BSX")) {
            return readbiaf(file, nav);
        }
        if (upper.endsWith(".DCB")) {
            return readdcbf(file, nav, stas);
        }
        LOG.warn("readdcb: unsupported DCB file type (expect .DCB/.BIA/.BSX): {}", file);
        return false;
    }

    /**
     * 入口清零 cbias（对齐 C readdcb L523-525）。
     * nav.cbias 由 Nav 构造函数分配为 [MAXSAT][NFREQ][NFREQ]，此处仅清零不重分配。
     */
    private static void initCbias(Nav nav) {
        if (nav.cbias != null) {
            for (double[][] satBias : nav.cbias) {
                if (satBias != null) {
                    for (double[] frqBias : satBias) {
                        if (frqBias != null) Arrays.fill(frqBias, 0.0);
                    }
                }
            }
        }
    }

    /**
     * 传统 DCB 格式（对应 C preceph.c readdcbf L378-417）。
     * 头部 "DIFFERENTIAL (P1-C1) CODE BIASES" → type=1，"DIFFERENTIAL (P2-C2) CODE BIASES" → type=2。
     * 值位于固定列 str2num(buff,26,9)，卫星行 cbias[sat-1][type-1][0]，接收机行 rbias[i][G/R][type-1]。
     */
    static boolean readdcbf(String file, Nav nav, Sta[] stas) {
        boolean ok = false;
        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String line;
            int type = 0;
            while ((line = br.readLine()) != null) {
                if (line.contains("DIFFERENTIAL (P1-C1) CODE BIASES")) type = 1;
                else if (line.contains("DIFFERENTIAL (P2-C2) CODE BIASES")) type = 2;

                /* C L397: if (!type||sscanf(...)<1) continue; */
                if (type == 0) continue;

                String[] tokens = line.trim().split("\\s+");
                if (tokens.length < 1) continue;
                String str1 = tokens[0];
                String str2 = tokens.length >= 2 ? tokens[1] : "";

                /* C L399: if ((cbias=str2num(buff,26,9))==0.0) continue; */
                double cbias = str2num(line, 26, 9);
                if (cbias == 0.0) continue;

                if (stas != null && (str1.equals("G") || str1.equals("R"))) {
                    /* receiver DCB（C L401-409）：按站名匹配 */
                    for (int i = 0; i < stas.length && i < nav.rbias.length; i++) {
                        if (stas[i].name != null && stas[i].name.equals(str2)) {
                            int j = str1.equals("G") ? 0 : 1;
                            if (type - 1 < MAX_CODE_BIASES && type - 1 < nav.rbias[i][j].length) {
                                nav.rbias[i][j][type - 1] = cbias * 1E-9 * Constants.CLIGHT; /* ns -> m */
                            }
                            break;
                        }
                    }
                } else {
                    /* satellite DCB（C L410-412） */
                    int sat = SatUtils.satid2no(str1);
                    if (sat > 0 && sat <= Constants.MAXSAT
                            && type - 1 < MAX_CODE_BIAS_FREQS
                            && type - 1 < nav.cbias[sat - 1].length) {
                        nav.cbias[sat - 1][type - 1][0] = cbias * 1E-9 * Constants.CLIGHT; /* ns -> m */
                        ok = true;
                    }
                }
            }
        } catch (IOException e) {
            LOG.warn("readdcbf: DCB file open error: {}", file);
            return false;
        }
        return ok;
    }

    /**
     * BIA/BSX Bias-SINEX 格式（对应 C preceph.c readbiaf L449-502，仅卫星码偏差）。
     * 每行 5 字段：bias(OBS/DSB/OSB) svn prn obs1 obs2，值位于 str2num(buff,70,21)。
     * obs1[0]=='C' 才处理（跳过相位偏差记录）；频率映射：L1→0，非 GAL L2→1，GAL L5→1。
     */
    static boolean readbiaf(String file, Nav nav) {
        boolean ok = false;
        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = br.readLine()) != null) {
                String[] tokens = line.trim().split("\\s+");
                /* C L463: sscanf(buff,"%4s %5s %4s %4s %4s",bias,svn,prn,obs1,obs2) 需 5 字段 */
                if (tokens.length < 5) continue;
                String bias = tokens[0];
                String prn = tokens[2];
                String obs1 = tokens[3];

                if (obs1.length() < 2 || obs1.charAt(0) != 'C') continue; /* 跳过相位偏差 C L464 */
                double cbias = str2num(line, 70, 21);
                if (cbias == 0.0) continue;

                int sat = SatUtils.satid2no(prn);
                if (sat <= 0 || sat > Constants.MAXSAT) continue; /* Java 防御：C 无此检查 */
                int sys = SatUtils.satsys(sat, null);

                /* 其他系统码偏差取 L1/L2，Galileo 取 L1/L5（C L468-473） */
                int freq;
                if (obs1.charAt(1) == '1') {
                    freq = 0;
                } else if ((sys != Constants.SYS_GAL && obs1.charAt(1) == '2')
                        || (sys == Constants.SYS_GAL && obs1.charAt(1) == '5')) {
                    freq = 1;
                } else {
                    continue;
                }
                if (freq >= MAX_CODE_BIAS_FREQS || freq >= nav.cbias[sat - 1].length) continue;

                int code1 = ObsCode.obs2code(obs1.substring(1));
                if (code1 == 0) continue; /* C L475: skip if code not valid */
                int biasIx1 = SppCore.code2biasIx(sys, code1);

                if (bias.equals("OSB")) {
                    /* observed signal bias（C L477-486） */
                    if (biasIx1 == 0) {
                        /* ref code：其余全部码偏差加 ref 偏移 */
                        for (int i = 0; i < MAX_CODE_BIASES && i < nav.cbias[sat - 1][freq].length; i++) {
                            nav.cbias[sat - 1][freq][i] += cbias * 1E-9 * Constants.CLIGHT; /* ns -> m */
                        }
                    } else if (biasIx1 > 0 && biasIx1 - 1 < MAX_CODE_BIASES) {
                        /* biasIx1<0=不支持的码（C init_bias_ix 初始化 -1）；C else 分支
                         * 缺 >0 检查会对 cbias[-2] 越界写（UB），Java 防御性跳过 */
                        nav.cbias[sat - 1][freq][biasIx1 - 1] -= cbias * 1E-9 * Constants.CLIGHT; /* ns -> m */
                    }
                    ok = true;
                } else if (bias.equals("DSB")) {
                    /* differential signal bias（C L487-496）：仅同频内 */
                    String obs2 = tokens[4];
                    if (obs2.length() < 2 || obs1.charAt(1) != obs2.charAt(1)) continue;
                    int code2 = ObsCode.obs2code(obs2.substring(1));
                    if (code2 == 0) continue; /* C L490: skip if code not valid */
                    int biasIx2 = SppCore.code2biasIx(sys, code2);
                    if (biasIx1 == 0 && biasIx2 - 1 >= 0 && biasIx2 - 1 < MAX_CODE_BIASES) {
                        /* obs1 为 ref 码：直接赋值 */
                        nav.cbias[sat - 1][freq][biasIx2 - 1] = cbias * 1E-9 * Constants.CLIGHT;
                    } else if (biasIx2 == 0 && biasIx1 - 1 >= 0 && biasIx1 - 1 < MAX_CODE_BIASES) {
                        /* obs2 为 ref 码：取负 */
                        nav.cbias[sat - 1][freq][biasIx1 - 1] = -cbias * 1E-9 * Constants.CLIGHT;
                    }
                    ok = true;
                }
            }
        } catch (IOException e) {
            LOG.warn("readbiaf: BIA/BSX file open error: {}", file);
            return false;
        }
        return ok;
    }

    private static double str2num(String s, int pos, int len) {
        if (s == null || pos < 0 || pos + len > s.length()) return 0.0;
        try {
            return Double.parseDouble(s.substring(pos, pos + len).trim());
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }
}