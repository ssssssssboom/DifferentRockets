# 新零件功能策划：机翼 / 空空导弹 / 涡扇发动机 / 螺旋桨

> 状态：策划 + 贴图已完成；**未**修改 PartList.xml 或任何 lua/java，集成为下一轮工作。
> 贴图目录：`game/core/assets/sprites/`（沿用 atlas 中既有美术风格：灰金属主体 + 垂直渐变 + 白色高光条 + 深红点缀带 + 深色描边 + 铆钉，约 30 px/游戏单位，侧视/俯视）。

## 0. 贴图清单

| 文件 | 尺寸(px) | 对应单位(约) | 内容 |
|---|---|---|---|
| `wing-1.png` | 240×120 | 8×4 | 机翼·无可动翼面（俯视平面形，红色翼根连接带 + 红色翼尖，翼肋面板线） |
| `wing-2.png` | 240×120 | 8×4 | 机翼·带副翼（后缘独立暗色副翼面板、铰链凸节、红色作动器摇臂，可作偏转动画参考） |
| `missile-1.png` | 60×240 | 2×8 | 空空导弹侧视（红色卵形弹头、导引头窗口、鸭翼 + 大尾翼、红色弹体环带、尾喷口） |
| `missile-2.png` | 60×60 | — | 导弹尾部正视（弹体圆截面 + 十字尾翼 + 喷口，供发射/尾部特写或编辑器图标用） |
| `turbofan-1.png` | 120×180 | 4×6 | 涡扇发动机侧视（顶部吊挂短柱、进气口唇口 + 风扇叶片暗示、红色环带、收敛尾喷口，朝向与现有发动机一致：喷口朝下） |
| `turbofan-2.png` | 120×120 | — | 涡扇进气口正视（12 叶片风扇盘，可叠加旋转动画表示转速） |
| `prop-1.png` | 180×180 | 6×6 | 螺旋桨侧视（桨毂整流罩 + 上下两叶桨、红色桨尖、发动机短舱 + 红色尾带） |
| `prop-2.png` | 30×90 | 1×3 | 单片桨叶（供运行时复制旋转做转动动画，红色桨尖） |

绘制脚本：`gen_new_part_sprites.py`（PIL，可复跑/调参）。

---

## 1. 机翼（Wing，两个版本）

### 1.1 物理参数建议

| 项 | wing-1（固定翼面） | wing-2（带副翼） |
|---|---|---|
| 默认尺寸 | 8×4 单位（编辑器内可 0.5x–2x 缩放，缩放同步改质量与升力面积） | 8×4 单位，同样可缩放 |
| mass | 0.5（约 250 kg，蒙皮+翼梁） | 0.6（多一套作动机构） |
| 连接点 | `LeftSide` / `RightSide`（翼根整边，breakForce 2000 / breakTorque 2500，与全件统一标定一致）；可选 `TopCenter` 便于背置 | 同左 |
| Shape | 梯形四顶点（翼根弦长 4，翼尖弦长 1.5，后掠），`ignoreEditorIntersections="true"` 便于贴机身叠放 | 同左 |
| drag | 设为 `drag="0.2"`（基线 Cd 略高于机身，因升力面迎风面积大；升力单独算，不走 drag 通道） | 同左 |
| Damage | disconnect 1200 / explode 2000，explosionPower 2 / explosionSize 5（翼面较脆，先断后爆） | 同左 |

### 1.2 Lua 行为策划（`wing-1.lua` / `wing-2.lua`）

- **气动升力**：每物理帧 `onUpdate` 中计算局部气流（船速 − 星球自转风速，密度经 `part:getAtmoDensity()`，该值已经过 physics.lua 的 `atmosphereDensity` / dragScale 缩放，升力公式沿用同一密度保持一致）：
  `L = 0.5 * rho * v^2 * S * CL(alpha)`，`CL ≈ 2π·alpha`（|alpha| < 15°，之后进入失速平台/衰减），方向垂直于局部气流。力施加在翼面 1/4 弦长处（`part:applyForceAt`），自然产生俯仰力矩。
- **副翼偏转（wing-2 专属）**：复用 `control.lua` 的输入语义——读 `part:getSteering()`：按钮模式（`buttonTurn ±1`）副翼满偏 ±25°；转向环模式按航向误差比例偏转；无输入回中。偏转以两种可叠加的方式生效：
  1. 直接对船体施加滚转/偏航力矩（量级按 `0.5*rho*v^2*S*CL_aileron*力臂`）；
  2. 改变本翼面有效迎角 ±（等效 CL 增量），产生左右不对称升力。
  实现上不需要改 control.lua——机翼脚本像发动机脚本一样 `part:readModText("control.lua")` 载入后调用同款输入约定即可。
