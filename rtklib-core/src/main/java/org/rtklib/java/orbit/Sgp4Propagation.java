package org.rtklib.java.orbit;


import static org.rtklib.java.orbit.Sgp4Constants.TWOPI;
import static org.rtklib.java.orbit.Sgp4Math.mod;

/**
 * SGP4/SDP4 传播算法核心实现，逐行移植自 python-sgp4 的 propagation.py。
 *
 * <p>本类严格按 python-sgp4 (Brandon Rhodes) 的 propagation.py 顺序与变量名
 * 进行 Java 移植。所有公式与边界处理保持一致，仅在以下方面做必要适配：</p>
 * <ul>
 *   <li>Python 多返回值 → 内部静态类 XxxxResult 包装</li>
 *   <li>Python % 对负数取非负余数 → 调用 {@link Sgp4Math#mod}</li>
 *   <li>Python 'a'/'n' 字符串单字符 → Java char</li>
 *   <li>Python True/False/None → Java true/false/Double.NaN</li>
 * </ul>
 *
 * <p>单位：位置 km、速度 km/s、坐标系 WGS72 TEME。</p>
 */
public final class Sgp4Propagation {

    private Sgp4Propagation() {}

    // ===================== 内部结果包装类 =====================

    /** _dpper 返回值：5 个修改后的轨道根数。 */
    private static final class DpperResult {
        double ep, inclp, nodep, argpp, mp;
    }

    /** _dscom 返回值（共 80+ 项）。 */
    private static final class DscomResult {
        double snodm, cnodm, sinim, cosim, sinomm, cosomm;
        double day, e3, ee2, em, emsq, gam;
        double peo, pgho, pho, pinco, plo, rtemsq;
        double se2, se3, sgh2, sgh3, sgh4, sh2, sh3;
        double si2, si3, sl2, sl3, sl4;
        double s1, s2, s3, s4, s5, s6, s7;
        double ss1, ss2, ss3, ss4, ss5, ss6, ss7;
        double sz1, sz2, sz3, sz11, sz12, sz13, sz21, sz22, sz23, sz31, sz32, sz33;
        double xgh2, xgh3, xgh4, xh2, xh3, xi2, xi3, xl2, xl3, xl4;
        double nm, z1, z2, z3, z11, z12, z13, z21, z22, z23, z31, z32, z33;
        double zmol, zmos;
    }

    /** _dsinit 返回值。 */
    private static final class DsinitResult {
        double em, argpm, inclm, mm, nm, nodem;
        int irez;
        double atime;
        double d2201, d2211, d3210, d3222, d4410, d4422, d5220, d5232, d5421, d5433;
        double dedt, didt, dmdt, dndt, dnodt, domdt;
        double del1, del2, del3, xfact, xlamo, xli, xni;
    }

    /** _dspace 返回值。 */
    private static final class DspaceResult {
        double atime, em, argpm, inclm, xli, mm, xni, nodem, dndt, nm;
    }

    /** _initl 返回值。 */
    private static final class InitlResult {
        double no, ainv, ao, con41, con42, cosio, cosio2;
        double eccsq, omeosq, posq, rp, rteosq, sinio, gsto;
        char method;
    }

    // ===================== _dpper =====================

    /**
     * 深空长期周期项贡献。逐行移植自 propagation.py 的 _dpper()。
     */
    private static DpperResult _dpper(Satellite satrec, double inclo, char init,
                                      double ep, double inclp, double nodep,
                                      double argpp, double mp, char opsmode) {
        DpperResult r = new DpperResult();

        // 拷贝 satrec 属性到局部变量，与 python-sgp4 一致
        double e3 = satrec.e3;
        double ee2 = satrec.ee2;
        double peo = satrec.peo;
        double pgho = satrec.pgho;
        double pho = satrec.pho;
        double pinco = satrec.pinco;
        double plo = satrec.plo;
        double se2 = satrec.se2;
        double se3 = satrec.se3;
        double sgh2 = satrec.sgh2;
        double sgh3 = satrec.sgh3;
        double sgh4 = satrec.sgh4;
        double sh2 = satrec.sh2;
        double sh3 = satrec.sh3;
        double si2 = satrec.si2;
        double si3 = satrec.si3;
        double sl2 = satrec.sl2;
        double sl3 = satrec.sl3;
        double sl4 = satrec.sl4;
        double t = satrec.t;
        double xgh2 = satrec.xgh2;
        double xgh3 = satrec.xgh3;
        double xgh4 = satrec.xgh4;
        double xh2 = satrec.xh2;
        double xh3 = satrec.xh3;
        double xi2 = satrec.xi2;
        double xi3 = satrec.xi3;
        double xl2 = satrec.xl2;
        double xl3 = satrec.xl3;
        double xl4 = satrec.xl4;
        double zmol = satrec.zmol;
        double zmos = satrec.zmos;

        // ---------------------- constants -----------------------------
        double zns = 1.19459e-5;
        double zes = 0.01675;
        double znl = 1.5835218e-4;
        double zel = 0.05490;

        // --------------- calculate time varying periodics -----------
        double zm = zmos + zns * t;
        if (init == 'y') {
            zm = zmos;
        }
        double zf = zm + 2.0 * zes * Math.sin(zm);
        double sinzf = Math.sin(zf);
        double f2 = 0.5 * sinzf * sinzf - 0.25;
        double f3 = -0.5 * sinzf * Math.cos(zf);
        double ses = se2 * f2 + se3 * f3;
        double sis = si2 * f2 + si3 * f3;
        double sls = sl2 * f2 + sl3 * f3 + sl4 * sinzf;
        double sghs = sgh2 * f2 + sgh3 * f3 + sgh4 * sinzf;
        double shs = sh2 * f2 + sh3 * f3;
        zm = zmol + znl * t;
        if (init == 'y') {
            zm = zmol;
        }
        zf = zm + 2.0 * zel * Math.sin(zm);
        sinzf = Math.sin(zf);
        f2 = 0.5 * sinzf * sinzf - 0.25;
        f3 = -0.5 * sinzf * Math.cos(zf);
        double sel = ee2 * f2 + e3 * f3;
        double sil = xi2 * f2 + xi3 * f3;
        double sll = xl2 * f2 + xl3 * f3 + xl4 * sinzf;
        double sghl = xgh2 * f2 + xgh3 * f3 + xgh4 * sinzf;
        double shll = xh2 * f2 + xh3 * f3;
        double pe = ses + sel;
        double pinc = sis + sil;
        double pl = sls + sll;
        double pgh = sghs + sghl;
        double ph = shs + shll;

        if (init == 'n') {

            pe = pe - peo;
            pinc = pinc - pinco;
            pl = pl - plo;
            pgh = pgh - pgho;
            ph = ph - pho;
            inclp = inclp + pinc;
            ep = ep + pe;
            double sinip = Math.sin(inclp);
            double cosip = Math.cos(inclp);

            // ----------------- apply periodics directly ------------
            // sgp4fix for lyddane choice: use perturbed inclination (gsfc 版本)
            if (inclp >= 0.2) {

                ph = ph / sinip;
                pgh = pgh - cosip * ph;
                argpp = argpp + pgh;
                nodep = nodep + ph;
                mp = mp + pl;

            } else {

                // ---- apply periodics with lyddane modification ----
                double sinop = Math.sin(nodep);
                double cosop = Math.cos(nodep);
                double alfdp = sinip * sinop;
                double betdp = sinip * cosop;
                double dalf = ph * cosop + pinc * cosip * sinop;
                double dbet = -ph * sinop + pinc * cosip * cosop;
                alfdp = alfdp + dalf;
                betdp = betdp + dbet;
                // python: nodep = nodep % twopi if nodep >= 0.0 else -(-nodep % twopi)
                nodep = (nodep >= 0.0) ? mod(nodep, TWOPI) : -mod(-nodep, TWOPI);
                // sgp4fix for afspc written intrinsic functions
                if (nodep < 0.0 && opsmode == 'a') {
                    nodep = nodep + TWOPI;
                }
                double xls = mp + argpp + pl + pgh + (cosip - pinc * sinip) * nodep;
                double xnoh = nodep;
                nodep = Math.atan2(alfdp, betdp);
                // sgp4fix for afspc written intrinsic functions
                if (nodep < 0.0 && opsmode == 'a') {
                    nodep = nodep + TWOPI;
                }
                if (Math.abs(xnoh - nodep) > Math.PI) {
                    if (nodep < xnoh) {
                        nodep = nodep + TWOPI;
                    } else {
                        nodep = nodep - TWOPI;
                    }
                }
                mp = mp + pl;
                argpp = xls - mp - cosip * nodep;
            }
        }

        r.ep = ep;
        r.inclp = inclp;
        r.nodep = nodep;
        r.argpp = argpp;
        r.mp = mp;
        return r;
    }

    // ===================== _dscom =====================

