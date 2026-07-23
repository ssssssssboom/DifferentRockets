# 23 · Lua 系统脚本：physics.lua / joints.lua / terrain.lua / planets.lua

> 本文精读 `game/core/assets/mods/` 下四个"系统级"内置 Lua 脚本（区别于零件脚本）：
> `physics.lua`（物理律）、`joints.lua`（连接点规则）、`terrain.lua`（地形生成）、`planets.lua`（太阳系定义）。
> 这四个文件不是零件，而是**游戏规则的开放层**：它们在首运行时随其余 33 个脚本一起拷贝到玩家目录
> `/sdcard/DifferentRocket/mod/`（仓库内的镜像副本在 `game/core/DifferentRocket/mod/`，与
> `assets/mods/` 内容逐字节一致，另有一份 `.defaults/` 备份），玩家改完即热重载生效。
>
> 引用格式：`physics.lua:9` 指 `game/core/assets/mods/physics.lua` 第 9 行；
> `PhysicsScript.java:118` 指 `game/core/src/com/differentrockets/game/PhysicsScript.java` 第 118 行。

## 0. 四脚本共通的加载/回退语义

| 脚本 | Java 桥接类 | 失败回退 |
|---|---|---|
| `physics.lua` | `PhysicsScript.java` | 任一函数报错 → 整个文件禁用（只记一次日志），内置物理律接管，修复保存后热重载恢复（physics.lua:5-7） |
| `joints.lua` | `JointScript.java` | 脚本缺失/报错/返回非表 → 退回内置规则（零件各自覆盖值、frequencyHz 高者胜）（JointScript.java:18-20, 71-78） |
| `terrain.lua` | `TerrainScript.java` | 任一入口报错 → 内置地形生成器静默接管（terrain.lua:128；TerrainScript.java:104-111） |
| `planets.lua` | `PlanetDefs.java` | 脚本不存在 → 内置 `assets/mods/planets.lua`；Lua 出错或无 root 天体 → 回退 `SmolarSystem.xml`（PlanetDefs.java:50-75） |

注意 `planets.lua` 与其余三者不同：它只在**加载太阳系时执行一次**（`definePlanet` 收集定义表），不参与每帧热重载循环。

---

## 1. physics.lua —— 物理律（玩家可改）

文件头自描述："Replaces the built-in physics. All functions are optional"（physics.lua:3-7）。
版本标记 `v2026.07.22`（physics.lua:1）。

### 1.1 暴露的接口总览（physics.lua:9-31）

```lua
gravityAccel(x, y, timeSec) -> ax, ay          -- 引力律，宇宙坐标（米），返回 m/s^2
atmosphereDensity(planetName, altitude) -> kg/m^3  -- 大气密度律
steering = { kp, ki, kd }                      -- 已废弃（见 1.2）
joints    = { frequencyHz, dampingRatio, angularDamping }  -- 焊接点全局调参
gimbal    = { kp, ki, kd, maxRateDeg }         -- 已废弃（见 1.2）
```

每个函数都可单独删除——删掉哪个，内置律就接管哪个；任何一个运行时报错则**整个文件**被禁用
（physics.lua:5-7；PhysicsScript.java:76-88）。

### 1.2 两张"已废弃但保留"的表

- **`steering = { kp = 1.8, ki = 0.5, kd = 1.2 }`（physics.lua:41）**：第 12 轮起飞船级 PI 转向控制器
  被移除，转向指令改由 SteeringIO（转向环/按钮）产生，发动机 gimbal 控制律移到 `mod/control.lua`
  的 `controlLaw`。此表仅为兼容旧玩家脚本而保留，**没有任何代码读它**（physics.lua:37-40）。
- **`gimbal = { kp = 8.0, ki = 0.1, kd = 0.6, maxRateDeg = 90.0 }`（physics.lua:105）**：第 12 轮起
  每发动机 gimbal PID 执行器被 `control.lua` 的共享控制律取代（gimbal 偏转 = 截断后的航向误差，
  控制链路中不再有 PID）。同样仅为兼容保留（physics.lua:101-104）。Java 侧对应的默认值读取器
  仍在（PhysicsScript.java:163-169），但发动机脚本已不再读取。

### 1.3 `joints` 表 —— 焊接点全局调参（physics.lua:43-73）

```lua
joints = { frequencyHz = 20.0, dampingRatio = 1.1, angularDamping = 0.6 }   -- physics.lua:73
```

