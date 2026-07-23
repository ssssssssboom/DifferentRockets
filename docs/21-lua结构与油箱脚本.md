# 21 · Lua 零件脚本：油箱、结构件、鼻锥、轮子与着陆腿

> 目标读者：刚接手 DifferentRockets 的开发者。
> 本文精读 `game/core/assets/mods/` 下 12 个零件脚本：`fueltank-0..5.lua`、`fuselage-1.lua`、`nosecone-1.lua`、`strut-1.lua`、`lander-1.lua`、`wheel-1.lua`、`wheel-2.lua`。
> 所有引用格式为 `文件名:行号`。除非特别说明，Java 文件均位于 `game/core/src/com/differentrockets/`，XML 指 `game/core/assets/PartList.xml`。

---

## 1. 零件 = XML 静态参数 + Lua 实例行为

每个零件由两半组成：

1. **静态定义**：`PartList.xml` 中的 `<PartType>` 标签（尺寸、质量、连接点、形状、伤害等），由 `game/PartType.java:109-210` 解析成 `PartType` 对象。
2. **实例行为**：`mods/<id>.lua`。每个零件**实例**拥有独立的 lua 状态（`game/Part.java:184-187`，`ModManager.createState(type.id)` 每实例各建一份），有三个钩子（`game/Part.java:184-201`）：

| 钩子 | 调用时机 | 签名 |
|---|---|---|
| `onLoad(part)` | 零件实例创建时（此时 body 可能尚未创建） | `Part.java:184-187` |
| `onUpdate(part, dt)` | 每个物理帧 | `Part.java:189-191` |
| `onStage(part)` | 该零件所属激活组被触发的那一帧 | `Part.java:193-196` |

`part` 参数是 `game/ModApi.java` 暴露的 Java 对象（`ModApi.java:12-15`），文件头注释说明坐标系为物理系（米，y 向上，相对于活动原点）。

这些内置脚本会在首次运行时拷贝到 `/sdcard/DifferentRocket/mod` 供玩家修改（同步清单见 `util/Res.java:107-108`，`wheel-1.lua`/`wheel-2.lua` 均在列）。

**本文 12 个脚本都很短，大量"零件行为"其实不在 lua 里，而在 XML 属性和 Java 引擎里。** 每个零件下面分「XML 参数」「Lua 逐函数」「引擎侧行为」三块讲。

---

## 2. 油箱系列：fueltank-0 ~ fueltank-5

### 2.1 六个脚本内容完全相同

`fueltank-0.lua` 到 `fueltank-5.lua` 每个都是 23 行，且**逐字节同内容**（版本标记 `v2026.07.21`）。油箱之间的差异全部在 `PartList.xml` 里，脚本只做一件事：**与同类型燃料的网络缓慢均压**。

### 2.2 XML 参数对照表

| id | 名称 | XML 行号 | 尺寸(宽×高, m) | XML mass(t) | 燃料容量 | 干重(t) | fuelType | 连接点 |
|---|---|---|---|---|---|---|---|---|
| fueltank-0 | Sloshy T750 | `PartList.xml:55-64` | 4×2 | 1.85 | 750 | 0.35 | 0（液体，默认） | 上/下/左/右 4 点，全部 `fuelLine="true"` |
| fueltank-1 | Sloshy T1500 | `PartList.xml:65-74` | 4×4 | 3.5 | 1500 | 0.5 | 0 | 同上 |
| fueltank-2 | Sloshy T3000 | `PartList.xml:75-84` | 4×8 | 6.85 | 3000 | 0.85 | 0 | 同上 |
| fueltank-3 | Sloshy T6000 | `PartList.xml:85-94` | 4×16 | 13.2 | 6000 | 1.2 | 0 | 同上 |
| fueltank-4 | Puffy T750 | `PartList.xml:96-105` | 4×2 | 1.85 | 750 | 0.35 | **1（单组元/RCS）** | 4 点，**均无 fuelLine** |
| fueltank-5 | Puffy T275 | `PartList.xml:107-114` | 1×3 | 0.65 | 275 | 0.13 | **1** | `RightCenter`（`flipX="true"`）+ `LeftCenter`，两点同 `group="1"` |

