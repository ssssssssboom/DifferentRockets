# API 参考：GameWorld

> 源码：`game/core/src/com/differentrockets/game/GameWorld.java`（共 1044 行）
> 引用格式：`GameWorld.java:行号`，即 `game/core/src/com/differentrockets/game/GameWorld.java` 的对应行。

## 1. 类职责概述

`GameWorld` 是整个游戏的模拟核心（`GameWorld.java:13-17`）：

- **Box2D 世界**：`boxWorld` 环境重力为零（`new World(new Vector2(0,0), false)`，`GameWorld.java:117`），所有引力按 N-body 方式逐零件施加（`applyEnvironmentForces`）。
- **行星走开普勒轨道（rails）**：`sun` / `planets` 由 `PlanetDefs.load()` 加载（玩家可编辑的 lua 定义，SmolarSystem.xml 兜底），随 `time` 更新位置（`sun.updateRails`，`GameWorld.java:120`）。
- **所有已发射的飞船持久存在**：`ships` 列表，远处/超 warp 的飞船转入 rails 积分（见 `updateRailsFlags` / `warpRailsShip`）。
- **分块地形**：`terrain`（`TerrainSystem`）每帧随活动飞船位置局部加载。
- **浮动原点（floating origin）**：物理系原点锚定在活动飞船质心（`origin` + `frameVel` 双通道，`GameWorld.java:66-73`），所有 body 只携带相对坐标与相对速度，从而避开 Box2D 的 max-translation 钳制（60Hz 下约 120 m/s）。
- **时间 warp 阶梯**：`warp <= 4` 走真实物理多子步；`warp > 4`（25x~250000x）走超 warp——活动飞船沿预计算轨道按时间采样跟随，其余飞船走分块 rails 积分（`GameWorld.java:24-34`）。
- **玩家输入 / 转向状态**：`inputTurn`、`inputThrottle`、`turnCommand` 等由 lua 脚本读取；转向环语义见 `updateSteering`。
- **存读档**：每 5 秒自动 `save()` 到 `save/world.json`（`GameWorld.java:566-570`）。

## 2. 常量

| 常量 | 值 | 说明 | 位置 |
|---|---|---|---|
| `PHYS_DT` | `1f/60f` | 物理固定步长（秒） | GameWorld.java:20 |
| `VEL_ITER` / `POS_ITER` | 8 / 3 | Box2D 速度/位置求解迭代次数 | GameWorld.java:21 |
| `RAILS_DISTANCE` | 20000.0 | 超过此距离（m）的非活动飞船转 rails | GameWorld.java:22 |
| `PHYS_WARP_MAX` | 4 | 物理 warp 上限；>4 进入超 warp | GameWorld.java:33 |
| `WARP_LEVELS` | {1,2,4,25,100,1000,7500,50000,250000} | 可选 warp 档位 | GameWorld.java:34 |
| `WARP_RAILS_CHUNK` | 2000.0 | 超 warp 单次 rails 积分的最大时长（s） | GameWorld.java:36 |
| `WT_MAX` | 40000 | 超 warp 预计算轨迹采样点上限（约 1.6 MB） | GameWorld.java:99 |

## 3. 字段表

### 3.1 世界与天体

| 字段 | 类型 | 说明 |
|---|---|---|
| `boxWorld` | `World`（public final） | Box2D 世界，环境重力为零；`doSleep=false` 必须保持——休眠 body 忽略受力，地形块移入时不会被弹出，会导致停靠飞船被地面慢慢吞没（GameWorld.java:113-117） |
| `sun` | `Planet` | 恒星（行星树根节点），由 `PlanetDefs.load()` 加载（GameWorld.java:118） |
| `planets` | `List<Planet>`（public final） | 由 `sun.flatten()` 展开的全部天体（GameWorld.java:119） |
| `ships` | `List<Ship>`（public final） | 世界中所有飞船 |
| `active` | `Ship` | 当前活动飞船；浮动原点、脚本更新、转向都以它为基准 |
| `terrain` | `TerrainSystem`（public final） | 分块地形系统（GameWorld.java:121） |

