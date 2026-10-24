package org.rtklib.java.adaptive;

import java.util.LinkedHashMap;
import java.util.Map;

public class PresetManager {
    private static final Map<String, Object> SPP_OPEN = Map.of(
            "thresslip", 0.05, "maxinno0", 30.0, "maxinno1", 30.0,
            "elmin", Math.toRadians(15), "enableSppEkf", false, "enableSppRobust", false,
            "sppInnovOutlierThresh", 5.0
    );
    private static final Map<String, Object> SPP_CANYON = Map.of(
            "thresslip", 0.10, "maxinno0", 50.0, "maxinno1", 50.0,
            "elmin", Math.toRadians(20), "enableSppEkf", true, "enableSppRobust", true,
            "sppInnovOutlierThresh", 7.0
    );
    private static final Map<String, Object> RTK_OPEN = Map.of(
            "thresslip", 0.05, "maxinno0", 30.0, "maxinno1", 30.0,
            "elmin", Math.toRadians(15), "enableIggiii", false, "enableSnrMedian", false,
            "enableSegmentSigmaFilter", false
    );
    private static final Map<String, Object> RTK_CANYON = Map.of(
            "thresslip", 0.10, "maxinno0", 50.0, "maxinno1", 50.0,
            "elmin", Math.toRadians(20), "enableIggiii", true, "enableSnrMedian", true,
            "enableSegmentSigmaFilter", true
    );
    private static final Map<String, Object> PPP_OPEN = Map.of(
            "thresslip", 0.05, "maxinno0", 5.0, "maxinno1", 30.0,
            "elmin", Math.toRadians(15), "enableIggiii", false, "enableSnrMedian", false
    );
    private static final Map<String, Object> PPP_CANYON = Map.of(
            "thresslip", 0.10, "maxinno0", 10.0, "maxinno1", 50.0,
            "elmin", Math.toRadians(20), "enableIggiii", true, "enableSnrMedian", true
    );
    private static final Map<String, Object> PPPAR_OPEN = Map.of(
            "thresslip", 0.05, "maxinno0", 5.0, "maxinno1", 30.0,
            "elmin", Math.toRadians(15), "enableIggiii", false, "enableSnrMedian", false
    );
    private static final Map<String, Object> PPPAR_CANYON = Map.of(
            "thresslip", 0.05, "maxinno0", 7.0, "maxinno1", 50.0,
            "elmin", Math.toRadians(20), "enableIggiii", true, "enableSnrMedian", true
    );
    private static final Map<String, Object> PPPRTK_OPEN = Map.of(
            "thresslip", 0.05, "maxinno0", 5.0, "maxinno1", 30.0,
            "elmin", Math.toRadians(15), "enableIggiii", false, "enableSnrMedian", false
    );
    private static final Map<String, Object> PPPRTK_CANYON = Map.of(
            "thresslip", 0.10, "maxinno0", 7.0, "maxinno1", 50.0,
            "elmin", Math.toRadians(20), "enableIggiii", true, "enableSnrMedian", true
    );

    public static Map<String, Object> getPreset(AdaptiveMode mode, GnssScene scene) {
        boolean isOpen = scene == GnssScene.OPEN_SKY;
        return switch (mode) {
            case SPP -> isOpen ? SPP_OPEN : SPP_CANYON;
            case RTK -> isOpen ? RTK_OPEN : RTK_CANYON;
            case PPP -> isOpen ? PPP_OPEN : PPP_CANYON;
            case PPP_AR -> isOpen ? PPPAR_OPEN : PPPAR_CANYON;
            case PPP_RTK -> isOpen ? PPPRTK_OPEN : PPPRTK_CANYON;
        };
    }

    public static int defaultWindow(AdaptiveMode mode) {
        return switch (mode) {
            case SPP -> 10;
            case RTK -> 30;
            case PPP -> 60;
            case PPP_AR -> 60;
            case PPP_RTK -> 30;
        };
    }

    public static int defaultCooldown(AdaptiveMode mode) {
        return switch (mode) {
            case SPP -> 10;
            case RTK -> 90;
            case PPP -> 300;
            case PPP_AR -> 300;
            case PPP_RTK -> 90;
        };
    }

    public static void applyPreset(AdaptiveMode mode, GnssScene scene, AdaptiveOverride override,
                                   java.util.Set<String> managedParams, java.util.Set<String> lockedParams) {
        Map<String, Object> preset = getPreset(mode, scene);
        for (Map.Entry<String, Object> e : preset.entrySet()) {
            String key = e.getKey();
            if (lockedParams != null && lockedParams.contains(key)) continue;
            if (managedParams != null && !managedParams.contains(key)) continue;
            override.set(key, e.getValue());
        }
    }
}