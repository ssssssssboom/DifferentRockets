# 方向B：火箭尾焰可见光发光的物理机制——调研报告

调研角色：文献调研（方向B）
日期：2026-02（基于当轮检索）
目标：解释真实火箭尾焰照片（Saturn V、Space Shuttle SSME、Falcon 9 的 shock diamonds）中激波胞格为什么亮，给出渲染亮度映射函数的可执行建议。

---

## 1. 尾焰可见光的四种发光机制

### 1.1 复燃（afterburning）——低空富燃羽流的主导增亮机制
火箭发动机通常富燃燃烧（最佳比冲混合比偏富燃），喷出的燃气含大量 CO、H2、未燃碳氢。低空时环境空气被卷吸进羽流混合层，O2 与这些可燃物发生二次燃烧（复燃），使羽流温度回升、辐射显著增强：

- 复燃可使羽流混合区温度相对增幅达 15.4%（轴向）/ 16.3%（径向），CO2/H2O 摩尔分数增加约 47.5%/53.4%，主辐射带强度增加约 31.5%（Ren & Zhu 2018，液发）。
- 固体发动机复燃可使局部火焰温度上升达约 1000 K，火焰带辐射亮度增强 10 倍以上（Li Xia et al. 2018）。
- 复燃可使辐射图像尺寸和强度增强达 40%（Niu et al. 2017，IRSAT 工具）。
- Sutton 教科书：助推级（低空）羽流辐射最强，部分原因就是富燃燃气与空气复燃增亮（Sutton, Rocket Propulsion Elements, 第18章）。
- 复燃需要 O2 掺混 + 足够温度；喷口附近（x < 约 20 m 量级）超声速核心区掺混差，复燃弱；复燃主要发生在羽流边界混合层和下游减速区（Ren & Zhu 2018）。
- 复燃随高度衰减：约 30–40 km 以上稀薄大气无法维持复燃，UV/可见光特征显著减弱（Clout 2025 博士论文, ONERA；Ren & Zhu 2018 显示 10 km 高度时增幅降至 10.7%）。

### 1.2 碳烟黑体辐射（连续谱）——煤油机可见光的主导
- LOX/RP-1（煤油）发动机富燃约 25%，未氧化碳形成微细炽热的碳烟颗粒，黑体/灰体连续谱辐射是黄色-白色明亮尾焰的主要来源。Falcon 9 的亮黄色羽流即此机制（SatObs 讨论整理引述推进界共识；RAeS Aerospace 2022 也指出 RP-1 排放大量黑碳）。
- AGARD AR-287（NATO）：羽流复燃区中，只要存在颗粒，**颗粒连续谱辐射主导可见波段**；典型复燃温度下颗粒发射率取 0.05–0.2。
- Decker et al. 2025（ONERA，固体发动机碳烟 IR 辐射）：0.2% 质量分数的碳烟即可复现实测辐射特征——少量碳烟对辐射贡献巨大。
- 数值含义：黑体辐射出射度 ∝ εσT⁴。T 从 1200 K 升到 1800 K，辐射强度增大约 5 倍——**亮度对温度极其敏感（T⁴ 律）**。

### 1.3 化学发光（chemiluminescence）——氢机蓝光与激波增亮
- LH2/LOX 发动机（SSME、LE-5、Vulcain）：产物几乎是水蒸气和氢，可见波段无强谱线，羽流"本质透明/无色"（Sutton RPE 第18章）。真实照片中 SSME 的蓝色激波胞格来自**非平衡化学发光**：
  - OH* 激发态在 310 nm 窄带发射（近紫外，比连续谱强约 20 倍）；
  - 可见蓝光主要来自 H2O*（excimer）的宽连续化学发光，且强度对压力高度非线性——21 bar → 30 bar 时 450 nm 强度跳变（Fiala 2015 博士论文, TU München, "Radiation from High Pressure Hydrogen-Oxygen Flames"）。
