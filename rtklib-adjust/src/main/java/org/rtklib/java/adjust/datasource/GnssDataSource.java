package org.rtklib.java.adjust.datasource;

import org.rtklib.java.data.PrcOpt;
import org.rtklib.java.data.SolData;

import java.util.List;

public interface GnssDataSource {

    String getId();

    double[] getAntennaPosition();

    List<SolData> sppPositioning(PrcOpt sppOpt);

    List<SolData> rtkPositioning(GnssDataSource rover, PrcOpt rtkOpt);

    List<SolData> staticPositioning(GnssDataSource refBase, PrcOpt staticOpt);

    boolean hasData();

    double estimatedDataHours();
}