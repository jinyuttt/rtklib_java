package org.rtklib.java;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.rtklib.java.config.RtkConfig;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.data.PrcOpt;
import org.rtklib.java.data.Rtk;
import org.rtklib.java.rtkpos.RtkProcessor;
import org.rtklib.java.rtkpos.RtkProcessor.RtkResult;
import org.rtklib.java.trace.TraceControl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;

@DisplayName("RTK AR 调试测试")
public class RtkArDebugTest {

    private static final Logger log = LoggerFactory.getLogger(RtkArDebugTest.class);

    private static final String BASE_PATH =
            "D:\\tdengine-jetlinks\\jetlinks-data\\device_rtcmbin_storage\\<BASE_DEVICE_ID>\\2026-07-20\\12.rtcm3";
    private static final String ROVER_PATH =
            "D:\\tdengine-jetlinks\\jetlinks-data\\device_rtcmbin_storage\\<ROVER_DEVICE_ID>\\2026-07-20\\12.rtcm3";

    @Test
    @DisplayName("调试 AR 为什么没有 Fix 解")
    void testArDebug() throws IOException {
        byte[] roverData, baseData;
        try (FileInputStream fis = new FileInputStream(ROVER_PATH)) {
            roverData = fis.readAllBytes();
        }
        try (FileInputStream fis = new FileInputStream(BASE_PATH)) {
            baseData = fis.readAllBytes();
        }

        PrcOpt opt = new PrcOpt();
        opt.mode = Constants.PMODE_KINEMA;
        opt.nf = 2;
        opt.navsys = Constants.SYS_CMP;
        opt.elmin = 10.0 * Constants.D2R;
        opt.ionoopt = Constants.IONOOPT_BRDC;
        opt.tropopt = Constants.TROPOPT_SAAS;
        opt.modear = Constants.ARMODE_OFF; // 先关闭AR
        opt.thresar[0] = 1.5; // 降低阈值更容易Fix
        opt.minfix = 1;
        opt.dynamics = 0;
        opt.refpos = Constants.POSOPT_RTCM;

        // 先测试基础配置（无优化）
        RtkConfig config = new RtkConfig();
        config.enableParRefReselect = false;
        config.enableAdaptiveQ = false;
        config.enableIggiii = false;
        config.enableSnrMedian = false;
        config.enableIonoTropGradient = false;
        config.enableAmbAnchor = false;

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        RtkProcessor processor = new RtkProcessor(opt, null, baos);
        
        RtkResult result = processor.process(roverData, baseData);
        
        Rtk rtk = processor.getRtk();
        
        log.info("========== AR 调试信息 ==========");
        log.info("总历元: {}, 成功: {}", result.totalEpochs, result.successCount);
        log.info("nfix (连续固定计数): {}", rtk.nfix);
        log.info("holdambFlag: {}", rtk.holdambFlag);
        log.info("sol.stat: {} (1=Single, 2=Float, 3=Fix)", rtk.sol.stat);
        log.info("sol.ratio: {}", rtk.sol.ratio);
        log.info("sol.ns: {}", rtk.sol.ns);
        
        // 检查模糊度状态
        int nf = opt.nf;
        int fixCount = 0;
        for (int i = 0; i < Constants.MAXSAT; i++) {
            for (int f = 0; f < nf; f++) {
                if (rtk.ssat[i].fix[f] > 0) fixCount++;
            }
        }
        log.info("fix 标志 > 0 的模糊度数量: {}", fixCount);
        
        // 检查锚定状态
        int anchoredCount = 0;
        for (int i = 0; i < Constants.MAXSAT * nf; i++) {
            if (rtk.ambAnchored[i]) anchoredCount++;
        }
        log.info("锚定模糊度数量: {}", anchoredCount);

        // 打印部分定位结果
        String output = baos.toString();
        String[] lines = output.split("\n");
        int printCount = 0;
        for (String line : lines) {
            if (line.startsWith("%") || line.trim().isEmpty()) continue;
            if (printCount < 5) {
                log.info("POS: {}", line.trim());
                printCount++;
            }
        }
    }
}