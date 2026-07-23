# 14 · Mod 系统与 Lua 桥接

> 适用范围：`game/core/src/com/differentrockets/game/` 下的 `ModManager.java`、`LuaScript.java`、`ModApi.java`、`JointScript.java`、`PhysicsScript.java`、`FlameScript.java`、`FlameFx.java`，以及 `game/core/assets/mods/` 下的内置 Lua 脚本。所有行号引用均对应仓库当前代码。

游戏的几乎全部零件行为、物理律、连接点规则和尾焰渲染都由 Lua 驱动。Java 侧通过 **luaj（org.luaj.vm2，JSE 模式）** 暴露桥接层，整体设计可概括为一句话：**Java 提供数据与渲染/物理原语，Lua 决定行为；Lua 出错时永远回退到内置默认行为。**

## 0. 两个脚本家族

| 家族 | 加载器 | 粒度 | 代表脚本 | 热重载 |
|---|---|---|---|---|
| 零件行为脚本 | `ModManager` | **每个零件实例一份独立 Globals** | `engine-0.lua`、`detacher-1.lua` 等 33 个内置脚本 | 否（新创建的零件/资源重载后才用新代码） |
| 单文件玩法脚本 | `LuaScript` | 全局共享一份 Globals | `physics.lua`、`joints.lua`、`flame.lua`、`terrain.lua`、`control.lua`（经 `readModText` 读入） | 是（每秒 stat 一次玩家文件，见 `LuaScript.java:74-126`） |

## 1. 脚本查找顺序与源码缓存

### 1.1 玩家 mod 目录优先，内置 assets 兜底

`ModManager.scriptFor(typeId)`（`ModManager.java:46-74`）的查找顺序：

1. **玩家 mod 目录**：`modDir.child(typeId + ".lua")`。`modDir` 来自 `Res.modDir()`（`ModManager.java:35`），Android 上是共享存储 `/sdcard/DifferentRocket/mod/`（`Res.java:578-581`；权限未授予时退回应用私有目录并自动迁移，见 `Res.java:9-16`），桌面端是项目目录下的 `DifferentRocket/mod/`（`Res.java:135`）。
2. **内置 assets**：`Gdx.files.internal("mods/" + typeId + ".lua")`（`ModManager.java:59`），即打包进 APK 的 `core/assets/mods/`。

首次运行时 `Res` 会把内置默认脚本拷贝到玩家目录供修改（`Res.java:19-21`），因此正常情况下玩家目录里总是存在一份可编辑副本；内置 assets 只在玩家文件缺失或读取失败时兜底。

源码按 `typeId` 缓存在静态 `scriptSourceCache`（`ModManager.java:25, 47`），**连 `null`（无脚本）也会缓存**。`reset()`（`ModManager.java:40-44`）在资源重载后清空缓存并重新定位 mod 目录。

每个脚本加载时会在日志中打印版本号：版本取自脚本前 5 行内第一个形如 `-- v2026.07.21` 的注释，由 `LuaScript.versionOf` 解析（`LuaScript.java:50-65`），没有版本注释的旧玩家文件在日志里显示为 `v?`。

### 1.2 LuaScript：单文件脚本的通用加载器

`LuaScript`（`LuaScript.java:19-127`）是 `physics.lua` / `joints.lua` / `flame.lua` / `terrain.lua` 共用的加载器，特性：

- 同样的"玩家目录 → 内置 assets"查找顺序（`LuaScript.java:86-102`）。
- **热重载**：每次访问 `globals()` 时若距上次 stat 超过 1 秒，重新比较玩家文件的 `lastModified`，变了就重新编译（`LuaScript.java:74-81`）。保存文件约 1 秒后生效，无需重启。
- **失败标记**：找不到文件或编译失败时 `globals` 置为 `null` 且只记一次日志（`loadFailed` 标志，`LuaScript.java:103-125`），调用方回退内置行为。
- 非线程安全，只能从渲染线程调用（`LuaScript.java:17` 注释）。
- `invalidate()`（`LuaScript.java:40-43`）强制下次访问重读，资源重载时由 `JointScript.invalidate()` / `PhysicsScript.invalidate()` / `FlameScript.invalidate()` 转发（如 `DRGame.java:63`）。

