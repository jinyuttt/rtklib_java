# IGS精密产品下载源与存储路径

> 对齐PRIDE-PPPAR v3.2 pdp3.sh，最后更新：2026-11-16（按代码复核，见 §7 文档与代码差异记录）

## 1. 服务器地址

| 编号 | 服务器 | 协议 | 地址 | 说明 |
|------|--------|------|------|------|
| S1 | PRIDE-BDS | FTPS | `ftps://bdspride.com` | PRIDE主源，匿名FTPS |
| S2 | IGS-IGN | FTP | `ftp://igs.ign.fr` | IGS法国节点 |
| S3 | WHU | FTP | `ftp://igs.gnsswhu.cn` | 武汉大学镜像 |
| S4 | CODE-AIUB | FTP | `ftp://ftp.aiub.unibe.ch` | 瑞士伯尔尼大学(CODE分析中心) |
| S5 | TU-Wien | HTTPS | `https://vmf.geo.tuwien.ac.at` | 维也纳理工大学(VMF3/GPT3) |
| S6 | IGS-Files | HTTPS | `https://files.igs.org` | IGS官方文件服务器(ANTEX) |
| S7 | CDDIS | HTTPS | `https://cddis.nasa.gov` | NASA CDDIS，需 Earthdata 凭据（见 §1.1），仅 OSB/DCB 候选 |

### 1.1 CDDIS 凭据

`CredentialManager` 按以下优先级查找 `credentials.properties`：

1. 系统属性 `-Drtklib.credentials=/path/to/credentials.properties`
2. 环境变量 `RTKLIB_CREDENTIALS`
3. `~/.rtklib/credentials.properties`
4. 当前目录 `./credentials.properties`

文件格式：`cddis.username=...` / `cddis.password=...`。未配置时 CDDIS 源自动跳过（不报错）。
该文件已在 `.gitignore` 中，禁止入库。

## 2. 本地缓存目录结构

```
product/
├── sp3/        精密星历
├── clk/        精密钟差
├── erp/        地球自转参数
├── bia/        观测偏差(Bias-SINEX)
├── obx/        姿态参数
├── fcb/        宽窄巷FCB/OSB(PPP-AR)
├── upd/        宽窄巷UPD(PPP-AR)
├── osb/        观测特定偏差(CAS)
├── dcb/        差分码偏差(CODE/CAS)
├── vmf3/       VMF3映射函数系数 + 合并文件 vmf_YYYYDDD
├── gpt3/       GPT3网格数据
├── table/      静态表(ANTEX/leap.sec/sat_parameters)
└── common/     通用目录(getCommonDir()，当前仅测试使用)
```

> `blq/` 不在此目录树：`ProductType` 无 BLQ，海潮负荷由 rtklib-otl 生成，见
> [docs/OTL_MODULE_REFERENCE.md](../docs/OTL_MODULE_REFERENCE.md)。
> `table/` 也无对应 `ProductType`，由 `downloadTable()` / `downloadAntex()` 直接写入。

## 3. 各产品详细说明

> **候选顺序中的两个开关**（详见 §7 7.1 / 7.2）：
> - SP3/CLK/ERP/BIA 的第 6 候选（WUM RTS 实时产品）由构造参数 `useRts` 控制，默认 `true`；
>   关闭后该候选不参与尝试。
> - OSB/DCB 在配置 CDDIS 凭据（§1.1）时追加 CDDIS 候选，未配置时整段跳过。

### 3.1 SP3 精密星历

| 项目 | 内容 |
|------|------|
| 本地目录 | `product/sp3/` |
| 本地文件名 | `WUM0MGXRAP_YYYYDDD0000_01D_05M_ORB.SP3` |
| 用途 | 精密卫星轨道，替代广播星历 |
| 读取类 | `Sp3Reader` → `nav.peph` |

**候选URL（按优先级）**：

