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
    """
    n = b + c
    if n == 0:
        return 1.0
    k = min(b, c)
    tail = sum(math.comb(n, i) for i in range(k + 1)) / (2.0 ** n)
    return min(1.0, 2.0 * tail)


def g1_judgeable(n_events_a: int, min_events: int = 100) -> bool:
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
                       min_events: int = 100) -> GateResult:
    """按 §7.4 汇总 G-1~G-4（接口一的净收益判据；接口三复用同一框架）。

    - G-1：错误固定率相对下降，取置信下界 ≥ 30%，且需过样本量前置；
    - G-2：固定率绝对下降 ≤ 1.0 个百分点；
    - G-3：跨站泛化由调用方按站逐个判定后汇总（此处只报全局值）；
    - G-4：最坏单站不恶化由调用方逐站判定后汇总（此处只报全局值）。
    """
    res = GateResult()

    if not g1_judgeable(group_a.n_wrong_fix, min_events):
        res.add("G-1", None,
                f"A 组错误固定事件 {group_a.n_wrong_fix} < {min_events}，不予判定（须扩数据）")
    else:
        point = ((group_a.wrong_fix_rate - group_c.wrong_fix_rate) / group_a.wrong_fix_rate)
        lo_a, hi_a = wilson_interval(group_a.n_wrong_fix, group_a.n_fix)
        lo_c, hi_c = wilson_interval(group_c.n_wrong_fix, group_c.n_fix)
        lower = (lo_a - hi_c) / hi_a if hi_a > 0 else 0.0
        p = mcnemar_exact(b, c)
        res.add("G-1", lower >= 0.30,
                f"相对下降 点估计 {point:.1%}，保守下界 {lower:.1%}，McNemar p={p:.3f}")

    drop_pp = (group_a.fix_rate - group_c.fix_rate) * 100.0
    res.add("G-2", drop_pp <= 1.0, f"固定率绝对下降 {drop_pp:.2f} 个百分点（阈值 ≤ 1.0）")
    res.add("G-3", None, "需按「未参与训练与早停的站」逐站判定后汇总")
    res.add("G-4", None, "需按站逐站判定「任一站错误固定率不上升」后汇总")
    return res