补充事实：

- 燃料类型常量：`PartType.java:16-19` —— 0=FUEL_LIQUID、1=FUEL_MONO、2=FUEL_ELECTRIC、3=FUEL_SOLID。`Tank` 标签缺省为液体（`PartType.java:150-155`，`fuelType` 缺省 `FUEL_LIQUID`）。
- 六个油箱都带 `<Damage disconnect="2500" explode="2500" explosionPower="5" explosionSize="10" />`（如 `PartList.xml:57`）。
- fueltank-4/5 有 `category="Satellite"`（`PartList.xml:96,107`），在编辑器里归入 Satellite 分类。
- fueltank-5 是 1 m 宽的侧挂小罐：两个连接点都在侧中线上，`group="1"` 表示它们属于同一侧挂组，`flipX` 用于贴图翻转（解析见 `PartType.java:194-199`）。
- **XML 的 `mass` 属性对油箱在运行时无效**：`Part.updateMass()` 对油箱直接用 `干重×1000 + 当前燃料量`（kg）覆盖质量（`game/Part.java:140-153`，油箱分支在 143-145 行）。即燃料 1 单位 ≈ 1 kg。例如 fueltank-0 满油运行质量 = 350 + 750 = 1100 kg，而不是 XML 写的 1.85 t（XML mass 只用于非油箱零件和编辑器展示）。燃料变化时质量实时变化（`Part.setFuel` 里调用 `updateMass`，`Part.java:159-162`），这也是燃烧中质心/转动惯量漂移的来源。

### 2.3 连接点与燃油管路（fuelLine）

- 连接点属性解析：`PartType.java:194-199`（`fuelLine`、`breakAngle`、`breakForce`、`group`、`flipX`）。
- 位置关键字：`TopCenter/BottomCenter/LeftCenter/RightCenter` 是单点；`LeftSide/RightSide/Top/Bottom` 是**整条边**可连接（`PartType.java:212-226`、`game/Attach.java:5-12`）。油箱的上下是单点、左右是整边。
- 焊接时，只有两个连接点**都**带 `fuelLine` 才会形成燃油管路边（`game/Ship.java:168`：`l.fuelEdge = apA.fuelLine && apB.fuelLine`）。
- 液体燃料（type 0）的供给范围 = 经 `fuelEdge` 连通的油箱组件（`Ship.fuelComponent`，`Ship.java:281-300`）；被没有 fuelLine 连接点的零件（pod、detacher、电池）隔开的油箱是**隔离**的，不会给你供油（`ModApi.java:145-152` 的 drainFuel 注释、`Ship.drainScope` `Ship.java:333-354`）。
- 单组元（type 1）和电（type 2）是**全船共享**的，不需要 fuelLine（`Ship.java:341-342`）——所以 fueltank-4/5 的连接点不带 fuelLine 是正确设计，不是遗漏。固体（type 3）只烧自己（`Ship.java:334-340`）。

### 2.4 油箱脚本逐函数说明（以 fueltank-0.lua 为例，其余五个相同）

```lua
function onLoad(part)        -- fueltank-0.lua:4-5
end                          -- 空：油箱无初始化逻辑

function onStage(part)       -- fueltank-0.lua:7-8
end                          -- 空：油箱不响应分级触发

function onUpdate(part, dt)  -- fueltank-0.lua:10-23
  local ft = part:getFuelType()          -- 本罐燃料类型；非油箱返回 -1
  if ft < 0 then return end
  local cap = part:getFuelMax()          -- 本罐容量
  local totalCap = part:getFuelCapacity(ft)  -- 全船该类型总容量
  if cap <= 0 or totalCap <= 0 then return end
  local myShare = part:getFuel() / cap        -- 本罐充满度 0..1
  local netShare = part:getFuelTotal(ft) / totalCap  -- 全网充满度 0..1
  local diff = myShare - netShare
  if math.abs(diff) > 0.002 then         -- 死区：0.2% 以内不折腾
    part:transferFuel(ft, diff * cap * math.min(1, dt * 2))
  end
end
```