- **关键观察**：氢氧焰穿过正激波被突然压缩时，压力/温度跳变触发化学发光急剧增强——照片中出现"激波处突然亮起来"的视觉效果（Space.SE #16821 引述 Fiala 2015 及试车照片）。
- 固体推进剂中的 Al2O3 颗粒（2000–3000 K）也是强连续/紫外辐射源（EUCASS 2022-6191）；微量 Na 产生 589 nm 黄线（AGARD AR-287）。

### 1.4 气体带谱辐射（主要在红外，可见贡献小）
- CO2 4.3 μm、H2O 2.7/6.3 μm 等分子带主导红外；可见波段气体本身贡献很小。无颗粒的干净燃气即使复燃，可见照片上也可能是暗的（AGARD AR-287 明确警告：低颗粒羽流复燃时也可能"看不见"）。

---

## 2. 激波胞格为什么亮：压缩区增亮的物理

综合上述机制，shock diamonds 亮的因果链是：

1. 欠/过膨胀射流中，激波（或激波交汇/Mach 盘）造成**突跃压缩** → 温度 T 突升（正激波后 T 可升数倍）。
2. 增亮的三个放大器同时作用：
   - 碳烟/颗粒黑体辐射 ∝ T⁴ —— 温度跳变被四次方放大（煤油机、固机主导）；
   - 化学发光对压力/温度高度非线性（H2O*、OH*）—— 氢机蓝光主导；
   - 压缩加热区若卷入 O2，可点燃残余燃料（复燃点火）——进一步增亮（教学文献：Nozzles courseware, CUTM；Aerospaceweb 图示）。
3. 反之，**膨胀扇区**温度骤降 → T⁴ 律下辐射急剧变暗。因此激波胞格呈现"压缩区亮、膨胀区暗"的条纹结构，激波不是一条"亮线"，而是**压缩后的整个区域变亮**（亮度的阶跃沿流向发生在激波位置，亮区延续到下一个膨胀扇）。
4. 激波胞格的间距/可见度受压力比控制：第一个 Mach 盘位置 x/D_e ≈ (2/3)√(p_e/p_a 反比关系)（教学文献估计式）；室压升高使胞格间距拉长、胞格更清晰（Niu et al. 2017）。
5. 含金属/碳烟多的羽流，复燃火焰会**遮蔽**胞格结构；干净燃烧推进剂中胞格最清晰（Propellant Characterization, Hackaday 存档的业余固机教材）。

---

## 3. 不同推进剂的尾焰外观差异

| 推进剂 | 可见外观 | 主导机制 | 出处 |
|---|---|---|---|
| LOX/RP-1（Falcon 9, Saturn V F-1） | 明亮黄白色，碳烟火焰明显，胞格常被火焰部分遮蔽 | 碳烟黑体连续谱 + 复燃 | AGARD AR-287；RAeS 2022 |
| LOX/LH2（SSME） | 近乎透明，淡蓝色胞格清晰可见 | H2O*/OH* 化学发光（非平衡） | Sutton RPE ch.18；Fiala 2015 |
| 固体（Shuttle SRB） | 极亮白黄色，大量 Al2O3 颗粒尾迹 | Al2O3/颗粒连续谱 + 复燃 | EUCASS 2022-6191 |
| N2O4/UDMH（肼类） | 橙色-淡黄，中等亮度，红外强 | 气体带谱 + 复燃（CO2 4.3μm 等） | Sutton RPE ch.18 |
| 真空/上面级（任何推进剂） | 大幅膨胀、极暗淡、几乎不可见 | 密度骤降、无复燃、自由分子流 | Sutton RPE ch.18；Cai et al. 2022 |

---

## 4. 真空膨胀时的转捩与发光衰减