    /**
     * 深空公共项。逐行移植自 propagation.py 的 _dscom()。
     */
    private static DscomResult _dscom(double epoch, double ep, double argpp, double tc,
                                      double inclp, double nodep, double np) {
        DscomResult r = new DscomResult();

        // -------------------------- constants -------------------------
        double zes = 0.01675;
        double zel = 0.05490;
        double c1ss = 2.9864797e-6;
        double c1l = 4.7968065e-7;
        double zsinis = 0.39785416;
        double zcosis = 0.91744867;
        double zcosgs = 0.1945905;
        double zsings = -0.98088458;

        // --------------------- local variables ------------------------
        double nm = np;
        double em = ep;
        double snodm = Math.sin(nodep);
        double cnodm = Math.cos(nodep);
        double sinomm = Math.sin(argpp);
        double cosomm = Math.cos(argpp);
        double sinim = Math.sin(inclp);
        double cosim = Math.cos(inclp);
        double emsq = em * em;
        double betasq = 1.0 - emsq;
        double rtemsq = Math.sqrt(betasq);

        // ---------------- initialize lunar solar terms ---------------
        double peo = 0.0;
        double pinco = 0.0;
        double plo = 0.0;
        double pgho = 0.0;
        double pho = 0.0;
        double day = epoch + 18261.5 + tc / 1440.0;
        double xnodce = mod(4.5236020 - 9.2422029e-4 * day, TWOPI);
        double stem = Math.sin(xnodce);
        double ctem = Math.cos(xnodce);
        double zcosil = 0.91375164 - 0.03568096 * ctem;
        double zsinil = Math.sqrt(1.0 - zcosil * zcosil);
        double zsinhl = 0.089683511 * stem / zsinil;
        double zcoshl = Math.sqrt(1.0 - zsinhl * zsinhl);
        double gam = 5.8351514 + 0.0019443680 * day;
        double zx = 0.39785416 * stem / zsinil;
        double zy = zcoshl * ctem + 0.91744867 * zsinhl * stem;
        zx = Math.atan2(zx, zy);
        zx = gam + zx - xnodce;
        double zcosgl = Math.cos(zx);
        double zsingl = Math.sin(zx);

        // ------------------------- do solar terms ---------------------
        double zcosg = zcosgs;
        double zsing = zsings;
        double zcosi = zcosis;
        double zsini = zsinis;
        double zcosh = cnodm;
        double zsinh = snodm;
        double cc = c1ss;
        double xnoi = 1.0 / nm;

        // 局部变量（在两个 lsflg 循环中累计）
        double s1 = 0, s2 = 0, s3 = 0, s4 = 0, s5 = 0, s6 = 0, s7 = 0;
        double ss1 = 0, ss2 = 0, ss3 = 0, ss4 = 0, ss5 = 0, ss6 = 0, ss7 = 0;
        double sz1 = 0, sz2 = 0, sz3 = 0;
        double sz11 = 0, sz12 = 0, sz13 = 0;
        double sz21 = 0, sz22 = 0, sz23 = 0;
        double sz31 = 0, sz32 = 0, sz33 = 0;
        double z1 = 0, z2 = 0, z3 = 0;
        double z11 = 0, z12 = 0, z13 = 0;
        double z21 = 0, z22 = 0, z23 = 0;
        double z31 = 0, z32 = 0, z33 = 0;

        // Python: for lsflg in 1, 2:
        for (int lsflg : new int[]{1, 2}) {

            double a1 = zcosg * zcosh + zsing * zcosi * zsinh;
            double a3 = -zsing * zcosh + zcosg * zcosi * zsinh;
            double a7 = -zcosg * zsinh + zsing * zcosi * zcosh;
            double a8 = zsing * zsini;
            double a9 = zsing * zsinh + zcosg * zcosi * zcosh;
            double a10 = zcosg * zsini;
            double a2 = cosim * a7 + sinim * a8;
            double a4 = cosim * a9 + sinim * a10;
            double a5 = -sinim * a7 + cosim * a8;
            double a6 = -sinim * a9 + cosim * a10;

            double x1 = a1 * cosomm + a2 * sinomm;
            double x2 = a3 * cosomm + a4 * sinomm;
            double x3 = -a1 * sinomm + a2 * cosomm;
            double x4 = -a3 * sinomm + a4 * cosomm;
            double x5 = a5 * sinomm;
            double x6 = a6 * sinomm;
            double x7 = a5 * cosomm;
            double x8 = a6 * cosomm;

            z31 = 12.0 * x1 * x1 - 3.0 * x3 * x3;
            z32 = 24.0 * x1 * x2 - 6.0 * x3 * x4;
            z33 = 12.0 * x2 * x2 - 3.0 * x4 * x4;
            z1 = 3.0 * (a1 * a1 + a2 * a2) + z31 * emsq;
            z2 = 6.0 * (a1 * a3 + a2 * a4) + z32 * emsq;
            z3 = 3.0 * (a3 * a3 + a4 * a4) + z33 * emsq;
            z11 = -6.0 * a1 * a5 + emsq * (-24.0 * x1 * x7 - 6.0 * x3 * x5);
            z12 = -6.0 * (a1 * a6 + a3 * a5) + emsq
                    * (-24.0 * (x2 * x7 + x1 * x8) - 6.0 * (x3 * x6 + x4 * x5));
            z13 = -6.0 * a3 * a6 + emsq * (-24.0 * x2 * x8 - 6.0 * x4 * x6);
            z21 = 6.0 * a2 * a5 + emsq * (24.0 * x1 * x5 - 6.0 * x3 * x7);
            z22 = 6.0 * (a4 * a5 + a2 * a6) + emsq
                    * (24.0 * (x2 * x5 + x1 * x6) - 6.0 * (x4 * x7 + x3 * x8));
            z23 = 6.0 * a4 * a6 + emsq * (24.0 * x2 * x6 - 6.0 * x4 * x8);
            z1 = z1 + z1 + betasq * z31;
            z2 = z2 + z2 + betasq * z32;
            z3 = z3 + z3 + betasq * z33;
            s3 = cc * xnoi;
            s2 = -0.5 * s3 / rtemsq;
            s4 = s3 * rtemsq;
            s1 = -15.0 * em * s4;
            s5 = x1 * x3 + x2 * x4;
            s6 = x2 * x3 + x1 * x4;
            s7 = x2 * x4 - x1 * x3;

            // ----------------------- do lunar terms -------------------
            if (lsflg == 1) {
                ss1 = s1;
                ss2 = s2;
                ss3 = s3;
                ss4 = s4;
                ss5 = s5;
                ss6 = s6;
                ss7 = s7;
                sz1 = z1;
                sz2 = z2;
                sz3 = z3;
                sz11 = z11;
                sz12 = z12;
                sz13 = z13;
                sz21 = z21;
                sz22 = z22;
                sz23 = z23;
                sz31 = z31;
                sz32 = z32;
                sz33 = z33;
                zcosg = zcosgl;
                zsing = zsingl;
                zcosi = zcosil;
                zsini = zsinil;
                zcosh = zcoshl * cnodm + zsinhl * snodm;
                zsinh = snodm * zcoshl - cnodm * zsinhl;
                cc = c1l;
            }
        }

        double zmol = mod(4.7199672 + 0.22997150 * day - gam, TWOPI);
        double zmos = mod(6.2565837 + 0.017201977 * day, TWOPI);

        // ------------------------ do solar terms ----------------------
        double se2 = 2.0 * ss1 * ss6;
        double se3 = 2.0 * ss1 * ss7;
        double si2 = 2.0 * ss2 * sz12;
        double si3 = 2.0 * ss2 * (sz13 - sz11);
        double sl2 = -2.0 * ss3 * sz2;
        double sl3 = -2.0 * ss3 * (sz3 - sz1);
        double sl4 = -2.0 * ss3 * (-21.0 - 9.0 * emsq) * zes;
        double sgh2 = 2.0 * ss4 * sz32;
        double sgh3 = 2.0 * ss4 * (sz33 - sz31);
        double sgh4 = -18.0 * ss4 * zes;
        double sh2 = -2.0 * ss2 * sz22;
        double sh3 = -2.0 * ss2 * (sz23 - sz21);

        // ------------------------ do lunar terms ----------------------
        double ee2 = 2.0 * s1 * s6;
        double e3 = 2.0 * s1 * s7;
        double xi2 = 2.0 * s2 * z12;
        double xi3 = 2.0 * s2 * (z13 - z11);
        double xl2 = -2.0 * s3 * z2;
        double xl3 = -2.0 * s3 * (z3 - z1);
        double xl4 = -2.0 * s3 * (-21.0 - 9.0 * emsq) * zel;
        double xgh2 = 2.0 * s4 * z32;
        double xgh3 = 2.0 * s4 * (z33 - z31);
        double xgh4 = -18.0 * s4 * zel;
        double xh2 = -2.0 * s2 * z22;
        double xh3 = -2.0 * s2 * (z23 - z21);

        // 写回结果
        r.snodm = snodm; r.cnodm = cnodm; r.sinim = sinim; r.cosim = cosim;
        r.sinomm = sinomm; r.cosomm = cosomm;
        r.day = day; r.e3 = e3; r.ee2 = ee2; r.em = em; r.emsq = emsq; r.gam = gam;
        r.peo = peo; r.pgho = pgho; r.pho = pho; r.pinco = pinco; r.plo = plo;
        r.rtemsq = rtemsq;
        r.se2 = se2; r.se3 = se3; r.sgh2 = sgh2; r.sgh3 = sgh3; r.sgh4 = sgh4;
        r.sh2 = sh2; r.sh3 = sh3; r.si2 = si2; r.si3 = si3;
        r.sl2 = sl2; r.sl3 = sl3; r.sl4 = sl4;
        r.s1 = s1; r.s2 = s2; r.s3 = s3; r.s4 = s4; r.s5 = s5; r.s6 = s6; r.s7 = s7;
        r.ss1 = ss1; r.ss2 = ss2; r.ss3 = ss3; r.ss4 = ss4; r.ss5 = ss5; r.ss6 = ss6; r.ss7 = ss7;
        r.sz1 = sz1; r.sz2 = sz2; r.sz3 = sz3;
        r.sz11 = sz11; r.sz12 = sz12; r.sz13 = sz13;
        r.sz21 = sz21; r.sz22 = sz22; r.sz23 = sz23;
        r.sz31 = sz31; r.sz32 = sz32; r.sz33 = sz33;
        r.xgh2 = xgh2; r.xgh3 = xgh3; r.xgh4 = xgh4;
        r.xh2 = xh2; r.xh3 = xh3; r.xi2 = xi2; r.xi3 = xi3;
        r.xl2 = xl2; r.xl3 = xl3; r.xl4 = xl4;
        r.nm = nm;
        r.z1 = z1; r.z2 = z2; r.z3 = z3;
        r.z11 = z11; r.z12 = z12; r.z13 = z13;
        r.z21 = z21; r.z22 = z22; r.z23 = z23;
        r.z31 = z31; r.z32 = z32; r.z33 = z33;
        r.zmol = zmol; r.zmos = zmos;
        return r;
    }