## 2. 每零件独立 Globals 的创建

`ModManager.createState(typeId)`（`ModManager.java:103-116`）为**每一个零件实例**创建全新的 Lua 状态：

```java
Globals g = JsePlatform.standardGlobals();
g.load(PID_LIB, "pid.lua").call();        // 先注入共享 PID 库
LuaValue chunk = g.load(src, typeId + ".lua");
chunk.call();                              // 执行脚本顶层（定义 onLoad 等钩子）
```

要点：

- 状态间完全隔离：`engine-0.lua` 里的 `local staged = false` 这类 upvalue 是每个实例一份。引擎脚本头部注释明确说明了这一点——存档读档后 Lua upvalue 不持久，`onLoad` 里要从 `part:getStage() > 0` 重新推导（`engine-0.lua` 第 30-33 行）。
- 顶层 `chunk.call()` 抛 `LuaError` 时返回 `null`，该零件退化为无脚本零件（`ModManager.java:112-115`）。
- 零件实例的 `lua` 字段在首次 `callOnLoad()` 时惰性创建（`Part.java:184-187`）：`if (lua == null) lua = ModManager.createState(type.id);`

钩子调用统一走 `ModManager.callHook`（`ModManager.java:118-131`）：从 Globals 取同名函数，不存在就直接返回；`onUpdate` 额外传 `dt`；`ModApi` 通过 `CoerceJavaToLua.coerce` 暴露为 Lua 侧的 `part` 参数。运行时 `LuaError` 只记日志、不中断游戏。

## 3. 钩子生命周期：onLoad / onUpdate / onStage

三个钩子在 `Part.java:184-196` 定义，由 `Ship` 驱动：

| 钩子 | 触发时机 | 用途与约束 |
|---|---|---|
| `onLoad(part)` | 零件加入飞船、**焊接之前**（`Ship.java:78-82`；读档恢复时同样在重新焊接之前，`Ship.java:742-744`） | 初始化实例状态；`setJointParams` 必须在这里调用，因为焊接参数解析发生在之后 |
| `onUpdate(part, dt)` | 每物理帧，`Ship.updateScripts` 遍历全部零件调用（`Ship.java:538-543`）。调用前先把所有零件的 `flameLevel` 清零 | 推力、燃料、传感器逻辑的主循环 |
| `onStage(part)` | 玩家触发分级时，对该级目标零件调用（`Ship.java:522-525`） | 分离器 `detach()`、发动机解锁（`staged = true`）等 |

`onStage` 的两个细节：

- 调用前把 `stageActivatedThisFrame` 置 `true`（`Part.java:194`），该标志保持到本帧 `onUpdate` 全部跑完才清除（`Ship.java:541-542`），Lua 里可用 `part:isStageActivated()` 读到（`ModApi.java:88`）。
- 分离器在 `onStage` 里调用 `detach()` 时，关节销毁是**延迟到回调后队列**执行的——因为调用方正在遍历零件列表，就地拆分飞船会破坏迭代（`Part.java:164-169` 注释）。

## 4. ModApi：暴露给 Lua 的 `part` 对象完整能力清单

`ModApi`（`ModApi.java`）是 Lua 钩子收到的 `part` 参数。所有坐标/速度都在**物理参考系**（米，y 轴向上，相对当前原点；`ModApi.java:7-11`）。以下按类列出全部公开方法。

### 4.1 身份（`ModApi.java:19-25`）

| 方法 | 说明 |
|---|---|
| `getTypeId()` / `getName()` / `getType()` | 零件类型 id（如 `"engine-0"`）、显示名、类别 |
| `getGroup()` / `setGroup(g)` | 激活组，0 = 无，1..8（set 时自动钳位） |