### 3.2 浮动原点（物理系）

| 字段 | 类型 | 说明 |
|---|---|---|
| `origin` | `Vec2d`（public final） | 物理系原点的宇宙坐标（即活动飞船质心，GameWorld.java:66-67） |
| `frameVel` | `Vec2d`（public final） | 物理系自身的宇宙速度；宇宙速度 = `frameVel` + `ship.originVel` + body 速度（GameWorld.java:68-73） |
| `time` | `double` | 宇宙绝对时间（秒） |

### 3.3 玩家输入 / 转向（供 lua 脚本读取）

| 字段 | 类型 | 说明 |
|---|---|---|
| `inputTurn` | `double` | -1..1 转向输入，由转向控制器写入（GameWorld.java:77） |
| `inputThrottle` | `double` | 0..1 油门 |
| `warp` | `int` | 当前 warp 档（1/2/4 物理，更大为超 warp） |
| `paused` | `boolean` | 暂停时 `update()` 直接返回 |
| `targetHeading` | `double` | 目标航向（弧度，body-angle 约定，自"上"逆时针，GameWorld.java:83-84） |
| `turnCommand` | `double` | SteeringIO 最新转向指令（-1..1），gimbal 引擎与 RCS 响应它（GameWorld.java:85-86） |
| `steerPrimed` | `boolean`（private） | 首帧是否已用飞船当前角度初始化目标航向 |

### 3.4 超 warp 预计算轨迹（round 19）

| 字段 | 类型 | 说明 |
|---|---|---|
| `wtX/wtY/wtVX/wtVY/wtT` | `double[WT_MAX]`（private final） | 预计算轨迹的位置/速度/绝对时间采样数组（GameWorld.java:100-104） |
| `wtCount` | `int`（private） | 有效采样点数；0 = 无轨迹 |
| `wtImpact` | `boolean`（private） | 轨迹是否以撞地结束 |
| `wtParts` | `int`（private） | 计算轨迹时 `active.parts.size()`，用于检测分级/分离 |
| `wtWarp` | `int`（private） | 计算轨迹时的 warp 档 |
| `wtHint` | `int`（private） | 单调采样二分查找提示（时间只增） |
| `warpPred` | `OrbitPredictor`（private final） | 复用轨道预测器的 velocity-Verlet 传播器（GameWorld.java:110） |

### 3.5 其他

| 字段 | 类型 | 说明 |
|---|---|---|
| `deferredStructure` | `List<Runnable>`（private final） | lua 回调（detach 等）触发的结构变更延迟队列（GameWorld.java:254-260） |
| `saveTimer` | `double`（private） | 自动存档计时器（>5 秒触发） |
| `tmpG/tmpGF/tmpV/tmp2d` | `Vec2d`/`Vector2`（private final） | 复用临时对象，避免热路径分配 |

## 4. 函数逐项说明

### 4.1 构造与全局

#### `GameWorld()` — GameWorld.java:112-124

**功能**：构造模拟世界。
**参数**：无。**返回值**：无。
**要点与副作用**：
- 创建零重力 Box2D 世界，`doSleep=false`（休眠飞船会被地形吞没的 bug 修复）；
- `PlanetDefs.load()` 加载玩家可编辑的行星定义（lua，SmolarSystem.xml 兜底），`sun.flatten(planets)` 展开天体列表，`sun.updateRails(0)` 初始化轨道位置；
- 创建 `TerrainSystem`；`PhysicsScript.ensureBound` / `TerrainScript.ensureBound` 绑定玩法脚本。

#### `setTime(double t)` — GameWorld.java:126-129

