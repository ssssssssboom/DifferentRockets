# API 参考：Mod 桥接与资源系统

> 目标读者：刚接手 DifferentRockets 项目的中国开发者。
> 覆盖范围：`core/src/com/differentrockets/game/` 下的 `ModManager.java`、`ModApi.java`、`LuaScript.java`、`JointScript.java`、`PhysicsScript.java`、`FlameScript.java`、`FlameFx.java`，以及 `core/src/com/differentrockets/util/Res.java`。
> 所有事实均来自上述源文件本身，引用格式为 `文件名:行号`（行号对应各自文件）。

## 0. 总览：Mod 系统如何工作

游戏的 Lua 可编程体系基于 **luaj**（`org.luaj.vm2`，`ModManager.java:6-11` 的 import 可见），整体分层如下：

| 层 | 类 | 干什么的 |
|---|---|---|
| 资源落盘 | `util/Res.java` | 把内置 lua / 贴图 / XML 拷到玩家可写目录（Android 为 `/sdcard/DifferentRocket/`，见 `Res.java:8-9`），并做版本化自动更新 |
| 单文件脚本加载 | `LuaScript.java` | flame.lua / terrain.lua / physics.lua / joints.lua 这类“全局唯一”脚本的加载、1 秒粒度热重载（`LuaScript.java:11-18` 类注释） |
| 零件脚本加载 | `ModManager.java` | 每种零件类型一个 `<typeId>.lua`，**每个零件实例一个独立 Lua 状态**（`ModManager.java:18-21` 类注释） |
| Lua 看到的零件 API | `ModApi.java` | 作为钩子函数里的 `part` 参数暴露给 Lua（`ModApi.java:7-11` 类注释） |
| 专项桥接 | `JointScript` / `PhysicsScript` / `FlameScript` | 分别把焊接 joint 求解、物理定律（重力/大气/转向增益）、引擎火焰渲染桥到 joints.lua / physics.lua / flame.lua |
| 粒子池 | `FlameFx.java` | 引擎尾焰的池化粒子系统，由 Lua 通过 `flame.emit` 驱动（`FlameFx.java:9-13` 类注释） |

**统一的容错模式**：所有桥接类都遵循“Lua 脚本缺失或报错 → 记一次日志 → 回退到 Java 内置行为”的策略（如 `JointScript.java:18-20`、`PhysicsScript.java:19-20`、`FlameScript.java:30-31` 的类注释）。

**统一的文件解析顺序**：玩家 mod 目录优先，内置 assets 兜底（`ModManager.java:46-74`、`LuaScript.java:83-102`、`Res.java:568-590`）。

---

## 1. ModManager.java（132 行）

### 1.1 职责

按零件类型加载 `<typeId>.lua`，为**每个零件实例**创建独立的 Lua globals/state。首次运行时玩家目录中的默认脚本由 `Res` 负责拷贝，本类只负责“读 + 编译 + 调钩子”。支持的钩子：`onLoad(part)`、`onUpdate(part, dt)`、`onStage(part)`（`ModManager.java:17-21` 类注释）。

### 1.2 重要字段

| 字段 | 类型 | 位置 | 说明 |
|---|---|---|---|
| `modDir` | `private static FileHandle` | `ModManager.java:24` | 玩家 mod 目录，由 `Res.modDir()` 解析（`ModManager.java:35`） |
| `scriptSourceCache` | `private static final Map<String, String>` | `ModManager.java:25` | typeId → 脚本源码的缓存；找不到脚本时缓存 `null` 值 |
| `initialized` | `private static boolean` | `ModManager.java:26` | 防止 `init()` 重复执行 |
| `PID_LIB` | `private static final String` | `ModManager.java:86-100` | 内嵌的 Lua PID 控制器库源码，注入到每个零件状态（详见 1.3.5） |

### 1.3 逐函数

#### `private ModManager()` — `ModManager.java:28`
- 功能：私有构造，纯静态工具类。
- 参数/返回值：无。

#### `public static void init()` — `ModManager.java:30-37`
- 功能：一次性初始化。解析玩家 mod 目录（`Res.modDir()`），打日志记录路径与是否存在。
- 要点：幂等（`initialized` 守卫，`ModManager.java:31-32`）；注释明确说明“内置 assets 是兜底来源”（`ModManager.java:33-34`）。

#### `public static void reset()` — `ModManager.java:40-44`
- 功能：资源重载后调用——清空源码缓存、重置 `initialized` 并重新 `init()`。
- 要点：玩家改了脚本后想全量生效，走这里。

#### `private static String scriptFor(String typeId)` — `ModManager.java:46-74`
- 功能：取某零件类型的脚本源码（带缓存）。
- 参数：`typeId` 零件类型 id（如 `"engine-0"`）。
- 返回值：脚本源码字符串；找不到返回 `null`（也会缓存，避免重复 IO）。
- 要点：
  - 查找顺序：玩家 mod 目录 `<modDir>/<typeId>.lua`（`ModManager.java:50-57`）→ 内置 `mods/<typeId>.lua`（`ModManager.java:58-67`）。
  - 命中后通过 `LuaScript.versionOf(src)` 打出版本号与来源（external/builtin）日志（`ModManager.java:68-71`）。
  - 读取异常被静默吞掉并继续走下一来源（`ModManager.java:56, 65`）。

#### `PID_LIB`（字符串常量，非函数）— `ModManager.java:86-100`
- 功能：注入到每个零件 Lua 状态的全局 `pid` 库。用法（`ModManager.java:77-85` 注释）：
  ```lua
  local ctl = pid.new(kp, ki, kd)                  -- 通常在 onLoad 里
  local rate = ctl:update(target, current, dt)     -- 控制输出
  ```
- 要点：
  - 积分限幅 ±30（`ModManager.java:92`）。
  - **微分作用在测量值上而非误差上**（derivative on measurement），避免目标值阶跃时踢执行器（`ModManager.java:94-96` 注释）。
  - 增益来自 physics.lua（`part:physicsNumber("gimbal", "kp")` 等），玩家不用改引擎脚本即可调参（`ModManager.java:81-84` 注释）。
  - `pid` 是普通 Lua 表，玩家脚本甚至可以整体替换它（`ModManager.java:83-84` 注释）。

#### `public static Globals createState(String typeId)` — `ModManager.java:103-116`
- 功能：为一个零件实例创建全新的 Lua 状态。
- 参数：`typeId` 零件类型 id。
- 返回值：`Globals`；无脚本或编译失败返回 `null`。
- 要点：
  - 每次调用都是 `JsePlatform.standardGlobals()` 新建状态（`ModManager.java:107`），这就是“每个零件实例独立 lua 状态”的实现点。
  - 先注入 `PID_LIB`（chunk 名 `"pid.lua"`），再加载零件脚本本体（chunk 名 `<typeId>.lua`）（`ModManager.java:108-110`）。
  - `LuaError` 只记日志不抛出（`ModManager.java:112-115`），单个坏脚本不会炸游戏。

#### `public static void callHook(Globals g, String name, ModApi api, double dt)` — `ModManager.java:118-131`
- 功能：调用某零件状态里的钩子函数。
- 参数：
  - `g`：该实例的 Lua 状态，`null` 直接返回；
  - `name`：钩子名（`"onLoad"` / `"onUpdate"` / `"onStage"`）；
  - `api`：暴露给 Lua 的 `part` 对象（即 `ModApi`，通过 `CoerceJavaToLua.coerce` 注入，`ModManager.java:124, 126`）；
  - `dt`：仅 `onUpdate` 使用的帧间隔。
