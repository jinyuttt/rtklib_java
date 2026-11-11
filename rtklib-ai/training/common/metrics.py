"""评估指标与净收益判据（设计文档 §7.2 / §7.4）。

只依赖标准库 + numpy，避免评估脚本引入额外统计包。三组能力：

1. 回归指标：``mae`` / ``rmse`` / 配对改善的 bootstrap 置信区间；
2. 比例指标：Wilson 区间（**不用正态近似**——小比例小样本下会给出负下界）；
3. 判据：G-1 的样本量前置（MDE）、配对 McNemar 显著性、G-1~G-4 汇总。
"""

from __future__ import annotations

import math
from dataclasses import dataclass, field

import numpy as np

#: G-1 样本量前置（MDE）：错误固定事件数低于此值时，95% 下界的分辨率不足以
#: 支撑 30% 的相对下降判定，只能记「不予判定」而非「未通过」。
MIN_WRONG_EVENTS = 100


def mae(pred: np.ndarray, truth: np.ndarray) -> float:
    return float(np.mean(np.abs(np.asarray(pred, dtype=np.float64)
                                - np.asarray(truth, dtype=np.float64))))


def rmse(pred: np.ndarray, truth: np.ndarray) -> float:
    d = np.asarray(pred, dtype=np.float64) - np.asarray(truth, dtype=np.float64)
    return float(np.sqrt(np.mean(d * d)))


def relative_improvement_ci(err_before: np.ndarray, err_after: np.ndarray,
                            n_boot: int = 2000, alpha: float = 0.05,
                            seed: int = 0) -> tuple[float, float, float]:
    """配对相对改善 ``(RMSE_before - RMSE_after) / RMSE_before`` 的点估计与置信区间。

    同一行在「未改正 / 已改正」两侧配对统计，禁止跨数据集比较两个独立集合（§7.4）。
    """
    before = np.asarray(err_before, dtype=np.float64) ** 2
    after = np.asarray(err_after, dtype=np.float64) ** 2
    if before.size == 0:
        return 0.0, 0.0, 0.0
    point = (math.sqrt(before.mean()) - math.sqrt(after.mean())) / math.sqrt(before.mean())

    rng = np.random.default_rng(seed)
    idx = rng.integers(0, before.size, size=(n_boot, before.size))
    b = before[idx].mean(axis=1)
    a = after[idx].mean(axis=1)
    ratios = (np.sqrt(b) - np.sqrt(a)) / np.sqrt(b)
    return point, float(np.quantile(ratios, alpha / 2)), float(np.quantile(ratios, 1 - alpha / 2))


def wilson_interval(k: int, n: int, z: float = 1.96) -> tuple[float, float]:
    """比例量的 Wilson score 区间（§7.4 G-1 要求 95% 置信下界）。"""
    if n <= 0:
        return 0.0, 1.0
    p = k / n
    denom = 1.0 + z * z / n
    center = (p + z * z / (2.0 * n)) / denom
    half = z * math.sqrt(p * (1.0 - p) / n + z * z / (4.0 * n * n)) / denom
    return max(0.0, center - half), min(1.0, center + half)


def mcnemar_exact(b: int, c: int) -> float:
    """配对 McNemar 精确检验（双侧二项检验，不依赖卡方近似）。

    ``b`` = 「A 错、C 对」的历元数，``c`` = 「A 对、C 错」的历元数。

    计算在对数空间完成：``n`` 上千时 ``2**n`` 与 ``comb(n, i)`` 都会溢出/耗时爆炸，
    直接求和会抛 ``OverflowError``。改用 ``lgamma`` 的 log-sum-exp 形式，
    结果与精确整数运算一致但不会溢出。
    """
    n = b + c
    if n == 0:
        return 1.0
    k = min(b, c)
    lg_n1 = math.lgamma(n + 1)
    logs = [lg_n1 - math.lgamma(i + 1) - math.lgamma(n - i + 1) for i in range(k + 1)]
    peak = max(logs)
    log_tail = peak + math.log(sum(math.exp(v - peak) for v in logs))
    tail = math.exp(log_tail - n * math.log(2.0))
    return min(1.0, 2.0 * tail)


def paired_relative_drop(wrong: np.ndarray, rejected: np.ndarray) -> float:
    """配对相对下降：A 组（全部固定历元）与 C 组（未被剔除的历元）来自**同一批历元**。

    ``wrong`` = 该历元是否为错误固定，``rejected`` = 该历元是否被接口一拦下。
    两组不是独立样本，因此相对下降必须按配对口径计算（§7.4 第 3 条）。
    """
    wrong = np.asarray(wrong, dtype=bool)
    rejected = np.asarray(rejected, dtype=bool)
    n_a = int(wrong.size)
    n_c = int((~rejected).sum())
    w_a = int(wrong.sum())
    w_c = int((wrong & ~rejected).sum())
    if w_a <= 0 or n_c <= 0:
        return 0.0
    return 1.0 - (w_c / n_c) / (w_a / n_a)


