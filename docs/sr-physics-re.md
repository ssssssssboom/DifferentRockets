# SimpleRockets 物理实现逆向对照分析

目标二进制：`tmp/sr-apk/lib/armeabi-v7a/libNativeModule.so`（ARM32 ARM 模式，.dynsym 未剥离）。
反汇编方法：pyelftools 取 `.dynsym` 函数地址/大小 + capstone ARM 模式反汇编。
**PC 相对常量寻址公式（实测修正）**：literal 地址 = `(指令地址 & ~3) + 8 + imm`（任务书写的 `+4` 是错的，已用 `LocalPhysics::Update` 里的 1/60 double 实测验证）。所有 `vldr dX, [pc, #imm]` / `ldr rX, [pc, #imm]` 常量均按此公式定位并解码。

文中所有地址均为 ELF 虚拟地址。标注"未解"= 汇编块含义未能确定，未做任何猜测。

---

## 0. 全局结论速览

- SR 的飞船 = **每个零件一个独立 dynamic b2Body**，零件间用刚性 b2WeldJoint 两两焊接（与我们复刻的模型一致）。
- Box2D world **ambient gravity = (0,0)**，引力、大气阻力、浮力、引擎推力全部通过**直接写 b2Body 的 m_force / m_torque 字段**实现（不走 `ApplyForce` API，且会把睡眠中的 body 强制唤醒：`m_flags |= 2`、`m_sleepTime = 0`）。
- 主引擎推力作用于质心、**无力矩**；RCS 作用在喷口位置、**有力矩**。没有 gimbal。
- 大气阻力是**整船一个等效阻力面积**算总力、再按零件质量比例分摊到各零件。
- 转动惯量完全由 Box2D 按 fixture 几何多边形算出（fixture density = 零件质量 / 零件 fixture 总面积），不是解析盒式近似。

---

## 1. 零件 body 创建

### SR 实现

`PartObject::CreatePhysics` @ `0x1d2bf0`（368 B）：

- 若 `part+0x1cb`（destroyed 标志）已置位则直接返回。
- 栈上构造 b2BodyDef（本分支 Box2D 的 BodyDef 布局：type 在最前）：
  - `type = 2`（dynamic）（`0x1d2c64: mov r3,#2 → str [sp,#0x14]`）
  - position = 传入位置 − 零件在 RigidBody 中的偏移（`part+0x1d0/0x1d8` 双精度），angle 取零件角度
  - linearVelocity/angularVelocity/linearDamping/angularDamping 全 0（`0x1d2c5c`–`0x1d2c84`）
  - allowSleep=1、awake=1（`0x1d2c4c/0x1d2c50`，sp+0x38/0x39）
  - fixedRotation=0、**bullet=0**（`0x1d2c88/0x1d2c8c`，sp+0x3a/0x3b）
  - enabled=1、gravityScale=1.0f（`0x1d2c58: mov r3,#0x3f800000 → str [sp,#0x44]`）
  - userData = 0（创建后另行关联）
- 调 `LocalPhysics::CreateBody` @ `0x197d48`（尾调 b2World::CreateBody），body 指针存入零件的 cfw::RigidBody 包装（`part+0x1bc`，RigidBody 布局：`+4` = b2Body*、`+8` = Vector2D 逻辑位置，见 `RigidBody::GetBody @ 0x1d1a90` / `SetBody @ 0x1d1ab4` / `GetPosition @ 0x1d1a54`）。
- 调 `PartObject::CreateFixtures(body, offset, angle)` @ `0x1d25d8`（1560 B）：
  - 遍历零件形状表 `[part+0x1a8..0x1ac)`（每项 16 B：形状类型字节 + b2Vec2 顶点 vector）。
  - 顶点按 flippedX/flippedY 取反（`0x1d2938` / `0x1d2a60` 分支），按 body 中心偏移平移、按角度旋转（`0x1d2988` 起 sin/cos 旋转）。
  - b2PolygonShape::Set（`0xa26e4`），shape radius = `0x3c23d70a` = **0.01f**（`0x1d2624`）。
  - FixtureDef：**density = 1.0f（初值）**（`0x1d2668: 0x3f800000 → sp+0x54`）、**friction = PartType 摩擦**（`[part+0x170]` double→float，`0x1d25d8` 起）、**restitution = 0**（sp+0x50）、**isSensor = 形状类型字节**（sp+0x58）、**filter.categoryBits = 0x0002、maskBits = 0x0003**（`0x1d2608/0x1d2634`：strh 2 → sp+0x5a，strh 3 → sp+0x5c）、groupIndex=0。
  - 每个多边形用 density=1 调 `b2PolygonShape::ComputeMass`（`0xa2240`）把面积累加进 `[part+0x1c0]`。
  - 全部 fixture 建完后（`0x1d28b4` 起）：调虚函数 `vtbl[0x10]`（GetMass）得零件质量，**把每个 fixture 的 m_density（b2Fixture+0）覆写为 `质量 / 总fixture面积`**，对每个 fixture 调 `vtbl[0x68]`（OnFixtureCreated），最后 `b2Body::ResetMassData` @ `0xa32b0`。
  - 结论：**质量精确等于 PartType 质量；转动惯量由 Box2D 按几何形状×等效密度算出**，无自定义 I。
- 最后遍历 body listener 列表 `[part+0x1ec..0x1f0)` 调 `vtbl[0]`（OnBodyCreated/BodyCreated 回调，DetacherObject 等用来补建额外 fixture）。

各子类：
- `TankObject::CreatePhysics` @ `0x1df5fc`：基类创建后遍历 body 的 fixture 链（`body+0x64` = m_fixtureList，fixture+4 = m_next，fixture+0x28 = userData），找到 userData 指向自己的 fixture 存入 `[tank+0x224]`，供燃料变化时改写密度。
- `EngineObject::CreatePhysics` @ `0x1cc618`：同上，存入 `[engine+0x298]`。
- `FixtureObject::CreatePhysics` @ `0x1cdd20`：仅当 `[part+0x220] != 0` 时才调基类创建 body，否则只记录 LocalPhysics（**无碰撞体的结构件**分支）。
- `DetacherObject::CreatePhysics` @ `0x1cb2ec`：`[part+0x240]`（已分离标志）置位时不建 body。
- `ParachuteObject::CreatePhysics` @ `0x1d0074`：基类 + 若 `[part+0x1c4]`（已开伞）则调开伞物理 `0x1cfdfc`。
- `LanderObject::CreatePhysics` @ `0x1ce734`：自建 dynamic body（同 BodyDef 参数），**不建任何 fixture**（腿的 fixture 等展开后在 PhysicsStep 里动态加）。
- `WheelObject::CreatePhysics` @ `0x1dfcc4`（832 B）：**建两个 dynamic body**——主轮 body（circle，半径 = `[part+0x150]×0.5`，**friction=0.95f**（`0x1dfe80: 0x3f733333`），density = GetMass/圆面积，**category=0x0008、mask=0x0009**（只撞地形和轮组，不撞船体），`0x1dfe24/0x1dfe38`）和轮毂 body（circle，半径×0.25，friction=0.85f（`0x1dff58: 0x3f59999a`），category=2/mask=3），然后 `CreateRevoluteJoint` 连接，joint 存 `[wheel+0x204]`；`EnableMotor(true)`（`0xb16bc`）、`SetMaxMotorTorque(750.0f)`（`0x1dffac: 0x443b8000`，`0xb1750`）。
- `ShipOrbitNode::CreatePhysics` @ `0x1b966c`：若 `[+0x17c]` 允许，遍历零件列表对每件调 `vtbl[0x28]`（CreatePhysics）。

