package org.rtklib.java.ephemeris;

import org.rtklib.java.common.SatUtils;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.data.Nav;
import org.rtklib.java.pntpos.SppCore;
import org.rtklib.java.time.TimeSystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * OSB（Observable-Specific Bias）产品读取器，支持IGS Bias-SINEX格式。
 *
 * <p>读取.BIA/.BSX文件中的OSB相位偏差，转化为FCB（WL/NL）数据供PPP-AR使用。
 * 同时读取伪距OSB偏差，存入nav.cbias供DCB改正使用。
 *
 * <p>文件格式：IGS Bias-SINEX v1.00
 * <pre>
 *   OSB  SVN  PRN  OBS1  START  END  UNIT  VALUE  STDDEV
 *   DSB  SVN  PRN  OBS1  OBS2   START  END  UNIT  VALUE  STDDEV
 * </pre>
 *
 * <p>对应C版：RTKLIB的readdcb()中readbiaf()处理DSB，本模块扩展处理OSB
 */
public final class OsbReader {
    private OsbReader() {}

    private static final Logger LOG = LoggerFactory.getLogger(OsbReader.class);

    private static final int MAX_CODE_BIASES = 3;
    private static final int MAX_CODE_BIAS_FREQS = 2;

    public static boolean readOsb(String file, Nav nav) {
        if (file == null || file.isEmpty()) return false;

        int nPhaseOsb = 0;
        int nCodeOsb = 0;
        int nDsb = 0;

        if (nav.cbias == null) {
            nav.cbias = new double[Constants.MAXSAT][MAX_CODE_BIAS_FREQS][MAX_CODE_BIASES];
        }

        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.length() < 75) continue;
                String[] tokens = line.trim().split("\\s+");
                if (tokens.length < 5) continue;

                String biasType = tokens[0];
                /* FIX-ALG-027: 0MGXRAP 系产品存在两种版式——WUM 行 SVN 填充，PRN 在
                 * tokens[2]；GBM 行 SVN 列空缺，PRN 直接落在 tokens[1]。VALUE 固定列
                 * 70-90 两种版式均对齐。
                 * FIX-ALG-028: 列偏移判别升级为双令牌规则。原规则（仅判 tokens[1] 是
                 * 否为合法卫星号）把 WUM 行中恰好落在 PRN 域内的 SVN 误判为 GBM 版式
                 * （GPS SVN 43-64 共 16 颗、BDS-2 SVN C005-C019 共 11 颗）：obs1 变成
                 * PRN 令牌（"G13" 以 'G' 开头、或 "C06" obs2code=0），整行被静默跳过，
                 * 27 颗卫星全部 OSB 丢失 → AR 候选完备性门槛将其剔除 → median 对齐
                 * 基准剧变，WUM 基线全 AR 路径中毒（fix 72.4%、2D RMS 66.62cm vs 金
                 * 标准 65.7%/3.54cm）。双令牌规则：tokens[1]/tokens[2] 均可解析为卫星
                 * 号 → WUM 双列版式（sh=1）；仅 tokens[1] 可解析 → GBM 版式（sh=0）；
                 * tokens[1] 不可解析（R801/E201/C201/G069 等 SVN 超出 PRN 域）→ sh=1。
                 * GBM 行 tokens[2] 为观测码（satid2no=0），行为与原规则逐值一致。 */
                int no1 = tokens.length > 1 ? SatUtils.satid2no(tokens[1]) : 0;
                int no2 = tokens.length > 2 ? SatUtils.satid2no(tokens[2]) : 0;
                int sh = (no1 != 0 && no2 == 0) ? 0 : 1;
                if (tokens.length < 3 + sh) continue;
                String prn = tokens[1 + sh];
                String obs1 = tokens[2 + sh];
                if (obs1.length() < 2) continue;
                char obsTypeChar = obs1.charAt(0);
                if (obsTypeChar != 'C' && obsTypeChar != 'L') continue;

                double value = str2num(line, 70, 21);
                if (value == 0.0) continue;

                int sat = SatUtils.satid2no(prn);
                if (sat == 0) continue;
                int sys = SatUtils.satsys(sat, null);

