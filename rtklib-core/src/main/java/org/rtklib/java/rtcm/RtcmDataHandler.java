package org.rtklib.java.rtcm;

import org.rtklib.java.data.Eph;
import org.rtklib.java.data.Geph;
import org.rtklib.java.data.Ssr;
import org.rtklib.java.data.Sta;

/**
 * RTCM3 数据解码回调接口。
 * 当 {@link RtcmCallbackDecoder} 解析到对应类型的 RTCM3 消息时，调用相应回调方法。
 */
public interface RtcmDataHandler {

    /**
     * 收到基站天线位置信息（RTCM 1005/1006）。
     *
     * @param sta 基站信息，包含 ECEF 位置、天线描述等
     */
    void onStation(Sta sta);

    /**
     * 收到 SSR 轨道/钟差修正信息（RTCM 1057-1068, 1240-1270）。
     *
     * @param ssr SSR 修正数据
     */
    void onSsr(Ssr ssr);

    /**
     * 收到星历信息（RTCM 1019 GPS / 1042 BDS / 1044 QZS / 1045 GAL / 1046 GAL）。
     * 同一颗卫星的星历可能被周期性重播，每次都会触发回调。
     *
     * @param eph 星历数据，包含轨道根数、钟差等
     */
    void onEph(Eph eph);

    /**
     * 收到 GLONASS 星历信息（RTCM 1020）。
     *
     * @param geph GLONASS 星历数据，包含卫星位置、频率号等
     */
    void onGeph(Geph geph);

    /**
     * 收到观测值历元（RTCM 1001-1004, 1071-1077, 1081-1087, 1091-1097,
     * 1101-1107, 1111-1117, 1121-1127, 1131-1137）。
     * 同一时刻的多个 MSM 消息会被合并为一个历元。
     *
     * @param epoch 观测历元，包含时间标签和各卫星的伪距/载波/多普勒等观测值
     */
    void onObservationEpoch(ObservationEpoch epoch);

    /**
     * 收到辅助数据（RTCM 1007 天线描述 / 1008 天线序列号 / 1033 接收机与天线描述）。
     *
     * @param aux 辅助数据，包含天线描述、序列号、接收机类型等
     */
    void onAuxData(AuxData aux);

    /**
     * 解码结束，所有数据已处理完毕。
     * 在 {@link RtcmCallbackDecoder#finish()} 时调用。
     */
    void onFinish();


    default void onRtcmMessageType(int type, int length) {
        // 默认不做任何事，保持兼容
    }
}