### 我们实现

- `Part.java:123-128`：DynamicBody，**bullet=true**（SR 不是 bullet！）；`Part.java:166-177`：density=1、friction=max(1.5, type.friction)、restitution=0。
- `Part.java:284-302` `updateMass()`：`setMassData` 显式设质量 + **盒式近似 I = m(w²+h²)/12**。

### 差异与复刻建议

| 项 | SR | 我们 | 建议 |
|---|---|---|---|
| bullet | 否 | 是（Part.java:128） | **不必复刻**（bullet 是我们防 tunneling 的改进） |
| 转动惯量 | 几何精确（density=m/面积 + ResetMassData） | 盒式近似 m(w²+h²)/12（Part.java:295） | **建议复刻**：改为按实际 fixture 几何让 Box2D 算（或按密度覆写法），矩形零件两者相同，异形件有差 |
| 零件 friction 直用 | type.friction（无下限） | max(1.5, …)（Part.java:174） | **不必复刻**（我们是有意抬高抓地） |
| filter | category 2 / mask 3 | 需查 Part.java 完整 fixture def | **建议核对**：保证零件只撞 地形(1)+零件(2)，轮(8) 不撞船体 |

---

## 2. 引力施加

### SR 实现

`LocalPhysics::ApplyGravity` @ `0x198018`（316 B），每个物理子步在 `b2World::Step` 之前调一次（调用点 `0x1981a8`，在 `LocalPhysics::Update` 内）：

1. 取飞船相对行星中心位置：调 `0x1ac184`（Runtime 内 `[Runtime+8]` 对象的 `vtbl[0x20]`，out 参数写 16 B Vector2D 到栈）。
2. `r = √(x²+y²)`（double）；若 r 为 NaN 走 `0x7f424`（sqrt 慢路径）。
3. `d8 = x²+y²`（r²）。从数据地址 `0x263820`（**.bss 运行时变量，静态不可知**，按上下文为行星半径或最小半径阈值）读 double d7；**仅当 r > d7 时**把 (x,y) 归一化（`vdivgt`，`0x19807c`）。未归一化分支是防奇异保护。
4. 调 `0x1ac138`：`[Runtime+0xb4]`（Planet）的 `vtbl[8]` 返回对象并取其首 8 字节 → 行星质量 M（double，kg）。
5. 常量：`vldr d7, [pc,#0xb8]` @ `0x198088` → literal @ `0x198148` = **6.67384e-11（真实万有引力常数 G）**。
6. 加速度 `a = −G·M / r²`，向量 `a·(ux, uy)`（`0x1980a4`–`0x1980b4`），转 float。
7. 遍历 b2World body 链（world 对象偏移 `0x19228` = m_bodyList，`0x19808c`）：**跳过 type != 2（非 dynamic）**；直接执行 `m_force += a·m_mass`（`[body+0x74]` = m_mass，`[body+0x4c/0x50]` = m_force，`vmla.f32`，`0x1980e8`–`0x198110`），睡眠 body 置 awake 并清零 sleepTime（`[body+4]` flags、`[body+0x90]`）。

要点：**单行星点质量引力**（当前所在行星），不是 N 体；G 用真实常数；力按质量缩放、每子步施加一次。

### 我们实现

- `GameWorld.java:222-241` `gravityAt()`：**Lua 可定义的 N 体 Σμ/r²**（`PhysicsScript.gravity` 优先），`r < rmin` 有下限 clamp（`GameWorld.java:257`）。
- `GameWorld.java:1229-1231`：每零件每子步 `applyForceToCenter(g·m)`。

### 差异与复刻建议

- SR 单行星 vs 我们 N 体：**不必复刻**（N 体是功能超集；SR 在 SOI 边界切换行星，见 §9）。
- SR 直接写 m_force 且强制唤醒 vs 我们 `applyForceToCenter(..., wake=false)`：**建议复刻**唤醒语义——若我们的 body 睡着，引力会被 Box2D 在 Step 里丢弃，造成"睡眠后悬停"。需确认我们的 body 是否常睡（allowSleep 策略）。
- SR 用 double 算引力再转 float；我们用 double 全程（`gravityFast`），精度相当。

---

## 3. 大气密度与阻力

### SR 实现：密度公式

`Atmosphere::GetAirDensityAtAltitude` @ `0x192128`（48 B）+ 内部助手 `0x191d50` / `0x191dc0`；常量来自 `Atmosphere::Atmosphere` @ `0x191de0`：

- 构造：`[this+0x60] = info[+8] × 100000.0`（literal @ `0x191f98` = **1e5**，`0x191e18`）——即表面气压 P₀ = info 字段 × 1 atm；
  `[this+0x58] = B = −info[+0x10] / ln(0.1 / P₀')`（`0x191e3c`：literal @ `0x191fa0` = **0.1**；`0x7f448` = log）——标高，使气压在高度 H=info[+0x10] 处恰好衰减到 0.1 Pa。
- 查询：`P = P₀ · e^(−alt/B)`，e 用 literal `0x4005BF0995AAF790` = **2.71828**（`0x191d68`–`0x191d78`，调 pow @ `0x7f16c`）；
  **若 P < 0.1 则密度 = 0**（clamp，literals @ `0x191db0`=0.1、@ `0x191db8`=0.0，`0x191d8c`–`0x191da0`）；
  **ρ = P / 84134.05**（literal @ `0x191dd8`，`0x191dc0` 的除法）。84134.05 ≈ R_specific·T（地球 287×293 量级）。

即：**压高公式指数衰减 + 0.1 Pa 硬截止 + 气压→密度固定换算系数**，无分段、无 Mach 修正。

### SR 实现：阻力施加

在 `ShipOrbitNode::PhysicsStep` @ `0x1b8f58`（1456 B，每子步对每艘船）：

