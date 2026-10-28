package org.rtklib.java.networkvrs.output;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rtklib.java.coord.CoordTransform;
import org.rtklib.java.data.Eph;
import org.rtklib.java.data.GTime;
import org.rtklib.java.data.Geph;
import org.rtklib.java.data.Obsd;
import org.rtklib.java.data.Ssr;
import org.rtklib.java.data.Sta;
import org.rtklib.java.networkvrs.sync.StationObservation;
import org.rtklib.java.rtcm.AuxData;
import org.rtklib.java.rtcm.ObservationEpoch;
import org.rtklib.java.rtcm.RtcmCallbackDecoder;
import org.rtklib.java.rtcm.RtcmDataHandler;
import org.rtklib.java.vrs.config.VrsOptionalConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VrsOutputBridgeTest {

    @Test
    @DisplayName("输出桥：无星历时仍写出 1005 站坐标")
    void testStationMessageOnly(@TempDir Path dir) throws IOException {
        double[] pos = {30.0 * Math.PI / 180.0, 95.0 * Math.PI / 180.0, 100.0};
        double[] ecef = new double[3];
        CoordTransform.pos2ecef(pos, ecef);

        Obsd obs = new Obsd();
        obs.time = new GTime(1000L, 0.0);
        obs.sat = 1;
        StationObservation master = new StationObservation("MASTER", ecef, List.of(obs));

        Path output = dir.resolve("bridge.rtcm3");
        VrsOptionalConfig opt = new VrsOptionalConfig();
        int epochs = VrsOutputBridge.generate(master, pos, List.of(),
                output.toString(), opt, "NETVRS");

        assertEquals(1, epochs);
        assertTrue(Files.size(output) > 0, "输出文件不应为空");

        Inspector inspector = new Inspector();
        RtcmCallbackDecoder decoder = new RtcmCallbackDecoder(inspector);
        byte[] data = Files.readAllBytes(output);
        decoder.feed(data, 0, data.length);
        decoder.finish();

        assertEquals(1, inspector.stationCount);
        assertArrayEquals(ecef, inspector.sta.pos, 1e-4);
        assertEquals(0, inspector.epochCount, "无星历不应产生观测历元");
    }

    @Test
    @DisplayName("输出桥：字节数组接口与文件接口逐字节一致，且回调收到的是完整帧")
    void testByteArrayOutput(@TempDir Path dir) throws IOException {
        double[] pos = {30.0 * Math.PI / 180.0, 95.0 * Math.PI / 180.0, 100.0};
        double[] ecef = new double[3];
        CoordTransform.pos2ecef(pos, ecef);

        Obsd obs = new Obsd();
        obs.time = new GTime(1000L, 0.0);
        obs.sat = 1;
        StationObservation master = new StationObservation("MASTER", ecef, List.of(obs));

        VrsOptionalConfig opt = new VrsOptionalConfig();

        Path output = dir.resolve("bridge.rtcm3");
        int fileEpochs = VrsOutputBridge.generate(master, pos, List.of(),
                output.toString(), opt, "NETVRS");
        byte[] fromFile = Files.readAllBytes(output);

        byte[] fromBytes = VrsOutputBridge.generateToBytes(master, pos, List.of(),
                opt, "NETVRS");

        assertArrayEquals(fromFile, fromBytes, "字节数组接口与文件接口应逐字节一致");

        List<byte[]> frames = new ArrayList<>();
        VrsOutputBridge.generate(master, pos, List.of(), frames::add, opt, "NETVRS");
        assertTrue(frames.size() >= 2, "至少应有站坐标与天线描述两帧");
        assertEquals(0xD3, frames.get(0)[0] & 0xFF, "每条回调都应是完整帧，以帧头开头");
        assertEquals(1005, messageType(frames.get(0)), "首帧应为站坐标 1005");
        assertEquals(1033, messageType(frames.get(1)), "次帧应为天线描述 1033");

        int total = 0;
        for (byte[] frame : frames) {
            assertEquals(0xD3, frame[0] & 0xFF, "每条回调都应是完整帧，以帧头开头");
            total += frame.length;
        }
        assertEquals(fromFile.length, total, "逐帧拼接应还原完整输出");
        assertEquals(2, frames.size(), "本用例无星历，只有站坐标与天线描述两帧");
    }

    private static int messageType(byte[] frame) {
        return ((frame[3] & 0xFF) << 4) | ((frame[4] & 0xF0) >> 4);
    }

    private static final class Inspector implements RtcmDataHandler {
        private Sta sta;
        private int stationCount = 0;
        private int epochCount = 0;

        @Override
        public void onStation(Sta sta) {
            this.sta = sta;
            stationCount++;
        }

        @Override
        public void onEph(Eph eph) {
        }

        @Override
        public void onGeph(Geph geph) {
        }

        @Override
        public void onObservationEpoch(ObservationEpoch epoch) {
            epochCount++;
        }

        @Override
        public void onSsr(Ssr ssr) {
        }

        @Override
        public void onAuxData(AuxData aux) {
        }

        @Override
        public void onFinish() {
        }
    }
}