                if (biasType.equals("OSB")) {
                    double tsO = (tokens.length > 3 + sh) ? parseSinexTime(tokens[3 + sh]) : -1.0;
                    double teO = (tokens.length > 4 + sh) ? parseSinexTime(tokens[4 + sh]) : -1.0;
                    boolean isPhase = (obsTypeChar == 'L');
                    if (isPhase) {
                        if (obs1.length() > 1) {
                            int code = obs2code(obs1.substring(1));
                            if (code > 0 && code <= Constants.MAXCODE) {
                                if (nav.fcbWlByCode == null) {
                                    nav.fcbWlByCode = new double[Constants.MAXSAT][Constants.MAXCODE + 1];
                                }
                                nav.fcbWlByCode[sat - 1][code] = value * 1E-9 * Constants.CLIGHT;
                                addOsbSeries(nav, sat, code, tsO, teO, value * 1E-9 * Constants.CLIGHT, true);
                                nav.fcbFromOsb = true;
                                nPhaseOsb++;
                                if (sys == Constants.SYS_CMP) {
                                    LOG.debug("OSB-DIAG prn={} obs={} code={} value={}", 
                                        prn, obs1, code, String.format("%.4e", value * 1E-9 * Constants.CLIGHT));
                                }
                            }
                        }
                    } else {
                        int freq = obs2freq(sys, obs1);
                        if (freq >= 0 && freq < MAX_CODE_BIAS_FREQS) {
                            int code1 = obs2code(obs1.substring(1));
                            double valueM = value * 1E-9 * Constants.CLIGHT;
                            boolean timedShort = tsO >= 0.0 && teO > tsO && (teO - tsO) < 86399.0;
                            if (timedShort) {
                                /* 时变短区间（如 GBM 30s 序列）：进 osbSeries 按历元取值；
                                 * 不进 cbias 累加（否则数千条记录累加导致码偏差爆炸，
                                 * FIX-ALG-027）。cbias 语义维持常量 DCB（WUM 场景）。 */
                                addOsbSeries(nav, sat, code1, tsO, teO, valueM, false);
                            } else {
                                int biasIx1 = SppCore.code2biasIx(sys, code1);
                                if (biasIx1 == 0) {
                                    for (int i = 0; i < MAX_CODE_BIASES; i++) {
                                        nav.cbias[sat - 1][freq][i] += valueM;
                                    }
                                } else if (biasIx1 >= 1 && biasIx1 - 1 < MAX_CODE_BIASES) {
                                    nav.cbias[sat - 1][freq][biasIx1 - 1] -= valueM;
                                }
                            }
                            if (code1 > 0 && code1 <= Constants.MAXCODE) {
                                if (nav.cbiasOsb == null) {
                                    nav.cbiasOsb = new double[Constants.MAXSAT][Constants.MAXCODE + 1];
                                }
                                nav.cbiasOsb[sat - 1][code1] = valueM;
                            }
                            nCodeOsb++;
                        }
                    }
                } else if (biasType.equals("DSB")) {
                    if (3 + sh >= tokens.length) continue;
                    String obs2 = tokens[3 + sh];
                    if (obs1.length() >= 2 && obs2.length() >= 2 && obs1.charAt(1) != obs2.charAt(1)) continue;
                    int freq = obs2freq(sys, obs1);
                    if (freq < 0 || freq >= MAX_CODE_BIAS_FREQS) continue;
                    int code1 = obs2code(obs1.substring(1));
                    int code2 = obs2code(obs2.substring(1));
                    int biasIx1 = SppCore.code2biasIx(sys, code1);
                    int biasIx2 = SppCore.code2biasIx(sys, code2);
                    if (biasIx1 == 0 && biasIx2 >= 1 && biasIx2 - 1 < MAX_CODE_BIASES) {
                        nav.cbias[sat - 1][freq][biasIx2 - 1] = value * 1E-9 * Constants.CLIGHT;
                    } else if (biasIx2 == 0 && biasIx1 >= 1 && biasIx1 - 1 < MAX_CODE_BIASES) {
                        nav.cbias[sat - 1][freq][biasIx1 - 1] = -value * 1E-9 * Constants.CLIGHT;
                    }
                    nDsb++;
                }
            }
        } catch (IOException e) {
            LOG.warn("OSB/BIA file open error: {}", file);
            return false;
        }

        int total = nPhaseOsb + nCodeOsb + nDsb;
        if (total > 0) {
            LOG.info("OSB/BIA loaded from {}: phaseOSB={}, codeOSB={}, DSB={}", file, nPhaseOsb, nCodeOsb, nDsb);
            
            // Diagnostic: show BDS satellite mapping
            LOG.debug("OSB-MAP fcbWlByCode={} fcbFromOsb={}",
                nav.fcbWlByCode != null ? "not-null" : "null", nav.fcbFromOsb);
            if (nav.fcbWlByCode != null && nav.fcbFromOsb) {
                LOG.debug("OSB-MAP BDS satellite index mapping:");
                for (int prn = 1; prn <= 46; prn++) {
                    int sat = SatUtils.satno(Constants.SYS_CMP, prn);
                    if (sat > 0 && sat <= Constants.MAXSAT) {
                        boolean hasOsb = false;
                        for (int code = 0; code <= Constants.MAXCODE; code++) {
                            if (nav.fcbWlByCode[sat - 1][code] != 0.0) {
                                hasOsb = true;
                                break;
                            }
                        }
                        if (hasOsb) {
                            LOG.debug("OSB-MAP C{:02d} -> sat={} (index={})", prn, sat, sat - 1);
                        }
                    }
                }
            }
        } else {
            LOG.warn("No OSB/BIA data loaded from {}", file);
        }
        return total > 0;
    }

    private static boolean isPhaseObs(String obs) {
        if (obs == null || obs.length() < 3) return false;
        char freqChar = obs.charAt(1);
        char typeChar = obs.length() >= 3 ? obs.charAt(2) : 'C';
        if (freqChar == '1' || freqChar == '2' || freqChar == '5' ||
            freqChar == '6' || freqChar == '7' || freqChar == '8') {
            return typeChar == 'I' || typeChar == 'W' || typeChar == 'X' || typeChar == 'L';
        }
        return false;
    }

    private static int obs2freq(int sys, String obs) {
        if (obs == null || obs.length() < 2) return -1;
        char f = obs.charAt(1);
        switch (f) {
            case '1': return 0;
            case '2': return (sys == Constants.SYS_GAL) ? -1 : 1;
            case '5': return (sys == Constants.SYS_GAL) ? 1 : -1;
            case '6': return (sys == Constants.SYS_CMP) ? 1 : -1;
            case '7': return -1;
            default: return -1;
        }
    }

    private static int obs2code(String obs) {
        if (obs == null || obs.length() < 2) return 0;
        char freqChar = obs.charAt(0);
        char sigChar = obs.length() >= 2 ? obs.charAt(1) : 'C';
        switch (freqChar) {
            case '1':
                switch (sigChar) {
                    case 'C': return Constants.CODE_L1C;
                    case 'P': return Constants.CODE_L1P;
                    case 'W': return Constants.CODE_L1W;
                    case 'X': return Constants.CODE_L1X;
                    case 'I': return Constants.CODE_L1I;
                    case 'L': return Constants.CODE_L1L;
                    default: return Constants.CODE_L1C;
                }
            case '2':
                switch (sigChar) {
                    case 'I': return Constants.CODE_L2I;
                    case 'C': return Constants.CODE_L2C;
                    case 'W': return Constants.CODE_L2W;
                    case 'X': return Constants.CODE_L2X;
                    case 'L': return Constants.CODE_L2L;
                    case 'S': return Constants.CODE_L2S;
                    case 'P': return Constants.CODE_L2P;
                    default: return Constants.CODE_L2C;
                }
            case '5':
                switch (sigChar) {
                    case 'I': return Constants.CODE_L5I;
                    case 'Q': return Constants.CODE_L5Q;
                    case 'X': return Constants.CODE_L5X;
                    case 'C': return Constants.CODE_L5C;
                    case 'P': return Constants.CODE_L5P;
                    default: return Constants.CODE_L5X;
                }
            case '6':
                switch (sigChar) {
                    case 'I': return Constants.CODE_L6I;
                    case 'X': return Constants.CODE_L6X;
                    case 'C': return Constants.CODE_L6C;
                    case 'A': return Constants.CODE_L6A;
                    default: return Constants.CODE_L6I;
                }
            case '7':
                switch (sigChar) {
                    case 'I': return Constants.CODE_L7I;
                    case 'Q': return Constants.CODE_L7Q;
                    case 'X': return Constants.CODE_L7X;
                    case 'D': return Constants.CODE_L7D;
                    case 'Z': return Constants.CODE_L7Z;
                    default: return Constants.CODE_L7X;
                }
            case '8':
                switch (sigChar) {
                    case 'I': return Constants.CODE_L8I;
                    case 'Q': return Constants.CODE_L8Q;
                    case 'X': return Constants.CODE_L8X;
                    default: return Constants.CODE_L8X;
                }
            default: return 0;
        }
    }

    /** 写入时变 OSB 序列（FIX-ALG-027）：ts/te 解析成功且 te>ts 才写入；
     *  key=((sat-1)*(MAXCODE+1)+code)*2 + (码0/相位1)——码 OSB 与相位 OSB
     *  分键存放（C1C 与 L1C 共享底层码号，混流会让相位改正误用码偏差），
     *  记录 {startGpsSec, endGpsSec, valueM}。常量型产品（单全天区间）同样写入。 */
    private static void addOsbSeries(Nav nav, int sat, int code, double ts, double te, double valueM, boolean phase) {
        if (ts < 0.0 || te <= ts) return;
        if (nav.osbSeries == null) nav.osbSeries = new java.util.HashMap<>();
        long key = ((long) (sat - 1) * (Constants.MAXCODE + 1) + code) * 2 + (phase ? 1 : 0);
        nav.osbSeries.computeIfAbsent(key, k -> new ArrayList<>()).add(new double[]{ts, te, valueM});
    }

    /** Bias-SINEX 时间 "yyyy:ddd:sssss" -> GPST Unix 秒；解析失败返回 -1.0。 */
    private static double parseSinexTime(String tok) {
        if (tok == null || tok.isEmpty()) return -1.0;
        String[] p = tok.split(":");
        if (p.length != 3) return -1.0;
        try {
            int y = Integer.parseInt(p[0].trim());
            int doy = Integer.parseInt(p[1].trim());
            int sod = Integer.parseInt(p[2].trim());
            return TimeSystem.epoch2time(new double[]{y, 1, 1, 0, 0, 0}).time
                    + (doy - 1) * 86400.0 + sod;
        } catch (NumberFormatException e) {
            return -1.0;
        }
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