package org.rtklib.java.research.integration;

import org.ejml.simple.SimpleMatrix;

/**
 * 状态转移模型：状态转移矩阵F、过程噪声Q、观测噪声R（移植自FE-GUT parameter.h/parameter.cc）。
 *
 * <p>核心方法：
 * <ul>
 *   <li>{@link #transitionMatrix(double, IntegrationState)} — 线性运动模型F矩阵（12×12）</li>
 *   <li>{@link #processNoiseCovariance(double)} — 分段白噪声Q矩阵（12×12）</li>
 *   <li>{@link #observationNoiseCovariance(int, int)} — 伪距/伪距率/UWB观测噪声</li>
 *   <li>{@link #propagateState(double, IntegrationState)} — 状态传播（恒速度模型）</li>
 * </ul>
 */
public final class StateModel {
    private StateModel() {}

    public static final double SQR(double x) { return x * x; }

    public static SimpleMatrix transitionMatrix(double dt, IntegrationState state) {
        SimpleMatrix F = SimpleMatrix.identity(IntegrationState.DIM_STATE);
        F.set(0,3,dt); F.set(1,4,dt); F.set(2,5,dt);
        F.set(3,6,dt); F.set(4,7,dt); F.set(5,8,dt);
        F.set(9,10,dt);
        return F;
    }

    public static SimpleMatrix processNoiseCovariance(double dt) {
        double d2 = dt * dt, d3 = dt * d2;
        double qa = SQR(0.25);
        SimpleMatrix Q = new SimpleMatrix(IntegrationState.DIM_STATE, IntegrationState.DIM_STATE);
        Q.set(0,0, qa * d3 / 3);  Q.set(0,3, qa * d2 / 2);
        Q.set(1,1, qa * d3 / 3);  Q.set(1,4, qa * d2 / 2);
        Q.set(2,2, qa * d3 / 3);  Q.set(2,5, qa * d2 / 2);
        Q.set(3,0, qa * d2 / 2);  Q.set(3,3, qa * dt);
        Q.set(4,1, qa * d2 / 2);  Q.set(4,4, qa * dt);
        Q.set(5,2, qa * d2 / 2);  Q.set(5,5, qa * dt);
        Q.set(9,9,   SQR(3.0) * dt);
        Q.set(10,10, SQR(0.1) * dt);
        Q.set(11,11, SQR(0.01) * dt);
        return Q;
    }

    public static double pseudorangeNoise(double elevation) {
        double el = Math.max(elevation, 0.05);
        return SQR(0.5 / Math.sin(el));
    }

    public static double pseudorangeRateNoise(double elevation) {
        double el = Math.max(elevation, 0.05);
        return SQR(0.1 / Math.sin(el));
    }

    public static double uwbRangeNoise() {
        return SQR(0.15);
    }

    public static IntegrationState propagateState(double dt, IntegrationState oldState) {
        IntegrationState newState = oldState.copy();
        for (int i = 0; i < 3; i++) {
            newState.xdata[i]     += dt * oldState.xdata[i + 3] + 0.5 * d2 * oldState.xdata[i + 6];
            newState.xdata[i + 3] += dt * oldState.xdata[i + 6];
        }
        newState.xdata[9]  += dt * oldState.xdata[10];
        newState.time += dt;
        return newState;
    }

    private static final double d2 = 0.0;
}