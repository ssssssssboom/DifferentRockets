# API 参考：零件 · 飞船 · 行星

> 本文档覆盖 `game/core/src/com/differentrockets/game/` 包中以下 9 个类：
> `Part`、`PartType`、`PartList`、`ShipDesign`、`Ship`、`Attach`、`Planet`、`PlanetDefs`、`SteeringIO`。
> 所有事实均来自源码精读，引用格式为 `文件名:行号`（行号基于仓库当前版本）。

## 总览：分层关系

```
PartList.xml / planets.lua / SmolarSystem.xml   （数据定义层）
        │
        ▼
PartType（零件静态定义）    PlanetDefs → Planet（天体定义 + Kepler 轨道）
        │
        ▼
ShipDesign.DesignPart ──► Part（运行时实例 = 独立 Box2D dynamic body）
        │                      ▲
        ▼                      │ WeldJoint（spring-damper）
Ship（零件集合 + 连接图 + 燃料网络 + 阶段列表 + 宇宙坐标 origin）
        │
        ▼
Attach（attach point 几何工具，编辑器吸附与 Ship 焊接共用）
SteeringIO（UI 写入、引擎/steering 脚本读取的共享输入状态）
```

核心设计要点：

- 每个 `Part` 是一个**独立的 Box2D dynamic body**，零件之间用 spring-damper `WeldJoint` 连接传力（`Ship.java:127-171`）。
- 每个 Part 实例有**独立的 Lua 状态**（`Part.lua` 字段），通过 `onLoad` / `onUpdate` / `onStage` 三个钩子驱动行为（`Part.java:184-196`）。
- 天体全部走 **Kepler 轨道（rails）**，位置/速度由解析公式推出，不做数值积分（`Planet.java:87-123`）。
- 远处不活跃的飞船整体 "on rails"：body 休眠，只对 `origin` 做简单重力积分（`Ship.java:598-641`）。

---

## Part — 运行时零件实例

**职责**（`Part.java:17-21`）：一个火箭零件的运行时实例，即一个独立的 Box2D dynamic body。物理 body、fixture、质量、燃料、关节覆写参数、气动暴露度都在此类；行为完全由该实例自己的 Lua 脚本（经 `ModManager`）驱动。

### 重要字段

| 字段 | 类型 | 说明 | 位置 |
|---|---|---|---|
| `type` | `PartType`（final） | 静态类型定义 | Part.java:22 |
| `ship` | `Ship` | 所属飞船（分裂后会改指向新 Ship） | Part.java:23 |
| `design` | `ShipDesign.DesignPart`（final） | 编辑器放置数据（含 `rot` 旋转步数） | Part.java:24 |
| `body` | `Body` | Box2D body，销毁后置 null | Part.java:25 |
| `api` | `ModApi`（final） | 暴露给 Lua 的 Java API 对象 | Part.java:26 |
| `lua` | `Globals` | 本实例独立的 Lua 状态，首次 `callOnLoad` 时惰性创建 | Part.java:27 |
| `fuel` | `double` | 当前燃料量（油箱/SRB/电池通用） | Part.java:29 |
| `deployed` | `boolean` | 降落伞/着陆腿展开状态 | Part.java:30 |
| `stageActivatedThisFrame` | `boolean` | 本帧是否被级联点火（供 Lua onUpdate 读取） | Part.java:31 |
| `dragCd` / `dragArea` | `double` | Lua 可覆写的阻力系数/参考面积，NaN = 用类型默认值 | Part.java:34,37 |
| `jointFreqHz` / `jointDampRatio` | `double` | Lua `part:setJointParams{}` 设置的 per-part 焊接参数覆写，NaN = 继承全局；焊接时 frequencyHz 更高（更硬）的一侧胜出 | Part.java:45-46 |
| `jointAngDamp` | `double` | per-part 角阻尼覆写 | Part.java:48 |
| `dragExposure` | `float` | 气动暴露度 0..1，由 `Ship.updateDragExposure` 射线遮蔽扫描重算，`GameWorld` 将其乘进阻力 | Part.java:56 |
| `group` | `int` | 激活组 0 = 无，1..8；ACTIVATE 触发整组 | Part.java:59 |
| `flameLevel` / `flameGimbalDeg` | `float` | 本帧火焰特效（Lua `emitFlame` 设置） | Part.java:62-63 |
| `gimbalDeg` | `float` | **实际** gimbal 偏转角（度），由引擎 Lua 的 PID 执行器驱向 `turnCommand*turnDeg`，推力使用此实际值——gimbal 滞后/超调是物理真实的；不入存档 | Part.java:72 |
| `angleOffset` | `float` | 设计旋转（弧度），= `design.rot * π/2` | Part.java:74 |

### 函数

