package org.rtklib.java.adaptive;

import org.rtklib.java.config.RtkConfig;
import org.rtklib.java.data.PrcOpt;

import java.util.LinkedHashMap;
import java.util.Map;

public class AdaptiveOverride {
    private final Map<String, Object> overrides = new LinkedHashMap<>();
    private final Map<String, Object> originals = new LinkedHashMap<>();
    private boolean active = false;

    public void snapshot(RtkConfig cfg, PrcOpt opt) {
        originals.clear();
        overrides.clear();
        originals.put("thresslip", opt.thresslip);
        originals.put("maxinno0", opt.maxinno[0]);
        originals.put("maxinno1", opt.maxinno[1]);
        originals.put("elmin", opt.elmin);
        originals.put("enableIggiii", cfg.rtk.enableIggiii);
        originals.put("enableSnrMedian", cfg.rtk.enableSnrMedian);
        originals.put("enableSegmentSigmaFilter", cfg.rtk.enableSegmentSigmaFilter);
        originals.put("enableAdaptiveQ", cfg.rtk.enableAdaptiveQ);
        originals.put("spp.enableEkf", cfg.spp.enableEkf);
        originals.put("spp.enableRobust", cfg.spp.enableRobust);
        originals.put("spp.enableInnovChisq", cfg.spp.enableInnovChisq);
        originals.put("spp.innovOutlierThresh", cfg.spp.innovOutlierThresh);
    }

    public void set(String key, Object value) {
        if (!active) return;
        overrides.put(key, value);
    }

    public boolean getBoolean(String key, boolean userValue) {
        if (!active || !overrides.containsKey(key)) return userValue;
        return (boolean) overrides.get(key);
    }

    public double getDouble(String key, double userValue) {
        if (!active || !overrides.containsKey(key)) return userValue;
        return (double) overrides.get(key);
    }

    public void applyTo(RtkConfig cfg, PrcOpt opt) {
        if (!active) return;
        if (overrides.containsKey("thresslip")) opt.thresslip = (double) overrides.get("thresslip");
        if (overrides.containsKey("maxinno0")) opt.maxinno[0] = (double) overrides.get("maxinno0");
        if (overrides.containsKey("maxinno1")) opt.maxinno[1] = (double) overrides.get("maxinno1");
        if (overrides.containsKey("elmin")) opt.elmin = (double) overrides.get("elmin");
        if (overrides.containsKey("enableIggiii")) cfg.rtk.enableIggiii = (boolean) overrides.get("enableIggiii");
        if (overrides.containsKey("enableSnrMedian")) cfg.rtk.enableSnrMedian = (boolean) overrides.get("enableSnrMedian");
        if (overrides.containsKey("enableSegmentSigmaFilter")) cfg.rtk.enableSegmentSigmaFilter = (boolean) overrides.get("enableSegmentSigmaFilter");
        if (overrides.containsKey("enableAdaptiveQ")) cfg.rtk.enableAdaptiveQ = (boolean) overrides.get("enableAdaptiveQ");
        if (overrides.containsKey("spp.enableEkf")) cfg.spp.enableEkf = (boolean) overrides.get("spp.enableEkf");
        if (overrides.containsKey("spp.enableRobust")) cfg.spp.enableRobust = (boolean) overrides.get("spp.enableRobust");
        if (overrides.containsKey("spp.enableInnovChisq")) cfg.spp.enableInnovChisq = (boolean) overrides.get("spp.enableInnovChisq");
        if (overrides.containsKey("spp.innovOutlierThresh")) cfg.spp.innovOutlierThresh = (double) overrides.get("spp.innovOutlierThresh");
    }

    public void restoreAll(RtkConfig cfg, PrcOpt opt) {
        if (originals.containsKey("thresslip")) opt.thresslip = (double) originals.get("thresslip");
        if (originals.containsKey("maxinno0")) opt.maxinno[0] = (double) originals.get("maxinno0");
        if (originals.containsKey("maxinno1")) opt.maxinno[1] = (double) originals.get("maxinno1");
        if (originals.containsKey("elmin")) opt.elmin = (double) originals.get("elmin");
        if (originals.containsKey("enableIggiii")) cfg.rtk.enableIggiii = (boolean) originals.get("enableIggiii");
        if (originals.containsKey("enableSnrMedian")) cfg.rtk.enableSnrMedian = (boolean) originals.get("enableSnrMedian");
        if (originals.containsKey("enableSegmentSigmaFilter")) cfg.rtk.enableSegmentSigmaFilter = (boolean) originals.get("enableSegmentSigmaFilter");
        if (originals.containsKey("enableAdaptiveQ")) cfg.rtk.enableAdaptiveQ = (boolean) originals.get("enableAdaptiveQ");
        if (originals.containsKey("spp.enableEkf")) cfg.spp.enableEkf = (boolean) originals.get("spp.enableEkf");
        if (originals.containsKey("spp.enableRobust")) cfg.spp.enableRobust = (boolean) originals.get("spp.enableRobust");
        if (originals.containsKey("spp.enableInnovChisq")) cfg.spp.enableInnovChisq = (boolean) originals.get("spp.enableInnovChisq");
        if (originals.containsKey("spp.innovOutlierThresh")) cfg.spp.innovOutlierThresh = (double) originals.get("spp.innovOutlierThresh");
        overrides.clear();
    }

    public void activate() { active = true; }
    public void deactivate() { active = false; overrides.clear(); }
    public boolean isActive() { return active; }

    public String diffString() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Object> e : overrides.entrySet()) {
            Object orig = originals.get(e.getKey());
            if (orig != null && !orig.equals(e.getValue())) {
                if (sb.length() > 0) sb.append(";");
                sb.append(e.getKey()).append(":").append(formatVal(orig)).append("→").append(formatVal(e.getValue()));
            }
        }
        return sb.toString();
    }

    private String formatVal(Object v) {
        if (v instanceof Double) {
            double d = (double) v;
            if (d == Math.floor(d) && !Double.isInfinite(d)) return String.valueOf((int) d);
            return String.format("%.4f", d);
        }
        if (v instanceof Boolean) return ((boolean) v) ? "true" : "false";
        return String.valueOf(v);
    }
}