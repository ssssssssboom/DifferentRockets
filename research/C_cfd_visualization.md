# 方向 C：羽流 CFD / MOC 计算方法与科学可视化渲染惯例

> 调研目的：为 `D:\DifferentRockets` 的二维拉瓦尔喷管尾焰激波波系动画（plume_core.py）提供物理依据。
> 当前实现把激波画成"细亮线 + zig-zag 几何映射"，用户质疑其物理真实性。本报告给出：(1) 特征线法（MOC）自由射流求解流程；(2) CFD 羽流论文的标准可视化量；(3) 海平面 vs 真空羽流形态差异；(4) 过膨胀喷管 FSS/RSS 分离；(5) 对渲染代码的可执行改进建议。

---

## 1. 特征线法（MOC）自由射流边界求解流程

### 1.1 欠膨胀射流的物理结构（教科书共识）

轴对称喷管出口压力 p_e ≠ 环境背压 p_a 时，喷口唇缘出现 **Prandtl–Meyer 膨胀扇**：

- **欠膨胀（p_e > p_a，真空/高空试车即此情形）**：唇缘发出膨胀扇，把气流压力降到 p_a；射流边界是一条**等压自由边界**（p = p_a 恒定，速度不连续），膨胀波打到边界上**反射为压缩波**，压缩波聚合形成"拦截激波（intercepting/barrel shock）"。激波在轴线上：低 NPR 时规则反射（激波胞格菱形，shock diamonds），高 NPR 时转为 Mach 反射形成 **Mach 盘（Mach disk）**。该胞格结构准周期重复，直到湍流混合层把核区耗散完为止。
- **过膨胀（p_e < p_a，海平面大喷管即此情形）**：唇缘直接发出斜激波系统，n = p_e/p_a ≈ 0.4–0.8 时壁面边界层分离（见 §4）。
- **完全适配（p_e = p_a）**：仍有弱波系——从受限区进入无限空间时气流先弱膨胀再弱压缩，只是波很弱、视觉上看不到激波胞格。

**出处（独立来源 ×3）**：
- [S1] Carpenter, T. W., 1991, *Analytical Prediction of the Expansion/Oblique Shock Wave Structure of an Axisymmetric Jet Exhausting into Still Air*, NASA NTRS 19940006457（Cal Poly 报告，含 MOC 与 Adamson–Nicholls 边界积分法对比、离散膨胀波近似全流程）。https://ntrs.nasa.gov/api/citations/19940006457/downloads/19940006457.pdf
- [S2] Shaw, P. 等（IIT Bombay），2017, *Parametric Study of Underexpanded Supersonic Jets*, NAPC-2017-139："射流边界上压力恒定、速度不连续；膨胀波在自由边界反射为压缩波并聚合成拦截激波；NPR 增大时规则反射转为 Mach 反射"。https://www.hypersonic-cfd.com/Inhouse_papers/2017/NAPC_Pratik.pdf
- [S3] Henry, R., *Prediction of Broadband Shock-Associated Noise*（博士论文，EC Lyon），图 1.6 给出膨胀扇（蓝）/压缩激波（红）在自由边界上的交替反射模型。https://acoustique.ec-lyon.fr/publi/henry_thesis.pdf

### 1.2 MOC 求解流程要点（可在简化模型里复刻）

1. 喷口出口均匀超声速气流（M_e, θ_e）为起始线。
2. 唇缘膨胀转角用 Prandtl–Meyer 函数：

   **ν(M) = √((γ+1)/(γ−1)) · atan(√((γ−1)/(γ+1)·(M²−1))) − atan(√(M²−1))**

   所需膨胀角 Δν 由等熵关系解出：p_e/p_a → M 在自由边界处的值。
