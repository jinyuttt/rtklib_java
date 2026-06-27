package org.rtklib.java.rtcm;

import org.rtklib.java.common.SatUtils;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.data.*;
import org.rtklib.java.time.TimeSystem;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class RtcmCallbackDecoder {

    private final Rtcm rtcm = new Rtcm();
    private final RtcmDataHandler handler;

    private byte[] pending = new byte[4096];
    private int pendingLen = 0;
    private boolean finished = false;

    private ObservationEpoch currentEpoch = null;
    private final List<ObservationEpoch> pendingEpochs = new ArrayList<>();

    public RtcmCallbackDecoder(RtcmDataHandler handler) {
        this.handler = handler;
    }

    public void feed(byte[] data, int offset, int length) {
        if (length <= 0 || finished) return;

        ensureCapacity(pendingLen + length);
        System.arraycopy(data, offset, pending, pendingLen, length);
        pendingLen += length;
        int pos = 0;
        while (pos < pendingLen) {
            int consumed = rtcm.input(pending, pos, pendingLen - pos);
            if (consumed > 0) {
                extractAndCallback();
                pos += consumed;
            } else if (consumed == 0) {
                break;
            } else {
                pos += 1;
            }
        }

        if (pos > 0) {
            if (pos < pendingLen) {
                System.arraycopy(pending, pos, pending, 0, pendingLen - pos);
            }
            pendingLen -= pos;
        }
    }

    public void finish() {
        if (!finished) {
            finished = true;
            flushCurrentEpochToPending();
            flushPendingEpochs();
            handler.onFinish();
        }
    }

    private void extractAndCallback() {
        int type = rtcm.type;

        if (isStationType(type)) {
            handler.onStation(copySta(rtcm.sta));
        }

        if (isSsrType(type) && rtcm.ephsat > 0) {
            int idx = rtcm.ephsat - 1;
            if (idx >= 0 && idx < rtcm.nav.ssr.length && rtcm.nav.ssr[idx] != null) {
                handler.onSsr(copySsr(rtcm.nav.ssr[idx]));
            }
        }

        if (isEphemerisType(type) && rtcm.ephsat != 0) {
            if (rtcm.ephset == 0) {
                Eph e = rtcm.nav.eph[rtcm.ephsat - 1];
                if (e != null && e.sat == rtcm.ephsat) {
                    handler.onEph(copyEph(e));
                }
            } else {
                int idx = rtcm.ephsat - 1 + Constants.MAXSAT;
                Eph e = rtcm.nav.eph[idx];
                if (e != null && e.sat == rtcm.ephsat) {
                    handler.onEph(copyEph(e));
                }
            }
            int[] prnArr = new int[1];
            int sys = SatUtils.satsys(rtcm.ephsat, prnArr);
            if (sys == Constants.SYS_GLO && prnArr[0] > 0) {
                Geph g = rtcm.nav.geph[prnArr[0] - 1];
                if (g != null && g.sat == rtcm.ephsat) {
                    handler.onGeph(copyGeph(g));
                }
            }
            flushPendingEpochs();
        }

        if (type == 1007 || type == 1008 || type == 1033) {
            if (rtcm.sta.antdes != null && !rtcm.sta.antdes.isEmpty()) {
                handler.onAuxData(new AuxData(type, rtcm.sta.antdes, rtcm.sta.antsno, rtcm.sta.rectype));
            }
        }

        if (isObservationType(type) && rtcm.obs.n > 0 && rtcm.obs.data[0].sat != 0) {
            GTime epochTime = rtcm.obs.data[0].time;
            if (!rtcm.isTimeInitialized()) {
                if (currentEpoch == null ||
                    Math.abs(TimeSystem.timediff(currentEpoch.time, epochTime)) > 1e-9) {
                    flushCurrentEpochToPending();
                    currentEpoch = new ObservationEpoch(epochTime);
                }
                for (int i = 0; i < rtcm.obs.n; i++) {
                    Obsd src = rtcm.obs.data[i];
                    if (src.sat == 0) continue;
                    mergeObsdIntoEpoch(currentEpoch, src);
                }
            } else {
                if (currentEpoch == null ||
                    Math.abs(TimeSystem.timediff(currentEpoch.time, epochTime)) > 1e-9) {
                    flushCurrentEpoch();
                    currentEpoch = new ObservationEpoch(epochTime);
                }
                for (int i = 0; i < rtcm.obs.n; i++) {
                    Obsd src = rtcm.obs.data[i];
                    if (src.sat == 0) continue;
                    mergeObsdIntoEpoch(currentEpoch, src);
                }
            }
        }
    }

    private void flushCurrentEpoch() {
        if (currentEpoch != null && !currentEpoch.obsList.isEmpty()) {
            handler.onObservationEpoch(currentEpoch);
            currentEpoch = null;
        }
    }

    private void flushCurrentEpochToPending() {
        if (currentEpoch != null && !currentEpoch.obsList.isEmpty()) {
            pendingEpochs.add(currentEpoch);
            currentEpoch = null;
        }
    }

    private void flushPendingEpochs() {
        flushCurrentEpochToPending();
        if (pendingEpochs.isEmpty()) return;
        GTime refTime = rtcm.time;
        int[] refWeekArr = new int[1];
        double refTow = TimeSystem.time2gpst(refTime, refWeekArr);
        int refWeek = refWeekArr[0];
        for (ObservationEpoch epoch : pendingEpochs) {
            int[] epochWeekArr = new int[1];
            double epochTow = TimeSystem.time2gpst(epoch.time, epochWeekArr);
            double dt = epochTow - refTow;
            int weekDiff = (int) Math.round(dt / 604800.0);
            int correctWeek = refWeek - weekDiff;
            GTime correctedTime = TimeSystem.gpst2time(correctWeek, epochTow);
            epoch.time = correctedTime;
            for (Obsd obs : epoch.obsList) {
                obs.time = new GTime(correctedTime);
            }
            handler.onObservationEpoch(epoch);
        }
        pendingEpochs.clear();
    }

    private Sta copySta(Sta src) {
        return new Sta(src);
    }

    private Ssr copySsr(Ssr src) {
        return new Ssr(src);
    }

    private Eph copyEph(Eph src) {
        return new Eph(src);
    }

    private Geph copyGeph(Geph src) {
        return new Geph(src);
    }

    private Obsd copyObsd(Obsd src) {
        return new Obsd(src);
    }

    private void mergeObsdIntoEpoch(ObservationEpoch epoch, Obsd src) {
        for (Obsd existing : epoch.obsList) {
            if (existing.sat == src.sat) {
                for (int j = 0; j < Constants.NFREQ + Constants.NEXOBS; j++) {
                    if (src.P[j] != 0.0) {
                        existing.P[j] = src.P[j];
                        existing.code[j] = src.code[j];
                        existing.Pstd[j] = src.Pstd[j];
                    }
                    if (src.L[j] != 0.0) {
                        existing.L[j] = src.L[j];
                        existing.LLI[j] = src.LLI[j];
                        existing.Lstd[j] = src.Lstd[j];
                        if (existing.code[j] == 0) existing.code[j] = src.code[j];
                    }
                    if (src.D[j] != 0.0f) {
                        existing.D[j] = src.D[j];
                    }
                    if (src.SNR[j] != 0.0f) {
                        existing.SNR[j] = src.SNR[j];
                    }
                }
                return;
            }
        }
        epoch.obsList.add(copyObsd(src));
    }

    private void ensureCapacity(int need) {
        if (need > pending.length) {
            pending = Arrays.copyOf(pending, Math.max(pending.length * 2, need + 1024));
        }
    }

    static boolean isObservationType(int type) {
        return (type >= 1001 && type <= 1004)
                || (type >= 1071 && type <= 1077)
                || (type >= 1081 && type <= 1087)
                || (type >= 1091 && type <= 1097)
                || (type >= 1101 && type <= 1107)
                || (type >= 1111 && type <= 1117)
                || (type >= 1121 && type <= 1127)
                || (type >= 1131 && type <= 1137);
    }

    static boolean isEphemerisType(int type) {
        return type == 1019 || type == 1020
                || type == 1041 || type == 1042
                || type == 1044 || type == 1045 || type == 1046;
    }

    static boolean isStationType(int type) {
        return type >= 1005 && type <= 1006;
    }

    static boolean isSsrType(int type) {
        return (type >= 1057 && type <= 1068)
                || (type >= 1240 && type <= 1270);
    }
}