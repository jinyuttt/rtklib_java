package org.rtklib.java.ppp;

import org.junit.jupiter.api.Test;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.data.Nav;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证B1 OSB等价映射修复。
 *
 * <p>问题：OSB文件提供L1X，但观测数据使用L1I，导致getOsb()返回0，WL FCB计算错误。</p>
 * <p>修复：添加BDS_B1_EQUIV映射，允许L1I/L1X/L1P互相替代。</p>
 */
public class B1OsbEquivalenceTest {

    @Test
    void testBdsB1OsbLookup() {
        Nav nav = new Nav();
        nav.fcbWlByCode = new double[Constants.MAXSAT][Constants.MAXCODE + 1];
        nav.fcbFromOsb = true;

        int sat = 111; // C06
        double osbValue = 0.5; // 0.5 meters

        // 模拟OSB文件存储L1X数据
        nav.fcbWlByCode[sat - 1][Constants.CODE_L1X] = osbValue;

        // 测试1：直接查找L1X应该成功
        double result1 = PppAmbFix.getOsb(nav, sat, Constants.CODE_L1X);
        assertEquals(osbValue, result1, 1e-10, "L1X直接查找应该成功");

        // 测试2：查找L1I应该通过等价映射找到L1X
        double result2 = PppAmbFix.getOsb(nav, sat, Constants.CODE_L1I);
        assertEquals(osbValue, result2, 1e-10, "L1I应该通过等价映射找到L1X");

        // 测试3：查找L1P应该通过等价映射找到L1X
        double result3 = PppAmbFix.getOsb(nav, sat, Constants.CODE_L1P);
        assertEquals(osbValue, result3, 1e-10, "L1P应该通过等价映射找到L1X");
    }

    @Test
    void testBdsB1OsbLookupReverse() {
        Nav nav = new Nav();
        nav.fcbWlByCode = new double[Constants.MAXSAT][Constants.MAXCODE + 1];
        nav.fcbFromOsb = true;

        int sat = 111; // C06
        double osbValue = 0.3;

        // 模拟OSB文件存储L1I数据（未来可能的情况）
        nav.fcbWlByCode[sat - 1][Constants.CODE_L1I] = osbValue;

        // 查找L1X应该通过等价映射找到L1I
        double result = PppAmbFix.getOsb(nav, sat, Constants.CODE_L1X);
        assertEquals(osbValue, result, 1e-10, "L1X应该通过等价映射找到L1I");
    }

    @Test
    void testBdsB1NoData() {
        Nav nav = new Nav();
        nav.fcbWlByCode = new double[Constants.MAXSAT][Constants.MAXCODE + 1];
        nav.fcbFromOsb = true;

        int sat = 111; // C06

        // 没有任何B1 OSB数据
        double result = PppAmbFix.getOsb(nav, sat, Constants.CODE_L1I);
        assertEquals(0.0, result, 1e-10, "没有数据时应该返回0");
    }

    @Test
    void testGpsB1NoEquivalence() {
        Nav nav = new Nav();
        nav.fcbWlByCode = new double[Constants.MAXSAT][Constants.MAXCODE + 1];
        nav.fcbFromOsb = true;

        int sat = 10; // G10 (GPS)
        double osbValue = 0.7;

        // GPS只存储L1C数据
        nav.fcbWlByCode[sat - 1][Constants.CODE_L1C] = osbValue;

        // 查找L1I不应该找到L1C（GPS不使用B1等价映射）
        double result = PppAmbFix.getOsb(nav, sat, Constants.CODE_L1I);
        assertEquals(0.0, result, 1e-10, "GPS L1I不应该通过等价映射找到L1C");
    }

    @Test
    void testBdsB2bEquivalence() {
        Nav nav = new Nav();
        nav.fcbWlByCode = new double[Constants.MAXSAT][Constants.MAXCODE + 1];
        nav.fcbFromOsb = true;

        int sat = 111; // C06
        double osbValue = 0.4;

        // 模拟OSB文件存储L7D数据
        nav.fcbWlByCode[sat - 1][Constants.CODE_L7D] = osbValue;

        // 查找L7I应该通过等价映射找到L7D
        double result = PppAmbFix.getOsb(nav, sat, Constants.CODE_L7I);
        assertEquals(osbValue, result, 1e-10, "L7I应该通过等价映射找到L7D");
    }
}