| 键 | 含义 | 代码中的说明 |
|---|---|---|
| `frequencyHz` | 每个零件间焊接点的弹簧刚度。越高越硬（下垂/弯曲越少），过高会让求解器抖动。建议区间 12..25，**必须低于 60 Hz 物理频率的一半**（physics.lua:46-49） | 历史值：was 12.0 |
| `dampingRatio` | 焊接阻尼比。1.0 = 临界阻尼（无弹性振荡），>1 过阻尼；低于约 0.9 时火箭在点火/转弯后可见晃动（physics.lua:50-52） | 历史值：was 1.05 |
| `angularDamping` | 每个零件的 Box2D body 旋转阻尼（0 = 无）。**人工微调约定**完整记录在注释里：第 12 轮因 control.lua 控制律是纯比例（无微分项），机械阻尼成为唯一合法稳定器，0.08 → 0.6；第 13 轮 telemetry（--smoke 的 HDG/ANGVEL）表明该律下飞船不会指数收敛，而是进入目标附近 ±3..4° 的极限环，环幅由非线性被控对象（燃料消耗改变质心/转动惯量）决定而非阻尼；阻尼只控制目标附近有界平台的相位和宽度。实测平台：2.0 只在 t=10s 达标；3.0 余量 0.1°；2.5 在 t=6..10s 全部样本距目标 1.85° 内 ≈ 4 秒相位容忍，是最稳健的窗口。代价：持续转弯速率 ~1/阻尼，粗调手感比 PID 时代"沉"（physics.lua:53-72） | 注释叙述的终点是 2.5，但**当前表里的实际取值是 0.6**（physics.lua:73），Java 硬编码兜底值是 0.08（PhysicsScript.java:118）——三者不一致，以 Lua 表值 0.6 为运行时实际生效值，接手者需注意这段注释记录的是调参过程而非最终值 |

**零件级覆盖**（第 9 轮，physics.lua:75-82）：零件脚本可在 `onLoad` 里调用
`part:setJointParams{frequencyHz=…, dampingRatio=…, angularDamping=…}` 覆盖任意键
（nil 键 → 本表 → Java 默认值）。两个零件对焊时 **frequencyHz 高者胜**（更硬的一侧说了算），
其 dampingRatio 随同；angularDamping 作用于零件自身 body。内置示例：`strut-1.lua` = 超刚性（35 Hz），
`dock-1.lua` / `port-1.lua` = 更软（8 Hz，让对接组合体略有弹性）。

**连接级规则**（第 11 轮，physics.lua:84-88）：`mod/joints.lua` 决定每一处焊接的最终四参数，
能看到两侧零件和两个连接点（详见第 2 节）。

### 1.4 `gravityAccel(x, y, timeSec)` —— 引力律与多体兼容性声明（physics.lua:107-124）

默认实现：**对每个天体做牛顿 GM/r² 求和**——文件头明确声明这是可替换点：
"Default: Newtonian sum of GM/r^2 over every planet. Replace with n-body propagation,
relativistic corrections, J2 oblateness, ..."（physics.lua:11-12）。这就是**多体兼容性声明**：
接口按"遍历全部天体"设计（不是只问最近天体），玩家可以直接换成 n-body 积分、相对论修正或
J2 扁率项而不改签名。

逐行逻辑（physics.lua:107-124）：

1. 遍历 `world:planetCount()` 个天体，跳过 `mu <= 0` 的（physics.lua:109-111）；
2. 计算到天体中心的位移 `(dx, dy)` 与距离平方 `r2`（physics.lua:112-114）；
3. **奇异保护**：`r` 被钳到 `planetRadius * 0.5` 的下限，防止钻进天体内部时加速度发散
   （physics.lua:116-117）；
4. `a = mu / (r2 * r)` 即 GM/r² 乘单位向量（1/r），累加到 `(ax, ay)`（physics.lua:118-121）。

**性能约束**：该函数在 Lua 里**每个零件每个物理 tick** 各跑一次（physics.lua:33-34；
PhysicsScript.java:22-24），注释要求"keep it cheap"——几十零件的规模下默认循环没问题，
若 profiling 发现问题应改为每船每帧批量调用。

### 1.5 `atmosphereDensity(planetName, altitude)` —— 大气密度律（physics.lua:126-132）

```lua
function atmosphereDensity(planetName, altitude)
  local e = planetEnv[planetName]
  if e == nil or e.atmoHeight <= 0 or e.surfacePressure <= 0 then return 0 end
  if altitude > e.atmoHeight or altitude < -e.scaleHeight * 3 then return 0 end
  return 1.225 * e.surfacePressure * math.exp(-math.max(altitude, 0) / e.scaleHeight)
end
```