3. 自由边界条件：**p = p_a**（等压），边界本身随流场迭代弯曲；膨胀波 → 边界反射为压缩波；压缩波 → 边界反射为膨胀波。
4. 压缩波包络线（coalescence）处判定激波生成；激波用斜激波关系（θ–β–M）局部插入。马赫反射判据：所需转角超过该 M 下斜激波最大转角 θ_max(M) 时出现 Mach 盘。
5. MOC 适用范围：Mach < ~10 且连续介质成立；真空羽流远场 Kn > 0.05 后 MOC/NS 失效，需 DSMC 或经验源流模型（见 §3）。[S4] Liaw, G. S., 1992, *Numerical Investigations in the Backflow Region of a Vacuum Plume*, NASA NTRS 19920021737。https://ntrs.nasa.gov/api/citations/19920021737/downloads/19920021737.pdf

### 1.3 可直接实现的简化公式（无需真跑 MOC）

**(a) 激波胞格长度**（Prandtl 线性涡层模型 / Pack 修正 / Tam）：

- Prandtl: **L_s ≈ 1.306 · D · √(M_j² − 1)**（M_j = 完全膨胀到 p_a 的射流马赫数）
- Pack: L_s ≈ 1.22 · D · √(M_j² − 1)

出处 [S3] Henry 论文式 (1.12)–(1.13)；NASA RP 1258 *Aeroacoustics of Flight Vehicles*（Hubbard 编）也收录 Tam 的 Bessel 级数解。两系数差 ~7%，取 1.2–1.3 均可。

**(b) Prandtl 主波长（Carpenter/Love–Grigsby 经验式）**：

- 无 Mach 盘（p_j/p_0 < 2）：**w/r_n = 3.1 · √(M_j²·(p_j/p_0) − 1)**
- 有 Mach 盘（p_j/p_0 > 2）：另有对应式（见 [S1]）。

**(c) 射流边界初始倾角**：唇缘处边界相对轴线的初始倾角 = ν(M_boundary) − ν(M_e)，其中 M_boundary 由 p_e/p_a 等熵解出（[S2] 图 2 即此曲线）。

**(d) Mach 盘出现条件**：p_j/p_a ≳ 3–4（经验，γ=1.4 空气）；盘径随 NPR 增大。来源 [S1][S2] 一致。

---

## 2. CFD 羽流论文中的标准可视化量

### 2.1 数值纹影（numerical schlieren）—— 事实标准

- **定义**：直接画密度梯度场，而不是密度场本身。常用 |∇ρ|（梯度幅值）或沿视线的 ∇ρ·k（k 为刀口敏感方向）。Settles 在其权威专著/综述中明确指出："展示密度导数场而非密度本身，已成为 CFD 后处理中不言自明的惯例，始于 Yates（1993 前后），大量作者直接用计算纹影与实验纹影对比做验证。"
  出处 [S5] Settles, G. S., 2017, *Schlieren and Shadowgraph Techniques in the 21st Century*（及同名 Springer 专著），图 11–12 给出 CFD 超声速射流的 bright-field / 竖直刀口 / 水平刀口纹影与 shadowgraph 渲染对比。
- **亮度映射惯例**：并非线性。Dauptain 等的冲击射流计算纹影图用 0–1000 / 0–100 / 0–10 kg·m⁻⁴ 三档量程对比，说明**梯度动态范围极大，必须做非线性压缩**。常用做法：
  - 对数映射：I = log(1 + k·|∇ρ|/|∇ρ|_ref)
  - 指数衰减映射（Dauptain 式）：I = exp(−k·|∇ρ|/max|∇ρ|)，暗底亮结构
  - 刀口方向性：|∂ρ/∂y|（只显示横向往复结构，激波清晰、膨胀扇柔和）
- NASA 实例：[S6] Drozda, T. G. 等, 2017, *Comparisons Between NO PLIF Imaging and CFD...*, NASA/TM—2018（NTRS 20180000518）：从 CFD 提取视线方向密度梯度，多剖面 Z-Project 平均合成"synthetic schlieren"，再做对比度拉伸，与实验 z-type 纹影半定量对比。https://ntrs.nasa.gov/api/citations/20180000518/downloads/20180000518.pdf

### 2.2 其他常用可视化量

