package org.rtklib.java.ppp;

import org.rtklib.java.config.RtkConfig;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.data.*;

public class PppBiasModel {
    private PppBiasModel() {
    }

    public static int extraDim(RtkConfig cfg) {
        if (!cfg.enableIsbIfcbIfb) return 0;

        int dim = 0;

        if (cfg.estimateIsb) {
            dim += 3;
        }

        if (cfg.estimateIfcb) {
            dim += Constants.MAXSAT;
        }

        if (cfg.estimateIfb) {
            dim += 4;
        }

        return dim;
    }

    public static int isbIndex(RtkConfig cfg, PrcOpt opt) {
        if (!cfg.estimateIsb) return -1;
        return pppBaseDim(opt);
    }

    public static int ifcbIndex(RtkConfig cfg, PrcOpt opt) {
        if (!cfg.estimateIfcb) return -1;
        int idx = pppBaseDim(opt);
        if (cfg.estimateIsb) idx += 3;
        return idx;
    }

    public static int ifbIndex(RtkConfig cfg, PrcOpt opt) {
        if (!cfg.estimateIfb) return -1;
        int idx = pppBaseDim(opt);
        if (cfg.estimateIsb) idx += 3;
        if (cfg.estimateIfcb) idx += Constants.MAXSAT;
        return idx;
    }

    private static int pppBaseDim(PrcOpt opt) {
        int np = opt.dynamics == 0 ? 3 : 9;
        int nc = 1;
        int nt = (opt.tropopt < Constants.TROPOPT_EST) ? 0 :
                 (opt.tropopt < Constants.TROPOPT_ESTG) ? 2 : 6;
        int ni = (opt.ionoopt == Constants.IONOOPT_EST) ? Constants.MAXSAT : 0;
        int nd = (opt.nf > 2 && opt.ionoopt != Constants.IONOOPT_IFLC) ? 1 : 0;
        return np + nc + nt + ni + nd;
    }

    public static void extendStateVector(Rtk rtk, int nxOrig, int nxNew, RtkConfig cfg) {
        if (!cfg.enableIsbIfcbIfb) return;

        double[] xNew = new double[nxNew];
        double[] PNew = new double[nxNew * nxNew];

        System.arraycopy(rtk.x, 0, xNew, 0, nxOrig);
        for (int i = 0; i < nxOrig; i++) {
            System.arraycopy(rtk.P, i * rtk.nx, PNew, i * nxNew, nxOrig);
        }

        int idx = nxOrig;
        double varIsb = 60.0 * 60.0;
        double varIfcb = 30.0 * 30.0;
        double varIfb = 60.0 * 60.0;

        if (cfg.estimateIsb) {
            for (int i = 0; i < 3; i++) {
                PNew[idx * nxNew + idx] = varIsb;
                idx++;
            }
        }

        if (cfg.estimateIfcb) {
            for (int i = 0; i < Constants.MAXSAT; i++) {
                PNew[idx * nxNew + idx] = varIfcb;
                idx++;
            }
        }

        if (cfg.estimateIfb) {
            for (int i = 0; i < 4; i++) {
                PNew[idx * nxNew + idx] = varIfb;
                idx++;
            }
        }

        rtk.x = xNew;
        rtk.P = PNew;
        rtk.nx = nxNew;
    }

    public static void extendObsEquation(double[] H, int nxOrig, int nxNew, int nv,
                                          int sat, int sys, int freq, PrcOpt opt,
                                          RtkConfig cfg) {
        if (!cfg.enableIsbIfcbIfb) return;

        int isbIdx = isbIndex(cfg, opt);
        if (isbIdx >= 0) {
            int isbOff = -1;
            if ((sys & Constants.SYS_GLO) != 0) isbOff = 0;
            else if ((sys & Constants.SYS_GAL) != 0) isbOff = 1;
            else if ((sys & Constants.SYS_CMP) != 0) isbOff = 2;

            if (isbOff >= 0) {
                for (int j = 0; j < nv; j++) {
                    H[(isbIdx + isbOff) * nv + j] = 1.0;
                }
            }
        }

        int ifcbIdx = ifcbIndex(cfg, opt);
        if (ifcbIdx >= 0 && freq > 0) {
            int satOff = sat - 1;
            for (int j = 0; j < nv; j++) {
                H[(ifcbIdx + satOff) * nv + j] = 1.0;
            }
        }

        int ifbIdx = ifbIndex(cfg, opt);
        if (ifbIdx >= 0) {
            int sysOff = -1;
            if ((sys & Constants.SYS_GPS) != 0) sysOff = 0;
            else if ((sys & Constants.SYS_GLO) != 0) sysOff = 1;
            else if ((sys & Constants.SYS_GAL) != 0) sysOff = 2;
            else if ((sys & Constants.SYS_CMP) != 0) sysOff = 3;

            if (sysOff >= 0) {
                for (int j = 0; j < nv; j++) {
                    H[(ifbIdx + sysOff) * nv + j] = 1.0;
                }
            }
        }
    }
}