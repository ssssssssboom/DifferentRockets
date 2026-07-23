# 10 游戏循环与物理 —— GameWorld

> 主文件：`game/core/src/com/differentrockets/game/GameWorld.java`（1044 行）
> 配套文件：`Ship.java`、`Part.java`、`OrbitPredictor.java`、`PhysicsScript.java`、`JointScript.java`、`TerrainSystem.java`、`assets/mods/physics.lua`
> 本文所有事实均来自上述源码，引用格式为 `文件名:行号`，行号基于当前工作区版本。

## 0. GameWorld 的职责

类头注释（GameWorld.java:13-17）概括了它的定位：整个模拟世界 = 一个**零环境重力**的 Box2D World（N 体引力逐零件手动施加）+ 沿 Kepler 轨道运行的行星 + 所有已发射飞船的持久化 + 分块地形 + 锚定在激活飞船上的浮点原点。

核心成员（GameWorld.java:59-80）：

| 成员 | 含义 |
|---|---|
| `boxWorld` | Box2D 世界，重力为 `(0,0)`，`doSleep=false` |
| `sun` / `planets` | 恒星与扁平化行星列表（`PlanetDefs.load()` 加载，可被玩家 Lua 定义覆盖，SmolarSystem.xml 兜底，GameWorld.java:118-119） |
| `ships` / `active` | 全部飞船与当前激活飞船 |
| `terrain` | 分块地形系统（TerrainSystem） |
| `origin` | 物理帧原点的宇宙坐标（double），即激活飞船质心所在处 |
| `frameVel` | 物理帧本身的宇宙速度（见 §2） |
| `time` | 宇宙时间（秒） |
| `inputTurn` / `inputThrottle` / `warp` / `paused` | 玩家输入状态（Lua 脚本可读） |

---

## 1. 世界初始化

构造函数（GameWorld.java:112-124）依次做四件事：

1. **创建 Box2D 世界**：`new World(new Vector2(0, 0), false)`（GameWorld.java:117）。第二个参数 `doSleep=false` 是刻意为之——注释（GameWorld.java:113-116）记录了"孤立指令舱下沉"事故：休眠的 body 会忽略外力，也不会被移动中的（传送/运动学）地形块挤出，停在地面上的飞船会被地面慢慢"吞掉"。
2. **加载行星**：`PlanetDefs.load()` 返回太阳，`sun.flatten(planets)` 按 parent-first 顺序扁平化整棵行星树，`sun.updateRails(0)` 把 t=0 的轨道位置算出来（GameWorld.java:118-120）。
3. **创建地形系统**：`new TerrainSystem(this)`（GameWorld.java:121）。
4. **绑定玩法脚本**：`PhysicsScript.ensureBound(this)` 与 `TerrainScript.ensureBound(this)`（GameWorld.java:122-123），把 `world` 代理和 `planetEnv` 表注入 physics.lua / terrain.lua 的 Lua 状态（PhysicsScript.java:49-71）。脚本热重载后会在每帧 `update()` 开头重新绑定（GameWorld.java:524-525）。

---

## 2. 坐标系：浮点原点 + 帧速度

这是理解全篇的前提。Box2D 是 float32，且对单步位移有上限（60 Hz 下约 120 m/s，注释见 GameWorld.java:70-72），而太阳系尺度是 double。解决方案是**双通道速度 + 浮点原点**：

```
宇宙位置 = origin(ship.origin + body.getPosition())
宇宙速度 = frameVel + ship.originVel + body.getLinearVelocity()
```

- `origin`（GameWorld.java:67）：物理帧原点的宇宙坐标，永远锚在激活飞船质心附近。
- `frameVel`（GameWorld.java:73）：物理帧自身的宇宙速度。body 只携带相对帧的速度，因此永远远低于 Box2D 的 max-translation 钳制。
- 每艘船还有自己的 `ship.origin` / `ship.originVel`（Ship.java:44-46），`getUniversePos()` / `getUniverseVel()` 按上式合成（Ship.java:575-583）。

维护不变量的三个操作：

- **`reanchorToActive()`**（GameWorld.java:448-458）：平移所有 body，使激活船质心恰好落在物理原点，`origin` 同步累加。
- **`velocityReanchor()`**（GameWorld.java:464-482）：把激活船质心速度"吸"进 `frameVel`，所有 body（含在轨船的 `originVel`）减去同一速度——body 速度保持小量。
- **`translateFrame(dx,dy)`**（GameWorld.java:376-384）：只改 `origin` 与各船 `origin` 的宇宙坐标赋值，body 不动——发射时把帧"搬"到发射场用（GameWorld.java:325）。

