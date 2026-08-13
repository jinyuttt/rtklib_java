package org.rtklib.java.data;

import org.rtklib.java.constants.Constants;
import org.rtklib.java.coord.CoordTransform;
import org.rtklib.java.time.TimeSystem;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 定位结果输出数据类，Java 友好的封装。
 *
 * <p>从内部 {@link Sol}（C 对齐结构，始终 type=0 ECEF）转换而来。
 * 根据 {@link PrcOpt#posMask} 配置，在构造时完成坐标转换，
 * 输出时直接使用，无需再计算。</p>
 *
 * <h3>核心设计</h3>
 * <ul>
 *   <li>位置、速度、精度分别用 {@link Position}、{@link Velocity}、{@link Accuracy} 封装，
 *       每个类只有 3 个分量 + type 标识</li>
 *   <li>通过 {@code List<Position>} 等列表支持多格式输出：
 *       posMask=POS_ECEF 时只有 1 个 Position(ECEF)，
 *       posMask=POS_ECEF|POS_LLH 时有 2 个 Position(ECEF, LLH)，
 *       posMask=POS_ECEF|POS_LLH|POS_ENU 时有 3 个</li>
 *   <li>内部 {@link Sol} 始终 type=0 (ECEF)，不做任何修改</li>
 * </ul>
 *
 * <h3>posMask 配置</h3>
 * <ul>
 *   <li>{@link PrcOpt#POS_ECEF} (1)：输出 ECEF 格式</li>
 *   <li>{@link PrcOpt#POS_LLH}  (2)：输出 LLH 格式</li>
 *   <li>{@link PrcOpt#POS_ENU}  (4)：输出 ENU 基线格式（需要基站位置 rb）</li>
 *   <li>可组合：{@code POS_ECEF | POS_LLH} = 3，同时输出 ECEF 和 LLH</li>
 * </ul>
 *
 * <h3>协方差转换</h3>
 * <p>内部 {@code Sol.qr} 存储 ECEF 协方差 (m²)，
 * ENU 精度通过 {@code soltocov → covenu} 旋转后开方得到，
 * 与 RTKLIB .pos 文件格式一致（sde/sdn/sdu/sdne/sdeu/sdun）。</p>
 *
 * <h3>速度</h3>
 * <p>速度来自 {@code Sol.rr[3..5]}（ECEF），仅 RTK dynamics=1 时有值。
 * LLH 和 ENU 对速度的表示相同（都是 ENU 方向 {ve,vn,vu}），
 * 故 Velocity.type 只有 0(ECEF) 和 1(ENU) 两种。</p>
 */
public class SolData {

    private static final int POS_ECEF = PrcOpt.POS_ECEF;
    private static final int POS_LLH  = PrcOpt.POS_LLH;
    private static final int POS_ENU  = PrcOpt.POS_ENU;

    public final GTime time;
    public final LocalDateTime timeUtc;
    public final String timeStr;

    public final SolutionStatus status;
    public final int numSat;

    public final List<Position> positions;
    public final List<Accuracy> accuracies;
    public final List<Velocity> velocities;

    public final double age;
    public final double ratio;

    public Position getPosition(CoordType type) {
        for (Position p : positions) {
            if (p.type == type) return p;
        }
        return null;
    }

    public Accuracy getAccuracy(CoordType type) {
        for (Accuracy a : accuracies) {
            if (a.type == type) return a;
        }
        return null;
    }

    public Velocity getVelocity(CoordType type) {
        for (Velocity v : velocities) {
            if (v.type == type) return v;
        }
        return null;
    }

    public final double[] clockBias;

    public final double gdop;
    public final double pdop;
    public final double hdop;
    public final double vdop;

    public SolData(Sol sol, int posMask) {
        this(sol, posMask, null);
    }

    public SolData(Sol sol, int posMask, double[] rb) {
        this.time = new GTime(sol.time);
        this.timeUtc = toLocalDateTime(sol.time);
        this.timeStr = formatTime(sol.time);

        this.status = SolutionStatus.fromCode(sol.stat);
        this.numSat = sol.ns;

        double[] llh = new double[3];
        CoordTransform.ecef2pos(sol.rr, llh);
        double lat = llh[0] * Constants.R2D;
        double lon = llh[1] * Constants.R2D;
        double height = llh[2];

        this.positions = buildPositions(sol, posMask, llh, lat, lon, height, rb);
        this.accuracies = buildAccuracies(sol, posMask, llh);
        this.velocities = buildVelocities(sol, posMask, llh);

        this.age = sol.age;
        this.ratio = sol.ratio;
        this.clockBias = sol.dtr.clone();
        this.gdop = sol.gdop;
        this.pdop = sol.pdop;
        this.hdop = sol.hdop;
        this.vdop = sol.vdop;
    }

    private static List<Position> buildPositions(Sol sol, int posMask,
            double[] llh, double lat, double lon, double height, double[] rb) {
        List<Position> list = new ArrayList<>(3);
        if ((posMask & POS_ECEF) != 0) {
            list.add(new Position(CoordType.ECEF, sol.rr[0], sol.rr[1], sol.rr[2]));
        }
        if ((posMask & POS_LLH) != 0) {
            list.add(new Position(CoordType.LLH, lat, lon, height));
        }
        if ((posMask & POS_ENU) != 0 && rb != null) {
            double[] dr = {sol.rr[0] - rb[0], sol.rr[1] - rb[1], sol.rr[2] - rb[2]};
            double[] rbLlh = new double[3];
            CoordTransform.ecef2pos(rb, rbLlh);
            double[] enu = new double[3];
            CoordTransform.ecef2enu(rbLlh, dr, enu);
            list.add(new Position(CoordType.ENU, enu[0], enu[1], enu[2]));
        }
        return List.copyOf(list);
    }

    private static List<Accuracy> buildAccuracies(Sol sol, int posMask, double[] llh) {
        List<Accuracy> list = new ArrayList<>(3);
        double[] P = soltocov(sol.qr);

        if ((posMask & POS_ECEF) != 0) {
            list.add(new Accuracy(CoordType.ECEF,
                    Math.sqrt(Math.max(P[0], 0.0)),
                    Math.sqrt(Math.max(P[4], 0.0)),
                    Math.sqrt(Math.max(P[8], 0.0)),
                    Math.sqrt(Math.abs(P[1])),
                    Math.sqrt(Math.abs(P[5])),
                    Math.sqrt(Math.abs(P[2]))));
        }

        if ((posMask & (POS_LLH | POS_ENU)) != 0) {
            double[] Q = new double[9];
            CoordTransform.covenu(llh, P, Q);
            double s1 = Math.sqrt(Math.max(Q[0], 0.0));
            double s2 = Math.sqrt(Math.max(Q[4], 0.0));
            double s3 = Math.sqrt(Math.max(Q[8], 0.0));
            double c12 = Math.sqrt(Math.abs(Q[1]));
            double c23 = Math.sqrt(Math.abs(Q[5]));
            double c31 = Math.sqrt(Math.abs(Q[2]));
            if ((posMask & POS_LLH) != 0) {
                list.add(new Accuracy(CoordType.ENU, s1, s2, s3, c12, c23, c31));
            }
            if ((posMask & POS_ENU) != 0) {
                list.add(new Accuracy(CoordType.ENU, s1, s2, s3, c12, c23, c31));
            }
        }
        return List.copyOf(list);
    }

    private static List<Velocity> buildVelocities(Sol sol, int posMask, double[] llh) {
        if (sol.rr[3] == 0.0 && sol.rr[4] == 0.0 && sol.rr[5] == 0.0) {
            return List.of();
        }
        List<Velocity> list = new ArrayList<>(2);
        if ((posMask & POS_ECEF) != 0) {
            list.add(new Velocity(CoordType.ECEF, sol.rr[3], sol.rr[4], sol.rr[5]));
        }
        if ((posMask & (POS_LLH | POS_ENU)) != 0) {
            double[] velEcef = {sol.rr[3], sol.rr[4], sol.rr[5]};
            double[] velEnu = new double[3];
            CoordTransform.ecef2enu(llh, velEcef, velEnu);
            list.add(new Velocity(CoordType.ENU, velEnu[0], velEnu[1], velEnu[2]));
        }
        return List.copyOf(list);
    }

    private static double[] soltocov(float[] qr) {
        double[] P = new double[9];
        P[0] = qr[0];
        P[4] = qr[1];
        P[8] = qr[2];
        P[1] = P[3] = qr[3];
        P[5] = P[7] = qr[4];
        P[2] = P[6] = qr[5];
        return P;
    }

    private static LocalDateTime toLocalDateTime(GTime time) {
        double[] ymdhms = TimeSystem.time2ymdhms(time);
        return LocalDateTime.of(
                (int) ymdhms[0], (int) ymdhms[1], (int) ymdhms[2],
                (int) ymdhms[3], (int) ymdhms[4], (int) ymdhms[5]);
    }

    private static String formatTime(GTime time) {
        double[] ymdhms = TimeSystem.time2ymdhms(time);
        return String.format("%04d/%02d/%02d %02d:%02d:%09.6f",
                (int) ymdhms[0], (int) ymdhms[1], (int) ymdhms[2],
                (int) ymdhms[3], (int) ymdhms[4], ymdhms[5]);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(timeStr).append("  ");
        for (Position p : positions) {
            sb.append(p).append("  ");
        }
        sb.append(status).append(" ").append(String.format("%3d", numSat)).append("  ");
        for (Accuracy a : accuracies) {
            sb.append(a).append("  ");
        }
        sb.append(String.format("%5.1f %6.1f", age, ratio));
        return sb.toString();
    }
}