- **积分点**：完全走现有 per-part lua 体系（onLoad/onUpdate + applyForceAt + getAtmoDensity），physics.lua 无需改动；若后续想让机翼的遮挡阻力参与 exposure 射线，沿用 `part:setDragCd` 接口。

---

## 2. 空空导弹（AAM）

### 2.1 物理参数建议

| 项 | 值 |
|---|---|
| 默认尺寸 | 2×8 单位 |
| mass | 0.3（总 150 kg，含战斗部与固体发动机） |
| 连接点 | `BottomCenter` 单点挂架连接（breakForce 150 / breakTorque 2500，对标 parachute/dock 的"弱连接、触发即分离"模式）；type 建议新设 `missile` |
| Shape | 矩形 2×8 即可（尾翼不计入碰撞，`ignoreEditorIntersections` 关闭） |
| Damage | disconnect 300 / explode 800，explosionPower 8 / explosionSize 15（战斗部当量高于油箱） |

### 2.2 Lua 行为策划（`missile-1.lua`）

- **两段生命周期**：
  1. **挂载段**：作为普通零件存在，质量计入船体；挂架连接点弱（150 kN），发射即断开。
  2. **飞行段**：`onStage`（或 ACTIVATE）触发——先点燃自身发动机 0.3 s 建立安全距离，随后从母船分离（脚本调用分离接口/或直接由弱连接被推力拉断），之后导弹本体作为独立最小飞船（自带指令逻辑）运行。
- **锁定与制导**：
  - 锁定：新增目标查询接口（集成点，见 §5）：枚举同星球域内其他飞船，选择视野锥 ±60° 内最近者，锁存其引用；丢失目标（距离 > 阈值或目标爆炸）进入比例导引最后的预测点自爆。
  - 制导：比例导引（PN, N=3）：每帧计算视线转率，横向加速度指令 `a = N * Vc * LOS_rate`，限幅 30 g；通过自身小发动机 + 鸭翼气动力（复用机翼 CL 简化式）实现。
- **动力与战斗部**：内置固体发动机（对标 engine-4 的 fuelType 3 模式：不可节流、燃尽即止），推力 ~ power 0.5（42.5 kN，推重比充裕）；燃时 3–5 s。战斗部触发：与目标距离 < 5 m 或碰撞时 `explode`（走现有 Damage/explosion 体系），破片简化为一团爆炸半径。
- **HUD**：导航环旁加"锁定框"指示（集成点：Runtime/渲染层，下一轮评估）。

---

## 3. 涡扇发动机（Turbofan）

### 3.1 物理参数建议

| 项 | 值 |
|---|---|
| 默认尺寸 | 4×6 单位（与 engine-2 同占地，便于替换） |
| mass | 0.9 |
| 连接点 | `TopCenter`（fuelLine，order 1）+ `BottomCenter`（order 2）+ `LeftSide`/`RightSide`（翼下吊挂），breakForce 2000 / breakTorque 2500 |
| Shape | 梯形（上窄下宽），沿用发动机惯例 |
| Damage | 同 engine-2（disconnect/explode 1500 量级） |

### 3.2 Lua 行为策划（`turbofan-1.lua`）

- **大气内高效推力**（与火箭发动机的差异化定位）：
  - 推力随气压：`T = T0 * throttle * (rho / rho0)`，密度经 `part:getAtmoDensity()`；真空（rho=0）推力为 0——大气外完全无效，这是与火箭发动机的核心差异。
  - 推力随空速：进气冲压收益曲线 `ramFactor = 1 + 0.4 * min(mach, 1.5)`（低速略亏、高速增益，上限后衰减模拟进气道溢出）；简化可直接用 `v / 340` 近似马赫数。
  - 油耗：BSFC 远低于火箭——同推力下 consumption 取火箭的 ~1/4（如 power 2.0 时 consumption 12–15 kg/s），鼓励大气内巡航。
  - 油门响应慢：`throttleExponential="false"`，脚本内对油门做一阶惯性（时间常数 ~2 s），模拟线轴加速。
- **燃料**：fuelType 0（化学燃料），走现有 fuelLine 管网，与 `part:drainFuel` 完全一致。
- **无摇摆**：turn=0，方向固定（或 ±3° 小角度辅助）；火焰表现用 `part:emitFlame` 低强度 + 后续可加进气口风扇旋转动画（turbofan-2.png 叠加旋转）。
- **集成点**：脚本结构照抄 engine-1.lua（onLoad 载入 control.lua 可选、onStage 解除保险、onUpdate 读油门/抽燃料/施加力），只改推力模型段，零引擎层改动即可先行落地。

---

## 4. 螺旋桨（Propeller，可自定义大小）

### 4.1 物理参数建议