每帧物理步进后都会执行 `reanchorToActive()` + `velocityReanchor()`（GameWorld.java:545-546）。

---

## 3. 物理步进主循环

### 3.1 常量

GameWorld.java:20-22：

```java
public static final float PHYS_DT = 1f / 60f;          // 固定步长
public static final int VEL_ITER = 8, POS_ITER = 3;    // Box2D 求解迭代
public static final double RAILS_DISTANCE = 20000.0;   // 超过 20 km 的非激活船上 rails
```

### 3.2 `update(frameDt)`（GameWorld.java:521-571）

每渲染帧调用一次，流程：

1. `paused` 直接返回（GameWorld.java:522）。
2. 重新绑定热重载的脚本（GameWorld.java:524-525）。
3. `updateRailsFlags()` 刷新各船 rails 状态（GameWorld.java:526，见 §8.2）。
4. **物理 warp 分支**（`warp <= PHYS_WARP_MAX=4`，GameWorld.java:528-548）：
   - 子步数 `steps = clamp(round(frameDt * warp / PHYS_DT), 1, 8)`（GameWorld.java:529）——固定步长 PHYS_DT，通过子步数量吸收帧率波动与 2x/4x 加速；上限 8 防止卡顿时的死亡螺旋。
   - 循环调用 `substep(PHYS_DT)`（GameWorld.java:530-532）。
   - 脚本每帧只跑一次，拿到整帧的模拟 dt：`updateSteering(frameDt*warp)` + `active.updateScripts(frameDt*warp)`（GameWorld.java:535-538）。
   - `processDeferredStructure()` 应用脚本请求的结构性变更（分离等，GameWorld.java:540）。
   - 步后整理：`active.checkJointBreaks(1/PHYS_DT)` 关节断裂检查、`reanchorToActive()`、`velocityReanchor()`、`updateLanded()`（GameWorld.java:542-548）。
5. **super-warp 分支**（`warp > 4`，GameWorld.java:549-562）：不走 Box2D、不跑脚本、引擎熄火（`p.flameLevel = 0`，GameWorld.java:559），调用 `superWarp(simDt)`，详见 §8。
6. 再次 `updateRailsFlags()`，`terrain.update(...)` 驱动地形跟随（GameWorld.java:563-564）。
7. **自动存档**：`saveTimer` 每累计 5 秒真实时间存一次档（GameWorld.java:566-570）。

### 3.3 `substep(h)`（GameWorld.java:753-767）

单个固定步长内做四件事，顺序固定：

1. `time += h`，`sun.updateRails(time)`——行星沿 Kepler 轨道推进。
2. `applyEnvironmentForces(active, h)`——只对**激活船**逐零件施加引力/阻力/浮力（见 §4、§5）。注意它必须在 `boxWorld.step` 之前调用，因为其中的 drag 遮挡扫描用 raycast，而世界锁定期间禁止 raycast（Ship.java:453-460 注释）。
3. `boxWorld.step(h, VEL_ITER, POS_ITER)`（GameWorld.java:757）。
4. 推进惯性帧：`origin += frameVel*h`，所有船的 `origin` 同步累加（GameWorld.java:759-763）；在轨船做 `s.integrateRails(h)`（GameWorld.java:764-766，见 §8.2）。

---

## 4. 引力计算：多体 ΣGM/r² 与 physics.lua 的关系

### 4.1 三条引力代码路径

游戏里有**三个**引力求值入口，面向不同调用频率：

| 入口 | 位置 | 是否走 Lua | 用途 |
|---|---|---|---|
| `gravityAt(x,y)` | GameWorld.java:133-149 | 先试 physics.lua，失败回退内置 | 物理 warp 下逐零件施力（GameWorld.java:796）、在轨船积分（Ship.java:620）、环境查询 |
| `gravityFast(x,y)` | GameWorld.java:160-175 | 否（纯内置） | super-warp 热路径——非激活船的 rails 积分（GameWorld.java:734）。注释（GameWorld.java:153-159）说明：super-warp 积分器每帧求引力可达 ~1000 次，走解释执行的 physics.lua 会成为 chunk 数瓶颈 |
| `OrbitPredictor.accelAt` | OrbitPredictor.java:275-289 | 否（纯内置） | 地图轨道预测线与 super-warp 预计算轨迹共用 |