- 指数模型：海平面密度 `1.225 kg/m³ × surfacePressure`，按 `scaleHeight` 指数衰减（physics.lua:130-131）；
- 无大气天体、海拔高于 `atmoHeight`、或低于 `-3 × scaleHeight`（地下深处）都返回 0（physics.lua:128-129）；
- 负海拔（低于名义半径）按海平面密度算（`math.max(altitude, 0)`）；
- 与 Java 内置模型 `Planet.densityAt` **逐式一致**（Planet.java:58-62），`scaleHeight = atmoHeight / 7`
  （Planet.java:55）；
- **第 14 轮修复**：阻力计算一度直接调内置 `Planet.densityAt`，导致玩家改 `atmosphereDensity`
  对阻力无效；现在阻力走 `GameWorld.densityAt`（内部先问 Lua）（GameWorld.java:803-807）。

### 1.6 大气阻力公式（遮挡感知，第 11 轮；physics.lua:90-99 + GameWorld.java:799-829)

physics.lua 以注释形式颁布阻力模型（实现不在 Lua，在 Java）：

```
F = 0.5 * rho * v^2 * Cd * area * exposure          -- physics.lua:91
```

- `rho`：来自 1.5 的 `atmosphereDensity`（玩家可改，GameWorld.java:807）；
- `Cd`：零件自己的 `part:setDragCd` 覆盖值优先，否则 `0.75 + PartList.xml 的 drag 属性`
  （nosecone 的 `drag="-1.0"` 会从全船总量里减）（physics.lua:92-93；GameWorld.java:819-821）；
- `area`：`part:setDragArea` 覆盖值，否则零件类型宽度（GameWorld.java:822）；
- `exposure`（0..1）：游戏计算的**遮挡系数**——对每个零件的轮廓向上风方向投射 8 条采样射线，
  完全被队友零件挡住的零件（如整流罩内的油箱）几乎不受阻力，迎风边缘的零件全额受力，
  因此头锥真实地屏蔽后方零件。约 15 次/秒刷新，气流方向摆动超过约 8° 时也刷新
  （physics.lua:93-98；Ship.java:469-471，其中 8° 判据写作 `sin(8°) ≈ 0.1392`）。
  脚本可用 `part:getDragExposure()` 读取当前值（physics.lua:99；ModApi.java:251-253）。

> **关于"0.1 系数"的事实核查**：交接任务中提到的"大气阻力公式含 0.1 系数的历史调整"，
> 在当前代码中**不存在**——阻力公式里没有任何 0.1 系数（GameWorld.java:823 为
> `0.5 * rho * speed2 * cd * area`），physics.lua、 joints.lua、terrain.lua、planets.lua
> 及桥接 Java 文件中也没有与阻力相关的 0.1。physics.lua 中唯一的 0.1 是已废弃表
> `gimbal.ki = 0.1`（physics.lua:105）及其 Java 兜底（PhysicsScript.java:166）。
> 若旧文档/口头传闻提到阻力 0.1 系数，应是更早版本的事，当前版本不可考，以本节的公式为准。

### 1.7 注入 physics.lua 的全局（physics.lua:25-31；PhysicsScript.java:29-65）

| 全局 | 内容 |
|---|---|
| `world:planetCount()` | 天体数量 |
| `world:planetName(i)` | 第 i 个天体名（0 起） |
| `world:planetX(i)` / `world:planetY(i)` | 宇宙坐标（米），**live 代理**，随轨道运动实时变化 |
| `world:planetMu(i)` | 引力参数 GM = gravity × radius²（Planet.java:52） |
| `world:planetRadius(i)` | 名义半径（米） |
| `planetEnv[name]` | `{ atmoHeight, surfacePressure, scaleHeight }`，绑定时一次性注入的静态表（PhysicsScript.java:57-65） |

---

## 2. joints.lua —— 连接（焊接点）统一规则（玩家可改）

文件头为中文（physics.lua 之后补写的轮次），版本 `v2026.07.21`（joints.lua:1）。
第 11 轮引入：**每形成一处连接**，游戏都调用 `jointParams(partA, attachA, partB, attachB)`，
用返回表覆盖默认焊接参数（joints.lua:5-7；JointScript.java:44-79）。函数可整段删除——
删除或报错后退回内置规则（与第 11 轮之前行为一致）。

### 2.1 参数（joints.lua:9-15）