| 签名 | 功能与要点 | 位置 |
|---|---|---|
| `Part(PartType type, Ship ship, ShipDesign.DesignPart design)` | 构造。创建 `ModApi`；若类型有油箱则加满燃料。**不创建 body**。 | Part.java:76 |
| `void createBody(float ox, float oy, float shipAngle)` | 在飞船局部坐标 `(ox,oy)` 创建 dynamic body。要点：① `bd.bullet = true` 开启 CCD，防止 500+ m/s 撞击时穿透静态地形块（注释称 round 11 item 7）；② 无自定义 shape 时按 `width×height` 生成矩形，有 shape 时每个 `ShapeDef` 截断为最多 8 顶点的 `PolygonShape`；③ fixture 摩擦取 `max(1.5, type.friction)` 保证着陆抓地（round 13 item 1a），restitution 恒为 0（无弹跳）；④ `isSensor = sd.sensor`；⑤ 结尾调 `updateMass()`。body 的 `userData` 指回本 Part。 | Part.java:85 |
| `void updateMass()` | 按定义重设质量：油箱件质量 = `dryMassTons*1000 + fuel`，其余 = `type.massKg()`；下限 0.05 kg。转动惯量按矩形近似 `m(w²+h²)/12`，质心固定在局部原点。燃料变化后必须调用（`setFuel` 内已调）。 | Part.java:140 |
| `double getFuel()` | 返回当前燃料。 | Part.java:155 |
| `double getFuelCapacity()` | 油箱容量，非油箱返回 0。 | Part.java:156 |
| `int getFuelType()` | 燃料类型（见 `PartType.FUEL_*`），非油箱返回 -1。 | Part.java:157 |
| `void setFuel(double v)` | 设置燃料并 clamp 到 `[0, 容量]`，随后 `updateMass()`（油箱质量随燃料变化）。 | Part.java:159 |
| `void detachJoints()` | 销毁本零件的所有连接。**延迟执行**：通过 `ship.world.deferStructure(...)` 排队到回调后处理——detacher 的 onStage 在遍历零件列表时触发，就地分裂飞船会破坏迭代器（注释 Part.java:164-168）。延迟任务里先校验 body/ship 仍有效再调 `ship.removeJointsOf(this)`。 | Part.java:169 |
| `void emitFlame(float size, float gimbalDeg)` | 记录本帧火焰特效：`flameLevel` 取最大值，`flameGimbalDeg` 直接覆盖。每帧由 `clearFrameFlags`/`Ship.updateScripts` 清零。 | Part.java:179 |
| `void callOnLoad()` | 若 `lua == null` 则 `ModManager.createState(type.id)` 创建独立 Lua 状态，再调 `onLoad` 钩子（第二参 0）。**必须在焊接前调用**（见 `Ship.buildFromDesign`）。 | Part.java:184 |
| `void callOnUpdate(double dt)` | 调 `onUpdate` 钩子，传 `dt`（秒）。lua 为 null 时静默跳过。 | Part.java:189 |
| `void callOnStage()` | 置 `stageActivatedThisFrame = true`，调 `onStage` 钩子。 | Part.java:193 |
| `void clearFrameFlags()` | 帧末清 `stageActivatedThisFrame` 与 `flameLevel`。 | Part.java:198 |
| `void destroyBody()` | 从 world 销毁 body 并置 null。 | Part.java:203 |
| `List<Vector2> attachWorldPositions()` | 返回所有 attach point 的世界坐标。要点：`body.getWorldPoint` 已含完整 body 变换（船体角 + 设计旋转），不可再手动乘 `angleOffset`，否则 90° 旋转件会双重旋转焊错位置（注释 round 11 item 3）；新版 gdx-box2d 返回共享 Vector2，必须拷贝。body 为 null 返回空表。 | Part.java:211 |
| `void attachWorldSegment(int index, Vector2 outA, Vector2 outB)` | 取第 index 个 attach point 的世界空间**线段**端点（边型 attach 的焊接锚点可在线段上滑动，见 `Attach`）。点型时 outA == outB。 | Part.java:227 |
| `List<AttachPoint> attachDefs()` | 返回 `type.attach`。 | Part.java:233 |

---

## PartType — 零件静态定义（PartList.xml 解析）

**职责**（`PartType.java:13-14`）：一种零件的静态定义，从 `PartList.xml` 解析。所有同类零件实例共享一个 PartType。包含尺寸、质量、碰撞形状、attach point 列表及引擎/油箱/RCS/太阳能板/着陆腿等子定义。

### 常量与内嵌类

- 燃料类型常量：`FUEL_LIQUID = 0`、`FUEL_MONO = 1`、`FUEL_ELECTRIC = 2`、`FUEL_SOLID = 3`（PartType.java:16-19）。
- `Vertex(float x, float y)`：形状顶点（PartType.java:21）。
- `ShapeDef { List<Vertex> verts; boolean sensor; }`：一个碰撞多边形；`sensor` 为 true 时不产生碰撞响应（PartType.java:26-29）。
- `AttachPoint`：连接点（PartType.java:31-49）。字段：
  - `x, y`：局部米坐标，y 向上；
  - `fuelLine`：是否燃料管路连接（决定燃料网络连通，见 `Ship.Link.fuelEdge`）；
  - `breakAngle`（默认 180）、`breakForce`（默认 `Float.MAX_VALUE` = 不可断）；
  - `group`、`flipX`、`order`（编辑器用）；
  - `edge`：边型 attach 标记。常量 `EDGE_NONE=0 / EDGE_LEFT=1 / EDGE_RIGHT=2 / EDGE_TOP=3 / EDGE_BOTTOM=4`（PartType.java:46-47）。`EDGE_*` 表示**整条边**可连接：配对零件可接触边上任意点，编辑器吸附沿边滑动、焊接锚在接触点；`EDGE_NONE` 为经典单点（TopCenter 等）。
