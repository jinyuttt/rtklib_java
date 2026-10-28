package org.rtklib.java.networkvrs.netsol;

import org.rtklib.java.coord.CoordTransform;
import org.rtklib.java.networkvrs.sync.StationObservation;

import java.util.ArrayList;
import java.util.List;

/**
 * 基线网：由站点坐标构建站间基线，并提供几何（共线）检查。
 *
 * <p>站数不超过 {@link #MAX_TRIANGLE_STATIONS} 时穷举所有距离合格的站对（3 站即三角形三条边，
 * 有冗余，利于误差分解）；站数更多时只保留每个站的最短边（最小生成树），避免 O(n²) 爆炸。</p>
 */
public final class BaselineNetwork {

    private static final int MAX_TRIANGLE_STATIONS = 4;

    /**
     * 一条基线。
     *
     * @param stationA     站名 A
     * @param stationB     站名 B
     * @param lengthMeters 基线长度（ECEF 弦长，米）
     */
    public record Baseline(String stationA, String stationB, double lengthMeters) {
    }

    private final List<Baseline> baselines;
    private final boolean connected;
    private final double geometryRatio;
    private final int stationCount;

    private BaselineNetwork(List<Baseline> baselines, boolean connected,
                            double geometryRatio, int stationCount) {
        this.baselines = List.copyOf(baselines);
        this.connected = connected;
        this.geometryRatio = geometryRatio;
        this.stationCount = stationCount;
    }

    /**
     * @param stations         各基准站
     * @param maxBaselineMeters 允许的最大基线长度
     */
    public static BaselineNetwork build(List<StationObservation> stations, double maxBaselineMeters) {
        if (stations == null || stations.size() < 2) {
            throw new IllegalArgumentException("至少需要 2 个站构建基线网");
        }
        int n = stations.size();
        List<Baseline> candidates = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                double d = distance(stations.get(i).ecef(), stations.get(j).ecef());
                if (d > 0.0 && d <= maxBaselineMeters) {
                    candidates.add(new Baseline(stations.get(i).id(), stations.get(j).id(), d));
                }
            }
        }
        candidates.sort((a, b) -> Double.compare(a.lengthMeters(), b.lengthMeters()));

        List<Baseline> selected;
        if (n <= MAX_TRIANGLE_STATIONS) {
            selected = candidates;
        } else {
            selected = minimumSpanningTree(stations, candidates);
        }

        boolean connected = isConnected(stations, selected);
        double ratio = n < 3 ? 0.0 : minAreaRatio(stations);
        return new BaselineNetwork(selected, connected, ratio, n);
    }

    public List<Baseline> baselines() {
        return baselines;
    }

    /** @return 所有站是否被基线连通 */
    public boolean connected() {
        return connected;
    }

    /** @return 归一化三角形面积 2A/maxEdge²（0 表示共线；站数 <3 时为 0） */
    public double geometryRatio() {
        return geometryRatio;
    }

    public int stationCount() {
        return stationCount;
    }

    public boolean geometryDegenerate(double minAreaRatio) {
        return stationCount >= 3 && geometryRatio < minAreaRatio;
    }

    /**
     * @return 基线长度（ECEF 弦长，米）
     */
    public static double distance(double[] a, double[] b) {
        double dx = a[0] - b[0];
        double dy = a[1] - b[1];
        double dz = a[2] - b[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static List<Baseline> minimumSpanningTree(List<StationObservation> stations,
                                                      List<Baseline> sorted) {
        int n = stations.size();
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) {
            parent[i] = i;
        }
        List<Baseline> tree = new ArrayList<>();
        for (Baseline b : sorted) {
            int ia = indexOf(stations, b.stationA());
            int ib = indexOf(stations, b.stationB());
            int ra = find(parent, ia);
            int rb = find(parent, ib);
            if (ra != rb) {
                parent[ra] = rb;
                tree.add(b);
                if (tree.size() == n - 1) {
                    break;
                }
            }
        }
        return tree;
    }

    private static boolean isConnected(List<StationObservation> stations, List<Baseline> edges) {
        int n = stations.size();
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) {
            parent[i] = i;
        }
        for (Baseline b : edges) {
            int ra = find(parent, indexOf(stations, b.stationA()));
            int rb = find(parent, indexOf(stations, b.stationB()));
            parent[ra] = rb;
        }
        int root = find(parent, 0);
        for (int i = 1; i < n; i++) {
            if (find(parent, i) != root) {
                return false;
            }
        }
        return true;
    }

    private static int find(int[] parent, int i) {
        while (parent[i] != i) {
            parent[i] = parent[parent[i]];
            i = parent[i];
        }
        return i;
    }

    private static int indexOf(List<StationObservation> stations, String id) {
        for (int i = 0; i < stations.size(); i++) {
            if (stations.get(i).id().equals(id)) {
                return i;
            }
        }
        throw new IllegalArgumentException("station not found: " + id);
    }

    /**
     * 所有三点组合的归一化面积最小值。站数 = 3 时即该三角形的值。
     */
    private static double minAreaRatio(List<StationObservation> stations) {
        int n = stations.size();
        double min = Double.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                for (int k = j + 1; k < n; k++) {
                    double ratio = areaRatio(stations.get(i), stations.get(j), stations.get(k));
                    min = Math.min(min, ratio);
                }
            }
        }
        return min == Double.MAX_VALUE ? 0.0 : min;
    }

    /**
     * 水平面内三角形归一化面积：2A / maxEdge²。
     */
    static double areaRatio(StationObservation a, StationObservation b, StationObservation c) {
        double[] origin = a.pos();
        double[] originEcef = new double[3];
        CoordTransform.pos2ecef(origin, originEcef);
        double[] ea = enu(origin, originEcef, a.ecef());
        double[] eb = enu(origin, originEcef, b.ecef());
        double[] ec = enu(origin, originEcef, c.ecef());
        double area2 = Math.abs((eb[0] - ea[0]) * (ec[1] - ea[1])
                - (ec[0] - ea[0]) * (eb[1] - ea[1]));
        double maxEdge = Math.max(Math.hypot(eb[0] - ea[0], eb[1] - ea[1]),
                Math.max(Math.hypot(ec[0] - eb[0], ec[1] - eb[1]),
                        Math.hypot(ea[0] - ec[0], ea[1] - ec[1])));
        if (maxEdge <= 0.0) {
            return 0.0;
        }
        return area2 / (maxEdge * maxEdge);
    }

    private static double[] enu(double[] originPos, double[] originEcef, double[] ecef) {
        double[] delta = {ecef[0] - originEcef[0], ecef[1] - originEcef[1], ecef[2] - originEcef[2]};
        double[] enu = new double[3];
        CoordTransform.ecef2enu(originPos, delta, enu);
        return enu;
    }
}
