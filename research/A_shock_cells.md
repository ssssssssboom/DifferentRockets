# 方向A：超音速自由射流激波胞格（shock cells）结构与实验成像

> 文献调研报告 · 撰写人：文献调研（方向A）
> 任务背景：D:\DifferentRockets 的二维拉瓦尔喷管尾焰激波波系可视化（plume_core.py），当前把激波画成"细亮线"，需评估物理真实性并给出可执行的渲染建议。

---

## 1. 关键结论速览

1. **激波在实验成像（schlieren / shadowgraph）中不是"发光亮线"，而是折射率（密度）梯度造成的暗/亮条纹对。** Schlieren 对密度一阶导数敏感、shadowgraph 对二阶导数敏感；一条激波在 shadowgraph 中总是表现为"一条暗线紧跟一条亮线"（密度高的一侧在亮侧）。渲染时把激波画成单根高亮细线对应的是 CFD 数值 Schlieren（|∇ρ| 上色）风格，不是物理实验观测形态。
2. 非理想膨胀射流内部是周期性的**菱形激波胞格（shock cells / Mach diamonds）**：斜激波在喷口唇缘发出，在射流自由边界上反射为膨胀波，再反射回压缩波/斜激波，如此重复直到黏性耗散使其消失。
3. **胞格长度经验公式（Tam & Tanna 1982 理论，Pack/Prandtl 谱系）**：`L_s = 2π r_j √(M_j² − 1) / μ_1`，其中 `r_j` 为完全膨胀射流半径，`μ_1 = 2.40483`（第一类 Bessel 函数 J₀ 的第一个零点）。近似数值：`L_s / D_j ≈ 1.306 √(M_j² − 1)`。该公式只含射流完全膨胀马赫数 M_j，几何尺寸随 M_j 单调增长。
4. **Norum & Seiner（NASA TM-84521, 1982）实验测量关联式**：`S_x / D_f = 1.1 (M_Pf² − 1)^(0.585)`（c=1.1, d/2=0.585），被广泛用作实验基准；与 Tam 公式交叉验证误差通常在 10–20% 量级。
5. **Mach disk（马赫盘）形成阈值**：对收敛喷口欠膨胀射流，经典经验阈值为 NPR = p₀/p_a ≳ 2–2.1；近期数值研究（Physics of Fluids, 2022, Muraoka & Hiejima 方向）给出更精确的阈值 NPR ≈ 3.03–3.12，并发现 Mach disk 出现时轴上总压损失约 40%。低于阈值时桶状激波（barrel shock）可直接在轴上相交形成规则菱形胞格。
6. **Mach disk 位置经验式**：`L_MD / D_j = 0.69 · M_j · √(γ · JPR)`（JPR = 喷口出口静压/环境压比）；更早的 Emden(1956)/Ashkenas–Sherman 型关联写作 `x_M/D ≈ 0.67 √(P₀/P_a)`。Mach disk 直径约为 (0.4–0.6)× 胞格宽度，随 NPR 增大而增大。
7. **射流边界是等压（压力匹配）自由边界**：膨胀波在边界上反射为压缩波、压缩波反射为膨胀波，正是"边界上保持常压"这一约束产生了周期性胞格结构。欠膨胀时边界先向外扩张（桶形），过膨胀时先向内收缩。
8. **过膨胀（pe/pa < 1）**：喷口唇缘直接发出斜激波（无膨胀扇），胞格更短更弱；强过膨胀（如海平面高喷管膨胀比工况）会在喷管内/唇缘形成马赫盘，胞格数少（3–5 个即消失）。
9. 胞格强度沿下游递减（黏性 + 剪切层增长耗散），实验图像中约 5–10 个胞格后激波结构"失去身份"（lose its identity），被湍流剪切层取代。
10. 亮度映射建议：激波可见度应**正比于密度梯度幅值 |∇ρ|（或激波强度 Δp/p）**，膨胀扇应为**宽阔的低亮度渐变区**（或反号暗区），二者不能画成同类细亮线。

---

## 2. Schlieren / Shadowgraph 实验成像中激波的真实形态

### 2.1 物理机制

气体的折射率与密度由 Gladstone–Dale 关系线性耦合：`n = 1 + Kρ`（K 为 Gladstone–Dale 常数，气体中典型值 0.1–1.5×10⁻³ (kg/m³)⁻¹）。光线穿过密度梯度场时向折射率大（密度大）的一侧偏折：

- **Schlieren（纹影）**：对密度**一阶导数**（垂直于刀口方向）敏感。偏折向刀口的光线被遮挡→变暗；偏折离开刀口→变亮。因此激波表现为一侧变暗、一侧变亮的**条纹对比**，且图像上下半部分亮度反转是常见特征。
- **Shadowgraph（阴影）**：对密度**二阶导数**敏感。光线汇聚处亮、发散处暗。"**一条激波在 shadowgraph 中总是一条暗线紧跟一条亮线，密度高的一侧在亮侧**"（Settles 教科书及多所大学讲义的一致结论）。
- 膨胀扇（Prandtl–Meyer 扇）只有一阶梯度：在 schlieren 中可见为**宽阔的渐变明暗区**，在 shadowgraph 中**几乎不可见**。