1. 先调 `OrbitNode::PhysicsStep`、`Ship::PhysicsStep`，再遍历零件列表 `[node+0x194..0x198)` 调各件 `vtbl[0x18]`（PhysicsStep）、用 `vtbl[0x10]`（GetMass）累加**整船质量 d8**（`0x1b9000`–`0x1b9048`）；随后遍历连接列表 `[+0x1a4..0x1a8)` 调连接 PhysicsStep（断裂检查）。
2. 阻力（`0x1b91c0` 起）：取行星（`0x1a7228` = `[node+0xfc]`），dynamic_cast 出带大气的 Planet；算 `alt = |shipPos| − [planetInfo+8]`（行星半径）；调 `Planet::GetAirDensityAtAltitude` @ `0x1aa108`（有大气则尾调 Atmosphere 查询，无则返回 0.0）。ρ ≤ 0 跳过。
3. `v = √([+0x140]²+[+0x148]²)`（船速，double）；**v ≤ 1.0 m/s 或总质量 ≤ 1.0 kg 跳过**（`0x1b9264`–`0x1b927c`）。
4. 远块 `0x1330b4`：**F = 0.5 · ρ · v² · A_ship**，其中 `A_ship = [node+0x1b0]`；`a = F / m_total`；方向 `−v̂`（`0x1330b4`–`0x1330f8`）。
5. **按零件质量分摊**：遍历零件，`F_i = m_i · a`，存 Vector2D 后调零件 `vtbl[0x0c]`（ApplyForceToCenter 包装）（`0x133100`–`0x133130`，`0x1b92cc`–`0x1b9304`）。
6. **A_ship 的生成**：`ShipOrbitNode::BuildObjectsList` @ `0x1ba818`——遍历 PartTree，调内部函数 `0x146a10`（未命名局部函数，紧邻 `PartType` 拷贝构造，输入 PartType* 输出 ~0x160 B 详情结构）取每件三个参数 f1=[out+0x140]、f2=[out+0x148]、f3=[out+0x158]；累加 `A += f1·√f2·f3·0.1`（literal @ `0x1bac80` = **0.1**，`0x1ba8cc`），权重和 `W += f1·√f2`；最终 `A_ship = A / W`，**下限 clamp 0.0125**（literal @ `0x1bac88`，`0x1bab28`–`0x1bab40`）。f1/f2/f3 的精确语义**未解**（疑似 尺寸×√面积×零件阻力系数 的加权平均，Cd≈1 量级折进 0.1 系数）。

即：**单一整船等效阻力面积 + 全局 0.5ρv²A + 按质量分摊**，无逐零件 Cd、无遮挡、无 Mach。

### 我们实现

- 密度：`Planet.java:58-67` `ρ = 1.225·surfacePressure·exp(−h/scaleHeight)`，Lua 可覆写（`GameWorld.java:298-304`）。无 0.1 Pa 硬截止（`rho > 1e-9` 判断，`GameWorld.java:1241`）。
- 阻力：`GameWorld.java:1233-1264` **逐零件** `F = 0.5·ρ·v²·Cd·A`，Cd = max(0, 0.75 + type.drag) 或 Lua 绝对值，A = type.width 或 Lua 值，再乘**遮挡系数** `p.dragExposure`（`Ship.java:753-784`，顺流遮蔽扫描）；相对风速 = 船 universe 速度 − 行星速度（含坐标系滚动修正，`GameWorld.java:1244-1247`）。

### 差异与复刻建议

| 项 | SR | 我们 | 建议 |
|---|---|---|---|
| 密度律 | P₀e^(−h/H)/84134.05，0.1 Pa 硬截止 | 1.225·P·e^(−h/H)，无硬截止 | **建议复刻**：加压力硬截止（等价高度截止），防止高空残余密度在 warp 下产生非物理阻力 |
| 阻力粒度 | 整船一个 A，按质量分摊 | 逐零件 CdA + 遮挡 | **不必复刻**（我们更细）；但若追求 SR 手感，**建议**提供"整船等效面积"开关做 A/B |
| 速度阈值 | v ≤ 1.0 m/s 不施加 | speed2 > 0.01（=0.1 m/s） | **建议复刻** 1.0 m/s 阈值（消除贴地蠕动时的数值抖动） |

---

## 4. 引擎推力与燃料

### SR 实现

`EngineObject::PhysicsStep` @ `0x1cd760`（1092 B，每子步）：

1. 调基类 `PartObject::PhysicsStep`；取自己的 b2Body（RigidBody+4）；未激活（`0x1d1e30` = IsActivated）直接返回。
2. 推力水平：`[engine+0x228]`（throttle 0..1）；`[engine+0x28]` 为引擎类型枚举，**==3 时强制 throttle=1.0**（`0x1cd7ec`–`0x1cd808`，类型语义未解，疑固态）。
3. **水下限制**：`IsUnderWater`（`0x1d2590`）为真时：`throttle ×= K`（literal 池 `0x1cdb70`，**未解**确切值——该处反汇编字 `0xf1c00000`/`svclo #0xc33333` 解码不可靠）并把 `[+0x290]` 置 1.0（spool 计时）；计时未归零期间 throttle clamp 到 **0.25**（`vmov.f64 d5, #2.5e-1`，`0x1cd8c4`–`0x1cd8f0`），计时器按 dt 递减。
4. 燃料：`UseFuel(throttle × [+0x20](耗率) × dt)` @ `0x1cd57c`——按 `[engine+0x248..0x24c)` 油箱列表**顺序抽干**（液体路径，逐箱 `0x1dfa14` 查余量、`0x1df974` 扣减）；`[+0x28]==2`（固态？）则**有余量的油箱均摊**。抽不到燃料 → 本步无推力（`0x1cd87c`）。扣燃料后：`[+0x298]` 指向的引擎自身 fixture 的 **m_density 改写为消耗后质量/面积**，随后 `ResetMassData`（`0x1cd73c`–`0x1cd754`）——**发动机随燃料消耗变轻**（油箱同理，见 TankObject）。
5. 推力方向：`angle = body.m_sweep.a + K1 + [+0x230]×[+0x38]`（K1 为安装角常量，literal 池 `0x1cdb74` 未解），cosf/sinf（`0x7f0dc`/`0x7f0d0`）得方向向量。
6. 推力大小：`F = throttle × K2 × [+0x18]`（PartType 推力，K2 literal @ `0x1cdb7c` 未解，疑 1000.0=单位换算）。
7. **施加**：body type==2 时**直接 `m_force += F·dir`**（`0x1cdb38`–`0x1cdb68`）——**作用于质心、零力矩**。没有 gimbal、没有施力点偏移。
8. 火焰特效/音效在 `EngineObject::Update @ 0x1cc3b0`（每帧）：含 `[+0x21c]` 火焰强度插值、振动噪声（sin 叠加）、`0x1ac570` 音效触发。