### 4.2 自身物理：推力矢量与力接口（`ModApi.java:27-49`）

| 方法 | 说明 |
|---|---|
| `getX()` / `getY()` | body 位置（物理系，米） |
| `getVelocityX()` / `getVelocityY()` | 线速度 |
| `getAngle()` / `getAngularVelocity()` | 姿态角（弧度）/ 角速度 |
| `getMass()` | 质量 |
| `applyForce(fx, fy)` | 对质心施加力（`applyForceToCenter`） |
| `applyForceAt(fx, fy, localX, localY)` | 在零件局部坐标偏移处施加力（引擎脚本用它沿喷口方向施加带 gimbal 偏转的推力矢量） |
| `applyTorque(t)` | 施加力矩 |

### 4.3 飞船 / 星球 / 引力（`ModApi.java:51-80`）

| 方法 | 说明 |
|---|---|
| `getShipX/Y()` / `getShipVelocityX/Y()` | 宇宙坐标系下飞船位置与速度 |
| `getPlanetCount()` / `getPlanetName(i)` / `getPlanetRadius(i)` / `getPlanetX(i)` / `getPlanetY(i)` | 星球枚举（位置换算回物理系） |
| `getCurrentPlanet()` | 当前主天体（最近星球）名 |
| `getGravityX()` / `getGravityY()` | 本零件处引力加速度（m/s²，走 `GameWorld.gravityAt`，即可被 `physics.lua` 改写） |
| `getAltitude()` | 当前星球地表以上高度（米） |
| `getAtmoDensity()` / `getAtmoPressure()` | 大气密度 / 气压 |
| `isInWater()` / `isInSunlight()` | 是否在有水星球的 sea level 以下 / 是否被太阳照射（无遮挡） |

### 4.4 玩家操作与转向（`ModApi.java:82-116`）

| 方法 | 说明 |
|---|---|
| `getTurn()` | 转向指令 -1/0/+1 |
| `getThrottle()` | 油门 0..1 |
| `isStageActivated()` | 仅本帧触发过分级时为 true |
| `getStage()` | 飞船当前分级序号 |
| `getTargetHeading()` / `setTargetHeading(rad)` | 转向环目标航向（弧度，body-angle 约定：0 = 机头朝上，逆时针为正）；set 会激活 ring mode |
| `getShipHeading()` / `getTurnCommand()` | 当前航向 / 最新转向指令 |
| `getSteering()` | 返回 LuaTable `{active, buttonTurn, targetRad}`，镜像 `SteeringIO` 原始状态（`ModApi.java:110-116`） |

### 4.5 燃料网络（`ModApi.java:140-164`）

| 方法 | 说明 |
|---|---|
| `getFuelTotal(fuelType)` / `getFuelCapacity(fuelType)` | 全船该型燃料总量 / 容量 |
| `drainFuel(fuelType, amount)` | 为**本零件**抽取燃料，返回实际抽到的量。供给范围：液体（0）只来自经 fuelLine 连接点连通的油箱；单组元（1）与电力（2）全船共享；固体（3）只烧自身（`ModApi.java:145-153` 注释） |
| `transferFuel(fuelType, amount)` | 在本零件供给范围内的油箱间转移燃料 |
| `getFuel()` / `getFuelMax()` / `getFuelType()` / `setFuel(v)` | 本零件自带油箱 |
| `addFuel(fuelType, amount)` | 向管网加注（如太阳能充电），返回实际加入量 |

### 4.6 零件定义数据（`ModApi.java:166-178`）

`getWidth()`、`getHeight()`、`getEnginePower()`、`getEngineConsumption()`、`getEngineTurn()`（gimbal 范围度）、`getEngineSize()`、`isThrottleExponential()`、`getEngineFuelType()`、`getRcsPower()`、`getRcsConsumption()`、`getSolarChargeRate()`、`hasLander()` —— 全部只读，来自 `PartType`（即 PartList.xml 定义）。