- 羽流随高度连续流 → 过渡流 → 自由分子流转捩（以梯度长度 Knudsen 数 Kn_GLL 判据，Cai et al. 2022, Aerospace MDPI 综述）。喷口附近仍连续，远场分子直线飞行，羽流直径可超 10 km。
- Sutton RPE 图 18-4（对数纵轴）：三级火箭总辐射强度随高度**数量级式**变化——上面级辐射极低，因为只有喷口附近无粘核心足够热、密度足够高才能辐射。
- 发光衰减的三重原因：(1) 密度 n 骤降 → 光学厚度 τ = κ·n·L 变薄，辐射 ∝ 1−exp(−τ) ≈ τ 线性下降；(2) 无环境 O2 → 复燃关闭（>30–40 km）；(3) 无外部压力约束 → 激波结构消失，无压缩增亮区。
- 例外：高空羽流可被太阳散射照亮（"twilight phenomenon"/太空水母），但那是散射阳光，不是自发辐射。

---

## 5. 对渲染亮度映射函数的具体建议（可执行）

### 5.1 亮度应映射到什么物理量
**主映射量：温度 T（经 T⁴ 律放大）× 光学厚度代理（密度 ρ × 路径长度）。** 不直接映射压力，但压力升高区（压缩区）与 T 升高区一致，所以激波胞格自然变亮。压缩区后**变亮**（post-shock 亮），膨胀扇后**变暗**。

### 5.2 建议的亮度公式（逐像素）

```
L(pixel) = L0 · ε_soot · f_afterburn(h, y) · (T/T_ref)^4 · (1 - exp(-κ·ρ·ℓ))
```

- `T/T_ref`：当地温度 / 喷口核心参考温度（如 T_ref = 出口气流温度）。黑体 T⁴ 律是亮度对比的物理来源；激波后 T 升 30% 即亮度 ×2.9，膨胀扇后 T 降 30% 即亮度 ×0.24——这自动产生"压缩亮/膨胀暗"条纹。
- `1 - exp(-κ·ρ·ℓ)`：光学厚度饱和项。ρ 为密度，ℓ 为沿视线/径向积分的路径长度代理（2D 渲染可用局部羽流宽度）。近喷口 ρ 大 → 饱和亮；真空膨胀 ρ 骤降 → 线性变暗。κ 取归一化常数。
- `f_afterburn(h, y)`：复燃增亮因子，低空、羽流边界混合层取值 1.3–1.4（文献增幅 31.5%–40%），核心超声速区取 1.0；高度 h > 30–40 km 时整体衰减到 1.0 再乘以全局衰减。
- `ε_soot`：推进剂相关发光系数（相对值，归一化）：
  - LOX/RP-1：1.0（基准，亮黄白色）
  - 固体（含铝）：1.2–1.5（更亮、带颗粒尾迹）
  - N2O4/UDMH：0.4–0.6
  - LOX/LH2：0.05–0.15（几乎透明，仅激波压缩区可见淡蓝）
- 全局高度衰减：连续流区亮度 ∝ ρ_ambient 相关因子；到真空时亮度再乘 ~0.01–0.1（Sutton 图18-4 的对数衰减），且关闭激波胞格渲染（无约束压力 → 无胞格）。

### 5.3 激波画法的具体修改建议
1. **不要把激波画成细亮线**。激波本身是间断面，物理上可见的是**激波后的增亮区域**。应把 T 场（或 T⁴ 场）直接作为亮度场渲染：激波位置自然呈现为亮度阶跃的亮边，亮区延续到下游膨胀扇。
2. 若一定要叠加"激波线"视觉效果，应画成"亮度阶跃的亮侧边缘 + 向下游衰减的柔光带（宽度约胞格间距的 1/3–1/2）"，而非等宽细线。
3. 亮度色随推进剂：RP-1 黄白（~2000–2600 K 黑体色），LH2 淡蓝（化学发光 ~400–500 nm 宽带），固体亮白。
4. 羽流边界混合层在低空应比同温核心区更亮（复燃因子 f_afterburn 的径向分布）。
5. 真空场景：羽流大幅扩张、亮度降 2 个数量级、无胞格、边缘无清晰边界（自由分子扩散）。

---

## 6. 出处清单（交叉验证 ≥ 3 独立来源）