- **压力等值线 / p/p_0 染色**：显示激波胞格的准周期压强振荡（[S3] 图 1.6）。
- **Mach 数云图 + 流线**：分离与回淋研究的标准输出（[S4]、过膨胀分离 CFD 论文）。
- **温度场**：羽流辐射/发光渲染时用；注意激波后温度突升、膨胀扇内温度骤降——**激波"亮"的物理原因之一是波后密度温度升高增强辐射/散射，并非激波面本身发光**。
- **密度本身**：近真空 DSMC 羽流常用 log 密度云图（跨 5+ 数量级）。

**对"细亮线"质疑的直接回答**：论文里激波之所以清晰可见，是因为画的是**密度梯度场**：激波处 |∇ρ| 极大 → 亮线；膨胀扇处 |∇ρ| 为中等负梯度 → 宽而柔和的暗/亮带。**激波线有宽度、膨胀扇不是黑空白区**，这是当前 zig-zag 细线画法最大的物理失真点。

---

## 3. 海平面 vs 真空羽流形态对比

### 3.1 真空羽流的大角度膨胀与回淋（backflow）

- 真空中背压≈0，羽流属极端欠膨胀。膨胀角上限由 Prandtl–Meyer 函数在 M→∞ 给出：

  **ν_max = (√((γ+1)/(γ−1)) − 1) × 90°**

  γ=1.4 → ν_max ≈ 130.5°；γ=1.2（典型火箭燃气）→ ν_max ≈ 159°。即**羽流可以绕过喷口唇缘向外张到接近甚至超过 90° 半角**。
  出处 [S7] *Density and Optical Properties of SPARCS Plumes*, NASA NTRS 19730004231。https://ntrs.nasa.gov/api/citations/19730004231/downloads/19730004231.pdf
- NASA 对真实发射的观测：低空时羽流细直向下；高空时羽流外表面**绕喷管底缘做 180° 转弯、沿箭体外侧向上包络到火箭前方**。出处 [S8] NASA-TM-2014-216622（Ames，风洞–加速器类比报告，附发射观测描述）。https://aviationsystems.arc.nasa.gov/publications/2014/NASA-TM-2014-216622.pdf
- **回淋机制**：喷管壁面边界层在唇缘处急剧膨胀（稀薄时边界层相对厚度大），可产生绕到发动机背面的反向流；固发液滴/燃气可沉积到航天器表面（污染）。高空试车台（如 AEDC J 系列、Plum Brook）正是为复现这一形态。出处：[S9] USAF/NASA International Spacecraft Contamination Conference 摘要集（NTRS 19790075245，含 5 lbf 双组元发动机回淋区 120° 范围质量通量测量计划）；[S4] Liaw 1992；[S10] Cai, G. 等, 2022, *A Review of Research on the Vacuum Plume*, Aerospace 9(11):706（被引 44）。https://www.mdpi.com/2226-4310/9/11/706
- **角分布近似（Simons 源流模型，工程标准）**：

  **ρ/ρ_0 = (A/r²) · cos⁷(πθ / (2θ_max))**，θ_max = 唇缘最大 PM 膨胀角

  90% 质量集中在轴线 30° 锥内（15° 半锥角喷管）；音速孔口自由射流仅 35%。出处 [S4][S11] AIP Physics of Fluids 36, 107104 (2024), *Underexpanded jet impingement in near vacuum environment*（引言综述 Bird DSMC、Lumpkin Apollo 登月舱 DSMC、Roberts 月尘分析）。https://pubs.aip.org/aip/pof/article/36/10/107104/3314979

### 3.2 海平面（大气中）羽流

- 背压 p_a 非零 → 等压自由边界把羽流约束成**周期性鼓胀-收缩的"桶形"包络**，激波胞格被大气边界清晰限定，宽度量级 = 喷口直径 D，胞格长 L_s ≈ 1.2–1.3·D·√(M_j²−1)（§1.3a）。
- 对游戏渲染的含义：**海平面羽流 ≈ 一串宽度有限、间距由 L_s 决定的菱形胞格 + 剪切层逐渐模糊化；真空羽流 ≈ 无胞格约束、钟形大幅张开、边缘可回卷，几乎看不到离散激波胞格**。两者形态差异是第一性的，不是参数微调。

---

## 4. 过膨胀喷管内激波分离：FSS / RSS