| 优先级 | URL模式 | 服务器 |
|--------|---------|--------|
| 1 | `ftps://bdspride.com/wum/{WWWW}/WUM0MGXRAP_{YYYY}{DDD}0000_01D_05M_ORB.SP3.gz` | S1 |
| 2 | `ftp://igs.ign.fr/pub/igs/products/mgex/{WWWW}/WUM0MGXRAP_{YYYY}{DDD}0000_01D_05M_ORB.SP3.gz` | S2 |
| 3 | `ftp://igs.gnsswhu.cn/pub/whu/phasebias/{YYYY}/orbit/WUM0MGXRAP_{YYYY}{DDD}0000_01D_05M_ORB.SP3.gz` | S3 |
| 4 | `ftps://bdspride.com/wcc/{WWWW}/WCC0OPSRAP_{YYYY}{DDD}0000_01D_05M_ORB.SP3.gz` | S1(WCC回退) |
| 5 | `ftp://igs.gnsswhu.cn/pub/whu/phasebias/{YYYY}/orbit/IGS2R03FIN_{YYYY}{DDD}0000_01D_05M_ORB.SP3.gz` | S3(IGS2最终) |
| 6 | `ftp://igs.gnsswhu.cn/pub/whu/phasebias/{YYYY}/orbit/WUM0MGXRTS_{YYYY}{DDD}0000_01D_05M_ORB.SP3.gz` | S3(RTS实时) |

> `{WWWW}` = GPS周，`{YYYY}` = 年，`{DDD}` = 年积日(DOY)

---

### 3.2 CLK 精密钟差

| 项目 | 内容 |
|------|------|
| 本地目录 | `product/clk/` |
| 本地文件名 | `WUM0MGXRAP_YYYYDDD0000_01D_30S_CLK.CLK` |
| 用途 | 精密卫星钟差，替代广播钟差 |
| 读取类 | `ClkReader` → `nav.pclk` |

**候选URL（按优先级）**：

| 优先级 | URL模式 | 服务器 |
|--------|---------|--------|
| 1 | `ftps://bdspride.com/wum/{WWWW}/WUM0MGXRAP_{YYYY}{DDD}0000_01D_30S_CLK.CLK.gz` | S1 |
| 2 | `ftp://igs.ign.fr/pub/igs/products/mgex/{WWWW}/WUM0MGXRAP_{YYYY}{DDD}0000_01D_30S_CLK.CLK.gz` | S2 |
| 3 | `ftp://igs.gnsswhu.cn/pub/whu/phasebias/{YYYY}/clock/WUM0MGXRAP_{YYYY}{DDD}0000_01D_30S_CLK.CLK.gz` | S3 |
| 4 | `ftps://bdspride.com/wcc/{WWWW}/WCC0OPSRAP_{YYYY}{DDD}0000_01D_30S_CLK.CLK.gz` | S1(WCC回退) |
| 5 | `ftp://igs.gnsswhu.cn/pub/whu/phasebias/{YYYY}/clock/IGS2R03FIN_{YYYY}{DDD}0000_01D_30S_CLK.CLK.gz` | S3(IGS2最终) |
| 6 | `ftp://igs.gnsswhu.cn/pub/whu/phasebias/{YYYY}/clock/WUM0MGXRTS_{YYYY}{DDD}0000_01D_05S_CLK.CLK.gz` | S3(RTS实时) |

---

### 3.3 ERP 地球自转参数

| 项目 | 内容 |
|------|------|
| 本地目录 | `product/erp/` |
| 本地文件名 | `WUM0MGXRAP_YYYYDDD0000_01D_01D_ERP.ERP` |
| 用途 | 极移(xp,yp)、UT1-UTC、LOD，供IERS2010潮汐改正使用 |
| 读取类 | `ErpReader` → `nav.erp` |

**候选URL（按优先级）**：

