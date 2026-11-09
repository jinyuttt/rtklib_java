package org.rtklib.java.test;

import java.util.List;

import org.rtklib.java.data.Accuracy;
import org.rtklib.java.data.Position;
import org.rtklib.java.data.SolData;
import org.rtklib.java.data.SolutionStatus;
import org.rtklib.java.rtkpos.RtkProcessor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 解算结果逐位比对工具（I-1 零回归断言用）。
 * <p>
 * 用 {@link Double#compare} 而非容差比较：AI 接线在「不干预」路径上不允许引入
 * 任何浮点扰动，否则同一份数据两次运行结果不同，零回归即不成立。
 * </p>
 */
final class SolBitwise {

    private SolBitwise() {
    }

    static long countFix(RtkProcessor.RtkResult r) {
        return r.solutions.stream().filter(s -> s.status == SolutionStatus.FIX).count();
    }

    static long countFloat(RtkProcessor.RtkResult r) {
        return r.solutions.stream().filter(s -> s.status == SolutionStatus.FLOAT).count();
    }

    static void assertIdentical(RtkProcessor.RtkResult a, RtkProcessor.RtkResult b, String tag) {
        assertEquals(a.totalEpochs, b.totalEpochs, tag + " totalEpochs");
        assertEquals(a.successCount, b.successCount, tag + " successCount");
        assertEquals(a.failCount, b.failCount, tag + " failCount");

        List<SolData> sa = a.solutions;
        List<SolData> sb = b.solutions;
        assertEquals(sa.size(), sb.size(), tag + " solutions 数量");

        for (int i = 0; i < sa.size(); i++) {
            SolData x = sa.get(i);
            SolData y = sb.get(i);
            String at = tag + " epoch#" + i + " " + x.timeStr;

            assertEquals(x.timeStr, y.timeStr, at + " 时间");
            assertEquals(x.status, y.status, at + " 状态");
            assertEquals(x.numSat, y.numSat, at + " 卫星数");
            assertBitEqual(x.ratio, y.ratio, at + " ratio");
            assertBitEqual(x.age, y.age, at + " age");

            assertEquals(x.positions.size(), y.positions.size(), at + " 位置数量");
            for (int j = 0; j < x.positions.size(); j++) {
                Position px = x.positions.get(j);
                Position py = y.positions.get(j);
                assertEquals(px.type, py.type, at + " 位置坐标系");
                assertBitEqual(px.v1, py.v1, at + " 位置 v1");
                assertBitEqual(px.v2, py.v2, at + " 位置 v2");
                assertBitEqual(px.v3, py.v3, at + " 位置 v3");
            }

            assertEquals(x.accuracies.size(), y.accuracies.size(), at + " 精度数量");
            for (int j = 0; j < x.accuracies.size(); j++) {
                Accuracy ax = x.accuracies.get(j);
                Accuracy ay = y.accuracies.get(j);
                assertEquals(ax.type, ay.type, at + " 精度坐标系");
                assertBitEqual(ax.s1, ay.s1, at + " 精度 s1");
                assertBitEqual(ax.s2, ay.s2, at + " 精度 s2");
                assertBitEqual(ax.s3, ay.s3, at + " 精度 s3");
                assertBitEqual(ax.c12, ay.c12, at + " 精度 c12");
                assertBitEqual(ax.c23, ay.c23, at + " 精度 c23");
                assertBitEqual(ax.c31, ay.c31, at + " 精度 c31");
            }
        }
    }

    private static void assertBitEqual(double expected, double actual, String message) {
        assertTrue(Double.compare(expected, actual) == 0,
                message + " 逐位不一致: expected=" + expected + ", actual=" + actual);
    }
}