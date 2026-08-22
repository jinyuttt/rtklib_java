package org.rtklib.java.data;

import java.io.Serializable;

public class Erp implements Serializable {
    private static final long serialVersionUID = 1L;
    public int n;
    public int nmax;
    public Erpd[] data;

    public Erp() {
        this.n = 0;
        this.nmax = 0;
    }
}