三者的内置实现**完全同构**：遍历 `planets`，跳过 `mu<=0` 的天体，累加 `a = mu/(r²·r)`（即 GM/r² 乘单位方向向量的合并写法，避免再除一次 r），并有**奇点钳制** `r = max(r, 0.5·planet.radius)`（GameWorld.java:142、GameWorld.java:167-168、OrbitPredictor.java:282-283）。

### 4.2 与 physics.lua 的关系

- `gravityAt` 首先调 `PhysicsScript.gravity(x, y, time, tmpG)`（GameWorld.java:135）；physics.lua 里定义了 `gravityAccel(x, y, timeSec)` 就用它，否则回退内置（PhysicsScript.java:74-88）。玩家目录下自带的 physics.lua 默认实现就是同一个 ΣGM/r² 循环（assets/mods/physics.lua:107-124），可以被玩家改成 n-body 传播、J2 扁率修正等。
- Lua 侧通过注入的 `world` 代理读取行星位置/mu/radius（PhysicsScript.java:29-38），通过 `planetEnv` 表读取大气参数（PhysicsScript.java:57-65）。
- 任何 Lua 运行时错误会**整体禁用该脚本**并回退内置定律，日志只打一次（PhysicsScript.java:173-178）。
- 大气密度同理：`densityAt` 先试 physics.lua 的 `atmosphereDensity`，返回 NaN 则用内置指数模型（GameWorld.java:209-216）；阻力计算用的是这个可被玩家修改的密度（GameWorld.java:807 注释记录了曾因直调内置模型导致玩家改 physics.lua 无效的 bug）。
- **重要不对称**：physics.lua 的自定义引力只在**物理 warp（≤4x）**生效；super-warp 与轨道预测永远用内置定律（gravityFast / accelAt），这是性能取舍（GameWorld.java:153-159）。

---

## 5. 零件间弹性阻尼连接点的力传递

飞船不是单个刚体：**每个零件是独立 Box2D dynamic body，零件之间用弹簧-阻尼参数的 WeldJoint 连接**（Ship.java:19-23 类注释）。

### 5.1 建船与连接匹配

- `buildFromDesign`（Ship.java:64-83）：逐零件 `createBody`，然后先调 `callOnLoad()`（零件 Lua 在 onLoad 里可 `setJointParams` 设本零件的连接参数覆盖），再 `connectAttachPoints()`——顺序是有意的，焊接必须读到这些覆盖（Ship.java:78-81 注释）。
- `connectAttachPoints`（Ship.java:85-122）：O(n²) 遍历零件对，对每对零件的所有 attach point 组合求**线段间最近距离**（`Attach.closestBetweenSegments`，Ship.java:108）——edge 型连接点匹配整条边而不仅是中心点，保证旋转过的零件也能焊上（Ship.java:103-106 注释）。最近距离 < `threshold = 0.35f`（Ship.java:87）就在两段接触点中点建立焊接（Ship.java:115-118）。

### 5.2 焊接参数解析（`weld`，Ship.java:127-171）

`WeldJointDef` 的两个关键参数 `frequencyHz`（弹簧刚度）和 `dampingRatio`（阻尼比）按优先级解析：

1. **mod/joints.lua**：`JointScript.resolve(a, apA, b, apB, jp)` 成功则全部来自 Lua（frequencyHz、dampingRatio、breakForce），Lua 脚本能看到两端零件和两端连接点（JointScript.java:16、Ship.java:140-144）。
2. **内置回退规则**：两端零件中 `jointFreqHz` 覆盖值**更高者获胜**（"更硬的一边主导连接"），它的 `jointDampRatio` 一并采用；都没设则用 physics.lua `joints` 表 / Java 默认值（Ship.java:146-158）。
   - Java 默认值：`frequencyHz=20.0`、`dampingRatio=1.1`、`angularDamping=0.08`（PhysicsScript.java:118）。20 Hz 刻意低于 60 Hz 物理步长的 30 Hz Nyquist 极限（PhysicsScript.java:115-117 注释）；玩家可在 physics.lua 的 `joints` 表调整（assets/mods/physics.lua:73，出厂值 angularDamping 被改为 0.6）。
   - 每零件覆盖由零件自己的 Lua 脚本在 onLoad 里调 `part:setJointParams{...}` 设置（ModApi.java:215-246；出厂示例：strut-1 用 35 Hz 加硬，dock-1/port-1 用 8 Hz 留"软"，assets/mods/physics.lua:81-82 注释）。