**功能**：直接设定宇宙时间。
**参数**：`t` — 新的绝对时间（s）。**返回值**：无。
**要点**：赋值 `time` 并立即 `sun.updateRails(t)` 让行星就位。副作用：更新全部行星位置。

### 4.2 环境查询（environment）

#### `gravityAt(double x, double y)` — GameWorld.java:133-149

**功能**：返回宇宙坐标点的合引力加速度。
**参数**：`x, y` — 宇宙坐标。**返回值**：`Vec2d`（复用 `tmpG`，勿长期持有）。
**要点**：优先走玩家可编辑的 `mod/physics.lua`（`PhysicsScript.gravity`），命中则直接返回；否则内置 ΣGM/r² 逐行星求和，`mu<=0` 的天体跳过，距离下限钳制为 `0.5·radius` 防奇异。

#### `gravityFast(double x, double y)` — GameWorld.java:160-175

**功能**：不走 lua 钩子的内置引力（超 warp 热路径专用）。
**参数/返回值**：同 `gravityAt`（复用 `tmpGF`）。
**要点**：与内置定律完全一致；超 warp 积分器每帧最多评估约 1000 次引力，经解释执行的 physics.lua 曾是分块数瓶颈（round 18）。玩家魔改的引力只在物理 warp 生效。

#### `currentPlanet()` — GameWorld.java:178-188

**功能**：按地表距离取离活动飞船最近的行星。
**参数**：无。**返回值**：`Planet`；无活动飞船时返回首个行星或 null。
**要点**：用 `dist - radius`（地表距离）比较。

#### `nearestPlanetTo(double x, double y)` — GameWorld.java:190-199

**功能**：按地表距离取离指定点最近的行星。
**参数**：`x, y` — 宇宙坐标。**返回值**：`Planet` 或 null（无行星时）。

#### `altitudeAt(double x, double y)` — GameWorld.java:201-207

**功能**：指定点的地形高度（含噪声地形起伏）。
**返回值**：`double`，中心距 − 半径 − `heightAt(ang)`；无行星返回 0。

#### `densityAt(double x, double y)` — GameWorld.java:209-216

**功能**：指定点的大气密度。
**返回值**：`double`。
**要点**：先调 lua 定律（`PhysicsScript.density(planetName, alt)`），返回 NaN 时回退内置 `Planet.densityAt`。

#### `pressureAt(double x, double y)` — GameWorld.java:218-222

**功能**：指定点大气压。**返回值**：`double`（内置 `Planet.pressureAt(altitude)`）。

#### `isInWater(double x, double y)` — GameWorld.java:224-230

**功能**：点是否在水中。
**返回值**：`boolean`。
**要点**：行星需 `waterDensity > 0`；判定条件为中心距 < 半径且该方向地形高度 < 0（地形低洼处为水）。

#### `isInSunlight(double x, double y)` — GameWorld.java:232-246

**功能**：点是否被阳光直射（用于太阳能板等）。
**返回值**：`boolean`。
**要点**：点到太阳中心线段被任一行星圆盘遮挡则 false；距离太阳极近（segLen2<1）直接 true；太阳自身不参与遮挡判定。

### 4.3 飞船管理（ships）

#### `addShip(Ship s)` — GameWorld.java:250-252

**功能**：把飞船加入世界（去重）。**参数**：`s`。**返回值**：无。

#### `deferStructure(Runnable r)` — GameWorld.java:262

**功能**：把结构变更操作入延迟队列。
**要点**：lua 回调（detach、未来的 destroy/spawn API）绝不允许内联改结构——回调可能深处于 parts/ships 迭代中，内联修改会并发修改/野引用崩溃（GameWorld.java:254-259）。

#### `processDeferredStructure()` — GameWorld.java:264-275

**功能**：在安全点执行全部延迟结构操作。
**要点**：先拷贝再清空队列；每个 op 包 try/catch，单个失败记日志不影响其余。调用点：`update()` 每帧脚本回调之后（GameWorld.java:540）。