- **FSS（Free Shock Separation）**：过膨胀工况（n = p_e/p_a ≈ 0.4–0.8 起）边界层在分离点离壁且不再附体；壁压升到接近环境的"平台压"，分离激波为斜激波，下游为开放回流区。
- **RSS（Restricted Shock Separation）**：NPR 更高时，分离流受 cap-shock 结构（内激波在轴线的 Mach 反射 + 分离激波 + 反射激波交汇于四重波点）产生的径向向外动量驱动**重新附体**，形成封闭回流泡；泡内压力显著低于环境，再附点壁压可出现**高于环境压的峰值**（交替激波/膨胀波沿壁）；分离点相对 FSS 突然下移。
- **FSS→RSS 转变发生在明确 NPR 上且有迟滞**（升压 FSS 保持更久，降压 RSS 保持更久）；LEATOC 喷管转变 NPR≈24。再附点推到出口时回流泡周期开闭 → **End-Effect Regime（EER）**，产生脉动侧向载荷（SSME 燃料管失效即归因于此）。
- **只发生在有内激波的喷管型面（TOP/TOC/CTIC）**；钟形理想喷管一般不出现 RSS。
- 出处（独立来源 ×3）：
  - [S12] Nave, L. H. & Coffey, G. A., 1973, *Sea Level Side Loads in High-Area-Ratio Rocket Engines*, AIAA Paper 73-1284（FSS/RSS 命名与侧载峰值在转变期的原始文献，被 [S13][S14] 一致引用）。
  - [S13] Östlund, J., 2004, *Supersonic Flow Separation with Application to Rocket Engine Nozzles*, KTH 博士论文（Volvo S1 实验+CFD，迟滞环、EER、侧载模型，n≈0.15 工况 Mach 分布图）。https://www.mech.kth.se/~jan/2004ostlund.pdf
  - [S14] Shams, A., Girard, S., Comte, P., 2012, *Numerical Simulation of Shock-Induced Separated Flows in Overexpanded Rocket Nozzles*, EUCASS 3p169（LEATOC 轴对称+3D，CNPR=24 形成四重波点 FTQP，NPR 25–46 的 RSS 演化）。https://www.eucass-proceedings.eu/articles/eucass/pdf/2012/02/eucass3p169.pdf

**渲染含义**：海平面大喷管（pr < 1，如项目的 pr0.3/pr0.5 工况）喷管**内部**就应画分离激波和分离点后的贴壁低压区，而不是把激波只画在喷口外部；喷口外的胞格首胞由分离激波延续而来，形态与欠膨胀完全不同。

---

## 5. 对当前 zig-zag 几何映射近似的改进建议（可执行）

按改动成本从低到高排序：

1. **从"画线"改为"画密度梯度场"**（最高优先级，回应用户质疑的核心）：
   - 在现有解析波系几何（膨胀扇边界线 + 拦截激波位置）基础上，不再只画细亮线，而是给每个波系单元赋一个**沿其法向的高斯/指数亮度剖面**：激波半宽 ~0.02–0.05 D（接近网格/像素极限的窄亮带），膨胀扇半宽 = 扇本身的张角区（宽柔和带），亮度符号相反或色调不同（论文惯例：激波亮带、膨胀扇灰暗带，见 [S5] 图 11）。
   - 全局叠加后再做 `I = |∇ρ|^(0.3~0.5)` 或对数压缩（§2.1），模拟数值纹影的非线性量程。

2. **用 Prandtl/Pack 公式定胞格间距，替代手工 zig-zag 波长**：
   `L_s = 1.22 * D_exit * sqrt(M_j**2 - 1)`，M_j 由 p_e/p_a 等熵解出；胞格数 3–6 个，之后亮度按胞格序号做指数衰减（湍流耗散，实验纹影可见）。出处 [S3][S1]。

3. **射流包络用等压边界近似替代直线/固定锥角**：
   - 欠膨胀：首胞边界用初始倾角 Δν（§1.3c）外张，之后按正弦/分段抛物线在 ±max 半径间鼓胀-收缩，波长 = 2·L_s，振幅随胞格衰减。
   - 海平面（pr>1 即过膨胀）：包络不外张，首胞直接收缩；pr ≳ 2（重度过膨胀）时在喷管内部加画分离激波（斜线，角度由斜激波关系按 p_a/p_e 估算），分离点后壁外不再画核区胞格（见 §4）。