3. `breakForce = min(apA.breakForce, apB.breakForce)`（Lua 未给正值时，Ship.java:143/157）。
4. `jd.collideConnected = false`——相连零件之间不产生碰撞（Ship.java:162）。

每条连接存为 `Ship.Link{joint, a, b, fuelEdge, breakForce}`（Ship.java:30-35）；`fuelEdge = apA.fuelLine && apB.fuelLine` 决定该边是否参与液体燃料输送网络（Ship.java:168）。

### 5.3 关节断裂与船体分裂

- 每物理帧步进后调 `checkJointBreaks(invDt)`（GameWorld.java:544 → Ship.java:191-201）：`joint.getReactionForce(invDt)` 取反作用力，换算成 kN，超过 `breakForce` 就销毁该 joint。
- 任何关节集合变化后调 `splitIfDisconnected()`（Ship.java:204-276）：对零件-joint 图做 BFS 连通分量；若裂成多块，含 pod 的分量（否则最大分量）保留为原船，其余分量各自成为**新的独立 Ship**（继承 origin/originVel，`currentStage` 置为极大值使残骸不能再点火分级，Ship.java:256），原船的未燃级索引重映射（Ship.java:259-275）。

力传递本身由 Box2D 求解器完成：带 `frequencyHz/dampingRatio` 的 WeldJoint 就是弹簧-阻尼软约束，推力/阻力/碰撞通过它在零件间传递并允许小幅形变与摆动——这就是"弹性阻尼连接点"的全部实现。

---

## 6. Box2D 使用方式与防穿地措施

### 6.1 Box2D 的使用约定

- 环境重力为 0，引力/阻力/浮力全部以 `applyForceToCenter` 手动施加（GameWorld.java:797/826/842）。
- 零件 body：`DynamicBody`，`linearDamping=0`，`angularDamping` 可经 Lua 覆盖（Part.java:87-97）；fixture 用 ≤8 顶点多边形，`friction` 下限 1.5（保证着陆抓地，Part.java:123-128 注释），`restitution=0`（着陆零弹跳，Part.java:129-131）；质量与转动惯量手动 `setMassData`（油箱质量随燃料变化，Part.java:140-153）。
- 迭代参数 `VEL_ITER=8, POS_ITER=3`（GameWorld.java:21）。

### 6.2 防穿地三板斧

1. **CCD（连续碰撞检测）**：每个零件 body `bd.bullet = true`（Part.java:88-91）——500+ m/s 的撞击速度下非 bullet body 单步位移 8 m 以上，会直接隧穿地形块。
2. **禁止睡眠**：`doSleep=false`（GameWorld.java:113-117）——睡眠 body 不被移动中的地形挤出，会被地面吞掉。
3. **运动学地形**：地形块是 **KINEMATIC body**，且**以速度驱动而非传送**（TerrainSystem.java:290-309）：每帧根据地形的行星跟随目标位置反算 `setLinearVelocity((tx-lastBX)/simDt, ...)`，让接触求解器看到地面的真实表面速度，着陆船靠摩擦随行星走，而不是被瞬移的静态碰撞体吞没。地形 collider 只存在于飞船周围 ±10 km 物理窗口（渲染窗口 ±100 km，TerrainSystem.java:74-75、TerrainSystem.java:337-343），窗口管理 10 Hz（`REFRESH_S = 0.1`，TerrainSystem.java:86、TerrainSystem.java:311-316）。另外有 `SEAM_OVERLAP_M = 0.05` 的列间重叠防缝隙隧穿（TerrainSystem.java:85）。

此外还有两道 rails 模式的"硬地板"兜底（无碰撞时的数值保护）：

- `Ship.integrateRails` 末尾：rails 步进结束后若陷入地表以下（`dist < surf + 0.5`），沿径向推出并消掉向内的径向速度（Ship.java:623-640）——远处 ballistic 坠落的级段被钳在地表而不是穿行星。
- `warpRailsShip` 同款逻辑（GameWorld.java:737-750）。