| 优先级 | URL模式 | 服务器 |
|--------|---------|--------|
| 1 | `ftps://bdspride.com/wum/{WWWW}/WUM0MGXRAP_{YYYY}{DDD}0000_01D_01D_ERP.ERP.gz` | S1 |
| 2 | `ftp://igs.ign.fr/pub/igs/products/mgex/{WWWW}/WUM0MGXRAP_{YYYY}{DDD}0000_01D_01D_ERP.ERP.gz` | S2 |
| 3 | `ftp://igs.gnsswhu.cn/pub/whu/phasebias/{YYYY}/orbit/WUM0MGXRAP_{YYYY}{DDD}0000_01D_01D_ERP.ERP.gz` | S3 |
| 4 | `ftps://bdspride.com/wcc/{WWWW}/WCC0OPSRAP_{YYYY}{DDD}0000_01D_01D_ERP.ERP.gz` | S1(WCC回退) |
| 5 | `ftp://igs.gnsswhu.cn/pub/whu/phasebias/{YYYY}/orbit/COD0R03FIN_{YYYY}{DDD}0000_01D_01D_ERP.ERP.gz` | S3(CODE最终) |
| 6 | `ftp://igs.gnsswhu.cn/pub/whu/phasebias/{YYYY}/orbit/WUM0MGXRTS_{YYYY}{DDD}0000_01D_01D_ERP.ERP.gz` | S3(RTS实时) |

---

### 3.4 BIA 观测偏差(Bias-SINEX)

| 项目 | 内容 |
|------|------|
| 本地目录 | `product/bia/` |
| 本地文件名 | `WUM0MGXRAP_YYYYDDD0000_01D_01D_OSB.BIA` |
| 用途 | OSB/DSB偏差，供伪距偏差改正和PPP-AR使用 |
| 读取类 | `OsbReader` → `nav.fcbWl` + `nav.cbias` |

**候选URL（按优先级）**：

| 优先级 | URL模式 | 服务器 |
|--------|---------|--------|
| 1 | `ftps://bdspride.com/wum/{WWWW}/WUM0MGXRAP_{YYYY}{DDD}0000_01D_01D_OSB.BIA.gz` | S1 |
| 2 | `ftp://igs.ign.fr/pub/igs/products/mgex/{WWWW}/WUM0MGXRAP_{YYYY}{DDD}0000_01D_01D_OSB.BIA.gz` | S2 |
| 3 | `ftp://igs.gnsswhu.cn/pub/whu/phasebias/{YYYY}/bias/WUM0MGXRAP_{YYYY}{DDD}0000_01D_01D_OSB.BIA.gz` | S3 |
| 4 | `ftps://bdspride.com/wcc/{WWWW}/WCC0OPSRAP_{YYYY}{DDD}0000_01D_01D_OSB.BIA.gz` | S1(WCC回退) |
| 5 | `ftp://igs.gnsswhu.cn/pub/whu/phasebias/{YYYY}/bias/IGS2R03FIN_{YYYY}{DDD}0000_01D_01D_OSB.BIA.gz` | S3(IGS2最终) |
| 6 | `ftp://igs.gnsswhu.cn/pub/whu/phasebias/{YYYY}/bias/WUM0MGXRTS_{YYYY}{DDD}0000_01D_05M_OSB.BIA.gz` | S3(RTS实时) |

---

### 3.5 OBX 姿态参数

| 项目 | 内容 |
|------|------|
| 本地目录 | `product/obx/` |
| 本地文件名 | `WUM0MGXRAP_YYYYDDD0000_01D_30S_ATT.OBX` |
| 用途 | 卫星姿态(偏航姿态)，BDS-3卫星需要 |
| 读取类 | — |

**候选URL（按优先级）**：

| 优先级 | URL模式 | 服务器 |
|--------|---------|--------|
| 1 | `ftps://bdspride.com/wum/{WWWW}/WUM0MGXRAP_{YYYY}{DDD}0000_01D_30S_ATT.OBX.gz` | S1 |
| 2 | `ftp://igs.ign.fr/pub/igs/products/mgex/{WWWW}/WUM0MGXRAP_{YYYY}{DDD}0000_01D_30S_ATT.OBX.gz` | S2 |
| 3 | `ftp://igs.gnsswhu.cn/pub/whu/phasebias/{YYYY}/orbit/WUM0MGXRAP_{YYYY}{DDD}0000_01D_30S_ATT.OBX.gz` | S3 |
| 4 | `ftps://bdspride.com/wcc/{WWWW}/WCC0OPSRAP_{YYYY}{DDD}0000_01D_30S_ATT.OBX.gz` | S1(WCC回退) |
| 5 | `ftp://igs.gnsswhu.cn/pub/whu/phasebias/{YYYY}/orbit/IGS2R03FIN_{YYYY}{DDD}0000_01D_30S_ATT.OBX.gz` | S3(IGS2最终) |