def paired_relative_drop_ci(wrong: np.ndarray, rejected: np.ndarray,
                            n_boot: int = 2000, alpha: float = 0.05,
                            seed: int = 0) -> tuple[float, float, float]:
    """相对下降的点估计与配对 bootstrap 置信区间。

    **为什么不能用两个独立 Wilson 区间相减**：``(lo_a - hi_c) / hi_a`` 把 A、C 当作
    两个独立比例的最坏情形差，既丢弃了「同一历元在 A、C 两侧配对」这一信息，也不是
    比值量的置信区间，会把下界压到几乎必然不达标（实测同一数据上 12% vs 30.5%）。
    §7.4 第 3 条明确要求配对比较，故此处按历元重采样：同一次抽样同时决定 A、C 两侧。

    注意：历元在时间上高度相关，逐历元重采样给出的区间偏乐观；跨 run 的
    cluster bootstrap 更保守。本函数保持 §7.4 的配对口径，cluster 版本作为稳健性对照。
    """
    wrong = np.asarray(wrong, dtype=bool)
    rejected = np.asarray(rejected, dtype=bool)
    n = int(wrong.size)
    point = paired_relative_drop(wrong, rejected)
    if n == 0:
        return point, point, point

    rng = np.random.default_rng(seed)
    idx = rng.integers(0, n, size=(n_boot, n))
    w_a = wrong[idx].sum(axis=1).astype(np.float64)
    n_c = (n - rejected[idx].sum(axis=1)).astype(np.float64)
    w_c = (wrong[idx] & ~rejected[idx]).sum(axis=1).astype(np.float64)
    with np.errstate(divide="ignore", invalid="ignore"):
        ratios = 1.0 - (w_c / np.maximum(n_c, 1.0)) / np.maximum(w_a / n, 1e-12)
    ratios = ratios[np.isfinite(ratios)]
    if ratios.size == 0:
        return point, point, point
    return point, float(np.quantile(ratios, alpha / 2)), float(np.quantile(ratios, 1 - alpha / 2))


def g1_judgeable(n_events_a: int, min_events: int = MIN_WRONG_EVENTS) -> bool:
    """G-1 样本量前置：A 组错误固定事件数不足时不予判定（既不算通过也不算失败）。"""
    return n_events_a >= min_events


def required_events(rel_drop: float = 0.30, alpha: float = 0.05, power: float = 0.80) -> int:
    """粗略 MDE 门限：检测 ``rel_drop`` 相对下降所需的最小事件数（经验值）。"""
    z_a = 1.6449 if alpha >= 0.05 else 1.96
    z_b = 0.8416 if power >= 0.80 else 0.0
    p0 = 0.05
    p1 = p0 * (1.0 - rel_drop)
    denom = (math.sqrt(p0 * (1.0 - p0)) + math.sqrt(p1 * (1.0 - p1))) ** 2
    return int(math.ceil((z_a + z_b) ** 2 * denom / max(1e-9, (p0 - p1) ** 2)))


def g1_max_achievable(n_total_epochs: int, n_wrong_fix: int,
                      max_fix_drop_pp: float = 1.0) -> float:
    """G-1 在 G-2 预算内的理论上限（错误固定率相对下降）。

    G-2 只允许剔除 ``max_fix_drop_pp`` 个百分点的 AR 历元。即便被剔除的历元**全部**
    命中错误固定（完美分类器），相对下降也只能达到「预算 / 错误固定数」。因此
    ``max_fix_drop_pp / 100 * A < 0.30 * W`` 时，G-1 的 30% 目标与 G-2 的 1pp 预算
    **数学上互斥**，与模型好坏无关：此时 G-1 只能「不予判定」，不能记「未通过」。
    """
    if n_wrong_fix <= 0 or n_total_epochs <= 0:
        return 0.0
    budget = max_fix_drop_pp / 100.0 * n_total_epochs
    return min(1.0, budget / n_wrong_fix)


@dataclass
class FixStats:
    """固定质量统计：固定率、错误固定率（分母取固定历元数）。"""

    n_epochs: int
    n_fix: int
    n_wrong_fix: int

    @property
    def fix_rate(self) -> float:
        return self.n_fix / self.n_epochs if self.n_epochs else 0.0

    @property
    def wrong_fix_rate(self) -> float:
        return self.n_wrong_fix / self.n_fix if self.n_fix else 0.0


