package org.rtklib.java.vrs.rtcm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.rtklib.java.common.SatUtils;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.data.*;
import org.rtklib.java.rtcm.AuxData;
import org.rtklib.java.rtcm.ObservationEpoch;
import org.rtklib.java.rtcm.RtcmCallbackDecoder;
import org.rtklib.java.rtcm.RtcmDataHandler;
import org.rtklib.java.time.TimeSystem;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class Rtcm3MsmEncoderTest {

    @Test
    @DisplayName("MSM4编码：构造GPS观测值→编码→解码→验证")
    void testMsm4EncodeDecodeGps() {
        GTime time = TimeSystem.gpst2time(2300, 345678.0);

        Obs obs = new Obs();
        obs.n = 0;

        int[] gpsSats = {1, 5, 10, 15, 20, 30};
        double[] pseudoRanges = {20200000.0, 20300000.0, 20150000.0, 20400000.0, 20250000.0, 20100000.0};
        double[] carrierPhases = {105000000.0, 106000000.0, 104500000.0, 107000000.0, 105500000.0, 104000000.0};
        float[] snrs = {45.0f, 42.0f, 48.0f, 40.0f, 44.0f, 46.0f};

        for (int i = 0; i < gpsSats.length; i++) {
            Obsd obsd = obs.data[obs.n];
            obsd.time = new GTime(time);
            obsd.sat = gpsSats[i];
            obsd.rcv = 2;
            obsd.LLI = new int[Constants.NFREQ + Constants.NEXOBS];
            obsd.code = new int[Constants.NFREQ + Constants.NEXOBS];
            obsd.L = new double[Constants.NFREQ + Constants.NEXOBS];
            obsd.P = new double[Constants.NFREQ + Constants.NEXOBS];
            obsd.D = new float[Constants.NFREQ + Constants.NEXOBS];
            obsd.SNR = new float[Constants.NFREQ + Constants.NEXOBS];
            obsd.Lstd = new float[Constants.NFREQ + Constants.NEXOBS];
            obsd.Pstd = new float[Constants.NFREQ + Constants.NEXOBS];

            obsd.code[0] = Constants.CODE_L1C;
            obsd.P[0] = pseudoRanges[i];
            obsd.L[0] = carrierPhases[i];
            obsd.SNR[0] = snrs[i];
            obsd.D[0] = 1000.0f + i * 100.0f;

            obsd.code[1] = Constants.CODE_L2C;
            obsd.P[1] = pseudoRanges[i] + 5.0;
            obsd.L[1] = carrierPhases[i] * 0.779 + 100.0;
            obsd.SNR[1] = snrs[i] - 3.0f;
            obsd.D[1] = 800.0f + i * 80.0f;

            obs.n++;
        }

        System.out.printf("输入: %d颗GPS卫星, time=%s%n", obs.n, time);
        for (int i = 0; i < obs.n; i++) {
            Obsd d = obs.data[i];
            int[] prn = new int[1];
            SatUtils.satsys(d.sat, prn);
            System.out.printf("  sat=%d(GPS PRN %d) P[0]=%.4f L[0]=%.4f code[0]=%d rcv=%d%n",
                    d.sat, prn[0], d.P[0], d.L[0], d.code[0], d.rcv);
        }

        Rtcm3Encoder encoder = new Rtcm3Encoder();
        encoder.stationId = 1;
        encoder.obs = obs;
        encoder.nav = new Nav();

        Sta sta = new Sta();
        sta.pos[0] = -1337908.0;
        sta.pos[1] = 5320124.0;
        sta.pos[2] = 3235708.0;
        encoder.sta = sta;

        byte[] msg1005 = encoder.encode(1005, 0, 0);
        assertNotNull(msg1005, "1005编码应成功");
        System.out.printf("1005消息: %d bytes%n", msg1005.length);

        byte[] msg1074 = encoder.encode(1074, 0, 0);
        assertNotNull(msg1074, "1074 (GPS MSM4)编码应成功");
        System.out.printf("1074消息: %d bytes%n", msg1074.length);

        List<byte[]> allMsgs = new ArrayList<>();
        allMsgs.add(msg1005);
        allMsgs.add(msg1074);

        int totalLen = 0;
        for (byte[] m : allMsgs) totalLen += m.length;
        byte[] combined = new byte[totalLen];
        int pos = 0;
        for (byte[] m : allMsgs) {
            System.arraycopy(m, 0, combined, pos, m.length);
            pos += m.length;
        }

        System.out.println();
        System.out.println("--- 解码验证 ---");

        List<ObservationEpoch> decodedEpochs = new ArrayList<>();
        Sta[] decodedSta = new Sta[1];

        RtcmDataHandler handler = new RtcmDataHandler() {
            @Override public void onStation(Sta s) {
                decodedSta[0] = s;
                System.out.printf("解码站坐标: X=%.4f Y=%.4f Z=%.4f%n", s.pos[0], s.pos[1], s.pos[2]);
            }
            @Override public void onEph(Eph eph) {}
            @Override public void onGeph(Geph geph) {}
            @Override public void onObservationEpoch(ObservationEpoch epoch) {
                decodedEpochs.add(epoch);
            }
            @Override public void onSsr(Ssr ssr) {}
            @Override public void onAuxData(AuxData aux) {}
            @Override public void onFinish() {}
        };

        RtcmCallbackDecoder decoder = new RtcmCallbackDecoder(handler);
        decoder.feed(combined, 0, combined.length);
        decoder.finish();

        assertNotNull(decodedSta[0], "应解码出站坐标");
        assertEquals(1, decodedEpochs.size(), "应解码出1个观测历元");

        ObservationEpoch decodedEpoch = decodedEpochs.get(0);
        List<Obsd> decodedObs = decodedEpoch.getObservations();
        System.out.printf("解码卫星数: %d%n", decodedObs.size());

        int validP = 0, validL = 0, validCode = 0;
        for (Obsd o : decodedObs) {
            System.out.printf("  sat=%d P[0]=%.4f L[0]=%.4f code[0]=%d rcv=%d SNR[0]=%.1f%n",
                    o.sat, o.P[0], o.L[0], o.code[0], o.rcv, o.SNR[0]);
            if (o.P[0] != 0.0) validP++;
            if (o.L[0] != 0.0) validL++;
            if (o.code[0] != 0) validCode++;
        }

        System.out.printf("有效统计: P非零=%d, L非零=%d, code非零=%d (共%d颗)%n",
                validP, validL, validCode, decodedObs.size());

        assertTrue(validP > 0, "应有非零伪距");
        assertTrue(validL > 0, "应有非零载波相位");
        assertTrue(validCode > 0, "应有非零信号码");
        assertEquals(gpsSats.length, decodedObs.size(), "解码卫星数应与输入一致");
    }

    @Test
    @DisplayName("MSM4编码：构造BDS观测值→编码→解码→验证")
    void testMsm4EncodeDecodeBds() {
        GTime time = TimeSystem.gpst2time(2300, 345678.0);

        Obs obs = new Obs();
        obs.n = 0;

        int bdsOffset = Constants.NSATGPS + Constants.NSATGLO + Constants.NSATGAL + Constants.NSATQZS;
        int[] bdsPrns = {1, 3, 6, 10, 13, 19, 22, 30};
        double[] bdsPseudoRanges = {35200000.0, 35300000.0, 35150000.0, 35400000.0,
                35250000.0, 35100000.0, 35350000.0, 35200000.0};

        double bdsLam1 = Constants.CLIGHT / Constants.FREQ1_CMP;
        double bdsLam2 = Constants.CLIGHT / Constants.FREQ2_CMP;

        for (int i = 0; i < bdsPrns.length; i++) {
            Obsd obsd = obs.data[obs.n];
            obsd.time = new GTime(time);
            obsd.sat = bdsOffset + bdsPrns[i];
            obsd.rcv = 2;
            obsd.LLI = new int[Constants.NFREQ + Constants.NEXOBS];
            obsd.code = new int[Constants.NFREQ + Constants.NEXOBS];
            obsd.L = new double[Constants.NFREQ + Constants.NEXOBS];
            obsd.P = new double[Constants.NFREQ + Constants.NEXOBS];
            obsd.D = new float[Constants.NFREQ + Constants.NEXOBS];
            obsd.SNR = new float[Constants.NFREQ + Constants.NEXOBS];
            obsd.Lstd = new float[Constants.NFREQ + Constants.NEXOBS];
            obsd.Pstd = new float[Constants.NFREQ + Constants.NEXOBS];

            obsd.code[0] = Constants.CODE_L2I;
            obsd.P[0] = bdsPseudoRanges[i];
            obsd.L[0] = bdsPseudoRanges[i] / bdsLam1;
            obsd.SNR[0] = 43.0f;
            obsd.D[0] = 500.0f + i * 50.0f;

            obsd.code[1] = Constants.CODE_L7I;
            obsd.P[1] = bdsPseudoRanges[i] + 3.0;
            obsd.L[1] = bdsPseudoRanges[i] / bdsLam2;
            obsd.SNR[1] = 40.0f;
            obsd.D[1] = 400.0f + i * 40.0f;

            obs.n++;
        }

        System.out.printf("输入: %d颗BDS卫星, B1I波长=%.6f, B2I波长=%.6f%n", obs.n, bdsLam1, bdsLam2);

        for (int i = 0; i < Math.min(2, obs.n); i++) {
            Obsd d = obs.data[i];
            double freq0 = SatUtils.sat2freq(d.sat, d.code[0], new Nav());
            double freq1 = SatUtils.sat2freq(d.sat, d.code[1], new Nav());
            System.out.printf("  sat=%d code[0]=%d freq0=%.4f P[0]=%.4f L[0]=%.4f%n",
                    d.sat, d.code[0], freq0, d.P[0], d.L[0]);
            System.out.printf("  sat=%d code[1]=%d freq1=%.4f P[1]=%.4f L[1]=%.4f%n",
                    d.sat, d.code[1], freq1, d.P[1], d.L[1]);
        }

        Rtcm3Encoder encoder = new Rtcm3Encoder();
        encoder.stationId = 1;
        encoder.obs = obs;
        encoder.nav = new Nav();

        Sta sta = new Sta();
        sta.pos[0] = -1337908.0;
        sta.pos[1] = 5320124.0;
        sta.pos[2] = 3235708.0;
        encoder.sta = sta;

        byte[] msg1124 = encoder.encode(1124, 0, 0);
        assertNotNull(msg1124, "1124 (BDS MSM4)编码应成功");
        System.out.printf("1124消息: %d bytes%n", msg1124.length);

        List<ObservationEpoch> decodedEpochs = new ArrayList<>();
        RtcmDataHandler handler = new RtcmDataHandler() {
            @Override public void onStation(Sta s) {}
            @Override public void onEph(Eph eph) {}
            @Override public void onGeph(Geph geph) {}
            @Override public void onObservationEpoch(ObservationEpoch epoch) {
                decodedEpochs.add(epoch);
            }
            @Override public void onSsr(Ssr ssr) {}
            @Override public void onAuxData(AuxData aux) {}
            @Override public void onFinish() {}
        };

        RtcmCallbackDecoder decoder = new RtcmCallbackDecoder(handler);
        decoder.feed(msg1124, 0, msg1124.length);
        decoder.finish();

        assertEquals(1, decodedEpochs.size(), "应解码出1个观测历元");
        List<Obsd> decodedObs = decodedEpochs.get(0).getObservations();
        System.out.printf("解码BDS卫星数: %d%n", decodedObs.size());

        int validP = 0, validL = 0;
        for (Obsd o : decodedObs) {
            System.out.printf("  sat=%d P[0]=%.4f L[0]=%.4f code[0]=%d P[1]=%.4f L[1]=%.4f code[1]=%d%n",
                    o.sat, o.P[0], o.L[0], o.code[0], o.P[1], o.L[1], o.code[1]);
            if (o.P[0] != 0.0) validP++;
            if (o.L[0] != 0.0) validL++;
        }

        System.out.printf("有效统计: P非零=%d, L非零=%d (共%d颗)%n", validP, validL, decodedObs.size());
        assertTrue(validP > 0, "BDS应有非零伪距");
        assertTrue(validL > 0, "BDS应有非零载波相位");
    }

    @Test
    @DisplayName("MSM4编码：观测值全零时应返回null")
    void testMsm4EncodeEmptyObs() {
        Obs obs = new Obs();
        obs.n = 0;

        Rtcm3Encoder encoder = new Rtcm3Encoder();
        encoder.stationId = 1;
        encoder.obs = obs;
        encoder.nav = new Nav();

        byte[] result = encoder.encode(1074, 0, 0);
        assertNull(result, "空观测值编码应返回null");
        System.out.println("空观测值编码返回null - 正确");
    }

    @Test
    @DisplayName("MSM4编码：验证rcv字段对解码结果的影响")
    void testMsm4RcvField() {
        GTime time = TimeSystem.gpst2time(2300, 345678.0);

        Obs obs = new Obs();
        obs.n = 0;

        Obsd obsd = obs.data[0];
        obsd.time = new GTime(time);
        obsd.sat = 1;
        obsd.rcv = 2;
        obsd.LLI = new int[Constants.NFREQ + Constants.NEXOBS];
        obsd.code = new int[Constants.NFREQ + Constants.NEXOBS];
        obsd.L = new double[Constants.NFREQ + Constants.NEXOBS];
        obsd.P = new double[Constants.NFREQ + Constants.NEXOBS];
        obsd.D = new float[Constants.NFREQ + Constants.NEXOBS];
        obsd.SNR = new float[Constants.NFREQ + Constants.NEXOBS];
        obsd.Lstd = new float[Constants.NFREQ + Constants.NEXOBS];
        obsd.Pstd = new float[Constants.NFREQ + Constants.NEXOBS];

        obsd.code[0] = Constants.CODE_L1C;
        obsd.P[0] = 20200000.0;
        obsd.L[0] = 105000000.0;
        obsd.SNR[0] = 45.0f;
        obs.n = 1;

        Rtcm3Encoder encoder = new Rtcm3Encoder();
        encoder.stationId = 1;
        encoder.obs = obs;
        encoder.nav = new Nav();
        encoder.sta = new Sta();
        encoder.sta.pos[0] = -1337908.0;
        encoder.sta.pos[1] = 5320124.0;
        encoder.sta.pos[2] = 3235708.0;

        byte[] msg1074 = encoder.encode(1074, 0, 0);
        assertNotNull(msg1074, "编码应成功");

        List<ObservationEpoch> decodedEpochs = new ArrayList<>();
        RtcmDataHandler handler = new RtcmDataHandler() {
            @Override public void onStation(Sta s) {}
            @Override public void onEph(Eph eph) {}
            @Override public void onGeph(Geph geph) {}
            @Override public void onObservationEpoch(ObservationEpoch epoch) {
                decodedEpochs.add(epoch);
            }
            @Override public void onSsr(Ssr ssr) {}
            @Override public void onAuxData(AuxData aux) {}
            @Override public void onFinish() {}
        };

        RtcmCallbackDecoder decoder = new RtcmCallbackDecoder(handler);
        decoder.feed(msg1074, 0, msg1074.length);
        decoder.finish();

        if (!decodedEpochs.isEmpty()) {
            Obsd decoded = decodedEpochs.get(0).getObservations().get(0);
            System.out.printf("输入rcv=%d → 解码rcv=%d%n", 2, decoded.rcv);
            System.out.printf("P[0]=%.4f L[0]=%.4f code[0]=%d%n", decoded.P[0], decoded.L[0], decoded.code[0]);
            System.out.println();
            System.out.println("注意：RTCM MSM消息本身不携带rcv字段。");
            System.out.println("RTK处理器通过1005站坐标消息识别基准站，");
            System.out.println("MSM中的观测值由解码器根据上下文设置rcv。");
        } else {
            System.out.println("未解码出观测历元");
        }
    }

    @Test
    @DisplayName("MSM5编码：GPS观测值→编码→解码→验证")
    void testMsm5EncodeDecodeGps() {
        GTime time = TimeSystem.gpst2time(2300, 345678.0);

        Obs obs = new Obs();
        obs.n = 0;

        int[] gpsSats = {1, 5, 10, 15, 20};
        double[] pseudoRanges = {20200000.0, 20300000.0, 20150000.0, 20400000.0, 20250000.0};

        for (int i = 0; i < gpsSats.length; i++) {
            Obsd obsd = obs.data[obs.n];
            obsd.time = new GTime(time);
            obsd.sat = gpsSats[i];
            obsd.rcv = 2;
            obsd.LLI = new int[Constants.NFREQ + Constants.NEXOBS];
            obsd.code = new int[Constants.NFREQ + Constants.NEXOBS];
            obsd.L = new double[Constants.NFREQ + Constants.NEXOBS];
            obsd.P = new double[Constants.NFREQ + Constants.NEXOBS];
            obsd.D = new float[Constants.NFREQ + Constants.NEXOBS];
            obsd.SNR = new float[Constants.NFREQ + Constants.NEXOBS];
            obsd.Lstd = new float[Constants.NFREQ + Constants.NEXOBS];
            obsd.Pstd = new float[Constants.NFREQ + Constants.NEXOBS];

            obsd.code[0] = Constants.CODE_L1C;
            obsd.P[0] = pseudoRanges[i];
            obsd.L[0] = pseudoRanges[i] / 0.190;
            obsd.SNR[0] = 45.0f;
            obsd.D[0] = 1000.0f;

            obsd.code[1] = Constants.CODE_L2C;
            obsd.P[1] = pseudoRanges[i] + 5.0;
            obsd.L[1] = pseudoRanges[i] / 0.244 + 100.0;
            obsd.SNR[1] = 42.0f;
            obsd.D[1] = 800.0f;

            obs.n++;
        }

        Rtcm3Encoder encoder = new Rtcm3Encoder();
        encoder.stationId = 1;
        encoder.obs = obs;
        encoder.nav = new Nav();
        encoder.sta = new Sta();
        encoder.sta.pos[0] = -1337908.0;
        encoder.sta.pos[1] = 5320124.0;
        encoder.sta.pos[2] = 3235708.0;

        byte[] msg1075 = encoder.encode(1075, 0, 0);
        assertNotNull(msg1075, "1075 (GPS MSM5)编码应成功");
        System.out.printf("1075消息: %d bytes%n", msg1075.length);

        List<ObservationEpoch> decodedEpochs = new ArrayList<>();
        RtcmDataHandler handler = new RtcmDataHandler() {
            @Override public void onStation(Sta s) {}
            @Override public void onEph(Eph eph) {}
            @Override public void onGeph(Geph geph) {}
            @Override public void onObservationEpoch(ObservationEpoch epoch) {
                decodedEpochs.add(epoch);
            }
            @Override public void onSsr(Ssr ssr) {}
            @Override public void onAuxData(AuxData aux) {}
            @Override public void onFinish() {}
        };

        RtcmCallbackDecoder decoder = new RtcmCallbackDecoder(handler);
        decoder.feed(msg1075, 0, msg1075.length);
        decoder.finish();

        assertEquals(1, decodedEpochs.size(), "应解码出1个观测历元");
        List<Obsd> decodedObs = decodedEpochs.get(0).getObservations();
        System.out.printf("MSM5解码卫星数: %d%n", decodedObs.size());

        int validP = 0, validL = 0;
        for (Obsd o : decodedObs) {
            if (o.P[0] != 0.0) validP++;
            if (o.L[0] != 0.0) validL++;
            System.out.printf("  sat=%d P[0]=%.4f L[0]=%.4f code[0]=%d%n",
                    o.sat, o.P[0], o.L[0], o.code[0]);
        }

        assertTrue(validP > 0, "MSM5应有非零伪距");
        assertTrue(validL > 0, "MSM5应有非零载波相位");
    }
}