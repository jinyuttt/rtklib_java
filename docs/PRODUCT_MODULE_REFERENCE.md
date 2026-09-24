# 精密产品下载模块技术参考

> **模块**：`rtklib-product`
> **核心类**：`org.rtklib.java.product.ProductDownloader`
> **功能**：IGS/MGEX精密产品自动下载，下载规则对齐PRIDE-PPPAR v3.2 `pdp3.sh`

---

## 1. 模块总览

ProductDownloader 是 IGS 精密产品（SP3/CLK/ERP/BIA/OBX/FCB/UPD/OSB/DCB/VMF3/GPT3）的自动下载器，支持 FTP/FTPS/HTTPS 多协议、多镜像源自动回退、本地缓存和 GZ 解压。

```
ProductDownloader
├── download(date, types)        按日期+产品类型下载
├── downloadFcb(date)            FCB宽窄巷偏差（PPP-AR）
├── downloadUpd(date)            UPD非校准相位延迟（PPP-AR）
├── downloadOsb(date)            OSB观测特定偏差（v2.3.0）
├── downloadDcb(date)            DCB差分码偏差
├── downloadVmf3Grid(date)       VMF3对流层映射函数网格
├── downloadGpt3Grid()           GPT3 5°网格（~5MB，静态表）
├── downloadAntex(name)          ANTEX天线相位中心改正
└── downloadTable(name)          静态表文件（leap.sec等）
```

---

## 2. 支持的产品类型

| ProductType | 说明 | 文件格式 | 存储目录 |
|-------------|------|----------|----------|
| `SP3` | 精密星历 | .SP3 | sp3/ |
| `CLK` | 精密钟差 | .CLK | clk/ |
| `ERP` | 地球自转参数 | .ERP | erp/ |
| `BIA` | 观测偏差（WUM） | .BIA | bia/ |
| `OBX` | 卫星姿态参数 | .OBX | obx/ |
| `FCB` | 宽窄巷FCB（WHU/CNES） | .FCB | fcb/ |
| `UPD` | 宽窄巷UPD（WHU） | .UPD | upd/ |
| `OSB` | 观测特定偏差（CAS） | .BIA | osb/ |
| `DCB` | 差分码偏差（CODE/CAS） | .BSX/.DCB | dcb/ |
| `VMF3` | VMF3映射函数系数 | .OP | vmf3/ |
| `GPT3` | GPT3网格数据 | .dat | gpt3/ |

---

## 3. URL优先级与镜像源

下载规则完全对齐 PRIDE-PPPAR `pdp3.sh PrepareProducts()`，按以下优先级依次尝试：

| 优先级 | 源 | 协议 | 说明 |
|--------|-----|------|------|
| 1 | bdspride.com/wum/ | FTPS | PRIDE主源，WUM产品 |
| 2 | igs.ign.fr | FTP | IGS官方MGEX |
| 3 | igs.gnsswhu.cn | FTP | 武汉大学镜像 |
| 4 | bdspride.com/wcc/ | FTPS | WCC产品回退 |
| 5 | igs.gnsswhu.cn (IGS2/COD) | FTP | 最终产品回退 |
| 6 | igs.gnsswhu.cn (RTS) | FTP | 实时产品回退（近3天） |

DCB 额外源：`ftp.aiub.unibe.ch/CODE/`（CODE/AIUB）

VMF3 额外源：`vmf.geo.tuwien.ac.at`（Vienna TU，HTTPS）

ANTEX 额外源：`files.igs.org`（IGS官方，HTTPS）

---

## 4. 缓存目录结构

```
product/
├── sp3/        WUM0MGXRAP_YYYYDDD0000_01D_05M_ORB.SP3
├── clk/        WUM0MGXRAP_YYYYDDD0000_01D_30S_CLK.CLK
├── erp/        WUM0MGXRAP_YYYYDDD0000_01D_01D_ERP.ERP
├── bia/        WUM0MGXRAP_YYYYDDD0000_01D_01D_OSB.BIA
├── obx/        WUM0MGXRAP_YYYYDDD0000_01D_30S_ATT.OBX
├── fcb/        WUM0MGXRTS_WWWW0_01D_05M_FCB.FCB
├── upd/        YYYYDDD0.UPD
├── osb/        CAS0MGXRTS_WWWW0_01D_01D_OSB.BIA
├── dcb/        CAS0MGXRTS_YYYYDDD0_01D_01D_DCB.BSX
├── vmf3/       VMF3_YYYYMMDD.HHH
├── gpt3/       gpt3_5deg.dat
└── table/      igs20_2317.atx / leap.sec / sat_parameters.txt
```

---

## 5. 使用方法

### 5.1 基本下载

```java
ProductDownloader dl = new ProductDownloader("./product");

// 下载单日SP3+CLK+ERP
DownloadResult result = dl.download(
    LocalDate.of(2024, 1, 15),
    ProductType.SP3, ProductType.CLK, ProductType.ERP
);

for (ProductFile f : result.files) {
    System.out.println(f);  // SP3: ./product/sp3/WUM0MGXRAP_20240150000_01D_05M_ORB.SP3 (downloaded)
}
```