---

### 3.6 FCB/OSB 模糊度固定产品(PPP-AR)

| 项目 | 内容 |
|------|------|
| 本地目录 | `product/fcb/` |
| 本地文件名 | 与命中的远端文件名相同（去掉 `.gz`），不重命名 |
| 用途 | WL/NL模糊度固定，PPP-AR核心产品 |
| 读取类 | `OsbReader` → `nav.fcbWl` |

**候选文件名（按优先级）**：

| 优先级 | 文件名模式 | 说明 |
|--------|-----------|------|
| 1 | `WUM0MGXRTS_{YYYY}{DDD}0000_01D_05M_OSB.BIA` | WUM RTS OSB(Bias-SINEX，11位日期) |
| 2 | `WUM0MGXRAP_{YYYY}{DDD}0000_01D_01D_OSB.BIA` | WUM RAP OSB(Bias-SINEX，11位日期，01D) |
| 3 | `CAS0MGXRTS_{YYYY}{DDD}0000_01D_05M_OSB.BIA` | CAS RTS OSB(Bias-SINEX，11位日期) |
| 4 | `WUM0MGXRTS_{YYYY}{DDD}00000_01D_05M_OSB.BIA` | WUM RTS OSB(12位填充，非标准回退探测) |
| 5 | `WUM0MGXRAP_{YYYY}{DDD}00000_01D_01D_OSB.BIA` | WUM RAP OSB(12位填充，非标准回退探测，01D) |
| 6 | `WUM0MGXRTS_{YYYY}{DDD}000_01D_05M_OSB.BIA` | WUM RTS OSB(10位填充，非标准回退探测) |
| 7 | `WUM0MGXRAP_{YYYY}{DDD}000_01D_01D_OSB.BIA` | WUM RAP OSB(10位填充，非标准回退探测，01D) |
| 8 | `WUM0MGXRTS_{WWWW}0_01D_05M_FCB.FCB` | WUM FCB(旧版，GPS周命名) |
| 9 | `CAS0MGXRTS_{WWWW}0_01D_05M_FCB.FCB` | CAS FCB(旧版，GPS周命名) |
| 10 | `{YYYY}{DDD}0.FCB` | 简化命名FCB |

**候选URL（每个文件名尝试以下服务器）**：

| 优先级 | URL模式 | 服务器 |
|--------|---------|--------|
| 1 | `ftps://bdspride.com/wum/{WWWW}/{FILENAME}` | S1 |
| 2 | `ftp://igs.gnsswhu.cn/pub/whu/phasebias/{YYYY}/bias/{FILENAME}` | S3 |
| 3 | `ftp://igs.ign.fr/pub/igs/products/mgex/{WWWW}/{FILENAME}` | S2 |

---

### 3.7 UPD 模糊度固定产品(PPP-AR)

| 项目 | 内容 |
|------|------|
| 本地目录 | `product/upd/` |
| 用途 | 窄巷UPD，PPP-AR备选产品 |
| 读取类 | — |

**候选文件名（按优先级）**：

| 优先级 | 文件名模式 | 说明 |
|--------|-----------|------|
| 1 | `{YYYY}{DDD}0.UPD` | 简化命名 |
| 2 | `WUM0MGXFIN_{WWWW}0_01D_15M_UPD.UPD` | WUM最终产品 |

**候选URL（每个文件名尝试以下服务器）**：

