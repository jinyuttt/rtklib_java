package org.rtklib.java.joint;

import org.rtklib.java.ambiguity.Lambda;
import org.rtklib.java.common.RtklibCommon;
import org.rtklib.java.common.SatUtils;
import org.rtklib.java.config.RtkConfig;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.data.*;
import org.rtklib.java.ephemeris.EphModel;
import org.rtklib.java.kalman.KalmanFilter;
import org.rtklib.java.pntpos.PntPos;
import org.rtklib.java.rtkpos.RtkCore;
import org.rtklib.java.time.TimeSystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;

public final class JointCore {
    private static final Logger log = LoggerFactory.getLogger(JointCore.class);
    private JointCore() {
    }

    public static int rtkpos(JointRtk jrtk, Obsd[] roverObs, int roverN,
                             BaseStation[] bases, int nBaseline, Nav nav) {
        if (jrtk.opt == null || roverObs == null || roverN <= 0) {
            log.warn("rtkpos early return: opt={}, roverObs={}, roverN={}", jrtk.opt, roverObs, roverN);
            return 0;
        }
        if (bases == null || nBaseline <= 0) {
            log.warn("rtkpos early return: bases={}, nBaseline={}", bases, nBaseline);
            return 0;
        }

        PrcOpt opt = jrtk.opt;
        int nf = (opt.ionoopt == Constants.IONOOPT_IFLC) ? 1 : opt.nf;

        for (int i = 0; i < Constants.MAXSAT; i++) {
            jrtk.ssat[i].sys = SatUtils.satsys(i + 1, null);
            for (int j = 0; j < Constants.NFREQ; j++) {
                jrtk.ssat[i].vsat[j] = 0;
                jrtk.ssat[i].snrRover[j] = 0;
                jrtk.ssat[i].snrBase[j] = 0;
            }
        }

        if (jrtk.P == null || jrtk.P.length == 0 || jrtk.P[0] == 0.0 || jrtk.P[0] > 1E14) {
            PrcOpt sppOpt = new PrcOpt(opt);
            sppOpt.mode = Constants.PMODE_SINGLE;
            sppOpt.ionoopt = Constants.IONOOPT_BRDC;
            sppOpt.tropopt = Constants.TROPOPT_SAAS;
            int sppResult = PntPos.pntpos(roverObs, roverN, nav, sppOpt, jrtk.sol, null, jrtk.ssat);
            if (sppResult == 0) {
                log.warn("Joint SPP failed for rover (roverN={}), cannot initialize position", roverN);
                if (opt.dynamics == 0) {
                    for (int b = 0; b < nBaseline; b++) {
                        if (bases[b] != null) {
                            double[] dr = new double[3];
                            for (int j = 0; j < 3; j++) dr[j] = bases[b].rb[j] - jrtk.rb[j];
                            double bl = RtklibCommon.norm(dr, 3);
                            log.info("  baseline[{}] length={} km", b, bl / 1000.0);
                        }
                    }
                    return 0;
                }
            } else {
                log.info("Joint SPP rover pos: ({}, {}, {})", jrtk.sol.rr[0], jrtk.sol.rr[1], jrtk.sol.rr[2]);
            }
        } else {
            jrtk.sol.time = roverObs[0].time;
        }

        BaselineObs[] blObs = new BaselineObs[nBaseline];
        for (int b = 0; b < nBaseline; b++) {
            blObs[b] = new BaselineObs();
            blObs[b].clear();

            BaseStation bs = bases[b];
            if (bs == null || bs.obsBuf == null || bs.obsCount <= 0) continue;

            int nu = roverN;
            int nr = bs.obsCount;
            int n = nu + nr;
            if (n > Constants.MAXOBS) {
                if (nu > Constants.MAXOBS) {
                    nu = Constants.MAXOBS;
                    nr = 0;
                } else {
                    nr = Constants.MAXOBS - nu;
                }
            }
            blObs[b].nu = nu;
            blObs[b].nr = nr;

            Obsd[] merged = new Obsd[nu + nr];
            System.arraycopy(roverObs, 0, merged, 0, nu);
            System.arraycopy(bs.obsBuf, 0, merged, nu, nr);

            Arrays.fill(blObs[b].azel, 0, (nu + nr) * 2, 0.0);
            Arrays.fill(blObs[b].freq, 0, (nu + nr) * nf, 0.0);

            EphModel.satposs(roverObs[0].time, merged, nu + nr, nav,
                    blObs[b].rs, blObs[b].dts, blObs[b].vare, blObs[b].svh, opt.sateph);

            RtkState state = jrtk.toState();

            if (!RtkCore.zdres(state, 1, merged, nu, nr,
                    blObs[b].rs, blObs[b].dts, blObs[b].vare, blObs[b].svh,
                    nav, bs.rb, opt,
                    blObs[b].y, blObs[b].e, blObs[b].azel, blObs[b].freq, jrtk.epoch)) {
                log.warn("baseline[{}] zdres failed, rb=({},{},{})", b, bs.rb[0], bs.rb[1], bs.rb[2]);
                continue;
            }

            blObs[b].ns = RtkCore.selsat(merged, blObs[b].azel, nu, nr, opt,
                    blObs[b].sat, blObs[b].iu, blObs[b].ir);
            log.info("baseline[{}] ns={}, nu={}, nr={}, rb=({},{},{})", b, blObs[b].ns, nu, nr, bs.rb[0], bs.rb[1], bs.rb[2]);
        }

        BaselineGroup group = new BaselineGroup(nBaseline);
        group.time = roverObs[0].time;
        group.roverObs = roverObs;
        group.roverObsCount = roverN;
        group.nBaseline = nBaseline;
        for (int i = 0; i < nBaseline; i++) group.bases[i] = bases[i];

        EpochAligner aligner = new EpochAligner(jrtk.jointConfig.maxTimeDiff);
        aligner.fillCommonSats(group, blObs);
        aligner.selectGlobalRefSatBySys(group, jrtk.ssat, blObs);

        int totalNs = 0;
        for (int b = 0; b < nBaseline; b++) {
            if (blObs[b] != null) totalNs += blObs[b].ns;
        }
        if (totalNs <= 0) {
            log.warn("totalNs=0, no common satellites across baselines");
            return 0;
        }

        int nrPerBaseline = RtkCore.NR(jrtk.toState());
        int nbPerBaseline = RtkCore.NB(jrtk.toState());
        int nxNew = nrPerBaseline + nbPerBaseline * nBaseline;
        jrtk.na = nrPerBaseline;

        if (jrtk.nx != nxNew) {
            jrtk.nx = nxNew;
            jrtk.x = new double[nxNew];
            jrtk.P = new double[nxNew * nxNew];

            double baseline0 = jrtk.sol.rr[0] - jrtk.rb[0];
            double baseline1 = jrtk.sol.rr[1] - jrtk.rb[1];
            double baseline2 = jrtk.sol.rr[2] - jrtk.rb[2];
            JointDdRes.initx(jrtk.x, jrtk.P, nxNew, baseline0, Constants.VAR_POS, 0);
            JointDdRes.initx(jrtk.x, jrtk.P, nxNew, baseline1, Constants.VAR_POS, 1);
            JointDdRes.initx(jrtk.x, jrtk.P, nxNew, baseline2, Constants.VAR_POS, 2);
            if (opt.dynamics != 0) {
                for (int i = 3; i < 6; i++) JointDdRes.initx(jrtk.x, jrtk.P, nxNew, 0.0, Constants.VAR_VEL, i);
                for (int i = 6; i < 9; i++) JointDdRes.initx(jrtk.x, jrtk.P, nxNew, 1E-6, Constants.VAR_ACC, i);
            }

            jrtk.baselineOffset = new int[nBaseline];
            for (int b = 0; b < nBaseline; b++) {
                jrtk.baselineOffset[b] = nrPerBaseline + b * nbPerBaseline;
            }
        }
        int nx = jrtk.nx;

        for (int b = 0; b < nBaseline; b++) {
            BaselineObs bo = blObs[b];
            if (bo == null) continue;
            for (int i = 0; i < bo.ns; i++) {
                for (int f = 0; f < nf; f++) {
                    jrtk.ssat[bo.sat[i] - 1].snrRover[f] = roverObs[bo.iu[i]].SNR[f];
                    jrtk.ssat[bo.sat[i] - 1].snrBase[f] = bases[b].obsBuf[bo.ir[i] - bo.nu].SNR[f];
                }
            }
        }

        udstate(jrtk, roverObs, roverN, bases, nBaseline, nav, blObs);

        double[] xp = new double[nx];
        double[] Pp = new double[nx * nx];
        System.arraycopy(jrtk.x, 0, xp, 0, nx);
        System.arraycopy(jrtk.P, 0, Pp, 0, nx * nx);

        double dt = 0.0;
        if (nBaseline > 0 && bases[0] != null && bases[0].obsTime != null) {
            dt = TimeSystem.timediff(roverObs[0].time, bases[0].obsTime);
        }

        int ny = totalNs * nf * 2 + 2;
        double[] v = new double[ny];
        double[] H = new double[nx * ny];
        double[] R = new double[ny * ny];
        int[] vflg = new int[ny];

        int stat = Constants.SOLQ_FLOAT;
        int nv = 0;

        for (int iter = 0; iter < opt.niter; iter++) {
            for (int b = 0; b < nBaseline; b++) {
                BaselineObs bo = blObs[b];
                if (bo == null || bo.ns <= 0) continue;
                BaseStation bs = bases[b];
                int nu = bo.nu;
                int nr = bo.nr;
                Obsd[] merged = new Obsd[nu + nr];
                System.arraycopy(roverObs, 0, merged, 0, nu);
                System.arraycopy(bs.obsBuf, 0, merged, nu, nr);

                double[] rr_rover = new double[3];
                for (int j = 0; j < 3; j++) rr_rover[j] = jrtk.rb[j] + xp[j];

                RtkState state = jrtk.toState();
                RtkCore.zdres(state, 0, merged, nu, nr,
                        bo.rs, bo.dts, bo.vare, bo.svh,
                        nav, rr_rover, opt,
                        bo.y, bo.e, bo.azel, bo.freq, jrtk.epoch);
                RtkCore.zdres(state, 1, merged, nu, nr,
                        bo.rs, bo.dts, bo.vare, bo.svh,
                        nav, bs.rb, opt,
                        bo.y, bo.e, bo.azel, bo.freq, jrtk.epoch);
            }

            nv = JointDdRes.ddres(jrtk, group, blObs, nav, dt, xp, Pp, v, H, R, vflg);
            if (jrtk.epoch == 0 && iter == 0) {
                int ib0 = jrtk.IB(0, blObs[0].sat[0], 0);
                int ib1 = jrtk.IB(0, blObs[0].sat[1], 0);
                if (ib0 >= 0 && ib1 >= 0) {
                    log.debug("epoch=0 xp[ib0]={} xp[ib1]={} ib0={} ib1={}", xp[ib0], xp[ib1], ib0, ib1);
                }
                log.debug("epoch=0 rover pos offset: dx={} dy={} dz={} rb=({},{},{})",
                        String.format("%.4f", xp[0]), String.format("%.4f", xp[1]), String.format("%.4f", xp[2]),
                        String.format("%.4f", jrtk.rb[0]), String.format("%.4f", jrtk.rb[1]), String.format("%.4f", jrtk.rb[2]));
                double[] rr_rover_now = new double[3];
                for (int j = 0; j < 3; j++) rr_rover_now[j] = jrtk.rb[j] + xp[j];
                log.debug("epoch=0 rover ECEF: ({},{},{})", 
                        String.format("%.4f", rr_rover_now[0]), String.format("%.4f", rr_rover_now[1]), String.format("%.4f", rr_rover_now[2]));
            }
            if (nv < 4) {
                log.warn("iter={} ddres nv={} < 4, insufficient observations", iter, nv);
                stat = Constants.SOLQ_NONE;
                break;
            }

            double ambVarBefore = 0;
            int ib0Diag = jrtk.IB(0, blObs[0].sat[0], 0);
            if (ib0Diag >= 0 && ib0Diag < nx) ambVarBefore = Pp[ib0Diag * nx + ib0Diag];
            if ((jrtk.epoch == 0 || jrtk.epoch == 1 || jrtk.epoch == 5) && iter == 0) {
                double hph0 = 0;
                for (int i = 0; i < nx; i++) {
                    double hi = H[0*nx+i];
                    if (hi == 0) continue;
                    for (int j = 0; j < nx; j++) {
                        double hj = H[0*nx+j];
                        if (hj == 0) continue;
                        hph0 += hi * Pp[i*nx+j] * hj;
                    }
                }
                log.debug("epoch={} BEFORE Kalman: HPHt[0]={} P[0]={} P[ib0]={} posVar={}", jrtk.epoch,
                        String.format("%.4f", hph0), String.format("%.4f", Pp[0]),
                        (ib0Diag >= 0 ? String.format("%.4f", Pp[ib0Diag*nx+ib0Diag]) : "N/A"),
                        String.format("%.6f", (Pp[0]+Pp[1*nx+1]+Pp[2*nx+2])/3.0));
            }
            int info = KalmanFilter.update(xp, Pp, H, v, R, nx, nv);
            if (jrtk.epoch <= 5 && iter == 0 && ib0Diag >= 0) {
                double ambVarAfter = Pp[ib0Diag * nx + ib0Diag];
                double maxR = 0, minR = Double.MAX_VALUE;
                for (int iv = 0; iv < nv; iv++) { maxR = Math.max(maxR, R[iv*nv+iv]); minR = Math.min(minR, R[iv*nv+iv]); }
                double ambXBefore = 0, ambXAfter = 0;
                if (ib0Diag >= 0) { ambXBefore = jrtk.x[ib0Diag]; ambXAfter = xp[ib0Diag]; }
                int zeroAmbCount = 0;
                for (int b = 0; b < blObs.length; b++) {
                    if (blObs[b] == null) continue;
                    for (int i = 0; i < blObs[b].ns; i++) {
                        for (int f = 0; f < nf; f++) {
                            int idx = jrtk.IB(b, blObs[b].sat[i], f);
                            if (idx >= 0 && idx < nx && xp[idx] == 0.0 && Pp[idx*nx+idx] > 0) zeroAmbCount++;
                        }
                    }
                }
                log.debug("epoch={} Kalman: ambVar {} -> {} (delta={}) ambX {} -> {} zeroAmbCount={} R_diag: min={} max={}", jrtk.epoch,
                        String.format("%.4f", ambVarBefore), String.format("%.4f", ambVarAfter),
                        String.format("%.4f", ambVarAfter - ambVarBefore),
                        String.format("%.4f", ambXBefore), String.format("%.4f", ambXAfter),
                        zeroAmbCount,
                        String.format("%.6f", minR), String.format("%.6f", maxR));
            }
            if (info != 0) {
                log.warn("iter={} Kalman update failed info={} nx={} nv={}", iter, info, nx, nv);
                stat = Constants.SOLQ_NONE;
                break;
            }
            if (jrtk.epoch == 0 && iter == 0) {
                double minAmbVar = Double.MAX_VALUE, maxAmbVar = 0, sumAmbVar = 0;
                int ambCnt = 0, unchangedCnt = 0;
                StringBuilder unchangedSats = new StringBuilder();
                for (int b = 0; b < blObs.length; b++) {
                    if (blObs[b] == null) continue;
                    int[] fUpdated = new int[nf], fUnchanged = new int[nf];
                    for (int i = 0; i < blObs[b].ns; i++) {
                        for (int f = 0; f < nf; f++) {
                            int idx = jrtk.IB(b, blObs[b].sat[i], f);
                            if (idx >= 0 && idx < nx && Pp[idx*nx+idx] > 0) {
                                double var = Pp[idx*nx+idx];
                                minAmbVar = Math.min(minAmbVar, var);
                                maxAmbVar = Math.max(maxAmbVar, var);
                                sumAmbVar += var;
                                ambCnt++;
                                if (var > 899.0) {
                                    unchangedCnt++;
                                    fUnchanged[f]++;
                                    if (unchangedCnt <= 3) unchangedSats.append(String.format("b%d_s%d_f%d ", b, blObs[b].sat[i], f));
                                } else {
                                    fUpdated[f]++;
                                }
                            }
                        }
                    }
                    log.debug("epoch=0 b[{}] f_updated={} f_unchanged={}", b, java.util.Arrays.toString(fUpdated), java.util.Arrays.toString(fUnchanged));
                }
                log.debug("epoch=0 AFTER Kalman: ambVar min={} max={} avg={} cnt={} unchanged={} sats={}",
                        String.format("%.4f", minAmbVar), String.format("%.4f", maxAmbVar),
                        ambCnt > 0 ? String.format("%.4f", sumAmbVar/ambCnt) : "N/A", ambCnt,
                        unchangedCnt, unchangedSats);
                double[] fAvgVar = new double[nf];
                int[] fCnt = new int[nf];
                for (int b = 0; b < blObs.length; b++) {
                    if (blObs[b] == null) continue;
                    for (int i = 0; i < blObs[b].ns; i++) {
                        for (int f = 0; f < nf; f++) {
                            int idx = jrtk.IB(b, blObs[b].sat[i], f);
                            if (idx >= 0 && idx < nx && Pp[idx*nx+idx] > 0 && Pp[idx*nx+idx] < 899.0) {
                                fAvgVar[f] += Pp[idx*nx+idx];
                                fCnt[f]++;
                            }
                        }
                    }
                }
                StringBuilder fSb = new StringBuilder();
                for (int f = 0; f < nf; f++) fSb.append(String.format("f%d:avg=%.1f/cnt=%d ", f, fCnt[f] > 0 ? fAvgVar[f]/fCnt[f] : 0, fCnt[f]));
                log.debug("epoch=0 AFTER Kalman per-freq: {}", fSb);
                double posVar = (Pp[0] + Pp[1*nx+1] + Pp[2*nx+2]) / 3.0;
                log.debug("epoch=0 AFTER Kalman: posVar={}", String.format("%.6f", posVar));
            }
            if ((jrtk.epoch == 0 || jrtk.epoch == 5) && iter == 0) {
                int nrPerBl = RtkCore.NR(jrtk.toState());
                int carrierNv = 0, codeNv = 0;
                for (int iv = 0; iv < nv; iv++) {
                    boolean hasAmb = false;
                    for (int k = nrPerBl; k < nx; k++) { if (H[iv*nx+k] != 0) { hasAmb = true; break; } }
                    if (hasAmb) carrierNv++; else codeNv++;
                }
                int activeStates = 0;
                for (int i = 0; i < nx; i++) { if (xp[i] != 0.0 && Pp[i*nx+i] > 0.0) activeStates++; }
                double sumAmbVar = 0;
                int ambCount = 0;
                for (int b = 0; b < blObs.length; b++) {
                    if (blObs[b] == null) continue;
                    for (int i = 0; i < blObs[b].ns; i++) {
                        for (int f = 0; f < nf; f++) {
                            int idx = jrtk.IB(b, blObs[b].sat[i], f);
                            if (idx >= 0 && idx < nx && Pp[idx*nx+idx] > 0) {
                                sumAmbVar += Pp[idx*nx+idx];
                                ambCount++;
                            }
                        }
                    }
                }
                double sumPosVar = (Pp[0] + Pp[1*nx+1] + Pp[2*nx+2]);
                log.debug("epoch={} before Kalman: nv={} (carrier={} code={}) active={} ambCount={} avgAmbVar={} posVar={}",
                        jrtk.epoch, nv, carrierNv, codeNv, activeStates, ambCount,
                        ambCount > 0 ? String.format("%.4f", sumAmbVar/ambCount) : "N/A",
                        String.format("%.6f", sumPosVar));
            }
            if (jrtk.epoch == 10 && iter == 0) {
                int ib0_b0 = jrtk.IB(0, blObs[0].sat[0], 0);
                int ib0_b1 = jrtk.IB(1, blObs[1].sat[0], 0);
                if (ib0_b0 >= 0 && ib0_b1 >= 0) {
                    double crossCov = Pp[ib0_b0 * nx + ib0_b1];
                    double crossCovPos = Pp[0 * nx + ib0_b1];
                    log.debug("epoch=10: crossCov(amb_b0, amb_b1)={} crossCov(pos, amb_b1)={}",
                            String.format("%.6f", crossCov), String.format("%.6f", crossCovPos));
                }
            }
            if (jrtk.epoch == 50 && iter == 0) {
                double posVar = (Pp[0] + Pp[1*nx+1] + Pp[2*nx+2]) / 3.0;
                int ib0 = jrtk.IB(0, blObs[0].sat[0], 0);
                log.debug("epoch=50: posVar={} ambVar={} xp[0]={} xp[1]={} xp[2]={}",
                        String.format("%.4f", posVar), (ib0 >= 0 ? String.format("%.4f", Pp[ib0*nx+ib0]) : "N/A"),
                        String.format("%.4f", xp[0]), String.format("%.4f", xp[1]), String.format("%.4f", xp[2]));
                double maxHpos = 0, maxHamb = 0, maxV = 0;
                for (int iv = 0; iv < nv; iv++) {
                    for (int k = 0; k < 3; k++) maxHpos = Math.max(maxHpos, Math.abs(H[iv*nx+k]));
                    if (ib0 >= 0) maxHamb = Math.max(maxHamb, Math.abs(H[iv*nx+ib0]));
                    maxV = Math.max(maxV, Math.abs(v[iv]));
                }
                log.debug("epoch=50 H stats: maxHpos={} maxHamb={} maxV={}", 
                        String.format("%.6f", maxHpos), String.format("%.6f", maxHamb), String.format("%.6f", maxV));
                int activeCount = 0;
                int zeroX = 0, zeroP = 0;
                for (int i = 0; i < nx; i++) {
                    if (xp[i] != 0.0 && Pp[i*nx+i] > 0.0) activeCount++;
                    if (xp[i] == 0.0 && Pp[i*nx+i] > 0.0) zeroX++;
                    if (Pp[i*nx+i] == 0.0 && i >= 3) zeroP++;
                }
                log.debug("epoch=50: nx={} active={} zeroX_nonzeroP={} zeroP={}", nx, activeCount, zeroX, zeroP);
            }
            if (jrtk.epoch <= 25 && iter == 0) {
                int ib0 = jrtk.IB(0, blObs[0].sat[0], 0);
                if (ib0 >= 0 && ib0 < nx) {
                    log.debug("epoch={} iter={} nv={} xp[ib0]={} Pp[ib0,ib0]={} ib0={}",
                            jrtk.epoch, iter, nv, xp[ib0], Pp[ib0 * nx + ib0], ib0);
                }
                int vsatCount = 0;
                for (int si = 0; si < Constants.MAXSAT; si++) {
                    for (int fi = 0; fi < nf; fi++) {
                        if (jrtk.ssat[si].vsat[fi] != 0) vsatCount++;
                    }
                }
                log.debug("epoch={} vsatCount={}/{}", jrtk.epoch, vsatCount, Constants.MAXSAT * nf);
            }
        }

        if (stat == Constants.SOLQ_FLOAT) {
            System.arraycopy(xp, 0, jrtk.x, 0, nx);
            System.arraycopy(Pp, 0, jrtk.P, 0, nx * nx);

            for (int i = 0; i < 3; i++) {
                jrtk.sol.rr[i] = jrtk.rb[i] + jrtk.x[i];
                jrtk.sol.qr[i] = (float) jrtk.P[i * nx + i];
            }
            jrtk.sol.qr[3] = (float) jrtk.P[0 * nx + 1];
            jrtk.sol.qr[4] = (float) jrtk.P[1 * nx + 2];
            jrtk.sol.qr[5] = (float) jrtk.P[0 * nx + 2];

            jrtk.sol.ns = 0;
            for (int i = 0; i < Constants.MAXSAT; i++) {
                for (int f = 0; f < nf; f++) {
                    if (jrtk.ssat[i].vsat[f] != 0 && f == 0) jrtk.sol.ns++;
                }
            }

            jrtk.sol.stat = Constants.SOLQ_FLOAT;

            if (opt.modear != Constants.ARMODE_OFF && opt.thresar[0] >= 1.0) {
                stat = resolveAmbiguity(jrtk, blObs, nf, nav);
                if (stat == Constants.SOLQ_FIX) {
                    jrtk.sol.stat = Constants.SOLQ_FIX;
                    for (int i = 0; i < 3; i++) {
                        jrtk.sol.rr[i] = jrtk.rb[i] + jrtk.x[i];
                    }
                    log.debug("AR FIX: ratio={}", jrtk.sol.ratio);
                }
            }
        }

        for (int i = 0; i < Constants.MAXSAT; i++) {
            for (int f = 0; f < nf; f++) {
                if (jrtk.ssat[i].vsat[f] == 0) continue;
                if (jrtk.ssat[i].lock[f] < 0 || (jrtk.nfix > 0 && jrtk.ssat[i].fix[f] >= 2)) {
                    jrtk.ssat[i].lock[f]++;
                }
            }
        }

        jrtk.epoch++;
        return stat != Constants.SOLQ_NONE ? 1 : 0;
    }

