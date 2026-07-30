# 方向D调研报告：湍流噪声与真实感尾焰动画渲染参考

**角色标签**：文献调研（方向D）
**日期**：2025-07（本轮会话）
**背景**：`plume_core.py` 当前把激波画成细亮线，用户质疑物理真实性。本报告聚焦：剪切层湍流结构的定量规律、高速摄影下尾焰抖动的时空特征、影视/游戏羽流渲染常用技术，并给出对 `turbulence_post` 后处理参数的可执行改进建议。

---

## 1. 射流剪切层湍流结构：定量规律

### 1.1 不可压剪切层增长率 δ'/x ≈ 0.16–0.18

- Brown & Roshko (1974) 对大量文献数据拟合，得到涡量厚度增长率
  **dδ_ω/dx ≈ (0.162–0.181)·λ**，其中 λ = (U_h − U_l)/(U_h + U_l) 为速度比参数。
  对静止环境射流（U_l = 0，λ = 1），即 δ'/x ≈ **0.16–0.18**（单侧约为一半，0.08–0.09）。
  来源：Brown, G.L. & Roshko, A., 1974, "On density effects and large structure in turbulent mixing layers", *J. Fluid Mech.* 64, 775–816。交叉验证：Barth et al., 2024, "Growth of organized flow coherent motions within a single-stream shear layer: 4D-PTV measurements", *Experiments in Fluids* 65（实测 0.168λ，落在该区间内），https://link.springer.com/article/10.1007/s00348-024-03846-5

### 1.2 大尺度涡卷（Brown–Roshko 结构）与 Kelvin–Helmholtz 不稳定

- Brown & Roshko (1974) 首次用阴影照相揭示：即便在完全湍流的高 Re 混合层中，仍被**二维大尺度相干涡卷**主导；涡卷通过**配对（pairing/merging）**合并使剪切层线性增厚（Winant & Browand, 1974, JFM 63, 237–255）。最放大扰动的 Strouhal 数 St = f·θ/ΔU ≈ 0.017/2（线性稳定性理论，McMullan et al. 2024 综述引用，*Physics of Fluids*）。
- 相干结构间距/厚度比 λ_s/δ ≈ π/2（≈1.57）；McMullan et al. (2024) 由结构线性增长导出视觉厚度增长率 k ≈ 1/π ≈ 0.32。
  来源：McMullan, W.A. et al., 2024, "The growth of the initially turbulent mixing layer: A large eddy simulation study", *Physics of Fluids*，https://publications.aston.ac.uk/id/eprint/46971/

### 1.3 可压缩性大幅抑制增长率（火箭尾焰必须考虑）

- Papamoschou & Roshko (1988) 用 20 ns 纹影发现：归一化增长率随**对流马赫数 M_c** 迅速下降，M_c>1 后渐近到不可压值的 **~0.2（约 1/5）**；M_c ≈ 0.5 起显著衰减。M_c = (U_1−U_c)/a_1 = (U_c−U_2)/a_2，对静止环境射流近似 M_c ≈ U_j/(a_j+a_∞)。
  来源：Papamoschou, D. & Roshko, A., 1988, "The compressible turbulent shear layer: an experimental study", *J. Fluid Mech.* 197, 453–477，DOI: 10.1017/S0022112088003325（被引 1800+）。交叉验证：NASA NTRS 20000004764（Caltech/Case Western 氦-空气同轴射流设施报告，综述 Bogdanoff/Papamoschou 结果：增长率降 3–4 倍）；Slessor, Zhuang & Dimotakis, 2000, "Turbulent shear-layer mixing: growth-rate compressibility scaling", *JFM* 414, 35–45。
- **含义**：海平面火箭尾焰（U_j~2000–3000 m/s，热燃气 a_j~1000–1200 m/s）M_c ≈ 0.8–1.2，剪切层增长率只有不可压值的 20–40%。真空中羽流出口即近乎自由膨胀，剪切层薄而弱。

### 1.4 激波对剪切层脉动的调制

- LES 研究（Guy et al., ONERA, EUCASS 2017-434）显示：第一激波胞下游剪切层速度脉动出现峰值（脉动水平达出口速度的 14.5%），轴向速度 PSD 在 St ≈ 0.2–0.45 有峰，St⁻⁵ᐟ³ 惯性区出现；红外相机观察到约 40 cm 空间波长、~2 kHz 的间歇性振荡结构。
  来源：Guy, A. et al., 2017, "Ionized Solid Propellant Rocket Exhaust Plume", EUCASS2017-434，https://www.eucass.eu/doi/EUCASS2017-434.pdf

---

## 2. 高速摄影下尾焰晃动/抖动的时空特征

### 2.1 低频整体摆动：flapping / helical 模态