- `EngineDef`（PartType.java:51-58）：`power`（推力 N = power × 1e6）、`consumption`（满推力每秒燃料单位）、`size`、`turnDeg`（gimbal 范围）、`throttleExponential`、`fuelType`。
- `TankDef`（PartType.java:60-64）：`fuel`（容量）、`dryMassTons`（干重）、`fuelType`。
- `RcsDef`（PartType.java:66-70）：`power`、`consumption`、`size`。
- `SolarDef`（PartType.java:72-74）：`chargeRate`。
- `LanderDef`（PartType.java:76-78）：`maxAngle, minLength, maxLength, angleSpeed, lengthSpeed, width`。

### 重要字段

| 字段 | 类型 | 说明 | 位置 |
|---|---|---|---|
| `id` / `name` / `sprite` / `type` | `String` | id 唯一键；`type` 语义化类别（"pod"/"detacher"/"parachute"/"lander"/"structural"…），`Ship.controlPart` 等靠它判断 | PartType.java:80-83 |
| `massTons` | `double` | 质量（吨） | PartType.java:84 |
| `width` / `height` | `float` | 尺寸（米），也是默认碰撞矩形与气动参考宽度的来源 | PartType.java:85 |
| `buoyancy` | `float` | 浮力参数（默认 0） | PartType.java:86 |
| `category` | `String` | 编辑器分类 | PartType.java:87 |
| `hidden` / `sandboxOnly` | `boolean` | 编辑器面板隐藏 / 仅沙盒 | PartType.java:88 |
| `ignoreEditorIntersections` / `disableEditorRotation` | `boolean` | 编辑器行为开关 | PartType.java:89 |
| `maxOccurrences` | `int` | 单船最大数量，-1 不限 | PartType.java:90 |
| `canExplode` | `boolean` | 是否可爆炸（默认 true） | PartType.java:91 |
| `friction` | `float` | 摩擦系数（默认 0.4；实际 fixture 在 `Part.createBody` 中被 floor 到 1.5） | PartType.java:92 |
| `drag` | `float` | 阻力调整值，整流罩用负值减阻 | PartType.java:93 |
| `coverHeight` | `float` | 整流罩遮盖高度 | PartType.java:94 |
| `engine` / `tank` / `rcs` / `solar` / `lander` | 各 Def | 子定义，null 表示无此功能 | PartType.java:96-100 |
| `shapes` / `attach` | `List`（final） | 碰撞形状与 attach point 列表 | PartType.java:101-102 |

### 函数

| 签名 | 功能与要点 | 位置 |
|---|---|---|
| `double massKg()` | `massTons * 1000`。 | PartType.java:104 |
| `boolean isEngine()` / `boolean isTank()` | 子定义非空判断。 | PartType.java:106-107 |
| `static Map<String, PartType> load(FileHandle file)` | 解析 PartList.xml → `LinkedHashMap<id, PartType>`（保序）。要点：① 剥 BOM；② 只识别 `<PartType>` 子元素；③ 子元素 `Engine/Tank/Rcs/Solar/Lander/Shape/AttachPoints` 分别填入对应字段；④ attach point 若有 `location` 属性走 `applyLocation` 命名定位，否则读显式 `x/y`；⑤ `breakForce` XML 默认 `Double.MAX_VALUE` 再转 float；⑥ 解析异常统一包装为 `RuntimeException("Failed to parse PartList.xml")`。 | PartType.java:109 |
| `private static void applyLocation(AttachPoint ap, String loc, float w, float h)` | 命名位置 → 局部坐标：`TopCenter/BottomCenter/LeftCenter/RightCenter` 为单点；`LeftSide/RightSide/Top/Bottom` 锚在边中心**并打上对应 `edge` 标记**使整边可滑动（round 11 item 5）；未知名称落到 (0,0)。 | PartType.java:212 |

---

## PartList — 零件类型全局注册表

**职责**（`PartList.java:10`）：`PartList.xml` 解析结果的全局静态注册表（`Map<id, PartType>`）。工具类，不可实例化。

### 函数

| 签名 | 功能与要点 | 位置 |
|---|---|---|
| `static void load()` | 调 `PartType.load(Res.asset("PartList.xml"))` 填充注册表；随后给所有 `type == "pod"` 且无油箱的类型**补一个 50 单位的小电池**（`FUEL_ELECTRIC`，干重 = `max(0.05, massTons-0.05)`）——指令舱属于全船电网（PartList.java:18-27）。 | PartList.java:16 |
| `static PartType get(String id)` | 按 id 查类型，不存在返回 null。 | PartList.java:30 |
| `static List<PartType> all()` | 全部类型的副本列表。 | PartList.java:32 |
| `static List<PartType> palette()` | 编辑器面板可见类型（`!hidden`）。 | PartList.java:35 |

---

## ShipDesign — 编辑器中的飞船设计（可序列化）

**职责**（`ShipDesign.java:8-12`）：建造编辑器里的一艘飞船：一串已放置零件（编辑器局部坐标，米，y 向上，90° 步进旋转）+ 阶段列表。用 JSON 存取。

### 内嵌类与字段

- `DesignPart`（ShipDesign.java:15-26）：`typeId`；`x, y` 编辑器位置（米）；`rot` 0..3（逆时针 90° 步数）；`group` 激活组 0..8。有无参构造（JSON 反序列化用）与四参构造。
- `parts`：`List<DesignPart>`（final），ShipDesign.java:28。
- `stages`：`List<List<Integer>>`（final）——阶段序号 → 该阶段点火的零件下标列表，ShipDesign.java:30。

### 函数