1. Sutton, G. P. & Biblarz, O., *Rocket Propulsion Elements*（第8/9版）, 第18章 "Exhaust Plumes"：羽流辐射随高度对数衰减（图18-4）、H2/O2 羽流可见波段透明、复燃增亮助推级、真空密度分布（图18-3）。PDF: https://walpachicken.com/storage/pdf_promociones/1R9pmp6F4KZF3Bl2kqHwyW2tpvWgxrq3NR2whgC5.pdf
2. AGARD Advisory Report 287, *Terminology and Assessment Methods of Solid Propellant Rocket Exhaust Signatures*, NATO STO：颗粒连续谱主导可见辐射，发射率 0.05–0.2；Na 黄线 589 nm；低颗粒羽流复燃也可能不可见。https://www.sto.nato.int/publications/AGARD/AGARD-AR-287/AGR8DIA8287.pdf
3. Niu, Q. et al. (2017), "IR radiation characteristics of rocket exhaust plumes under varying motor operating conditions", *Chinese Journal of Aeronautics*：复燃增强辐射达 40%；室压升高胞格更清晰、间距拉长。https://www.sciencedirect.com/science/article/pii/S1000936117300912
4. Ren Hong-fan, Zhu Ding-qiang (2018), "液体火箭发动机尾焰复燃对红外辐射特性的影响", *推进技术* 39(6): 1227–1233：复燃使温度+15.4%、辐射+31.5%；增幅随高度下降（10 km 时仅 10.7%）。DOI: 10.13675/j.cnki.tjjs.2018.06.004
5. Li Xia et al. (2018), "Afterburning and infrared radiation effects of exhaust plumes for solid rocket motors", *Infrared and Laser Engineering* 47(9): 0904003：复燃升温达 1000 K，火焰带亮度增强 >10 倍。https://www.researching.cn/articles/OJ75d36e6f07643a6
6. Fiala, T. (2015), *Radiation from High Pressure Hydrogen-Oxygen Flames and its Use in Assessing Rocket Combustion Instability*, PhD thesis, TU München：氢氧焰蓝色可见辐射为 H2O* 化学发光宽连续谱，对压力非线性（21→30 bar 跳变）；OH* 310 nm。（经 Space.SE #16821 引述核对）https://space.stackexchange.com/questions/16821/
7. Decker, T. G. et al. (2025), "Infrared radiation from soot particles in rocket engine plume", ONERA：0.2% 碳烟质量分数即主导羽流 IR/可见辐射；激波胞格结构产生温度峰。https://hal.science/hal-05191034v1/file/Decker_2025_Infrared_Radiation_Soot_Rocket_Engine_Plume.pdf
8. Clout (2025), PhD thesis (HAL)：低空 UV/可见由复燃区化学发光+热辐射主导；>30–40 km 复燃消失，可见特征减弱，高空靠太阳散射。https://theses.hal.science/tel-05488177v1/file/151690_CLOUT_2025_archivage.pdf
9. Cai, G. et al. (2022), "A Review of Research on the Vacuum Plume", *Aerospace* 9(11): 706：真空羽流连续/稀薄流分区、Kn_GLL 转捩判据、CFD/DSMC 混合。https://www.mdpi.com/2226-4310/9/11/706
10. EUCASS 2022-6191：Al2O3 颗粒 2000–3000 K 强辐射；复燃与氧化铝浓度影响。https://www.eucass.eu/doi/EUCASS2022-6191.pdf
11. Nozzles courseware (CUTM)：激波突跃压缩→温度突升→OH/H 化学发光（310 nm 强 20 倍）→残余燃料点燃，Mach 盘及尾迹发亮；第一个 Mach 盘位置估计式。https://courseware.cutm.ac.in/wp-content/uploads/2020/06/Nozzles.pdf
12. RAeS Aerospace Magazine (Feb 2022)：RP-1 发动机排放大量黑碳烟。https://www.aerosociety.com/media/17742/aerospace-magazine-february-2022.pdf