### 5.2 日期范围下载

```java
// 下载2024-01-15到2024-01-17的全部产品类型
DownloadResult result = dl.download(
    LocalDate.of(2024, 1, 15),
    LocalDate.of(2024, 1, 17)
);
```

### 5.3 PPP-AR专用产品

```java
// FCB（宽窄巷偏差，用于PPP-AR模糊度固定）
String fcbPath = dl.downloadFcb(LocalDate.of(2024, 1, 15));

// UPD（非校准相位延迟）
String updPath = dl.downloadUpd(LocalDate.of(2024, 1, 15));
```

### 5.4 对流层产品

```java
// VMF3映射函数网格（6h间隔，自动合并）
String vmf3Path = dl.downloadVmf3Grid(LocalDate.of(2024, 1, 15));

// GPT3 5°网格（~5MB静态表，只需下载一次）
String gpt3Path = dl.downloadGpt3Grid();
```

### 5.5 偏差产品

```java
// OSB（观测特定偏差，CAS/MGEX）
String osbPath = dl.downloadOsb(LocalDate.of(2024, 1, 15));

// DCB（差分码偏差，CODE/CAS）
String dcbPath = dl.downloadDcb(LocalDate.of(2024, 1, 15));
```

### 5.6 静态表和ANTEX

```java
// 闰秒表
String leapPath = dl.downloadTable("leap.sec");

// ANTEX天线相位中心改正文件
String atxPath = dl.downloadAntex("igs20_2317.atx");
```

### 5.7 离线模式

```java
// 仅使用本地缓存，不发起网络请求
ProductDownloader dl = new ProductDownloader("./product", true, true);
DownloadResult result = dl.download(date, ProductType.SP3);
// result.files 仅包含已缓存的文件
```

### 5.8 自定义镜像源

```java
ProductDownloader dl = new ProductDownloader(
    "./product", true, false,
    true,                          // useRts
    "ftps://custom1.com",         // bdspride
    "ftp://custom2.com",          // ign
    "ftp://custom3.com"           // whu
);
```

---

## 6. 构造参数

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `cacheDir` | `user.dir/product` | 缓存根目录 |
| `useCache` | `true` | 启用本地缓存（已存在且非空则跳过下载） |
| `offline` | `false` | 离线模式，仅使用缓存 |
| `useRts` | `true` | 启用RTS实时产品回退（近3天） |
| `urlBdspride` | `ftps://bdspride.com` | PRIDE主源URL |
| `urlIgn` | `ftp://igs.ign.fr` | IGS官方URL |
| `urlWhu` | `ftp://igs.gnsswhu.cn` | 武汉大学镜像URL |

---

## 7. 时间系统

所有日期/时间基于 **UTC**：

- 输入 `LocalDate` 视为 UTC 日期
- DOY（年积日）按 UTC 日计算
- GPS 周从 1980-01-06(UTC) 起算
- IGS 产品文件名中的 YYYYDDD 均为 UTC 日期

**北京时间→UTC日期映射**（`PppProductIntegrationTest` 使用）：

```
北京文件夹 2026-07-01:
  0~7.rtcm3  → UTC 2026-06-30 16:00~23:59  → 需UTC 06-30的产品
  8~23.rtcm3 → UTC 2026-07-01 00:00~15:59  → 需UTC 07-01的产品
```

因此一个北京日期的文件夹需要下载**两个UTC日期**的精密产品：`folderDate - 1天` 和 `folderDate`。

---

## 8. 协议支持

| 协议 | 实现方式 | 说明 |
|------|----------|------|
| FTPS | Apache Commons Net `FTPSClient` | 匿名登录，PBSZ/PROT加密 |
| FTP | Apache Commons Net `FTPClient` | 匿名登录，被动模式 |
| HTTP/HTTPS | `HttpURLConnection` | 支持重定向，3次重试 |

GZ 解压：下载后自动检测 `.gz` 后缀并解压，删除压缩包保留解压文件。

---

## 9. 与PPP处理集成

`PppProductIntegrationTest` 演示了完整的产品下载+PPP处理流程：

1. 按北京日期文件夹确定UTC日期范围
2. 下载对应UTC日期的SP3/CLK/ERP/BIA等精密产品
3. 加载RTCM观测数据（基站+流动站）
4. 配置PppProcessor使用下载的精密产品
5. 执行PPP解算并验证定位精度

---

## 10. 命令行使用

```bash
java org.rtklib.java.product.ProductDownloader [cache-dir] <yyyy-mm-dd> [yyyy-mm-dd] [SP3|CLK|ERP|BIA|OBX|TABLE|VMF3|ANTEX]...

# 示例：下载2024-01-15的SP3+CLK+ERP到./product目录
java org.rtklib.java.product.ProductDownloader ./product 2024-01-15 2024-01-15 SP3 CLK ERP
```