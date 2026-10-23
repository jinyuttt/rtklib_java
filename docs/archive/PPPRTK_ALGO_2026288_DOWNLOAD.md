# PPP-AR/PPP-RTK 测试数据下载清单（ALGO 2026-10-15，DOY 288）

- 日期基准：2026-10-15（DOY 288，星期四），GPS week 2440 day 4（Regina 检索码 `24404`）
- 历元口径：30s × 2880 历元，与既有 PPP-AR 基线（ALGO 2024 DOY 060）一致
- 产品源：CNES ppp-wizard POST_PROCESSED 归档（GBM 快速产品，免注册）

## 1. CNES post 包（核心产品，已就绪）

| 项 | 状态 |
|---|---|
| `post_2026288.tgz`（49MB） | **已下载验证**（`%TEMP%\post288\`，正式执行时移至 data/test-data/） |

包内含（全部无需另行下载）：

| 文件 | 用途 | 读取器 |
|---|---|---|
| `GBM0MGXRAP_20262880000_01D_05M_ORB.SP3` | 精密轨道 5min | Sp3Reader |
| `GBM0MGXRAP_20262880000_01D_30S_CLK.CLK` | 精密钟差 30s | ClkReader |
| `GBM0MGXRAP_20262880000_01D_30S_ABS.BIA` | OSB 码+相位偏差 30s（G32星全相位/R仅码/E24星/C28星） | DcbReader/FcbReader |
| `BRDM00DLR_S_20262880000_01D_MN.rnx` | 全系统广播星历 | RinexParser |
| `GBM0MGXRAP_..._ATT.OBX`、`igs20_2437.atx` | 姿态 / ANTEX | PcvReader |

重复下载命令：`curl -O http://www.ppp-wizard.net/products/POST_PROCESSED/post_2026288.tgz`

## 2. ALGO RINEX 观测（CDDIS，需 Earthdata 认证）

- 凭据：`C:\Users\Admin\.rtklib\credentials.properties`（已配置，禁止入库）
- URL：`https://cddis.nasa.gov/archive/gnss/data/daily/2026/288/26d/ALGO00CAN_R_20262880000_01D_30S_MO.crx.gz`
- 下载命令（.netrc 或 `-u user:pass`）：`curl -n -O <URL>`
- 后处理：Hatanaka 解压（crx2rnx）→ `ALGO...30S_MO.obs`
- 备选采样：30S 缺失时用 `15S`（历元口径相应调整为 2880×2，需注明）

## 3. 参考坐标（IGS20，同日解）

- 首选 UNR/NGL tenv3：`https://geodesy.unr.edu/gps_timeseries/tenv3/IGS14/ALGO.tenv3`
  **注意**：2026-10-22 实测 302 → `oasisvm2.rd.unr.edu` DNS 解析失败，站点可能迁移，执行时用浏览器核实新路径
- 备选 1：IGS 周解坐标 SINEX `https://files.igs.org/pub/station/coord/IGS20/`（取对应 GPS 周文件）
- 备选 2：CDDIS `gnss/products/sinex/`（与观测同 Earthdata 认证）
- 惯例（FIX-ALG-019）：使用观测历元同日解坐标，禁止使用旧历元硬编码坐标

## 4. 电离层平静核实（Kp ≤ 3）

- GFZ 年表：`https://kp.gfz-potsdam.de/app/files/Kp_ap_apx_2026.txt`（2026-10-22 实测超时，执行时重试；行格式 `2026 10 15 ...`）
- SWPC 仅提供最近 6 小时实时 Kp，不覆盖历史
- 若 DOY 288 当日 Kp>4：候选顺延 DOY 287（10-14）或改用 post 包后续新归档日

## 5. 测试接线要点（仅方案，不改代码）

1. BIA OSB 为 **30s 时变序列**——需核查 `DcbReader/FcbReader` 现有 OSB 加载是常量取值还是按历元时间取值；时变支持为接线关键核查点
2. AR 配置复现 FIX-ALG-022~025 体系：IFLC PPP 浮点 + `enablePppAR` + OSB 门（完备性/maxdev/maxsig）+ NL 弧段机制
3. 对照基线：WUM 产品（既有 PppArValidationTest）与 GBM 产品各跑一轮，验证产品源无关性
4. 数据落位：ALGO 为 IGS 公开数据，允许进入 `data/test-data/`；凭据文件不入库