    // ===================== _dsinit =====================

    /**
     * 深空初始化。逐行移植自 propagation.py 的 _dsinit()。
     */
    private static DsinitResult _dsinit(
            double xke, double cosim, double emsq, double argpo,
            double s1, double s2, double s3, double s4, double s5,
            double sinim, double ss1, double ss2, double ss3, double ss4, double ss5,
            double sz1, double sz3, double sz11, double sz13, double sz21, double sz23,
            double sz31, double sz33,
            double t, double tc, double gsto,
            double mo, double mdot, double no, double nodeo, double nodedot,
            double xpidot, double z1, double z3, double z11, double z13, double z21,
            double z23, double z31, double z33,
            double ecco, double eccsq,
            double em, double argpm, double inclm, double mm, double nm, double nodem,
            int irez, double atime,
            double d2201, double d2211, double d3210, double d3222,
            double d4410, double d4422, double d5220, double d5232, double d5421, double d5433,
            double dedt, double didt, double dmdt, double dnodt, double domdt,
            double del1, double del2, double del3, double xfact, double xlamo,
            double xli, double xni) {

        DsInitHolder h = new DsInitHolder();
        h.em = em; h.argpm = argpm; h.inclm = inclm; h.mm = mm; h.nm = nm; h.nodem = nodem;
        h.irez = irez; h.atime = atime;
        h.d2201 = d2201; h.d2211 = d2211; h.d3210 = d3210; h.d3222 = d3222;
        h.d4410 = d4410; h.d4422 = d4422; h.d5220 = d5220; h.d5232 = d5232;
        h.d5421 = d5421; h.d5433 = d5433;
        h.dedt = dedt; h.didt = didt; h.dmdt = dmdt; h.dnodt = dnodt; h.domdt = domdt;
        h.del1 = del1; h.del2 = del2; h.del3 = del3;
        h.xfact = xfact; h.xlamo = xlamo; h.xli = xli; h.xni = xni;

        double q22 = 1.7891679e-6;
        double q31 = 2.1460748e-6;
        double q33 = 2.2123015e-7;
        double root22 = 1.7891679e-6;
        double root44 = 7.3636953e-9;
        double root54 = 2.1765803e-9;
        double rptim = 4.37526908801129966e-3; // equates to 7.29211514668855e-5 rad/sec
        double root32 = 3.7393792e-7;
        double root52 = 1.1428639e-7;
        double x2o3 = 2.0 / 3.0;
        double znl = 1.5835218e-4;
        double zns = 1.19459e-5;

        // -------------------- deep space initialization ------------
        h.irez = 0;
        if (0.0034906585 < h.nm && h.nm < 0.0052359877) {
            h.irez = 1;
        }
        if (8.26e-3 <= h.nm && h.nm <= 9.24e-3 && h.em >= 0.5) {
            h.irez = 2;
        }

        // ------------------------ do solar terms -------------------
        double ses = ss1 * zns * ss5;
        double sis = ss2 * zns * (sz11 + sz13);
        double sls = -zns * ss3 * (sz1 + sz3 - 14.0 - 6.0 * emsq);
        double sghs = ss4 * zns * (sz31 + sz33 - 6.0);
        double shs = -zns * ss2 * (sz21 + sz23);
        // sgp4fix for 180 deg incl
        if (h.inclm < 5.2359877e-2 || h.inclm > Math.PI - 5.2359877e-2) {
            shs = 0.0;
        }
        if (sinim != 0.0) {
            shs = shs / sinim;
        }
        double sgs = sghs - cosim * shs;

        // ------------------------- do lunar terms ------------------
        h.dedt = ses + s1 * znl * s5;
        h.didt = sis + s2 * znl * (z11 + z13);
        h.dmdt = sls - znl * s3 * (z1 + z3 - 14.0 - 6.0 * emsq);
        double sghl = s4 * znl * (z31 + z33 - 6.0);
        double shll = -znl * s2 * (z21 + z23);
        // sgp4fix for 180 deg incl
        if (h.inclm < 5.2359877e-2 || h.inclm > Math.PI - 5.2359877e-2) {
            shll = 0.0;
        }
        h.domdt = sgs + sghl;
        h.dnodt = shs;
        if (sinim != 0.0) {
            h.domdt = h.domdt - cosim / sinim * shll;
            h.dnodt = h.dnodt + shll / sinim;
        }

        // ----------- calculate deep space resonance effects --------
        double dndt = 0.0;
        double theta = mod(gsto + tc * rptim, TWOPI);
        h.em = h.em + h.dedt * t;
        h.inclm = h.inclm + h.didt * t;
        h.argpm = h.argpm + h.domdt * t;
        h.nodem = h.nodem + h.dnodt * t;
        h.mm = h.mm + h.dmdt * t;

        // -------------- initialize the resonance terms -------------
        if (h.irez != 0) {

            double aonv = Math.pow(h.nm / xke, x2o3);

            // ---------- geopotential resonance for 12 hour orbits ------
            if (h.irez == 2) {

                double cosisq = cosim * cosim;
                double emo = h.em;
                h.em = ecco;
                double emsqo = emsq;
                emsq = eccsq;
                double eoc = h.em * emsq;
                double g201 = -0.306 - (h.em - 0.64) * 0.440;

                double g211, g310, g322, g410, g422, g520;
                if (h.em <= 0.65) {
                    g211 = 3.616 - 13.2470 * h.em + 16.2900 * emsq;
                    g310 = -19.302 + 117.3900 * h.em - 228.4190 * emsq + 156.5910 * eoc;
                    g322 = -18.9068 + 109.7927 * h.em - 214.6334 * emsq + 146.5816 * eoc;
                    g410 = -41.122 + 242.6940 * h.em - 471.0940 * emsq + 313.9530 * eoc;
                    g422 = -146.407 + 841.8800 * h.em - 1629.014 * emsq + 1083.4350 * eoc;
                    g520 = -532.114 + 3017.977 * h.em - 5740.032 * emsq + 3708.2760 * eoc;
                } else {
                    g211 = -72.099 + 331.819 * h.em - 508.738 * emsq + 266.724 * eoc;
                    g310 = -346.844 + 1582.851 * h.em - 2415.925 * emsq + 1246.113 * eoc;
                    g322 = -342.585 + 1554.908 * h.em - 2366.899 * emsq + 1215.972 * eoc;
                    g410 = -1052.797 + 4758.686 * h.em - 7193.992 * emsq + 3651.957 * eoc;
                    g422 = -3581.690 + 16178.110 * h.em - 24462.770 * emsq + 12422.520 * eoc;
                    if (h.em > 0.715) {
                        g520 = -5149.66 + 29936.92 * h.em - 54087.36 * emsq + 31324.56 * eoc;
                    } else {
                        g520 = 1464.74 - 4664.75 * h.em + 3763.64 * emsq;
                    }
                }

                double g533, g521, g532;
                if (h.em < 0.7) {
                    g533 = -919.22770 + 4988.6100 * h.em - 9064.7700 * emsq + 5542.21 * eoc;
                    g521 = -822.71072 + 4568.6173 * h.em - 8491.4146 * emsq + 5337.524 * eoc;
                    g532 = -853.66600 + 4690.2500 * h.em - 8624.7700 * emsq + 5341.4 * eoc;
                } else {
                    g533 = -37995.780 + 161616.52 * h.em - 229838.20 * emsq + 109377.94 * eoc;
                    g521 = -51752.104 + 218913.95 * h.em - 309468.16 * emsq + 146349.42 * eoc;
                    g532 = -40023.880 + 170470.89 * h.em - 242699.48 * emsq + 115605.82 * eoc;
                }

                double sini2 = sinim * sinim;
                double f220 = 0.75 * (1.0 + 2.0 * cosim + cosisq);
                double f221 = 1.5 * sini2;
                double f321 = 1.875 * sinim * (1.0 - 2.0 * cosim - 3.0 * cosisq);
                double f322 = -1.875 * sinim * (1.0 + 2.0 * cosim - 3.0 * cosisq);
                double f441 = 35.0 * sini2 * f220;
                double f442 = 39.3750 * sini2 * sini2;
                double f522 = 9.84375 * sinim * (sini2 * (1.0 - 2.0 * cosim - 5.0 * cosisq)
                        + 0.33333333 * (-2.0 + 4.0 * cosim + 6.0 * cosisq));
                double f523 = sinim * (4.92187512 * sini2 * (-2.0 - 4.0 * cosim + 10.0 * cosisq)
                        + 6.56250012 * (1.0 + 2.0 * cosim - 3.0 * cosisq));
                double f542 = 29.53125 * sinim * (2.0 - 8.0 * cosim + cosisq
                        * (-12.0 + 8.0 * cosim + 10.0 * cosisq));
                double f543 = 29.53125 * sinim * (-2.0 - 8.0 * cosim + cosisq
                        * (12.0 + 8.0 * cosim - 10.0 * cosisq));
                double xno2 = h.nm * h.nm;
                double ainv2 = aonv * aonv;
                double temp1 = 3.0 * xno2 * ainv2;
                double temp = temp1 * root22;
                h.d2201 = temp * f220 * g201;
                h.d2211 = temp * f221 * g211;
                temp1 = temp1 * aonv;
                temp = temp1 * root32;
                h.d3210 = temp * f321 * g310;
                h.d3222 = temp * f322 * g322;
                temp1 = temp1 * aonv;
                temp = 2.0 * temp1 * root44;
                h.d4410 = temp * f441 * g410;
                h.d4422 = temp * f442 * g422;
                temp1 = temp1 * aonv;
                temp = temp1 * root52;
                h.d5220 = temp * f522 * g520;
                h.d5232 = temp * f523 * g532;
                temp = 2.0 * temp1 * root54;
                h.d5421 = temp * f542 * g521;
                h.d5433 = temp * f543 * g533;
                h.xlamo = mod(mo + nodeo + nodeo - theta - theta, TWOPI);
                h.xfact = mdot + h.dmdt + 2.0 * (nodedot + h.dnodt - rptim) - no;
                h.em = emo;
                emsq = emsqo;
            }

            // ---------------- synchronous resonance terms --------------
            if (h.irez == 1) {
                double g200 = 1.0 + emsq * (-2.5 + 0.8125 * emsq);
                double g310 = 1.0 + 2.0 * emsq;
                double g300 = 1.0 + emsq * (-6.0 + 6.60937 * emsq);
                double f220 = 0.75 * (1.0 + cosim) * (1.0 + cosim);
                double f311 = 0.9375 * sinim * sinim * (1.0 + 3.0 * cosim) - 0.75 * (1.0 + cosim);
                double f330 = 1.0 + cosim;
                f330 = 1.875 * f330 * f330 * f330;
                h.del1 = 3.0 * h.nm * h.nm * aonv * aonv;
                h.del2 = 2.0 * h.del1 * f220 * g200 * q22;
                h.del3 = 3.0 * h.del1 * f330 * g300 * q33 * aonv;
                h.del1 = h.del1 * f311 * g310 * q31 * aonv;
                h.xlamo = mod(mo + nodeo + argpo - theta, TWOPI);
                h.xfact = mdot + xpidot - rptim + h.dmdt + h.domdt + h.dnodt - no;
            }

            // ------------ for sgp4, initialize the integrator ----------
            h.xli = h.xlamo;
            h.xni = no;
            h.atime = 0.0;
            h.nm = no + dndt;
        }
        // dndt 局部，写回结果时使用 nm 更新后的值
        h.dndt = dndt; // 注意：Python 中 _dsinit 没有 dndt 输出参数，但 sgp4init 解包时把 dndt 也作为局部；这里把当前 dndt 保留在结果中
        return buildDsInit(h);
    }

