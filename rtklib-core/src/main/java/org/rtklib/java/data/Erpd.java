package org.rtklib.java.data;

import java.io.Serializable;

public class Erpd implements Serializable {
    private static final long serialVersionUID = 1L;
    public double mjd;
    public double xp;
    public double yp;
    public double xpr;
    public double ypr;
    public double ut1_utc;
    public double lod;
}