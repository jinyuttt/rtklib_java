package org.rtklib.java.otl;

public final class Fes2004Record {

    public final double doodson;
    public final String darwinName;
    public final int n;
    public final int m;
    public final double cPlus;
    public final double epsPlus;
    public final double cMinus;
    public final double epsMinus;

    public Fes2004Record(double doodson, String darwinName, int n, int m,
                         double cPlus, double epsPlus, double cMinus, double epsMinus) {
        this.doodson = doodson;
        this.darwinName = darwinName;
        this.n = n;
        this.m = m;
        this.cPlus = cPlus;
        this.epsPlus = epsPlus;
        this.cMinus = cMinus;
        this.epsMinus = epsMinus;
    }

    public static boolean isBlqDoodson(double doodson) {
        int d = (int) Math.round(doodson * 1000);
        return d == 135655 || d == 145555 || d == 163555 || d == 165555 ||
               d == 245655 || d == 255555 || d == 273555 || d == 275555 ||
               d == 57555  || d == 65455  || d == 75555;
    }
}