    /** _dsinit 内部状态载体（简化参数传递）。 */
    private static final class DsInitHolder {
        double em, argpm, inclm, mm, nm, nodem;
        int irez;
        double atime;
        double d2201, d2211, d3210, d3222, d4410, d4422, d5220, d5232, d5421, d5433;
        double dedt, didt, dmdt, dnodt, domdt, dndt;
        double del1, del2, del3, xfact, xlamo, xli, xni;
    }

    private static DsinitResult buildDsInit(DsInitHolder h) {
        DsinitResult r = new DsinitResult();
        r.em = h.em; r.argpm = h.argpm; r.inclm = h.inclm; r.mm = h.mm;
        r.nm = h.nm; r.nodem = h.nodem;
        r.irez = h.irez; r.atime = h.atime;
        r.d2201 = h.d2201; r.d2211 = h.d2211; r.d3210 = h.d3210; r.d3222 = h.d3222;
        r.d4410 = h.d4410; r.d4422 = h.d4422; r.d5220 = h.d5220; r.d5232 = h.d5232;
        r.d5421 = h.d5421; r.d5433 = h.d5433;
        r.dedt = h.dedt; r.didt = h.didt; r.dmdt = h.dmdt;
        r.dndt = h.dndt; r.dnodt = h.dnodt; r.domdt = h.domdt;
        r.del1 = h.del1; r.del2 = h.del2; r.del3 = h.del3;
        r.xfact = h.xfact; r.xlamo = h.xlamo; r.xli = h.xli; r.xni = h.xni;
        return r;
    }

    // ===================== _dspace =====================

    /**
     * 深空扰动传播。逐行移植自 propagation.py 的 _dspace()。
     */
    private static DspaceResult _dspace(
            int irez,
            double d2201, double d2211, double d3210, double d3222,
            double d4410, double d4422, double d5220, double d5232,
            double d5421, double d5433,
            double dedt, double del1, double del2, double del3,
            double didt, double dmdt, double dnodt, double domdt,
            double argpo, double argpdot,
            double t, double tc, double gsto,
            double xfact, double xlamo, double no,
            double atime, double em, double argpm, double inclm, double xli,
            double mm, double xni, double nodem, double nm) {

        DspaceResult r = new DspaceResult();

        double fasx2 = 0.13130908;
        double fasx4 = 2.8843198;
        double fasx6 = 0.37448087;
        double g22 = 5.7686396;
        double g32 = 0.95240898;
        double g44 = 1.8014998;
        double g52 = 1.0508330;
        double g54 = 4.4108898;
        double rptim = 4.37526908801129966e-3; // equates to 7.29211514668855e-5 rad/sec
        double stepp = 720.0;
        double stepn = -720.0;
        double step2 = 259200.0;

        // ----------- calculate deep space resonance effects -----------
        double dndt = 0.0;
        double theta = mod(gsto + tc * rptim, TWOPI);
        em = em + dedt * t;
        inclm = inclm + didt * t;
        argpm = argpm + domdt * t;
        nodem = nodem + dnodt * t;
        mm = mm + dmdt * t;

        // - update resonances : numerical (euler-maclaurin) integration -
        // ------------------------- epoch restart ----------------------
        double ft = 0.0;
        if (irez != 0) {

            // sgp4fix streamline check
            if (atime == 0.0 || t * atime <= 0.0 || Math.abs(t) < Math.abs(atime)) {
                atime = 0.0;
                xni = no;
                xli = xlamo;
            }

            // sgp4fix move check outside loop
            double delt;
            if (t > 0.0) {
                delt = stepp;
            } else {
                delt = stepn;
            }

            int iretn = 381; // added for do loop
            // iret = 0; // added for loop
            double xndt = 0, xldot = 0, xnddt = 0;
            double xomi = 0, x2omi = 0, x2li = 0;
            while (iretn == 381) {

                // ------------------- dot terms calculated ------------
                // ----------- near - synchronous resonance terms -------
                if (irez != 2) {
                    xndt = del1 * Math.sin(xli - fasx2)
                            + del2 * Math.sin(2.0 * (xli - fasx4))
                            + del3 * Math.sin(3.0 * (xli - fasx6));
                    xldot = xni + xfact;
                    xnddt = del1 * Math.cos(xli - fasx2)
                            + 2.0 * del2 * Math.cos(2.0 * (xli - fasx4))
                            + 3.0 * del3 * Math.cos(3.0 * (xli - fasx6));
                    xnddt = xnddt * xldot;
                } else {

                    // --------- near - half-day resonance terms --------
                    xomi = argpo + argpdot * atime;
                    x2omi = xomi + xomi;
                    x2li = xli + xli;
                    xndt = d2201 * Math.sin(x2omi + xli - g22)
                            + d2211 * Math.sin(xli - g22)
                            + d3210 * Math.sin(xomi + xli - g32)
                            + d3222 * Math.sin(-xomi + xli - g32)
                            + d4410 * Math.sin(x2omi + x2li - g44)
                            + d4422 * Math.sin(x2li - g44)
                            + d5220 * Math.sin(xomi + xli - g52)
                            + d5232 * Math.sin(-xomi + xli - g52)
                            + d5421 * Math.sin(xomi + x2li - g54)
                            + d5433 * Math.sin(-xomi + x2li - g54);
                    xldot = xni + xfact;
                    xnddt = d2201 * Math.cos(x2omi + xli - g22)
                            + d2211 * Math.cos(xli - g22)
                            + d3210 * Math.cos(xomi + xli - g32)
                            + d3222 * Math.cos(-xomi + xli - g32)
                            + d5220 * Math.cos(xomi + xli - g52)
                            + d5232 * Math.cos(-xomi + xli - g52)
                            + 2.0 * (d4410 * Math.cos(x2omi + x2li - g44)
                            + d4422 * Math.cos(x2li - g44)
                            + d5421 * Math.cos(xomi + x2li - g54)
                            + d5433 * Math.cos(-xomi + x2li - g54));
                    xnddt = xnddt * xldot;
                }

                // ----------------------- integrator -------------------
                // sgp4fix move end checks to end of routine
                if (Math.abs(t - atime) >= stepp) {
                    // iret = 0;
                    iretn = 381;
                } else {
                    ft = t - atime;
                    iretn = 0;
                }

                if (iretn == 381) {
                    xli = xli + xldot * delt + xndt * step2;
                    xni = xni + xndt * delt + xnddt * step2;
                    atime = atime + delt;
                }
            } // end while

            double xl;
            nm = xni + xndt * ft + xnddt * ft * ft * 0.5;
            xl = xli + xldot * ft + xndt * ft * ft * 0.5;
            if (irez != 1) {
                mm = xl - 2.0 * nodem + 2.0 * theta;
                dndt = nm - no;
            } else {
                mm = xl - nodem - argpm + theta;
                dndt = nm - no;
            }
            nm = no + dndt;
        }

        r.atime = atime;
        r.em = em;
        r.argpm = argpm;
        r.inclm = inclm;
        r.xli = xli;
        r.mm = mm;
        r.xni = xni;
        r.nodem = nodem;
        r.dndt = dndt;
        r.nm = nm;
        return r;
    }

