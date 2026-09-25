package org.rtklib.java.trace;

import org.rtklib.java.common.SatUtils;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.coord.CoordTransform;
import org.rtklib.java.data.GTime;
import org.rtklib.java.time.TimeSystem;

import java.util.Locale;
import java.util.Map;

public final class Trace {
    private Trace() {}

    public static void emit(String topic, String action,
                            TraceConfig cfg, TraceCallback cb,
                            int epoch, GTime time, Object... kv) {
        if (cfg == null || !cfg.enabled) return;
        if (!cfg.topics.contains(topic)) return;
        if (cfg.actions != null && !cfg.actions.contains(action)) return;
        if (cfg.maxEpoch > 0 && epoch >= cfg.maxEpoch) return;
        if (cfg.samplerate > 1 && epoch % cfg.samplerate != 0) return;
        if (cfg.maxOutputLines > 0 && cfg.stats.outputLineCount >= cfg.maxOutputLines) return;
        if (!passSatFilter(kv, cfg)) return;

        StringBuilder sb = new StringBuilder(256);
        sb.append("TRACE|topic=").append(topic)
          .append("|action=").append(action)
          .append("|gpst=").append(gpstStr(time))
          .append("|epoch=").append(epoch);

        for (int i = 0; i + 1 < kv.length; i += 2) {
            sb.append('|').append(kv[i]).append('=').append(fmt(kv[i + 1]));
        }

        appendDiagnosis(sb, topic, action, kv, cfg);

        try { cb.onLine(sb.toString()); } catch (Exception ignored) {}
        cfg.stats.outputLineCount++;

        accumulate(cfg, topic, action, kv, epoch);
    }

    public static void summary(TraceConfig cfg, TraceCallback cb) {
        if (cfg == null || !cfg.enabled) return;
        TraceStats s = cfg.stats;

        StringBuilder sb = new StringBuilder(256);
        sb.append("TRACE|topic=RESULT|action=SUMMARY")
          .append("|total=").append(s.totalEpochs)
          .append("|success=").append(s.successEpochs)
          .append("|fail=").append(s.failEpochs)
          .append("|fail_reasons=").append(joinReasons(s.failReasons))
          .append("|fix=").append(s.fixCount)
          .append("|float=").append(s.floatCount)
          .append("|conv_epoch=").append(s.convEpoch)
          .append("|max_ar_shift=").append(fmt(s.maxArShift))
          .append("|output_lines=").append(s.outputLineCount);

        try { cb.onLine(sb.toString()); } catch (Exception ignored) {}
    }

    private static String gpstStr(GTime time) {
        if (time == null || time.time == 0) return "0.000";
        return String.format(Locale.US, "%.3f", TimeSystem.time2gpst(time, null));
    }

    private static String fmt(Object v) {
        if (v == null) return "";
        if (v instanceof Double || v instanceof Float) {
            return String.format(Locale.US, "%.6f", ((Number) v).doubleValue());
        }
        return String.valueOf(v);
    }

    private static boolean passSatFilter(Object[] kv, TraceConfig cfg) {
        if (cfg.targetSats == null || cfg.targetSats.length == 0) return true;
        for (int i = 0; i + 1 < kv.length; i += 2) {
            if ("sat".equals(kv[i])) {
                int satNo = satNo(kv[i + 1]);
                if (satNo > 0) {
                    for (int ts : cfg.targetSats) {
                        if (ts == satNo) return true;
                    }
                    return false;
                }
            }
        }
        return true;
    }

    private static int satNo(Object val) {
        if (val instanceof Integer) return (Integer) val;
        if (val instanceof String) return SatUtils.satid2no((String) val);
        return 0;
    }

    private static void appendDiagnosis(StringBuilder sb, String topic,
                                        String action, Object[] kv,
                                        TraceConfig cfg) {
        if ("POSITION".equals(topic) && "UPDATE".equals(action) && cfg.refEcef != null) {
            double[] ecef = extractEcef(kv);
            if (ecef != null) {
                double[] neu = ecefToNeu(ecef, cfg.refEcef);
                double d3 = Math.sqrt(neu[0] * neu[0] + neu[1] * neu[1] + neu[2] * neu[2]);
                String conv = d3 < cfg.convergenceThresh ? "YES" : "NO";
                sb.append("|d_dN=").append(fmt(neu[0]))
                  .append("|d_dE=").append(fmt(neu[1]))
                  .append("|d_dU=").append(fmt(neu[2]))
                  .append("|d_d3D=").append(fmt(d3))
                  .append("|d_conv=").append(conv);
            }
        }

        if ("AR".equals(topic) && "FIX".equals(action)) {
            double shift3 = getDouble(kv, "shift_3D");
            if (shift3 > cfg.arShiftWarnThresh) {
                sb.append("|d_WARN=LARGE_SHIFT|d_thresh=").append(fmt(cfg.arShiftWarnThresh));
                cfg.stats.maxArShift = Math.max(cfg.stats.maxArShift, shift3);
            }
        }
    }

