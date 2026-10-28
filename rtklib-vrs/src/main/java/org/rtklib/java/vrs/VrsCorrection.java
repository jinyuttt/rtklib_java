package org.rtklib.java.vrs;

import org.rtklib.java.coord.CoordTransform;
import org.rtklib.java.ephemeris.EphModel;
import org.rtklib.java.ionosphere.IonosphereModel;
import org.rtklib.java.common.RtklibCommon;
import org.rtklib.java.common.SatUtils;
import org.rtklib.java.troposphere.TroposphereModel;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.data.Nav;
import org.rtklib.java.data.Obs;
import org.rtklib.java.data.Obsd;
import org.rtklib.java.data.Sta;
import org.rtklib.java.rtcm.ObservationEpoch;
import org.rtklib.java.vrs.config.VrsOptionalConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class VrsCorrection {
    private static final Logger log = LoggerFactory.getLogger(VrsCorrection.class);

    private VrsCorrection() {
    }

    public static Obs correct(ObservationEpoch epoch, Nav nav,
                              Sta baseSta, double[] vrsPos,
                              VrsOptionalConfig opt) {
        return correct(epoch, nav, baseSta, vrsPos, opt, null);
    }

    /**
     * @param provider 外部改正量提供者；为 null 时全部卫星走模型计算，
     *                 某卫星返回 null 时该卫星同样回退模型
     */
    public static Obs correct(ObservationEpoch epoch, Nav nav,
                              Sta baseSta, double[] vrsPos,
                              VrsOptionalConfig opt,
                              VrsCorrectionProvider provider) {
        double elMaskRad = opt.elMaskDeg * Constants.D2R;

        double[] basePos = new double[3];
        CoordTransform.ecef2pos(baseSta.pos, basePos);

        double[] vrsEcef = new double[3];
        CoordTransform.pos2ecef(vrsPos, vrsEcef);

        Obs vrsObs = new Obs();
        vrsObs.n = 0;

        for (Obsd baseObsd : epoch.getObservations()) {
            int[] prnArr = new int[1];
            int sys = SatUtils.satsys(baseObsd.sat, prnArr);
            if ((sys & opt.navsys) == 0) continue;

            double[] rs = new double[6];
            double[] dts = new double[2];
            double[] vare = new double[1];
            EphModel.satpos(baseObsd.time, nav, baseObsd.sat, rs, dts, vare);
            if (rs[0] == 0.0 && rs[1] == 0.0 && rs[2] == 0.0) {
                if (log.isDebugEnabled()) {
                    log.debug("sat={} 星历未找到，跳过", baseObsd.sat);
                }
                continue;
            }

            double[] eBase = new double[3];
            double[] eVrs = new double[3];
            double rBase = RtklibCommon.geodist(rs, baseSta.pos, eBase);
            double rVrs = RtklibCommon.geodist(rs, vrsEcef, eVrs);
            if (rBase <= 0.0 || rVrs <= 0.0) continue;

            double dGeom = rVrs - rBase;

            double[] azelBase = new double[2];
            double[] azelVrs = new double[2];
            RtklibCommon.satazel(basePos, eBase, azelBase);
            RtklibCommon.satazel(vrsPos, eVrs, azelVrs);
            if (azelVrs[1] < elMaskRad) continue;

            double dIon;
            double dTrop;
            double dOrbit = 0.0;
            double[] external = provider == null ? null
                    : provider.correction(baseObsd.time, baseObsd.sat);
            if (external != null && external.length >= 3) {
                dIon = external[0];
                dTrop = external[1];
                dOrbit = external[2];
            } else {
                double[] tropOut = new double[2];
                TroposphereModel.tropcorr(baseObsd.time, nav, basePos, azelBase, opt.tropModel, tropOut);
                double tropBase = tropOut[0];
                TroposphereModel.tropcorr(baseObsd.time, nav, vrsPos, azelVrs, opt.tropModel, tropOut);
                double tropVrs = tropOut[0];
                dTrop = tropVrs - tropBase;

                double[] ionOut = new double[2];
                IonosphereModel.ionocorr(baseObsd.time, nav, baseObsd.sat, basePos, azelBase, opt.ionoModel, ionOut);
                double ionBase = ionOut[0];
                IonosphereModel.ionocorr(baseObsd.time, nav, baseObsd.sat, vrsPos, azelVrs, opt.ionoModel, ionOut);
                double ionVrs = ionOut[0];
                dIon = ionVrs - ionBase;
            }

            double dRange = dGeom + dTrop + dOrbit;

            Obsd vrsObsd = new Obsd(baseObsd);
            for (int f = 0; f < Constants.NFREQ; f++) {
                double freq = SatUtils.sat2freq(baseObsd.sat, baseObsd.code[f], nav);
                double lam = freq > 0.0 ? Constants.CLIGHT / freq : 0.0;

                if (baseObsd.P[f] != 0.0) {
                    vrsObsd.P[f] = baseObsd.P[f] + dRange + dIon;
                }
                if (baseObsd.L[f] != 0.0 && lam > 0.0) {
                    vrsObsd.L[f] = baseObsd.L[f] + (dRange - dIon) / lam;
                }
            }

            if (vrsObs.n < vrsObs.nmax) {
                vrsObs.data[vrsObs.n] = vrsObsd;
                vrsObs.n++;
            }
        }
        return vrsObs;
    }
}