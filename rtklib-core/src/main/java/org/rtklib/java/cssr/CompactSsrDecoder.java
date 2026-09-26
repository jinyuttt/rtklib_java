package org.rtklib.java.cssr;

import org.rtklib.java.common.BitUtils;
import org.rtklib.java.common.SatUtils;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.data.*;
import org.rtklib.java.time.TimeSystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CompactSsrDecoder implements Serializable {
    private static final long serialVersionUID = 1L;
    private static final Logger log = LoggerFactory.getLogger(CompactSsrDecoder.class);

    public static final int CSSR_MSGTYPE = 4073;
    public static final int MAXNET = 32;
    public static final int SYSMAX = 16;

    private static final int[] STEC_SZ_T = {4, 4, 5, 7};
    private static final double[] STEC_SCL_T = {0.04, 0.12, 0.16, 0.24};

    private static final double[] DORB_SCL = {0.0016, 0.0064, 0.0064};
    private static final double DCLK_SCL = 0.0016;
    private static final int[] DORB_BLEN = {15, 13, 13};
    private static final int DCLK_BLEN = 15;
    private static final int CB_BLEN = 11;
    private static final double CB_SCL = 0.02;
    private static final int PB_BLEN = 15;
    private static final double PB_SCL = 0.001;

    private static final int CT_MASK = 0;
    private static final int CT_ORBIT = 1;
    private static final int CT_CLOCK = 2;
    private static final int CT_CBIAS = 3;
    private static final int CT_PBIAS = 4;
    private static final int CT_STEC = 5;
    private static final int CT_TROP = 6;
    private static final int CT_URA = 7;
    private static final int CT_AUTH = 8;
    private static final int CT_HCLOCK = 9;
    private static final int CT_VTEC = 10;
    private static final int CT_OC = 11;

    public int week = -1;
    public double tow0 = -1;
    public int iodssr = -1;
    public int iodssrP = -1;
    public int subtype = 0;
    public int nsatN = 0;
    public int nsigTotal = 0;
    public int nsigMax = 0;
    public int ngnss = 0;
    public int inet = -1;
    public boolean flgNet = false;
    public GTime time = new GTime();
    public double tow = 0;
    public boolean localPbias = true;

    public int[] satN = new int[0];
    public int[] sysN = new int[0];
    public int[] nsigN = new int[0];
    public int[] satNP = new int[0];
    public Map<Integer, List<Integer>> sigN = new HashMap<>();

    public LocalCorr[] lc = new LocalCorr[MAXNET + 1];

    public byte[] l6Buff = new byte[250 * 10];
    public byte[] l6BuffP = null;
    public int fcnt = -1;
    public int facility = -1;
    public int pattern = -1;
    public int sid = -1;

    public GridDefinition gridDef = null;

    public CompactSsrDecoder() {
        for (int i = 0; i <= MAXNET; i++) {
            lc[i] = new LocalCorr();
            lc[i].inet = i;
        }
    }

    public void setWeek(int week) {
        this.week = week;
    }

    public void setGridDefinition(GridDefinition gridDef) {
        this.gridDef = gridDef;
    }

    private static double sval(long u, int n, double scl) {
        long invalid = -(1L << (n - 1));
        if (u == invalid) return Double.NaN;
        return u * scl;
    }

    private static double qualityIdx(int cls, int val) {
        if (cls == 7 && val == 7) return 5.4665;
        if (cls == 0 && val == 0) return Double.NaN;
        return (Math.pow(3, cls) * (1 + val * 0.25) - 1) * 1e-3;
    }

    private static boolean isset(int mask, int nbit, int k) {
        return ((mask >> (nbit - k - 1)) & 1) != 0;
    }

    private static int gnss2sys(int gnss) {
        switch (gnss) {
            case 0: return Constants.SYS_GPS;
            case 1: return Constants.SYS_GLO;
            case 2: return Constants.SYS_GAL;
            case 3: return Constants.SYS_CMP;
            case 4: return Constants.SYS_QZS;
            case 5: return Constants.SYS_SBS;
            case 7: return Constants.SYS_CMP;
            default: return -1;
        }
    }

    private List<Integer> decodeLocalSat(int netmask) {
        List<Integer> sats = new ArrayList<>();
        for (int k = 0; k < nsatN; k++) {
            if (isset(netmask, nsatN, k)) {
                sats.add(satN[k]);
            }
        }
        return sats;
    }

    private static List<Integer> decodeMask(long din, int bitlen, int ofst) {
        List<Integer> v = new ArrayList<>();
        for (int k = 0; k < bitlen; k++) {
            if ((din & (1L << (bitlen - k - 1))) != 0) {
                v.add(k + ofst);
            }
        }
        return v;
    }

    private int decodeHead(byte[] msg, int i, int st) {
        if (st == CssrMessageType.MASK.value) {
            tow = BitUtils.getbitu(msg, i, 20);
            i += 20;
            tow0 = ((long) tow / 3600) * 3600;
        } else {
            long dtow = BitUtils.getbitu(msg, i, 12);
            i += 12;
            if (tow0 >= 0) {
                tow = tow0 + dtow;
            }
        }
        if (week >= 0) {
            time = TimeSystem.gpst2time(week, tow);
        }
        return i;
    }

    private int[] decodeHeadFields(byte[] msg, int i) {
        long udi = BitUtils.getbitu(msg, i, 4); i += 4;
        long mi = BitUtils.getbitu(msg, i, 1); i += 1;
        int iodssrHead = (int) BitUtils.getbitu(msg, i, 4); i += 4;
        return new int[]{i, (int) udi, (int) mi, iodssrHead};
    }

    public int decodeL6Msg(byte[] msg, int ofst) {
        int i = ofst * 8;
        long preamble = BitUtils.getbitu(msg, i, 32); i += 32;
        if (preamble != 0x1acffc1dL) return -1;

        int prn = (int) BitUtils.getbitu(msg, i, 8); i += 8;
        int vendor = (int) BitUtils.getbitu(msg, i, 3); i += 3;
        int facilityCode = (int) BitUtils.getbitu(msg, i, 2); i += 2;
        int pt = (int) BitUtils.getbitu(msg, i, 2); i += 2;
        int sidVal = (int) BitUtils.getbitu(msg, i, 1); i += 1;
        int alert = (int) BitUtils.getbitu(msg, i, 1); i += 1;

        if (sidVal == 1) {
            fcnt = 0;
            l6BuffP = l6Buff.clone();
            l6Buff = new byte[250 * 10];
        }

        int[][] facilityT = {{0, 2, 1, 3}, {2, 0, 3, 1}};
        int newFacility;
        if (vendor == 5) {
            newFacility = facilityT[pt][facilityCode];
        } else {
            newFacility = facilityCode;
        }

        if (facility >= 0 && newFacility != facility) {
            fcnt = -1;
        }
        facility = newFacility;
        pattern = pt;
        sid = sidVal;

        if (fcnt < 0) {
            log.warn("facility changed or not initialized");
            return -1;
        }

        copyBuff(msg, l6Buff, i, 1695 * fcnt, 1695);
        fcnt++;

        return 0;
    }

    private void copyBuff(byte[] src, byte[] dst, int srcBitPos, int dstBitPos, int bitLen) {
        for (int k = 0; k < bitLen; k++) {
            int srcByte = (srcBitPos + k) / 8;
            int srcBit = (srcBitPos + k) % 8;
            int dstByte = (dstBitPos + k) / 8;
            int dstBit = (dstBitPos + k) % 8;
            if (srcByte >= src.length || dstByte >= dst.length) break;
            int bit = (src[srcByte] >> (7 - srcBit)) & 1;
            if (bit == 1) {
                dst[dstByte] |= (byte) (1 << (7 - dstBit));
            } else {
                dst[dstByte] &= (byte) ~(1 << (7 - dstBit));
            }
        }
    }

    public int decodeCssr(byte[] msg, int startBit, Nav nav) {
        int i = startBit;
        long msgtype = BitUtils.getbitu(msg, i, 12); i += 12;
        int subtypeVal = (int) BitUtils.getbitu(msg, i, 4); i += 4;

        if (msgtype != CSSR_MSGTYPE) return -1;
        subtype = subtypeVal;

        CssrMessageType mt = CssrMessageType.fromValue(subtypeVal);
        if (mt == null) {
            log.warn("unknown CSSR subtype: {}", subtypeVal);
            return -1;
        }

        switch (mt) {
            case MASK:
                i = decodeCssrMask(msg, i, nav);
                break;
            case ORBIT:
                i = decodeCssrOrbit(msg, i, 0, nav);
                break;
            case CLOCK:
                i = decodeCssrClock(msg, i, 0, nav);
                break;
            case CBIAS:
                i = decodeCssrCbias(msg, i, 0, nav);
                break;
            case PBIAS:
                i = decodeCssrPbias(msg, i, 0, nav);
                break;
            case BIAS:
                i = decodeCssrBias(msg, i, nav);
                break;
            case URA:
                i = decodeCssrUra(msg, i, 0, nav);
                break;
            case STEC:
                i = decodeCssrStec(msg, i, nav);
                break;
            case GRID:
                i = decodeCssrGrid(msg, i, nav);
                break;
            case COMBINED:
                i = decodeCssrComb(msg, i, 0, nav);
                break;
            case ATMOS:
                i = decodeCssrAtmos(msg, i, nav);
                break;
            case HCLOCK:
                i = decodeCssrHrClock(msg, i, 0, nav);
                break;
            default:
                log.debug("CSSR subtype {} not implemented", subtypeVal);
                break;
        }

        if (i <= 0) return -1;
        return i;
    }

    private int decodeCssrMask(byte[] msg, int i, Nav nav) {
        i = decodeHead(msg, i, CssrMessageType.MASK.value);
        int[] hf = decodeHeadFields(msg, i);
        i = hf[0];
        int iodssrHead = hf[3];

        ngnss = (int) BitUtils.getbitu(msg, i, 4); i += 4;
        flgNet = false;

        if (iodssr != iodssrHead) {
            satNP = satN.clone();
            iodssrP = iodssr;
        }
        iodssr = iodssrHead;

        List<Integer> satList = new ArrayList<>();
        List<Integer> sysList = new ArrayList<>();
        List<Integer> nsigList = new ArrayList<>();
        sigN.clear();
        nsatN = 0;
        nsigTotal = 0;
        nsigMax = 0;

        for (int j = 0; j < ngnss; j++) {
            int gnss = (int) BitUtils.getbitu(msg, i, 4); i += 4;
            long svmask = BitUtils.getbitu(msg, i, 40); i += 40;
            long sigmask = BitUtils.getbitu(msg, i, 16); i += 16;
            int cma = (int) BitUtils.getbitu(msg, i, 1); i += 1;

            int sys = gnss2sys(gnss);
            List<Integer> prns = decodeMask(svmask, 40, 1);
            List<Integer> sigs = decodeMask(sigmask, 16, 0);
            int nsat = prns.size();
            int nsig = sigs.size();
            nsatN += nsat;
            if (nsig > nsigMax) nsigMax = nsig;

            int[] vc = null;
            if (cma == 1 && nsat > 0 && nsig > 0) {
                vc = new int[nsat];
                for (int k = 0; k < nsat; k++) {
                    long v = BitUtils.getbitu(msg, i, nsig);
                    i += nsig;
                    vc[k] = (int) v;
                }
            }

            for (int k = 0; k < nsat; k++) {
                int prn = prns.get(k);
                if (gnss == 4) prn += 192;
                else if (gnss == 7) prn += 18;

                int sat = SatUtils.satno(sys, prn);
                sysList.add(sys);
                satList.add(sat);

                List<Integer> satSigs = new ArrayList<>();
                if (cma == 1 && vc != null) {
                    List<Integer> sigS = decodeMask(vc[k], nsig, 0);
                    satSigs.addAll(sigS);
                    nsigList.add(sigS.size());
                    nsigTotal += sigS.size();
                } else {
                    satSigs.addAll(sigs);
                    nsigList.add(nsig);
                    nsigTotal += nsig;
                }
                sigN.put(sat, satSigs);
            }
        }

        satN = satList.stream().mapToInt(Integer::intValue).toArray();
        sysN = sysList.stream().mapToInt(Integer::intValue).toArray();
        nsigN = nsigList.stream().mapToInt(Integer::intValue).toArray();

        lc[0].cstat |= (1 << CT_MASK);
        lc[0].setT0(0, CT_MASK, time);
        return i;
    }

    private int decodeOrbSat(byte[] msg, int i, int sat, int sys, int inet) {
        int n = (sys == Constants.SYS_GAL) ? 10 : 8;
        int iode = (int) BitUtils.getbitu(msg, i, n); i += n;
        int dx = BitUtils.getbits(msg, i, DORB_BLEN[0]); i += DORB_BLEN[0];
        int dy = BitUtils.getbits(msg, i, DORB_BLEN[1]); i += DORB_BLEN[1];
        int dz = BitUtils.getbits(msg, i, DORB_BLEN[2]); i += DORB_BLEN[2];

        double[] dorb = new double[3];
        dorb[0] = sval(dx, DORB_BLEN[0], DORB_SCL[0]);
        dorb[1] = sval(dy, DORB_BLEN[1], DORB_SCL[1]);
        dorb[2] = sval(dz, DORB_BLEN[2], DORB_SCL[2]);

        lc[inet].iode.put(sat, iode);
        lc[inet].dorb.put(sat, dorb);
        return i;
    }

    private int decodeClkSat(byte[] msg, int i, int sat, int inet) {
        int dclk = BitUtils.getbits(msg, i, DCLK_BLEN); i += DCLK_BLEN;
        lc[inet].dclk.put(sat, sval(dclk, DCLK_BLEN, DCLK_SCL));
        return i;
    }

    private int decodeCbiasSat(byte[] msg, int i, int sat, int rsig, int inet) {
        int cb = BitUtils.getbits(msg, i, CB_BLEN); i += CB_BLEN;
        lc[inet].cbias.computeIfAbsent(sat, k -> new HashMap<>());
        lc[inet].cbias.get(sat).put(rsig, sval(cb, CB_BLEN, CB_SCL));
        return i;
    }

    private int decodePbiasSat(byte[] msg, int i, int sat, int rsig, int inet) {
        int pb = BitUtils.getbits(msg, i, PB_BLEN); i += PB_BLEN;
        int di = (int) BitUtils.getbitu(msg, i, 2); i += 2;
        lc[inet].pbias.computeIfAbsent(sat, k -> new HashMap<>());
        lc[inet].pbias.get(sat).put(rsig, sval(pb, PB_BLEN, PB_SCL));
        return i;
    }

    private int decodeCssrOrbit(byte[] msg, int i, int inet, Nav nav) {
        i = decodeHead(msg, i, -1);
        int[] hf = decodeHeadFields(msg, i);
        i = hf[0];
        flgNet = false;

        lc[inet].dorb.clear();
        lc[inet].iode.clear();

        for (int k = 0; k < nsatN; k++) {
            i = decodeOrbSat(msg, i, satN[k], sysN[k], inet);
            lc[inet].setT0(satN[k], CT_ORBIT, time);
            writeSsrOrbit(nav, satN[k], lc[inet].dorb.get(satN[k]), lc[inet].iode.get(satN[k]));
        }

        lc[inet].cstat |= (1 << CT_ORBIT);
        return i;
    }

    private int decodeCssrClock(byte[] msg, int i, int inet, Nav nav) {
        i = decodeHead(msg, i, -1);
        int[] hf = decodeHeadFields(msg, i);
        i = hf[0];
        flgNet = false;

        if ((lc[0].cstat & (1 << CT_MASK)) != (1 << CT_MASK)) return -1;

        lc[inet].dclk.clear();
        for (int k = 0; k < nsatN; k++) {
            i = decodeClkSat(msg, i, satN[k], inet);
            lc[inet].setT0(satN[k], CT_CLOCK, time);
            writeSsrClock(nav, satN[k], lc[inet].dclk.get(satN[k]));
        }

        lc[inet].cstat |= (1 << CT_CLOCK);
        return i;
    }

    private int decodeCssrCbias(byte[] msg, int i, int inet, Nav nav) {
        i = decodeHead(msg, i, -1);
        int[] hf = decodeHeadFields(msg, i);
        i = hf[0];
        flgNet = false;

        lc[inet].cbias.clear();
        for (int k = 0; k < nsatN; k++) {
            int sat = satN[k];
            lc[inet].cbias.put(sat, new HashMap<>());
            List<Integer> sigs = sigN.get(sat);
            if (sigs != null) {
                for (int j = 0; j < sigs.size(); j++) {
                    int rsig = sigs.get(j);
                    i = decodeCbiasSat(msg, i, sat, rsig, inet);
                    writeSsrCodeBias(nav, sat, rsig, lc[inet].cbias.get(sat).get(rsig));
                }
            }
            lc[inet].setT0(sat, CT_CBIAS, time);
        }

        lc[inet].cstat |= (1 << CT_CBIAS);
        return i;
    }

    private int decodeCssrPbias(byte[] msg, int i, int inet, Nav nav) {
        i = decodeHead(msg, i, -1);
        int[] hf = decodeHeadFields(msg, i);
        i = hf[0];
        flgNet = false;

        lc[inet].pbias.clear();
        for (int k = 0; k < nsatN; k++) {
            int sat = satN[k];
            lc[inet].pbias.put(sat, new HashMap<>());
            List<Integer> sigs = sigN.get(sat);
            if (sigs != null) {
                for (int j = 0; j < sigs.size(); j++) {
                    int rsig = sigs.get(j);
                    i = decodePbiasSat(msg, i, sat, rsig, inet);
                    writeSsrPhaseBias(nav, sat, rsig, lc[inet].pbias.get(sat).get(rsig));
                }
            }
            lc[inet].setT0(sat, CT_PBIAS, time);
        }

        lc[inet].cstat |= (1 << CT_PBIAS);
        return i;
    }

    private int decodeCssrBias(byte[] msg, int i, Nav nav) {
        int inetLocal = 0;
        i = decodeHead(msg, i, -1);
        int[] hf = decodeHeadFields(msg, i);
        i = hf[0];

        int cb = (int) BitUtils.getbitu(msg, i, 1); i += 1;
        int pb = (int) BitUtils.getbitu(msg, i, 1); i += 1;
        int net = (int) BitUtils.getbitu(msg, i, 1); i += 1;
        flgNet = (net != 0);

        int svmaskn = 0;
        if (flgNet) {
            inetLocal = (int) BitUtils.getbitu(msg, i, 5); i += 5;
            svmaskn = 0;
            for (int k = 0; k < nsatN; k++) {
                svmaskn |= ((int) BitUtils.getbitu(msg, i, 1)) << (nsatN - k - 1);
                i += 1;
            }
            inet = inetLocal;
        }

        if (cb != 0) lc[inet].cbias.clear();
        if (pb != 0) lc[inet].pbias.clear();

        for (int k = 0; k < nsatN; k++) {
            if (flgNet && !isset(svmaskn, nsatN, k)) continue;
            int sat = satN[k];
            if (cb != 0) lc[inet].cbias.put(sat, new HashMap<>());
            if (pb != 0) lc[inet].pbias.put(sat, new HashMap<>());

            List<Integer> sigs = sigN.get(sat);
            if (sigs != null) {
                for (int j = 0; j < (k < nsigN.length ? nsigN[k] : sigs.size()); j++) {
                    if (cb != 0) {
                        int rsig = sigs.get(j);
                        i = decodeCbiasSat(msg, i, sat, rsig, inet);
                        writeSsrCodeBias(nav, sat, rsig, lc[inet].cbias.get(sat).get(rsig));
                        lc[inet].setT0(sat, CT_CBIAS, time);
                    }
                    if (pb != 0) {
                        int rsig = sigs.get(j);
                        i = decodePbiasSat(msg, i, sat, rsig, inet);
                        writeSsrPhaseBias(nav, sat, rsig, lc[inet].pbias.get(sat).get(rsig));
                        lc[inet].setT0(sat, CT_PBIAS, time);
                    }
                }
            }
        }

        if (cb != 0) lc[inet].cstat |= (1 << CT_CBIAS);
        if (pb != 0) lc[inet].cstat |= (1 << CT_PBIAS);
        return i;
    }

    private int decodeCssrUra(byte[] msg, int i, int inet, Nav nav) {
        i = decodeHead(msg, i, -1);
        int[] hf = decodeHeadFields(msg, i);
        i = hf[0];
        int iodssrHead = hf[3];

        if (iodssr != iodssrHead) return -1;

        lc[inet].ura.clear();
        for (int k = 0; k < nsatN; k++) {
            int sat = satN[k];
            int cls = (int) BitUtils.getbitu(msg, i, 3); i += 3;
            int val = (int) BitUtils.getbitu(msg, i, 3); i += 3;
            lc[inet].ura.put(sat, qualityIdx(cls, val));
            writeSsrUra(nav, sat, cls, val);
        }

        lc[inet].cstat |= (1 << CT_URA);
        return i;
    }

    private double[] decodeCssrStecCoeff(byte[] msg, int stype, int i) {
        double[] ci = new double[6];
        int v0 = BitUtils.getbits(msg, i, 14); i += 14;
        ci[0] = sval(v0, 14, 0.05);

        if (stype > 0) {
            int v1 = BitUtils.getbits(msg, i, 12); i += 12;
            int v2 = BitUtils.getbits(msg, i, 12); i += 12;
            ci[1] = sval(v1, 12, 0.02);
            ci[2] = sval(v2, 12, 0.02);
        }
        if (stype > 1) {
            int v3 = BitUtils.getbits(msg, i, 10); i += 10;
            ci[3] = sval(v3, 10, 0.02);
        }
        if (stype > 2) {
            int v4 = BitUtils.getbits(msg, i, 8); i += 8;
            int v5 = BitUtils.getbits(msg, i, 8); i += 8;
            ci[4] = sval(v4, 8, 0.005);
            ci[5] = sval(v5, 8, 0.005);
        }

        int[] result = new int[]{i};
        return ci;
    }

    private int decodeCssrStecCoeffAndStore(byte[] msg, int stype, int i, int sat, int inet) {
        double[] ci = new double[6];
        int v0 = BitUtils.getbits(msg, i, 14); i += 14;
        ci[0] = sval(v0, 14, 0.05);
        if (stype > 0) {
            int v1 = BitUtils.getbits(msg, i, 12); i += 12;
            int v2 = BitUtils.getbits(msg, i, 12); i += 12;
            ci[1] = sval(v1, 12, 0.02);
            ci[2] = sval(v2, 12, 0.02);
        }
        if (stype > 1) {
            int v3 = BitUtils.getbits(msg, i, 10); i += 10;
            ci[3] = sval(v3, 10, 0.02);
        }
        if (stype > 2) {
            int v4 = BitUtils.getbits(msg, i, 8); i += 8;
            int v5 = BitUtils.getbits(msg, i, 8); i += 8;
            ci[4] = sval(v4, 8, 0.005);
            ci[5] = sval(v5, 8, 0.005);
        }
        lc[inet].ci.put(sat, ci);
        return i;
    }

    private int decodeCssrStec(byte[] msg, int i, Nav nav) {
        i = decodeHead(msg, i, -1);
        int[] hf = decodeHeadFields(msg, i);
        i = hf[0];
        int iodssrHead = hf[3];

        if (iodssr != iodssrHead) return -1;

        flgNet = true;
        int stype = (int) BitUtils.getbitu(msg, i, 2); i += 2;
        int inetLocal = (int) BitUtils.getbitu(msg, i, 5); i += 5;

        int netmask = 0;
        for (int k = 0; k < nsatN; k++) {
            netmask |= ((int) BitUtils.getbitu(msg, i, 1)) << (nsatN - k - 1);
            i += 1;
        }

        inet = inetLocal;
        List<Integer> localSats = decodeLocalSat(netmask);
        lc[inet].satN = localSats.stream().mapToInt(Integer::intValue).toArray();
        lc[inet].nsatN = localSats.size();
        lc[inet].flgStec = 2;
        lc[inet].ci.clear();

        for (int sat : lc[inet].satN) {
            lc[inet].setT0(sat, CT_STEC, time);
            int cls = (int) BitUtils.getbitu(msg, i, 3); i += 3;
            int val = (int) BitUtils.getbitu(msg, i, 3); i += 3;
            lc[inet].stecQuality.put(sat, qualityIdx(cls, val));
            i = decodeCssrStecCoeffAndStore(msg, stype, i, sat, inet);
        }

        lc[inet].cstat |= (1 << CT_STEC);
        return i;
    }

    private int decodeCssrGrid(byte[] msg, int i, Nav nav) {
        i = decodeHead(msg, i, -1);
        int[] hf = decodeHeadFields(msg, i);
        i = hf[0];
        int iodssrHead = hf[3];

        if (iodssr != iodssrHead) return -1;

        int ttype = (int) BitUtils.getbitu(msg, i, 2); i += 2;
        int range = (int) BitUtils.getbitu(msg, i, 1); i += 1;
        int inetLocal = (int) BitUtils.getbitu(msg, i, 5); i += 5;

        int svmaskn = 0;
        for (int k = 0; k < nsatN; k++) {
            svmaskn |= ((int) BitUtils.getbitu(msg, i, 1)) << (nsatN - k - 1);
            i += 1;
        }

        int cls = (int) BitUtils.getbitu(msg, i, 3); i += 3;
        int val = (int) BitUtils.getbitu(msg, i, 3); i += 3;
        int ng = (int) BitUtils.getbitu(msg, i, 6); i += 6;

        flgNet = true;
        inet = inetLocal;
        lc[inet].ng = ng;
        lc[inet].tropQuality = qualityIdx(cls, val);

        List<Integer> localSats = decodeLocalSat(svmaskn);
        lc[inet].satN = localSats.stream().mapToInt(Integer::intValue).toArray();
        lc[inet].nsatN = localSats.size();

        lc[inet].ct = new double[8];
        lc[inet].ct[0] = 2.3;
        lc[inet].ct[4] = 0.252;

        int sz = (range == 0) ? 7 : 16;
        lc[inet].dth = new double[ng];
        lc[inet].dtw = new double[ng];
        lc[inet].flgTrop = (ttype > 0) ? 3 : 0;

        lc[inet].dstec.clear();
        for (int sat : lc[inet].satN) {
            lc[inet].dstec.put(sat, new double[ng]);
            lc[inet].sSz.put(sat, sz);
        }

        for (int j = 0; j < ng; j++) {
            if (ttype > 0) {
                int dth = BitUtils.getbits(msg, i, 9); i += 9;
                int dtw = BitUtils.getbits(msg, i, 8); i += 8;
                lc[inet].dth[j] = sval(dth, 9, 0.004);
                lc[inet].dtw[j] = sval(dtw, 8, 0.004);
            }
            for (int sat : lc[inet].satN) {
                int dstec = BitUtils.getbits(msg, i, sz); i += sz;
                lc[inet].dstec.get(sat)[j] = sval(dstec, sz, 0.04);
            }
        }

        lc[inet].cstat |= (1 << CT_TROP);
        for (int sat : lc[inet].satN) {
            lc[inet].setT0(sat, CT_TROP, time);
        }
        return i;
    }

    private int decodeCssrComb(byte[] msg, int i, int inetLocal, Nav nav) {
        i = decodeHead(msg, i, -1);
        int[] hf = decodeHeadFields(msg, i);
        i = hf[0];
        int iodssrHead = hf[3];

        if (iodssr != iodssrHead) return -1;

        int orb = (int) BitUtils.getbitu(msg, i, 1); i += 1;
        int clk = (int) BitUtils.getbitu(msg, i, 1); i += 1;
        int net = (int) BitUtils.getbitu(msg, i, 1); i += 1;
        flgNet = (net != 0);

        int svmask = 0;
        if (flgNet) {
            inetLocal = (int) BitUtils.getbitu(msg, i, 5); i += 5;
            for (int k = 0; k < nsatN; k++) {
                svmask |= ((int) BitUtils.getbitu(msg, i, 1)) << (nsatN - k - 1);
                i += 1;
            }
            inet = inetLocal;
        }

        if (orb != 0) {
            lc[inet].dorb.clear();
            lc[inet].iode.clear();
        }
        if (clk != 0) {
            lc[inet].dclk.clear();
        }

        for (int k = 0; k < nsatN; k++) {
            if (flgNet && !isset(svmask, nsatN, k)) continue;
            int sat = satN[k];

            if (orb != 0) {
                i = decodeOrbSat(msg, i, sat, sysN[k], inet);
                lc[inet].setT0(sat, CT_ORBIT, time);
                writeSsrOrbit(nav, sat, lc[inet].dorb.get(sat), lc[inet].iode.get(sat));
            }
            if (clk != 0) {
                i = decodeClkSat(msg, i, sat, inet);
                lc[inet].setT0(sat, CT_CLOCK, time);
                writeSsrClock(nav, sat, lc[inet].dclk.get(sat));
            }
        }

        if (clk != 0) lc[inet].cstat |= (1 << CT_CLOCK);
        if (orb != 0) lc[inet].cstat |= (1 << CT_ORBIT);
        return i;
    }

    private int decodeCssrAtmos(byte[] msg, int i, Nav nav) {
        i = decodeHead(msg, i, -1);
        int[] hf = decodeHeadFields(msg, i);
        i = hf[0];
        int iodssrHead = hf[3];

        if (iodssr != iodssrHead) return -1;

        int tropFlg = (int) BitUtils.getbitu(msg, i, 2); i += 2;
        int stecFlg = (int) BitUtils.getbitu(msg, i, 2); i += 2;
        int inetLocal = (int) BitUtils.getbitu(msg, i, 5); i += 5;
        int ng = (int) BitUtils.getbitu(msg, i, 6); i += 6;

        flgNet = true;
        inet = inetLocal;
        lc[inet].ng = ng;
        lc[inet].ofst = 0;
        lc[inet].flgTrop = tropFlg;
        lc[inet].flgStec = stecFlg;

        if (tropFlg > 0) {
            int cls = (int) BitUtils.getbitu(msg, i, 3); i += 3;
            int val = (int) BitUtils.getbitu(msg, i, 3); i += 3;
            lc[inet].tropQuality = qualityIdx(cls, val);
        }

        lc[inet].ct = new double[8];

        if ((tropFlg & 2) != 0) {
            int ttype = (int) BitUtils.getbitu(msg, i, 2); i += 2;
            int t00 = BitUtils.getbits(msg, i, 9); i += 9;
            lc[inet].ct[0] = sval(t00, 9, 0.004) + 2.3;
            if (ttype > 0) {
                int t01 = BitUtils.getbits(msg, i, 7); i += 7;
                int t10 = BitUtils.getbits(msg, i, 7); i += 7;
                lc[inet].ct[1] = sval(t01, 7, 0.002);
                lc[inet].ct[2] = sval(t10, 7, 0.002);
            }
            if (ttype > 1) {
                int t11 = BitUtils.getbits(msg, i, 7); i += 7;
                lc[inet].ct[3] = sval(t11, 7, 0.001);
            }
        }

        if ((tropFlg & 1) != 0) {
            int szBit = (int) BitUtils.getbitu(msg, i, 1); i += 1;
            int ofstVal = (int) BitUtils.getbitu(msg, i, 4); i += 4;
            lc[inet].ct[4] = ofstVal * 0.02;
            int sz = (szBit == 0) ? 6 : 8;
            lc[inet].dtw = new double[ng];
            for (int k = 0; k < ng; k++) {
                int vtr = BitUtils.getbits(msg, i, sz); i += sz;
                lc[inet].dtw[k] = sval(vtr, sz, 0.004);
            }
        }

        int netmask = 0;
        for (int k = 0; k < nsatN; k++) {
            netmask |= ((int) BitUtils.getbitu(msg, i, 1)) << (nsatN - k - 1);
            i += 1;
        }

        List<Integer> localSats = decodeLocalSat(netmask);
        lc[inet].satN = localSats.stream().mapToInt(Integer::intValue).toArray();
        lc[inet].nsatN = localSats.size();

        if ((stecFlg & 2) != 0) {
            lc[inet].ci.clear();
        }
        if ((stecFlg & 1) != 0) {
            lc[inet].dstec.clear();
        }

        for (int k = 0; k < lc[inet].nsatN; k++) {
            int sat = lc[inet].satN[k];
            lc[inet].setT0(sat, CT_STEC, time);

            if (stecFlg > 0) {
                int cls = (int) BitUtils.getbitu(msg, i, 3); i += 3;
                int val = (int) BitUtils.getbitu(msg, i, 3); i += 3;
                lc[inet].stecQuality.put(sat, qualityIdx(cls, val));
            }

            if ((stecFlg & 2) != 0) {
                int stype = (int) BitUtils.getbitu(msg, i, 2); i += 2;
                lc[inet].stype.put(sat, stype);
                i = decodeCssrStecCoeffAndStore(msg, stype, i, sat, inet);
            }

            if ((stecFlg & 1) != 0) {
                int szIdx = (int) BitUtils.getbitu(msg, i, 2); i += 2;
                int sz = STEC_SZ_T[szIdx];
                lc[inet].sSz.put(sat, sz);
                double scl = STEC_SCL_T[szIdx];
                double[] dstecArr = new double[ng];
                for (int j = 0; j < ng; j++) {
                    int v = BitUtils.getbits(msg, i, sz); i += sz;
                    dstecArr[j] = sval(v, sz, scl);
                }
                lc[inet].dstec.put(sat, dstecArr);
            }
        }

        if (tropFlg > 0) {
            lc[inet].cstat |= (1 << CT_TROP);
            lc[inet].setT0(0, CT_TROP, time);
        }
        if (stecFlg > 0) {
            lc[inet].cstat |= (1 << CT_STEC);
        }
        return i;
    }

    private int decodeCssrHrClock(byte[] msg, int i, int inet, Nav nav) {
        i = decodeHead(msg, i, -1);
        int[] hf = decodeHeadFields(msg, i);
        i = hf[0];
        int iodssrHead = hf[3];

        if (iodssr != iodssrHead) return -1;

        for (int k = 0; k < nsatN; k++) {
            int sat = satN[k];
            int hrclk = BitUtils.getbits(msg, i, 10); i += 10;
            double hrclkVal = sval(hrclk, 10, 0.0016);
            writeSsrHrClock(nav, sat, hrclkVal);
            lc[inet].setT0(sat, CT_HCLOCK, time);
        }

        lc[inet].cstat |= (1 << CT_HCLOCK);
        return i;
    }

    public double[] getTrop(double dlat, double dlon) {
        int inetLocal = (gridDef != null) ? gridDef.inetRef : inet;
        if (inetLocal < 0 || inetLocal > MAXNET) return new double[]{0, 0};

        double trph = 0, trpw = 0;
        if ((lc[inetLocal].flgTrop & 2) != 0) {
            double[] p = {1, dlat, dlon, dlat * dlon};
            trph = lc[inetLocal].ct[0] * p[0] + lc[inetLocal].ct[1] * p[1]
                    + lc[inetLocal].ct[2] * p[2] + lc[inetLocal].ct[3] * p[3];
            trpw = lc[inetLocal].ct[4] * p[0] + lc[inetLocal].ct[5] * p[1]
                    + lc[inetLocal].ct[6] * p[2] + lc[inetLocal].ct[7] * p[3];
        }
        if ((lc[inetLocal].flgTrop & 1) != 0 && gridDef != null) {
            if (lc[inetLocal].dth != null) {
                for (int j = 0; j < gridDef.ngrid; j++) {
                    trph += lc[inetLocal].dth[gridDef.gridIndex[j] - 1] * gridDef.gridWeight[j];
                }
            }
            if (lc[inetLocal].dtw != null) {
                for (int j = 0; j < gridDef.ngrid; j++) {
                    trpw += lc[inetLocal].dtw[gridDef.gridIndex[j] - 1] * gridDef.gridWeight[j];
                }
            }
        }
        return new double[]{trph, trpw};
    }

    public double[] getStec(double dlat, double dlon) {
        int inetLocal = (gridDef != null) ? gridDef.inetRef : inet;
        if (inetLocal < 0 || inetLocal > MAXNET) return new double[0];

        int nsat = lc[inetLocal].nsatN;
        double[] stec = new double[nsat];
        for (int k = 0; k < nsat; k++) {
            int sat = lc[inetLocal].satN[k];
            if ((lc[inetLocal].flgStec & 2) != 0) {
                double[] ci = lc[inetLocal].ci.get(sat);
                if (ci != null) {
                    if (ci.length >= 3 && ci[3] == 0 && ci[4] == 0 && ci[5] == 0) {
                        stec[k] = ci[0] + ci[1] * dlat + ci[2] * dlon;
                    } else {
                        stec[k] = ci[0] + ci[1] * dlat + ci[2] * dlon
                                + ci[3] * dlat * dlon + ci[4] * dlat * dlat + ci[5] * dlon * dlon;
                    }
                }
            }
            if ((lc[inetLocal].flgStec & 1) != 0 && gridDef != null) {
                double[] dstec = lc[inetLocal].dstec.get(sat);
                if (dstec != null) {
                    for (int j = 0; j < gridDef.ngrid; j++) {
                        stec[k] += dstec[gridDef.gridIndex[j] - 1] * gridDef.gridWeight[j];
                    }
                }
            }
        }
        return stec;
    }

    public boolean chkStat() {
        int inetRef = (gridDef != null) ? gridDef.inetRef : inet;
        int csGlobal = lc[0].cstat;
        int csLocal = (inetRef >= 0 && inetRef <= MAXNET) ? lc[inetRef].cstat : 0;

        if ((csGlobal & 0x0F) != 0x0F) return false;
        if ((csLocal & 0x60) != 0x60) return false;
        if (localPbias && (csLocal & 0x10) != 0x10) return false;
        return true;
    }

    private void writeSsrOrbit(Nav nav, int sat, double[] dorb, Integer iode) {
        if (nav == null || sat <= 0 || sat > Constants.MAXSAT) return;
        if (nav.ssr[sat - 1] == null) nav.ssr[sat - 1] = new Ssr();
        Ssr s = nav.ssr[sat - 1];
        if (dorb != null) {
            System.arraycopy(dorb, 0, s.deph, 0, 3);
        }
        if (iode != null) s.iode = iode;
        s.t0[0] = new GTime(time);
        s.update = 1;
    }

    private void writeSsrClock(Nav nav, int sat, Double dclk) {
        if (nav == null || sat <= 0 || sat > Constants.MAXSAT) return;
        if (nav.ssr[sat - 1] == null) nav.ssr[sat - 1] = new Ssr();
        Ssr s = nav.ssr[sat - 1];
        if (dclk != null) s.dclk[0] = dclk;
        s.t0[1] = new GTime(time);
        s.update = 1;
    }

    private void writeSsrHrClock(Nav nav, int sat, double hrclk) {
        if (nav == null || sat <= 0 || sat > Constants.MAXSAT) return;
        if (nav.ssr[sat - 1] == null) nav.ssr[sat - 1] = new Ssr();
        Ssr s = nav.ssr[sat - 1];
        s.hrclk = hrclk;
        s.t0[2] = new GTime(time);
        s.update = 1;
    }

    private void writeSsrCodeBias(Nav nav, int sat, int code, Double cbias) {
        if (nav == null || sat <= 0 || sat > Constants.MAXSAT || cbias == null) return;
        if (nav.ssr[sat - 1] == null) nav.ssr[sat - 1] = new Ssr();
        Ssr s = nav.ssr[sat - 1];
        if (code >= 0 && code < Constants.MAXCODE) {
            s.cbias[code] = (float) (double) cbias;
        }
        s.t0[4] = new GTime(time);
        s.update = 1;
    }

    private void writeSsrPhaseBias(Nav nav, int sat, int code, Double pbias) {
        if (nav == null || sat <= 0 || sat > Constants.MAXSAT || pbias == null) return;
        if (nav.ssr[sat - 1] == null) nav.ssr[sat - 1] = new Ssr();
        Ssr s = nav.ssr[sat - 1];
        if (code >= 0 && code < Constants.MAXCODE) {
            s.pbias[code] = pbias;
        }
        s.t0[5] = new GTime(time);
        s.update = 1;
    }

    private void writeSsrUra(Nav nav, int sat, int cls, int val) {
        if (nav == null || sat <= 0 || sat > Constants.MAXSAT) return;
        if (nav.ssr[sat - 1] == null) nav.ssr[sat - 1] = new Ssr();
        Ssr s = nav.ssr[sat - 1];
        s.ura = (cls << 3) | val;
        s.t0[3] = new GTime(time);
        s.update = 1;
    }
}