    private static void accumulate(TraceConfig cfg, String topic,
                                   String action, Object[] kv, int epoch) {
        TraceStats s = cfg.stats;

        if ("RESULT".equals(topic)) {
            if ("UPDATE".equals(action)) s.successEpochs++;
            if ("FAIL".equals(action)) {
                s.failEpochs++;
                String reason = getString(kv, "reason");
                if (reason != null && !reason.isEmpty()) {
                    s.failReasons.merge(reason, 1, Integer::sum);
                }
            }
        }

        if ("AR".equals(topic)) {
            if ("FIX".equals(action)) s.fixCount++;
            if ("WARN".equals(action)) s.floatCount++;
        }

        if ("SATELLITE".equals(topic) && "START".equals(action)) {
            s.totalEpochs++;
        }

        if ("POSITION".equals(topic) && "UPDATE".equals(action) && cfg.refEcef != null) {
            double[] ecef = extractEcef(kv);
            if (ecef != null) {
                double[] neu = ecefToNeu(ecef, cfg.refEcef);
                double d3 = Math.sqrt(neu[0] * neu[0] + neu[1] * neu[1] + neu[2] * neu[2]);
                if (d3 < cfg.convergenceThresh) {
                    s.consecutiveConvEpochs++;
                    if (s.convEpoch == 0 && s.consecutiveConvEpochs >= cfg.convergenceEpochs) {
                        s.convEpoch = epoch;
                    }
                } else {
                    s.consecutiveConvEpochs = 0;
                }
            }
        }
    }

    private static double[] extractEcef(Object[] kv) {
        Double x = getDoubleObj(kv, "x");
        Double y = getDoubleObj(kv, "y");
        Double z = getDoubleObj(kv, "z");
        if (x != null && y != null && z != null) {
            return new double[]{x, y, z};
        }
        Double lat = getDoubleObj(kv, "lat");
        Double lon = getDoubleObj(kv, "lon");
        Double h = getDoubleObj(kv, "h");
        if (lat != null && lon != null && h != null) {
            double[] pos = {lat * Constants.D2R, lon * Constants.D2R, h};
            double[] ecef = new double[3];
            CoordTransform.pos2ecef(pos, ecef);
            return ecef;
        }
        return null;
    }

    private static double[] ecefToNeu(double[] ecef, double[] ref) {
        double dx = ecef[0] - ref[0];
        double dy = ecef[1] - ref[1];
        double dz = ecef[2] - ref[2];

        double[] refPos = new double[3];
        CoordTransform.ecef2pos(ref, refPos);
        double lat = refPos[0];
        double lon = refPos[1];

        double slat = Math.sin(lat), clat = Math.cos(lat);
        double slon = Math.sin(lon), clon = Math.cos(lon);

        double n = -slat * clon * dx - slat * slon * dy + clat * dz;
        double e = -slon * dx + clon * dy;
        double u = clat * clon * dx + clat * slon * dy + slat * dz;

        return new double[]{n, e, u};
    }

    private static double getDouble(Object[] kv, String key) {
        for (int i = 0; i + 1 < kv.length; i += 2) {
            if (key.equals(kv[i])) {
                Object v = kv[i + 1];
                if (v instanceof Number) return ((Number) v).doubleValue();
                if (v instanceof String) {
                    try { return Double.parseDouble((String) v); }
                    catch (NumberFormatException ignored) {}
                }
            }
        }
        return 0.0;
    }

    private static Double getDoubleObj(Object[] kv, String key) {
        for (int i = 0; i + 1 < kv.length; i += 2) {
            if (key.equals(kv[i])) {
                Object v = kv[i + 1];
                if (v instanceof Number) return ((Number) v).doubleValue();
                if (v instanceof String) {
                    try { return Double.parseDouble((String) v); }
                    catch (NumberFormatException ignored) {}
                }
            }
        }
        return null;
    }

    private static String getString(Object[] kv, String key) {
        for (int i = 0; i + 1 < kv.length; i += 2) {
            if (key.equals(kv[i]) && kv[i + 1] instanceof String) {
                return (String) kv[i + 1];
            }
        }
        return null;
    }

    private static String joinReasons(Map<String, Integer> reasons) {
        if (reasons.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : reasons.entrySet()) {
            if (sb.length() > 0) sb.append(',');
            sb.append(e.getKey()).append(':').append(e.getValue());
        }
        return sb.toString();
    }
}