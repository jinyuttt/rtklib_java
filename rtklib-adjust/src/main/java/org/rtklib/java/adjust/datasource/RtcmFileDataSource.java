package org.rtklib.java.adjust.datasource;

import org.rtklib.java.constants.Constants;
import org.rtklib.java.coord.CoordTransform;
import org.rtklib.java.data.*;
import org.rtklib.java.pntpos.SppProcessor;
import org.rtklib.java.rtcm.AuxData;
import org.rtklib.java.rtcm.ObservationEpoch;
import org.rtklib.java.rtcm.RtcmCallbackDecoder;
import org.rtklib.java.rtcm.RtcmDataHandler;
import org.rtklib.java.rtkpos.RtkProcessor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public class RtcmFileDataSource implements GnssDataSource {

    private final String id;
    private final List<String> filePaths;
    private double[] cachedAntennaPos;

    public RtcmFileDataSource(String id, String... filePaths) {
        this.id = id;
        this.filePaths = List.of(filePaths);
    }

    public RtcmFileDataSource(String id, List<String> filePaths) {
        this.id = id;
        this.filePaths = new ArrayList<>(filePaths);
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public double[] getAntennaPosition() {
        if (cachedAntennaPos != null) return cachedAntennaPos;
        for (String path : filePaths) {
            if (!Files.exists(Paths.get(path))) continue;
            try {
                byte[] data = Files.readAllBytes(Paths.get(path));
                double[] pos = extractStationPos(data);
                if (pos != null) {
                    cachedAntennaPos = pos;
                    return pos;
                }
            } catch (IOException e) {
                // continue
            }
        }
        return null;
    }

    @Override
    public List<SolData> sppPositioning(PrcOpt sppOpt) {
        SppProcessor spp = new SppProcessor(sppOpt);
        for (String path : filePaths) {
            if (!Files.exists(Paths.get(path))) continue;
            try {
                byte[] data = Files.readAllBytes(Paths.get(path));
                spp.feed(data);
            } catch (IOException e) {
                // continue
            }
        }
        return spp.finish().solutions;
    }

    @Override
    public List<SolData> rtkPositioning(GnssDataSource rover, PrcOpt rtkOpt) {
        if (!(rover instanceof RtcmFileDataSource)) {
            throw new IllegalArgumentException("RtcmFileDataSource需要同类型Rover");
        }
        RtcmFileDataSource roverSrc = (RtcmFileDataSource) rover;

        RtkProcessor rtk = new RtkProcessor(rtkOpt);
        List<SolData> allSol = new ArrayList<>();

        for (int i = 0; i < filePaths.size(); i++) {
            String basePath = filePaths.get(i);
            if (!Files.exists(Paths.get(basePath))) continue;

            String roverPath = (i < roverSrc.filePaths.size()) ? roverSrc.filePaths.get(i) : null;
            if (roverPath == null || !Files.exists(Paths.get(roverPath))) continue;

            try {
                RtkProcessor.RtkResult res = rtk.process(roverPath, basePath);
                rtk.resetForNextBatch();
                allSol.addAll(res.solutions);
            } catch (IOException e) {
                // continue
            }
        }
        return allSol;
    }

    @Override
    public List<SolData> staticPositioning(GnssDataSource refBase, PrcOpt staticOpt) {
        if (!(refBase instanceof RtcmFileDataSource)) {
            throw new IllegalArgumentException("RtcmFileDataSource需要同类型参考站");
        }
        RtcmFileDataSource refSrc = (RtcmFileDataSource) refBase;

        PrcOpt opt = cloneOptForStatic(staticOpt);
        RtkProcessor rtk = new RtkProcessor(opt);
        List<SolData> allSol = new ArrayList<>();

        for (int i = 0; i < filePaths.size(); i++) {
            String roverPath = filePaths.get(i);
            if (!Files.exists(Paths.get(roverPath))) continue;

            String basePath = (i < refSrc.filePaths.size()) ? refSrc.filePaths.get(i) : null;
            if (basePath == null || !Files.exists(Paths.get(basePath))) continue;

            try {
                RtkProcessor.RtkResult res = rtk.process(roverPath, basePath);
                rtk.resetForNextBatch();
                allSol.addAll(res.solutions);
            } catch (IOException e) {
                // continue
            }
        }
        return allSol;
    }

    @Override
    public boolean hasData() {
        return filePaths.stream().anyMatch(p -> Files.exists(Paths.get(p)));
    }

    @Override
    public double estimatedDataHours() {
        long totalBytes = 0;
        for (String path : filePaths) {
            if (Files.exists(Paths.get(path))) {
                try { totalBytes += Files.size(Paths.get(path)); } catch (IOException e) { /* skip */ }
            }
        }
        return totalBytes / (3600.0 * 5000.0);
    }

    private static double[] extractStationPos(byte[] data) {
        final double[][] result = {null};
        RtcmDataHandler handler = new RtcmDataHandler() {
            @Override public void onObservationEpoch(ObservationEpoch epoch) {}
            @Override public void onEph(Eph eph) {}
            @Override public void onGeph(Geph geph) {}
            @Override public void onStation(Sta sta) {
                if (result[0] == null && sta.pos != null
                        && (sta.pos[0] != 0.0 || sta.pos[1] != 0.0 || sta.pos[2] != 0.0)) {
                    result[0] = sta.pos.clone();
                }
            }
            @Override public void onSsr(Ssr ssr) {}
            @Override public void onAuxData(AuxData aux) {}
            @Override public void onFinish() {}
        };
        RtcmCallbackDecoder decoder = new RtcmCallbackDecoder(handler);
        decoder.feed(data, 0, data.length);
        decoder.finish();
        return result[0];
    }

    private static PrcOpt cloneOptForStatic(PrcOpt opt) {
        PrcOpt clone = new PrcOpt();
        clone.mode = Constants.PMODE_STATIC;
        clone.nf = opt.nf;
        clone.navsys = opt.navsys;
        clone.elmin = opt.elmin;
        clone.soltype = opt.soltype;
        clone.modear = opt.modear;
        clone.glomodear = opt.glomodear;
        clone.ionoopt = opt.ionoopt;
        clone.tropopt = opt.tropopt;
        clone.posMask = opt.posMask;
        clone.refpos = opt.refpos;
        clone.intpref = opt.intpref;
        clone.maxtdiff = opt.maxtdiff;
        clone.outsingle = opt.outsingle;
        clone.rb = opt.rb != null ? opt.rb.clone() : null;
        return clone;
    }
}