| 签名 | 功能与要点 | 位置 |
|---|---|---|
| `void clear()` | 清空零件与阶段。 | ShipDesign.java:32 |
| `void autoStage()` | 自动分级：stage 0 = 全部引擎，stage 1 = 全部 detacher，stage 2 = 降落伞 + 着陆腿；空类跳过；一个阶段都没有则补一个空 stage。 | ShipDesign.java:34 |
| `String toJson()` | 序列化。零件字段缩写为 `t/x/y/r/g`（`g` 仅当 group>0 写出）；`stages` 为下标数组的数组。 | ShipDesign.java:55 |
| `static ShipDesign fromJson(String json)` | 反序列化；零件缺省 `t = "pod-1"`；若 JSON 无 stages 则回退 `autoStage()`。 | ShipDesign.java:80 |

---

## Ship — 飞船（零件集合 + 连接图 + 燃料网络）

**职责**（`Ship.java:19-24`）：一艘火箭：一组由 spring-damper weld joint 连接的独立零件 body。拥有燃料网络、阶段列表与宇宙坐标系 origin。远离活跃飞船的不活跃船被置于 rails（body 停用、简单重力积分），但仍留在世界中、可切换。

### 重要字段

| 字段 | 类型 | 说明 | 位置 |
|---|---|---|---|
| `debugLastWeldHz` / `debugLastWeldDamp` | `static float` | 最近一次焊接解析出的参数（冒烟测试诊断） | Ship.java:28 |
| `debugLastWeldSource` | `static String` | `"lua"` = joints.lua 解析成功，`"fallback"` = 内置规则 | Ship.java:125 |
| `Link`（内嵌类） | — | 一条连接：`Joint joint; Part a, b; boolean fuelEdge; float breakForce`（默认不可断） | Ship.java:30-35 |
| `world` | `GameWorld`（final） | 所属世界 | Ship.java:37 |
| `parts` / `links` | `List`（final） | 零件与连接 | Ship.java:38-39 |
| `stages` / `currentStage` | — | 阶段列表（设计下标）与当前阶段 | Ship.java:40-41 |
| `origin` | `Vec2d`（final） | 本船局部坐标系原点的宇宙坐标（双精度米） | Ship.java:44 |
| `originVel` | `Vec2d`（final） | 局部坐标系的宇宙速度（活跃船为 0） | Ship.java:46 |
| `onRails` / `landed` | `boolean` | 轨道跟随状态 / 着陆状态 | Ship.java:47-48 |
| `name` | `String` | 默认 `"Ship-<id>"` | Ship.java:49 |
| `id` / `nextId` | `int`（private/static） | 自增实例编号 | Ship.java:52-53 |

### 构建与连接

| 签名 | 功能与要点 | 位置 |
|---|---|---|
| `Ship(GameWorld world)` | 构造，分配自增 id 并生成默认名。 | Ship.java:55 |
| `void buildFromDesign(ShipDesign d, float spawnAngle)` | 按设计实例化：每个 `DesignPart` 的设计坐标按 spawnAngle 旋转后 `createBody`；复制 stages；**先对所有零件 `callOnLoad()` 再 `connectAttachPoints()`**——零件脚本在 onLoad 里设置 per-part 关节覆写（setJointParams），焊接必须基于这些覆写而非全局默认（round 9 item 1）。 | Ship.java:64 |
| `private void connectAttachPoints()` | O(n²) 扫描所有零件对的所有 attach 线段对，用 `Attach.closestBetweenSegments` 取最近距离；最近的一对若 < 0.35 m 阈值则焊接，锚点取两接触点中点。边型 attach 使旋转件即使只有边中心以外处贴合也能焊上（round 11 item 5）。 | Ship.java:86 |
| `private void weld(Part a, Part b, Vector2 worldAnchor, AttachPoint apA, AttachPoint apB)` | 创建 `WeldJointDef`。解析顺序：① 系统属性 `dr.nojoints` 为真则直接返回（debug 开关）；② `JointScript.resolve(...)`（mod/joints.lua）成功则采用脚本参数，`breakForce` 取脚本值与两端 attach 定义的较小者，记 `debugLastWeldSource="lua"`；③ 否则 fallback：per-part 覆写中 **frequencyHz 更高者胜出**（更硬的一侧主导连接），其 dampingRatio 随附，再缺省读 `PhysicsScript.jointParam(...)`；④ `collideConnected = false`；⑤ 建 `Link`，`fuelEdge = apA.fuelLine && apB.fuelLine`（两端都是燃料管路才通油）。 | Ship.java:127 |
| `void removeJointsOf(Part p)` | 销毁涉及 p 的所有 Link，随后 `splitIfDisconnected()`。 | Ship.java:173 |
| `private void destroyLink(Link l)` | 销毁 joint（置 null）并从 `links` 移除。 | Ship.java:182 |
| `void checkJointBreaks(float invDt)` | 每帧检查：`joint.getReactionForce(invDt)` 长度换算 kN，超过 `breakForce` 的 Link 销毁；有断裂则尝试分裂。 | Ship.java:191 |
| `void splitIfDisconnected()` | 连接图 BFS 求连通分量；≥2 个分量时：主分量 = 含 pod 者（pod 加 10000 分），否则最大分量。每个非主分量生成新 Ship（继承 origin/originVel），迁移零件与 Link，`currentStage` 置 `Integer.MAX_VALUE/2` 使碎片船**不能再点火分级**（原下标已失效），并 `world.addShip(ns)`。主船侧把未点火阶段中下标仍 < `parts.size()` 的项保留并重排，`currentStage` 归零。注意：注释明确说分裂后不再追踪原设计下标，这是" safest "的折中（Ship.java:259-275）。 | Ship.java:204 |