### 我们实现

- 推力由 Lua（mod/control.lua / physics.lua）驱动，`GameWorld.java:913-936`：Lua 计算的力存入队列，**每个子步重放** `applyForce(fx, fy, px, py, true)`——支持施力点（有力矩）和 gimbal。
- 燃料：Lua 油箱脚本（docs/21）。

### 差异与复刻建议

- SR 主引擎纯质心推力、转向全靠 RCS：**不必复刻**（我们的 gimbal 是功能超集）。但若做"SR 拟真模式"，**建议**加 per-engine `torqueless` 标志走质心路径。
- SR 水下 throttle ×K + 出水 25% 缓启动：**建议复刻**（gameplay 手感项；我们当前水下引擎行为由 Lua 决定，可对照）。
- SR 引擎/油箱 fixture 密度随燃料实时改写 + ResetMassData：**建议复刻**——我们 `Part.updateMass()`（Part.java:284）在燃料变化时更新，但 I 是盒式近似；SR 是几何重算，油箱烧掉后质心偏移更真实。

---

## 5. 物理步细节

### SR 实现

`LocalPhysics::Update` @ `0x198158`（336 B，每帧）：

- 时间累加器：`[+0x30] += frameDt`（double）；目标时间 `[+0x28]`；`d8 = 1/60`（literal @ `0x1982a0` = **0.01666666753590107** double，`vldr [pc,#0x118]` @ `0x198180` 验证）。
- 循环：当 `[+0x28] < [+0x30]` 且剩余步数 > 0：
  1. `ApplyGravity()`（`0x1981a8`）；
  2. `b2World::Step(1/60f, 6, 2)`（`0xa7a44`，`movw/movt 0x3c888889` = 1/60f，`0x1981b4`–`0x1981c0`；r2=6 速度迭代、r3=2 位置迭代）；
  3. `[+0x28] += 1/60`；调 `Runtime::UpdatePhysicsStep(1/60f)` @ `0x1ac0d4`（推进行星自转等，`vtbl[0x18]` on `[Runtime+0x18]`，细节未解）；
  4. 遍历对象列表 `[+0x3c..+0x40)` 调各 `vtbl[0x18]`（**PhysicsStep(1/60)**）。
- 步数上限 r6 = **0x65 = 101**（`0x19817c`）；耗尽后 `[+0x30] = [+0x28]`（丢时间，`0x198290`）。
- 帧末：遍历对象列表调 `vtbl[0x1c]`（**Update(frameDt)**，每帧一次，非每子步）。
- `LocalPhysics::LocalPhysics` @ `0x198420`：b2World 以 **gravity=(0,0)** 构造（`0xa62bc`，栈上两个 0）；`SetContactListener(this+4)`（`0xa64e8`）；构造参数含 double **10000.0**（`Runtime::EnterLocalPhysics` @ `0x1ac96c`：`movw 0x8800/movt 0x40c3` → 0x40C3880000008800 ≈ 10000.0，局部物理范围/尺寸，存储位置未完全定位）。

`PartObject::PhysicsStep` @ `0x1d2350`（172 B，每子步每零件）：

- 先调 RigidBody 的 `vtbl[8]`（= `RigidBody::PhysicsStep @ 0x1d12c8`，把 b2Body 的 xf/角度同步进 double 逻辑位置/速度字段，纯簿记）。
- **延迟爆炸/移除**：`[part+0x1ca]` 置位且未 destroyed → 清位、置 destroyed 位（`0x1c8`）、调 `Explode()`；`[part+0x1c9]` 置位 → 置位 `0x1c8` 并调 `PartTree::0x1d97f8`（从树移除/拆树）。最后调自身 `vtbl[0x70]`（PostPhysicsStep 钩子，基类近空）。
- 即：碰撞/断裂只在 body 上打标志，**实际爆炸/移除统一在下一个子步的 PhysicsStep 执行**，避免在 Box2D 回调里改 world。

`ShipOrbitNode::PhysicsStep`：见 §3（顺序：OrbitNode 基类 → Ship → 零件 PhysicsStep → 连接 PhysicsStep → 阻力）。

### 我们实现

- `GameWorld.java:766-776`：固定 1/60、6/2 迭代、时间累加、步数上限 **16**（SR 是 101）。
- `Ship.java:494+` `checkJointBreaks(invDt)`：每帧一次（`GameWorld.java:795`）。

### 差异与复刻建议

- 步数上限 16 vs 101：**建议复刻**到 ~101（warp 4 × 低帧率时 16 步会丢模拟时间，SR 允许追帧）。
- SR 每子步调零件 PhysicsStep（爆炸延迟一子步执行）：已复刻（单帧三通道断裂）；保持。
- `Runtime::UpdatePhysicsStep`（行星自转推进在子步内）：**建议核对**我们的行星自转/时间推进是帧级还是子步级。

---

## 6. 碰撞处理与爆炸

### SR 实现

`LocalPhysics::BeginContact` @ `0x19791c`（468 B）：

- 取两 fixture 的 userData（`fixture+0x28` → PhysicsObject*）；若一方的 `fixture+0x26`（m_isSensor 字节）与对方不同且均非空，对 sensor 所属对象调 `vtbl[0x34]`（**sensor 穿越回调**——水面/触发器进入）。
- 把 (objA, objB) 追加到 LocalPhysics 的 contactStart 列表（`[+0x48..+0x4c)` 动态数组，`0x1979ac` 起）。

`LocalPhysics::PostSolve` @ `0x197734`（368 B）：

- 若 `[+0x28]`（物理时钟 double）< 0 直接返回。
- 两 fixture userData 相同（同一对象自碰）则跳过。
- 从 b2ContactImpulse（`[impulse+0x10]` = count）取**最大法向冲量** s16（初值 0.0f，literal @ `0x1978a0`）。
- 在 contactStart 列表查 (A,B)/(B,A) 是否本帧新接触 → r8。
- 对两个对象分别调 `vtbl[0x30]` = `OnCollision(other, fixtureA, fixtureB, maxImpulse, isNew)`；再调 Runtime 的 `vtbl[0x24]` 回调（objA, objB, maxImpulse）。

`PartObject::OnCollision` @ `0x1d1534`（216 B）：