- `partA` / `partB`：两个零件的 Lua API 对象（同 `onLoad`/`onUpdate` 里的 `part`）；
- `attachA` / `attachB`：连接点表，字段：

| 键 | 类型 | 含义 |
|---|---|---|
| `x`, `y` | number | 连接点在零件局部坐标中的位置（米） |
| `fuelLine` | boolean | 是否燃油管路点 |
| `edge` | number | 0 = 普通单点；1 = 左边；2 = 右边；3 = 顶边；4 = 底边（整条边可连） |
| `breakForce` | number | 该连接点的断裂力上限（千牛）；**不可断的点省略此键**——Java 侧仅当 `breakForce != Float.MAX_VALUE` 时才写入（JointScript.java:87） |

### 2.2 返回表（键全部可省略，省略即用默认值；joints.lua:17-23）

| 键 | 含义 |
|---|---|
| `frequencyHz` | 焊接弹簧频率（Hz）。2-5 松软像橡胶，20 为默认刚性，40+ 接近不可弯，过大可能抖动 |
| `dampingRatio` | 阻尼比。<1 欠阻尼（回弹），1 临界阻尼，>1 过阻尼（发黏） |
| `angularDamping` | 两零件的角速度阻尼（每秒衰减比例，0 = 不衰减）。注意它**不是连接属性**：Java 直接对两个 body 调 `setAngularDamping`（JointScript.java:63-68） |
| `breakForce` | 断裂力上限（千牛）。省略时 Java 侧标记 -1，由调用方取两连接点的较小值（JointScript.java:36, 62）；想造永不分离的连接填 `1e18`（joints.lua:22-23） |

省略键的兜底链：Lua 返回表 → `physics.lua` 的 `joints` 表 → Java 硬编码默认值
20 / 1.1 / 0.08（JointScript.java:58-61；PhysicsScript.java:118, 125-134）。

### 2.3 默认实现逐行说明（joints.lua:31-54）

默认实现**还原了内置规则**，是改造的基准：

1. 读两侧零件在 `onLoad` 里用 `part:setJointParams{...}` 设的覆盖值
   `oA = partA:getJointParams()`、`oB`（joints.lua:33-34）；
2. **frequencyHz 高者胜**：`fA` 存在且（`fB` 不存在或 `fA >= fB`）→ 取 A 的频率和 A 的阻尼比；
   否则取 B 的（joints.lua:37-43）——"更硬的一侧说了算，它的 dampingRatio 随同"；
3. 返回表：
   - `frequencyHz = freq or partA:physicsNumber("joints", "frequencyHz")` —— 覆盖缺省时读
     physics.lua 的 `joints` 表，再缺省用内置 20（joints.lua:47）；
   - `dampingRatio` 同理，兜底 1.1（joints.lua:48）；
   - `angularDamping = oA.angularDamping or oB.angularDamping or physicsNumber(...)` ——
     任一侧有覆盖就用覆盖（A 优先），否则全局默认（joints.lua:50-51）；
   - `breakForce` 不返回 → Java 取两连接点较小值（joints.lua:52-53）。

文件头给出的三个改造方向（joints.lua:25-28）：按零件类型定软硬
（`partA:getTypeId() == "strut-1"`）、按连接点位置定软硬（`attachA.edge ~= 0`）、
让引擎座更容易断（`attachA.breakForce`）。

---

## 3. terrain.lua —— 行星地形生成（玩家可改）

版本 `v2026.07.24.1`（terrain.lua:1）。第 18 轮重写为**柱状地形（COLUMNAR TERRAIN）**模型。

### 3.1 柱状地形模型（terrain.lua:5-10）

地表是环绕行星一圈的**四边形列环**：每列宽 `blockWidthM` 米（弧长），第 i 列是
"h[i]、h[i+1] 两个交界处高度（顶边）与同一条边向下 `depthM` 米（底边）之间的四边形"。
交界高度全部来自 `surfaceHeight()`，列与列共享交界，**天然无缝**；同一份数据同时构建
渲染网格和碰撞 fixture——"所见即所撞"（terrain.lua:8-10）。

### 3.2 `terrainRender` 参数表（热重载；terrain.lua:12-44）

```lua
terrainRender = {
  blockWidthM = 4.0, depthM = 32.0, rangeM = 100000.0, physicsRangeM = 10000.0,
  friction = 1.0, restitution = 0.0,
  topBrightness = 1.35, bottomBrightness = 0.25, bandVariation = 0.06,
  texture = nil, deepColor = { 0.23, 0.15, 0.09 },
}
```

逐字段：

