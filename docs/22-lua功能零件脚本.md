# 22 · Lua 功能零件脚本（pod / dock / port / detacher / parachute / rcs / battery / solar）

> 目标读者：刚接手 DifferentRockets 的开发者。本文逐文件、逐函数讲解 `game/core/assets/mods/` 下 9 个「功能零件」脚本（结构/装饰类零件的脚本见其他章节）。
>
> 所有事实均来自仓库源码，引用格式为 `文件名:行号`。Lua 脚本路径省略前缀 `game/core/assets/mods/`，Java 源码省略前缀 `game/core/src/com/differentrockets/`。

## 0. 共读背景（适用于全部 9 个脚本）

- 每个零件**实例**拥有独立的 Lua 状态（luaj `Globals`），在首次触发钩子时按需创建：`Part.java:184-187`（`callOnLoad` 里 `ModManager.createState(type.id)`）。因此脚本顶层局部变量（如 engine 脚本里的 `staged`）是「每零件一份」，不会在零件间共享，也不会随存档持久化。
- 三个钩子：`onLoad(part)`（零件创建/存档载入后调用一次）、`onStage(part)`（分级/激活组触发）、`onUpdate(part, dt)`（每物理帧）。入口分别在 `Part.java:184-196`。
- `part` 是 `ModApi` 实例（`game/ModApi.java`），本文大量引用其中的方法注释。
- 燃料类型编号（贯穿 battery / rcs / solar / engine 脚本的硬编码整数）：

| fuelType | 含义 | 供给范围（`Ship.drainScope`，`Ship.java:333-354`） |
|---|---|---|
| 0 | liquid（化学燃料） | **仅**通过 `fuelLine` 连接点连成的燃料管网 + 直接贴在消费者身上的油箱 |
| 1 | monopropellant（RCS 单组元） | 全船共享，无需管路 |
| 2 | electric（电力） | 全船共享（电池没有 fuelLine 概念） |
| 3 | solid（固体助推） | 只烧消费者自身油箱，永不传输 |

- 9 个脚本全部带版本头 `-- v2026.07.21`（ion-0.lua 为 v2026.07.22）。内置 33 个 lua 脚本首运行拷贝到 `/sdcard/DifferentRocket/mod` 供玩家修改（拷贝清单见 `util/Res.java:80` 附近），因此脚本注释里反复强调「PLAYER-EDITABLE」——改这些数字就是改游戏手感。

---

## 1. pod-1.lua — 指令舱：控制核心，但自身零转向能力

全文仅 24 行（`pod-1.lua:1-24`），三个钩子全部为空。**这是刻意的历史决策，不是没写完**。

### 1.1 控制核心地位体现在哪

pod 的「核心」地位不在 Lua 里，而在 Java 侧：

- **航向参考件**：`Ship.controlPart()`（`Ship.java:591-595`）优先返回 type 为 `"pod"` 的零件，否则退回第一个零件。转向控制器 `GameWorld.updateSteering`（`GameWorld.java:889-913`）用这个零件的 body 角度作为船头航向，计算转向环（steering ring）的航向误差。
- **分裂时保住主船身份**：船体断开成多个连通分量时，含 pod 的分量加 10000 分，保证它留在「主船」里（`Ship.java:235`）。
- **自带应急电池**：`PartList.load()` 给每个没有油箱定义的 pod 类型自动追加一个 50 单位 electric 油箱（`PartList.java:18-26`），pod-1.lua 头注释也写明这一点（`pod-1.lua:12-13`）。注意 `PartList.xml:2-15` 的 pod-1 定义里**没有** `<Tank>` 元素——这个油箱是代码补的，XML 里看不到。
- XML 中 `hidden="true"`（`PartList.xml:2`）：pod 不出现在编辑器调色板，是设计阶段强制的根零件。

### 1.2 为什么 pod 不带转向力矩（历史决策）

`pod-1.lua:5-11` 的头注释写得很明确：pod 是控制**输入**源，自己**不施加任何力矩**（"owner requirement"，`pod-1.lua:23`）。转向能力只来自两个物理执行器：

1. **发动机摇摆（gimbal）**：发动机推力时响应 `part:getTurn()`/控制律偏转喷管（见 `ion-0.lua` / engine 系列脚本的 `updateGimbal`）；
2. **RCS 喷咀**：烧 monopropellant 产生力矩（见第 5 节）。

转向命令的产生链路（round 12 之后的现状）：