- 先调对方 `vtbl[0x14]`；非零走特殊交互分支（`0x1d83e8`/`0x1d84c0`/`0x1dd4b0`——对接捕获相关，部分未解）。
- **冲量损伤**：`impulse > [part+0x60]`（double，挤压阈值）→ 置 `0x1c9`（移除标志）；`impulse > [part+0x68]`（double，爆炸阈值）→ 置 `0x1ca`（爆炸标志）。**单位是冲量（N·s 每子步归一前），不是力**。
- 新接触且 `impulse > 500.0f`（literal @ `0x1d1608`，`0x1d1598`）且未在爆炸 → 生成碰撞特效（`0x1ac6b8`）。
- `PartObject::SetExplodeThreshold` @ `0x1d2288`：写 `[part+0x68]`。唯一调用点 @ `0x16f9ac`，位于 `SatRecoveryRuntime::Initialize`（沙盒/回收场景把零件设为不可爆）。
- `PartObject::Explode` @ `0x1d2298`：未 destroyed 且 `[part+0x14a]`（canExplode）→ 通知 listeners `vtbl[8]`、置 destroyed（`0x1cb`）、`PartTree::0x1d9aa4`（从树摘除）、调 `0x1ac3a0`（在零件位置生成爆炸，强度 = `[part+0x150](explode) × [part+0x70]`），最后 `vtbl[0x6c]`（OnExploded 虚函数）。

`PartObject::OnEnterWater` @ `0x1d1e84`（476 B）——入水伤害：

- 入水速度 v：**v > 90.0f**（literal @ `0x1d2054`）→ 可爆件置爆炸标志（0x1ca）、不可爆件置移除标志（0x1c9）；
- **25.0 < v ≤ 90.0** → 只置移除标志（零件沉没消失）；v ≤ 25.0 → 安全（`0x1d2018`）。
- 溅落冲击：强度 = clamp(v / 50.0f, 0.1, 1.0) × clamp(质量/10, 0.25, 10)（literals @ `0x1d2058`=50.0、@ `0x1d205c`=0.1），调 `0x1a9260`（水花/冲击波效果）。

### 我们实现

- `GameWorld.java:194-199`：ContactListener `beginContact → crush(c, true)`、`postSolve → crush(...)`。
- `Ship.java:483-501`：SR 式断裂（reactionForce/Torque + 角度通道）。
- 爆炸阈值：joints.lua / PartType，语义为力（kN）。

### 差异与复刻建议

- SR 碰撞损伤用 **PostSolve 最大法向冲量** 对双阈值（挤压消失 / 爆炸）：**建议复刻**——查我们的 `crush()` 是否同样用 postSolve 冲量（beginContact 里没有冲量数据，若我们在 beginContact 判定会系统性偏严）。
- SR 入水 90/25 m/s 双阈值：**建议复刻**（常数简单、手感明确）。
- 爆炸延迟到下一子步 PhysicsStep 执行（§5）：**必须复刻**（在 Box2D 回调内直接拆 body/joint 会崩；我们若已延迟则忽略）。

---

## 7. 地形

### SR 实现

`TerrainSection::CreatePhysics` @ `0x1c3a34`（584 B）：

- 一个 section 建**一个 static body**（BodyDef.type = 0，`0x1c3a70`；位置 0；其余同零件 BodyDef）。
- 遍历高程采样表 `[+0x7c..0x80)`，**每相邻两个采样点生成一个 4 顶点四边形**（b2PolygonShape::Set(verts, 4)，`0x1c3b30`/`0xa26e4`；顶点 = 两点的地表高程与底部包络，减去 section 原点 `[+0x68/+0x70]`）——与我们"梯形块"同构。
- FixtureDef：**friction = 0.85f**（`0x1c3c14: 0x3f59999a`）、restitution=0、density=0、isSensor=0、**categoryBits=0x0001、maskBits=0xFFFF**（`0x1c3c04`/`0x1c3c10`）、groupIndex=0。
- 加载：`Terrain::EnterLocalPhysics` @ `0x1c144c` 只记录 lp 指针；`Terrain::Update(pos)` @ `0x1c1d60`（1156 B，每帧）按船位置算角度 atan2 与距离 r：
  - r > `[terrain+0x20]`（卸载半径，运行时值）→ 不加载；
  - 在 `[angle−[+0x30], angle+[+0x28]+const]` 角度区间按 `[+0x28]` 步进扫描（区间/步长为行星配置的运行时 double，静态不可知）；
  - 每个角度找包含它的 section（`TerrainSection::0x1c4818` = 角度包含判定），没有则 `CreateTerrainSectionForAngle` @ `0x1c14b0` **懒创建**；
  - section 已存在且 `r < [terrain+0x18] + sectionOuterRadius + 250.0`（literal @ `0x1c2198` = **250.0**，`0x1c1e8c`–`0x1c1eac`）且 lp 已激活 → 调 section `vtbl[0x28]`（CreatePhysics）；`ExitLocalPhysics` @ `0x1c1454` 遍历所有 section 调 `vtbl[0x2c]`（DestroyPhysics）。
- 常量池另有 1000.0（@ `0x1c21a0`，用途未解）。

### 我们实现

- `TerrainSystem.java:49,86`：**10 Hz 窗口管理**（REFRESH_S=0.1），梯形块 **kinematic** body；`TerrainSystem.java:271` 起有立即刷新旁路。

### 差异与复刻建议

| 项 | SR | 我们 | 建议 |
|---|---|---|---|
| body 类型 | static | kinematic | **建议复刻** static（kinematic 与 static 对 dynamic 的接触响应等价，但 static 省去 Box2D 的扫掠/速度处理，且与 SR 抖动行为一致） |
| 加载频率 | 每帧角度扫描 + 250 m 距离余量 | 10 Hz 定时 | **建议复刻**按距离/角度触发（我们已有立即刷新旁路，可作为主路径） |
| 地形摩擦 | 0.85 | lua 可配 | 对照即可 |

---

## 8. 浮力与水

### SR 实现

`PartObject::SimulateWater` @ `0x1d2e20`（912 B，每子步；调用点在水 manager 的步进内）：

- 从 Runtime 取水体信息结构（`0x1ac390` + `0x1aa0e4`），若 `[info+0x109]`（在水域标志）为 0 直接返回。
- 水面判定：r_part = |零件位置|，r_water = info 半径；`r_water > r_part` 即水下。入水瞬间（表面 1 m 带内，`r_water − 1.0 − r_part ≤ 0` 判定）触发一次 `vtbl[0x60]`（OnEnterWater 虚函数），出水调 `vtbl[0x64]`（OnExitWater），标志 `[part+0x1fa]`。
- **水阻尼**（改写 body 的 damping 字段！）：
  - `submersion s = clamp((r_water − r_part) / (0.5·([part+0x150]+[part+0x158])), 0, 1)`（分母 = (buoyancy+height)/2，即半高）；
  - `body.m_linearDamping（[+0x84]）= 2·s`（封顶 2.0），`body.m_angularDamping（[+0x88]）= 0.5·s`（封顶 0.5）（`0x1d3060`–`0x1d3090`）；离开水时**清零**（`0x1d2f6c`–`0x1d2f80`）。