4. **真空工况（pr ≈ 0）切换形态模板，而非调参**：
   - 关闭激波胞格绘制（真空中无等压边界约束、无清晰胞格）；
   - 包络半角取 min(ν_max(γ) 的 60–80%, ~130°)（γ=1.2 燃气 ν_max≈159°，但可视质量集中在 30° 内，建议主亮锥 30–40° + 宽暗晕到 100°+，按 Simons `cos^7` 角分布衰减亮度，§3.1）；
   - 可在喷管背侧画极淡的回淋晕（亮度 < 主羽流 5%），呼应 Apollo/高空试车的 180° 回卷形态 [S8]。

5. **Mach 盘**：当 p_e/p_a ≳ 3–4 时，在首胞末端的轴线上画一段**垂直于轴线的短亮盘**（宽度 ~0.3–
0.6 D，随 NPR 增大），并让拦截激波终止于盘缘的三重点而非轴线交点——这是规则反射→Mach 反射转变的视觉标志 [S1][S2]。

6. **发光/颜色物理化（可选）**：激波后温度密度双升 → 亮度 ∝ ρ²（复合/散射近似），膨胀扇内降温降密 → 变暗。因此"亮线"应理解为**波后区域的亮**，而非波面本身；可把激波亮带的高斯中心略向下游偏移 0.01–0.03 D 来体现。

7. **保守回退**：若不想改场渲染，至少把 zig-zag 折线的**线段角度**改为马赫角 μ = asin(1/M_j)（相对当地流线），胞格间距用 (2) 的 L_s——当前若用任意固定角度，是最容易被质疑的点。

---

## 6. 来源清单（交叉验证 ≥3 独立来源/主题）

| # | 出处 | 用途 |
|---|------|------|
| S1 | Carpenter 1991, NASA NTRS 19940006457 | MOC/离散波系全流程、胞格波长经验式、Adamson–Nicholls 边界积分 |
| S2 | Shaw et al. 2017, NAPC-2017-139 (IIT Bombay) | 欠膨胀射流结构、边界等压条件、边界初始倾角、NPR→Mach 反射 |
| S3 | Henry (EC Lyon 博士论文) | 激波胞格物理图像、Prandtl/Pack/Tam 胞格长度公式 |
| S4 | Liaw 1992, NASA NTRS 19920021737 | 真空羽流回淋、Simons 源流 cos⁷ 模型、MOC 适用上限 |
| S5 | Settles 2017, 纹影/阴影技术综述（Springer 专著配套论文） | 数值纹影惯例、亮度映射、刀口方向性 |
| S6 | Drozda et al. 2017, NASA/TM NTRS 20180000518 | CFD 合成纹影实现流程（梯度→视线平均→对比度拉伸） |
| S7 | NASA NTRS 19730004231 (SPARCS plumes) | PM 函数与 ν_max 公式、深空羽流膨胀角 |
| S8 | NASA-TM-2014-216622 | 发射观测：高空羽流 180° 回卷包络箭体 |
| S9 | NTRS 19790075245（USAF/NASA 污染会议摘要集） | 回淋区 120° 质量通量测量、固发羽流回淋机制 |
| S10 | Cai et al. 2022, Aerospace 9(11):706 | 真空羽流地面试验与仿真综述 |
| S11 | Physics of Fluids 36, 107104 (2024) | 近真空欠膨胀射流流区划分、Apollo DSMC 文献链 |
| S12 | Nave & Coffey 1973, AIAA 73-1284 | FSS/RSS 原始命名（经 S13/S14 转引核实） |
| S13 | Östlund 2004, KTH 博士论文 | FSS/RSS 壁压特征、迟滞、EER、侧载 |
| S14 | Shams, Girard & Comte 2012, EUCASS 3p169 | cap-shock/四重波点机制、CNPR≈24、RSS 随 NPR 演化 |