- `GameWorld.inputTurn`（`GameWorld.java:77`）是当前 -1..1 转向命令，Lua 侧经 `part:getTurn()` 读取（`ModApi.java:84`）。
- 命令来源是 `SteeringIO`（按钮 = 满偏；转向环 = 航向误差的比例截断，15° 满偏，`GameWorld.java:901-911`）。**船级 PI 航向控制器已在 round 12 移除**（`physics.lua:37-39`），`physics.lua:41` 的 `steering = {kp=1.8, ki=0.5, kd=1.2}` 是保留参数。pod-1.lua 头注释里"PI heading-controller"的表述（`pod-1.lua:10-11`）是 round 12 之前的遗留描述，以 Java 现状为准。
- 符号约定：正的 turn 命令产生**顺时针**力矩，engine gimbal 与 RCS 脚本都遵守（`GameWorld.java:886-887`）。

### 1.3 逐函数

| 函数 | 行为 |
|---|---|
| `onLoad(part)`（`pod-1.lua:16-17`） | 空。 |
| `onStage(part)`（`pod-1.lua:19-20`） | 空。pod 不参与分级。 |
| `onUpdate(part, dt)`（`pod-1.lua:22-24`） | 空，注释重申"no built-in torque by design"。 |

**改动警告**：不要图省事在 pod 的 `onUpdate` 里加 `applyTorque`——那会绕开 gimbal/RCS 的燃料约束与摇摆在低速失效的真实感设计，且与"无内置力矩"的 owner 需求直接冲突。

---

## 2. dock-1.lua / port-1.lua — 对接插头与对接口：唯一的职责是「软连接」

两个文件几乎逐字相同（`dock-1.lua:1-17`、`port-1.lua:1-16`）：dock-1 是船侧插头（XML type `dockconnector`，`PartList.xml:243`，`maxOccurrences="1"` 每船限一个），port-1 是接受侧接口（XML type `dockport`，`PartList.xml:257`）。

### 2.1 核心机制：per-part joint override

```lua
function onLoad(part)
  part:setJointParams{frequencyHz = 8.0, dampingRatio = 0.9}
end
```

- 零件之间用弹簧-阻尼 weld joint 连接；`setJointParams` 是 round 9 引入的每零件覆盖（`ModApi.java:212-231`），存入 `Part.jointFreqHz/jointDampRatio`（`Part.java:40-42`）。
- **合并规则**：两个零件焊接时，**frequencyHz 更高（更硬）的一方胜出**，其 dampingRatio 一并采用；为 nil 的键回落到 `physics.lua` 的 `joints` 表 → Java 默认值（`ModApi.java:214-222`；实现见 `Ship.java:136-160`）。
- 8 Hz / 0.9 属于**软**连接（默认焊接远比这硬）：故意让对接后的组合体有一点弹性余量，两艘对接的船不会刚性地互相较劲（两个脚本头注释，round 9）。

### 2.2 逐函数

| 函数 | dock-1 | port-1 |
|---|---|---|
| `onLoad` | 设软连接参数 8 Hz / 0.9（`dock-1.lua:9-11`） | 同上（`port-1.lua:8-10`） |
| `onStage` | 空 | 空 |
| `onUpdate` | 空 | 空 |

**注意**：两个脚本不含任何对接捕获/锁定逻辑；Java 侧也检索不到 dock 相关的专门行为代码（全仓库仅 `util/Res.java:80` 的资源拷贝清单出现 "dock"）。脚本注释中 "a docked joint has a little give" 描述的是这类零件参与焊接时的连接手感；若未来要实现真正的船间对接捕获，这两个文件是挂 `onStage`/`onUpdate` 逻辑的自然位置。

---

## 3. detacher-1.lua / detacher-2.lua — 分离器：一行 `part:detach()` 背后的崩溃史

两个文件**完全相同**（`detacher-1.lua:1-11` = `detacher-2.lua:1-11`），区别只在 XML 连接点方向：detacher-1 是 TopCenter/BottomCenter 的纵向分离器（`PartList.xml:16-21`），detacher-2 是 LeftCenter/RightCenter 的侧向分离器（`PartList.xml:22-27`）。脚本相同意味着「在哪一侧断开」完全由焊接拓扑决定，与 Lua 无关。

### 3.1 onStage 流程全链路

```lua
function onStage(part)
  part:detach()
end
```

看似一行，实际触发一条精心设计的**延迟执行**链路：