- 返回值：无。
- 要点：
  - 钩子不存在或不是函数时静默跳过（`ModManager.java:120-121`）。
  - 只有 `onUpdate` 会传第二参数 `dt`（`ModManager.java:123-124`），其余钩子只传 `part`。
  - 运行时 `LuaError` 记日志后继续（`ModManager.java:128-130`），单帧脚本崩溃不影响游戏主循环。

---

## 2. ModApi.java（270 行）

### 2.1 职责

暴露给 Lua 脚本的 `part` 对象本体。所有位置/速度都在**物理坐标系**（米、y 轴向上、相对于当前活动 origin）；行星位置也在同一坐标系暴露（`ModApi.java:7-11` 类注释）。由于 luaj 的 `CoerceJavaToLua` 会把 public 方法全部暴露，**本类的每个 public 方法都是 Lua 可见方法**，2.3 节逐个列出。

### 2.2 重要字段

| 字段 | 类型 | 位置 | 说明 |
|---|---|---|---|
| `part` | `public final Part` | `ModApi.java:13` | 被包装的 Java 零件实例；Lua 侧不直接可见字段，只通过方法访问 |

### 2.3 逐函数（含全部 Lua 可见方法）

#### 内部辅助（Lua 不可见）

**`private GameWorld world()`** — `ModApi.java:17`
- 功能：取当前世界（`part.ship.world`）。
- 返回值：`GameWorld`。

**`private double universeX()`** — `ModApi.java:70`
**`private double universeY()`** — `ModApi.java:71`
- 功能：本零件的宇宙坐标 = `world.origin + body position`（body 为空时取 0）。
- 返回值：米。

**`private static double optJointNum(LuaTable t, String key)`** — `ModApi.java:233-236`
- 功能：从 Lua 表读可选数值，非数字返回 `Double.NaN`（表示“未设置/继承”）。
- 参数：`t` Lua 表；`key` 键名。
- 返回值：数值或 `NaN`。

#### 构造

**`public ModApi(Part part)`** — `ModApi.java:15`
- 功能：包装一个零件实例。

#### identity 分组

**`public String getTypeId()`** — `ModApi.java:20`
- 功能：零件类型 id（如 `"engine-0"`）。
- 返回值：`part.type.id`。

**`public String getName()`** — `ModApi.java:21`
- 功能：零件显示名。
- 返回值：`part.type.name`。

**`public String getType()`** — `ModApi.java:22`
- 功能：零件类型分类字符串。
- 返回值：`part.type.type`。

**`public int getGroup()`** — `ModApi.java:24`
- 功能：激活组编号，0 = 无，1..8（`ModApi.java:23` 注释）。
- 返回值：`part.group`。

**`public void setGroup(int g)`** — `ModApi.java:25`
- 功能：设置激活组，自动 clamp 到 0..8。

#### own physics 分组

**`public double getX()`** — `ModApi.java:28`
**`public double getY()`** — `ModApi.java:29`
- 功能：body 位置（物理系，米）；body 为 null 时返回 0。

**`public double getVelocityX()`** — `ModApi.java:30`
**`public double getVelocityY()`** — `ModApi.java:31`
- 功能：body 线速度分量；body 为 null 时返回 0。

**`public double getAngle()`** — `ModApi.java:32`
- 功能：body 角度，**弧度**（注释明确标注，`ModApi.java:32`）。

**`public double getAngularVelocity()`** — `ModApi.java:33`
- 功能：body 角速度。

**`public double getMass()`** — `ModApi.java:34`
- 功能：body 质量。

**`public void applyForce(double fx, double fy)`** — `ModApi.java:36-38`
- 功能：对质心施加力（`applyForceToCenter`，会唤醒 body）。

**`public void applyForceAt(double fx, double fy, double localX, double localY)`** — `ModApi.java:41-45`
- 功能：在零件局部偏移处施加力。
- 参数：`localX/localY` 为零件局部系下的米（`ModApi.java:40` 注释），内部经 `getWorldPoint` 转世界点。

**`public void applyTorque(double t)`** — `ModApi.java:47-49`
- 功能：施加力矩（唤醒 body）。

#### ship / world 分组

**`public double getShipX()`** — `ModApi.java:52`
**`public double getShipY()`** — `ModApi.java:53`
- 功能：宇宙坐标下的船/零件位置 = `origin + body position`。

**`public double getShipVelocityX()`** — `ModApi.java:54`
**`public double getShipVelocityY()`** — `ModApi.java:55`
- 功能：船的宇宙速度（`part.ship.getUniverseVel()`），与浮点原点无关的绝对速度。

**`public int getPlanetCount()`** — `ModApi.java:57`
- 功能：世界行星数量。

**`public String getPlanetName(int i)`** — `ModApi.java:58`
- 功能：第 i 颗行星的名字。

**`public double getPlanetRadius(int i)`** — `ModApi.java:59`
- 功能：第 i 颗行星半径（米）。

**`public double getPlanetX(int i)`** — `ModApi.java:60`
**`public double getPlanetY(int i)`** — `ModApi.java:61`
- 功能：第 i 颗行星在物理系中的位置（`planet.pos - origin`），与零件坐标直接可比。

**`public String getCurrentPlanet()`** — `ModApi.java:64`
- 功能：当前主天体名（最近行星，即 SOI 意义上的主导天体，`ModApi.java:63` 注释）；无则返回空串。

**`public double getGravityX()`** — `ModApi.java:67`
**`public double getGravityY()`** — `ModApi.java:68`
- 功能：本零件处的重力加速度向量（m/s²，`ModApi.java:66` 注释）。

**`public double getAltitude()`** — `ModApi.java:74`
- 功能：当前行星地表以上的高度（米，`ModApi.java:73` 注释）。

**`public double getAtmoDensity()`** — `ModApi.java:75`
- 功能：本位置大气密度。

**`public double getAtmoPressure()`** — `ModApi.java:76`
- 功能：本位置大气压。

**`public boolean isInWater()`** — `ModApi.java:78`
- 功能：是否在有水的行星海平面以下（`ModApi.java:77` 注释）。

**`public boolean isInSunlight()`** — `ModApi.java:80`
- 功能：是否被日照（未被任何行星遮挡太阳，`ModApi.java:79` 注释）。

#### input 分组

**`public double getTurn()`** — `ModApi.java:84`
- 功能：转向指令，-1 = 左转，0，+1 = 右转（`ModApi.java:83` 注释）。

**`public double getThrottle()`** — `ModApi.java:86`
- 功能：油门 0..1。

**`public boolean isStageActivated()`** — `ModApi.java:88`
- 功能：仅在分级激活的那一帧为 true（`ModApi.java:87` 注释）。配合 `onStage` 钩子使用。

**`public int getStage()`** — `ModApi.java:90`
- 功能：船当前所处的级序号。

#### steering 分组（round 12：SteeringIO + control.lua）

**`public double getTargetHeading()`** — `ModApi.java:94`
- 功能：目标航向（弧度，body-angle 约定：0 = 机头朝“上”，逆时针为正，`ModApi.java:93` 注释）。

**`public void setTargetHeading(double rad)`** — `ModApi.java:96`
- 功能：命令一个航向（ring 语义），会激活 ring 模式（`ModApi.java:95` 注释）。

**`public double getShipHeading()`** — `ModApi.java:98`
- 功能：当前船航向（与 target 同一约定）。

**`public double getTurnCommand()`** — `ModApi.java:100`
- 功能：最新转向指令 -1..1（与 `getTurn()` 同值，`ModApi.java:99` 注释）。