逻辑要点：

- 这是**比例均压**：比较"本罐充满度"与"全网充满度"，每帧把差值的一部分（速率 `dt*2`，封顶 1，即时间常数约 0.5 s）通过 `transferFuel` 转移。正值 = 本罐偏满 → 往外给；负值 = 往里抽。
- 涉及的 ModApi 方法：`getFuelType`/`getFuelMax`/`getFuel`（`ModApi.java:158-160`）、`getFuelTotal`/`getFuelCapacity`（`ModApi.java:142-143`）、`transferFuel`（`ModApi.java:155`）。
- **一个容易踩的坑**：`getFuelTotal(ft)`/`getFuelCapacity(ft)` 统计的是**全船**所有该类型油箱（`Ship.fuelTotal`/`fuelCapacity`，`Ship.java:310-320`，遍历 `tanksOf`），而 `transferFuel` 对液体只在**本罐的 fuelLine 连通组件**内搬油（`Ship.transferFuel`，`Ship.java:386-416`，391-392 行选择范围）。所以一个被隔离的液体罐会拿"全船充满度"当目标反复尝试转移，但 `others` 为空直接返回 0（`Ship.java:393-394`）——无害，但理解数据时要知道分母口径不一致。
- `transferFuel` 内部按"接收方剩余空间比例"或"供给方现有油量比例"分摊，并做容量钳制（`Ship.java:395-415`）；`setFuel` 自身也有 0..容量 的钳制（`Part.java:159-161`）。

### 2.5 油量可视化接口

油箱的油量显示**不在 lua 里**，lua 只需维护 `part.fuel`（经 `setFuel`/`transferFuel`/`drainFuel`），渲染由 Java 完成：

1. **罐体液位条**（round 9 item 5）：`ui/SandboxScreen.java:1140-1171` `drawTankLevels()`。
   - 只画油箱（`getFuelCapacity() > 0`）且在屏幕上至少约 24 px 高时（`:1149-1151`），避免缩小后满屏色块。
   - 在罐体中央画一条宽 16% 的竖向细条（`:1155`），随 body 角度旋转；液面以下是亮色、以上是半透明黑色（`:1160-1167`）。
   - 颜色按燃料类型（`:1164-1167`）：电（ft==2）黄绿、固体（ft==3）橙、**其余（含液体 ft==0 和单组元 ft==1）都是青色**。
   - 数据源就是 `p.getFuel() / p.getFuelCapacity()`（`:1152`）——lua 改了油量，下一帧液位条自动跟上，**不需要任何额外接口调用**。
2. **选中读数**：点选一个油箱/固推/电池时，分级栏显示数值 `FUEL/MONO... x / max`（`SandboxScreen.java:1786-1797`）；顶栏还有全船 FUEL/MONO/BATT 汇总（`:1781`）。
3. lua 侧可读接口：`getFuel()`、`getFuelMax()`、`getFuelType()`（`ModApi.java:158-160`），以及全网统计 `getFuelTotal(ft)`/`getFuelCapacity(ft)`（`ModApi.java:142-143`）。

---

## 3. fuselage-1（Fuselage，结构隔框）

- XML：`PartList.xml:38-45`。4×4 m、1.25 t、`type="fuselage"`、`buoyancy="1.0"`（能浮）。
- 连接点 4 个：上下 `TopCenter/BottomCenter`（**带 fuelLine**——隔框可以过油），左右 `LeftSide/RightSide`（整边，不带 fuelLine）。
- Lua（`fuselage-1.lua:1-10`，10 行）：三个钩子**全空**，注释自述 "Structural part: no active behavior."。
- 引擎侧：无特殊代码路径；它就是一个带 fuelLine 连接点的轻质方框，用于拉开间距和传油。

