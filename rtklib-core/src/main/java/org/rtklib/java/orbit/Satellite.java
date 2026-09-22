package org.rtklib.java.orbit;

public class Satellite {

    public int error = 0;
    public String error_message = null;

    public char operationmode = 'i';

    public String satnum_str = "";

    public double bstar = 0.0;
    public double ndot = 0.0;
    public double nddot = 0.0;
    public double ecco = 0.0;
    public double argpo = 0.0;
    public double inclo = 0.0;
    public double mo = 0.0;
    public double no_kozai = 0.0;
    public double nodeo = 0.0;

    public double no_unkozai = 0.0;

    public int isimp = 0;
    public char method = 'n';

    public double aycof = 0.0;
    public double con41 = 0.0;
    public double cc1 = 0.0;
    public double cc4 = 0.0;
    public double cc5 = 0.0;
    public double d2 = 0.0;
    public double d3 = 0.0;
    public double d4 = 0.0;
    public double delmo = 0.0;
    public double eta = 0.0;
    public double argpdot = 0.0;
    public double omgcof = 0.0;
    public double sinmao = 0.0;
    public double t = 0.0;
    public double t2cof = 0.0;
    public double t3cof = 0.0;
    public double t4cof = 0.0;
    public double t5cof = 0.0;
    public double x1mth2 = 0.0;
    public double x7thm1 = 0.0;
    public double mdot = 0.0;
    public double nodedot = 0.0;
    public double xlcof = 0.0;
    public double xmcof = 0.0;
    public double nodecf = 0.0;

    public int irez = 0;
    public double d2201 = 0.0;
    public double d2211 = 0.0;
    public double d3210 = 0.0;
    public double d3222 = 0.0;
    public double d4410 = 0.0;
    public double d4422 = 0.0;
    public double d5220 = 0.0;
    public double d5232 = 0.0;
    public double d5421 = 0.0;
    public double d5433 = 0.0;
    public double dedt = 0.0;
    public double del1 = 0.0;
    public double del2 = 0.0;
    public double del3 = 0.0;
    public double didt = 0.0;
    public double dmdt = 0.0;
    public double dnodt = 0.0;
    public double domdt = 0.0;
    public double e3 = 0.0;
    public double ee2 = 0.0;
    public double peo = 0.0;
    public double pgho = 0.0;
    public double pho = 0.0;
    public double pinco = 0.0;
    public double plo = 0.0;
    public double se2 = 0.0;
    public double se3 = 0.0;
    public double sgh2 = 0.0;
    public double sgh3 = 0.0;
    public double sgh4 = 0.0;
    public double sh2 = 0.0;
    public double sh3 = 0.0;
    public double si2 = 0.0;
    public double si3 = 0.0;
    public double sl2 = 0.0;
    public double sl3 = 0.0;
    public double sl4 = 0.0;
    public double gsto = 0.0;
    public double xfact = 0.0;
    public double xgh2 = 0.0;
    public double xgh3 = 0.0;
    public double xgh4 = 0.0;
    public double xh2 = 0.0;
    public double xh3 = 0.0;
    public double xi2 = 0.0;
    public double xi3 = 0.0;
    public double xl2 = 0.0;
    public double xl3 = 0.0;
    public double xl4 = 0.0;
    public double xlamo = 0.0;
    public double zmol = 0.0;
    public double zmos = 0.0;
    public double atime = 0.0;
    public double xli = 0.0;
    public double xni = 0.0;

    public double am = 0.0;
    public double em = 0.0;
    public double im = 0.0;
    public double Om = 0.0;
    public double om = 0.0;
    public double mm = 0.0;
    public double nm = 0.0;

    public double a = 0.0;
    public double alta = 0.0;
    public double altp = 0.0;

    public double tumin = OrbitConstants.TUMIN;
    public double mu = OrbitConstants.MU;
    public double radiusearthkm = OrbitConstants.EARTH_RADIUS_KM;
    public double xke = OrbitConstants.XKE;
    public double j2 = OrbitConstants.J2;
    public double j3 = OrbitConstants.J3;
    public double j4 = OrbitConstants.J4;
    public double j3oj2 = OrbitConstants.J3OJ2;

    public char init = 'y';
}