### 4.7 动作 / 气动 / 特效（`ModApi.java:180-210`）

| 方法 | 说明 |
|---|---|
| `detach()` | 销毁本零件全部关节（分离器用；延迟执行，见 §3） |
| `setDeployed(b)` / `isDeployed()` | 展开状态（降落伞、起落架、太阳能板） |
| `getDrag()` / `setDrag(cd)` / `resetDrag()` | 阻力系数；未 override 时为 0.75 基线 + PartList.xml `drag` 修正 |
| `getDragArea()` / `setDragArea(a)` / `resetDragArea()` | 阻力参考面积（默认零件宽度） |
| `getDragExposure()` | 0..1 迎风暴露比例（遮挡感知阻力，`ModApi.java:251-253`） |
| `emitFlame(size, angleOffsetDeg)` | 触发尾焰特效；size 0..1+，角度偏移为相对喷口向下方向的度数（写入 `flameLevel`/`flameGimbalDeg`，`Part.java:179-182`） |

### 4.8 连接点与执行器自定义（`ModApi.java:212-266`）

| 方法 | 说明 |
|---|---|
| `setJointParams{frequencyHz=, dampingRatio=, angularDamping=}` | 覆盖本零件焊接关节的弹簧-阻尼参数，键可省略；`angularDamping` 立即写入自身 body（`ModApi.java:223-231`）。两个零件焊接时 frequencyHz **高者胜**（更硬的一侧说了算），其 dampingRatio 随同 |
| `getJointParams()` | 读回本零件的覆盖值表（未设置的键为 nil），供 `joints.lua` 折叠进默认规则（`ModApi.java:243-249`） |
| `physicsNumber(section, key)` | 读 `physics.lua` 中任意数值表项，如 `part:physicsNumber("gimbal", "kp")`，缺失时回退内置默认（`ModApi.java:260-262`） |
| `getGimbalDeg()` / `setGimbalDeg(deg)` | 引擎 gimbal 实际偏转角（度），由 Lua 控制律驱动 |
| `readModText(name)` | 以与零件脚本相同的查找顺序读一个 mod 文件文本；只允许纯文件名（拒绝 `/`、`\`、`..`，`ModApi.java:123-138`）。引擎脚本用它把共享的 `control.lua` 加载进自己的独立状态 |
| `log(msg)` | 打印到控制台，带 `[lua:<typeId>]` 前缀 |

## 5. PID 库的注入与使用约定

每个零件状态创建时都会先注入一段纯 Lua 的 PID 库（`ModManager.java:86-100`），注入后全局存在 `pid` 表：

```lua
local ctl = pid.new(kp, ki, kd)               -- 一般在 onLoad 里创建
local rate = ctl:update(target, current, dt)  -- 每帧求控制输出
```

实现细节（`ModManager.java:87-100`）：

- 积分带 **anti-windup**：积分项钳位在 ±30。
- **微分作用于测量值（derivative on measurement）而非误差**：目标值阶跃时不会踢执行器一脚（注释明确说明这是标准 anti-kick 做法，`ModManager.java:94-96`）。
- `dt <= 1e-9` 时微分项为 0，避免除零。

使用约定（`ModManager.java:76-85` 注释）：

- 增益不写死在引擎脚本里，而是从 `physics.lua` 读：`part:physicsNumber("gimbal", "kp")` 等，玩家只改 `physics.lua` 即可调手感。gimbal 的 Java 侧默认值为 `kp=8.0, ki=0.1, kd=0.6, maxRateDeg=90`（`PhysicsScript.java:163-171`）。
- `pid` 是普通 Lua 表、每个零件状态注入一份，玩家脚本甚至可以整体替换它。

> **注意（round 12 变更）**：每引擎的 gimbal PID 已被共享控制律取代——偏转角现在由 `mod/control.lua` 的 `controlLaw(part)` 直接给出（按钮 = 满偏 / 转向环 = 截断的航向误差 / 无输入 = 回中），引擎脚本在 `onLoad` 里用 `part:readModText("control.lua")` + `load()` 把它加载进自己的独立状态（`engine-0.lua` 第 36-41 行）。`physics.lua` 里的 `steering` 表仅为兼容旧玩家脚本保留，已无读者（`physics.lua` 第 40-43 行注释）。PID 库仍注入，供玩家自己的脚本使用。

## 6. JointScript：用 Lua 定义连接点参数

零件之间用弹簧-阻尼焊接关节（weld joint）连接。**每一处连接**形成时都会先询问 `mod/joints.lua`（`JointScript.java:22-79`）：

```lua
jointParams(partA, attachA, partB, attachB)
    -> { frequencyHz=…, dampingRatio=…, angularDamping=…, breakForce=…|nil }