| 字段 | 默认 | 含义 |
|---|---|---|
| `blockWidthM` | 4.0 | 每列的地表弧长（米）。越小地面越平滑，列/fixture 数量越多（terrain.lua:13-14）。这就是"4 m 宽四边形列环"的出处 |
| `depthM` | 32.0 | 表皮下的碰撞/壳体深度（米）。这个深度加零件的 bullet-CCD 共同阻止高速穿地（terrain.lua:15-16） |
| `rangeM` | 100000.0 | 以飞船为中心的加载/渲染窗口半径（±米）。网格覆盖此范围（terrain.lua:17, 19）。窗口管理 10 Hz 刷新（TerrainSystem.java:86 `REFRESH_S = 0.1`），即"10Hz 局部加载" |
| `physicsRangeM` | 10000.0 | 碰撞体窗口（±米）。**Box2D fixture 只在此范围内存在**，范围外只有渲染网格（terrain.lua:18-19；TerrainSystem.java:53） |
| `friction` | 1.0 | 列表面摩擦；约 1.0 可阻止出生后的侧滑（terrain.lua:20） |
| `restitution` | 0.0 | 弹性系数；0 = 无弹性（terrain.lua:21） |
| `topBrightness` | 1.35 | 地表皮肤亮度乘数，作用于行星地壳色（planets.lua 的 `terrain.color`），有钳制（terrain.lua:22-23） |
| `bottomBrightness` | 0.25 | 壳体底部亮度乘数（terrain.lua:24） |
| `bandVariation` | 0.06 | 确定性的逐列顶部亮度抖动幅度（±此比例）；0 = 完全平滑渐变（terrain.lua:25-26） |
| `texture` | nil | nil = 程序化渐变；或填资源名（如 `"PlanetCrustSmearth.png"`）拉伸铺满每列四边形；**玩家在 assets/ 里的同名副本优先于内置**（terrain.lua:27-29） |
| `deepColor` | {0.23, 0.15, 0.09} | 壳体以下实心块到可见地壳底部的颜色（0..1 RGB，深棕默认）（terrain.lua:30-31） |

Java 侧在热重载时整表重读并重建地形（"Any change rebuilds"，TerrainSystem.java:175-213）。

### 3.3 `specialTerrains` —— 手工区域（第 18 轮；terrain.lua:46-71）

按行星列出的手工 authoring 区域列表。在 `|x - center| < range`（弧长米）内，地表基准高度
**不再是自然地形**，而是在关键点之间做 **smoothstep 插值**，再叠加 `noise` 米的**绝对**
确定性抖动（terrain.lua:47-51）。

> **第 18 轮修复**：旧版把抖动乘以**整个自然地形高度**，山区里手工区域会被自然地形整个淹没；
> 现在是绝对米数抖动（terrain.lua:49-51, 253-254）。

区域表字段：

| 字段 | 含义 |
|---|---|
| `center` | 区域中心弧长位置（米，自角度 0 起） |
| `range` | 区域半径（弧长米） |
| `blend` | 外缘混合比例，默认 0.2：range 的外侧 `blend`  fraction 用 smoothstep 混回自然地形。区域高度与周围差得大时调宽它，让边缘是坡不是墙（terrain.lua:52-54） |
| `noise` | 绝对抖动幅度（米），可省略 |
| `points` | 关键点 `{x, h}` 列表，x 为弧长米、h 为高出名义半径的米数，**从左到右排列**（terrain.lua:55-56） |

内置两个区域都在 Smearth（terrain.lua:58-70）：

1. **滨海平原**（center 730 km，range 10 km，blend 0.5，noise 2.0）：从浅海区升起的 +6 m 平台
   （弧 720-740 km 是 Smearth 上最平的低地）；50% 的宽 blend 把约 1.3 km 高差拉成长坡；
2. **赤道高地山脊**（center 58 km，range 8 km，noise 40.0）：比周围自然山地高约 800 m 的山峰，
   4 个关键点（50 km/2300 → 56 km/2900 → 60 km/3200 → 66 km/2350），端点高度贴合当地自然
   高度，所以没设大 blend。

插值细节（surfaceHeight 内，terrain.lua:236-252）：x 落在首/尾关键点之外时钳到端点高度；
落在相邻关键点之间时 `t = t²(3-2t)` smoothstep。

### 3.4 `flattenPad` —— 发射台削平（第 13 轮；terrain.lua:73-100）

飞船永远出生在 90°（行星顶端，发射代码里的 `padAngle`）。斜的出生台会让新生成的飞船无论如何
都侧滑，所以高度函数在出生角度附近向一个水平平台混合：