### 2.2 对火箭尾焰的推论

真实火箭尾焰照片中可见的"马赫钻石"亮斑，是**激波压缩加热 + 未燃尽燃料/炭粒/自由基化学发光**共同作用的结果，发光峰值位于激波胞格交叉点（压缩最强、温度最高处），呈现为**一列离散的菱形亮斑/结（knots）**，而不是贯穿整个尾焰的连续细亮网格线。冷射流实验（无燃烧）中激波本身完全不发光。

---

## 3. 激波胞格结构与胞格长度公式

### 3.1 结构描述

- 欠膨胀射流：喷口唇缘发出 Prandtl–Meyer 膨胀扇 → 在等压自由边界反射为压缩波 → 汇聚成**拦截激波/桶状激波（intercepting/barrel shock）** → 胞格末端或以规则反射在轴心相交（弱欠膨胀），或以 Mach disk 正激波终结（强欠膨胀），并产生三波点和滑移线（slip line）。
- 过膨胀射流：唇缘直接发出斜激波，在边界反射为膨胀扇，形成同样周期性但更弱更短的菱形胞格。
- 重复直到黏性效应使激波结构消失（典型 5–10 个胞格）。

### 3.2 胞格长度公式（可直接用于代码）

**Tam & Tanna（1982）理论式**（Prandtl 1904 线性无粘理论 + Pack 1948 修正谱系）：

```
L_s = 2π r_j √(M_j² − 1) / μ_1 ,   μ_1 = 2.40483（J₀ 的第一个零点）
```

换算成完全膨胀射流直径 `D_j`：

```
L_s / D_j = (π/μ_1) √(M_j² − 1) ≈ 1.306 √(M_j² − 1)
```

其中完全膨胀马赫数 `M_j` 与 NPR 的关系（等熵，γ=1.4）：

```
M_j² = (2/(γ−1)) [ NPR^((γ−1)/γ) − 1 ]
```

**Norum & Seiner（1982）实验关联式**：

```
S_x / D_f = 1.1 (M_Pf² − 1)^0.585
```

（M_Pf 为完全膨胀马赫数；被大量后续 LES/实验用作基准。）

**马赫角（用于斜激波/马赫线倾角的几何上限参考）**：

```
μ = arcsin(1/M)
```

斜激波实际倾角由 θ-β-M 关系确定，弱激波极限趋近马赫角 μ。胞格中斜激波与轴线夹角一般在 15°–40°（M_j 1.2–3 范围内）。

**Mach disk 位置**：

```
L_MD / D_j = 0.69 M_j √(γ · JPR)        （JPR = p_exit/p_ambient）
x_M / D    ≈ 0.67 √(P₀/P_a)             （Emden 1956 / Ashkenas–Sherman 型）
```

**Mach disk 形成条件**：收敛喷口欠膨胀射流 NPR ≳ 2–2.1（经典经验）；更精确的数值阈值 NPR ≈ 3.0–3.1，对应轴上总压损失约 40%（Physics of Fluids, 2022）。Mach disk 后轴心流动变亚声速，桶状激波后仍为超声速，二者之间以滑移线分隔。

### 3.3 数值量级（γ=1.4, 收敛喷口）

| NPR | M_j | L_s/D_j (Tam) | 备注 |
|-----|------|---------------|------|
| 2.0 | 1.20 | 0.87 | 无 Mach disk，规则菱形 |
| 3.0 | 1.36 | 1.21 | Mach disk 阈值附近 |
| 4.0 | 1.46 | 1.39 | 清晰 Mach disk |
| 8.0 | 1.76 | 1.89 | 强欠膨胀，胞格拉长 |

---

## 4. 对渲染代码的可执行建议

1. **几何**：用 `L_s/D_j = 1.306√(M_j²−1)`（或 Norum–Seiner 关联）计算胞格间距，画 3–6 个菱形胞格，胞格长度沿下游可乘缓慢增长因子（剪切层增厚使实际胞格略长于理论值 10–20%），第 N 个胞格后让激波强度指数衰减消失。
2. **强度/亮度映射**：激波亮度 `I ∝ |∇ρ|`（数值纹影风格）或 `∝ Δp/p_s`（激波强度），并做 gamma 压缩（如 `I^(0.5)`）避免只有最强激波可见；**不要给所有激波统一亮度**。
3. **暗/亮对**：若要模仿实验 schlieren，每条斜激波画成"一侧暗带+一侧亮带"的梯度对，而非单根亮线；若走 CFD 可视化路线，则用 |∇ρ| 单峰高亮，但需配合第 4 条的膨胀扇处理。
4. **膨胀扇**：画成宽阔的扇形**低亮度渐变区**（亮度低于背景或与激波反号），绝不能画成亮线。欠膨胀时唇缘第一特征必须是膨胀扇，过膨胀时第一特征必须是斜激波——这是区分两种工况的核心视觉线索。
5. **射流边界**：画一条随 NPR 摆动的等压自由边界（欠膨胀外鼓、过膨胀内收），激波/膨胀波在边界处反射并改变波型（激波↔膨胀），这比固定边界的平行网格更接近真实。
6. **Mach disk**：NPR 超过 ~3 时在首个胞格末端画一段垂直于轴的粗短亮带（直径 ~0.5 胞格宽），其后中心线区域亮度下降（亚声速区），并画出从三波点延伸的滑移线（细长弱线）。
7. **马赫盘/胞格结亮度**：真实发光尾焰中亮度峰值在胞格交叉点，可把"发光"成分叠加为以交叉点为中心的离散高斯亮斑（shock diamonds），与密度梯度线分开建模。

