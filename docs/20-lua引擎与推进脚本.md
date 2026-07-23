# Lua 引擎与推进脚本（engine-0~4 / ion-0 / flame / control）

> 目标读者：刚接手 DifferentRockets 的开发者。本文精读 `game/core/assets/mods/` 下
> 与推进相关的 8 个 lua 脚本：`engine-0.lua`、`engine-1.lua`、`engine-2.lua`、
> `engine-3.lua`、`engine-4.lua`、`ion-0.lua`、`flame.lua`、`control.lua`。
> 所有结论均来自脚本源码，引用格式为 `文件名:行号`（行号对应仓库内当前版本，
> 版本号见各文件第 1 行 `-- v2026.07.xx` 注释）。

## 0. 总览

| 脚本 | 行数 | 版本注释 | 职责 |
|---|---|---|---|
| `engine-0.lua` ~ `engine-4.lua` | 各 90 | v2026.07.22 | 化学/固体/单组元引擎的推力、燃料消耗、摆角驱动 |
| `ion-0.lua` | 90 | v2026.07.22 | 离子（电推）引擎，逻辑与 engine-* 完全相同 |
| `flame.lua` | 233 | v2026.07.21 | 程序化尾焰渲染：三角形激波锥 + glow/smoke/spark 粒子 |
| `control.lua` | 60 | v2026.07.22.1 | 全船共享的发动机摇摆（gimbal）控制律 |

**关键事实：`engine-0.lua` 到 `engine-4.lua` 以及 `ion-0.lua` 六个文件的内容逐字节完全一致**
（md5 均为 `164d5c4eb88141abdd9e998127b163d5`）。引擎之间的差异（推力、油耗、
燃料类型、是否可摇摆）全部由 PartList.xml 中的零件属性（`getEnginePower()`、
`getEngineConsumption()`、`getEngineFuelType()`、`getEngineTurn()` 等）驱动，
lua 脚本本身是同一份「通用引擎行为」模板。改一处不等于改全部——若要统一修改
引擎逻辑，需要同步改这 6 个文件。

整体数据流：

```
玩家点火(stage)  →  onStage(part) 置 staged=true
每个物理帧        →  onUpdate(part, dt)
                      ├─ updateGimbal() → controlLaw(part)（来自 control.lua）→ setGimbalDeg
                      ├─ 按 fuelType 走 SRB / 化学(含单组元/电力) 两条消耗路径
                      ├─ 推力矢量 applyForceAt 施加在喷口处
                      └─ emitFlame(强度, 摆角) → Java 端回调 flame.lua 的 drawFlame(ctx)
```

---

## 1. 通用引擎脚本（engine-0~4.lua / ion-0.lua）

以下行号以 `engine-0.lua` 为准，其余 5 个文件行号相同。

### 1.1 头部注释里的两条领域规则（engine-0.lua:2-23）

注释本身即设计文档，两条规则必须理解：

1. **燃油供给规则（engine-0.lua:8-16）**——化学燃料（fuelType 0）发动机只从
   「通过 fuelLine 连接点与它相连的油箱 + 直接贴在它身上的油箱」组成的燃料管网取油；
   被没有 fuelLine 的零件（指令舱 / 分离器 / 电池等）隔开的油箱不会给它供油。
   单组元（type 1，RCS）与电力（type 2）全船共享，固体燃料（type 3）只烧自身内置油箱。
   该拓扑寻径在 Java 端 `part:drainFuel(ft, need)` 内实现，lua 只负责调用。
2. **摇摆控制（round 12）（engine-0.lua:18-23）**——偏转角直接由 `control.lua` 的
   `controlLaw(part)` 给出（按钮 = 满偏 / 转向环 = 航向误差截断 / 无输入 = 回中），
   取代了 round 9 的每引擎 PID。推力方向使用 controlLaw 返回的【实际】偏转角。

### 1.2 upvalue `staged`（engine-0.lua:24）