| 项 | 值 |
|---|---|
| 默认尺寸 | 6×6 单位（编辑器 0.5x–2x 缩放，桨盘直径与功率同步缩放） |
| mass | 0.4（含电动机/活塞机） |
| 连接点 | `RightCenter`（短舱尾部，flipX 成对，group=1，对标 rcs/solar 的侧挂模式），breakForce 2000 |
| Shape | 短舱本体矩形 + 桨盘区域 `sensor="true"`（桨叶不撞东西，对标 dock-1 的 sensor shape 先例） |
| Damage | disconnect 800 / explode 1500（桨叶脆弱） |

### 4.2 Lua 行为策划（`prop-1.lua`）

- **推力模型**：`T = CT(rpm, 前进比 J=v/(n·D)) * rho * n^2 * D^4`。
  - 简化实现：`T = T_max * throttle * (rho / rho0) * max(0, 1 - v / v_pitch)`，v_pitch = 桨距速度（前进速度追平桨距速度后推力归零，自然限速，涡扇高速占优、螺旋桨低速占优的分工由此形成）。
  - D 取缩放后的桨盘直径：缩放零件 = 改 D，T 随 D^2 级增长、质量线性增长，大小自定义有意义但有代价。
- **能源**：默认电力（fuelType 2，对标 ion-0 的电池网络，`part:drainFuel(2, …)`），功率 = T·v/η；也可提供燃料版变体（fuelType 0）作为第二个 PartType。电力版强制玩家配电池/太阳能，形成与现有电力体系（solar/battery）的闭环。
- **转速表现**：脚本内维护 rpm 状态量，随油门惯性升降；渲染层用 `prop-2.png` 单桨叶旋转复制 3 片 + 高转速半透明模糊盘（下一轮集成时实现）。
- **反扭矩**：施加推力的同时对船体施加反作用力矩 `Q = P / (2πn)`，双桨玩家需对转布置（编辑器加 mirror 选项）——沙盒乐趣点。
- **集成点**：同发动机脚本骨架；不动 control.lua（螺旋桨无摇摆）；电力网络直接用现成 drainFuel 语义。

---

## 5. 与现有体系的集成点汇总（下一轮工作）

| 体系 | 需要的改动 | 备注 |
|---|---|---|
| PartList.xml | 新增 5–6 个 PartType（wing-1/2、missile-1、turbofan-1、prop-1、可选 prop-1-fuel） | 仅声明式，无代码 |
| ShipSprites 图集 | 将 8 张 png 打入 ShipSprites.png/.xml（TexturePacker 或手排） | 命名沿用 `sprite` 属性约定；本轮先存散件 png |
| mods/*.lua | 新增 wing-1.lua、wing-2.lua、missile-1.lua、turbofan-1.lua、prop-1.lua | 全部照 engine-1.lua 骨架，热重载体系自动生效 |
| control.lua | 不改；机翼/导弹读取同款 SteeringIO 输入约定 | 保持单一控制律哲学 |
| physics.lua | 不改；升力/螺旋桨密度都走 `part:getAtmoDensity()`（已含 dragScale） | 若需独立升力缩放系数，再加 `aero = { liftScale = … }` 表 |
| flame.lua | 涡扇/螺旋桨可复用 emitFlame；导弹加烟迹可选 | 后续美化项 |
| Java 桥接（GameWorld/Part API） | 导弹制导需要：枚举他船、读取他船位置/速度、跨飞船爆炸伤害 | 唯一可能需要 Java 侧新增 API 的零件；机翼/涡扇/螺旋桨纯 lua 可落地 |

## 6. 分阶段实施建议

1. **阶段 1（纯 lua，零 Java 改动）**：涡扇 + 螺旋桨 + 固定机翼（wing-1）。三者都只需新增 PartType + 单零件 lua，先验证大气内气动/推力模型的手感与平衡（密度缩放、升力系数、电力消耗）。
2. **阶段 2**：带副翼机翼（wing-2），接入 SteeringIO 输入语义，调滚转力矩增益；螺旋桨加桨叶旋转动画。
3. **阶段 3**：空空导弹。先做"无制导火箭弹"（发射即直线飞 + 近炸）验证分离/爆炸链路，再加目标枚举 API 与比例导引，最后做锁定 HUD。
4. **阶段 4**：平衡性迭代——用现有 smoke/探针遥测（HDG/ANGVEL/推力曲线）校验大气内飞机的静稳定性与导弹命中率，必要时在 physics.lua 增加 `aero` 调参表。

## 7. 平衡性基调

- 火箭发动机：全能但大气内费油；涡扇：大气内省油 4 倍、真空失效；螺旋桨：低速最省（电力）、有速度天花板。三者形成明确的速度/高度分工。
- 机翼只在大气内有效，给大气内飞行提供升力换取燃料节省，代价是质量、阻力和脆弱连接（先断后爆）。
- 导弹走"弱挂架 + 独立飞船 + 比例导引"路线，爆炸当量高于油箱、低于 SRB 殉爆，鼓励远程规避而非贴脸硬抗。