#### `launchShip(ShipDesign design, Planet planet)` — GameWorld.java:278-365

**功能**：从设计图在指定行星发射台生成一艘飞船。
**参数**：`design` — 飞船设计；`planet` — 发射行星。**返回值**：新 `Ship`。
**要点与副作用**（顺序重要）：
1. `FlameFx.reset()` 清掉上一场残留的尾焰粒子；
2. 先记录所有既有飞船的宇宙坐标/速度（帧移动会把它们传送到发射场的 bug 修复）；
3. 发射角 `padAngle = π/2`（行星顶部），地表半径含噪声地形 `heightAt`；
4. 用设计全零件包围盒（非仅 root）求 minY，让最低点恰好落在地表上方 0.1 m（round 13：1.2 m 自由落体会积累撞击速度，0.1 m 保留间隙又无跌落）；
5. `buildFromDesign(design, spawnAngle)` 建立 body，设计 +y 指向径向外；
6. `frameVel.set(planet.vel)`——帧携带行星速度，body 从零速起步，飞船相对发射场静止（避开 Box2D 速度钳制）；
7. `translateFrame` 把帧移到发射场（body 不动），`setActive(ship)`，`targetHeading = spawnAngle`；
8. 恢复所有既有飞船的宇宙位置与速度（rails 船改 `originVel`，活动 body 改 `setLinearVelocity`）；
9. `updateRailsFlags()`、打印 `[launch]` 调试日志、`save()`。

#### `setActive(Ship s)` — GameWorld.java:368-373

**功能**：切换活动飞船，浮动原点重新锚定到它。
**要点**：null 直接忽略；依次 `reanchorToActive()` + `updateRailsFlags()`。

#### `translateFrame(double dx, double dy)`（private）— GameWorld.java:376-384

**功能**：平移物理系原点，body 不动。
**要点**：`origin` 与每艘船的 `s.origin` 同步加偏移；零偏移直接返回。

#### `teleportActiveToPlanet(Planet target, double altAbove)` — GameWorld.java:399-445

**功能**：把活动飞船传送到另一行星 `altAbove` 米上空（round 13，烟测把转向收敛场景移进 Smoon 真空用）。
**参数**：`target` — 目标行星；`altAbove` — 地表以上高度（m）。**返回值**：无。
**要点**：
- 只移动活动飞船的宇宙锚点（double 精度的 `origin`），body 局部 float 坐标不动，无精度损失；
- 速度按新行星本地系重表达：保留旧行星系下的径向/切向分量，换到新行星径向方向（否则"垂直上升"会变成"贴地横飞"撞地——首次 Smoon 运行失败的原因）；
- `frameVel` 吸收宇宙速度差，其余飞船在各自速度通道减去同一差值保持不动（镜像 `launchShip` 的保存/恢复模式）；
- 结束 `updateRailsFlags()`。

#### `reanchorToActive()`（private）— GameWorld.java:448-458

**功能**：平移所有 body，使活动飞船质心位于物理原点。
**要点**：位移小于 1e-6（平方）时跳过；每艘船 `shiftBodies(-dx,-dy)`，`origin` 加对应量。

#### `velocityReanchor()`（private）— GameWorld.java:464-482

**功能**：把活动飞船质心速度转入 `frameVel`，保持 body 相对速度足够小、永不触碰 Box2D max-translation 钳制。
**要点**：活动船速度平方 < 1e-4 时跳过；rails 船从 `originVel` 减、活动 body 逐个 `setLinearVelocity` 减；最后 `frameVel` 加上该速度。

#### `updateRailsFlags()`（private）— GameWorld.java:486-516