| 优先级 | URL模式 | 服务器 |
|--------|---------|--------|
| 1 | `ftps://bdspride.com/wum/{WWWW}/{FILENAME}` | S1 |
| 2 | `ftp://igs.gnsswhu.cn/pub/whu/phasebias/{YYYY}/{BUCKET}/{FILENAME}` | S3(分桶目录，简化命名) |
| 3 | `ftp://igs.gnsswhu.cn/pub/whu/phasebias/{YYYY}/bias/{FILENAME}` | S3(WUM周命名回退) |

> WHU 分桶目录：BUCKET = 年积日/50+1（整数除法），与代码 `doy/50+1` 一致；
> 分桶路径用于简化命名 `{YYYY}{DDD}0.UPD`，`/bias/` 路径用于 WUM 周命名。

---

### 3.8 OSB 观测特定偏差(CAS)

| 项目 | 内容 |
|------|------|
| 本地目录 | `product/osb/` |
| 用途 | CAS分析中心OSB偏差模型 |
| 读取类 | `OsbReader` → `nav.fcbWl` + `nav.cbias` |

**候选文件名（按优先级）**：

| 优先级 | 文件名模式 | 说明 |
|--------|-----------|------|
| 1 | `CAS0MGXRTS_{WWWW}0_01D_01D_OSB.BIA` | CAS RTS(GPS周命名) |
| 2 | `{YYYY}{DDD}0.BIA` | 简化命名 |

**候选URL（每个文件名尝试以下服务器）**：

| 优先级 | URL模式 | 服务器 |
|--------|---------|--------|
| 1 | `ftp://igs.ign.fr/pub/igs/products/mgex/{WWWW}/{FILENAME}` | S2 |
| 2 | `ftp://igs.gnsswhu.cn/pub/whu/phasebias/{YYYY}/{BUCKET}/{FILENAME}` | S3(分桶目录) |

> WHU 分桶目录：BUCKET = 年积日/50+16（整数除法），与代码 `doy/50+16` 一致。

---

### 3.9 DCB 差分码偏差

| 项目 | 内容 |
|------|------|
| 本地目录 | `product/dcb/` |
| 本地保存名 | `CAS0MGXRTS_{YYYY}{DDD}0_01D_01D_DCB.BSX`（统一重命名） |
| 用途 | 伪距偏差改正(P1-C1, P1-P2等) |
| 读取类 | `DcbReader` → `nav.cbias`（支持.BIA和.BSX格式） |

> **重要**：IGS已统一使用Bias-SINEX格式(.BIA)，传统.BSX和.DCB格式已逐步淘汰。
> 远端实际文件由GFZ分析中心提供：`GFZ0OPSRAP_{YYYY}{DDD}0000_01D_01D_DCB.BIA.gz`

**远端候选文件名（按优先级）**：

| 优先级 | 远端文件名模式 | 说明 |
|--------|---------------|------|
| 1 | `CAS0MGXRAP_{YYYY}{DDD}0000_01D_01D_DCB.BSX` | CAS RAP(BSX，11位日期) |
| 2 | `GFZ0OPSRAP_{YYYY}{DDD}0000_01D_01D_DCB.BIA` | **GFZ RAP(BIA，11位日期，实际可用)** |
| 3 | `CAS0MGXRTS_{YYYY}{DDD}0000_01D_01D_DCB.BSX` | CAS RTS(BSX，11位日期) |
| 4 | `CAS0MGXRAP_{YYYY}{DDD}0000_01D_01D_DCB.BIA` | CAS RAP(BIA，11位日期) |
| 5 | `CODE_{YYYY}{DDD}0.DCB` | CODE传统DCB格式 |

**候选URL（每个远端文件名尝试以下服务器，先.gz后裸文件）**：