---

## 5. 主要文献出处

1. **Tam, C. K. W., & Tanna, H. K. (1982).** "Shock associated noise of supersonic jets from convergent–divergent nozzles." *Journal of Sound and Vibration*, 81(3), 337–358. —— 胞格长度理论公式 L_s = 2π r_j√(M_j²−1)/μ_1（经 AIAA 2010-3732 等论文引用转录，https://acoustique.ec-lyon.fr/publi/aiaa_2010_3732.pdf）。
2. **Norum, T. D., & Seiner, J. M. (1982).** "Measurements of Mean Static Pressure and Far-Field Acoustics of Shock-Containing Supersonic Jets." *NASA Technical Memorandum 84521*. —— 胞格间距与强度随 NPR 变化的实验基准（引用信息经 MDPI Fluids 2019, 4(3):132 参考文献核实，https://www.mdpi.com/2311-5521/4/3/132；关联式 S_x/D_f=1.1(M²−1)^0.585 经 https://repository.ias.ac.in/127422/1/EXIF_1-Accepted.pdf 转录）。
3. **Settles, G. S. (2001).** *Schlieren and Shadowgraph Techniques: Visualizing Phenomena in Transparent Media.* Springer, Berlin/Heidelberg. ISBN 9783642566400. —— 纹影/阴影成像原理的权威教科书；激波=暗线+亮线对的结论另经 TU/e 讲义（https://pure.tue.nl/ws/files/46777411/304041-1.pdf）与 Caltech Ae104 课程讲义（https://shepherd.caltech.edu/T5/Ae104/Ae104b_handout2015.pdf）交叉验证。
4. **Pack, D. C. (1948).** "On the formation of shock-waves in supersonic gas jets." *The Quarterly Journal of Mechanics and Applied Mathematics*, 1(1), 1–17.（Prandtl 1904 理论的修正；谱系经 Li, X. P. (2018) *Chinese Physics B*, 27(9), 094705 综述核实，https://cpb.iphy.ac.cn/article/2018/1953/cpb_27_9_094705.html）。
5. **Muraoka / Tashiro et al. (2022).** "Onset conditions for Mach disk formation in underexpanded jet flows." *Physics of Fluids*, 34, 116125. DOI: 10.1063/5.0122861（https://ui.adsabs.harvard.edu/abs/2022PhFl...34k6125M/abstract）—— Mach disk 形成阈值 NPR≈3.03–3.12、总压损失~40%。
6. **NSF PAR 10631110**（实验 schlieren 研究，Mach 3 射流）：Mach disk 位置式 L_MD/D_j = 0.69 M_j √(γ·JPR)，https://par.nsf.gov/servlets/purl/10631110 。
7. **Hu, T. F. (1981).** "Flow and acoustic properties of low Reynolds number supersonic jets." *NASA 报告（NTRS 19820022412）*, https://ntrs.nasa.gov/api/citations/19820022412/downloads/19820022412.pdf —— "膨胀波反射为压缩波以保持射流边界常压、胞格重复直至黏性使其消失"的直接描述。
8. **Mehta, R. C. (2025).** "CFD Analysis of Underexpanded Jets." *Scholars Journal of Engineering and Technology*, 13(7), 552–568. —— 首胞格长度随激波强度 β² 线性增长、Tam 关系在非设计工况的应用（https://www.saspublishers.com/article/22619/download/）。
9. **Emden, R. (1956)** —— Mach disk 位置早期关联 x_M/D ≈ 0.67√(P₀/P_a)（经典公式，经多篇二级文献转录；建议引用时注明经 Ashkenas & Sherman 谱系）。

---

## 6. 给主协调人的一句话总结

激波不是发光的细线：实验成像中它是密度梯度产生的暗/亮条纹对（schlieren）或暗线+亮线（shadowgraph），真实尾焰的"马赫钻石"发光只集中在胞格交叉点；渲染时应按 Tam–Tanna 公式 L_s/D_j≈1.306√(M_j²−1) 布置菱形胞格，亮度正比于 |∇ρ| 并随下游衰减，膨胀扇画成宽阔低亮度渐变，NPR≳3 时加入 Mach disk。