    // ===================== _initl =====================

    /**
     * SGP4 初始化辅助。逐行移植自 propagation.py 的 _initl()。
     */
    private static InitlResult _initl(double xke, double j2,
                                      double ecco, double epoch, double inclo,
                                      double no, char method, char opsmode) {
        InitlResult r = new InitlResult();

        double x2o3 = 2.0 / 3.0;

        // ------------- calculate auxillary epoch quantities ----------
        double eccsq = ecco * ecco;
        double omeosq = 1.0 - eccsq;
        double rteosq = Math.sqrt(omeosq);
        double cosio = Math.cos(inclo);
        double cosio2 = cosio * cosio;

        // ------------------ un-kozai the mean motion -----------------
        double ak = Math.pow(xke / no, x2o3);
        double d1 = 0.75 * j2 * (3.0 * cosio2 - 1.0) / (rteosq * omeosq);
        double del_ = d1 / (ak * ak);
        double adel = ak * (1.0 - del_ * del_ - del_
                * (1.0 / 3.0 + 134.0 * del_ * del_ / 81.0));
        del_ = d1 / (adel * adel);
        no = no / (1.0 + del_);

        double ao = Math.pow(xke / no, x2o3);
        double sinio = Math.sin(inclo);
        double po = ao * omeosq;
        double con42 = 1.0 - 5.0 * cosio2;
        double con41 = -con42 - cosio2 - cosio2;
        double ainv = 1.0 / ao;
        double posq = po * po;
        double rp = ao * (1.0 - ecco);
        method = 'n';

        // sgp4fix modern approach to finding sidereal time
        double gsto;
        if (opsmode == 'a') {

            // sgp4fix use old way of finding gst
            // count integer number of days from 0 jan 1970
            double ts70 = epoch - 7305.0;
            double ds70 = Sgp4Math.floorToLong(ts70 + 1.0e-8);
            double tfrac = ts70 - ds70;
            // find greenwich location at epoch
            double c1 = 1.72027916940703639e-2;
            double thgr70 = 1.7321343856509374;
            double fk5r = 5.07551419432269442e-15;
            double c1p2p = c1 + TWOPI;
            gsto = mod(thgr70 + c1 * ds70 + c1p2p * tfrac + ts70 * ts70 * fk5r, TWOPI);
            if (gsto < 0.0) {
                gsto = gsto + TWOPI;
            }
        } else {
            gsto = Sgp4Time.gstime(epoch + 2433281.5);
        }

        r.no = no;
        r.method = method;
        r.ainv = ainv;
        r.ao = ao;
        r.con41 = con41;
        r.con42 = con42;
        r.cosio = cosio;
        r.cosio2 = cosio2;
        r.eccsq = eccsq;
        r.omeosq = omeosq;
        r.posq = posq;
        r.rp = rp;
        r.rteosq = rteosq;
        r.sinio = sinio;
        r.gsto = gsto;
        return r;
    }

    // ===================== sgp4init =====================