1. `part:detach()` → `Part.detachJoints()`（`ModApi.java:181-182`）。
2. `detachJoints` **不立即**毁 joint，而是把「毁 joint + 可能触发的船体分裂」打包成 Runnable 放进 `GameWorld.deferStructure` 队列（`Part.java:164-177`）。
3. 在安全的时机统一执行队列：分级激活后（`Ship.activateStage` 末尾，`Ship.java:526`）或激活组按钮处理后（`SandboxScreen.java:762`），调 `GameWorld.processDeferredStructure()`（`GameWorld.java:264-275`，单个 op 抛异常只记日志不影响其余）。
4. 执行时先防御性检查零件是否仍属于该船（`Part.java:172-176`），然后 `Ship.removeJointsOf`（`Ship.java:173-180`）毁掉该零件的所有 joint，再 `splitIfDisconnected()`（`Ship.java:204-276`）做连通分量 BFS 分船。
5. 分裂出去的碎片船 `currentStage` 被设为极大值，**永久失去分级能力**（下标已失效，`Ship.java:255-256`）；主船则重建剩余分级表（`Ship.java:259-275`）。

### 3.2 历史崩溃 bug 背景（为什么必须是延迟队列）

源码里留有三处互相印证的记录：

- `GameWorld.java:254-259`：Lua 回调里的结构变更「必须 NEVER 内联执行」——回调可能正深处 parts/ships 迭代之中，在那里改图会**并发修改崩溃**（concurrent modification / stale refs）。
- `SandboxScreen.java:749-751`：ACTIVATE 按钮按**激活组**（`group > 0` 的零件同组齐发，`PartList.xml` 里 rcs/solar/dock 的 attach point 有 `group="1"` 属性，零件激活组见 `SandboxScreen.java:748-754`）触发时，历史上「一边迭代 live parts 列表、一边被 detacher 的 onStage 分裂改列表」正是 **group-activate 崩溃**的成因。修复 = 先把组成员快照成 `targets`，再逐个触发，且触发前重新校验零件仍存活（`SandboxScreen.java:752-761`）。
- `Ship.java:515-517`：分级激活同理——先把分级表里的下标解析成 Part 引用快照，再触发 onStage。

**改动警告**：任何新的「在 Lua 回调里改船体结构」的 API（destroy/spawn 等）都必须走 `deferStructure`，这是用崩溃换来的规矩。

### 3.3 逐函数

| 函数 | 行为 |
|---|---|
| `onLoad(part)` | 空。 |
| `onStage(part)` | `part:detach()`，延迟断开全部 joint 并触发分船（见 3.1）。 |
| `onUpdate(part, dt)` | 空。分离器无持续行为。 |

---

## 4. parachute-1.lua — 降落伞：大气阻力完全参数化

### 4.1 阻力模型（Java 侧）

每零件阻力 `F = 0.5 · ρ · v² · Cd · A`（`GameWorld.java:823`），其中：

- `ρ` 来自**玩家可改的** `mod/physics.lua` 的 `atmosphereDensity`（round 14 修复：之前误用内置 `Planet.densityAt`，改 physics.lua 不生效，`GameWorld.java:803-807`）；
- `v` 是相对行星的风相对速度（`GameWorld.java:809-815`）；
- `Cd`、`A` 是每零件参数：Lua `setDrag`/`setDragArea` 设的绝对值优先，否则用默认 `max(0, 0.75 + PartList.xml drag)` 和零件宽度（`GameWorld.java:819-822`、`ModApi.java:188-207`）；
- 最后再乘 `dragExposure`（被上游结构遮挡的程度，8 条 raycast 采样，`Ship.java:430-508`）。

### 4.2 状态机

```lua
onLoad:  Cd = 1.2, A = 零件宽度          -- 收拢：只有外壳的小阻力
onStage: 若 ρ > 0.0005  → 展开, Cd = 8, A = 36 m²
onUpdate: 若已展开且 ρ < 0.00001 → 收回, 恢复 Cd = 1.2 / A = 宽度
```

- `part:getAtmoDensity()` 是零件当前位置的密度（`ModApi.java:75`），两个阈值差约 50 倍，形成迟滞，避免在临界密度反复开合。
- `setDeployed(true/false)` 置 `Part.deployed` 标志（`Part.java:30`）：渲染层据此画伞盖（`SandboxScreen.java:1288-1289`），存档会持久化（写出 `Ship.java:688`，读回 `Ship.java:736`），所以存/读档不会丢伞状态。
- 真空里按空格不会展开（密度检查失败），但已展开的伞飞出大气会自动"收伞"——这是防呆而非真实复用。

### 4.3 逐函数