### 燃料网络

| 签名 | 功能与要点 | 位置 |
|---|---|---|
| `private List<Part> fuelComponent(Part from, int fuelType)` | 从 `from` 出发、只沿 `fuelEdge` Link 做 DFS，返回该燃料管路连通分量中所有油箱类型匹配 `fuelType` 的零件。 | Ship.java:281 |
| `private List<Part> tanksOf(int fuelType)` | 全船该类型油箱列表。 | Ship.java:302 |
| `double fuelTotal(int fuelType)` / `double fuelCapacity(int fuelType)` | 全船该类型当前量/总容量。 | Ship.java:310,316 |
| `private List<Part> drainScope(Part consumer, int fuelType)` | 消费者允许抽油的范围（注释 Ship.java:322-332）：**solid(3)** 仅自身油箱（SRB 内烧）；**electric(2)** 全船电网（电池无燃料管）；**mono(1)** 全船（RCS 从任意 mono 箱啜取，对齐 KSP 规则，round 11 item 4）；**liquid(0)** 与 consumer 直接相连（任意 Link）的油箱各自的 fuelLine 网络，加上 consumer 自身（若它是油箱）的网络——隔着无 fuelLine 连接点的零件（pod/detacher/电池）才够到的油箱被隔离。 | Ship.java:333 |
| `double drainFuel(Part consumer, int fuelType, double amount)` | 在 `drainScope` 内按比例均匀抽油；返回实际抽到的量（≤ amount）。 | Ship.java:362 |
| `double drainFuel(int fuelType, double amount)` | 全船该类型油箱均匀抽油的重载。 | Ship.java:374 |
| `double transferFuel(Part part, int fuelType, double amount)` | 油箱间传输：amount>0 从 `part` 抽出按剩余空间比例分摊给同 scope 其他油箱；<0 反向（按油量比例抽入）。scope 规则同 drainScope（electric/mono 全船，liquid 仅本 fuelLine 分量，solid 不传输）。返回实际传输量（带符号）。 | Ship.java:386 |
| `double addFuel(int fuelType, double amount)` | 向全船该类型油箱按剩余空间比例加油；返回实际加入量。 | Ship.java:419 |

### 气动遮蔽（round 11 item 2）

| 签名 | 功能与要点 | 位置 |
|---|---|---|
| `private final class OcclusionQuery implements RayCastCallback` | 射线回调：命中**本船其他零件**（userData 是 Part、非 self、同 ship）则置 `blocked` 并返回 0 终止射线；命中其他船/地面返回 -1 忽略。 | Ship.java:438-450 |
| `void updateDragExposure(float rvx, float rvy, float time)` | 重算每个零件的 `dragExposure`(0..1)：沿垂直于来流方向在零件剪影上布 8 个采样点，向上风方向投射线，未被本船其他零件遮挡的采样比例即暴露度。缓存约 15 Hz；来流方向变化 > ~8°（sin 阈值 0.1392）或零件数变化时提前重算。来流速度² < 1 视为无气流、全部置 1。射线长度取船上最远上风 extent + 30 m 余量。**必须在 `boxWorld.step` 之前调用**（world 锁定时禁止 raycast）。参数 `rvx/rvy` 为相对气流速度。 | Ship.java:461 |

### 阶段与脚本

| 签名 | 功能与要点 | 位置 |
|---|---|---|
| `int activateStage()` | 点火下一级，返回点火的阶段下标，无更多阶段返回 -1。**先把下标快照成 Part 引用再逐个 `callOnStage()`**——detacher 的 onStage 会延迟销毁关节并分裂飞船、在返回后改变 `parts`，先解析引用保证每个成员可达；最后 `world.processDeferredStructure()` 冲刷延迟队列，`currentStage++`。 | Ship.java:513 |
| `Part partAt(int designIndex)` | 设计下标 → 运行时 Part（同序但可能已过滤），越界返回 null。 | Ship.java:532 |
| `void updateScripts(double dt)` | 帧脚本驱动：先清所有 `flameLevel`，再逐个 `callOnUpdate(dt)`，最后清 `stageActivatedThisFrame`（阶段标志保留到帧末供 Lua onUpdate 读）。 | Ship.java:538 |

### 运动学与坐标

| 签名 | 功能与要点 | 位置 |
|---|---|---|
| `Vector2 centerOfMass(Vector2 out)` | 质量加权质心（局部坐标）。空船返回 (0,0)。 | Ship.java:545 |
| `Vector2 velocity(Vector2 out)` | 质量加权平均速度（局部坐标）。 | Ship.java:560 |
| `Vec2d getUniverseVel()` | 宇宙速度 = `world.frameVel + originVel + 局部速度`。 | Ship.java:575 |
| `Vec2d getUniversePos()` | 宇宙位置 = `origin + 局部质心`。 | Ship.java:580 |
| `void setBodiesActive(boolean active)` | 批量启停所有 body（rails 休眠/唤醒用）。 | Ship.java:585 |
| `Part controlPart()` | 控制输入/航向参考件：第一个 pod，否则第一个零件，空船 null。 | Ship.java:592 |
| `void integrateRails(double dt)` | 不活跃远船的 rails 积分（body 休眠，只动 origin）。要点：① **贴地停泊保持**——距地表 < 50 m 且相对行星速度 < 1 m/s 时，origin 跟随行星速度平移并把 originVel 钉到行星速度（round 13/14：防止重力沉穿地形，且行星自己在轨道上走、停泊船必须跟着走）；② 否则 `world.gravityAt` 重力半隐式欧拉积分；③ **硬地板**——积分后若低于地形面（`np.radius + np.heightAt(ang) + 0.5`）则沿径向推出并消掉向内的径向速度分量，防止远处弹道级坠入时穿行星。 | Ship.java:598 |
| `void shiftBodies(double dx, double dy)` | 浮动原点平移：所有 body 平移 `(dx,dy)`，`origin` 反向修正（角度不变）。 | Ship.java:644 |
| `void destroy()` | 先销毁全部 Link（Box2D 在 body 销毁时会自动销毁附着 joint，故必须先手工拆 joint），再销毁所有 body 并清空 `parts`。 | Ship.java:655 |