@dataclass
class GateResult:
    """G-1~G-5 判定结果；未判定项显式标注，避免「没判定」被读成「通过」。"""

    gates: dict = field(default_factory=dict)
    notes: list = field(default_factory=list)

    def add(self, name: str, passed: bool | None, detail: str) -> None:
        self.gates[name] = (passed, detail)

    def report(self) -> str:
        lines = []
        for name, (passed, detail) in self.gates.items():
            tag = "未判定" if passed is None else ("通过" if passed else "未通过")
            lines.append(f"  {name}: {tag} — {detail}")
        for note in self.notes:
            lines.append(f"  note: {note}")
        return "\n".join(lines)


def evaluate_fix_gates(group_a: FixStats, group_c: FixStats, b: int, c: int,
                       min_events: int = MIN_WRONG_EVENTS, max_fix_drop_pp: float = 1.0,
                       rel_target: float = 0.30,
                       wrong: "np.ndarray | None" = None,
                       rejected: "np.ndarray | None" = None) -> GateResult:
    """按 §7.4 汇总 G-1~G-4（接口一的净收益判据；接口三复用同一框架）。

    - G-1：错误固定率相对下降，取 95% 置信下界 ≥ 30%，且需过样本量前置与可行性前置；
    - G-2：固定率绝对下降 ≤ 1.0 个百分点；
    - G-3：跨站泛化由调用方按站逐个判定后汇总（此处只报全局值）；
    - G-4：最坏单站不恶化由调用方逐站判定后汇总（此处只报全局值）。

    可行性前置（:func:`g1_max_achievable`）：错误固定占 AR 历元比例过高时，剔除 30%
    错误固定所需历元数超过 G-2 预算，两条判据互斥，此时 G-1 记「不予判定」。

    置信下界口径（§7.4 第 3 条）：传入 ``wrong`` / ``rejected`` 时用**配对**
    bootstrap 下界；未传时退化为两组独立 Wilson 区间相减——后者丢弃配对信息、
    会把下界压得远低于真实置信水平，仅为兼容旧调用保留，**不应**用于正式判定。
    """
    res = GateResult()

    max_rel = g1_max_achievable(group_a.n_epochs, group_a.n_wrong_fix, max_fix_drop_pp)
    if max_rel < rel_target:
        res.add("G-1", None,
                f"数据可行性不足：G-2 的 {max_fix_drop_pp:.1f} pp 预算最多剔除 "
                f"{max_rel:.1%} 的错误固定（< 目标 {rel_target:.0%}），"
                f"需 0.3*W={0.3 * group_a.n_wrong_fix:.0f} 历元 > "
                f"预算 {max_fix_drop_pp / 100.0 * group_a.n_epochs:.0f} 历元")
    elif not g1_judgeable(group_a.n_wrong_fix, min_events):
        res.add("G-1", None,
                f"A 组错误固定事件 {group_a.n_wrong_fix} < {min_events}，不予判定（须扩数据）")
    else:
        point = ((group_a.wrong_fix_rate - group_c.wrong_fix_rate) / group_a.wrong_fix_rate)
        lo_a, hi_a = wilson_interval(group_a.n_wrong_fix, group_a.n_fix)
        lo_c, hi_c = wilson_interval(group_c.n_wrong_fix, group_c.n_fix)
        p = mcnemar_exact(b, c)
        if wrong is not None and rejected is not None:
            point, lower, upper = paired_relative_drop_ci(wrong, rejected)
            ci = f"配对 bootstrap 95% 区间 [{lower:.1%}, {upper:.1%}]，判据取下界 {lower:.1%}"
        else:
            lower = (lo_a - hi_c) / hi_a
            ci = (f"非配对下界 {lower:.1%}（A [{lo_a:.1%},{hi_a:.1%}] / "
                  f"C [{lo_c:.1%},{hi_c:.1%}]）")
        res.add("G-1", lower >= rel_target,
                f"相对下降 点估计 {point:.1%}，{ci}，McNemar p={p:.3f}"
                f"（可行性上限 {max_rel:.1%}）")

    drop_pp = (group_a.fix_rate - group_c.fix_rate) * 100.0
    res.add("G-2", drop_pp <= max_fix_drop_pp,
            f"固定率绝对下降 {drop_pp:.2f} 个百分点（阈值 ≤ {max_fix_drop_pp:.1f}）")
    res.add("G-3", None, "需按「未参与训练与早停的站」逐站判定后汇总")
    res.add("G-4", None, "需按站逐站判定「任一站错误固定率不上升」后汇总")
    return res