**`public LuaTable getSteering()`** — `ModApi.java:110-116`
- 功能：返回原始转向输入状态的 Lua 表，镜像自 `SteeringIO`（`ModApi.java:102-109` 注释）：
  - `active` bool — ring 模式开（引擎追踪 targetRad）；
  - `buttonTurn` int — 按住转向按钮时为 -1/0/+1（覆盖 ring）；
  - `targetRad` num — ring 目标航向（弧度）。
- 返回值：新建的 `LuaTable`。
- 要点：引擎控制律在 `mod/control.lua` 的 `controlLaw(part)`；角度误差必须 wrap 到 `[-pi, pi]`（`ModApi.java:107-108` 注释）。

#### mod 文件读取

**`public String readModText(String name)`** — `ModApi.java:123-138`
- 功能：读取一个 mod 文件文本（玩家目录优先，内置兜底——与零件脚本同一解析顺序，`ModApi.java:118-122` 注释）。引擎脚本用它把 control.lua 加载进自己的 Lua 状态。
- 参数：`name` 纯文件名。
- 返回值：文件文本；失败/不存在返回 `null`。
- 要点：**有路径穿越防护**——空名、含 `/`、`\`、`..` 一律返回 null（`ModApi.java:124-125`）。

#### fuel network 分组

**`public double getFuelTotal(int fuelType)`** — `ModApi.java:142`
- 功能：全船燃料网络中某类燃料的总量（`ModApi.java:141` 注释）。

**`public double getFuelCapacity(int fuelType)`** — `ModApi.java:143`
- 功能：全船某类燃料总容量。

**`public double drainFuel(int fuelType, double amount)`** — `ModApi.java:153`
- 功能：为本零件抽取最多 `amount` 单位燃料；返回实际抽到的量。
- 要点（`ModApi.java:144-152` 注释，**这是燃料系统的核心规则**）：
  - 液体燃料（type 0）只能来自通过 fuelLine 连接点与本零件连通的油箱——被无 fuelLine 点的零件（pod、detacher、电池）隔开的油箱**不会**供给本零件；
  - 单组元（type 1）与电力（type 2）全船共享；
  - 固体（type 3）只烧消费者自己的油箱。

**`public double transferFuel(int fuelType, double amount)`** — `ModApi.java:155`
- 功能：在本零件供给范围内的油箱间转移燃料（液体：fuelLine 网络；单组元/电力：全船）；返回从本油箱移出的量（负值 = 移入）（`ModApi.java:154` 注释）。

#### own tank 分组

**`public double getFuel()`** — `ModApi.java:158`
- 功能：本零件油箱当前燃料量。

**`public double getFuelMax()`** — `ModApi.java:159`
- 功能：本零件油箱容量。

**`public int getFuelType()`** — `ModApi.java:160`
- 功能：本零件储存的燃料类型。

**`public void setFuel(double v)`** — `ModApi.java:161`
- 功能：直接设置本零件燃料量。

**`public double addFuel(int fuelType, double amount)`** — `ModApi.java:164`
- 功能：向燃料网络加注（如太阳能充电）；返回实际加入量（`ModApi.java:163` 注释）。

#### part definition 分组（读零件类型定义，全部来自 `part.type`）

**`public double getWidth()`** — `ModApi.java:167` — 零件宽度（米）。
**`public double getHeight()`** — `ModApi.java:168` — 零件高度（米）。
**`public double getEnginePower()`** — `ModApi.java:169` — 引擎推力；非引擎返回 0。
**`public double getEngineConsumption()`** — `ModApi.java:170` — 引擎消耗率；非引擎返回 0。
**`public double getEngineTurn()`** — `ModApi.java:171` — 引擎 gimbal 转角（`engine.turnDeg`）；非引擎返回 0。
**`public double getEngineSize()`** — `ModApi.java:172` — 引擎尺寸参数；非引擎返回 0。
**`public boolean isThrottleExponential()`** — `ModApi.java:173` — 油门是否指数响应。
**`public int getEngineFuelType()`** — `ModApi.java:174` — 引擎燃料类型；非引擎返回 0。
**`public double getRcsPower()`** — `ModApi.java:175` — RCS 推力；无 RCS 返回 0。
**`public double getRcsConsumption()`** — `ModApi.java:176` — RCS 消耗率；无 RCS 返回 0。
**`public double getSolarChargeRate()`** — `ModApi.java:177` — 太阳能板充电率；无太阳能板返回 0。
**`public boolean hasLander()`** — `ModApi.java:178` — 是否有着陆腿定义。

#### actions 分组

**`public void detach()`** — `ModApi.java:182`
- 功能：切断连接本零件的所有 joint（detacher 用，`ModApi.java:181` 注释）。

**`public void setDeployed(boolean b)`** — `ModApi.java:184`
**`public boolean isDeployed()`** — `ModApi.java:185`
- 功能：设置/读取展开状态（降落伞、着陆腿、太阳能板通用）。

#### aerodynamics 分组

**`public double getDrag()`** — `ModApi.java:193-195`
- 功能：本零件有效阻力系数 Cd。若 Lua 用 `setDrag` 设过绝对值则用该值；否则为 0.75 基线 + PartList.xml 的 `drag` 调整（`Math.max(0.0, 0.75 + part.type.drag)`）。例：nosecone 的 `drag="-1.0"` → 0.25，即从全船总量中减去（`ModApi.java:188-192` 注释）。

**`public void setDrag(double cd)`** — `ModApi.java:197`
- 功能：设置绝对 Cd（如张开的降落伞设 8，`ModApi.java:196` 注释）。

**`public void resetDrag()`** — `ModApi.java:199`
- 功能：恢复 PartList.xml 推导的默认 Cd（内部置 `Double.NaN`）。

**`public double getDragArea()`** — `ModApi.java:201-203`
- 功能：阻力参考面积 m²（默认 = 零件宽度，`ModApi.java:200` 注释）。

**`public void setDragArea(double a)`** — `ModApi.java:205`
- 功能：设置阻力参考面积（如降落伞伞衣 36 m²，`ModApi.java:204` 注释）。

**`public void resetDragArea()`** — `ModApi.java:207`
- 功能：恢复默认参考面积（置 `NaN`）。

**`public void emitFlame(double size, double angleOffsetDeg)`** — `ModApi.java:210`
- 功能：生成引擎火焰特效。`size` 0..1+；`angleOffsetDeg` 为相对零件喷口朝下方向的角度偏移（度，`ModApi.java:209` 注释）。

#### joint & actuator customization 分组（round 9）

**`public void setJointParams(LuaTable t)`** — `ModApi.java:223-231`
- 功能：覆盖本零件焊接 joint 的弹簧-阻尼参数。Lua 用法（`ModApi.java:214-222` 注释）：
  ```lua
  part:setJointParams{frequencyHz=35, dampingRatio=1.2, angularDamping=0.05}
  ```
- 参数：`t` Lua 表，键 `frequencyHz` / `dampingRatio` / `angularDamping` 均可省略（nil → 继承 physics.lua 的 `joints` 表 → Java 默认值）。
- 要点：
  - 两个零件焊接时，**frequencyHz 更高（更硬）的一侧的覆盖生效**，其 dampingRatio 一并采用（`ModApi.java:218-220` 注释）；
  - `angularDamping` 只作用于本零件自己的 body，且若已设置会立刻 `setAngularDamping`（`ModApi.java:228-230`）。

**`public LuaTable getJointParams()`** — `ModApi.java:243-249`
- 功能：返回本零件自己的 joint 覆盖，形如 `{frequencyHz=.., dampingRatio=.., angularDamping=..}`，未设置的键为 nil（`ModApi.java:238-242` 注释）。joints.lua 用它做默认解析。

**`public double getDragExposure()`** — `ModApi.java:253`
- 功能：0..1，本零件横截面暴露于气流的比例（遮挡感知阻力，round 11 item 2），1 = 完全暴露（`ModApi.java:251-252` 注释）。

**`public double physicsNumber(String section, String key)`** — `ModApi.java:260-262`
- 功能：从 physics.lua 的某个表读数值，如 `part:physicsNumber("gimbal", "kp")` 读 `gimbal = { kp = ... }`；表/键缺失时返回内置默认值（`ModApi.java:255-259` 注释）。
- 返回值：`PhysicsScript.tableNumber(section, key)` 的结果。

**`public double getGimbalDeg()`** — `ModApi.java:265`
- 功能：本引擎实际 gimbal 偏转角（度），由 Lua PID 驱动（`ModApi.java:264` 注释）。

**`public void setGimbalDeg(double deg)`** — `ModApi.java:266`
- 功能：设置 gimbal 偏转角。

**`public void log(String msg)`** — `ModApi.java:269`
- 功能：向控制台打印，前缀 `[lua:<typeId>]`。

---

## 3. LuaScript.java（127 行）

### 3.1 职责

“全局唯一”单文件玩法脚本（flame.lua、terrain.lua、physics.lua）的共享加载器。解析顺序：玩家 mod 目录优先、内置 assets 兜底。**热重载**：每秒 stat 一次玩家文件，mtime 变化就重编译。加载失败的脚本标记为 broken（只记一次日志），调用方回退内置行为。**非线程安全——只能在渲染线程调用**（`LuaScript.java:11-18` 类注释）。

### 3.2 重要字段

| 字段 | 类型 | 位置 | 说明 |
|---|---|---|---|
| `fileName` | `private final String` | `LuaScript.java:21` | 如 `"physics.lua"` |
| `logTag` | `private final String` | `LuaScript.java:22` | 日志 tag：`"lua:" + fileName` |
| `globals` | `private Globals` | `LuaScript.java:23` | 当前生效的 Lua 状态；无脚本或坏了为 null |
| `loadFailed` | `private boolean` | `LuaScript.java:24` | 失败只记一次日志的防抖标记 |
| `playerModStamp` | `private long` | `LuaScript.java:25` | 玩家文件的 lastModified；-1 = 文件不存在；-2 = 初始“未 stat” |
| `lastStat` | `private long` | `LuaScript.java:26` | 上次 stat 的墙钟毫秒，用于 1 秒节流 |

### 3.3 逐函数

#### `public LuaScript(String fileName)` — `LuaScript.java:28-31`
- 功能：构造加载器（只记文件名和日志 tag，不触发 IO）。

#### `public Globals globals()` — `LuaScript.java:34-37`
- 功能：取当前生效的 Lua 状态；无脚本或加载失败返回 `null`。
- 要点：每次调用先走 `refreshIfNeeded()`，这是热重载的入口。

#### `public void invalidate()` — `LuaScript.java:40-43`
- 功能：强制下次访问时重读（如资源重载后，`LuaScript.java:39` 注释）。实现：stamp 置 -2、`lastStat` 清零。

#### `public static String versionOf(String src)` — `LuaScript.java:50-65`
- 功能：提取脚本版本标签（round 11 item 1b）：**前 5 行内**第一条形如 `-- v...`（v 后紧跟数字）的注释，如 `-- v2026.07.21`。
- 参数：`src` 脚本源码。
- 返回值：版本字符串（不含 v 前缀）；无则返回 `"?"`，让旧玩家文件在日志里明确显示为未版本化（`LuaScript.java:46-49` 注释）。

#### `private FileHandle playerFile()` — `LuaScript.java:67-72`
- 功能：解析玩家目录中的对应文件；目录不可用或文件不存在返回 `null`。

#### `private void refreshIfNeeded()` — `LuaScript.java:74-126`
- 功能：热重载核心。每秒最多 stat 一次（`LuaScript.java:75-77`）；玩家文件 mtime 没变就直接返回（`LuaScript.java:80`）。
- 要点：
  - 读玩家文件失败 → 读内置 `mods/<fileName>`（`LuaScript.java:93-102`）；
  - 两处都没有 → `globals = null`，只记一次 “using built-in defaults” 日志（`LuaScript.java:103-110`）；
  - 编译成功：换新 globals、清 `loadFailed`、打来源与版本日志（`LuaScript.java:111-118`）；
  - `LuaError`：`globals = null` + 一次性错误日志（`LuaScript.java:119-125`）。
  - 注意 `globals != null && stamp == playerModStamp` 的判断（`LuaScript.java:80`）：首次加载时 globals 为 null，不会短路。

---

## 4. JointScript.java（90 行）

### 4.1 职责

把焊接 joint 的参数求解桥到 `mod/joints.lua`（round 11 item 6）。**每一条连接**都会先咨询脚本的
```lua
jointParams(partA, attachPointA, partB, attachPointB)
    -> {frequencyHz=…, dampingRatio=…, angularDamping=…, breakForce=…|nil}