### 存档

| 签名 | 功能与要点 | 位置 |
|---|---|---|
| `String toJson()` | 序列化：name/origin/originVel/currentStage；每个零件写 `t`、body 的 `x/y/a/vx/vy/va`、`fuel`、`dep`（deployed）、`grp`（>0 时）；links 存为零件下标对 `{a,b}`；stages 原样。 | Ship.java:664 |
| `static Ship fromJson(GameWorld world, Json.JObj o)` | 重建：零件按存档位置/角度/速度 `createBody`（`DesignPart` 用 rot=0 占位，角度直接来自存档）；燃料/deployed/group 恢复后 `updateMass()`；**先 `callOnLoad()` 再按 links 重焊**（round 9 item 1 同一理由）；焊接锚点用 `bestAnchor`（两船最近 attach point 对的中点），attach 定义用 `nearestAttach` 找最近的。缺省零件类型 `fuselage-1`。 | Ship.java:716 |
| `private static Vector2 bestAnchor(Part a, Part b)` | 两零件所有 attach 世界位置中最近一对的中点；无 attach 时返回 null。 | Ship.java:772 |
| `private static PartType.AttachPoint nearestAttach(Part p, Vector2 world)` | 离世界点最近的 attach 定义；零件无 attach 时返回一个新建的默认 `AttachPoint`（避免 NPE）。 | Ship.java:784 |

---

## Attach — attach point 几何工具

**职责**（`Attach.java:5-12`）：编辑器吸附与 `Ship` 焊接共用的 attach point 几何（round 11）。边型 attach point（LeftSide/RightSide/Top/Bottom）表示整条边可连接：配对件可接触边上任意点；Center 位置仍是经典单点。接触解析规则：点↔点 = 两点本身；点↔边 = 点及其在线段上的投影；边↔边 = 两线段最近点对。工具类，不可实例化。

### 常量与静态缓存

- `EDGE_SNAP_STEP = 0.25f`（Attach.java:22）：编辑器中边型吸附的滑动量化步长（米）；点型接触从不动量化。
- `private static final Vector2 tA, tB, c2`（Attach.java:64-65）：`closestBetweenSegments` 复用的临时向量（非线程安全，游戏单线程使用）。

### 函数

| 签名 | 功能与要点 | 位置 |
|---|---|---|
| `static Vector2 quantizeAlongSegment(float px, float py, Vector2 a, Vector2 b, Vector2 out)` | 把吸附位置 `(px,py)` 沿线段 `(a,b)` 的自由滑动分量量化到 `EDGE_SNAP_STEP` 的最近整数倍；垂直接触分量不变。退化线段（点型）原样返回。写入 `out` 并返回，`out` 可与 `a`/`b` 别名。 | Attach.java:31 |
| `static void localSegment(PartType t, PartType.AttachPoint ap, Vector2 outA, Vector2 outB)` | 取 attach point 的局部空间线段端点：四种 `EDGE_*` 返回对应整条边（用零件 width/height），点型返回退化线段（outA == outB == (ap.x, ap.y)）。 | Attach.java:43 |
| `static Vector2 closestOnSegment(Vector2 p, Vector2 a, Vector2 b, Vector2 out)` | 点 p 在线段 (a,b) 上的最近点（投影参数 clamp 到 [0,1]）；退化线段返回 a。写入 `out` 并返回。 | Attach.java:55 |
| `static float closestBetweenSegments(Vector2 a1, Vector2 a2, Vector2 b1, Vector2 b2, Vector2 outA, Vector2 outB)` | 两线段最近点对：枚举四个端点对另一线段的投影取最小；若两线段**真交叉**（`segmentsCross`），距离为 0，锚点取四端点均值。写入 outA/outB，返回距离。 | Attach.java:71 |
| `private static float cross(float ox, ..., float by)` | 2D 叉积辅助（OA × OB）。 | Attach.java:94 |
| `private static boolean segmentsCross(Vector2 a1, Vector2 a2, Vector2 b1, Vector2 b2)` | 严格跨立相交判定（两侧叉积异号），共线/端点接触不算交叉。 | Attach.java:98 |

---

## Planet — 天体（Kepler 轨道 + 地形 + 大气）

**职责**（`Planet.java:12-15`）：一个天体，数据来自 `SmolarSystem.xml`（或经 `PlanetDefs` 来自 Lua）。天体全部位于相对父体的 Kepler 轨道上（rails），位置/速度由解析公式直接给出。同时携带地形高度生成器（内置 value noise + 可被 mod/terrain.lua 覆写）与指数大气模型。

### 重要字段