    /**
     * SGP4 主初始化。逐行移植自 propagation.py 的 sgp4init()。
     *
     * @param whichconst 重力常数集合（如 {@link Sgp4Constants#WGS72}）
     * @param opsmode 操作模式：'a'=AFSPC, 'i'=improved
     * @param satn 5 字符卫星编号
     * @param epoch 历元（自 1950-01-00 0h 起的天数，python-sgp4 内部用 jdsatepoch-2433281.5）
     * @param xbstar B* 阻力系数
     * @param xndot, xnddot 一阶/二阶平均运动导数
     * @param xecco 偏心率
     * @param xargpo 近地点幅角 (rad)
     * @param xinclo 轨道倾角 (rad)
     * @param xmo 平近点角 (rad)
     * @param xno_kozai 平均运动 (rad/min, Kozai)
     * @param xnodeo 升交点赤经 (rad)
     * @param satrec 待填充的 Satellite 对象（in-place 修改）
     * @return true=成功；false=失败（satrec.error 已设置）
     */
    public static boolean sgp4init(Sgp4Constants.Gravity whichconst, char opsmode, String satn,
                                   double epoch, double xbstar, double xndot, double xnddot,
                                   double xecco, double xargpo, double xinclo, double xmo,
                                   double xno_kozai, double xnodeo, Satellite satrec) {

        double temp4 = 1.5e-12;

        // ----------- set all near earth variables to zero ------------
        satrec.isimp = 0; satrec.method = 'n'; satrec.aycof = 0.0;
        satrec.con41 = 0.0; satrec.cc1 = 0.0; satrec.cc4 = 0.0;
        satrec.cc5 = 0.0; satrec.d2 = 0.0; satrec.d3 = 0.0;
        satrec.d4 = 0.0; satrec.delmo = 0.0; satrec.eta = 0.0;
        satrec.argpdot = 0.0; satrec.omgcof = 0.0; satrec.sinmao = 0.0;
        satrec.t = 0.0; satrec.t2cof = 0.0; satrec.t3cof = 0.0;
        satrec.t4cof = 0.0; satrec.t5cof = 0.0; satrec.x1mth2 = 0.0;
        satrec.x7thm1 = 0.0; satrec.mdot = 0.0; satrec.nodedot = 0.0;
        satrec.xlcof = 0.0; satrec.xmcof = 0.0; satrec.nodecf = 0.0;

        // ----------- set all deep space variables to zero ------------
        satrec.irez = 0; satrec.d2201 = 0.0; satrec.d2211 = 0.0;
        satrec.d3210 = 0.0; satrec.d3222 = 0.0; satrec.d4410 = 0.0;
        satrec.d4422 = 0.0; satrec.d5220 = 0.0; satrec.d5232 = 0.0;
        satrec.d5421 = 0.0; satrec.d5433 = 0.0; satrec.dedt = 0.0;
        satrec.del1 = 0.0; satrec.del2 = 0.0; satrec.del3 = 0.0;
        satrec.didt = 0.0; satrec.dmdt = 0.0; satrec.dnodt = 0.0;
        satrec.domdt = 0.0; satrec.e3 = 0.0; satrec.ee2 = 0.0;
        satrec.peo = 0.0; satrec.pgho = 0.0; satrec.pho = 0.0;
        satrec.pinco = 0.0; satrec.plo = 0.0; satrec.se2 = 0.0;
        satrec.se3 = 0.0; satrec.sgh2 = 0.0; satrec.sgh3 = 0.0;
        satrec.sgh4 = 0.0; satrec.sh2 = 0.0; satrec.sh3 = 0.0;
        satrec.si2 = 0.0; satrec.si3 = 0.0; satrec.sl2 = 0.0;
        satrec.sl3 = 0.0; satrec.sl4 = 0.0; satrec.gsto = 0.0;
        satrec.xfact = 0.0; satrec.xgh2 = 0.0; satrec.xgh3 = 0.0;
        satrec.xgh4 = 0.0; satrec.xh2 = 0.0; satrec.xh3 = 0.0;
        satrec.xi2 = 0.0; satrec.xi3 = 0.0; satrec.xl2 = 0.0;
        satrec.xl3 = 0.0; satrec.xl4 = 0.0; satrec.xlamo = 0.0;
        satrec.zmol = 0.0; satrec.zmos = 0.0; satrec.atime = 0.0;
        satrec.xli = 0.0; satrec.xni = 0.0;

        // ------------------------ earth constants -----------------------
        satrec.tumin = whichconst.tumin;
        satrec.mu = whichconst.mu;
        satrec.radiusearthkm = whichconst.radiusearthkm;
        satrec.xke = whichconst.xke;
        satrec.j2 = whichconst.j2;
        satrec.j3 = whichconst.j3;
        satrec.j4 = whichconst.j4;
        satrec.j3oj2 = whichconst.j3oj2;

        satrec.error = 0;
        satrec.operationmode = opsmode;
        satrec.satnum_str = satn;

        satrec.bstar = xbstar;
        satrec.ndot = xndot;
        satrec.nddot = xnddot;
        satrec.ecco = xecco;
        satrec.argpo = xargpo;
        satrec.inclo = xinclo;
        satrec.mo = xmo;
        satrec.no_kozai = xno_kozai;
        satrec.nodeo = xnodeo;

        // single averaged mean elements
        satrec.am = 0.0;
        satrec.em = 0.0;
        satrec.im = 0.0;
        satrec.Om = 0.0;
        satrec.mm = 0.0;
        satrec.nm = 0.0;

        double ss = 78.0 / satrec.radiusearthkm + 1.0;
        double qzms2ttemp = (120.0 - 78.0) / satrec.radiusearthkm;
        double qzms2t = qzms2ttemp * qzms2ttemp * qzms2ttemp * qzms2ttemp;
        double x2o3 = 2.0 / 3.0;

        satrec.init = 'y';
        satrec.t = 0.0;

        InitlResult il = _initl(satrec.xke, satrec.j2, satrec.ecco, epoch,
                satrec.inclo, satrec.no_kozai, satrec.method, satrec.operationmode);
        satrec.no_unkozai = il.no;
        char method = il.method;
        double ainv = il.ainv;
        double ao = il.ao;
        satrec.con41 = il.con41;
        double con42 = il.con42;
        double cosio = il.cosio;
        double cosio2 = il.cosio2;
        double eccsq = il.eccsq;
        double omeosq = il.omeosq;
        double posq = il.posq;
        double rp = il.rp;
        double rteosq = il.rteosq;
        double sinio = il.sinio;
        satrec.gsto = il.gsto;

        satrec.a = Math.pow(satrec.no_unkozai * satrec.tumin, -2.0 / 3.0);
        satrec.alta = satrec.a * (1.0 + satrec.ecco) - 1.0;
        satrec.altp = satrec.a * (1.0 - satrec.ecco) - 1.0;

        if (omeosq >= 0.0 || satrec.no_unkozai >= 0.0) {

            satrec.isimp = 0;
            if (rp < 220.0 / satrec.radiusearthkm + 1.0) {
                satrec.isimp = 1;
            }
            double sfour = ss;
            double qzms24 = qzms2t;
            double perige = (rp - 1.0) * satrec.radiusearthkm;

            // - for perigees below 156 km, s and qoms2t are altered -
            if (perige < 156.0) {
                sfour = perige - 78.0;
                if (perige < 98.0) {
                    sfour = 20.0;
                }
                // sgp4fix use multiply for speed instead of pow
                double qzms24temp = (120.0 - sfour) / satrec.radiusearthkm;
                qzms24 = qzms24temp * qzms24temp * qzms24temp * qzms24temp;
                sfour = sfour / satrec.radiusearthkm + 1.0;
            }

            double pinvsq = 1.0 / posq;

            double tsi = 1.0 / (ao - sfour);
            satrec.eta = ao * satrec.ecco * tsi;
            double etasq = satrec.eta * satrec.eta;
            double eeta = satrec.ecco * satrec.eta;
            double psisq = Math.abs(1.0 - etasq);
            double coef = qzms24 * Math.pow(tsi, 4.0);
            double coef1 = coef / Math.pow(psisq, 3.5);
            double cc2 = coef1 * satrec.no_unkozai * (ao * (1.0 + 1.5 * etasq + eeta
                    * (4.0 + etasq)) + 0.375 * satrec.j2 * tsi / psisq * satrec.con41
                    * (8.0 + 3.0 * etasq * (8.0 + etasq)));
            satrec.cc1 = satrec.bstar * cc2;
            double cc3 = 0.0;
            if (satrec.ecco > 1.0e-4) {
                cc3 = -2.0 * coef * tsi * satrec.j3oj2 * satrec.no_unkozai * sinio / satrec.ecco;
            }
            satrec.x1mth2 = 1.0 - cosio2;
            satrec.cc4 = 2.0 * satrec.no_unkozai * coef1 * ao * omeosq
                    * (satrec.eta * (2.0 + 0.5 * etasq) + satrec.ecco
                    * (0.5 + 2.0 * etasq) - satrec.j2 * tsi / (ao * psisq)
                    * (-3.0 * satrec.con41 * (1.0 - 2.0 * eeta + etasq
                    * (1.5 - 0.5 * eeta)) + 0.75 * satrec.x1mth2
                    * (2.0 * etasq - eeta * (1.0 + etasq)) * Math.cos(2.0 * satrec.argpo)));
            satrec.cc5 = 2.0 * coef1 * ao * omeosq * (1.0 + 2.75
                    * (etasq + eeta) + eeta * etasq);
            double cosio4 = cosio2 * cosio2;
            double temp1 = 1.5 * satrec.j2 * pinvsq * satrec.no_unkozai;
            double temp2 = 0.5 * temp1 * satrec.j2 * pinvsq;
            double temp3 = -0.46875 * satrec.j4 * pinvsq * pinvsq * satrec.no_unkozai;
            satrec.mdot = satrec.no_unkozai + 0.5 * temp1 * rteosq * satrec.con41 + 0.0625
                    * temp2 * rteosq * (13.0 - 78.0 * cosio2 + 137.0 * cosio4);
            satrec.argpdot = (-0.5 * temp1 * con42 + 0.0625 * temp2
                    * (7.0 - 114.0 * cosio2 + 395.0 * cosio4)
                    + temp3 * (3.0 - 36.0 * cosio2 + 49.0 * cosio4));
            double xhdot1 = -temp1 * cosio;
            satrec.nodedot = xhdot1 + (0.5 * temp2 * (4.0 - 19.0 * cosio2)
                    + 2.0 * temp3 * (3.0 - 7.0 * cosio2)) * cosio;
            double xpidot = satrec.argpdot + satrec.nodedot;
            satrec.omgcof = satrec.bstar * cc3 * Math.cos(satrec.argpo);
            satrec.xmcof = 0.0;
            if (satrec.ecco > 1.0e-4) {
                satrec.xmcof = -x2o3 * coef * satrec.bstar / eeta;
            }
            satrec.nodecf = 3.5 * omeosq * xhdot1 * satrec.cc1;
            satrec.t2cof = 1.5 * satrec.cc1;
            // sgp4fix for divide by zero with xinco = 180 deg
            if (Math.abs(cosio + 1.0) > 1.5e-12) {
                satrec.xlcof = -0.25 * satrec.j3oj2 * sinio * (3.0 + 5.0 * cosio) / (1.0 + cosio);
            } else {
                satrec.xlcof = -0.25 * satrec.j3oj2 * sinio * (3.0 + 5.0 * cosio) / temp4;
            }
            satrec.aycof = -0.5 * satrec.j3oj2 * sinio;
            // sgp4fix use multiply for speed instead of pow
            double delmotemp = 1.0 + satrec.eta * Math.cos(satrec.mo);
            satrec.delmo = delmotemp * delmotemp * delmotemp;
            satrec.sinmao = Math.sin(satrec.mo);
            satrec.x7thm1 = 7.0 * cosio2 - 1.0;

            // --------------- deep space initialization -------------
            if (2 * Math.PI / satrec.no_unkozai >= 225.0) {

                satrec.method = 'd';
                satrec.isimp = 1;
                double tc = 0.0;
                double inclm = satrec.inclo;

                DscomResult dc = _dscom(epoch, satrec.ecco, satrec.argpo, tc,
                        satrec.inclo, satrec.nodeo, satrec.no_unkozai);

                // 写回 satrec 的 dscom 输出字段
                satrec.e3 = dc.e3; satrec.ee2 = dc.ee2;
                satrec.peo = dc.peo; satrec.pgho = dc.pgho; satrec.pho = dc.pho;
                satrec.pinco = dc.pinco; satrec.plo = dc.plo;
                satrec.se2 = dc.se2; satrec.se3 = dc.se3;
                satrec.sgh2 = dc.sgh2; satrec.sgh3 = dc.sgh3; satrec.sgh4 = dc.sgh4;
                satrec.sh2 = dc.sh2; satrec.sh3 = dc.sh3;
                satrec.si2 = dc.si2; satrec.si3 = dc.si3;
                satrec.sl2 = dc.sl2; satrec.sl3 = dc.sl3; satrec.sl4 = dc.sl4;
                satrec.xgh2 = dc.xgh2; satrec.xgh3 = dc.xgh3; satrec.xgh4 = dc.xgh4;
                satrec.xh2 = dc.xh2; satrec.xh3 = dc.xh3;
                satrec.xi2 = dc.xi2; satrec.xi3 = dc.xi3;
                satrec.xl2 = dc.xl2; satrec.xl3 = dc.xl3; satrec.xl4 = dc.xl4;
                satrec.zmol = dc.zmol; satrec.zmos = dc.zmos;

                // 局部变量（用于 dsinit）
                double sinim = dc.sinim, cosim = dc.cosim;
                double em = dc.em, emsq = dc.emsq;
                double s1 = dc.s1, s2 = dc.s2, s3 = dc.s3, s4 = dc.s4, s5 = dc.s5;
                double ss1 = dc.ss1, ss2 = dc.ss2, ss3 = dc.ss3, ss4 = dc.ss4, ss5 = dc.ss5;
                double sz1 = dc.sz1, sz3 = dc.sz3;
                double sz11 = dc.sz11, sz13 = dc.sz13;
                double sz21 = dc.sz21, sz23 = dc.sz23;
                double sz31 = dc.sz31, sz33 = dc.sz33;
                double z1 = dc.z1, z3 = dc.z3;
                double z11 = dc.z11, z13 = dc.z13;
                double z21 = dc.z21, z23 = dc.z23;
                double z31 = dc.z31, z33 = dc.z33;
                double nm = dc.nm;

                DpperResult dp = _dpper(satrec, inclm, satrec.init,
                        satrec.ecco, satrec.inclo, satrec.nodeo, satrec.argpo, satrec.mo,
                        satrec.operationmode);
                satrec.ecco = dp.ep;
                satrec.inclo = dp.inclp;
                satrec.nodeo = dp.nodep;
                satrec.argpo = dp.argpp;
                satrec.mo = dp.mp;

                double argpm = 0.0;
                double nodem = 0.0;
                double mm = 0.0;
                double dndt = 0.0; // sgp4init 中 dndt 是局部，不写回 satrec

                DsinitResult di = _dsinit(
                        satrec.xke,
                        cosim, emsq, satrec.argpo,
                        s1, s2, s3, s4, s5, sinim,
                        ss1, ss2, ss3, ss4, ss5,
                        sz1, sz3, sz11, sz13, sz21, sz23,
                        sz31, sz33,
                        satrec.t, tc, satrec.gsto,
                        satrec.mo, satrec.mdot, satrec.no_unkozai, satrec.nodeo,
                        satrec.nodedot, xpidot,
                        z1, z3, z11, z13, z21, z23, z31, z33,
                        satrec.ecco, eccsq,
                        em, argpm, inclm, mm, nm, nodem,
                        satrec.irez, satrec.atime,
                        satrec.d2201, satrec.d2211, satrec.d3210, satrec.d3222,
                        satrec.d4410, satrec.d4422, satrec.d5220, satrec.d5232,
                        satrec.d5421, satrec.d5433,
                        satrec.dedt, satrec.didt, satrec.dmdt, satrec.dnodt, satrec.domdt,
                        satrec.del1, satrec.del2, satrec.del3,
                        satrec.xfact, satrec.xlamo, satrec.xli, satrec.xni);

                // 写回 satrec 的 dsinit 输出字段
                satrec.irez = di.irez;
                satrec.atime = di.atime;
                satrec.d2201 = di.d2201; satrec.d2211 = di.d2211;
                satrec.d3210 = di.d3210; satrec.d3222 = di.d3222;
                satrec.d4410 = di.d4410; satrec.d4422 = di.d4422;
                satrec.d5220 = di.d5220; satrec.d5232 = di.d5232;
                satrec.d5421 = di.d5421; satrec.d5433 = di.d5433;
                satrec.dedt = di.dedt; satrec.didt = di.didt; satrec.dmdt = di.dmdt;
                satrec.dnodt = di.dnodt; satrec.domdt = di.domdt;
                satrec.del1 = di.del1; satrec.del2 = di.del2; satrec.del3 = di.del3;
                satrec.xfact = di.xfact; satrec.xlamo = di.xlamo;
                satrec.xli = di.xli; satrec.xni = di.xni;
                // 注意：em/argpm/inclm/mm/nm/nodem 在 sgp4init 中是局部变量，没有写回 satrec
                // （这些是 singly averaged mean elements，仅在 sgp4 中重新计算）
            }

            //----------- set variables if not deep space -----------
            if (satrec.isimp != 1) {
                double cc1sq = satrec.cc1 * satrec.cc1;
                satrec.d2 = 4.0 * ao * tsi * cc1sq;
                double temp = satrec.d2 * tsi * satrec.cc1 / 3.0;
                satrec.d3 = (17.0 * ao + sfour) * temp;
                satrec.d4 = 0.5 * temp * ao * tsi * (221.0 * ao + 31.0 * sfour) * satrec.cc1;
                satrec.t3cof = satrec.d2 + 2.0 * cc1sq;
                satrec.t4cof = 0.25 * (3.0 * satrec.d3 + satrec.cc1
                        * (12.0 * satrec.d2 + 10.0 * cc1sq));
                satrec.t5cof = 0.2 * (3.0 * satrec.d4
                        + 12.0 * satrec.cc1 * satrec.d3
                        + 6.0 * satrec.d2 * satrec.d2
                        + 15.0 * cc1sq * (2.0 * satrec.d2 + cc1sq));
            }
        }

        // finally propogate to zero epoch to initialize all others.
        sgp4(satrec, 0.0, whichconst);

        satrec.init = 'n';

        return true;
    }