```lua
local staged = false
```

每个零件实例有**独立的 lua 状态**（见 `onLoad` 注释 engine-0.lua:30-32），所以
`staged` 是每引擎私有的「是否已点火」标志。

### 1.3 `onLoad(part)`（engine-0.lua:26-37）

做两件事：

1. **存档往返后重新布防（:27-29）**：飞船的 stage 列表会持久化，但 lua 的
   upvalue 不会，所以读档后用 `part:getStage() > 0` 反推 `staged`，避免读档后
   已点火的引擎不再工作。
2. **把共享控制律载入本引擎的 lua 状态（:30-36）**：
   ```lua
   if controlLaw == nil then
     local src = part:readModText("control.lua")
     if src ~= nil then pcall(load(src, "control.lua")) end
   end
   ```
   注意三个细节：
   - 用 `pcall(load(...))` 容错——control.lua 语法错误不会崩掉引擎脚本；
   - 只在 `controlLaw == nil` 时加载，不会重复加载；
   - 注释明确说明：control.lua 的修改只对**新造的船 / 资源重载后**生效
     （control.lua:26-28 也重复了这一点），因为老船上的零件状态早已加载过旧版本。

### 1.4 `onStage(part)`（engine-0.lua:39-41）

玩家按下对应级点火时被 Java 端调用，仅置 `staged = true`。从这一帧起
`onUpdate` 才开始工作。

### 1.5 `drainOwn(part, dt)`（engine-0.lua:43-49）——SRB 专用内置油箱消耗

```lua
local need = part:getEngineConsumption() * dt
local have = part:getFuel()
local use = math.min(need, have)
part:setFuel(have - use)
if need > 0 then return use / need else return 0 end
```

固体助推器（SRB）不走燃料管网，只烧零件自己的 `fuel` 储量。返回值为
**实际消耗占需求的比例**（0..1），用于把推力按燃料余量等比衰减——油箱见底时
推力线性掉到 0，而不是戛然而止。

### 1.6 `updateGimbal(part, dt)`（engine-0.lua:51-59）——摆角驱动

```lua
if part:getEngineFuelType() == 3 or part:getEngineTurn() <= 0 then return 0 end
if controlLaw == nil then return 0 end
local g = controlLaw(part)
part:setGimbalDeg(g)
return g
```

- SRB（fuelType 3）或不可摇摆引擎（`getEngineTurn() <= 0`）直接返回 0；
- control.lua 缺失/加载失败时**安全回中**（:55 注释）；
- 每个物理帧都会调用 `controlLaw(part)` 并把结果写回 `part:setGimbalDeg(g)`。
  即使油门为 0 也运行（:51 注释），这样关车后喷口能随控制律回中；
- 返回值 g 就是本帧用于推力方向和尾焰方向的**实际**偏转角（度）。

### 1.7 `onUpdate(part, dt)`（engine-0.lua:61-90）——每物理帧主逻辑

**前置闸门（:62）**：`if not staged then return end`——未点火的引擎完全不工作
（连摆角回中也不做，注意这与 updateGimbal 内部「油门 0 也运行」是两个层级）。

**摆角（:63）**：先取 `gimbal = updateGimbal(part, dt)`，后面 SRB 分支会强制清零。

**分支一：SRB（fuelType == 3，:68-72）**：

```lua
local frac = drainOwn(part, dt)
thrust = part:getEnginePower() * 1e5 * frac
gimbal = 0 -- SRB: rigid nozzle, no gimbal
```

- 不受油门控制（**全功率**烧完为止），推力只随内置油箱余量比例 `frac` 衰减；
- 推力量级 = `引擎功率 × 1e5`（引擎参数里的 power 单位是「10 万牛顿」级）；
- 喷管固定，强制 `gimbal = 0`。

**分支二：化学 / 单组元 / 电力（:73-81）**：