| 字段 | 类型 | 说明 | 位置 |
|---|---|---|---|
| `name` | `String` | 名称（地形噪声种子也用它：`name.hashCode()`） | Planet.java:17 |
| `gravity` / `radius` | `double` | 地表重力 m/s²、名义半径 m | Planet.java:18-19 |
| `mapColor` / `icon` / `description` | — | Map 视图显示 | Planet.java:20-23 |
| `launchEnabled` | `boolean` | 是否可作为发射场 | Planet.java:22 |
| `a, e, w, v0` | `double` | 轨道根数（相对父体）：半长轴、偏心率、近点幅角、初始平近点角相关相位 | Planet.java:26 |
| `prograde` | `boolean` | 顺行/逆行 | Planet.java:27 |
| `maxHeight` / `minHeight` / `noise` | `double` | 地形高度范围与粗糙度 | Planet.java:30-31 |
| `crustTexture` / `crustColor` | — | 地壳贴图与颜色 | Planet.java:32-33 |
| `waterDensity` | `double` | 海水密度，0 = 无海洋（浮力判定用） | Planet.java:34 |
| `Range`（内嵌类）/ `ranges` | — | 分段地形区间：`startDeg/endDeg/minH/maxH`，不同经度段可有不同高度范围 | Planet.java:36-39 |
| `atmoHeight` / `surfacePressure` | `double` | 大气顶高 m、地表压强（1.0 = Smearth 海平面） | Planet.java:42-43 |
| `parent` / `children` | `Planet` / `List` | 轨道层级（父体为 null 的是根/Sun） | Planet.java:45-46 |
| `pos` / `vel` | `Vec2d`（final） | 运行时宇宙位置/速度，由 `updateRails` 推导 | Planet.java:49-50 |

### 大气与轨道

| 签名 | 功能与要点 | 位置 |
|---|---|---|
| `double mu()` | 引力参数 = `gravity * radius²`。 | Planet.java:52 |
| `boolean hasAtmosphere()` | `atmoHeight > 0 && surfacePressure > 0`。 | Planet.java:54 |
| `double scaleHeight()` | 标高 = `atmoHeight / 7`。 | Planet.java:55 |
| `double densityAt(double h)` | 高度 h 处大气密度 kg/m³（指数模型）：`1.225 * surfacePressure * exp(-max(h,0)/scaleHeight)`；无大气、`h > atmoHeight` 或低于 `-3*scaleHeight` 返回 0。 | Planet.java:58 |
| `double pressureAt(double h)` | 高度 h 处压强（地表压强为单位），同样指数模型。 | Planet.java:65 |
| `private double solveKepler(double M)` | Newton 法解 Kepler 方程（12 次迭代）。round 14 修复：先把 M wrap 到 [-π, π] 并用 `E = M + e·sin(M)` 作初值——长时间会话/高 warp 下大 M 加高偏心率会让 E=M 初值发散，折叠轨道与预测轨迹。 | Planet.java:72 |
| `private void localPosVel(double t, Vec2d outPos, Vec2d outVel)` | t 时刻相对父体的位置/速度（父体系）：平近点角 `M = n·t + v0`（逆行取负）→ 偏近点角 E → 轨道面坐标 → 按近点幅角 w 旋转。父体为 null 输出零。 | Planet.java:87 |
| `void updateRails(double t)` | 用 `localPosVel` 算出本体系数后**叠加父体 pos/vel**（父体也需先更新；根天体钉在宇宙原点），随后递归更新所有 children。调用方需保证父先子后（本方法自身递归已保证）。 | Planet.java:113 |

### 地形高度

| 签名 | 功能与要点 | 位置 |
|---|---|---|
| `double heightAt(double angleRad)` | 世界系角度（弧度）处相对名义半径的地形高度。优先级（round 18 修复）：① `TerrainScript.heightAboveDatum`（玩家 mod/terrain.lua 的 surfaceHeight，含 specialTerrains，与碰撞/渲染列一致）；② `TerrainScript.heightAt`（旧版玩家脚本的 terrainHeight，无特殊区域）；③ `builtinHeightAt` 内置生成器。 | Planet.java:128 |
| `double builtinHeightAt(double angleRad)` | 内置生成器（同时被 mods/terrain.lua 镜像）：角度归一到 [0,360) → 命中 `ranges` 区间则替换上下限 → 4 个倍频的确定性 value noise（种子 `name.hashCode()`，基频 `max(2, 6+noise*0.6)`）→ 归一 [0,1] 后用 `pow(n01, min(2.5, 0.25+noise*0.28))` 塑形 → 映射到 [lo, hi]。 | Planet.java:142 |
| `private static double norm(double d)` | 角度归一到 [0,360)。 | Planet.java:169 |
| `private static double hash(double i, double seed)` | 正弦哈希伪随机 [0,1)。 | Planet.java:171 |
| `private static double valueNoise(double x, double period, double seed)` | 无缝 1D value noise ∈ [-1,1]：晶格按整数 period 回绕（保证 0°/360° 接缝连续），smoothstep 插值。 | Planet.java:177 |
| `private static double lerp(...)` | 线性插值。 | Planet.java:186 |

### 解析与查找