- **浮力**（body dynamic 时，直接写 m_force）：
  `F = [part+0x150](buoyancy) × [part+0x158](体积/高度) × s × [part+0x178](零件质量) × [water+0x110](水体系数) × [water+8](重力) `，方向 = 行星中心向外的单位向量（`0x1d30b4`–`0x1d3110`）。
  - 因含质量因子，**浮力产生的是与质量无关的加速度**；PartList.xml 的 `buoyancy="0.5"/"1.0"` 即 `[part+0x150]`。
- 水下对引擎/降落伞另有联动（§4、§10）。

### 我们实现

- `GameWorld.java:1267-1278`：`fb = waterDensity · (w·h) · max(0,type.buoyancy) · submersion · |g|`，沿径向向外 `applyForceToCenter`；submersion = min(1, (radius−r)/max(1,height))。

### 差异与复刻建议

- 公式结构一致（排水体积×密度×g×浸入比），SR 多乘质量（等效加速度）且用水体自带系数。**不必复刻**（语义等价，参数不同）。
- SR 的**线性/角阻尼随浸入比渐变（2.0 / 0.5 封顶）**：**建议复刻**——这是水上漂浮稳定性的主要来源；我们目前 float 行为靠 lua/未复刻阻尼，水中姿态可能偏"硬"。
- SR 入水速度伤害 90/25 双阈值（§6）：**建议复刻**。

---

## 9. 轨道模式切换

### SR 实现

- 范围常量：**10000.0 m**——`Runtime::EnterLocalPhysics` @ `0x1ac928` 以 double 10000.0 构造 LocalPhysics；`ShipOrbitNode::EnterPhysicsIfCloseToPlayerShip` @ `0x1b9c28`：**非玩家船与玩家船距离 < 10000.0（literal @ `0x1b9d38`）才为它创建局部物理**（`0x1b9d14`–`0x1b9d24`），否则 `DestroyPhysics` @ `0x1b96d8`（销毁所有零件 body，回到纯轨道外推）。
- 进入链路：`Runtime::EnterLocalPhysics` → RecenterCoordinateSystem → new LocalPhysics → 存 `[Runtime+0x98]` → 对 `[Runtime+0x18]`（OrbitNode 根）调 `vtbl[0x38]`（OnEnterLocalPhysics）→ `OrbitNode::OnEnterLocalPhysics` @ `0x1a75d8` 遍历子节点递归下发 → `ShipOrbitNode::OnEnterLocalPhysics` @ `0x1b9d48` → CreatePhysics（逐零件建 body）。
- 退出：`OrbitNode::OnExitLocalPhysics` @ `0x1a7554` 递归调 `vtbl[0x3c]`；`ShipOrbitNode::OnExitLocalPhysics` = `DestroyPhysics`；`Planet::OnExitLocalPhysics` @ `0x1a9c18` + `Terrain::ExitLocalPhysics` 销毁地形 body。
- SOI 切换：`ShipOrbitNode::CheckCurrentSphereOfInfluence` @ `0x1b9854`（976 B）/ `ChangeSphereOfInfluence` @ `0x1b9760` / `SphereOfInfluenceRadius` @ `0x1b8978`——在 `ShipOrbitNode::Update`（`0x1b9e20`，1596 B，每帧）里做开普勒外推与 SOI 检测（轨道根数计算在此函数与其调用的 Orbit 类内，本次未展开，标注未解）。
- 恢复：重新进入时由零件的 RigidBody 逻辑位置/速度（double 簿记，与轨道外推同源）重建 body，保证位置连续。

### 我们实现

- `GameWorld.java:830,1009,1117`：rails 船用 velocity-Verlet + Σμ/r² 积分，非活动船 `integrateRails`（`Ship.java`）；活动船始终有 body。

### 差异与复刻建议

- SR 双模式（局部 Box2D ↔ 开普勒外推）以 10 km 为界，非玩家船按与玩家距离懒加载：**建议复刻**距离门控思想（我们已对非活动船 rails 化）；10 km 界限可作为我们"活动物理半径"的基准参考。
- SOI 切换检测每帧做：**已等价实现**（nearestPlanetTo），无需动。

---

## 10. 其它零件

### RCS（`RcsObject::PhysicsStep` @ `0x1dcad8`，540 B；点火器 `0x1dc830`）

- 每子步衰减喷口计时器 `[+0x230..]`（每项 16 B：角度/位置/计时）。
- 从 Ship 控制量读三通道：旋转（`0x1b8870`）、平移 X（`0x1b8838`）、平移 Y（`0x1b8800`）（经 `0x1db634` = Ship::GetControls 类）；按输入符号与零件镜像标志（`IsFlippedX @ 0x1d1e20`）决定点火方向（方向索引 0/1/2）。
- 点火 `0x1dc830`：先扣燃料（`0x1dc764` = RcsObject 燃料扣减，列表抽油箱同引擎）；**推力 = |input| × 1000.0（literal @ `0x1dcac0`）× [part+0x18]（PartType RCS 推力）**；方向角 = body 角 + 喷口角 + **π**（literal @ `0x1dcac4` = 3.1415927）；**直接写 m_force（+0x4c/0x50）并写 m_torque（+0x54）：τ = Fx·(py−cy) − Fy·(px−cx)**（`0x1dca50`–`0x1dcab0`，杠杆臂相对 body sweep 中心 `[+0x2c/0x30]`）——RCS 是唯一产生力矩的推进器。燃料常量 0.045 / 0.065（@ `0x1dcac8/0x1dcacc`，语义未解）。

### 轮子（`WheelObject::PhysicsStep` @ `0x1e007c`）

- 有 revolute joint（`[+0x204]`）且激活：读船前进输入（`0x1b8800`），**SetMotorSpeed = input × 20.0 rad/s**（`0xb1710`，`vmov.f32 s15, #20.0`，`0x1e00e8`）；无输入 → EnableMotor(false) 后 SetMotorSpeed(0)。创建时 MaxMotorTorque=750 N·m（§1）。推进纯靠轮胎与地面的接触摩擦（friction 0.95）。

### 降落伞（`ParachuteObject::PhysicsStep` @ `0x1d00b0`，1216 B）