| 字段 | 默认 | 含义 |
|---|---|---|
| `flattenPad.enabled` | true | 总开关 |
| `flattenPad.angleDeg` | 90.0 | 台中心角度（度），90 = 出生点 |
| `flattenPad.halfWidthM` | 24.0 | 削平区半宽（地表弧长米）。高度跨此半宽用 smoothstep 混回自然地形，台中心完全水平，边缘与坡面相切（terrain.lua:79-83） |

**`padRadii` 表（terrain.lua:92-99）**：米→角度换算需要行星半径，这里硬编码了全部 16 个天体的
半径，注释要求**与 planets.lua 的 `radius=` 保持同步**——planets.lua 里改了半径而这里没改，
削平宽度就会错；不在表里的行星不削平（terrain.lua:84-86, 171-172）。
`padHeightCache` 缓存各行星台中心的自然高度（terrain.lua:100, 183-189）。

### 3.5 `baseTerrainHeight(planetName, angleRad)` —— 自然地形（内部函数；terrain.lua:133-166）

不含发射台削平的自然地形高度（也用于取台中心参考高度）：

1. 查 `planetInfo[planetName]`，未知行星返回 0（terrain.lua:134-135）；
2. **高度带**：遍历 `info.ranges`，第一个命中角度区间的 range 胜出，用它的
   `minHeight/maxHeight` 覆盖全局值；`startAngle > endAngle` 时按跨 0° 处理（terrain.lua:139-148）；
3. 跨度 ≤ 0.0001 直接返回下界（terrain.lua:150-151）；
4. **4 个八度的包裹值噪声**：种子 = `noise.hash(planetName)`（与 Java `String.hashCode()` 兼容，
   TerrainScript.java:83-86），基础频率 `floor(max(2, 6 + noise*0.6) + 0.5)`，每八度频率翻倍、
   振幅减半，种子按 `oct * 131.7` 错开（terrain.lua:153-162）；
5. 噪声归一化到 [0,1] 后过**粗糙度整形曲线** `rough = min(2.5, 0.25 + noise*0.28)`，
   返回 `lo + span * n01^rough`（terrain.lua:163-165）。

### 3.6 `terrainHeight(planetName, angleRad)` —— 旧接口（terrain.lua:168-193）

签名与语义（terrain.lua:105-108）：输入行星名 + 地表角度（弧度，世界系，0 = +x 轴），
返回**高出/低于名义半径的米数**。默认实现 = `baseTerrainHeight` + 发射台削平混合
（`t = |dA|/halfA`，`s = t²(3-2t)`，返回 `hc + (h-hc)*s`，terrain.lua:190-192）。

可用数据与助手（terrain.lua:111-120）：`planetInfo[name] = { minHeight, maxHeight, noise, ranges }`
（值来自 planets.lua 的 `definePlanet`）；`noise.value1(x, period, seed)`（周期包裹的无缝 1D 值噪声，
[-1,1]）、`noise.value2(x, y, seed)`、`noise.hash(string)`。

**确定性铁律**（terrain.lua:122-124）：同行星同角度必须永远返回同一高度——碰撞和视觉在不同
时刻生成，必须用固定种子的 `noise.*`，**绝不可用 `math.random()`**。本文件出错时内置生成器
静默接管（terrain.lua:128）。

### 3.7 `regionJitter(info, x, seed)` —— 区域抖动（内部；terrain.lua:212-224）

specialTerrains 用的确定性绝对抖动，[-1,1]：3 个八度的同款无缝值噪声，晶格周期包裹到行星
周长（约 1 km 基础特征），使 x 与 x+周长无缝衔接；种子 = `noise.hash(行星名) + seed`。

### 3.8 `surfaceHeight(info, x)` —— 新接口及其与 terrainHeight 的关系（terrain.lua:195-270）

签名（terrain.lua:200-203）：`info` 是游戏注入的该行星星球表
`{ name, radius, minHeight, maxHeight, noise, ranges }`（TerrainScript.java:47-67），
`x` 是自角度 0 起的**弧长米**；返回**绝对半径米数**（R + 地形高度，注意与 terrainHeight 的
"相对高度"不同）。

- 调用频率：地形系统**每个新交界只调一次**，结果 Java 侧缓存；必须是 (info, x) 的确定性纯函数
  （terrain.lua:196-199；TerrainScript.java:114-119）；
