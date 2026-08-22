package org.rtklib.java.data;

import java.io.Serializable;
import org.rtklib.java.constants.Constants;

public class SbsIon implements Serializable {
    private static final long serialVersionUID = 1L;
    public int iodi;
    public int nigp;
    public SbsIgp[] igp;

    public SbsIon() {
        this.iodi = 0;
        this.nigp = 0;
        this.igp = new SbsIgp[Constants.MAXNIGP];
        for (int i = 0; i < Constants.MAXNIGP; i++) {
            this.igp[i] = new SbsIgp();
        }
    }
}