- 未开伞且 `[+0x228]==0`：调 `0x1cfdfc` 建伞 body（dynamic，含 BodyDef 常规参数）+ 与主 body 的 joint（`[+0x224]`，从 LocalPhysics 的 CreateRopeJoint/CreateDistanceJoint 看为 rope/distance，确切类型未解）。
- 开伞后每子步：ρ 取 `Runtime+0x88/0x90` 缓存值（每帧由 Runtime/Atmosphere 更新）；**F = 0.5·ρ·v²·A·k**（A、展开系数 k 来自 `[+0x204]/[+0x210]` 与是否半开 `[+0x20d]`：半开时 A′=(A−0.1)×8.0；v≤某值用 0.001 密度下限 @ `0x1d0540`），**力上限 ±100000 N**（literals @ `0x1d0548`=−1e5、@ `0x1d0564`=1e5），逆风方向直接写伞 body 的 m_force（`0x1d044c`）。
- 伞 body 线阻尼 = min(√ρ·9·k, 9.0)（`0x1d03e4`–`0x1d041c`）；伞体角度对齐来流：`atan2(vy,vx) + π/2`（d @ `0x1d0550` = 1.5707963）调 `b2Body::SetTransform`（`0xa38fc`）。
- **断绳**：伞 body 与主 body 距离² > **2025（=45² m²，literal @ `0x1d0568`）** → DestroyJoint + DestroyBody + 零件置移除（`0x1d04a4`–`0x1d04d8`）。

### 着陆腿（`LanderObject::PhysicsStep` @ `0x1ce170`，940 B）

- 展开动画：`[+0x204]`（角度）按 `dt × [+0x98]/[+0xa0] × (π/180)`（d @ `0x1ce508` = **0.0174532925 = π/180**）推进，到限位置 `0x20c` 完成位；收起重置。
- 展开完成后动态建腿 fixture（SetAsBox，腿长 = `[+0x208]` 伸长量 ×0.9（f @ `0x1ce514`）等）：**friction = 0.75f（`0x3f400000`）**、density = (GetMass/面积)×(1+腿质量比)（`0x1ce4a4`–`0x1ce4c8`）、**groupIndex = −1**（`strh 0xffff`，不与本体碰）、category 2/mask 3。收腿时销毁 fixture（`0xa34f8`）。
- 即：SR 的着陆腿 = **展开后才有的高摩擦 fixture + 负碰撞组**，没有悬挂/弹簧。

### 分离器（DetacherObject）

- `Activate` @ `0x1cb238`：取父连接（`0x1d7a50`），按两侧 attach 点几何算分离方向 atan2（`0x7f43c`）存 `[+0x208]`；存 `[+0x220]` = 对侧 PartTree；调 `PartTree::0x1d97f8` **拆树**；写入常量 **[+0x218] = 2500.0**（0x40A3880000008800，疑分离 impulse/断裂力）、**[+0x210] = 0.25**（0x3FD0000000000000）。
- 分离后本件不再建 body（CreatePhysics 查 `[+0x240]`）；`BodyCreated @ 0x1cb0e4`：建自身小盒 fixture（SetAsBox，w/2=`[+0x150]·0.5`，h/2=`[+0x158]·0.5`，friction 0.75，density=GetMass/面积，category 2/mask 3）。
- 分离冲击在 `DetacherObject::Update @ 0x1cb368`（未展开，264 B）。
- **没有爆炸力**，纯拆树 + 连接断裂。

### 对接（DockPortObject / DockConnectorObject）

- `DockPortObject::PhysicsStep` @ `0x1cc0bc`：有目标口 `[+0x228]` 且双方存活、分属不同船（`0x1d8548`）→ 冷却计时 `[+0x230]` 递减；条件满足调 Runtime `vtbl[0x20]` = `Runtime::CreateDockingConnection(treeA, treeB, port, true)`（`0x1ad2e8`），置 `[+0x22c]` 已对接；`0x1ac5e8`（参数 0.25f）为对接吸附特效/磁吸，细节未解。
- `DockConnectorObject::PhysicsStep` @ `0x1cb980`：捕获窗口（`[+0x228]` 倒计时 > 0）内对自身 body **持续施加恒定吸力** `[+0x230, +0x238]`（写 m_force）。
- `PartConnection::CreateDockingConnection` @ `0x1d06e4`：新建 PartConnection（0x68 B）：**角度阈值 [+0x44] = π/4**（0x3F490FDB = 0.7853981 rad）、**力阈值 [+0x48] = 2500.0 N、[+0x4c] = 6250000.0 = 2500²**（已核实 float 精确值）、`[+0x60]` = 1（对接连接标志）。

### 连接断裂语义终验（`PartConnection::PhysicsStep` @ `0x1d0a30` + 构造 @ `0x1d0c9c`）

- 构造：`[+0x44] = min(两侧 attach 的 [+0x50])`（角度阈值 rad）；`[+0x48] = min(两侧 [+0x58])`（= PartList 的 explode，1500/2500/3000 N）；`[+0x4c] = [+0x48]²`。
- 每子步：两 body 都在 → `Δang = (aA − aB) − [+0x40]`（创建时初始角差），经 `0x7f2b0`（角度归一/fabs）后 **> [+0x44] → 断**；否则取 `joint->GetReactionForce(inv_dt)`（inv_dt 来自全局 float，应为 60）算 |F|²，**> [+0x4c] → 断**。断裂 → `0x1d088c`（DestroyJoint 并拆树，`0x1d97f8`）。与既有结论一致。

---

## 11. 坐标系重置（Recenter）

### SR 实现

- `Runtime::RecenterCoordinateSystem` @ `0x1ac810`：取船位置（`0x1ac184`），偏移 = 船位 − 当前中心（`[Runtime+0x20/0x28]`）；对 `[Runtime+0x18]`（OrbitNode 根）调 `vtbl[0x48]`（Recenter），对 `[+0xbc]`、`[+0xb8]`（相机/粒子等，vtbl[0] Recenter）、`[+0x98]`（LocalPhysics，vtbl[0]）递归下发；最后把中心改写为船位。
- 调用点：`EnterLocalPhysics`（`0x1ac93c`）与 **`Runtime::Update` 每帧**（`0x1af178`）——即**每一帧都把原点重置到船上**，从根本上消除原点漂浮（而不是阈值触发）。
- 各级 Recenter 实现：`LocalPhysics::Recenter @ 0x19771c`（存新中心）、`RigidBody::Recenter @ 0x1d1920`（body SetTransform 平移）、`PartObject::Recenter @ 0x1d19a4`、`TerrainSection::Recenter @ 0x1c3ef8`（1788 B，重建顶点）等——全场景对象统一平移。

### 我们实现

- 浮动原点：`GameWorld.java:606` 起（maxTranslation clamp 处理）、`origin/originVel` 滚动（`GameWorld.java:1180-1197` 区域）。

### 差异与复刻建议

- SR **每帧无条件 recenter 到船**：**建议复刻**——若我们是按阈值/按距离触发，改为每帧（或每子步）以船为原点可进一步压低 float 误差；代价与 SR 相同（全对象平移，本就要做）。

---

## 12. 优先级总表

