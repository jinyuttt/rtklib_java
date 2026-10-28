package org.rtklib.java.ext.igs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * IGS SSR IM201 的球谐合成、穿刺点几何与单层映射单测（IGS SSR v1.00 §4.5.1）。
 */
public class IgsSsrVtecTest {

    private static final double EPS = 1e-12;

    @Test
    @DisplayName("完全归一化连带勒让德函数符合 Heiskanen & Moritz 定义")
    void normalizedLegendre() {
        double x = 0.3;
        double u = Math.sqrt(1.0 - x * x);
        double[][] p = IgsSsrVtec.normalizedLegendre(2, x);

        assertEquals(1.0, p[0][0], EPS);
        assertEquals(Math.sqrt(3.0) * x, p[1][0], EPS);
        assertEquals(Math.sqrt(3.0) * u, p[1][1], EPS);
        assertEquals(Math.sqrt(5.0) * (3.0 * x * x - 1.0) / 2.0, p[2][0], EPS);
        assertEquals(Math.sqrt(10.0 / 6.0) * 3.0 * x * u, p[2][1], EPS);
        assertEquals(Math.sqrt(10.0 / 24.0) * 3.0 * u * u, p[2][2], EPS);
    }

    @Test
    @DisplayName("太阳固定系经度：14:00 地方时为 0，昼夜相位推进 π")
    void sunLongitude() {
        assertEquals(0.0, IgsSsrVtec.sunLongitude(0.0, 50400), EPS);
        assertEquals(Math.PI, IgsSsrVtec.sunLongitude(0.0, 93600), 1e-12);
        assertEquals(2.0 * Math.PI - 50400.0 * Math.PI / 43200.0,
                IgsSsrVtec.sunLongitude(0.0, 0), 1e-12);
    }

    @Test
    @DisplayName("穿刺点：天顶方向 ψ=0；赤道正北方向 φ_PP=ψ")
    void piercePointGeometry() {
        double hI = 450.0;
        assertEquals(0.0, IgsSsrVtec.centralAngle(0.0, hI, Math.PI / 2.0), EPS);

        double psi = IgsSsrVtec.centralAngle(0.0, hI, Math.toRadians(30.0));
        assertTrue(psi > 0.0);
        assertEquals(psi, IgsSsrVtec.piercePointLatitude(0.0, psi, 0.0), EPS);
        assertEquals(0.0, IgsSsrVtec.piercePointLongitude(0.0, 0.0, psi, psi, 0.0), EPS);
    }

    @Test
    @DisplayName("VTEC 合成：常数项；负值按标准置 0")
    void vtecClamp() {
        double lat = Math.toRadians(35.0);
        assertEquals(10.0, IgsSsrVtec.vtecTecu(constantLayer(450.0, 10.0), lat, 1.0), EPS);
        assertEquals(0.0, IgsSsrVtec.vtecTecu(constantLayer(450.0, -5.0), lat, 1.0), EPS);
    }

    @Test
    @DisplayName("STEC 映射：天顶方向 STEC=VTEC，多层线性求和")
    void stecMapping() {
        List<IgsSsrVtecLayer> layers = List.of(
                constantLayer(450.0, 10.0), constantLayer(250.0, 5.0));
        double stec = IgsSsrVtec.stecTecu(layers, 0.0, 0.0, 0.0, 0.0, Math.PI / 2.0, 50400);
        assertEquals(15.0, stec, EPS);
    }

    @Test
    @DisplayName("STEC 到伪距延迟：1 TECU @ L1 ≈ 0.162 m")
    void rangeDelay() {
        assertEquals(0.16238, IgsSsrVtec.rangeDelayMeters(1.0, 1575.42e6), 1e-5);
    }

    private static IgsSsrVtecLayer constantLayer(double heightKm, double c00) {
        double[][] cnm = new double[2][2];
        double[][] snm = new double[2][2];
        cnm[0][0] = c00;
        return new IgsSsrVtecLayer(heightKm, 1, 1, cnm, snm);
    }
}