| 优先级 | URL模式 | 服务器 |
|--------|---------|--------|
| 1 | `ftp://igs.ign.fr/pub/igs/products/bias/{YYYY}/{FILENAME}.gz` | S2(bias目录) |
| 2 | `ftp://igs.ign.fr/pub/igs/products/mgex/{WWWW}/{FILENAME}.gz` | S2(mgex目录) |
| 3 | `ftp://igs.gnsswhu.cn/pub/whu/phasebias/{YYYY}/bias/{FILENAME}.gz` | S3 |
| 4 | `ftp://ftp.aiub.unibe.ch/CODE/{YYYY}/{FILENAME}.gz` | S4 |
| 5 | 裸文件回退：S2 `products/bias`、S2 `products/mgex`、S3 `bias`（**不含** S4 AIUB 裸文件） | 去掉`.gz`后缀 |
| 6 | 同上两条 CDDIS 路径（`/archive/gnss/products/bias/{YYYY}/`、`/archive/gnss/products/mgex/{WWWW}/`） | S7(仅裸文件，需凭据) |

> 下载后统一重命名为 `CAS0MGXRTS_{YYYY}{DDD}0_01D_01D_DCB.BSX`，与用户指定路径一致

---

### 3.10 VMF3 映射函数系数

| 项目 | 内容 |
|------|------|
| 本地目录 | `product/vmf3/` |
| 单文件名 | `VMF3_{YYYY}{MM}{DD}.H{HH}` |
| 合并文件名 | `vmf_{YYYY}{DDD}` |
| 用途 | VMF3映射函数系数，6h间隔，需合并前后3天共6个文件 |
| 读取类 | `Vmf3OpReader` → `nav.vmf3Op` |

**下载URL**：

| URL模式 | 服务器 |
|---------|--------|
| `https://vmf.geo.tuwien.ac.at/trop_products/GRID/1x1/VMF3/VMF3_OP/{YYYY}/VMF3_{YYYY}{MM}{DD}.H{HH}` | S5 |

**需要下载的时段**（以目标日为中心）：

| 日期 | 时刻 |
|------|------|
| 目标日-1 | 18:00 |
| 目标日 | 00:00, 06:00, 12:00, 18:00 |
| 目标日+1 | 00:00 |

---

### 3.11 GPT3 网格数据

| 项目 | 内容 |
|------|------|
| 本地目录 | `product/gpt3/` |
| 本地文件名 | `gpt3_5deg.dat` |
| 用途 | GPT3 5°×5°网格先验对流层参数，静态文件只需下载一次 |
| 读取类 | `Gpt3GridReader` → `nav.gpt3Grid` |
| 文件大小 | ~5 MB |

**候选URL（按优先级）**：

| 优先级 | URL模式 | 服务器 |
|--------|---------|--------|
| 1 | `https://vmf.geo.tuwien.ac.at/codes/gpt3_5.grd` | S5(HTTPS) |
| 2 | `ftps://bdspride.com/table/gpt3_5deg.dat` | S1 |
| 3 | `ftp://igs.gnsswhu.cn/pub/whu/phasebias/table/gpt3_5deg.dat` | S3 |

> 实际下载文件名可能为 `gpt3_5.grd`，需重命名为 `gpt3_5deg.dat`

---

### 3.12 BLQ 海潮负荷

| 项目 | 内容 |
|------|------|
| 本地目录 | `product/blq/` |
| 本地文件名 | `{站号}.blq`（如 `WTZR.blq`） |
| 用途 | 海潮负荷改正(IERS2010 tidecorr=7时需要) |
| 读取类 | `OtlReader` → `nav.otl` |
| 获取方式 | 需从在线服务按测站坐标生成，非自动下载 |

**获取途径**：

| URL | 说明 |
|-----|------|
| `https://geodesy.unr.edu/OceanLoading/` | UNR海潮负荷在线服务，输入测站坐标生成BLQ |
| `ftps://bdspride.com/table/{站号}.blq` | PRIDE预计算表(部分测站) |

---

### 3.13 ANTEX 天线相位中心改正

| 项目 | 内容 |
|------|------|
| 本地目录 | `product/table/` |
| 本地文件名 | `igs20_2317.atx` 等 |
| 用途 | 接收机/卫星天线相位中心改正(PCV/PCO) |
| 读取类 | — |

**候选URL（按优先级）**：