- 默认实现（terrain.lua:226-269）：先取自然地形 `terrainHeight(info.name, x / R)`
  （**含发射台削平**），再叠加 specialTerrains 区域：
  - 命中区域 → 关键点 smoothstep 基准 + `noise × regionJitter(info, x, i*7919)` 绝对抖动，
    外缘 `blend` 段按 `w` 混回自然地形，返回 `R + natural + (special - natural) * w`；
  - 未命中 → `R + natural`。

**新旧函数关系（接手重点）**：

| | `terrainHeight`（旧） | `surfaceHeight`（新，第 18 轮） |
|---|---|---|
| 输入 | (行星名, 角度 rad) | (info 表, 弧长米) |
| 返回 | 相对名义半径的高度 | 绝对半径 |
| 发射台削平 | 含 | 含（内部调 terrainHeight） |
| specialTerrains | **不知道** | 知道 |
| 用途 | 保留为自然高度数据源；出错回退链的一环 | 柱状地形（渲染网格 + 碰撞 quads）的唯一数据源 |

Java 侧所有**玩法层**表面查询（高度表、出生台、轨道地板、水面）走
`TerrainScript.heightAboveDatum`，它**路由到 surfaceHeight**——第 18 轮修复：旧
terrainHeight 路径不知道 specialTerrains，飞船可能停在手工区域上方/下方的"隐形平面"上
（TerrainScript.java:139-159）。因此：**改地形行为优先改 `surfaceHeight`；只改
`terrainHeight` 会影响自然地形但不影响手工区域。**

---

## 4. planets.lua —— 默认太阳系（Smolar System）16 天体定义

版本 `v2026.07.21`（planets.lua:1）。文件头声明：与内置 `SmolarSystem.xml` 等价；
`radius + terrain` 同时驱动渲染地壳块和碰撞高度场；`orbit = {a, e, w, v, prograde}`，
**角度一律弧度**；`mapColor` / `terrain.color` 为 0-255 RGB；`prograde`：1 = 顺行，0 = 逆行
（planets.lua:2-6）。

### 4.1 `definePlanet{...}` 格式与解析默认值

加载机制（PlanetDefs.java:50-75）：优先玩家目录 `mod/planets.lua` + `mod/planets/*.lua`
（后者可拆文件，**同名后定义覆盖先定义**，PlanetDefs.java:87-89）；都没有才用内置
`assets/mods/planets.lua`；Lua 失败回退 XML。无 `parent` 的定义是 root（必须恰好有一个，
即 Sun），`parent` 必须在已定义的名字里（PlanetDefs.java:110-129）。

字段与缺省值（PlanetDefs.java:133-181）：

| 字段 | 缺省 | 说明 |
|---|---|---|
| `name` | 必填 | 天体名；`padRadii`/`specialTerrains`/`planetEnv` 都按它索引 |
| `parent` | nil | 母天体名；省略即 root |
| `gravity` | 0 | 表面重力（m/s²）；GM = gravity × radius²（Planet.java:52） |
| `radius` | 1000 | 名义半径（米） |
| `mapColor` | 灰 0.7 | 地图视图颜色，{r,g,b} 0-255 |
| `icon` | nil | 地图图标资源名 |
| `launchEnabled` | **true** | 是否允许作为发射场（Sun、彗星、各 Jr 卫星显式 false） |
| `description` | "" | 描述文本 |
| `orbit` | 全 0 | `{a, e, w, v, prograde}`：a 半长轴（米）、e 偏心率、w 近点幅角（弧度）、v 初始真近点角（弧度）、prograde 1/0 |
| `atmosphere` | 无大气 | `{height, surfacePressure}`：大气顶高度（米）、海平面气压（1.0 = Smearth）；scaleHeight 派生为 height/7 |
| `terrain` | 平地 | 见 4.2 |

### 4.2 `terrain` 子表（planets.lua 每个天体都有）

| 字段 | 缺省 | 说明 |
|---|---|---|
| `maxHeight` | 0 | 全局地形高度上界（米） |
| `minHeight` | 0 | 全局下界；负值 = 海盆（Smearth -1000、Titan Jr -800） |
| `noise` | 2.0 | 粗糙度参数，喂给 terrain.lua 的基础频率与整形曲线 |
| `texture` | nil | 地壳贴图资源名（如 `"PlanetCrustSmearth.png"`） |
| `color` | (0.4,0.3,0.2) | 地壳颜色 {r,g,b} 0-255，terrainRender 的亮度乘数作用于它 |
| `waterDensity` | 0 | 水密度；>0 且地形高度 <0 时产生浮力（Smearth/Titan Jr = 75；GameWorld.java:831-843） |
| `ranges` | 无 | 高度带列表 `{startAngle, endAngle, minHeight, maxHeight}`（角度**度**），terrain.lua 里"第一个命中者胜" |

