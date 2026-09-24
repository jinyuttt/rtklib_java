package org.rtklib.java.otl;

public final class BlqConsts {

    private BlqConsts() {}

    public static final int NUM_CONSTITUENTS = 11;

    public static final String[] CONSTITUENT_NAMES = {
            "M2", "S2", "N2", "K2", "K1", "O1", "P1", "Q1", "Mf", "Mm", "Ssa"
    };

    public static final double[] DOODSON_NUMBERS = {
            255.555, 273.555, 245.655, 275.555, 165.555,
            145.555, 163.555, 135.655, 75.555, 65.455, 57.555
    };

    public static final int MAX_DEG = 100;

    public static final double G = 6.67428e-11;
    public static final double RHO_W = 1.025e3;
    public static final double GE = 9.7803278;
    public static final double AE = 6378137.0;
    public static final double GM = 3.986004415e14;
}