```lua
if th <= 0 then return end
local te = th
if part:isThrottleExponential() then te = th * th end
local need = part:getEngineConsumption() * te * dt
local got = part:drainFuel(ft, need)
local frac = need > 0 and (got / need) or 0
thrust = part:getEnginePower() * 1e5 * te * frac
```

- 油门 `th <= 0` 直接返回（摆角已在 :63 更新过，喷口照样回中）；
- `isThrottleExponential()` 为真时油门按平方响应（`te = th²`），低油门段更细腻；
- **消耗与推力解耦的关键**：`part:drainFuel(ft, need)` 从燃料网络实际抽走
  `got`，返回比例 `frac = got / need`，推力再乘 `frac`——燃料供不上时推力
  等比下降，而不是「有油满推力、没油零推力」的硬切换；
- `drainFuel` 的寻径范围就是头部注释那条规则：fuelType 0（煤油）只走
  fuelline 管网 + 贴身油箱；fuelType 1（单组元）和 2（电力）全船共享。
  **电推与化学发动机的差异完全体现在这里**：ion 引擎在 PartList.xml 里配成
  fuelType 2，`drainFuel(2, need)` 即从全船电力网络取电，无需 fuelline。

**推力矢量输出（:83-89）**：

```lua
local ang = part:getAngle() + math.rad(gimbal)
local dx = -math.sin(ang)
local dy = math.cos(ang)
part:applyForceAt(dx * thrust, dy * thrust, 0, -part:getHeight() / 2)
part:emitFlame(math.min(1.2, thrust / (part:getEnginePower() * 1e5)), gimbal)
```

- 推力方向 = 零件朝向 `getAngle()` 叠加摆角（弧度），方向约定为体角 a 下的
  `(-sin a, cos a)`（与 control.lua:48 的船头指向约定一致）；
- **施力点在喷口**（零件中心下方 `getHeight()/2` 处，:87-88），所以摆角产生
  真实的 Box2D 力矩，这就是姿态控制的物理来源；
- `emitFlame(强度, 摆角)` 通知渲染层：强度 = 实际推力 / 满推力，上限截到 1.2
  （SRB 满油时 frac=1 正好为 1.0）；Java 端随后每渲染帧回调 `flame.lua` 的
  `drawFlame(ctx)`。

---

## 2. control.lua —— 全船共享的摇摆控制律（round 12 / 13 修订）

整个文件只导出一个函数 `controlLaw(part)`（control.lua:35-59），每个可摇摆
引擎每物理帧各调一次，返回该引擎本帧的摆角（度；正 = 推力顺时针偏，产生顺时针
力矩）。**刻意没有 PID**（control.lua:7），历史注释（:30-32）说明它取代了
round 9 的每引擎 PID（physics.lua 的 gimbal 表）和 GameWorld 的船级 PI 转向
（steering 表），两张表现在仅保留不再读取。

### 2.1 入口与量程（control.lua:36-38）

```lua
local s = part:getSteering()
local maxDeg = part:getEngineTurn()
if maxDeg <= 0 then return 0 end
```

`s` 是 Java 端 SteeringIO 的快照，含 `buttonTurn`（-1/+1）、`active`（转向环
是否激活）、`targetRad`（目标航向，弧度）。`maxDeg` 是本引擎的最大摆角，
不可摇摆的引擎直接返回 0。

### 2.2 模式一：按钮 BUTTON（control.lua:40-43）

```lua
if s.buttonTurn ~= 0 then
  return s.buttonTurn * maxDeg
end
```

UI 按住转向键时，**所有可摇摆发动机统一打到该方向的最大摆角**，并覆盖转向环。
响应是「砰」式的满偏——没有斜坡、没有限速率。

### 2.3 模式二：转向环 RING（control.lua:45-57）——矢量法航向误差

这是 round 13 的修订点（control.lua:17-22）：误差角改用**矢量法**计算，避免
角度取模在 ±180° 处的跳变。