停在地面（高度 <50 m 且相对行星速度 <1 m/s）的在轨船则"骑行星"：`originVel` 锁成行星速度相对帧速度（Ship.java:608-618、GameWorld.java:722-733）。

---

## 7. 飞船激活 / 分离 / 爆炸

### 7.1 发射与激活

- `launchShip(design, planet)`（GameWorld.java:278-365）：在行星顶端（`padAngle = π/2`）地表上方 0.1 m 处生成（余量从 1.2 m 降到 0.1 m 避免自由落体积累撞击速度，GameWorld.java:306-310 注释）；设计 +y 旋向径向外（GameWorld.java:317-318）；`frameVel` 设为行星速度使船相对发射场静止（GameWorld.java:319-321）；`translateFrame` 把帧搬到发射点并 `setActive(ship)`。
  - **关键保护**：搬帧前快照所有已有船的宇宙位姿，之后逐一恢复（GameWorld.java:283-290、329-356），否则帧移动会把所有旧船"传送"到发射场。
- `setActive(s)`（GameWorld.java:368-373）：切换激活船 = `reanchorToActive()`（浮点原点换锚）+ `updateRailsFlags()`。
- 分级点火：`Ship.activateStage()`（Ship.java:513-529）——先快照本级的 Part 引用（因为 detacher 的 onStage 会延迟修改 parts 列表），逐个 `callOnStage()`，随后 `world.processDeferredStructure()` 立即应用分离；UI 的 ACTIVATE 按钮走 `SandboxScreen.activateSelected()`（SandboxScreen.java:741-766），按激活组（group）批量触发 onStage。

### 7.2 分离（detach）

结构性变更**绝不允许在 Lua 回调里内联执行**——回调可能正深处 parts/ships 迭代中，原地改图会并发修改崩溃（GameWorld.java:254-259 注释）。因此：

1. 分离器零件 Lua（detacher-1.lua:7 / detacher-2.lua:7）调 `part:detach()` → `ModApi.detach()` → `Part.detachJoints()`（Part.java:169-177），把"移除该零件所有 joint"封装成 Runnable 丢进 `deferredStructure` 队列（GameWorld.java:260-262）。
2. 安全点（每帧脚本回调后 GameWorld.java:540，或 activateStage/activateSelected 返回后 Ship.java:526 / SandboxScreen.java:762）统一执行 `processDeferredStructure()`（GameWorld.java:264-275），逐个 try/catch 运行。
3. `removeJointsOf` 销毁 joint 后调 `splitIfDisconnected()` 完成船体分裂（Ship.java:173-180，见 §5.3）。

### 7.3 爆炸（现状说明）

**当前代码没有独立的爆炸/零件销毁系统**——全仓库没有任何 ContactListener（无 `beginContact`），`PartType.canExplode` 字段（PartType.java:91，XML 解析于 PartType.java:133）在 Java 与内置 Lua mod 中均**无任何消费方**，仅 PartList.xml 里少数零件声明 `canExplode="false"`。撞击的物理后果目前完全由两条机制表达：

- **关节断裂 + 船体分裂**（§5.3）——摔碎的直观表现；
- **super-warp 撞击交还**：预计算轨迹终点撞地时 `warp = 1` 把控制交还给物理（GameWorld.java:702-705），由 Box2D 处理撞击。

接手工件如要做零件爆炸/损毁，需要新增接触监听或在断裂检查处扩展——`canExplode` 是预留的挂点。

---

## 8. 加速档位体系

### 8.1 档位表

GameWorld.java:33-34：

```java
public static final int PHYS_WARP_MAX = 4;
public static final int[] WARP_LEVELS = {1, 2, 4, 25, 100, 1000, 7500, 50000, 250000};
```

- **物理 warp（1/2/4x）**：正常走 Box2D 子步，只是每帧子步数乘 warp（GameWorld.java:529）。≤4x 是因为子步上限 8，4x 已接近 60 fps 下的实时极限。
- **super-warp（25x–250000x）**：注释（GameWorld.java:24-32）说得很直白——激活船跟随**预计算轨迹**（纯时间函数采样，无逐帧积分）、引擎锁定、其余所有船骑分块 rails，"这是 25x..250000x 能实时跑的唯一办法"。

### 8.2 rails（在轨）机制

`updateRailsFlags()`（GameWorld.java:486-516）决定每艘船是否 onRails：