## 4. nosecone-1（Nose Cone，鼻锥）

- XML：`PartList.xml:203-213`。4×2 m、0.05 t（极轻）、`type="nosecone"`、**`drag="-1.0"`**。
- **自定义碰撞形状**：不是矩形，而是梯形四顶点（`PartList.xml:204-209`：底边 ±2.0，顶边 ±0.6）——没有 `<Shape>` 的零件才用宽×高矩形盒（`game/Part.java:101-111`）。
- 连接点只有 `BottomCenter` 一个（`PartList.xml:210-212`）：鼻锥只能顶在别的零件上方，且**不带 fuelLine**（不过油）。
- Lua（`nosecone-1.lua:1-16`）：三个钩子全空，文件价值全在头注释。

### 4.1 阻力模型与"负阻力"的真相

阻力公式（`game/GameWorld.java:770-829` `applyEnvironmentForces`）：每个零件
`F = 0.5 * ρ * v² * Cd * A * exposure`，方向逆相对风速（`:823-828`）。其中：

- `Cd`：lua 用 `setDrag(cd)` 设的绝对值优先；否则 `Math.max(0.0, 0.75 + PartList.xml 的 drag 属性)`（`GameWorld.java:819-821`，脚本侧读取 `ModApi.getDrag` 同样的钳制，`ModApi.java:193-195`）。
- `A`：lua `setDragArea` 优先，否则取零件**宽度**（`GameWorld.java:822`、`ModApi.java:201-203`）。
- `exposure`：遮挡系数 0..1，8 条逆风射线采样，被前方零件挡住的零件几乎不受阻（`GameWorld.java:824-825`、`game/Ship.java:464-506`、`physics.lua:90-99`），脚本可读 `part:getDragExposure()`（`ModApi.java:253`）。

**注意一个文档与代码不一致的地方**：`nosecone-1.lua:4-8` 的头注释声称 `drag="-1.0"` 使 Cd = -0.25、"从全船总阻力中**减去**阻力"；`GameWorld.java:816-818` 的行内注释也这么写。但实际代码是 `Math.max(0.0, 0.75 + drag)` —— 负值被钳到 0。所以鼻锥的真实效果是：

1. 自身**零阻力**（不是负阻力）；
2. 真正的减阻收益来自**遮挡**：鼻锥在来流前方，降低身后油箱的 `dragExposure`，间接减掉别人的阻力（`physics.lua:96-99` 明确描述了这一点）。

修改时以代码为准；如果哪天想让鼻锥真的产生负 Cd，需要同时改 `GameWorld.java:821` 和 `ModApi.java:194` 两处钳制。

## 5. strut-1（Strut，加强梁）—— 分离器之外唯一有"主动"行为的结构件

- XML：`PartList.xml:46-53`。16×2 m 长梁、2.0 t、`canExplode="false"`（不可爆）、`buoyancy="0.5"`。
- 连接点 4 个全是**整边**（`Top/Bottom/LeftSide/RightSide`），每个带 `breakAngle="20"`、`breakForce="150.0"`。
  - `breakForce` 生效：焊接时取两点较小值（`game/Ship.java:143,157`），每帧检查接头反作用力（kN）超限即断（`Ship.checkJointBreaks`，`Ship.java:190-201`）。150 kN 远低于油箱的缺省（`breakForce` 缺省 `Double.MAX_VALUE`，`PartType.java:196`）。
  - **`breakAngle` 目前没有任何运行时使用**——只在 `PartType.java:34,195` 定义和解析。写文档/做功能时别指望它。
- Lua（`strut-1.lua:1-17`，17 行）：
  - `onLoad`（`:9-11`）：`part:setJointParams{frequencyHz = 35.0, dampingRatio = 1.25}` —— 给自己登记**超刚性焊接参数**。
  - `onStage`/`onUpdate`（`:13-17`）：空。