```lua
local a = part:getShipHeading()
local px, py = -math.sin(a), math.cos(a)
local tx, ty = -math.sin(s.targetRad), math.cos(s.targetRad)
local err = math.atan2(px * ty - py * tx, px * tx + py * ty)
local g = -math.deg(err)
if g > maxDeg then g = maxDeg elseif g < -maxDeg then g = -maxDeg end
return g
```

算法要点：

1. **两个单位向量**：船头指向 `p = (-sin a, cos a)`（体角约定，0 = 船头朝
   「上」，逆时针为正），目标指向 `t` 同理（control.lua:46-51）。船首向取的是
   **指令舱角** `part:getShipHeading()` 而不是发动机自身角——全船所有引擎对同一
   误差角响应，避免不同朝向的引擎互相打架。注释提示：想让每台发动机按自身角度
   控制，把 `getShipHeading()` 换成 `part:getAngle()`（control.lua:21-22）。
2. **误差 = atan2(二维叉积, 点积)**（control.lua:53）：
   `err = atan2(p×t, p·t)`。叉积和点积都是连续函数，所以船转过 ±180° 时误差角
   **平滑环绕，没有取模运算的跳变沿**——这就是「避免角度跃变」的具体实现。
   该式还与「航向 0 点朝哪」的全局约定无关：只要 p、t 用同一约定，参考系在
   叉积/点积中抵消（control.lua:19-21）。
3. **符号翻转**（control.lua:54）：误差为正（需逆时针转向）→ 返回负摆角
   （产生逆时针力矩）。
4. **按引擎量程截断**（control.lua:55）：`|g|` 截到 `maxDeg`。于是响应曲线是
   分段线性的——误差小时摆角与误差成正比（线性回中），误差超过 maxDeg 后饱和
   满偏。这就是任务里说的「与飞船角度差成正比至最大摆角」。
5. **luaj 坑**（control.lua:23-24）：必须用 `math.atan2(y, x)`；
   `math.atan` 的双参数形式在 luaj 里会**静默忽略第二个参数**（已实测）。

### 2.4 无输入（control.lua:59）

转向环未激活且没按按钮时返回 0——摆角回中。

### 2.5 两种控制方式对比

| | BUTTON（按钮） | RING（转向环） |
|---|---|---|
| 触发 | `s.buttonTurn = ±1` | `s.active = true` 且有目标航向 |
| 输出 | 直接满偏 `±maxDeg` | `clamp(-deg(err), ±maxDeg)`，与航向误差成正比直到饱和 |
| 优先级 | 高，覆盖转向环 | 低 |
| 响应特性 | 阶跃式，最猛的纠正力矩 | 连续平滑，接近目标时线性收敛 |

---

## 3. flame.lua —— 程序化尾焰渲染

文件头注释（flame.lua:2-50）是一份完整的 API 文档，要点先列出来：

- 每台运转中的引擎每帧调用一次 `drawFlame(ctx)`（flame.lua:23, 84）；
- 绘图是立即模式，由 Java 端合批（flame.lua:5）；
- 三个 API：`draw.triangle`（世界坐标三角形，普通半透明混合，flame.lua:7）、
  `draw.sprite`（加法混合发光精灵，贴图可选 `"glow"` / `"smoke"` / `"spark"`，
  flame.lua:9-14）、`flame.emit{...}`（发射世界坐标粒子，加法混合，
  **粒子池上限 600，满了回收最旧的**，flame.lua:15-19）；
- 粒子发射数量按 `ctx.dt`（**含 warp 的模拟秒**）缩放，所以 4x 加速下速率稳定
  （flame.lua:20-21）；
- 文件出错时回退到内置默认尾焰；保存即热重载（约 1 秒检查一次，flame.lua:49）。

### 3.1 工具函数

