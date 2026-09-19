package org.rtklib.java.adjust.model;

public enum ReliabilityGrade {

    VERIFIED(5, "交叉验证通过：双FIX且Rover坐标差<2cm，FIX可靠"),
    CROSS_CHECKED(4, "交叉检查通过：双FIX且Rover坐标差<5cm，较可靠"),
    SINGLE_VERIFIED(3, "单基线验证：仅一条FIX，FLOAT基线位置接近佐证"),
    UNVERIFIED(2, "未验证：仅一条FIX，无交叉验证"),
    SUSPECT(1, "可疑：双FIX但Rover坐标差>5cm，至少一条假固定");

    public final int level;
    public final String description;

    ReliabilityGrade(int level, String description) {
        this.level = level;
        this.description = description;
    }

    public boolean isReliable() {
        return this.level >= CROSS_CHECKED.level;
    }

    public boolean shouldFuse() {
        return this.level >= SINGLE_VERIFIED.level;
    }
}