```

- 连接点以小表传入：`{x, y, fuelLine, edge, breakForce}`（`JointScript.java:81-89`）。`edge`：0 = 单点，1..4 = 左/右/顶/底整条边；`breakForce` 单位千牛，不可断的点省略该键。
- 返回表的键都可省略：`frequencyHz`/`dampingRatio` 缺省读 `physics.lua` 的 `joints` 表（再缺省用 Java 内置默认），`breakForce` 缺省由调用方取两连接点较小值（`JointScript.java:58-63`）。
- `angularDamping` 若返回则**同时写入两侧 body**（`JointScript.java:64-68`）。
- 脚本缺失、函数不存在或抛错 → 返回 false，调用方走内置规则（每零件 `setJointParams` 覆盖值，frequencyHz 高者胜）。脚本报错置 `callFailed`，在本次加载的 Globals 存活期内不再调用，热重载出新 Globals 后自动复位（`JointScript.java:48-49, 71-78`）。

Java 侧焊接点在 `Ship`（`Ship.java:138-143`）：`JointScript.resolve` 成功则采用 Lua 的 `frequencyHz`/`dampingRatio`/`breakForce`，否则退回内置规则；`breakForce` 最终落到 `min(apA.breakForce, apB.breakForce)`。

内置 `joints.lua` 的默认实现就是内置规则的 Lua 版：读两侧 `part:getJointParams()` 覆盖值 → frequencyHz 高者胜（其 dampingRatio 随同）→ 缺省键回落 `partA:physicsNumber("joints", …)`（即 `physics.lua` 的 `joints` 表）→ 再缺省用 Java 调好的默认值 `frequencyHz=20, dampingRatio=1.1, angularDamping=0.08`（`PhysicsScript.java:118`）。注释里说明 20 Hz 是有意选在 60 Hz 物理步长的 Nyquist 极限（30 Hz）之下（`PhysicsScript.java:115-117`）。

**调用顺序约束**：`onLoad` 先于焊接执行（§3），所以零件脚本在 `onLoad` 里 `setJointParams` 的覆盖值能参与本次焊接解析——这是 round 9 的刻意设计（`Ship.java:78-81` 注释）。

## 7. PhysicsScript：用 Lua 定义物理律

`mod/physics.lua` 可整体接管引力与大气模型（`PhysicsScript.java:26-179`）。暴露给 Lua 的环境：

- **`world` 代理**（`WorldProxy`，`PhysicsScript.java:29-38`）：`planetCount()`、`planetName(i)`、`planetX/Y(i)`（宇宙坐标）、`planetMu(i)`（GM）、`planetRadius(i)`。
- **`planetEnv` 表**：按星球名给出 `{atmoHeight, surfacePressure, scaleHeight}`（`PhysicsScript.java:57-65`）。
- 两者在 `ensureBound(world)` 时注入：脚本热重载或世界切换后重新绑定，`GameWorld` 构造和每帧 update 都会调用（`GameWorld.java:122, 524`）。

脚本可定义的接口：

| Lua 侧 | Java 消费点 | 回退 |
|---|---|---|
| `gravityAccel(x, y, timeSec) -> ax, ay` | `PhysicsScript.gravity`（`PhysicsScript.java:74-88`），被 `GameWorld.gravityAt` 优先调用（`GameWorld.java:133-135`） | 返回 false → 内置逐星球 GM/r² 牛顿求和 |
| `atmosphereDensity(planetName, altitude) -> kg/m³` | `PhysicsScript.density`（`PhysicsScript.java:91-103`），被 `GameWorld.densityAt` 调用（`GameWorld.java:214-215`） | 返回 NaN/出错 → 内置指数大气模型 |
| `joints = {frequencyHz, dampingRatio, angularDamping}` | `PhysicsScript.jointParam`（`PhysicsScript.java:125-134`），焊接参数与 `joints.lua` 的共同缺省源 | 逐键回退 20 / 1.1 / 0.08 |
| `gimbal = {kp, ki, kd, maxRateDeg}` | `part:physicsNumber("gimbal", …)`（`PhysicsScript.tableNumber`，`PhysicsScript.java:141-150`） | 逐键回退 8.0 / 0.1 / 0.6 / 90 |

错误策略：**任何一次运行时错误禁用整个文件**（`callFailed`，只记一次日志），内置物理律接管，直到玩家修复保存触发热重载（`PhysicsScript.java:173-178`；`physics.lua` 头部注释同述）。

性能注意：`gravityAccel` 是**每零件每物理 tick** 在 Lua 里执行，注释提醒保持其廉价；当前零件规模（数十个）没问题，若 profiling 出现问题应改为按飞船按帧批量传数组（`PhysicsScript.java:23-25`）。

## 8. FlameScript + FlameFx：程序化尾焰

尾焰渲染完全由 `mod/flame.lua` 决定，Java 只提供合批原语与粒子池。调用链在 `SandboxScreen.java:1305-1336`：`FlameScript.begin(dtSim)` → 每台运转引擎 `FlameScript.drawPart(...)` → `flush(shapes)` + `flushSprites(batch)`。

### 8.1 提供给 Lua 的渲染 API（`FlameScript.java:67-130`）

每次脚本（重）加载出新 Globals 后重新安装两张表：

- **`draw.triangle(x1,y1, x2,y2, x3,y3, r,g,b,a)`**：世界坐标三角形，写入 10 float/个 的批缓冲（初始 4096，不够倍增，`FlameScript.java:37, 210-215`），`flush` 时经 `ShapeRenderer` 普通半透明混合一次性画出。
- **`draw.sprite(tex, x, y, w, h, angleDeg, alpha [, r, g, b])`**：贴图精灵，11 float/个 批缓冲；`flushSprites` 用 **additive 混合**（`GL_SRC_ALPHA, GL_ONE`）渲染，并在结束后恢复原先的 blend function（`FlameScript.java:187-208`）。
- **`flame.emit{tex, x, y, vx, vy, life, size0, size1, r, g, b, a0, a1, drag}`**：向 `FlameFx` 粒子池发射一个粒子（全部键有默认值，`FlameScript.java:107-123`）。
- **`flame.count()`**：当前存活粒子数。

### 8.2 drawFlame(ctx) 与气压相关马赫环

每台运转中的引擎每帧调用一次 `drawFlame(ctx)`（`FlameScript.java:140-171`）。`ctx` 字段：`x, y`（喷口世界坐标）、`dirX, dirY`（喷流单位方向）、`angle`、`nozzleW`、`throttle`（0..1+ 焰级）、`engineSize`、`engineHeight`、`time`、`dt`（**含 warp 的模拟秒**，使 4x warp 下发射速率稳定）、`partId`（每台引擎稳定 id，供脚本做按引擎累积状态）、`fuelType`（2 = 离子）/`ion`、以及关键的 **`pressure`（喷口环境气压，1.0 = 海平面，0 = 真空）和 `density`**（`FlameScript.java:146-161`）。

内置 `flame.lua`（`core/assets/mods/flame.lua`）按气压分层构造尾焰：

- **任意高度**：三角形激波锥铺底 + 轴向 4 段 glow 精灵串作准直核心；核心颜色随真空度从橙白渐变到蓝白（`flame.lua:102-105`）。
- **马赫环（Mach diamonds）**：`md = clamp((p - 0.12) / 0.5, 0, 1)`，海平面最强，p < 0.12（约 15 km）完全消失；4 枚 glow 亮斑沿核心下方排列（`flame.lua:156-165`）。
- **中气压（p ≈ 0.05..0.6）**：带 `drag = 1.5` 的 smoke 烟团，边减速边胀大变淡，模拟平流层凝结羽（`flame.lua:202-216`）。
- **高空/真空**：激波锥张开 `1 + 5 * vac^0.7` 倍、核心变宽变淡偏蓝，额外发射 ±35° 稀疏宽扇微粒模拟欠膨胀喷流（`flame.lua:135-137, 218-232`）。
- **离子引擎**：细长蓝羽 + 稀疏高速蓝色火花，无烟（`flame.lua:107-131`）。

发射预算用 `budget(id, stream, rate, dt)` 小数累积器：速率按"个/模拟秒"定义、按 `ctx.dt` 折算每帧整数发射量，key 为 `partId * 16 + stream`，因此 4x warp 下粒子速率稳定（`flame.lua:69-80`）。

脚本缺失或 `drawFlame` 抛错 → Java 画内置默认尾焰（只记一次日志，`FlameScript.java:30-32, 164-170`）。

### 8.3 FlameFx：Java 侧粒子池（`FlameFx.java`）

- **SoA 环形缓冲**，硬上限 `MAX = 600`，写满回收最旧槽位（`FlameFx.java:15, 21-31, 55-70`）。
- 粒子参数：位置、速度、指数阻尼 `drag`（`exp(-drag*dt)` 衰减，`FlameFx.java:81-84`）、寿命、尺寸 `size0→size1` 与透明度 `a0→a1` 随寿命线性插值（`FlameFx.java:97-99`）。
- 渲染为 additive 混合，调用方不得已 begin batch，相机已设置（`FlameFx.java:88-107`）。
- `update(dt)` 接收**含 warp 的模拟秒**（`FlameFx.java:72-73`）。
- **三张纹理全部程序化生成、无任何贴图资源文件**（`FlameFx.java:120-179`）：
  - `glow`（64×64）：径向平方衰减软光斑，用于核心/马赫环/真空羽流；
  - `spark`（32×32）：硬芯 + 短衰减亮点，用于火花；
  - `smoke`（64×64）：两个 octave 的 value noise 斑块（`vnoise` + `hash01` 整数哈希，`FlameFx.java:181-196`），外部套径向 mask，用于烟羽。
- 纹理懒加载于 `ensureTextures()`；`texIdForName` 把 Lua 的 `"glow"/"smoke"/"spark"` 映射到 id，未知名字回退 glow（`FlameFx.java:43-47`）。

## 9. 共性约定与排错速查

- **统一回退哲学**：任何 Lua 脚本缺失/编译失败/运行时错误，都只记一次日志并回退内置行为，游戏永不因玩家脚本崩溃。
- **热重载边界**：`LuaScript` 家族（physics/joints/flame/terrain）保存约 1 秒生效；零件行为脚本（ModManager 家族）只影响**新创建**的零件状态，已存在零件要到资源重载/重新载入后才换脚本。
- **日志关键字**：`mods`（零件脚本加载）、`res`（版本与来源）、`lua:<file>`（单文件脚本）、`[lua:<typeId>]`（脚本内 `part:log`）。
- **玩家脚本版本**：改动内置脚本时记得更新首行 `-- vYYYY.MM.DD` 注释，版本同步机制（`Res` 的 `.defaults/` shadow，`Res.java:22` 附近注释）与日志排错都依赖它。
- **坐标系**：ModApi 全部是物理系（米，y 向上，相对原点）；`PhysicsScript` 的 `gravityAccel` 是宇宙系。两个系不要混用。