| # | 系统 | 差异点 | 级别 | 出处（SR 地址 / 我们位置） |
|---|------|--------|------|---------------------------|
| 1 | 碰撞损伤 | PostSolve 最大法向冲量 × 双阈值（消失 `[+0x60]` / 爆炸 `[+0x68]`），延迟一子步执行 | **必须复刻** | `0x197734` / GameWorld.java:194 |
| 2 | 爆炸执行时机 | 回调只打标志，下一子步 PhysicsStep 执行 Explode/移除 | **必须复刻** | `0x1d2350` / Ship.java |
| 3 | 子步上限 | 101 步追帧 vs 我们 16 步丢时间 | 建议复刻 | `0x19817c` / GameWorld.java:772 |
| 4 | 引力唤醒 | 直接写 m_force 并强制唤醒睡眠 body | 建议复刻 | `0x198018` / GameWorld.java:1231 |
| 5 | 密度硬截止 | 气压 < 0.1 Pa → ρ=0 | 建议复刻 | `0x191db0` / Planet.java:61 |
| 6 | 阻力速度阈值 | v ≤ 1.0 m/s 不施加 | 建议复刻 | `0x1b9264` / GameWorld.java:1247 |
| 7 | 水阻尼 | 浸入比驱动 linearDamping≤2.0、angularDamping≤0.5 | 建议复刻 | `0x1d2e20` / GameWorld.java:1267 |
| 8 | 入水伤害 | 90 / 25 m/s 双阈值 | 建议复刻 | `0x1d1e84`（@`0x1d2054`）/ 无 |
| 9 | 地形 body | static + 0.85 摩擦 + 每帧角度扫描 + 250 m 余量 | 建议复刻 | `0x1c3a34` / TerrainSystem.java |
| 10 | 转动惯量 | 几何精确（density=质量/面积 + ResetMassData） | 建议复刻 | `0x1d28b4` / Part.java:295 |
| 11 | 质量随燃料 | 引擎/油箱 fixture 密度实时改写 + ResetMassData | 建议复刻 | `0x1df790` / Part.java:284 |
| 12 | 坐标重置 | 每帧以船为原点 | 建议复刻 | `0x1ac810` / GameWorld.java |
| 13 | 轨道切换 | 10 km 物理半径 + 非玩家船 10 km 距离门控 | 建议复刻 | `0x1ac96c`、`0x1b9d38` / GameWorld.java |
| 14 | 主引擎质心推力/无 gimbal | SR 无 gimbal，转向全靠 RCS（有力矩） | 不必复刻 | `0x1cdb38`、`0x1dca50` / control.lua |
| 15 | 阻力模型 | 整船等效面积按质量分摊 vs 我们逐件+遮挡 | 不必复刻 | `0x1330b4` / GameWorld.java:1250 |
| 16 | N 体引力 | SR 单行星点质量；我们 N 体 + Lua | 不必复刻 | `0x198018` / GameWorld.java:222 |
| 17 | bullet 标志 | SR 不用 bullet | 不必复刻 | `0x1d2c88` / Part.java:128 |
| 18 | 着陆腿悬挂 | 无悬挂，展开后高摩擦 fixture + group −1 | 不必复刻 | `0x1ce170` / Part.java |
| 19 | 轮子 | revolute motor：speed=input×20 rad/s，maxTorque=750，轮胎 friction 0.95，mask 避开船体 | 参考复刻 | `0x1dfcc4`、`0x1e007c` / Part.java:214 |
| 20 | 对接 | 磁吸恒力 + π/4、2500 N 断裂阈值的对接连接 | 参考复刻 | `0x1cb980`、`0x1d06e4` / 无 |

## 附：未解清单（不猜测，留待后续）

1. `ApplyGravity` 中 `0x263820`（.bss double）：归一化保护阈值，静态不可知（运行时写入，疑行星半径）。
2. `PartObject::GetMass` 中全局缩放（GOT→.bss float，@ `0x1d13a4` 上下文）：运行时值，疑质量单位系数 1.0。
3. `ShipOrbitNode::BuildObjectsList` 调用的 `0x146a10` 输出的 f1/f2/f3（[+0x140]/[+0x148]/[+0x158]）精确语义（阻力面积加权项）。
4. 引擎水下系数（literal 池 `0x1cdb70`）与推力换算系数 K2（`0x1cdb7c`）确切值：该常量池区域字解码异常，未能可靠读取。
5. `Runtime::UpdatePhysicsStep @ 0x1ac0d4` 的内部调用链（`0x1aff74`、`0x19c6b0`、`0x1bd750`）。
6. 开普勒轨道根数计算（`ShipOrbitNode::Update @ 0x1b9e20` 与 Orbit 类）未展开。
7. 降落伞连接主/伞 body 的 joint 确切类型（rope vs distance，`0x1cfdfc` 内）。
8. `PartObject::PhysicsStep` 末尾 `vtbl[0x70]` 钩子语义（基类近空，子类用途未查）。
9. `LocalPhysics` 构造的 10000.0 double 参数的确切存储字段与用途（范围判定之外的用途）。
10. DetacherObject 常量 2500.0（`[+0x218]`）与 0.25（`[+0x210]`）的消费点（`DetacherObject::Update @ 0x1cb368` 未展开）。

## 单位公约（round 37 起生效）

DifferentRockets 采用 SR 原生单位，**SR 逆向得到的常量直接落地，禁止任何换算**：

- **质量**：`质量 kg = XML mass × 50`。出处：`PartObject::GetMass` / `TankObject::GetMass`
  共用 float 常量 50.0f（libNativeModule.so `.data:0x2542d8`，经 GOT `0x253a6c` 引用）。
  实现：`PartType.massKg() = massTons * 50.0`。
- **推力**：`推力 N = power × 8500`。出处：double 常量 8500.0（`@0x1cdb78`）。
  实现：`mods/engine-*.lua` / `ion-0.lua` 的 `thrust = part:getEnginePower() * 8.5e3 * frac`。
- **燃料质量**：`燃料 kg = fuel × 0.1`（SR 满油箱 6000 fuel = 12 XML 质量单位 = 600 kg）。
  实现：`Part.updateMass()` 的 `dryMassTons * 50.0 + fuel * 0.1`。
- **冲击阈值**：PartList.xml `explode="N"` 为法向冲量 N·s **原值原生适用**
  （常见 1500/2500），`PartType.impactDestroy = explode × 0.36` 仍是估计值。

迁移规则（round 37 一次性执行）：所有显式力/力矩常量 ÷10，所有质量 ×0.1，
速度/角度/加速度不动 —— 迁移前后动力学完全一致（TWR、48 s 燃烧、
20 m/s 轮地极速、断裂余量均不变）。
