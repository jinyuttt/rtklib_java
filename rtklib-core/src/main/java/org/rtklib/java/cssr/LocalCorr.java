package org.rtklib.java.cssr;

import org.rtklib.java.data.GTime;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

public class LocalCorr implements Serializable {
    private static final long serialVersionUID = 1L;

    public int inet = -1;
    public int inetRef = -1;
    public int ng = 0;
    public int ofst = 0;
    public int flgTrop = 0;
    public int flgStec = 0;
    public int cstat = 0;

    public Map<Integer, Map<Integer, Double>> pbias = new HashMap<>();
    public Map<Integer, Map<Integer, Double>> cbias = new HashMap<>();
    public Map<Integer, Integer> iode = new HashMap<>();
    public Map<Integer, double[]> dorb = new HashMap<>();
    public Map<Integer, Double> dclk = new HashMap<>();
    public Map<Integer, Double> ura = new HashMap<>();

    public Map<Integer, double[]> ci = new HashMap<>();
    public Map<Integer, double[]> dstec = new HashMap<>();
    public Map<Integer, Double> stecQuality = new HashMap<>();
    public Map<Integer, Integer> stype = new HashMap<>();
    public Map<Integer, Integer> sSz = new HashMap<>();

    public double[] ct = new double[8];
    public double[] dth = null;
    public double[] dtw = null;
    public double[] maph = null;
    public double[] mapw = null;
    public Double tropQuality = null;

    public int[] satN = new int[0];
    public int nsatN = 0;
    public int netmask = 0;

    public Map<Integer, Map<Integer, GTime>> t0 = new HashMap<>();

    public GTime[] t0s = new GTime[16];

    public LocalCorr() {
        for (int i = 0; i < t0s.length; i++) {
            t0s[i] = new GTime();
        }
    }

    public void setT0(int sat, int ctype, GTime time) {
        t0.computeIfAbsent(sat, k -> new HashMap<>());
        t0.get(sat).put(ctype, new GTime(time));
    }

    public GTime getT0(int sat, int ctype) {
        if (t0.containsKey(sat) && t0.get(sat).containsKey(ctype)) {
            return t0.get(sat).get(ctype);
        }
        return null;
    }
}