| 优先级 | URL模式 | 服务器 |
|--------|---------|--------|
| 1 | `https://files.igs.org/pub/station/general/{FILENAME}` | S6 |
| 2 | `https://files.igs.org/pub/station/general/pcv_archive/{FILENAME}` | S6(归档) |
| 3 | `https://files.igs.org/pub/station/general/pcv_archive/{FILENAME}.gz` | S6(压缩) |
| 4 | `ftps://bdspride.com/table/{FILENAME}` | S1 |
| 5 | `ftp://igs.gnsswhu.cn/pub/whu/phasebias/table/{FILENAME}` | S3 |

---

### 3.14 静态表文件

| 文件名 | 用途 | 候选URL |
|--------|------|---------|
| `leap.sec` | 闰秒表 | S1→S3: `bdspride.com/table/` → `whu/.../table/` |
| `sat_parameters` | 卫星参数( Block/质量/面积等) | S1→S3 |

## 4. 文件名格式说明

### IGS MGEX 文件名模板

```
{AC}{TYPE}{PROD}_{YYYYDDDHHMM}_{INT1}D_{INT2}{S}_{TYPE2}.{EXT}
```

| 字段 | 说明 | 示例 |
|------|------|------|
| AC | 分析中心 | WUM0, CAS0, COD0, IGS2 |
| TYPE | 产品类型 | MGX(多GNSS), R03(单GPS) |
| PROD | 产品时效 | RAP(快速), RTS(实时), FIN(最终) |
| YYYYDDDHHMM | 起始时刻（年+年积日+时+分） | 标准 11 位：`20261800000`（2026 年第 180 日 00:00）；实现另探测 12 位 / 10 位填充变体（见 §3.6），非 IGS 标准 |
| INT1 | 采样间隔(天) | 01 |
| INT2 | 采样间隔 | 05M(5分钟), 30S(30秒), 01D(1天) |
| S | 采样类型 | S(秒), M(分), D(天) |
| TYPE2 | 数据类型 | ORB, CLK, ERP, OSB, DCB, ATT |
| EXT | 扩展名 | SP3, CLK, ERP, BIA, BSX, OBX |

### 常见分析中心编号

| 编号 | 分析中心 | 国家 |
|------|---------|------|
| WUM | 武汉大学(WHU) | 中国 |
| CAS | 中科院 | 中国 |
| GFZ | 地学研究中心(GFZ) | 德国 |
| COD | CODE(伯尔尼) | 瑞士 |
| IGS | IGS组合 | 国际 |

## 5. 产品依赖的功能映射

| 功能 | 需要的产品 | 对应Nav字段 | 缺失时退化 |
|------|-----------|------------|-----------|
| 精密星历 | SP3 + CLK | `nav.peph`, `nav.pclk` | 退化到广播星历 |
| IERS2010潮汐改正 | ERP | `nav.erp` | 跳过潮汐改正 |
| GPT3+VMF3对流层 | GPT3 + VMF3 | `nav.gpt3Grid`, `nav.vmf3Op` | 退化到Saastamoinen+GMF |
| PPP-AR模糊度固定 | FCB/OSB/UPD | `nav.fcbWl`, `nav.fcbNl` | 浮点解 |
| 伪距偏差改正 | DCB/BIA | `nav.cbias` | 跳过DCB改正 |
| 海潮负荷 | BLQ | `nav.otl` | 跳过海潮负荷 |
| 天线相位中心 | ANTEX | `nav.pcv` | 跳过PCV改正 |

## 6. 北京时间与UTC日期转换

> **关键**：观测数据目录按北京时间(UTC+8)组织，精密产品按UTC日期发布。
> 下载产品时必须将北京时间转换为UTC日期。

**转换规则**：

| 北京时间日期 | UTC日期 | 说明 |
|-------------|---------|------|
| 2026-06-29 | 2026-06-28 | 北京时间00:00 = UTC前日16:00 |

**代码实现**：
```java
LocalDate obsDateBeijing = LocalDate.parse("2026-06-29");  // 观测数据目录日期
LocalDate obsDateUtc = obsDateBeijing.minusDays(1);         // 产品下载UTC日期
```