- 生效机制（这是"弹性阻尼连接点传力"的核心定制点）：
  - 全船零件间是 Box2D `WeldJoint` 弹簧-阻尼器（`Ship.weld`，`Ship.java:127-171`）。默认参数来自 `physics.lua` 的 `joints` 表：`frequencyHz = 20.0, dampingRatio = 1.1, angularDamping = 0.6`（`physics.lua:73`）。
  - 每个零件可用 `setJointParams` 覆盖自己的参数（`ModApi.java:223-236`，键可缺省，nil 回退全局表再回退 Java 默认）。
  - 两处焊接时**frequencyHz 高者胜**（更硬的一侧说了算），其 dampingRatio 随同（Java 回退规则 `Ship.java:146-156`；`joints.lua` 的默认实现 `joints.lua:31-54` 复刻同一规则）。
  - 结果：任何焊到 strut 上的连接都按 35 Hz / 1.25 解析（除非对方更高）——strut 的存在感不在自身，而在它**碰过的每一条焊缝**。
  - `angularDamping` 键只作用于零件自身 body（`ModApi.java:228-230`），strut 没用它。

## 6. lander-1（Lander，着陆腿）

- XML：`PartList.xml:288-294`。1×5 m、0.5 t、`type="lander"`、`ignoreEditorIntersections="true"`（编辑器里允许与其他零件重叠）、`buoyancy="0.5"`。
- `<Lander maxAngle="140" minLength="2.26" maxLength="4.15" angleSpeed="25" lengthSpeed="0.5" width="0.5" />`（`PartList.xml:289`）。
- 连接点：`LeftCenter` + `RightCenter`，同 `group="1"`（侧挂组，与 fueltank-5 同款）。
- Lua（`lander-1.lua:1-11`，11 行）：
  - `onLoad`：空。
  - `onStage`（`:6-8`）：`part:setDeployed(true)` —— 分级触发时置"展开"标志。
  - `onUpdate`：空。
- 引擎侧现状（**重要，别被 XML 参数误导**）：
  - `deployed` 只是 `Part` 上的一个布尔字段（`game/Part.java:30`），由 `ModApi.setDeployed/isDeployed` 读写（`ModApi.java:184-185`），会存进存档（`game/Ship.java:688,736`）。
  - **当前 Java 里唯一消费 `deployed` 的地方是降落伞渲染**（`ui/SandboxScreen.java:1288-1289`）。着陆腿的 `LanderDef` 参数（maxAngle/minLength/...）被解析（`game/PartType.java:76-78,166-174`）后**没有任何运行时代码使用**——全库搜索仅出现在 PartType 与 `ModApi.hasLander`（`ModApi.java:178`）。
  - 即：着陆腿目前 = 一根 1×5 m、摩擦与其他零件相同的杆 + 一个存档标志位；腿的展开动画、展开后的额外碰撞体/缓冲都**尚未实现**（或留给玩家 mod 用 `hasLander`+`isDeployed` 自行发挥）。
- 地面交互：与所有零件一样靠 fixture 摩擦（见第 8 节）。分级编排上，lander 与降落伞同属"辅助级"（`game/ShipDesign.java:44-45`：stage 0 引擎、stage 1 分离器、stage 2 降落伞+着陆腿）。

## 7. wheel-1 / wheel-2（轮子）

- XML：
  - `wheel-1` "Old Wheel"（`PartList.xml:28-32`）：4×4 m、0.25 t、`type="wheel"`、`hidden="true"`（编辑器里隐藏，属遗留件）、`disableEditorRotation="true"`、`ignoreEditorIntersections="true"`。
  - `wheel-2` "Wheel"（`PartList.xml:33-37`）：参数相同，**不隐藏**，且 `buoyancy="1.0"`。
  - 两者都只有一个原点连接点 `x=0 y=0 breakAngle=180`（单点挂接）。