**功能**：按距离/warp 更新每艘船的 rails 标志，并做切换时的状态交接。
**要点**：
- rails 条件：超 warp（`warp > PHYS_WARP_MAX`）时**所有**船走 rails（帧携带活动船）；物理 warp 时仅距活动船超 `RAILS_DISTANCE` 的非活动船走 rails；
- 转 rails：把帧相对速度存入 `originVel`，body 速度清零、`setBodiesActive(false)`；
- 退出 rails：body 速度恢复为 `originVel`，`originVel` 清零、`setBodiesActive(true)`。

### 4.4 主更新（update）

#### `update(float frameDt)` — GameWorld.java:521-571

**功能**：推进模拟 `frameDt` 秒（按 warp 缩放）。**参数**：`frameDt` — 真实帧时长（s）。**返回值**：无。
**要点与副作用**：
1. `paused` 直接返回；
2. `PhysicsScript/TerrainScript.ensureBound`（热重载后重绑定）；
3. **物理 warp**（`warp <= 4`）：按 `frameDt·warp/PHYS_DT` 取 1..8 个 `substep(PHYS_DT)`；随后每帧一次 `updateSteering(frameDt·warp)` + `active.updateScripts(frameDt·warp)`（脚本拿到完整模拟 dt）；`processDeferredStructure()`；`active.checkJointBreaks(1/PHYS_DT)`、`reanchorToActive()`、`velocityReanchor()`、`updateLanded()`；
4. **超 warp**（`warp > 4`）：`superWarp(frameDt·warp)`，活动船全部零件 `flameLevel = 0`（引擎熄火），`updateLanded()`；
5. 结尾再 `updateRailsFlags()`、`terrain.update(活动船宇宙坐标, simDt)`；
6. `saveTimer` 超 5 秒自动 `save()`。

#### `superWarp(double simDt)`（private）— GameWorld.java:594-706

**功能**：超 warp 时间推进（round 19 重写：预计算轨迹跟随）。
**参数**：`simDt` — 本帧模拟时长（s）。
**要点**：
- 无活动船：只走时钟 + 行星轨道；
- **停在地表**（高度 < 50 m 且相对行星速度 < 1 m/s）：轨迹作废（`wtCount=0`），帧骑行星（`frameVel` 对齐行星速度，其余船补偿），时钟推进，非活动船走 `warpRailsShip`；
- **在轨**：低频重算轨迹（见 `warpTrajValid`）——用 `OrbitPredictor.computeWarp` 一次性传播（与地图预测线同一 velocity-Verlet、同一 ΣGM/r²、同一自适应 dt，`dtScale` 恒为 1.0，保证"飞的线就是画的线"；40000 采样在低轨约覆盖 25 圈）；`wtCount < 2` 则只走时钟；
- 每帧按目标时间在轨迹上 **cubic Hermite 采样**（速度取 Hermite 精确导数），带 `wtHint` 单调二分；到轨迹末端且 `wtImpact` 时钳制并置 `hitEnd`；
- 时钟与行星按 `warpChunk(simDt)` 分块推进，非活动船每块 `warpRailsShip`；
- 活动船精确放到采样位置（`translateFrame`），`frameVel = 采样速度 − active.originVel`，其余船补偿 dv；
- `hitEnd`：`warp = 1`、`wtCount = 0`，在撞击点把控制权交还物理。

#### `warpTrajValid()`（private）— GameWorld.java:709-716

**功能**：判断预计算轨迹是否仍可用。
**返回值**：`boolean`。false 即触发重算。
**要点**：`wtCount < 2` / 无活动船 / warp 档变化 / 零件数变化（分级、分离）/ 时间越界均失效；剩余不足总时长 20% 时重建。

#### `warpRailsShip(Ship s, double h)`（private）— GameWorld.java:719-751

**功能**：超 warp 中单艘非活动船的 rails 分块积分（帧相对）。
**参数**：`s` — 飞船；`h` — 步长（s）。
**要点**：
- 停泊判定（高度 < 50 m 且相对行星速度 < 1 m/s）：骑行星，`originVel` 设为行星速度减帧速度后返回；
- 否则半隐式 Euler：`gravityFast`（内置定律热路径）→ `originVel += g·h` → `origin += originVel·h`；
- 撞地防护：穿入地表 0.5 m 以内则沿径向推出并消除向心速度分量。

