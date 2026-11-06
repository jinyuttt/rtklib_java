package org.rtklib.java.networkvrs.netsol;

/**
 * 一条站间双差观测（卫星 {@code sat} 相对参考星 {@code refSat}）。
 *
 * <p>所有距离量单位为米；{@code ddMwCycles} 为宽巷模糊度浮点值（周）。
 * {@code ddModelTropo} 为模型对流层双差（站 A 减站 B，米），求解前已从相位/码中扣除；
 * {@code los*} 为双差几何距离对站 B 坐标的偏导分量（e_s − e_ref）；
 * {@code tropoMapDiffA/B} 为各站映射函数差（m_s − m_ref），用作残余天顶对流层延迟的偏导。</p>
 */
public record DdObservation(
        int sat,
        int refSat,
        int sys,
        double f1,
        double f2,
        double wavelengthWl,
        double ddRange,
        double ddPhaseIf,
        double ddCodeIf,
        double ddGfPhase,
        double ddMwCycles,
        double ddModelTropo,
        double losX,
        double losY,
        double losZ,
        double tropoMapDiffA,
        double tropoMapDiffB,
        double elevationSatDeg,
        double elevationRefDeg) {
}