- Lua（各 10 行）：与 fuselage 相同的三空钩子，自述 "Structural part: no active behavior."。
- **Java 里没有任何 wheel 专用代码**：全库搜索 `wheel` 只命中同步清单 `util/Res.java:107-108`。`type="wheel"` 字符串从未被读取。描述文本 "Your turn buttons can control these wheels" 指的是飞船的转向按钮（`GameWorld.inputTurn`，`game/GameWorld.java:77`；引擎 gimbal 控制律在 `mods/control.lua`，按钮模式见 `control.lua:41-42`）——轮子本身**没有马达、没有转向机构**，它的"地面交互"就是一块高摩擦碰撞体（见下节）。
- 两者差异仅：wheel-1 隐藏 + 无浮力（旧版保留），wheel-2 可见 + 可浮。

## 8. 所有零件共享的地面交互（轮子/着陆腿必读）

轮子、着陆腿乃至任何零件与地形的接触行为都走同一条代码路径（`game/Part.createBody`，`game/Part.java:85-137`）：

- **CCD 防穿透**：所有零件 body 都是 `bullet = true`（`Part.java:88-91`）——撞击速度 500+ m/s 时每步位移超过 8 m，不开 CCD 会直接穿过静态地形块。
- **摩擦下限 1.5**：`fd.friction = Math.max(1.5f, type.friction)`（`Part.java:123-128`）。原因是 Box2D 的接触摩擦按 `sqrt(fA*fB)` 混合，旧默认 0.4 会让飞船在任何坡面上打滑；抬到 1.5 后着陆才真正"抓地"。XML 里 `friction` 缺省 0.4（`PartType.java:92,134`），所以对轮子/着陆腿而言实际摩擦就是 1.5。
- **零弹性**：`fd.restitution = 0.0f`（`Part.java:129-131`）——着陆不弹跳（旧值 0.05 会让飞船出生后蹦一下）。
- 形状：无 `<Shape>` 的零件（轮子、着陆腿、油箱、机身、梁）用宽×高矩形盒（`Part.java:101-111`），轮子是 4×4 **方**盒而非圆形——靠高摩擦而不是滚动来"抓地"。
- 浮力（`buoyancy` 属性）在 `GameWorld.java:832-843`：水下按排水体积 × buoyancy × 重力沿径向向外推，wheel-2(1.0)、fuselage(1.0) 能浮，strut/lander(0.5) 半浮，油箱无 buoyancy 属性（默认 0）会沉。

---

## 9. 速查与常见坑

| 主题 | 结论 | 出处 |
|---|---|---|
| 油箱质量 | 运行时 = 干重 + 剩余燃料(kg)，XML mass 被覆盖 | `Part.java:140-153` |
| 液体供油范围 | 仅 fuelLine 连通组件；mono/电全船共享；固体只烧自己 | `Ship.java:333-354` |
| 均压脚本 | 6 个油箱脚本完全相同；速率 dt*2、死区 0.002；全船统计 vs 组件内转移口径不一致 | `fueltank-0.lua:10-23`、`Ship.java:386-416` |
| 油量可视化 | Java 自动画液位条 + 选中读数，lua 无需配合 | `SandboxScreen.java:1140-1171, 1786-1797` |
| 鼻锥负阻力 | 注释说 Cd=-0.25 减总阻力，**实际被钳到 0**；收益靠遮挡 exposure | `GameWorld.java:819-821`、`physics.lua:90-99` |
| strut 刚性 | setJointParams 35 Hz/1.25，焊接时高频侧胜 | `strut-1.lua:10`、`Ship.java:146-156` |
| breakAngle | 解析了但**运行时不使用** | `PartType.java:195`（无其他引用） |
| LanderDef 参数 | 解析了但**运行时不使用**；deployed 仅降落伞渲染消费 | `PartType.java:166-174`、`SandboxScreen.java:1289` |
| 轮子 | 无任何专用代码；摩擦下限 1.5 + 零弹性 + CCD；wheel-1 隐藏 | `Part.java:91,128,131`、`PartList.xml:28` |