- 火箭尺度实验（Ariane 5 缩比热试车，高速纹影）：jet 呈**侧向摆动（flapping，反对称/sinuous 模态）**，频率 f ≈ 700 Hz，对应 **St = f·D/U ≈ 0.18**；另有 1400 Hz 高频模态做径向"胀缩"。该 flapping 模态与飞行中 Ariane 5 观测一致。
  来源：Research Square rs-7579481, "High-speed schlieren analysis of rocket exhaust jet dynamics in launcher base region"（预印本），https://www.researchsquare.com/article/rs-7579481/v1.pdf
- 轴对称超声速射流的 screech 模态分类（Powell 1953 起）：A1/A2 轴对称、B/D flapping、C helical（螺旋）。flapping 模态是"增强混合"的主导机制，摆动幅度可超过喷管半径量级。
  来源：Raman, G. & Rice, E.J., 1993, "Instability Modes Excited by Natural Screech Tones in a Supersonic Rectangular Jet", NASA TM-106322（NTRS 19940017801）；Edgington-Mitchell, D. et al., 2022, "A unifying theory of jet screech", *J. Fluid Mech.* 945（SPOD 分析显示 B 模态 flapping 与 A2 轴对称模态可共存）。
- 关键结论：**尾焰视觉上的"摆动"是低频（St~0.1–0.3）、大尺度、整体性的；剪切层条带/脉动是高频（St~0.5–5）、小尺度的**。动画需要这两个频段的叠加才显得真实。

### 2.2 高频剪切层脉动

- 剪切层 KH 波的优选 Strouhal 数 St_θ = f·θ/ΔU ≈ 0.016–0.022（近喷口薄剪切层），随剪切层增厚，局部最优频率沿下游降低（Brès et al., 2018, "Importance of the nozzle-exit boundary-layer state in subsonic turbulent jets", *J. Fluid Mech.* 851）。
- 时间频谱呈宽带 + 低频峰结构：低频峰（St~0.1–0.3，flapping/大结构），宽惯性区 f⁻⁵ᐟ³ 衰减（Guy et al. 2017 实测 PSD）。

---

## 3. 电影/游戏中羽流渲染常用技术

以 KSP 社区事实标准 **Waterfall**（Chris Adderly/"Nertea"，https://github.com/KSPModStewards/Waterfall，官方论坛 Developer Insights #11 "Engine Exhaust Visual Effects", 2021-09-17）为代表：

1. **网格驱动的多层壳体**：羽流由多个嵌套/叠加的锥形网格壳构成（核心亮层、中间过渡层、外扩散层），每层独立参数化长度/半径/颜色，随油门与大气压插值（"支持大气膨胀与动态效果"）。
2. **加色混合（additive shader）**：发光羽流用 additive blending，亮度自然叠加形成马赫盘亮斑——开发者明确说明 "Waterfall uses additive shaders for the luminous exhaust plumes, which makes a lot of sense"。
3. **顶点噪声位移（vertex displacement）**：用噪声场对网格顶点做时间相关位移，制造剪切层晃动与边缘破碎感（"some vertex displacement and a nice dash of bloom"）。
4. **Bloom/光晕后处理**：高亮核心过曝 + bloom 拖尾，模拟摄影机对高温羽流的饱和响应。
5. **马赫盘以纹理/壳体亮度节点实现**，而非真实激波求解；关键是用"亮度脉动 + 轻微轴向抖动"避免静态贴图感。
6. **卷曲噪声/FBM 纹理滚动**：沿羽流轴向滚动的 FBM（分形布朗运动，3–5 个倍频程）alpha/密度纹理，使剪切层出现随下游放大的条带（striations）。
7. **不对称性注入**：对位移噪声的方位角做低频调制，让羽流在任何时刻都不是完美轴对称的（真实 flapping 的视觉签名）。

影视 VFX 层面（通用火焰/羽流做法）：体积模拟（温度/密度场）+ 黑体辐射亮度映射；2.5D 场景下则退化为"层叠 billboard + 噪声位移 + 发光度脉动"。真实感公认来自**运动的时间相关性**（低频大摆动 + 高频碎湍）而非纹理复杂度本身。

---

## 4. 视觉上"真实感"的关键要素清单

按重要性排序（均有第 1、2 节物理依据）：

