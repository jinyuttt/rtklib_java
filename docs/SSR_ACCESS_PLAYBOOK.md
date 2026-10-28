# SSR 接入手册（v3.1.0）

> 定位：SSR 接入的**操作与验收手册**。CAS `SSRA01CAS0`/`SSRC01CAS0` 已接入并完成录制/回放闭环验证（2026-10-28）；算法已封存，接入不涉及算法改动。
> 相关：[archive/ROADMAP_v3.3.0.md](archive/ROADMAP_v3.3.0.md) §8.8（✅ 已闭环，历史记录） · [PROJECT_FINAL_SUMMARY.md](PROJECT_FINAL_SUMMARY.md) 表 3 · [ALGORITHM_FIX_LOG.md](ALGORITHM_FIX_LOG.md) FIX-ALG-026~028、035~036

## 一、一页结论：只改两行

```java
// 1. 改 Caster 与挂载点（凭据从环境变量读取，不入库）
NtripClient client = new NtripClient(host, port, mountpoint, username, password, listener);

// 2. 打开 PPP-RTK 路由
cfg.pppRtk.enable = true;
```

数据链路已全链路贯通，无需改算法代码：

```
NTRIP/Caster ──► Rtcm（RTCM SSR 1057~1068 / 1240~1270）
                     └─► Nav.ssr ──► SsrCorrector ──► PppRtkCore.ppprtkos()
CLAS L6 ──► CompactSsrDecoder ──► Nav.ssr ─┘
HAS ──────► HasSsrDecoder ──────► Nav.ssr ─┘
```

无 SSR 数据时 `ppprtkos()` 会自动退化为普通 PPP，不会丢历元。

## 二、数据源矩阵

| 来源 | 当前状态 | 覆盖 / 内容 | 能否 AR | 接入动作 |
|---|---|---|---|---|
| **CAS IGS**（`igs.bdsmart.cn:2001`） | ✅ 已接入：`SSRA01CAS0`/`SSRC01CAS0` 可用，录制/回放闭环通过（FIX-ALG-035/036） | 亚太 | 是（含 1270 BDS 相位偏差） | 无需动作；换源时改挂载点即可 |
| **BDS PPP-B2b**（武汉大学 BDSet 公开归档） | 公开可用，可直接下载 | BDS 改正，**无相位偏差** | 否，仅浮点 | 先跑通 float，验证解码与改正链 |
| **北航 2025 全球区域 SSR** | 需离线申请；元数据未明确含相位偏差与区域大气 | 全球区域 | 待确认 | 提交数据集申请，拿到元数据后判定 |
| **Galileo HAS** | `HasSsrDecoder` 已实现 COMPACT-SSR（MT1~MT9）→ `Nav.ssr`；互联网 IDD 接入未实现；无公开事后存档 | 全球 | 是（MT8 相位偏差） | 获取 IDD 注册凭据 + 补 IDD 帧同步层 |
| **QZSS CLAS** | `CompactSsrDecoder` 已实现 MT4073 子类型 1~12；缺公开 L6 样本 | 日本及周边 | 部分（取决于改正内容） | 先找公开 L6 二进制样本做端到端冒烟 |
| BKG `SSRC00VETA` | 公开但为欧洲区域服务 | 欧洲 | — | 覆盖不到国内/加拿大，不作验证源 |
| CNES ppp-wizard | 仅事后 SP3/CLK/BIA/OBX | — | — | 无 RTCM SSR 电文，不作实时源 |

## 三、凭据与测试数据管理（硬约束）

1. 账号密码**不得入库**：通过环境变量或本机用户目录配置提供；`.gitignore` 已排除 `test-data/gnss-validation/`。
2. 依赖凭据的验证代码放在公开测试树的**私有数据目录**下，用 `Assumptions.assumeTrue(file.isFile())` 门卫，CI 数据缺失自动 skip。
3. 推荐的变量名（与现有测试一致）：

| 变量 | 用途 |
|---|---|
| `WUH20_RTCM` / `WUH20_BRDC` | 录制流与广播星历路径覆盖 |
| `PPP_GPT3_GRID` | GPT3 网格文件路径覆盖 |
| `PPP_DATA_SUBDIR` / `PPP_PRODUCT_SUBDIR` / `PPP_TABLE_SUBDIR` | PPP-AR 验证数据集切换 |

## 四、首次接入五步

