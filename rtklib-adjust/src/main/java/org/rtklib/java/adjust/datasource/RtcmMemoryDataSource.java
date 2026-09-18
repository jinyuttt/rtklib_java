package org.rtklib.java.adjust.datasource;

import org.rtklib.java.constants.Constants;
import org.rtklib.java.data.*;
import org.rtklib.java.pntpos.SppProcessor;
import org.rtklib.java.rtcm.AuxData;
import org.rtklib.java.rtcm.ObservationEpoch;
import org.rtklib.java.rtcm.RtcmCallbackDecoder;
import org.rtklib.java.rtcm.RtcmDataHandler;
import org.rtklib.java.rtkpos.RtkProcessor;

import java.util.ArrayList;
import java.util.List;

public class RtcmMemoryDataSource implements GnssDataSource {

    private final String id;
    private final List<byte[]> dataChunks = new ArrayList<>();
    private double[] cachedAntennaPos;

    public RtcmMemoryDataSource(String id) {
        this.id = id;
    }

    public void feed(byte[] data) {
        dataChunks.add(data);
        cachedAntennaPos = null;
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public double[] getAntennaPosition() {
        if (cachedAntennaPos != null) return cachedAntennaPos;
        for (byte[] data : dataChunks) {
            double[] pos = extractStationPos(data);
            if (pos != null) {
                cachedAntennaPos = pos;
                return pos;
            }
        }
        return null;
    }

    @Override
    public List<SolData> sppPositioning(PrcOpt sppOpt) {
        SppProcessor spp = new SppProcessor(sppOpt);
        for (byte[] data : dataChunks) {
            spp.feed(data);
        }
        return spp.finish().solutions;
    }

    @Override
    public List<SolData> rtkPositioning(GnssDataSource rover, PrcOpt rtkOpt) {
        if (!(rover instanceof RtcmMemoryDataSource)) {
            throw new IllegalArgumentException("RtcmMemoryDataSource需要同类型Rover");
        }
        RtcmMemoryDataSource roverSrc = (RtcmMemoryDataSource) rover;

        RtkProcessor rtk = new RtkProcessor(rtkOpt);
        List<SolData> allSol = new ArrayList<>();

        int maxChunks = Math.max(dataChunks.size(), roverSrc.dataChunks.size());
        for (int i = 0; i < maxChunks; i++) {
            byte[] baseData = (i < dataChunks.size()) ? dataChunks.get(i) : null;
            byte[] roverData = (i < roverSrc.dataChunks.size()) ? roverSrc.dataChunks.get(i) : null;
            if (baseData == null || roverData == null) continue;

            RtkProcessor.RtkResult res = rtk.process(roverData, baseData);
            rtk.resetForNextBatch();
            allSol.addAll(res.solutions);
        }
        return allSol;
    }

    @Override
    public List<SolData> staticPositioning(GnssDataSource refBase, PrcOpt staticOpt) {
        if (!(refBase instanceof RtcmMemoryDataSource)) {
            throw new IllegalArgumentException("RtcmMemoryDataSource需要同类型参考站");
        }
        RtcmMemoryDataSource refSrc = (RtcmMemoryDataSource) refBase;

        PrcOpt opt = cloneOptForStatic(staticOpt);
        RtkProcessor rtk = new RtkProcessor(opt);
        List<SolData> allSol = new ArrayList<>();

        int maxChunks = Math.max(dataChunks.size(), refSrc.dataChunks.size());
        for (int i = 0; i < maxChunks; i++) {
            byte[] roverData = (i < dataChunks.size()) ? dataChunks.get(i) : null;
            byte[] baseData = (i < refSrc.dataChunks.size()) ? refSrc.dataChunks.get(i) : null;
            if (roverData == null || baseData == null) continue;

            RtkProcessor.RtkResult res = rtk.process(roverData, baseData);
            rtk.resetForNextBatch();
            allSol.addAll(res.solutions);
        }
        return allSol;
    }

    @Override
    public boolean hasData() {
        return !dataChunks.isEmpty();
    }

    @Override
    public double estimatedDataHours() {
        long totalBytes = 0;
        for (byte[] chunk : dataChunks) totalBytes += chunk.length;
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