| # | 要素 | 物理来源 | 典型量级 |
|---|------|---------|---------|
| 1 | 低频整体横向摆动（flapping） | 射流反对称不稳定模态 B/C | St≈0.1–0.3；摆幅 ~0.1–0.5 倍局部羽流半径，随下游增大 |
| 2 | 高频剪切层条带/脉动（striations） | KH 涡卷 + 宽带湍流 | St≈0.5–5（相对 θ）；幅度沿下游线性增长 |
| 3 | 剪切层边缘扩散角随 M_c 压扁 | Papamoschou–Roshko 压缩性抑制 | δ'/x = 0.17·Φ(M_c)，Φ≈0.2–0.4（超声速） |
| 4 | 亮度脉动（闪烁） | 温度/密度脉动 → 黑体辐射强烈非线性 | RMS 脉动 ~10–15% 出口速度 → 亮度 RMS 20–40% |
| 5 | 方位角不对称 | flapping/helical 模态投影 | 任何瞬时快照偏离轴对称 5–15% |
| 6 | 马赫盘/激波胞亮斑的位置微抖动 | 激波非定常性（screech 反馈回路） | 轴向抖动 ~5–10% 胞长，与 flapping 同频 |
| 7 | 下游破碎与卷吸羽化 | 大涡卷配对 + 环境卷吸 | 下游 3–5 倍激波胞长后边缘"毛边"宽度 ~ 剪切层厚度 |
| 8 | 过曝核心 + bloom | 高温核心辐射饱和 | 核心亮度饱和，边缘按 T⁴ 级陡降 |

---

## 5. 对 `turbulence_post` 后处理的具体改进建议

### 5.1 位移场：双频段叠加（幅度量级）

用出口半径 R_e 归一化。对剪切层边界（激波胞边界轮廓）施加横向位移：

```
Δy(x, t) = A_low · g(x) · sin(2π f_low t + φ(x))          # 低频 flapping
         + A_high · g(x) · fbm1D(k·x − ω·t)               # 高频剪切层脉动
```

- **增长率包络 g(x)**：剪切层厚度 δ(x) = 0.17·λ·Φ(M_c)·x。建议代码取
  `g(x) = min(1, 0.08·(x/R_e) · Φ / 0.17·λ )` 简化为：
  - 海平面/过膨胀工况：Φ ≈ 0.3（M_c≈1），剪切层半角 ≈ 0.17·0.3 ≈ **0.05 rad（≈3°）**；
  - 适度欠膨胀：Φ ≈ 0.2–0.3；
  - 真空：剪切层极弱，主要用低频摆动，Φ 等效取 0.1。
  即位移包络 **g(x) ≈ δ(x)/R_e ≈ 0.05·(x/R_e)**（海平面），封顶在羽流半宽。
- **低频分量**：f_low 对应 St_D = f·D_e/U_j ≈ 0.15–0.25。取 U_j ≈ 2500 m/s、D_e = 0.1 m → f ≈ 3750–6250 Hz（真实尺度）；动画中应按"视觉秒"重标定：若动画 1 帧 = Δt_vis，建议 flapping 周期 = **25–60 帧**（约 1–2.4 s @25fps），相位沿下游传播（波速 ≈ 0.6·U_j，即波包向下游对流）。
- **低频幅度**：A_low(x) ≈ 0.15–0.35 · 局部羽流半宽 · g(x)/g_max。初始为 0，在 x ≈ 1–2 个激波胞长后开启（flapping 在第三激波胞附近最强，Ecker 2015 / Umeda & Ishii 2001）。
- **高频分量**：空间波数对应结构间距 λ_s ≈ (π/2)·δ(x)（McMullan 2024），即条带间距随下游线性变稀；时间频率取 flapping 的 5–10 倍，谱形状 **f⁻⁵ᐟ³** 或 FBM（Hurst 指数 H≈1/3）天然满足。A_high ≈ 0.3–0.6 · A_low。

### 5.2 亮度映射

- 激波胞亮斑（马赫盘）亮度不要做减法细线，改用**加色叠加**：I_total = I_base + Σ_cells I_cell·pulse_i(t)，
  pulse_i(t) = 1 + 0.2·sin(2π f_low t + i·π/2)（相邻胞反相 90°，模拟激波位置随 flapping 的轴向微抖，幅度 ~5–8% 胞长）。
- 核心亮度对温度用**幂律映射**模拟黑体辐射的非线性：I ∝ (T/T_max)^γ，γ ≈ 3–4（比 T⁴ 略缓以保留层次），核心处允许饱和（clip 到 1.0）以产生过曝白核。
- 剪切层条带：在加色层上乘以 `1 + 0.3·striation(x,θ,t)`，striation 为沿下游放大（乘 g(x)）的 FBM 带通噪声，方位角方向加 1 个低频 m=1 分量（不对称）。

### 5.3 频谱配方（直接可用的权重表）