    private static int resolveAmbiguity(JointRtk jrtk, BaselineObs[] blObs, int nf, Nav nav) {
        PrcOpt opt = jrtk.opt;
        int nx = jrtk.nx;
        double thresAR = opt.thresar[0];

        double posvar = 0.0;
        for (int i = 0; i < 3; i++) posvar += jrtk.P[i * nx + i];
        posvar /= 3.0;
        if (posvar > opt.thresar[1]) {
            jrtk.sol.ratio = 0.0f;
            log.debug("AR skip: posvar={} > thresar[1]={}", posvar, opt.thresar[1]);
            return Constants.SOLQ_FLOAT;
        }

        double minRatio = Double.POSITIVE_INFINITY;
        double[] xa = Arrays.copyOf(jrtk.x, nx);
        int[] allAmbIdx = new int[Constants.MAXSAT * nf * jrtk.nBaseline];
        int nbTotal = 0;

        for (int b = 0; b < jrtk.nBaseline; b++) {
            BaselineObs bo = blObs[b];
            if (bo == null || bo.ns <= 0) continue;

            int[] ambIdx = new int[bo.ns * nf];
            int nb = 0;
            int nbSkipLock = 0;
            int nbSkipVsat = 0;
            for (int j = 0; j < bo.ns; j++) {
                int sat = bo.sat[j];
                for (int f = 0; f < nf; f++) {
                    int idx = jrtk.IB(b, sat, f);
                    if (idx < 0 || idx >= nx) continue;
                    if (jrtk.ssat[sat - 1].vsat[f] == 0) { nbSkipVsat++; continue; }
                    if (jrtk.ssat[sat - 1].lock[f] < 0) { nbSkipLock++; continue; }
                    ambIdx[nb++] = idx;
                }
            }
            if (nb < 2) {
                if (jrtk.epoch % 50 == 0) {
                    log.debug("AR baseline[{}]: nb={} skipLock={} skipVsat={} minlock={}",
                            b, nb, nbSkipLock, nbSkipVsat, opt.minlock);
                }
                minRatio = 0.0;
                continue;
            }

            double[] a = new double[nb];
            double[] Q = new double[nb * nb];
            for (int i = 0; i < nb; i++) {
                a[i] = jrtk.x[ambIdx[i]];
                for (int j = 0; j < nb; j++) {
                    Q[i * nb + j] = jrtk.P[ambIdx[i] * nx + ambIdx[j]];
                }
            }

            double[] F = new double[nb * 2];
            double[] s = new double[2];
            int info = Lambda.lambda(nb, 2, a, Q, F, s);
            if (info != 0 || s[0] <= 0.0) {
                minRatio = 0.0;
                if (jrtk.epoch % 50 == 0) {
                    double maxA = 0, maxQ = 0;
                    for (int ii = 0; ii < nb; ii++) { maxA = Math.max(maxA, Math.abs(a[ii])); maxQ = Math.max(maxQ, Q[ii*nb+ii]); }
                    log.debug("AR baseline[{}]: LAMBDA failed info={} s0={} nb={} maxA={} maxQdiag={}", b, info, s[0], nb, maxA, maxQ);
                }
                continue;
            }

            double ratio = s[1] / s[0];
            if (ratio < minRatio) minRatio = ratio;

            if (jrtk.epoch % 50 == 0 || ratio >= thresAR) {
                double maxVar = 0, minVar = Double.MAX_VALUE;
                for (int ii = 0; ii < nb; ii++) { maxVar = Math.max(maxVar, Q[ii*nb+ii]); minVar = Math.min(minVar, Q[ii*nb+ii]); }
                log.debug("AR baseline[{}]: nb={} ratio={} thres={} maxVar={} minVar={}", b, nb, String.format("%.2f", ratio), thresAR, String.format("%.4f", maxVar), String.format("%.4f", minVar));
            }

            if (ratio >= thresAR) {
                for (int i = 0; i < nb; i++) {
                    xa[ambIdx[i]] = F[i * 2];
                    allAmbIdx[nbTotal++] = ambIdx[i];
                }
            }
        }

        jrtk.sol.ratio = (float) minRatio;
        if (minRatio >= thresAR && nbTotal >= 2) {
            int na = jrtk.na;
            for (int i = 0; i < na; i++) {
                for (int k = 0; k < nbTotal; k++) {
                    xa[i] -= jrtk.P[i * nx + allAmbIdx[k]] * (jrtk.x[allAmbIdx[k]] - xa[allAmbIdx[k]]);
                }
            }
            System.arraycopy(xa, 0, jrtk.x, 0, nx);
            return Constants.SOLQ_FIX;
        }
        return Constants.SOLQ_FLOAT;
    }

