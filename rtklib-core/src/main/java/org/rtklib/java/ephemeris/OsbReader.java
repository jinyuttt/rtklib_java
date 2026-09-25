package org.rtklib.java.ephemeris;

import org.rtklib.java.common.SatUtils;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.data.Nav;
import org.rtklib.java.pntpos.SppCore;
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
                String prn = tokens[2];
                String obs1 = tokens[3];
                if (obs1.length() < 2) continue;
                char obsTypeChar = obs1.charAt(0);
                if (obsTypeChar != 'C' && obsTypeChar != 'L') continue;

                double value = str2num(line, 70, 21);
                if (value == 0.0) continue;

                int sat = SatUtils.satid2no(prn);
                if (sat == 0) continue;
                int sys = SatUtils.satsys(sat, null);

                if (biasType.equals("OSB")) {
                    boolean isPhase = (obsTypeChar == 'L');
                    if (isPhase) {
                        if (obs1.length() > 1) {
                            int code = obs2code(obs1.substring(1));
                            if (code > 0 && code <= Constants.MAXCODE) {
                                if (nav.fcbWlByCode == null) {
                                    nav.fcbWlByCode = new double[Constants.MAXSAT][Constants.MAXCODE + 1];
                                }
                                nav.fcbWlByCode[sat - 1][code] = value * 1E-9 * Constants.CLIGHT;
                                nav.fcbFromOsb = true;
                                nPhaseOsb++;
                                if (sys == Constants.SYS_CMP) {
                                    System.out.printf("OSB-DIAG prn=%s obs=%s code=%d value=%.4e%n",
                                        prn, obs1, code, value * 1E-9 * Constants.CLIGHT);
                                }
                            }
                        }
                    } else {
                        int freq = obs2freq(sys, obs1);
                        if (freq >= 0 && freq < MAX_CODE_BIAS_FREQS) {
                            int code1 = obs2code(obs1.substring(1));
                            int biasIx1 = SppCore.code2biasIx(sys, code1);
                            if (biasIx1 == 0) {
                                for (int i = 0; i < MAX_CODE_BIASES; i++) {
                                    nav.cbias[sat - 1][freq][i] += value * 1E-9 * Constants.CLIGHT;
                                }
                            } else if (biasIx1 >= 1 && biasIx1 - 1 < MAX_CODE_BIASES) {
                                nav.cbias[sat - 1][freq][biasIx1 - 1] -= value * 1E-9 * Constants.CLIGHT;
                            }
                            nCodeOsb++;
                        }
                    }
                } else if (biasType.equals("DSB")) {
                    String obs2 = tokens[4];
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
            System.err.printf("OSB-MAP-DEBUG fcbWlByCode=%s fcbFromOsb=%s%n",
                nav.fcbWlByCode != null ? "not-null" : "null", nav.fcbFromOsb);
            System.err.flush();
            if (nav.fcbWlByCode != null && nav.fcbFromOsb) {
                System.err.println("OSB-MAP BDS satellite index mapping:");
                for (int prn = 1; prn <= 46; prn++) {
                    int sat = SatUtils.satno(Constants.SYS_CMP, prn);
                    if (sat > 0 && sat <= Constants.MAXSAT) {
                        // Check if this satellite has any OSB data
                        boolean hasOsb = false;
                        for (int code = 0; code <= Constants.MAXCODE; code++) {
                            if (nav.fcbWlByCode[sat - 1][code] != 0.0) {
                                hasOsb = true;
                                break;
                            }
                        }
                        if (hasOsb) {
                            System.err.printf("OSB-MAP C%02d -> sat=%d (index=%d)%n", prn, sat, sat - 1);
                        }
                    }
                }
                System.err.flush();
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

    private static double str2num(String s, int pos, int len) {
        if (s == null || pos < 0 || pos + len > s.length()) return 0.0;
        try {
            return Double.parseDouble(s.substring(pos, pos + len).trim());
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }
}