- super-warp 期间**所有**船（含激活船）上 rails（`warp > PHYS_WARP_MAX`，GameWorld.java:493）；
- 物理 warp 下，距激活船超过 `RAILS_DISTANCE = 20000` m 的非激活船上 rails（GameWorld.java:22、494）。

切换时"冻结/解冻"（GameWorld.java:495-514）：上 rails 时把船的帧相对速度存入 `originVel`、body 速度清零、`setBodiesActive(false)`；下 rails 时把 `originVel` 还给每个 body 并重新激活。物理 warp 下在轨船每子步 `integrateRails(h)` 做半隐式 Euler 重力积分（Ship.java:598-641），停靠船骑行星、带硬地板（§6.2）。

### 8.3 super-warp：预计算轨道跟随（superWarp / warpTraj）

这是 round 19 的重写成果，核心思想（GameWorld.java:573-593 长注释）：激活船未来的惯性路径由 **OrbitPredictor 自己的 velocity-Verlet 传播器**一次性预计算（与地图预测线同一套积分器、同一套 ΣGM/r²、同一套自适应步长，所以"飞的线就是地图画的线"），之后每帧只做**纯时间函数采样**——热路径无积分、零累积漂移。

**数据结构**（GameWorld.java:99-110）：

```java
private static final int WT_MAX = 40000;        // 5 × 40000 × 8 B ≈ 1.6 MB
private final double[] wtX/wtY/wtVX/wtVY/wtT;   // 位置、速度、绝对宇宙时间
private int wtCount;      // 有效点数（0 = 无轨迹）
private boolean wtImpact; // 轨迹终点撞地
private int wtParts = -1; // 计算时 active.parts.size()
private int wtWarp = -1;  // 计算时的 warp 档位
private int wtHint;       // 单调采样 hint（时间只增，多数帧免二分）
private final OrbitPredictor warpPred;
```

**预计算**：`warpPred.computeWarp(this, active, dtScale=1.0, wtX, wtY, wtVX, wtVY, wtT)`（GameWorld.java:634-636 → OrbitPredictor.java:185-237）。与地图预测 `compute()` 的差别：额外记录速度、撞击检测用**真实地形表面**（radius + heightAt）而非基准圆，保证交还点落在地面而非山里（OrbitPredictor.java:178-184）。`dtScale` 恒为 1.0——和地图线用同一未缩放步长规则是飞线贴合地图线的关键（GameWorld.java:626-633 注释：4x 拉伸曾在低轨测得 5.4e-4 rad/圈的额外相位误差）；40000 个采样在低轨约覆盖 25 圈，250000x 下约 5 秒真实时间才需重算一次。

**重算触发**（`warpTrajValid()` 的 false 分支，GameWorld.java:709-716）：

1. 无轨迹或激活船为空；
2. `wtWarp != warp`——换挡；
3. `wtParts != active.parts.size()`——分级/分离改变了零件数；
4. 时间跑出轨迹区间；
5. **剩余轨迹 <20%** 时提前重建。

**每帧采样**（GameWorld.java:648-700）：

- 目标时间 `target = time + simDt`，超出轨迹末端则钳到末端并记 `hitEnd`（GameWorld.java:650-653）。
- 用 `wtHint` 快速定位区间（失效则二分查找，GameWorld.java:656-665），在相邻两采样间做**三次 Hermite 插值**：位置用 Hermite 基函数，速度取其**精确导数**（GameWorld.java:666-676）。
- 时钟与行星按 `warpChunk(simDt)` 分块推进（GameWorld.java:678-688）；chunk 长度取激活船最近天体动力学时间 τ=√(r³/μ) 的 0.004 倍，钳制在 `[simDt/1000, WARP_RAILS_CHUNK=2000s]`（GameWorld.java:36、45-57）。非激活船每个 chunk 走 `warpRailsShip`（GameWorld.java:719-751）：内置引力 gravityFast 的半隐式 Euler + 停靠骑行星 + 硬地板。
- 把激活船**精确放**到采样状态：`translateFrame(sx-cur.x, sy-cur.y)` 搬帧；`frameVel = 采样速度 − active.originVel`，其余船的 `originVel` 补偿同一 dv（GameWorld.java:690-700）。
- `hitEnd`（轨迹撞地）：`warp = 1` 交还物理、`wtCount = 0`（GameWorld.java:702-705）。