| 函数 | 行 | 行为 |
|---|---|---|
| `onLoad(part)` | `parachute-1.lua:7-10` | 设收拢态阻力：绝对 Cd 1.2、参考面积 = 零件宽度（`getWidth()`，`ModApi.java:167`）。 |
| `onStage(part)` | `parachute-1.lua:12-18` | 仅当大气密度 > 0.0005 时：`setDeployed(true)` + Cd 8 + 面积 36 m²，产生真实减速。 |
| `onUpdate(part, dt)` | `parachute-1.lua:20-26` | 若已展开且密度 < 0.00001（离开大气），收回并恢复收拢参数。 |

相关 XML：`PartList.xml:197-201`——`canExplode="false"`，且唯一连接点带 `breakForce="150.0"`（150 kN 反应力断连，断连判定见 `Ship.java:190-201`），粗暴着陆时伞会被扯掉而不是无限吊着。

---

## 5. rcs-1.lua — RCS 姿控：全船共享 mono，无需燃料管

```lua
function onUpdate(part, dt)
  local turn = part:getTurn()
  if turn == 0 then return end
  local need = part:getRcsConsumption() * 10 * dt
  local got = part:drainFuel(1, need)
  if need > 0 and got / need > 0.2 then
    part:applyTorque(-turn * part:getRcsPower() * 60000)
    part:emitFlame(0.2, 90 * turn)
  end
end
```

### 5.1 燃料规则（round 11 的历史决策）

- `drainFuel(1, need)` 的 fuelType 1 = monopropellant。**mono 全船共享**：船上任何 mono 油箱给所有 RCS 供油，无需 fuelLine 管路——脚本头注释（`rcs-1.lua:2-5`，中英双语）与 Java `drainScope` 注释（`Ship.java:326-327`，"round 11 item 4 — 匹配 KSP 规则"）一致，实现即 `drainScope` 直接返回 `tanksOf(1)`（`Ship.java:342`）。
- 对比：化学燃料（type 0）走燃料管网（见第 7 节 battery 的 transferFuel 讨论和 engine 脚本），电力（type 2）同样全船共享（`Ship.java:341`）。
- 多油箱按储量比例均匀抽取（`Ship.drainFuel`，`Ship.java:362-371`）。

### 5.2 逐行要点

- `part:getTurn()` 读全局转向命令（`ModApi.java:84` → `GameWorld.inputTurn`），无输入直接 return，不耗油。
- `need = consumption × 10 × dt`：`getRcsConsumption()` 来自 XML `<Rcs power="1.0" consumption="0.1" .../>`（`PartList.xml:215-216`，`ModApi.java:175-176`），×10 是脚本内手感系数。
- **20% 供油门槛**：`got/need > 0.2` 才出力——油量濒临耗尽时 RCS 直接罢工而不是输出抖动的小力矩；一旦出力就是全额力矩。
- `applyTorque(-turn × power × 60000)`：负号配合「正命令 = 顺时针」的全局约定（`GameWorld.java:886-887`），60000 是力矩增益；直达 Box2D body（`ModApi.java:47-49`）。
- `emitFlame(0.2, 90 × turn)`：小型火焰特效，按转向方向偏 90°（`ModApi.java:209-210`，每帧由 `Ship.updateScripts` 清零重计，`Ship.java:538-543`）。

### 5.3 与电推（ion）的对照

RCS 烧 mono（全船共享）、电推发动机烧电力（fuelType 2，同样全船共享，`ion-0.lua:6` 头注释及 `Ship.java:341`）。两者都是「无管路」资源；唯一需要 fuelLine 管网的是 type 0 化学燃料。

---

## 6. battery-0.lua — 电池：其实是「通用油箱均衡器」脚本

**重要认知**：`battery-0.lua` 的文件内容并不是电池专用逻辑，而是 fueltank 系列共用的**通用油箱脚本**——头注释自述 "Generic fuel tank"（`battery-0.lua:2-3`）。电池的特殊性全在 XML：`<Tank fuel="250.0" dryMass="1.24" fuelType="2" />`（`PartList.xml:232-241`），fuelType 2 = 电力。

### 6.1 onUpdate 的均衡算法

```lua
local ft = part:getFuelType()
if ft < 0 then return end                 -- 无油箱零件不跑
local myShare  = part:getFuel() / cap     -- 本箱充满度
local netShare = part:getFuelTotal(ft) / totalCap  -- 全网充满度
local diff = myShare - netShare
if math.abs(diff) > 0.002 then
  part:transferFuel(ft, diff * cap * math.min(1, dt * 2))
end
```