1. **拉 Sourcetable**：`getSourcetable()` 已支持 NTRIP 1.0/2.0 状态行与 chunked/gzip 响应，确认目标挂载点在线、且有 SSR 标识。
2. **录制 30 s**：统计帧数与 CRC；基线参考为 WUH20 观测流 74 KB / 262 帧全通过。
3. **确认解码落库**：检查 `nav.ssr` 的轨道、钟差、码/相位偏差字段非空；RTCM 走 `Rtcm`，CLAS 走 `CompactSsrDecoder`，HAS 走 `HasSsrDecoder`。
4. **先跑浮点**：`cfg.pppRtk.enable = true`，其余 AR 开关保持关闭，对照事后产品核对 ENU，确认改正链生效。
5. **再开固定**：`cfg.pppRtk.enableAR = true`（可选 `cfg.pppRtk.enableFixHold`），统计 fix 率、固定解 RMS 与逐历元误差分布（p50/p90），排查假 Fix。

## 五、开关一览（默认全部关闭）

| 开关 | 默认 | 作用 |
|---|---|---|
| `cfg.pppRtk.enable` | `false` | 路由至 `ppprtkos()`；无 SSR 时退化为普通 PPP |
| `cfg.pppRtk.enableAR` | `false` | PPP-RTK 模糊度固定 |
| `cfg.pppRtk.arRatio` | `3.0` | 固定判据 ratio 门限 |
| `cfg.pppRtk.enableFixHold` | `false` | 固定后保持（FIX-AND-HOLD） |
| `cfg.pppRtk.fixHoldMinEpoch` | `10` | 进入保持的最小连续固定历元 |
| `cfg.pppRtk.fixHoldVar` | `1e-4` | 保持伪观测方差 |
| `cfg.ppp.enableCompactSsr` | `false` | 紧凑 SSR（QZSS CLAS MT4073）解码入口 |

## 六、验收清单

- [x] Sourcetable 解析成功（含 chunked / gzip 响应）
- [x] 30 s 录制帧 CRC 全通过，帧率与挂载点标称一致
- [x] `nav.ssr` 轨道 / 钟差 / 相位偏差三类字段非空
- [x] 浮点 PPP-RTK 的 ENU 与事后产品同量级（无系统性偏移）
- [x] AR 链路已实测：WL 100% 固定、NL 受可见卫星数限制未达 FIX（结论归档，算法冻结不调门限）
- [x] 关闭全部开关的基线回归用例全部通过（不改变既有行为）

## 七、已知坑（按踩坑概率排序）

1. **把时变 OSB 当常量读**：30 s BIA 必须按时间戳序列取值，否则码 OSB 累加爆炸（FIX-ALG-027）。
2. **MW 组合 WL 偏差改正施加两次或零次**：必须恰好一次；单次 + 不施码 OSB 为终态（FIX-ALG-028）。
3. **双频 IFLC 追求高固定率**：无差分 median 对齐存在整周平移自由度，δ 门会拦截并安全回退；生产用三频 3F。
4. **BDS3 宽巷双重改正**：`PppAmbFixBds3` 在 OSB 模式下仍存在一次多余改正，属已归档架构债，启用 BDS3 AR 前需知悉。
5. **SSR 不含相位偏差却开 AR**：固定率必然 0%，应只跑浮点。
6. **授权错误 6 误判为密码错误**：该码表示挂载点未授权，不是认证失败。
7. **把 HAS IDD 当 RTCM 喂**：IDD 是专有二进制，须先帧同步再交给 `HasSsrDecoder`，不能直接进 `Rtcm`。
8. **时间系统混用**：全链路按 GPST 处理，SSR 时标（IOD/参考时刻）过期会导致改正失效。

## 八、与其它文档的关系

| 想知道 | 看 |
|---|---|
| SSR 数据源接入现状（已闭环，历史记录） | [archive/ROADMAP_v3.3.0.md](archive/ROADMAP_v3.3.0.md) §8.8 |
| 两行改动背后的能力边界 | [PROJECT_FINAL_SUMMARY.md](PROJECT_FINAL_SUMMARY.md) |
| OSB / AR 的历史修复细节 | [ALGORITHM_FIX_LOG.md](ALGORITHM_FIX_LOG.md) FIX-ALG-022~029 |
| 配置项完整语义 | [PPP_Extra_Optimizations.md](PPP_Extra_Optimizations.md) |