```
连接点以小表 `{x, y, fuelLine, edge, breakForce}` 传入。脚本缺失/出错则回退内置规则（per-part 覆盖、更硬一侧生效），与 round-11 之前的行为完全一致。随文件热重载（`JointScript.java:12-21` 类注释）。

### 4.2 重要字段与内部类

| 字段 | 类型 | 位置 | 说明 |
|---|---|---|---|
| `script` | `private static final LuaScript` | `JointScript.java:24` | joints.lua 的加载器 |
| `lastSeen` | `private static Globals` | `JointScript.java:25` | 上次见过的 globals，用于热重载后清错误标记 |
| `callFailed` | `private static boolean` | `JointScript.java:26` | 当前 globals 实例内调用已失败（防抖） |

**`public static final class Params`** — `JointScript.java:33-38`（求解输出）

| 字段 | 类型 | 说明 |
|---|---|---|
| `frequencyHz` | `float` | 弹簧频率 |
| `dampingRatio` | `float` | 阻尼比 |
| `breakForce` | `float` | 断裂力，默认 -1；<0 表示用调用方默认值（连接点 breakForce 的较小者）（`JointScript.java:36`） |
| `fromLua` | `boolean` | 标记是否走了 Lua 路径（冒烟测试用，`JointScript.java:32` 注释） |

### 4.3 逐函数

#### `private JointScript()` — `JointScript.java:28`
- 纯静态类。

#### `public static void invalidate()` — `JointScript.java:30`
- 功能：转发到底层 `LuaScript.invalidate()`，资源重载后强制重读。

#### `public static boolean resolve(Part a, PartType.AttachPoint apA, Part b, PartType.AttachPoint apB, Params out)` — `JointScript.java:44-79`
- 功能：向 joints.lua 询问一条连接的参数。
- 参数：`a`/`b` 两端零件；`apA`/`apB` 两端连接点；`out` 输出。
- 返回值：`true` = Lua 已填好 `out`；`false` = 调用方应使用内置规则（脚本缺失/坏/调用出错）。
- 要点：
  - globals 换实例（热重载）时清 `callFailed`（`JointScript.java:48`）；
  - 脚本里没有 `jointParams` 函数直接返回 false（`JointScript.java:50-51`）；
  - `frequencyHz`/`dampingRatio` 缺省回落到 `PhysicsScript.jointParam(...)`（即 physics.lua 的 `joints` 表 → Java 默认，`JointScript.java:58-61`）；`breakForce` 缺省 -1（`JointScript.java:62`）；
  - `angularDamping` 若给出，**同时施加到两端 body**（`JointScript.java:63-68`，注释说明这是 per-connection 语义）；
  - `LuaError` 只记一次日志并永久回退（直到脚本重载）（`JointScript.java:71-78`）。

#### `private static LuaTable attachTable(PartType.AttachPoint ap)` — `JointScript.java:81-89`
- 功能：把 Java 连接点打包成 Lua 表 `{x, y, fuelLine, edge, breakForce}`。
- 要点：`breakForce == Float.MAX_VALUE`（即“不可断”）时不写 `breakForce` 键（`JointScript.java:87`），Lua 侧看到 nil。

---

## 5. PhysicsScript.java（179 行）

### 5.1 职责

把物理定律桥到 `mod/physics.lua`（item 8c）。暴露给 Lua 的 API（`PhysicsScript.java:13-20` 类注释）：
```lua
gravityAccel(x, y, timeSec) -> ax, ay   -- 宇宙坐标，m/s^2
atmosphereDensity(planetName, altitude) -> kg/m^3
steering = { kp = .., ki = .. }         -- PI 转向增益（item 4）
```
脚本可看到一个**活的** `world` 代理（行星位置/mu）和一个静态 `planetEnv` 表（按行星名的大气参数）。任何 Lua 错误都会禁用脚本（记一次日志）并回退内置定律。

**性能注意**（`PhysicsScript.java:22-25` 注释）：`gravityAccel` 每零件每物理 tick 在 Lua 里跑一次，目前零件数量级（几十个）没问题；若 profiling 出问题，应按船按帧批量传数组。

### 5.2 重要字段与内部类

| 字段 | 类型 | 位置 | 说明 |
|---|---|---|---|
| `script` | `private static final LuaScript` | `PhysicsScript.java:40` | physics.lua 的加载器 |
| `bound` | `private static Globals` | `PhysicsScript.java:41` | 已注入环境表（world/planetEnv）的 globals 实例 |
| `callFailed` | `private static boolean` | `PhysicsScript.java:42` | 调用失败后永久回退内置定律（直到脚本重载） |
| `DEF_JOINT_FREQ / DEF_JOINT_DAMP / DEF_ANG_DAMP` | `private static final double` | `PhysicsScript.java:118` | 焊接默认 20 Hz / 1.1 / 0.08（round 6 调参：近临界阻尼；20 Hz 低于 60 Hz 物理步长的 30 Hz Nyquist 极限，见 `PhysicsScript.java:115-117` 注释） |

**`public static class WorldProxy`** — `PhysicsScript.java:29-38`

被 `CoerceJavaToLua.coerce` 注入 Lua 的活世界视图（`PhysicsScript.java:28` 注释），构造于 `PhysicsScript.java:31`，方法：
- `public int planetCount()` — `PhysicsScript.java:32` — 行星数。
- `public String planetName(int i)` — `PhysicsScript.java:33` — 第 i 颗行星名。
- `public double planetX(int i)` / `planetY(int i)` — `PhysicsScript.java:34-35` — 行星**宇宙坐标**（注意：与 ModApi 不同，这里不减 origin）。
- `public double planetMu(int i)` — `PhysicsScript.java:36` — 行星引力参数 μ。
- `public double planetRadius(int i)` — `PhysicsScript.java:37` — 行星半径。

### 5.3 逐函数

#### `private PhysicsScript()` — `PhysicsScript.java:44`
- 纯静态类。

#### `public static void invalidate()` — `PhysicsScript.java:46`
- 功能：转发到底层加载器，资源重载后强制重读 physics.lua。

#### `public static void ensureBound(GameWorld world)` — `PhysicsScript.java:49-71`
- 功能：当脚本重载或世界变化时，（重新）向 Lua 注入 `world` 代理与 `planetEnv` 表。
- 要点：
  - 以 globals 实例身份判断是否已绑（`PhysicsScript.java:51-53`），热重载后自动重绑并重置 `callFailed`（`PhysicsScript.java:54`）；
  - `planetEnv` 按行星名索引，每颗行星含 `atmoHeight`、`surfacePressure`、`scaleHeight`（`PhysicsScript.java:57-65`）；
  - 绑定失败记日志并把 `bound` 置 null（`PhysicsScript.java:67-70`）。

#### `public static boolean gravity(double x, double y, double timeSec, Vec2d out)` — `PhysicsScript.java:74-88`
- 功能：从 Lua 求重力加速度。
- 参数：`x/y` 宇宙坐标；`timeSec` 模拟时间；`out` 输出向量。
- 返回值：`true` = 用 Lua 结果；`false` = 调用方用内置定律（未绑定/已失败/无 `gravityAccel` 函数）。
- 要点：`LuaError` 走 `fail()` 永久回退（`PhysicsScript.java:84-87`）。

#### `public static double density(String planetName, double altitude)` — `PhysicsScript.java:91-103`
- 功能：从 Lua 求大气密度。
- 返回值：kg/m³；**`NaN` 表示使用内置模型**（`PhysicsScript.java:90` 注释）。

#### `public static double steeringGain(String key, double def)` — `PhysicsScript.java:106-113`
- 功能：从 Lua 的 `steering = {kp=.., ki=..}` 表读 PI 增益。
- 参数：`key` 键名；`def` 默认值。
- 返回值：数值；表/键缺失或非数字返回 `def`。
- 要点：只检查 `bound`，不看 `callFailed`（读表不算调用，不会失败）。

#### `public static double jointParam(String key)` — `PhysicsScript.java:125-134`
- 功能：从 Lua 的 `joints = {frequencyHz=.., dampingRatio=.., angularDamping=..}` 表读焊接参数，逐键回落到 DEF_* 默认值。
- 参数：`key` 为 `"frequencyHz"` | `"dampingRatio"` | `"angularDamping"`（`PhysicsScript.java:120-124` 注释）。

#### `public static double tableNumber(String section, String key)` — `PhysicsScript.java:141-150`
- 功能：通用读取器，读 physics.lua 任意表中的数值项（`ModApi.physicsNumber` 的后端）。
- 参数：`section` 表名；`key` 键名。
- 返回值：数值；缺失时用内置默认——`"gimbal"` 段走 `gimbalDefault`，`"joints"` 段走 `jointDefault`，其他段默认 0（`PhysicsScript.java:142-143`）。

#### `private static double jointDefault(String key)` — `PhysicsScript.java:153-160`
- 功能：焊接参数默认值（与 DEF_* 常量保持同步，`PhysicsScript.java:152` 注释）；未知键返回 0。

#### `private static double gimbalDefault(String key)` — `PhysicsScript.java:163-171`
- 功能：引擎 gimbal PID 执行器默认值：`kp=8.0`、`ki=0.1`、`kd=0.6`、`maxRateDeg=90.0`；未知键返回 0。可在 physics.lua 的 `gimbal` 表覆盖（`PhysicsScript.java:162` 注释）。

#### `private static void fail(String fn, LuaError e)` — `PhysicsScript.java:173-178`
- 功能：记一次错误日志并置 `callFailed`，之后内置定律接管。

---

## 6. FlameScript.java（223 行）

### 6.1 职责

把引擎火焰渲染桥到 `mod/flame.lua`。**Java 负责批量图元，脚本决定画什么**（`FlameScript.java:16-32` 类注释）。Lua API：
```lua
draw.triangle(x1,y1, x2,y2, x3,y3, r,g,b,a)        -- 世界坐标，批量
draw.sprite(tex, x,y,w,h, angleDeg, alpha [,r,g,b]) -- 贴图 quad，加色混合
flame.emit{tex=..., x=..., y=..., vx=..., vy=..., life=..., size0=...,
           size1=..., r=..., g=..., b=..., a0=..., a1=..., drag=...}
                                                     -- 池化粒子（FlameFx）
