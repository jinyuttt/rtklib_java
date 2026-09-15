package org.rtklib.java.rtkpos;

import org.rtklib.java.data.*;

public final class RtkPos {
    private RtkPos() {
    }

    public static int rtkpos(Rtk rtk, Obsd[] obs, int n, Nav nav) {
        return RtkCore.rtkpos(rtk, obs, n, nav);
    }
}