    // ===================== sgp4 =====================

    /**
     * SGP4/SDP4 传播。逐行移植自 propagation.py 的 sgp4()。
     *
     * @param satrec 已初始化的 Satellite 对象
     * @param tsince 自历元起的时间 (分钟)
     * @param whichconst 重力常数集合
     * @return 长度 6 的数组 [x, y, z, vx, vy, vz]（单位 km / km/s, TEME）；
     *         出错时 satrec.error 被设置，返回数组中元素为 NaN
     */
    public static double[] sgp4(Satellite satrec, double tsince, Sgp4Constants.Gravity whichconst) {

        double mrt = 0.0;

        double temp4 = 1.5e-12;
        double x2o3 = 2.0 / 3.0;
        double vkmpersec = satrec.radiusearthkm * satrec.xke / 60.0;

        // --------------------- clear sgp4 error flag -----------------
        satrec.t = tsince;
        satrec.error = 0;
        satrec.error_message = null;

        // ------- update for secular gravity and atmospheric drag -----
        double xmdf = satrec.mo + satrec.mdot * satrec.t;
        double argpdf = satrec.argpo + satrec.argpdot * satrec.t;
        double nodedf = satrec.nodeo + satrec.nodedot * satrec.t;
        double argpm = argpdf;
        double mm = xmdf;
        double t2 = satrec.t * satrec.t;
        double nodem = nodedf + satrec.nodecf * t2;
        double tempa = 1.0 - satrec.cc1 * satrec.t;
        double tempe = satrec.bstar * satrec.cc4 * satrec.t;
        double templ = satrec.t2cof * t2;

        if (satrec.isimp != 1) {

            double delomg = satrec.omgcof * satrec.t;
            // sgp4fix use multiply for speed instead of pow
            double delmtemp = 1.0 + satrec.eta * Math.cos(xmdf);
            double delm = satrec.xmcof
                    * (delmtemp * delmtemp * delmtemp
                    - satrec.delmo);
            double temp = delomg + delm;
            mm = xmdf + temp;
            argpm = argpdf - temp;
            double t3 = t2 * satrec.t;
            double t4 = t3 * satrec.t;
            tempa = tempa - satrec.d2 * t2 - satrec.d3 * t3 - satrec.d4 * t4;
            tempe = tempe + satrec.bstar * satrec.cc5 * (Math.sin(mm) - satrec.sinmao);
            templ = templ + satrec.t3cof * t3 + t4 * (satrec.t4cof + satrec.t * satrec.t5cof);
        }

        double nm = satrec.no_unkozai;
        double em = satrec.ecco;
        double inclm = satrec.inclo;
        if (satrec.method == 'd') {

            double tc = satrec.t;
            DspaceResult ds = _dspace(
                    satrec.irez,
                    satrec.d2201, satrec.d2211, satrec.d3210, satrec.d3222,
                    satrec.d4410, satrec.d4422, satrec.d5220, satrec.d5232,
                    satrec.d5421, satrec.d5433,
                    satrec.dedt, satrec.del1, satrec.del2, satrec.del3,
                    satrec.didt, satrec.dmdt, satrec.dnodt, satrec.domdt,
                    satrec.argpo, satrec.argpdot,
                    satrec.t, tc, satrec.gsto,
                    satrec.xfact, satrec.xlamo, satrec.no_unkozai,
                    satrec.atime, em, argpm, inclm, satrec.xli, mm, satrec.xni,
                    nodem, nm);

            satrec.atime = ds.atime;
            satrec.xli = ds.xli;
            satrec.xni = ds.xni;
            em = ds.em;
            argpm = ds.argpm;
            inclm = ds.inclm;
            mm = ds.mm;
            nodem = ds.nodem;
            nm = ds.nm;
            // dndt 是局部，不写回 satrec
        }

        if (nm <= 0.0) {
            satrec.error_message = String.format("mean motion %f is less than zero", nm);
            satrec.error = 2;
            return new double[]{Double.NaN, Double.NaN, Double.NaN,
                    Double.NaN, Double.NaN, Double.NaN};
        }

        double am = Math.pow((satrec.xke / nm), x2o3) * tempa * tempa;
        nm = satrec.xke / Math.pow(am, 1.5);
        em = em - tempe;

        // fix tolerance for error recognition
        if (em >= 1.0 || em < -0.001) {
            satrec.error_message = String.format(
                    "mean eccentricity %f not within range 0.0 <= e < 1.0", em);
            satrec.error = 1;
            return new double[]{Double.NaN, Double.NaN, Double.NaN,
                    Double.NaN, Double.NaN, Double.NaN};
        }

        // sgp4fix fix tolerance to avoid a divide by zero
        if (em < 1.0e-6) {
            em = 1.0e-6;
        }
        mm = mm + satrec.no_unkozai * templ;
        double xlm = mm + argpm + nodem;
        double emsq = em * em;
        double temp = 1.0 - emsq;

        nodem = (nodem >= 0.0) ? mod(nodem, TWOPI) : -mod(-nodem, TWOPI);
        argpm = mod(argpm, TWOPI);
        xlm = mod(xlm, TWOPI);
        mm = mod(xlm - argpm - nodem, TWOPI);

        // sgp4fix recover singly averaged mean elements
        satrec.am = am;
        satrec.em = em;
        satrec.im = inclm;
        satrec.Om = nodem;
        satrec.om = argpm;
        satrec.mm = mm;
        satrec.nm = nm;

        // ----------------- compute extra mean quantities -------------
        double sinim = Math.sin(inclm);
        double cosim = Math.cos(inclm);

        // -------------------- add lunar-solar periodics --------------
        double ep = em;
        double xincp = inclm;
        double argpp = argpm;
        double nodep = nodem;
        double mp = mm;
        double sinip = sinim;
        double cosip = cosim;
        if (satrec.method == 'd') {

            DpperResult dp = _dpper(satrec, satrec.inclo, 'n',
                    ep, xincp, nodep, argpp, mp, satrec.operationmode);
            ep = dp.ep;
            xincp = dp.inclp;
            nodep = dp.nodep;
            argpp = dp.argpp;
            mp = dp.mp;

            if (xincp < 0.0) {
                xincp = -xincp;
                nodep = nodep + Math.PI;
                argpp = argpp - Math.PI;
            }

            if (ep < 0.0 || ep > 1.0) {
                satrec.error_message = String.format(
                        "perturbed eccentricity %f not within range 0.0 <= e <= 1.0", ep);
                satrec.error = 3;
                return new double[]{Double.NaN, Double.NaN, Double.NaN,
                        Double.NaN, Double.NaN, Double.NaN};
            }
        }

        // -------------------- long period periodics ------------------
        if (satrec.method == 'd') {
            sinip = Math.sin(xincp);
            cosip = Math.cos(xincp);
            satrec.aycof = -0.5 * satrec.j3oj2 * sinip;
            // sgp4fix for divide by zero for xincp = 180 deg
            if (Math.abs(cosip + 1.0) > 1.5e-12) {
                satrec.xlcof = -0.25 * satrec.j3oj2 * sinip
                        * (3.0 + 5.0 * cosip) / (1.0 + cosip);
            } else {
                satrec.xlcof = -0.25 * satrec.j3oj2 * sinip
                        * (3.0 + 5.0 * cosip) / temp4;
            }
        }

        double axnl = ep * Math.cos(argpp);
        temp = 1.0 / (am * (1.0 - ep * ep));
        double aynl = ep * Math.sin(argpp) + temp * satrec.aycof;
        double xl = mp + argpp + nodep + temp * satrec.xlcof * axnl;

        // --------------------- solve kepler's equation ---------------
        double u = mod(xl - nodep, TWOPI);
        double eo1 = u;
        double tem5 = 9999.9;
        int ktr = 1;
        // sgp4fix for kepler iteration
        // 注意：Python 无块作用域，sineo1/coseo1 在 while 循环外仍可见；
        // Java 需在循环外声明这两个变量，保持与 Python 一致的语义。
        double sineo1 = Math.sin(eo1);
        double coseo1 = Math.cos(eo1);
        while (Math.abs(tem5) >= 1.0e-12 && ktr <= 10) {
            sineo1 = Math.sin(eo1);
            coseo1 = Math.cos(eo1);
            tem5 = 1.0 - coseo1 * axnl - sineo1 * aynl;
            tem5 = (u - aynl * coseo1 + axnl * sineo1 - eo1) / tem5;
            if (Math.abs(tem5) >= 0.95) {
                tem5 = (tem5 > 0.0) ? 0.95 : -0.95;
            }
            eo1 = eo1 + tem5;
            ktr = ktr + 1;
        }

        // ------------- short period preliminary quantities -----------
        double ecose = axnl * coseo1 + aynl * sineo1;
        double esine = axnl * sineo1 - aynl * coseo1;
        double el2 = axnl * axnl + aynl * aynl;
        double pl = am * (1.0 - el2);
        double[] r = new double[3];
        double[] v = new double[3];
        if (pl < 0.0) {
            satrec.error_message = String.format("semilatus rectum %f is less than zero", pl);
            satrec.error = 4;
            return new double[]{Double.NaN, Double.NaN, Double.NaN,
                    Double.NaN, Double.NaN, Double.NaN};
        } else {

            double rl = am * (1.0 - ecose);
            double rdotl = Math.sqrt(am) * esine / rl;
            double rvdotl = Math.sqrt(pl) / rl;
            double betal = Math.sqrt(1.0 - el2);
            temp = esine / (1.0 + betal);
            double sinu = am / rl * (sineo1 - aynl - axnl * temp);
            double cosu = am / rl * (coseo1 - axnl + aynl * temp);
            double su = Math.atan2(sinu, cosu);
            double sin2u = (cosu + cosu) * sinu;
            double cos2u = 1.0 - 2.0 * sinu * sinu;
            temp = 1.0 / pl;
            double temp1 = 0.5 * satrec.j2 * temp;
            double temp2 = temp1 * temp;

            // -------------- update for short period periodics ------------
            if (satrec.method == 'd') {
                double cosisq = cosip * cosip;
                satrec.con41 = 3.0 * cosisq - 1.0;
                satrec.x1mth2 = 1.0 - cosisq;
                satrec.x7thm1 = 7.0 * cosisq - 1.0;
            }
            mrt = rl * (1.0 - 1.5 * temp2 * betal * satrec.con41)
                    + 0.5 * temp1 * satrec.x1mth2 * cos2u;
            su = su - 0.25 * temp2 * satrec.x7thm1 * sin2u;
            double xnode = nodep + 1.5 * temp2 * cosip * sin2u;
            double xinc = xincp + 1.5 * temp2 * cosip * sinip * cos2u;
            double mvt = rdotl - nm * temp1 * satrec.x1mth2 * sin2u / satrec.xke;
            double rvdot = rvdotl + nm * temp1 * (satrec.x1mth2 * cos2u
                    + 1.5 * satrec.con41) / satrec.xke;

            // --------------------- orientation vectors -------------------
            double sinsu = Math.sin(su);
            double cossu = Math.cos(su);
            double snod = Math.sin(xnode);
            double cnod = Math.cos(xnode);
            double sini = Math.sin(xinc);
            double cosi = Math.cos(xinc);
            double xmx = -snod * cosi;
            double xmy = cnod * cosi;
            double ux = xmx * sinsu + cnod * cossu;
            double uy = xmy * sinsu + snod * cossu;
            double uz = sini * sinsu;
            double vx = xmx * cossu - cnod * sinsu;
            double vy = xmy * cossu - snod * sinsu;
            double vz = sini * cossu;

            // --------- position and velocity (in km and km/sec) ----------
            double _mr = mrt * satrec.radiusearthkm;
            r[0] = _mr * ux;
            r[1] = _mr * uy;
            r[2] = _mr * uz;
            v[0] = (mvt * ux + rvdot * vx) * vkmpersec;
            v[1] = (mvt * uy + rvdot * vy) * vkmpersec;
            v[2] = (mvt * uz + rvdot * vz) * vkmpersec;
        }

        // sgp4fix for decaying satellites
        if (mrt < 1.0) {
            satrec.error_message = String.format(
                    "mrt %f is less than 1.0 indicating the satellite has decayed", mrt);
            satrec.error = 6;
        }

        return new double[]{r[0], r[1], r[2], v[0], v[1], v[2]};
    }
}