> 北京时间00:00~16:00对应UTC前日16:00~当日00:00，因此产品日期为北京时间日期减1天。
> 对于跨日观测（北京时间16:00后），可能需要同时下载UTC当日和次日产品。

## 7. 文档与代码差异记录

> 复核基准：`ProductDownloader.java`（v3.5.0，2026-11-16，HEAD `adb0d4b`）。
> 本节只记录**已核实**的文档与代码不一致项，编号 7.* 供后续引用。
> "已修正"表示正文对应小节已按代码同步；"备案"表示代码未改，仅在此说明以免按文档误判。

| 编号 | 位置 | 文档原表述 | 代码事实 | 处置 |
|------|------|-----------|---------|------|
| 7.1 | §3.1~3.4 | 第 6 优先级（WUM RTS）按无条件候选列出 | `generateUrls()` 中 SP3/CLK/ERP/BIA 的 RTS 候选包在 `if (useRts)`；默认构造传 `useRts=true` | 已修正：§3 开头补开关说明 |
| 7.2 | §3.8 OSB | URL 表 2 条（S2 `/mgex/`、S3 分桶），未提 CDDIS | `downloadOsb()` 在有 CDDIS 凭据时追加第 3 条 `/archive/gnss/products/mgex/{WWWW}/{name}` | 已修正：§3 开头补注 |
| 7.3 | §3.9 DCB | 裸文件回退写为"同上路径去掉 `.gz`"，含 S4 AIUB；未提 CDDIS | 裸文件实际 3 条（S2 bias、S2 mgex、S3 bias），**不含** AIUB 裸文件；有凭据时另有 CDDIS 2 条（`bias`、`mgex`） | 已修正：§3.9 拆为第 5/6 行 |
| 7.4 | §3.6 FCB | 只列候选文件名，未写落盘名 | 命中后按远端原名去掉 `.gz` 保存，不重命名 | 已修正：§3.6 补"本地文件名"行 |
| 7.5 | §3.7 UPD | 候选 URL 3 条（S1 `/wum/`、S3 分桶、S3 `/bias/`） | 运行路径 `downloadProduct()` 走 `generateUrls()`，与文档一致；另有公开方法 `downloadUpd()` 仅 2 条（缺 `/bias/` 回退），全仓库无调用方 | 文档不改；`downloadUpd()` 备案为未使用 API |
| 7.6 | §3.6 / §3.8 / §3.9 / §3.10 | 落盘名以各小节"本地文件名/保存名"为准 | OSB/DCB/VMF3/FCB 走专用方法，不调用 `generateLocalFileName()`；该方法对应分支（OSB/DCB 为 `...{YYYY}{DDD}0000...`、VMF3 为 `VMF3_{YYYY}{DDD}.OP`、FCB 为 `..._05M_FCB.FCB`）与实际落盘不符，属遗留分支；`GPT3` 分支 `gpt3_5deg.dat` 与 `downloadGpt3Grid()` 落盘一致，不算遗留 | 文档以专用方法为准；遗留分支备案未改（改动牵动 `ProductDownloaderTest`） |
| 7.7 | §2 目录树 | 只列 `sp3/`~`table/` 共 11 个目录 | `getCommonDir()` 会创建 `product/common/`，当前仅 `ProductDownloaderTest#testCacheDirStructure` 使用 | 已修正：§2 树补 `common/` |
| 7.8 | §3.1~3.4（代码侧） | 代码 javadoc 称 RTS 为"近 3 天回退" | `generateUrls()` 无日期窗口判断，RTS 候选对所有日期都会尝试 | 备案：文档不采纳"近 3 天"表述，以此条为准 |
| 7.9 | §3.14（代码侧） | class javadoc 目录树写 `sat_parameters.txt` | 实际表名 `sat_parameters`（无后缀），`downloadTable()` 按传入名直存 | 已修正：javadoc 去掉后缀 |

> 7.5 / 7.6 / 7.8 属代码侧可改进项，本次仅备案不改动，避免在 3.x「仅接受 bugfix」阶段引入行为变化。