| 签名 | 功能与要点 | 位置 |
|---|---|---|
| `static Planet loadSolarSystem(FileHandle file)` | 解析 SmolarSystem.xml：剥 BOM，取第一个 `<Planet>` 为根（Sun），递归 `parsePlanet`。失败抛 `RuntimeException`。 | Planet.java:190 |
| `private static Planet parsePlanet(XmlReader.Element e, Planet parent)` | 递归解析：基本属性 + `<Orbit>`(a/e/w/v/prograde) + `<Terrain>`（含 `<Ranges>` 分段）+ `<Atmosphere>` + `<Children>` 递归。 | Planet.java:204 |
| `private static Color parseColor(String s)` | `"r,g,b"`(0-255) 字符串 → Color；解析失败回退灰色。 | Planet.java:259 |
| `void flatten(List<Planet> out)` | 父先序展平成列表。 | Planet.java:271 |
| `Planet findLaunchable(String name)` | 递归按名（忽略大小写）找 `launchEnabled` 的天体，找不到返回 null。 | Planet.java:276 |

---

## PlanetDefs — Lua 行星定义加载器

**职责**（`PlanetDefs.java:21-40`）：从玩家可编辑的 Lua 读取行星定义。查找顺序：资源根下 `mod/planets.lua` 与 `mod/planets/*.lua`（玩家 mod 优先，二者可共存、后者覆盖同名定义）；都没有则退回内置 `mods/planets.lua`；再失败/为空则回退 `SmolarSystem.xml`。Lua API 为 `definePlanet{ name=..., parent=..., gravity=..., radius=..., orbit={a,e,w,v,prograde}, atmosphere={height,surfacePressure}, terrain={maxHeight,minHeight,noise,texture,color,waterDensity,ranges={...}} }`。radius + terrain 同时驱动渲染的地壳块与碰撞高度场。工具类，不可实例化。

### 函数

| 签名 | 功能与要点 | 位置 |
|---|---|---|
| `static Planet load()` | 入口。收集脚本文件 → `runScripts`；Lua 路线失败则记 error 日志并回退 `Planet.loadSolarSystem(Res.asset("SmolarSystem.xml"))`。返回根天体。 | PlanetDefs.java:50 |
| `private static Planet runScripts(List<FileHandle> scripts, String sourceLabel)` | 建独立 `JsePlatform.standardGlobals()`，注入 `definePlanet`（OneArgFunction）：解析表 → 按名去重（后定义覆盖先定义，保证 planets/*.lua 覆盖 planets.lua）→ 记录声明顺序。逐个 `g.load(src, name).call()` 执行；任何 `LuaError` 记日志返回 null（触发 XML 回退）。随后按 `parent` 名链接父子，无 parent 的第一个定义为根；无根记 error 返回 null。 | PlanetDefs.java:77 |
| `private static Def parse(LuaTable t)` | Lua 表 → `Def`（含半成品 `Planet`）。字段映射与 XML 版一致；`orbit/atmosphere/terrain/ranges` 均要求 table 才解析；`mapColor`/`terrain.color` 支持 `{r,g,b}` 表或 `"r,g,b"` 字符串。 | PlanetDefs.java:133 |
| `private static double num / String str / boolean bool(LuaValue t, String key, 缺省)` | 带类型检查的取值 helper（类型不符返回缺省）。 | PlanetDefs.java:183,188,193 |
| `private static Color color(LuaValue v, Color def)` | `{r,g,b}` 表或 `"r,g,b"` 字符串 → Color；异常回退缺省。 | PlanetDefs.java:199 |

---

## SteeringIO — 共享转向输入状态

**职责**（`SteeringIO.java:2`）：沙盒 UI 写入、steering/引擎控制读取的共享输入状态。仅 8 行，纯静态字段。

| 成员 | 说明 | 位置 |
|---|---|---|
| `static volatile boolean ringActive` | 转向环是否激活（volatile：UI 线程写、逻辑线程读） | SteeringIO.java:4 |
| `static volatile int buttonTurn` | 按住的方向按钮：-1 左 / 0 无 / +1 右 | SteeringIO.java:5 |
| `static volatile double targetHeadingRad` | 目标航向（弧度） | SteeringIO.java:6 |
| `static boolean hasTarget()` | 是否有目标航向（= `ringActive`） | SteeringIO.java:7 |

---

## 关键协作流程速查

1. **从设计到飞船**：`ShipDesign`（编辑器数据）→ `Ship.buildFromDesign`（旋转 spawnAngle、`createBody`、先 `callOnLoad` 后焊接）→ `connectAttachPoints`（0.35 m 阈值 + 边型 attach 最近线段对）→ `weld`（joints.lua 优先，per-part 覆写 frequencyHz 高者胜出的 fallback）。
2. **分离/爆炸**：Lua `onStage` → `Part.detachJoints`（**延迟**到 `deferStructure` 队列）→ `Ship.removeJointsOf` → `splitIfDisconnected`（BFS 连通分量，pod 所在为主船，碎片船禁止再分级）。
3. **每帧**：`Ship.updateScripts`（清火焰 → onUpdate → 清阶段标志）→ `updateDragExposure`（**step 前**，射线遮蔽）→ `boxWorld.step` → `checkJointBreaks`（反作用力 kN > breakForce 断 joint）。
4. **燃料**：引擎脚本 → `Ship.drainFuel(consumer, type, amount)` → `drainScope` 决定可见油箱（solid 自身 / electric+mono 全船 / liquid 仅 fuelLine 网络）→ 按比例均匀抽取 + `setFuel` 联动质量更新。
5. **天体**：`PlanetDefs.load`（Lua 优先，XML 兜底）→ 每帧 `root.updateRails(t)` 解析推出全部天体宇宙坐标 → 地形高度一律走 `Planet.heightAt`（玩家 terrain.lua 优先，内置 noise 兜底）。