drawFlame(ctx)                                       -- 每个运行中的引擎每帧调用
```
`ctx` 字段：`x, y`（喷口世界坐标）、`dirX, dirY`（羽流单位方向）、`angle`（喷口角，弧度）、`nozzleW`（米）、`throttle`（0..1+ 火焰等级）、`engineSize`、`engineHeight`（米）、`time`（秒）、`dt`（本帧模拟秒数，含 warp）、`partId`（稳定的每零件 key，供脚本存 per-engine 状态）、`fuelType`（2 = 离子）、`ion`（bool）、`pressure`（环境压，1.0 = 海平面，0 = 真空）、`density`（kg/m³）。
脚本缺失或出错时改画内置默认羽流（记一次日志）；随文件热重载。

### 6.2 重要字段

| 字段 | 类型 | 位置 | 说明 |
|---|---|---|---|
| `script` | `private static final LuaScript` | `FlameScript.java:35` | flame.lua 的加载器 |
| `buf` / `tris` | `private static float[]` / `int` | `FlameScript.java:37-38` | 三角形批量缓冲，每三角形 10 个 float（6 坐标 + 4 颜色），初始容量 4096 |
| `sbuf` / `sprites` | `private static float[]` / `int` | `FlameScript.java:39-40` | sprite 批量缓冲，每个 11 个 float（texId + x,y,w,h,angle + alpha + r,g,b），初始容量 1024 |
| `callFailed` | `private static boolean` | `FlameScript.java:41` | 当前 globals 内 drawFlame 已失败 |
| `ctx` | `private static final LuaTable` | `FlameScript.java:43` | **复用**的 ctx 表（避免每引擎每帧分配） |
| `lastSeen` | `private static Globals` | `FlameScript.java:137` | 上次见过的 globals（声明位置靠后，在 `begin` 之后） |

### 6.3 逐函数

#### `private FlameScript()` — `FlameScript.java:45`
- 纯静态类。

#### `public static void invalidate()` — `FlameScript.java:47`
- 功能：转发到底层加载器。

#### `public static boolean available()` — `FlameScript.java:49-52`
- 功能：脚本可用且定义了 `drawFlame` 函数时返回 true。

#### `public static boolean begin(float dtSim)` — `FlameScript.java:59-135`
- 功能：开始一个批量火焰 pass。
- 参数：`dtSim` 本帧覆盖的模拟秒数（含 warp），让 4x 加速下粒子发射率保持稳定（`FlameScript.java:54-58` 注释）。
- 返回值：`false` = 调用方应画内置羽流（脚本缺失/无函数/已失败）。
- 要点：
  - globals 换实例时清 `callFailed`（`FlameScript.java:62-65`）；
  - 给当前 globals **安装 draw/flame API**（仅当表不存在时，`FlameScript.java:68-103, 104-130`）：
    - `draw.triangle`：VarArgFunction，最多取 10 个参数写入 `buf`，`ensure` 扩容（`FlameScript.java:71-80`）；
    - `draw.sprite`：tex 名经 `FlameFx.texIdForName` 转 id（默认 `"glow"`），r/g/b 缺省 1.0（`FlameScript.java:82-101`）；
    - `flame.emit`：从表参数读 13 个字段转发 `FlameFx.emit`，缺省 life=0.5、size0/size1=0.5、a0=0.5、a1=0（`FlameScript.java:107-123`）；
    - `flame.count`：返回 `FlameFx.activeCount()`（`FlameScript.java:124-128`）；
  - 写 `ctx.dt = dtSim`，清零两个计数器（`FlameScript.java:131-133`）。

#### `public static void drawPart(float x, float y, float dirX, float dirY, float angle, float nozzleW, float throttle, float engineSize, float engineHeight, double time, int fuelType, double pressure, double density, int partId)` — `FlameScript.java:140-171`
- 功能：让脚本画一台引擎的羽流。
- 参数：对应 ctx 各字段；`partId` 是稳定的每零件 key。
- 要点：
  - 填充复用的 `ctx` 表全部字段（`FlameScript.java:146-161`），`ion` 由 `fuelType == 2` 推导（`FlameScript.java:156`）；
  - `pressure`/`density` 是喷口处环境大气（1.0 = 当前行星海平面压，0 = 真空），脚本用它画马赫环/羽流膨胀；旧脚本直接忽略（`FlameScript.java:157-159` 注释）；
  - `LuaError` 记一次日志后永久回退内置羽流（`FlameScript.java:164-170`）。

#### `public static void flush(ShapeRenderer shapes)` — `FlameScript.java:174-181`
- 功能：把批量三角形通过 ShapeRenderer 画掉（ShapeRenderer 须已 begin），然后清零计数。

#### `public static void flushSprites(SpriteBatch batch)` — `FlameScript.java:187-208`
- 功能：把批量 sprite 以加色混合渲染。自管 batch begin/end，并恢复之前的 blend function（`FlameScript.java:183-186` 注释）。
- 要点：`alpha <= 0.003f` 直接跳过（`FlameScript.java:197`）；调用前确保贴图已生成（`FlameFx.ensureTextures()`，`FlameScript.java:189`）。

#### `private static void ensure(int n)` — `FlameScript.java:210-215`
**`private static void ensureSprite(int n)`** — `FlameScript.java:217-222`
- 功能：批量缓冲按需倍增扩容。

---

## 7. FlameFx.java（199 行）

### 7.1 职责

引擎尾气的**池化粒子系统**（Item 2）：世界坐标、加色混合、硬上限；贴图在启动时程序化生成，不需要任何新 asset 文件。由 Lua 通过 `flame.emit` 驱动（`FlameFx.java:9-13` 类注释）。

### 7.2 重要字段

| 字段 | 类型 | 位置 | 说明 |
|---|---|---|---|
| `MAX` | `public static final int = 600` | `FlameFx.java:15` | 粒子硬上限 |
| `TEX_GLOW / TEX_SMOKE / TEX_SPARK` | `public static final int` 0/1/2 | `FlameFx.java:16` | 三种程序化贴图 id |
| `tex` | `private static TextureRegion[]` | `FlameFx.java:18` | 惰性生成的贴图数组 |
| `x, y, vx, vy, drag, life, age, size0, size1, cr, cg, cb, a0, a1, texId` | `private static final float[]`（`texId` 为 `int[]`） | `FlameFx.java:21-28` | **structure-of-arrays 环形缓冲**，长度均为 MAX |
| `head` | `private static int` | `FlameFx.java:29` | 下一个要（覆）写的槽位 |
| `count` | `private static int` | `FlameFx.java:30` | 存活粒子数 |
| `maxEver` | `private static int` | `FlameFx.java:31` | 高水位线（测试用） |

### 7.3 逐函数

#### `private FlameFx()` — `FlameFx.java:33`
- 纯静态类。

#### `public static synchronized void ensureTextures()` — `FlameFx.java:35-41`
- 功能：惰性生成三张程序化贴图（glow/smoke/spark）。幂等。

#### `public static int texIdForName(String name)` — `FlameFx.java:43-47`
- 功能：贴图名 → id。`"smoke"` → 1，`"spark"` → 2，其余（含 `"glow"`）→ 0。

#### `public static TextureRegion tex(int id)` — `FlameFx.java:49-53`
- 功能：id → 贴图；越界 id 回落到 TEX_GLOW。

#### `public static synchronized int emit(int id, float px, float py, float pvx, float pvy, float pdrag, float plife, float s0, float s1, float r, float g, float b, float aa0, float aa1)` — `FlameFx.java:55-70`
- 功能：发射一个粒子到环形缓冲（满了就覆盖最老的）。
- 参数：贴图 id、位置、速度、阻力、寿命、起始/结束尺寸、RGB、起始/结束 alpha。
- 返回值：当前存活数 `count`。
- 要点：`plife <= 0.001f` 直接忽略（`FlameFx.java:58`）；复用死槽位才递增 `count`（`FlameFx.java:60`）。

#### `public static synchronized void update(float dt)` — `FlameFx.java:73-86`
- 功能：按模拟秒推进粒子（dt 已含 warp，`FlameFx.java:72` 注释）。
- 要点：到寿置 `life=0` 并减计数；阻力用指数衰减 `exp(-drag*dt)`（`FlameFx.java:81-84`）。

#### `public static synchronized void render(SpriteBatch batch)` — `FlameFx.java:89-107`
- 功能：加色渲染全部存活粒子。**调用方不得已 begin batch；相机须已设置**（`FlameFx.java:88` 注释）。渲染后恢复原 blend function。
- 要点：尺寸与 alpha 按 `age/life` 线性插值（`FlameFx.java:97-99`）；过小或过透明跳过（`FlameFx.java:100`）。

#### `public static synchronized int activeCount()` — `FlameFx.java:109`
- 功能：当前存活粒子数（Lua 的 `flame.count()` 后端）。

#### `public static synchronized int maxActiveEver()` — `FlameFx.java:110`
- 功能：历史最高存活数（测试用）。

#### `public static synchronized void reset()` — `FlameFx.java:112-115`
- 功能：清空全部粒子，head/count 归零。

#### `public static synchronized void resetMaxEver()` — `FlameFx.java:118`
- 功能：测试钩子——场景之间清零高水位线（`FlameFx.java:117` 注释）。

#### `private static Texture makeGlow()` — `FlameFx.java:122-139`
- 功能：64×64 柔和径向光斑（alpha = (1-d)²）。

#### `private static Texture makeSpark()` — `FlameFx.java:141-157`
- 功能：32×32 硬芯火花（d<0.22 全亮，之后 0.35 宽线性衰减）。

#### `private static Texture makeSmoke()` — `FlameFx.java:159-179`
- 功能：64×64 斑块烟雾——两个 octave 的 value noise 叠径向 mask（`FlameFx.java:167-170`）。

#### `private static float vnoise(float fx, float fy)` — `FlameFx.java:181-189`
- 功能：2D value noise，smoothstep 插值四个格点哈希值。

#### `private static float hash01(int ix, int iy)` — `FlameFx.java:191-196`
- 功能：整数格点 → [0,1] 伪随机哈希。

#### `private static float clamp01(float v)` — `FlameFx.java:198`
- 功能：clamp 到 [0,1]。

---

## 8. Res.java（591 行，util 包）

### 8.1 职责

**玩家可见的资源根目录**（`Res.java:7-29` 类注释，信息密度很高，直接要点化）：

- **Android**：优先用**共享根** `/storage/emulated/0/DifferentRocket/`——通过反射调 `Environment.getExternalStorageDirectory()` 解析，而**不是** `Gdx.files.getExternalStoragePath()`（libGDX ≥ 1.9.10 起后者返回 app 私有目录 `Android/data/<pkg>/files/`）。共享根不可写（权限未授予）时回退 app 私有目录，并在**每次 resume 重试共享根**；首次成功切换时把私有目录文件迁移过去（只补缺失文件，玩家修改永不覆盖）。
- **Desktop**：项目目录下的 `DifferentRocket/`（core/assets 的父目录，避免玩家副本泄漏进 Android assets 树）。
- 首运行拷贝全部默认资源：`<root>/assets/`（贴图 + XML 配置）、`<root>/mod/`（Lua 零件行为脚本 + planets.lua）。
- **版本化同步（round 10）**：每个区保留 `.defaults/` 影子目录存上次运行的出厂字节——玩家没动过的文件在内置默认更新时自动跟进，玩家改过的永远保留。内置一组已知旧出厂文件的 SHA-1，用于迁移该机制存在之前的老安装。
- 若所有目录都不可写，所有查找优雅回退到内置 assets。

### 8.2 重要字段

| 字段 | 类型 | 位置 | 说明 |
|---|---|---|---|
| `root` | `private static FileHandle` | `Res.java:32` | 当前生效的资源根 |
| `sharedRoot` | `private static FileHandle` | `Res.java:33` | `/sdcard/DifferentRocket`（目标） |
| `privateRoot` | `private static FileHandle` | `Res.java:34` | `Android/data/<pkg>/files/DifferentRocket`（回退） |
| `external` | `private static boolean` | `Res.java:35` | 玩家根是否可用 |
| `ASSET_FILES` | `private static final String[]` | `Res.java:45-73` | 内置资源**显式清单**（27 项）。注释解释了为什么硬编码：Android 的 `AssetManager.list()` 在部分设备对 APK asset 根返回空数组，导致拷贝循环跑 0 次（`Res.java:37-44`）；SmokeScreen 负责校验清单与实际文件同步 |
| `MOD_FILES` | `private static final String[]` | `Res.java:75-109` | 33 个内置 lua 的显式清单（引擎/油箱/控制/物理/地形/火焰/joints/planets 等） |
| `KNOWN_OLD` | `private static final Map<String, String[]>` | `Res.java:273` | 已知旧出厂文件的 SHA-1（key 形如 `"mod/physics.lua"`），见 8.3 |

### 8.3 逐函数

#### `private Res()` — `Res.java:111`
- 纯静态类。

#### `public static void init()` — `Res.java:113-139`
- 功能：一次性初始化双根并首次 `populate()`。
- 要点：
  - Android：shared 根来自 `sharedBase()`（反射），private 根来自 `Gdx.files.getExternalStoragePath()`（`Res.java:115-131`）；
  - Desktop：shared = private = `Gdx.files.local("../DifferentRocket/")`（`Res.java:132-137`）；
  - 幂等（`root != null` 直接返回）。

#### `private static String sharedBase()` — `Res.java:142-161`
- 功能：反射调用 `Environment.getExternalStorageDirectory()`（core 模块没有 android.jar，`Res.java:141` 注释）。
- 返回值：以 `/` 结尾的共享存储基路径。
- 要点：反射失败或路径可疑（非 `/` 开头）时强制 `"/storage/emulated/0/"`（`Res.java:151-155`）；非标准路径会打日志提示（多用户/工作资料？）但仍使用（`Res.java:156-158`）。

#### `public static boolean refresh()` — `Res.java:170-180`
- 功能：每次 app resume 重跑目录检查/populate，让 MANAGE_EXTERNAL_STORAGE 授权**无需重启**即生效（`Res.java:163-169` 注释）。
- 返回值：`true` = 生效根发生了**切换**（内置兜底 → external，或 app 私有 → 共享），调用方应重载 atlas/mod/world。

#### `private static void populate()` — `Res.java:187-215`
- 功能：挑选最优可写根（共享优先、私有回退）、置 `external`、拷默认资源。
- 要点：首次切到共享根时执行 `migrateToShared()`（`Res.java:209`）；两个根都不可写则 `external = false`，后续全部走内置 assets（`Res.java:200-206`）。

#### `private static boolean probeWritable(FileHandle dir)` — `Res.java:218-230`
- 功能：mkdirs + 写/删 `.probe` 试探文件——注释称这是**唯一可靠的**可写性测试（`Res.java:217`）。

#### `private static void migrateToShared()` — `Res.java:238-250`
- 功能：首次切到共享根时，把 app 私有根里玩家已有的东西全部搬过去。**只补缺失文件**，共享根中的玩家修改永不覆盖，私有副本保留原位（`Res.java:232-237` 注释）。

#### `private static void copyMissing(FileHandle from, FileHandle to, int[] n)` — `Res.java:252-264`
- 功能：递归拷贝缺失文件，`n[0]` 计数。

#### `private static Map<String, String[]> buildKnownOld()` — `Res.java:275-296`
- 功能：构造已知旧出厂字节 SHA-1 表（`Res.java:266-272` 注释）。命中即证明该玩家文件是**未修改的旧出厂副本**，即使没有 `.defaults` 可比也自动更新。
- 内容：round-8/round-9 的 physics.lua 各一个哈希（`Res.java:280-282`）；round-8 六个 gimbal 引擎脚本（engine-0..4 + ion-0，字节相同，cp 同步过）两个哈希（`Res.java:285-291`）；round-8 三角锥羽流版 flame.lua 一个哈希（`Res.java:293-294`）。

#### `private static int[] syncTree(FileHandle rootDir)` — `Res.java:315-320`
- 功能：对 `assets` 与 `mod` 两组各跑一次 `syncGroup`。
- 返回值：`{copied, auto-updated, keptCurrent, keptPlayer, keptUnknown, failed}` 六元计数（`Res.java:313`）。

#### `private static void syncGroup(FileHandle rootDir, String group, String internalPrefix, String[] manifest, int[] n)` — `Res.java:322-425`
- 功能：版本化默认同步的核心（判定规则见 `Res.java:298-314` 注释）：
  1. `mod/X` 缺失 → 拷新默认（**copied**）；
  2. 与新默认字节相同 → 不动（**kept-current**）；
  3. 与旧 `.defaults/X` 字节相同（玩家从未动过）→ 覆盖为新默认（**auto-updated**）；
  4. 命中 `KNOWN_OLD` 哈希（可证明是版本化机制之前的旧出厂副本）→ 覆盖（**auto-updated**）；
  5. 否则 → 玩家改过（或首装未知），保留（**kept-player** / **kept-unknown**）。
- 要点：
  - 所有比较先对**旧** `.defaults` 做，然后 pass 2 才把 `.defaults` 从当前 bundle 强制刷新（`Res.java:406-421`）；
  - `.defaults` 里会写 `_README.txt`（中英双语“请勿修改”，`Res.java:415-418`）；
  - `.lua` 文件在 kept-player 之前还会尝试 `replaceOlderFactoryLua` 版本头回退（`Res.java:391-393`）；
  - 每个决定都打 `res` 日志。

#### `private static boolean replaceOlderFactoryLua(byte[] curB, byte[] intB, String group, String name, FileHandle cur, FileHandle internal)` — `Res.java:441-466`
- 功能：**版本头回退（round 16）**（`Res.java:427-440` 注释）。round-8/9 时代的玩家文件早于 `.defaults` 机制，首装时既不与 bundle 字节相同也无 `.defaults` 可比，而 KNOWN_OLD 只覆盖三个脚本，其余旧出厂 .lua 会永远停在 kept-unknown。因此：玩家副本解析出的版本头（`LuaScript.versionOf`，前 5 行的 `-- vX.Y.Z`）**严格旧于**内置版本时，判定为旧出厂脚本（可能带玩家在旧版上的修改）——**备份一次**到 `<name>.player-bak`（已有备份则保留第一代），再用内置默认覆盖。版本相同或更新则走正常 kept-player 路径。
- 返回值：是否发生了替换。

#### `static int compareVersions(String a, String b)` — `Res.java:474-483`
- 功能：点分数字版本元组比较：逐段 parse int，首个不同段决定胜负；公共段全等时**更长的元组更大**（`"2026.07.21" < "2026.07.22.2"`；`"1.2" < "1.2.1"`）；无法解析的段按 0 算（`Res.java:468-473` 注释）。
- 返回值：-1 / 0 / 1。
- 要点：包可见（非 private），便于测试。

#### `private static int versionSeg(String s)` — `Res.java:485-491`
- 功能：单个版本段 parse，失败返回 0。

#### `private static String sha1Hex(byte[] b)` — `Res.java:493-504`
- 功能：字节数组的 SHA-1 小写 hex；异常返回空串。

#### `public static int[] syncTreeForTest(String absoluteRoot)` — `Res.java:507-509`
- 功能：测试钩子（round 10）——对任意根目录跑版本化同步。

#### `private static void copyDefaults()` — `Res.java:517-523`
- 功能：对当前 `root` 跑 `syncTree` 并打一行汇总日志（copied / auto-updated / kept-current / kept-player / kept-unknown / failed 六个计数）。

#### `public static String checkManifest()` — `Res.java:531-554`
- 功能：Desktop/开发环境的健全性检查——硬编码清单必须覆盖内置 assets 里的每个文件（desktop 的目录列举可靠，Android 不可靠，`Res.java:525-530` 注释）。由 SmokeScreen 打印，防止“加了 asset/mod 忘了登记清单”。
- 返回值：`"OK (60 files listed)"` 或 `"MISSING FROM MANIFEST: ..."` 或 `"ERROR: ..."`。

#### `private static boolean contains(String[] arr, String s)` — `Res.java:556-559`
**`private static void appendMissing(StringBuilder sb, String s)`** — `Res.java:561-564`
- 功能：清单辅助小函数。

#### `public static boolean usingExternal()` — `Res.java:566`
- 功能：玩家根是否可用。

#### `public static FileHandle asset(String name)` — `Res.java:569-575`
- 功能：取贴图或 XML 配置：玩家副本（`<root>/assets/<name>`）优先，内置兜底（`Res.java:568` 注释）。

#### `public static FileHandle modDir()` — `Res.java:578-581`
- 功能：玩家 mod 目录；回退时返回内置 `mods` 目录（注意此时可能不代表可写目录，`Res.java:577` 注释）。`ModManager.init()` 与 `LuaScript.playerFile()` 都走这里。

#### `public static FileHandle modFile(String name)` — `Res.java:584-590`
- 功能：取单个 mod 文件：玩家副本优先，内置兜底。

---

## 9. 关键协作关系速查

```
Res.init()/refresh()                      （app 启动 / resume）
   └─ 选定可写根 → syncTree 版本化拷贝/更新玩家目录
        ├─ ModManager.init() → Res.modDir()
        │    └─ Part 创建时 ModManager.createState(typeId)
        │         └─ 每实例 Globals + PID_LIB + <typeId>.lua
        │              └─ ModManager.callHook(g, "onLoad"/"onUpdate"/"onStage", api, dt)
        │                   └─ api = new ModApi(part)（Lua 里的 part）
        ├─ LuaScript("physics.lua") ← PhysicsScript.ensureBound(world)
        │    └─ gravity() / density() / jointParam() / tableNumber()
        ├─ LuaScript("joints.lua") ← JointScript.resolve(a, apA, b, apB, out)
        ├─ LuaScript("flame.lua") ← FlameScript.begin/drawPart/flush(+Sprites)
        │    └─ flame.emit → FlameFx.emit → update/render
        └─ LuaScript("terrain.lua")（由 TerrainSystem 使用，本文件未展开）
```

- 零件脚本没有热重载：`scriptSourceCache` 只在 `ModManager.reset()` 时清空（`ModManager.java:40-44`）；热重载（1 秒 stat）只存在于 `LuaScript` 系的单文件脚本（`LuaScript.java:74-80`）。
- 所有 Lua 错误路径都是“记一次日志 + 回退内置行为”，单个玩家脚本崩溃不会拖垮游戏。