#### `warpChunk(double simDt)`（private）— GameWorld.java:45-57

**功能**：超 warp 自适应 rails 分块时长。
**参数**：`simDt` — 本帧模拟时长。**返回值**：`double` 步长（s）。
**要点**：用轨道预测器的动力学时间规则（活动船最近行星 `tau = sqrt(br³/μ)` 的 0.004 倍），钳制到 `[max(0.25, simDt/1000), WARP_RAILS_CHUNK]`；无活动船或无 mu 时取上限 2000 s。

#### `substep(float h)`（private）— GameWorld.java:753-767

**功能**：一个固定物理子步。
**参数**：`h` — 步长（`PHYS_DT`）。
**要点**：`time += h` → `sun.updateRails` → 活动船 `applyEnvironmentForces` → `boxWorld.step(h, 8, 3)` → 帧随 `frameVel` 漂移（`origin` 与各船 `s.origin` 同步）→ rails 船 `integrateRails(h)`。

#### `applyEnvironmentForces(Ship ship, float h)`（private）— GameWorld.java:770-847

**功能**：对飞船每个零件施加引力、气动阻力与浮力。
**要点**：
- 先取第一个活动 body 的自由流（船宇宙速度相对行星）调 `ship.updateDragExposure(...)` 做遮挡扫描（item 2：上游结构遮挡的零件阻力减小，逐零件速度差异只是结构晃动，可忽略）；
- **引力**：逐零件 `gravityAt` × 质量 `applyForceToCenter`；
- **阻力**：行星有大气且密度 > 1e-9 时——密度必须走玩家可编辑定律 `densityAt`（round 14 修复：曾直接调内置 `Planet.densityAt`，改 physics.lua 无效）；`Cd` 取 lua 设的 `p.dragCd`（非 NaN 优先），否则 `0.75 + p.type.drag`（PartList.xml `drag` 属性，下限 0）；面积取 `p.dragArea` 否则 `type.width`；`f = 0.5·ρ·v²·Cd·A·dragExposure`；行星自转忽略；
- **浮力**：`waterDensity > 0` 且在本地水面下时，`Fb = waterDensity · (w·h) · max(0,type.buoyancy) · 浸没度 · |g|`，沿径向外（与本地引力反向）。

#### `updateLanded()`（private）— GameWorld.java:849-855

**功能**：更新活动船 `landed` 标志。
**要点**：相对最近行星速度 < 0.5 m/s 视为着陆（无行星时按船速模长）。

### 4.5 转向（steering）

#### `currentHeading()` — GameWorld.java:860-864

**功能**：活动船当前航向。**返回值**：`double`，控制舱（`controlPart()`，否则首个零件）body 角度；无船/无 body 返回 0。

#### `setTargetHeading(double rad)` — GameWorld.java:867-871

**功能**：命令目标航向（ring 语义）。**参数**：`rad` — 弧度。
**副作用**：写 `targetHeading`，并置 `SteeringIO.targetHeadingRad = rad`、`SteeringIO.ringActive = true`（激活 ring 模式）。

#### `getTargetHeading()` — GameWorld.java:872

**功能**：读目标航向。**返回值**：`double`。

#### `getTurnCommand()` — GameWorld.java:873

**功能**：读最新转向指令。**返回值**：`double`（-1..1）。

#### `updateSteering(double dt)`（private）— GameWorld.java:889-913