### 4.3 16 天体一览

| # | name | parent | gravity | radius (m) | 大气 (height/P0) | 轨道特征 | launchEnabled |
|---|---|---|---|---|---|---|---|
| 1 | Sun | — (root) | 274.0 | 69 634 200 | 无 | 无 orbit | false（planets.lua:10） |
| 2 | Smercury | Sun | 3.7 | 243 970 | 无 | e=0.2056（最扁的行星轨道） | true |
| 3 | Smenus | Sun | 8.87 | 605 180 | 250 000 / 93.0 | e≈0.0068 | true |
| 4 | Smearth | Sun | 9.798 | 637 100 | 70 000 / 1.0 | e≈0.0167 | true |
| 5 | Smoon | Smearth | 1.622 | 173 710 | 无 | e=0.0549 | true |
| 6 | Smalley's Comet | Sun | 0.01 | 8 000 | 无 | **e=0.967，prograde=0（逆行）**（planets.lua:59） | false |
| 7 | Smars | Sun | 3.71 | 339 600 | 95 000 / 0.0059405 | e≈0.0933 | true |
| 8 | Smupiter | Sun | 24.79 | 6 991 100 | 125 000 / 1.0 | e≈0.0488 | true |
| 9 | Ganymede Jr | Smupiter | 1.428 | 263 410 | 无 | e=0.0013 | false |
| 10 | Europa Jr | Smupiter | 1.314 | 156 000 | 无 | e=0.0009，**maxHeight=0（冰面）**（planets.lua:92） | false |
| 11 | Io Jr | Smupiter | 1.796 | 182 160 | 无 | e=0.0041 | false |
| 12 | Callisto Jr | Smupiter | 1.235 | 241 030 | 无 | e=0.0074 | false |
| 13 | Smaturn | Sun | 10.44 | 6 026 800 | 275 000 / 1.0 | e≈0.0557 | true |
| 14 | Titan Jr | Smaturn | 1.352 | 257 600 | 215 000 / 1.46 | e=0.0288；有水和高度带（planets.lua:123-129） | false |
| 15 | Smuranus | Sun | 8.69 | 2 555 900 | 125 000 / 1.0 | e≈0.0444 | true |
| 16 | Smeptune | Sun | 11.15 | 2 476 400 | 88 022 / 1.0 | e≈0.0112 | true |

要点：

- 层级：Sun 为 root；Smoon 绕 Smearth；四颗 "Jr" 卫星绕 Smupiter；Titan Jr 绕 Smaturn
  ——共三级（planets.lua 各 `parent` 字段）。
- 特殊地形：`ranges` 只在 Smenus（85-87° 高地 4000-4500）、Smearth（20-89° 大洋 -2000~-1000、
  91-93° 高山 4000-8000）、Titan Jr（80-110° 高地、135-180° 海盆）出现。
- 贴图复用：Ganymede/Europa/Io Jr 共用 `PlanetCrustSmoon.png`，Callisto Jr 用
  `PlanetCrustSmercury.png`（planets.lua:85-106）。
- 气态巨行星（Smupiter/Smaturn/Smuranus/Smeptune）都有 `surfacePressure = 1.0` 的大气和高噪声地形。

---

## 5. 接手注意事项速查

1. **改完 planets.lua 的 radius，必须同步 terrain.lua 的 `padRadii`**（terrain.lua:84-86），
   否则发射台削平宽度出错。
2. **改物理先改对应 Lua 函数而不是 Java**：阻力密度、引力、连接参数都有"Lua → 内置"回退链，
   改 Java 内置值只对脚本被禁用/删除时生效。
3. **已废弃表不要再接线**：`steering`、`gimbal`（physics.lua:41, 105）无读者；真正的控制律在
   `mod/control.lua`。
4. **angularDamping 三处数值不一致**是已知状态：Lua 表 0.6（生效值）、注释叙述的调参终点 2.5、
   Java 兜底 0.08。要动它先看 physics.lua:53-72 的整段 telemetry 结论——阻尼只调相位和平台宽度，
   治不了极限环。
5. **terrain 全链路确定性**：terrain.lua 任何函数里出现 `math.random()` 都会破坏"渲染=碰撞"
   的不变量（terrain.lua:122-124, 198）。
6. 所谓"阻力 0.1 系数"在当前代码中不存在，见 1.6 的核查说明。