- **`clamp(v, lo, hi)`**（flame.lua:52-56）：标量截断。
- **`rr(a, b)`**（flame.lua:58）：`[a,b]` 均匀随机，所有粒子参数抖动都靠它。
- **`cone(ctx, len, half, lenF, widF, r, g, b, a)`**（flame.lua:60-67）：从喷口沿
  `dir` 方向画一个三角形激波锥——顶点在喷口，底边在 `len*lenF` 处、半宽
  `half*widF`，底边方向取喷流的垂直向量 `(-dirY, dirX)`。整个尾焰的「铺底层」
  都由它画出。
- **`acc` 表 + `budget(id, stream, rate, dt)`**（flame.lua:69-80）：按引擎的
  **小数发射累积器**。键为 `partId * 16 + stream`（每台引擎 5 条粒子流各占一个
  stream 号），每帧累加 `rate * dt`，取整作为本帧发射数，余数留存——保证任意
  dt（含 warp）下平均速率精确等于 `rate` 个/模拟秒。条目数受引擎数限制，无需
  清理（flame.lua:82 注释）。

### 3.2 `drawFlame(ctx)` 的公共前奏（flame.lua:84-105）

```lua
local lvl = math.min(1, ctx.throttle)
if lvl <= 0.01 then return end
local p = clamp(ctx.pressure or 1.0, 0, 1.2)
local vac = 1 - math.min(p, 1)          -- 真空度 0..1
local atmo = clamp(p / 0.25, 0, 1)      -- 低气压因子
```

- `lvl`：焰级（跟随油门），≤0.01 直接不画；
- **气压是两个视觉因子的来源**（`ctx.pressure`：1.0 = 海平面，0 = 真空，
  flame.lua:36）：
  - `vac`（真空度）控制喷流**扩散**——真空欠膨胀；
  - `atmo`（p/0.25 截断）控制大气内效果（火花、粒子亮度）；
- 其他派生量：垂直向量 `(px, py)`（:93）、视觉喷流速度
  `speed = engH * (6 + 7*lvl)`（:97）、尾焰长度
  `len = engH * (1 + 2.2*lvl) * (1 + 0.9*vac^1.3)`（:98，**真空中拉长近一倍**）、
  以及由世界坐标哈希出的 `phase`（:100）用于各引擎闪光错相。

**核心颜色随气压渐变**（flame.lua:102-105）：R 恒 1.0，G 从 0.72 升到 0.85，
B 从 0.38 升到 0.93——海平面橙白 → 真空蓝白（橙→蓝移）。

### 3.3 离子引擎分支（flame.lua:107-131）

`ctx.ion`（fuelType == 2）时走独立分支并 `return`：

- 两层蓝色半透明锥（:110-111，宽度系数乘 `iw = 1 + 0.8*vac`，真空变宽）；
- 3 枚轴向 glow 精灵作准直蓝核心（:113-118）；
- 稀疏高速火花：预算速率 `8 * engS * lvl` 个/秒，方向抖动仅 ±0.05 rad，
  速度 `speed * rr(1.6, 2.6)`（比化学焰快得多），寿命 0.3-0.7 秒，颜色
  `(0.55, 0.75, 1.0)`（:120-129）。**无烟团**——电推没有燃烧产物。

### 3.4 化学引擎分支（flame.lua:133-232）

按「贴图铺底 → 核心 → 马赫环 → 四条粒子流」分层：

1. **铺底激波锥**（:135-142）：外层锥宽度系数 `2.5 * (1 + 5*vac^0.7)`——
   **真空中张开到 6 倍宽**并变淡（`0.15 * (1 - 0.75*vac)`），模拟欠膨胀喷流；
   再叠一个中压散射光晕锥，权重为高斯 `exp(-((p-0.3)/0.18)²)`，峰值在 p≈0.3
   （平流层凝结羽的辉光）。
2. **贴图核心**（:144-154）：4 段轴向 glow 精灵串，尺寸沿轴向递减
   （`1.9 - 1.1f`）、随真空加宽（`1 + 0.9*vac`）、带 6% 亮度呼吸
   （`shimmer = 1 + 0.06*sin(time*11 + phase)`），透明度随 `lvl` 缩放。