**功能**：每帧解析转向指令（round 12）。
**要点**：
- 无控制舱：`inputTurn = turnCommand = 0`；
- 首帧（`!steerPrimed`）：以当前角度初始化目标航向（保持出生朝向）；
- **BUTTON 模式**（`SteeringIO.buttonTurn != 0`）：全速率指令，覆盖 ring，gimbal 引擎各自打到最大；
- **RING 模式**（`SteeringIO.ringActive`）：`SteeringIO.targetHeadingRad` 为权威；gimbal 由 control.lua 跟踪航向误差，本处仅给 RCS 等非引擎消费者的比例回退（误差超 15° 饱和到 ±1）；符号约定：误差为正（目标在机头逆时针侧）需逆时针力矩 = 负指令；
- 无输入：指令 0（gimbal 回中、RCS 待机）；
- 结尾 `inputTurn = turnCommand`。正指令产生**顺时针**力矩（引擎 gimbal 与 RCS 脚本遵循同一约定）。

#### `wrapPi(double a)`（private static）— GameWorld.java:915-920

**功能**：角度归一化到 (-π, π]。**返回值**：`double`。

### 4.6 存读档（save/load）

#### `saveFile()`（private）— GameWorld.java:924-926

**功能**：存档文件句柄。**返回值**：`FileHandle`（`Gdx.files.local("save/world.json")`）。

#### `save()` — GameWorld.java:928-994

**功能**：把世界序列化写入 `save/world.json`。
**要点与副作用**：
- 顶层：`time`、`originX/Y`、`frameVelX/Y`（浮动原点必须存——否则恢复的船在引力/高度/行星距离上整体偏移）、`active`（索引，无则 -1）；
- 每船：`name`、`originX/Y`、`velX/Y`（`originVel`）、`stage`、`rails`；每零件：类型 id、body 位姿与速度（`x,y,a,vx,vy,va`）、`fuel`、`dep`（deployed）、`grp`（>0 才写）；`links`（零件索引对）、`stages`（索引数组的数组）；
- 异常只记日志不抛出。

#### `load()` — GameWorld.java:997-1029

**功能**：读档恢复世界。**返回值**：`boolean`，是否加载到任何飞船。
**要点**：
- 文件不存在返回 false；`FlameFx.reset()` 清残留粒子；
- **先于飞船**恢复 `origin`/`frameVel`（body 坐标是帧相对的，旧档无这些键时保持 origin=0 的旧行为）；
- `sun.updateRails(time)` → 逐船 `Ship.fromJson` → 恢复 `active`（索引越界则取第 0 艘）；
- 异常记日志返回 false。

#### `clearSave()` — GameWorld.java:1031-1036

**功能**：删除存档文件。**要点**：异常静默忽略。

#### `dispose()` — GameWorld.java:1038-1043

**功能**：销毁世界资源。
**副作用**：每船 `destroy()`、清 `ships`、`terrain.dispose()`、`boxWorld.dispose()`。

## 5. 关键设计要点速查

1. **浮动原点双通道**：位置通道 `origin`（double）、速度通道 `frameVel`（double）；任何宇宙量 = 帧量 + body 相对量。改任何涉及坐标/速度的代码时，三个通道（`frameVel`、`ship.originVel`、body 速度）必须同步补偿，参考 `launchShip` / `teleportActiveToPlanet` 的保存-恢复模式。
2. **结构变更必须延迟**：lua 回调里改零件/飞船结构一律 `deferStructure`，安全点在 `update()` 脚本回调之后。
3. **超 warp = 纯时间函数**：活动船飞行路径在重算之间是时间的纯函数（cubic Hermite 采样），与地图预测线同源同参数；重算触发条件集中在 `warpTrajValid`。
4. **lua 可定制点**：引力（`PhysicsScript.gravity`，仅物理 warp）、大气密度（`PhysicsScript.density`，含阻力）、零件 `dragCd/dragArea`、转向控制律（`mod/control.lua` + `SteeringIO`）。
5. **doSleep 绝不能开**：`boxWorld` 的 `doSleep=false` 是 lone-pod 沉地 bug 的修复（GameWorld.java:113-116）。