| 分量 | St 基准 | 视觉周期（25fps 动画） | 相对幅度 | 空间尺度 | 下游行为 |
|------|--------|----------------------|---------|---------|---------|
| Flapping（m=1 整体摆动） | 0.18 (D_e) | 25–60 帧 | 1.0 | ~整个羽流宽度 | 幅度 ∝ g(x)，x>2 胞长开启 |
| 螺旋进动相位漂移 | 同上 | 60–120 帧一圈 | 0.3（方位角相位） | — | 恒定角速度 |
| KH 大涡卷 | 0.5–1 (δ) | 8–15 帧 | 0.4 | λ_s≈1.57δ(x) | 间距/幅度 ∝ x |
| 宽带碎湍 | >1 (δ) | 2–5 帧 | 0.15 | <δ(x) | f⁻⁵ᐟ³ 谱 |
| 亮度整体脉动（推力噪声） | — | 15–40 帧 | ±3–5% 全局亮度 | 全局 | 恒定 |

### 5.4 三条"立刻能做"的代码级建议

1. 把激波细亮线改为**窄高斯亮带**（σ ≈ 0.5–1 px → 羽流宽度的 1–2%），加色混合，亮度加 ±20% 时间脉动 + ±6% 胞长轴向抖动——消除"画上去的线条感"。
2. 对羽流外轮廓施加 §5.1 的双频位移场（flapping 波包向下游传播 + FBM 条带），幅度包络按 Φ(M_c)·0.17·x 增长——这直接回应"物理真实性"质疑。
3. 方位角（2D 中为上下两侧）噪声用**反对称相关**（上侧 +A 时下侧 −A，flapping）与**对称分量**（胀缩，A2 模态，权重 0.3）混合，避免上下边缘各自独立抖动产生"面条感"。

---

## 6. 主要出处汇总

1. Brown, G.L. & Roshko, A. (1974). On density effects and large structure in turbulent mixing layers. *J. Fluid Mech.* 64, 775–816.
2. Papamoschou, D. & Roshko, A. (1988). The compressible turbulent shear layer: an experimental study. *J. Fluid Mech.* 197, 453–477. DOI:10.1017/S0022112088003325. https://cfd.spb.ru/agarbaruk/doc/1988_Papamoschou-Roshko_The-compressible-turbulent-shear-layer-an-experimental-study.pdf
3. Slessor, M.D., Zhuang, M. & Dimotakis, P.E. (2000). Turbulent shear-layer mixing: growth-rate compressibility scaling. *J. Fluid Mech.* 414, 35–45. https://authors.library.caltech.edu/records/x1r20-zhd94/files/SLEjfm00a.pdf
4. Barth et al. (2024). Growth of organized flow coherent motions within a single-stream shear layer: 4D-PTV measurements. *Experiments in Fluids* 65. https://link.springer.com/article/10.1007/s00348-024-03846-5
5. McMullan, W.A. et al. (2024). The growth of the initially turbulent mixing layer: A large eddy simulation study. *Physics of Fluids*. https://publications.aston.ac.uk/id/eprint/46971/
6. Raman, G. & Rice, E.J. (1993). Instability Modes Excited by Natural Screech Tones in a Supersonic Rectangular Jet. NASA TM-106322 / NTRS 19940017801. https://ntrs.nasa.gov/api/citations/19940017801/downloads/19940017801.pdf
7. Edgington-Mitchell, D. et al. (2022). A unifying theory of jet screech. *J. Fluid Mech.* 945. https://www.cambridge.org/core/journals/journal-of-fluid-mechanics/article/unifying-theory-of-jet-screech/388E745CBA77205DA5282C1EB68992F8
8. Guy, A. et al. (2017). Ionized Solid Propellant Rocket Exhaust Plume. EUCASS2017-434. https://www.eucass.eu/doi/EUCASS2017-434.pdf
9. Brès, G.A. et al. (2018). Importance of the nozzle-exit boundary-layer state in subsonic turbulent jets. *J. Fluid Mech.* 851. https://flowphysics.ucsd.edu/wp-content/papercite-data/pdf/bresetal_2018_jfm.pdf
10. High-speed schlieren study of launcher base jet dynamics (Ariane 5 缩比热试车). Research Square rs-7579481. https://www.researchsquare.com/article/rs-7579481/v1.pdf
11. KSPModStewards. Waterfall: Mesh-driven engine effect framework for KSP. https://github.com/KSPModStewards/Waterfall ; KSP 官方论坛 Developer Insights #11 – Engine Exhaust Visual Effects (2021-09-17). https://forum.kerbalspaceprogram.com/topic/204884-developer-insights-11-engine-exhaust-visual-effects/
12. NASA NTRS 20000004764. Development and Validation of a Supersonic Helium-Air Coannular Jet Facility. https://ntrs.nasa.gov/api/citations/20000004764/downloads/20000004764.pdf
13. Ecker, T. (2015). Experimental analysis of shock unsteadiness in supersonic jets. Virginia Tech 学位论文. https://vtechworks.lib.vt.edu/bitstream/handle/10919/51687/Ecker_T_D_2015.pdf