3. **马赫环**（:156-165）：权重 `md = clamp((p - 0.12) / 0.5, 0, 1)`——
   **p = 1（海平面）最强，p < 0.12（约 15 km）完全消失**，这就是「气压相关的
   马赫环」。4 枚拉宽的 glow 亮斑沿核心下方 0.40~0.73 倍焰长处等距排开，
   颜色近白 `(1, 1, 0.95)`。
4. **粒子流一·亮核心碎焰**（:167-185）：任意高度都发，速率
   `(30 + 70*lvl) * engS`；张角 `±(0.04 + 0.28*vac)`（真空中散开）、寿命
   `0.15 → 0.32+0.5*vac` 变长、末端尺寸 `0.3 + 1.4*vac` 变大——真空中更宽、
   更蓝（用核心渐变色）、更长寿。
5. **粒子流二·火花**（:187-200）：只在 `atmo > 0.05` 时发射，速率还乘 `atmo`
   ——大气内短命硬质亮点（spark 贴图，0.18-0.42 秒），颜色偏黄白
   `(1.0, 0.85, 0.55)`。
6. **粒子流三·中段烟羽**（:202-216）：高斯带 `exp(-((p-0.3)/0.28)²)` 限定在
   p≈0.05..0.6 的中气压区间；烟团（smoke 贴图）在焰长 50%-90% 处出生，
   `drag = 1.5`（指数阻尼，边减速边胀大变淡），尺寸从 `nw*1.6-2.4` 胀到
   `nw*5-8`，寿命 1-2 秒——平流层凝结羽。
7. **粒子流四·真空宽扇**（:218-232）：只在 `vac > 0.5` 时发射，张角
   **±0.6 rad ≈ ±35°**，极淡（a0 = 0.06）蓝白长寿（0.5-1.1 秒）微粒，
   末端尺寸胀到 `nw*3.5-6`——真空欠膨胀的宽扇形羽流。

### 3.5 气压驱动的整体过渡（对照 flame.lua:39-48 的设计注释）

| 高度带 | 主导效果 |
|---|---|
| 海平面 p≈1 | 马赫环亮斑 + 火花 + 橙白准直核心 |
| 中气压 p≈0.05..0.6 | 散射光晕锥 + smoke 烟羽（drag 减速胀大） |
| 高空/真空 p→0 | 激波锥张开 6 倍、核心变宽变蓝、±35° 宽扇微粒，马赫环与火花消失 |
| 离子引擎任意高度 | 细长蓝羽 + 高速蓝火花，全程无烟 |

---

## 4. 接手者常见疑问速查

- **改引擎推力/油耗/燃料类型去哪改？** 不在 lua，在 PartList.xml 的引擎零件属性
  （脚本只读 `getEnginePower()/getEngineConsumption()/getEngineFuelType()/getEngineTurn()`）。
- **改了 control.lua 为什么不生效？** 引擎只在 `onLoad` 时加载一次
  （engine-0.lua:33-36），老船沿用旧代码；需新造船或触发资源重载
  （control.lua:26-28）。
- **为什么油箱有油引擎却不工作？** 化学引擎只从 fuelline 管网 + 贴身油箱取油
  （engine-0.lua:8-16），中间隔着指令舱/分离器/电池的油箱是隔离的。
- **为什么没有 PID 也不会振荡发散？** 控制律输出有界（截到 ±maxDeg），力臂短
  （喷口在零件底部），且 Box2D 物理本身有阻尼；设计取舍见 control.lua:7。
- **warp 下尾焰为什么不爆粒子？** 发射预算按含 warp 的 `ctx.dt` 缩放且有
  小数累积器（flame.lua:20-21, 73-80），粒子池硬上限 600（flame.lua:17）。
