package org.rtklib.java.adjust.datasource;

import org.rtklib.java.data.PrcOpt;
import org.rtklib.java.data.SolData;

import java.util.List;

/**
 * [JAVA-ONLY 原创] 平差数据源抽象：统一 RINEX 文件 / RTCM 文件 / 内存流三种输入。
 *
 * <p><b>归属声明</b>：本项目原创实现，未移植任何外部开源项目代码。</p>
 */
public interface GnssDataSource {

    String getId();

    double[] getAntennaPosition();

    List<SolData> sppPositioning(PrcOpt sppOpt);

    List<SolData> rtkPositioning(GnssDataSource rover, PrcOpt rtkOpt);

    List<SolData> staticPositioning(GnssDataSource refBase, PrcOpt staticOpt);

    boolean hasData();

    double estimatedDataHours();
}