    private static void udstate(JointRtk jrtk, Obsd[] roverObs, int roverN,
                                BaseStation[] bases, int nBaseline, Nav nav,
                                BaselineObs[] blObs) {
        PrcOpt opt = jrtk.opt;
        int nx = jrtk.nx;
        int nf = (opt.ionoopt == Constants.IONOOPT_IFLC) ? 1 : opt.nf;

        if (opt.tropopt == Constants.TROPOPT_EST || opt.tropopt == Constants.TROPOPT_ESTG) {
            for (int b = 0; b < nBaseline; b++) {
                for (int r = 0; r < 2; r++) {
                    int it = jrtk.IT(b, r);
                    if (it < 0 || it >= nx) continue;
                    if (jrtk.P[it * nx + it] == 0.0) {
                        double varTrop = JointDdRes.SQR(opt.std[2]);
                        JointDdRes.initx(jrtk.x, jrtk.P, nx, Constants.INIT_ZWD, varTrop, it);
                    }
                }
            }
        }

        if (opt.ionoopt == Constants.IONOOPT_EST) {
            for (int b = 0; b < nBaseline; b++) {
                BaselineObs bo = blObs[b];
                if (bo == null) continue;
                double bl = 0.0;
                for (int i = 0; i < 3; i++) bl += jrtk.x[i] * jrtk.x[i];
                bl = Math.sqrt(bl);
                for (int i = 0; i < bo.ns; i++) {
                    int ii = jrtk.II(b, bo.sat[i]);
                    if (ii < 0 || ii >= nx) continue;
                    if (jrtk.P[ii * nx + ii] == 0.0) {
                        double varIono = JointDdRes.SQR(opt.std[1] * bl / 1E4);
                        JointDdRes.initx(jrtk.x, jrtk.P, nx, 1E-6, varIono, ii);
                    }
                }
            }
        }

        if (opt.mode > Constants.PMODE_DGPS) {
            for (int b = 0; b < nBaseline; b++) {
                BaselineObs bo = blObs[b];
                if (bo == null) continue;
                BaseStation bs = bases[b];
                for (int i = 0; i < bo.ns; i++) {
                    for (int f = 0; f < nf; f++) {
                        int idx = jrtk.IB(b, bo.sat[i], f);
                        if (idx < 0 || idx >= nx) continue;
                        if (jrtk.P[idx * nx + idx] == 0.0) {
                            double biasVal = 0.0;
                            Obsd obsR = roverObs[bo.iu[i]];
                            Obsd obsB = bs.obsBuf[bo.ir[i] - bo.nu];
                            if (obsR != null && obsB != null && f < opt.nf) {
                                if (obsR.P[f] != 0.0 && obsB.P[f] != 0.0 &&
                                    obsR.L[f] != 0.0 && obsB.L[f] != 0.0) {
                                    double freqi = SatUtils.sat2freq(bo.sat[i], obsR.code[f], nav);
                                    if (freqi > 0.0) {
                                        double cp = obsR.L[f] - obsB.L[f];
                                        double pr = obsR.P[f] - obsB.P[f];
                                        biasVal = cp - pr * freqi / Constants.CLIGHT;
                                    }
                                }
                            }
                            if (biasVal == 0.0) biasVal = 1E-10;
                            double varAmb = JointDdRes.SQR(opt.std[0]);
                            JointDdRes.initx(jrtk.x, jrtk.P, nx, biasVal, varAmb, idx);
                            jrtk.ssat[bo.sat[i] - 1].lock[f] = -opt.minlock;
                        }
                    }
                }
            }
        }
    }
}