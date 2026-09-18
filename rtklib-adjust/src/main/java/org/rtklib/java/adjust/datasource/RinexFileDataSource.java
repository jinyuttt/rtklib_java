package org.rtklib.java.adjust.datasource;

import org.rtklib.java.data.PrcOpt;
import org.rtklib.java.data.SolData;
import org.rtklib.java.rinex.RinexRtkProcessor;
import org.rtklib.java.rinex.RinexSppProcessor;
import org.rtklib.java.rtkpos.RtkProcessor;

import java.util.ArrayList;
import java.util.List;

public class RinexFileDataSource implements GnssDataSource {

    private final String id;
    private final String obsPath;
    private final String navPath;

    public RinexFileDataSource(String id, String obsPath) {
        this(id, obsPath, null);
    }

    public RinexFileDataSource(String id, String obsPath, String navPath) {
        this.id = id;
        this.obsPath = obsPath;
        this.navPath = navPath;
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public double[] getAntennaPosition() {
        return null;
    }

    @Override
    public List<SolData> sppPositioning(PrcOpt sppOpt) {
        if (navPath != null) {
            RinexSppProcessor.SppResult result = RinexSppProcessor.processRinex(obsPath, navPath, sppOpt);
            return result.solutions;
        }
        return List.of();
    }

    @Override
    public List<SolData> rtkPositioning(GnssDataSource rover, PrcOpt rtkOpt) {
        if (!(rover instanceof RinexFileDataSource)) {
            throw new IllegalArgumentException("RinexFileDataSource需要同类型Rover");
        }
        RinexFileDataSource roverSrc = (RinexFileDataSource) rover;
        String nav = navPath != null ? navPath : roverSrc.navPath;
        if (nav == null) return List.of();

        RtkProcessor.RtkResult result = RinexRtkProcessor.processRinex(
                roverSrc.obsPath, obsPath, nav, rtkOpt);
        return result.solutions;
    }

    @Override
    public List<SolData> staticPositioning(GnssDataSource refBase, PrcOpt staticOpt) {
        if (!(refBase instanceof RinexFileDataSource)) {
            throw new IllegalArgumentException("RinexFileDataSource需要同类型参考站");
        }
        RinexFileDataSource refSrc = (RinexFileDataSource) refBase;
        String nav = refSrc.navPath != null ? refSrc.navPath : navPath;
        if (nav == null) return List.of();

        PrcOpt opt = new PrcOpt();
        opt.mode = org.rtklib.java.constants.Constants.PMODE_STATIC;
        opt.nf = staticOpt.nf;
        opt.navsys = staticOpt.navsys;
        opt.elmin = staticOpt.elmin;
        opt.modear = staticOpt.modear;
        opt.glomodear = staticOpt.glomodear;
        opt.ionoopt = staticOpt.ionoopt;
        opt.tropopt = staticOpt.tropopt;

        RtkProcessor.RtkResult result = RinexRtkProcessor.processRinex(
                obsPath, refSrc.obsPath, nav, opt);
        return result.solutions;
    }

    @Override
    public boolean hasData() {
        return java.nio.file.Files.exists(java.nio.file.Paths.get(obsPath));
    }

    @Override
    public double estimatedDataHours() {
        return 1.0;
    }
}