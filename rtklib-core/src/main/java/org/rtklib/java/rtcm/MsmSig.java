package org.rtklib.java.rtcm;

/**
 * MSM signal definitions for each GNSS system.
 * Aligned with RTKLIB rtklib-2.5.0 rtcm3.c msm_sig_* arrays.
 * Signal identifiers use RINEX obs code format (e.g. "1C", "2I")
 * as required by obs2code() conversion.
 *
 * Reference: RTCM 10403.3 tables:
 *   GPS:    table 3.5-91
 *   GLO:    table 3.5-96
 *   GAL:    table 3.5-99
 *   SBAS:   table 3.5-102
 *   QZS:    table 3.5-105
 *   BDS:    table 3.5-108
 *   IRNSS:  table 3.5-108.3
 */
public class MsmSig {
    public static final String[] MSM_SIG_GPS = {
        "",   "1C", "1P", "1W", "",   "",   "",   "2C", "2P", "2W", "",   "",    //  1-12
        "",   "",   "2S", "2L", "2X", "",   "",   "",   "",   "5I", "5Q", "5X",  // 13-24
        "",   "",   "",   "",   "",   "1S", "1L", "1X"                           // 25-32
    };

    public static final String[] MSM_SIG_GLO = {
        "",   "1C", "1P", "",   "",   "",   "",   "2C", "2P", "",   "",   "",    //  1-12
        "",   "3I", "3Q", "3X", "",   "4A", "4B", "4X", "",   "6A", "6B", "6X",  // 13-24
        "",   "",   "",   "",   "",   "",   "",   ""                             // 25-32
    };

    public static final String[] MSM_SIG_GAL = {
        "",   "1C", "1A", "1B", "1X", "1Z", "",   "6C", "6A", "6B", "6X", "6Z",  //  1-12
        "",   "7I", "7Q", "7X", "",   "8I", "8Q", "8X", "",   "5I", "5Q", "5X",  // 13-24
        "",   "",   "",   "",   "",   "",   "",   ""                               // 25-32
    };

    public static final String[] MSM_SIG_QZS = {
        "",   "1C", "",   "",   "1E", "1Z", "1B", "",   "6S", "6L", "6X", "6E",  //  1-12
        "6Z", "",   "2S", "2L", "2X", "",   "",   "",   "",   "5I", "5Q", "5X",  // 13-24
        "5D", "5P", "5Z", "",   "",   "1S", "1L", "1X"                            // 25-32
    };

    public static final String[] MSM_SIG_SBS = {
        "",   "1C", "",   "",   "",   "",   "",   "",   "",   "",   "",   "",      //  1-12
        "",   "",   "",   "",   "",   "",   "",   "",   "",   "5I", "5Q", "5X",   // 13-24
        "",   "",   "",   "",   "",   "",   "",   ""                               // 25-32
    };

    public static final String[] MSM_SIG_CMP = {
        "",   "2I", "2Q", "2X", "1S", "1L", "1Z", "6I", "6Q", "6X", "6D", "6P",  //  1-12
        "6Z", "7I", "7Q", "7X", "",   "8D", "8P", "8X", "",   "5D", "5P", "5X",  // 13-24
        "7D", "7P", "7Z", "",   "",   "1D", "1P", "1X"                            // 25-32
    };

    public static final String[] MSM_SIG_IRN = {
        "",   "1D", "1P", "1X", "",   "",   "",   "9A", "9B", "9C", "9X", "",    //  1-12
        "",   "",   "",   "",   "",   "",   "",   "",   "",   "5A", "5B", "5C",   // 13-24
        "5X", "",   "",   "",   "",   "",   "",   ""                               // 25-32
    };
}