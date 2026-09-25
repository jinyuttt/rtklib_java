package org.rtklib.java.ppp;

import org.rtklib.java.constants.Constants;
import org.rtklib.java.data.Nav;

/**
 * 验证B1 OSB等价映射修复的手动测试程序。
 *
 * <p>问题：OSB文件提供L1X，但观测数据使用L1I，导致getOsb()返回0，WL FCB计算错误。</p>
 * <p>修复：添加BDS_B1_EQUIV映射，允许L1I/L1X/L1P互相替代。</p>
 */
public class B1OsbManualVerification {

    public static void main(String[] args) {
        System.out.println("=== B1 OSB等价映射修复验证 ===\n");

        testBdsB1OsbLookup();
        testBdsB1OsbLookupReverse();
        testBdsB1NoData();
        testGpsB1NoEquivalence();
        testBdsB2bEquivalence();

        System.out.println("\n=== 所有测试通过 ===");
    }

    private static void testBdsB1OsbLookup() {
        System.out.println("测试1: BDS B1 OSB查找（L1X→L1I/L1P等价）");

        Nav nav = new Nav();
        nav.fcbWlByCode = new double[Constants.MAXSAT][Constants.MAXCODE + 1];
        nav.fcbFromOsb = true;

        int sat = 111; // C06
        double osbValue = 0.5;

        // 模拟OSB文件存储L1X数据
        nav.fcbWlByCode[sat - 1][Constants.CODE_L1X] = osbValue;

        // 测试1：直接查找L1X应该成功
        double result1 = PppAmbFix.getOsb(nav, sat, Constants.CODE_L1X);
        if (Math.abs(result1 - osbValue) > 1e-10) {
            throw new AssertionError("L1X直接查找失败: expected=" + osbValue + ", got=" + result1);
        }
        System.out.println("  ✓ L1X直接查找成功: " + result1);

        // 测试2：查找L1I应该通过等价映射找到L1X
        double result2 = PppAmbFix.getOsb(nav, sat, Constants.CODE_L1I);
        if (Math.abs(result2 - osbValue) > 1e-10) {
            throw new AssertionError("L1I等价查找失败: expected=" + osbValue + ", got=" + result2);
        }
        System.out.println("  ✓ L1I通过等价映射找到L1X: " + result2);

        // 测试3：查找L1P应该通过等价映射找到L1X
        double result3 = PppAmbFix.getOsb(nav, sat, Constants.CODE_L1P);
        if (Math.abs(result3 - osbValue) > 1e-10) {
            throw new AssertionError("L1P等价查找失败: expected=" + osbValue + ", got=" + result3);
        }
        System.out.println("  ✓ L1P通过等价映射找到L1X: " + result3);
        System.out.println();
    }

    private static void testBdsB1OsbLookupReverse() {
        System.out.println("测试2: BDS B1 OSB查找（反向：L1I→L1X）");

        Nav nav = new Nav();
        nav.fcbWlByCode = new double[Constants.MAXSAT][Constants.MAXCODE + 1];
        nav.fcbFromOsb = true;

        int sat = 111;
        double osbValue = 0.3;

        // 模拟OSB文件存储L1I数据（未来可能的情况）
        nav.fcbWlByCode[sat - 1][Constants.CODE_L1I] = osbValue;

        // 查找L1X应该通过等价映射找到L1I
        double result = PppAmbFix.getOsb(nav, sat, Constants.CODE_L1X);
        if (Math.abs(result - osbValue) > 1e-10) {
            throw new AssertionError("L1X反向等价查找失败: expected=" + osbValue + ", got=" + result);
        }
        System.out.println("  ✓ L1X通过等价映射找到L1I: " + result);
        System.out.println();
    }

    private static void testBdsB1NoData() {
        System.out.println("测试3: BDS B1无数据时返回0");

        Nav nav = new Nav();
        nav.fcbWlByCode = new double[Constants.MAXSAT][Constants.MAXCODE + 1];
        nav.fcbFromOsb = true;

        int sat = 111;

        // 没有任何B1 OSB数据
        double result = PppAmbFix.getOsb(nav, sat, Constants.CODE_L1I);
        if (Math.abs(result) > 1e-10) {
            throw new AssertionError("无数据时应该返回0, got=" + result);
        }
        System.out.println("  ✓ 无数据时返回0");
        System.out.println();
    }

    private static void testGpsB1NoEquivalence() {
        System.out.println("测试4: GPS不使用B1等价映射");

        Nav nav = new Nav();
        nav.fcbWlByCode = new double[Constants.MAXSAT][Constants.MAXCODE + 1];
        nav.fcbFromOsb = true;

        int sat = 10; // G10 (GPS)
        double osbValue = 0.7;

        // GPS只存储L1C数据
        nav.fcbWlByCode[sat - 1][Constants.CODE_L1C] = osbValue;

        // 查找L1I不应该找到L1C（GPS不使用B1等价映射）
        double result = PppAmbFix.getOsb(nav, sat, Constants.CODE_L1I);
        if (Math.abs(result) > 1e-10) {
            throw new AssertionError("GPS L1I不应该通过等价映射找到L1C, got=" + result);
        }
        System.out.println("  ✓ GPS L1I不会错误地找到L1C");
        System.out.println();
    }

    private static void testBdsB2bEquivalence() {
        System.out.println("测试5: BDS B2b等价映射（L7D→L7I）");

        Nav nav = new Nav();
        nav.fcbWlByCode = new double[Constants.MAXSAT][Constants.MAXCODE + 1];
        nav.fcbFromOsb = true;

        int sat = 111;
        double osbValue = 0.4;

        // 模拟OSB文件存储L7D数据
        nav.fcbWlByCode[sat - 1][Constants.CODE_L7D] = osbValue;

        // 查找L7I应该通过等价映射找到L7D
        double result = PppAmbFix.getOsb(nav, sat, Constants.CODE_L7I);
        if (Math.abs(result - osbValue) > 1e-10) {
            throw new AssertionError("L7I等价查找失败: expected=" + osbValue + ", got=" + result);
        }
        System.out.println("  ✓ L7I通过等价映射找到L7D: " + result);
        System.out.println();
    }
}