**地面停靠特例**（GameWorld.java:598-624）：激活船停在地面（高度 <50 m 且相对行星速度 <1 m/s）时轨迹无意义，`wtCount=0`，沿用老逻辑——帧骑行星、时间快进、其余船走 `warpRailsShip`。

super-warp 期间的限制（GameWorld.java:550-555 注释）：无 Box2D、无脚本、引擎熄火（`flameLevel=0`，GameWorld.java:559）。

---

## 9. 保存 / 读取（continue 沙盒）

存档文件：`Gdx.files.local("save/world.json")`（GameWorld.java:924-926）——libGDX `local` 在桌面是工作目录、在 Android 是应用私有存储。

**写入时机**：发射后（GameWorld.java:363）、每 5 秒真实时间自动存档（GameWorld.java:566-570）。

**`save()`**（GameWorld.java:928-994）序列化：

| 字段 | 内容 |
|---|---|
| `time` | 宇宙时间 |
| `originX/Y` | 浮点原点宇宙坐标——注释（GameWorld.java:933-936）强调缺了它，恢复的船求引力/高度/行星距离会整体偏移一个帧原点 |
| `frameVelX/Y` | 帧宇宙速度 |
| `active` | 激活船在 ships 中的索引 |
| `ships[]` | 每船：`name`、`originX/Y`、`velX/Y`（originVel）、`stage`（currentStage）、`rails`、`parts[]`（类型 id、body 位置/角度/线速度/角速度、`fuel`、`dep`(deployed)、`grp`(group)）、`links[]`（零件索引对）、`stages[]`（剩余分级的零件索引） |

**`load()`**（GameWorld.java:997-1029）：

1. 文件不存在返回 `false`（新沙盒）；
2. `FlameFx.reset()` 清掉上一场尾焰粒子（GameWorld.java:1001）；
3. 先恢复 time/origin/frameVel 并 `sun.updateRails(time)`——**必须先于船**，因为 body 坐标是帧相对的（GameWorld.java:1004-1010 注释；旧存档没有这些键时 origin 保持 0 兼容）；
4. 逐船 `Ship.fromJson`（Ship.java:716-770）：重建 body 并恢复速度/燃料/部署状态，`callOnLoad()` 先于按 `links` 重新焊接（理由同 §5.1，Ship.java:742-744），焊接锚点取两船最近 attach point 的中点（Ship.java:772-782）；
5. 按 `active` 索引 `setActive`，越界则取第一艘（GameWorld.java:1018-1023）。

**注意**：预计算 warp 轨迹（wtX 等）与零件 gimbal 实时角（Part.java:65-72 注释）**不持久化**——读档后轨迹会在下一帧按需重算，gimbal 在几分之一秒内自行收敛。`clearSave()` 删除存档（GameWorld.java:1031-1036），`dispose()` 销毁全部船、地形与 boxWorld（GameWorld.java:1038-1043）。

---

## 附录：关键常量速查

| 常量 | 值 | 位置 |
|---|---|---|
| `PHYS_DT` | 1/60 s | GameWorld.java:20 |
| `VEL_ITER / POS_ITER` | 8 / 3 | GameWorld.java:21 |
| `RAILS_DISTANCE` | 20000 m | GameWorld.java:22 |
| `PHYS_WARP_MAX` | 4 | GameWorld.java:33 |
| `WARP_LEVELS` | 1,2,4,25,100,1000,7500,50000,250000 | GameWorld.java:34 |
| `WARP_RAILS_CHUNK` | 2000 s | GameWorld.java:36 |
| `WT_MAX` | 40000 采样点 | GameWorld.java:99 |
| 自动存档间隔 | 5 s（真实时间） | GameWorld.java:566-570 |
| 焊接匹配阈值 | 0.35 m | Ship.java:87 |
| 焊接默认参数 | 20 Hz / 阻尼比 1.1 / 角阻尼 0.08 | PhysicsScript.java:118 |
| 地形物理窗口 / 渲染窗口 | ±10 km / ±100 km | TerrainSystem.java:74-75 |
| 地形窗口管理频率 | 10 Hz（REFRESH_S=0.1） | TerrainSystem.java:86 |
| 轨道预测点数上限 | MAX_STEPS 4200（地图）/ 40000（warp） | OrbitPredictor.java:23 / GameWorld.java:99 |