- 每个油箱每帧把自己与「供给范围」的充满度之差，按 `dt × 2` 的速率搬移——视觉上就是同类型油箱缓慢互相均衡。
- `transferFuel` 的范围规则（`Ship.java:386-416`）：**电力和 mono 在全船范围内均衡；液体燃料只在自己的 fuelLine 管网内均衡；固体燃料永不传输**。电池（type 2）因此天然全船互联，这也解释了 `drainScope` 注释里"电池没有 fuelLine"（`Ship.java:325`）。
- 0.002 死区防止末位抖动导致的无限微搬移。
- 搬移是容量加权的（按对方空余空间/可用量比例分摊，`Ship.java:395-415`），不会造出负油或超容量。

### 6.2 逐函数

| 函数 | 行为 |
|---|---|
| `onLoad` / `onStage`（`battery-0.lua:4-8`） | 空。 |
| `onUpdate`（`battery-0.lua:10-22`） | 上述均衡逻辑；`ft < 0`（非油箱）直接返回。 |

### 6.3 「电力恢复」链路

电池自身不发电，电力恢复靠 solar-1.lua 充电（第 7 节）+ 本脚本的均衡把电摊到全船电池。pod 自带的 50 单位应急电（`PartList.java:18-26`）也参与这张网。

---

## 7. solar-1.lua — 太阳能板：日照检测 + 全船充电

全文 13 行（`solar-1.lua:1-13`），唯一逻辑在 `onUpdate`：

```lua
function onUpdate(part, dt)
  if part:isInSunlight() then
    part:addFuel(2, part:getSolarChargeRate() * dt)
  end
end
```

- `isInSunlight()`：从零件位置向太阳中心做线段求交，被任何行星圆盘遮挡即 false（`GameWorld.java:232-246`）——没有每零件的被船体遮挡计算，只有星体级遮挡。
- `getSolarChargeRate()` 来自 XML `<Solar chargeRate="2.0" />`（`PartList.xml:223-224`，`ModApi.java:177`），即每块板 2 单位电/秒。
- `addFuel(2, ...)` 把电注入**全船电网**：按各电池空余容量比例分摊，真正加不进去的部分（全网满电）丢弃并返回 0（`Ship.java:418-428`）。
- 电力消耗端：ion 电推（fuelType 2，`ion-0.lua`）经同一个全船电网 `drainFuel` 取电（`Ship.java:341`）。

### 逐函数

| 函数 | 行为 |
|---|---|
| `onLoad` / `onStage`（`solar-1.lua:3-7`） | 空。太阳能板不需要分级展开动作（XML 里无 deployed 语义绑定）。 |
| `onUpdate`（`solar-1.lua:9-12`） | 在日照下按 chargeRate 向全船电网充电。 |

---

## 8. 速查表

| 脚本 | 关键 API | 关键常量 | 关联 Java / XML |
|---|---|---|---|
| pod-1.lua | （空钩子） | 应急电 50 | `PartList.java:18-26`、`Ship.java:591-595`、`GameWorld.java:889-913` |
| dock-1.lua / port-1.lua | `setJointParams` | 8 Hz / 0.9 软连接 | `ModApi.java:212-231`、`Ship.java:136-160`、`PartList.xml:243,257` |
| detacher-1/2.lua | `detach()` | 延迟结构变更队列 | `Part.java:164-177`、`GameWorld.java:254-275`、`SandboxScreen.java:749-762` |
| parachute-1.lua | `setDrag/setDragArea/setDeployed` | 收拢 Cd 1.2；展开 Cd 8 / 36 m²；阈值 0.0005 / 0.00001 | `GameWorld.java:799-829`、`SandboxScreen.java:1288-1289`、`PartList.xml:197-201` |
| rcs-1.lua | `getTurn/drainFuel(1,..)/applyTorque/emitFlame` | consumption×10、20% 门槛、力矩增益 60000 | `Ship.java:341-342,362-371`、`PartList.xml:215-221` |
| battery-0.lua | `transferFuel` | 死区 0.002、速率 dt×2 | `Ship.java:386-416`、`PartList.xml:232-241` |
| solar-1.lua | `isInSunlight/addFuel(2,..)` | chargeRate 2.0/s | `GameWorld.java:232-246`、`Ship.java:418-428`、`PartList.xml:223-230` |

**给改动者的三条铁律**：

1. 结构变更（detach/未来 destroy/spawn）必须走 `GameWorld.deferStructure`，严禁在 Lua 回调里内联改船体图（崩溃史见 §3.2）。
2. 不要在 pod 里加力矩；转向只能来自 gimbal 和 RCS（§1.2）。
3. 只有 type 0 化学燃料认 fuelLine 管网；mono / 电力全船共享、固体只烧自己——写新零件脚本选 fuelType 前先查 §0 的表。
