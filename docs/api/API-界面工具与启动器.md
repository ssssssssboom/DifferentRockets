# API 参考：界面、工具与启动器

本文档覆盖游戏的入口层（Android/Desktop 启动器）、应用主类 `DRGame`、主菜单与程序化 UI 皮肤、`util` 包中的基础工具类，以及地形 lua 桥接 `TerrainScript` 与转向输入共享状态 `SteeringIO`。

所有行号均对应仓库当前源码，引用格式为 `文件名:行号`。

**涉及文件一览**

| 文件 | 包 | 职责 |
|---|---|---|
| `game/android/src/com/differentrockets/android/AndroidLauncher.java` | `android` | Android 入口，存储权限申请与资源热切换 |
| `game/desktop/src/com/differentrockets/desktop/DesktopLauncher.java` | `desktop` | 桌面（LWJGL3）调试入口 |
| `game/core/src/com/differentrockets/game/DRGame.java` | `game` | libGDX `Game` 主类，全局资源与 screen 管理 |
| `game/core/src/com/differentrockets/ui/MenuScreen.java` | `ui` | 主菜单 screen |
| `game/core/src/com/differentrockets/ui/Ui.java` | `ui` | 程序化生成的 scene2d skin |
| `game/core/src/com/differentrockets/util/AtlasPack.java` | `util` | TexturePacker XML 图集解析 |
| `game/core/src/com/differentrockets/util/Json.java` | `util` | 极简 JSON 读写器（存档系统专用） |
| `game/core/src/com/differentrockets/util/Xml.java` | `util` | XML double 属性补丁 |
| `game/core/src/com/differentrockets/util/Vec2d.java` | `util` | 双精度 2D 向量 |
| `game/core/src/com/differentrockets/game/TerrainScript.java` | `game` | 地形生成与 `terrain.lua` 的桥接 |
| `game/core/src/com/differentrockets/game/SteeringIO.java` | `game` | UI 与飞控之间的转向输入共享状态 |

---

## 1. AndroidLauncher（Android 启动器）

**职责**：Android 端入口 Activity（继承 `AndroidApplication`）。除创建 `DRGame` 外，核心工作是**存储权限的探测与申请**：游戏需要把玩家可修改的资源（贴图、零件、lua 脚本）放到共享存储 `/storage/emulated/0/DifferentRocket/`，因此在 API 30+ 上要走"所有文件访问"权限，在 API 23–29 上走运行时 `WRITE_EXTERNAL_STORAGE` 权限。授权后回到游戏**无需重启**，通过 `DRGame.reloadResources()` 热切换资源根。日志统一打 `adb logcat` 可过滤的 `[storage]` 前缀（TAG 为 `DifferentRockets`，AndroidLauncher.java:13）。

### 成员字段

| 字段 | 说明 |
|---|---|
| `game: DRGame` | 游戏实例，onCreate 中创建（AndroidLauncher.java:15） |
| `permDialog: AlertDialog` | 权限说明对话框引用，用于去重与授权后关闭（AndroidLauncher.java:16） |
| `userDeclinedThisSession: boolean` | 用户本会话点过"暂不"，之后 onResume 不再反复弹窗（AndroidLauncher.java:17） |

### 逐函数说明

#### `onCreate(Bundle savedInstanceState)`（AndroidLauncher.java:20）
- **功能**：Activity 创建。配置 `AndroidApplicationConfiguration`：关闭加速度计/罗盘，开启 wakelock（AndroidLauncher.java:22-25）。随后记录存储状态日志、探测存储可写性，不可写则立即申请权限，最后创建 `DRGame` 并调 `initialize(game, cfg)`。
- **要点**：权限申请发生在 `initialize` **之前**，但即使玩家暂不授权，游戏仍以内置资源正常启动（`Res` 兜底）。

#### `onResume()`（AndroidLauncher.java:35）
- **功能**：每次回到前台重新探测存储。若探测失败且用户本会话未拒绝过，再次走权限申请流程（`fromOnCreate=false`，不自动跳设置页）。随后 `Res.refresh()` 检测资源根是否从内置切换到外部；若切换成功，关闭权限对话框并调用 `game.reloadResources()` 热重载全部资源。
- **要点**：整个方法包在 `try/catch (Throwable)` 里，资源刷新失败只记 warning，不会崩溃（AndroidLauncher.java:53-55）。这是"授权后返回游戏自动生效"的关键路径。

#### `logStorageState(String where)`（AndroidLauncher.java:59）
- **功能**：向 logcat 输出一条完整存储状态：SDK 版本、外部存储根路径、`isExternalStorageManager`（API 30+）、`WRITE_EXTERNAL_STORAGE` 是否已授予（API < 30）、targetSdk=29。
- **参数**：`where` —— 调用点标记（如 `"onCreate"`/`"onResume"`）。
- **要点**：纯诊断用途，便于远程排查玩家权限问题。

#### `probeStorage(String where)`（AndroidLauncher.java:82）
- **功能**：真实验证共享存储可写性：在 `外部存储根/DifferentRocket/` 下 `mkdirs()`，写入 1 字节 `.probe` 文件再删除。
- **返回值**：`true` 表示共享存储根确实可写。
- **要点**：不依赖权限标志位而是**实际写文件**，因为"权限已授予"与"目录真的可写"在各厂商 ROM 上并不等价。

#### `requestStorageAccess(boolean fromOnCreate)`（AndroidLauncher.java:113）
- **功能**：按 API 等级申请匹配的权限。API ≥ 30 且非 external storage manager → 弹中文说明对话框；API 23–29 未授权 → `requestPermissions` 同时申请 READ/WRITE_EXTERNAL_STORAGE（requestCode=1）。
- **参数**：`fromOnCreate` —— 是否来自 onCreate；为 `true` 时对话框**立即附带**跳转设置页动作（见下）。

#### `showPermissionDialog(boolean fireIntentImmediately)`（AndroidLauncher.java:133）
- **功能**：弹出不可取消的中文对话框"需要文件访问权限"，说明需要在 `/storage/emulated/0/DifferentRocket/` 创建玩家资源目录；正按钮"去授权"跳 `ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION` 设置页，负按钮"暂不（使用内置资源）"置 `userDeclinedThisSession=true`。
- **参数**：`fireIntentImmediately` —— 为 `true` 时对话框弹出**同时**直接打开设置页（onCreate 路径的行为）。
- **要点**：对话框单例去重（已显示则直接 return，AndroidLauncher.java:134）。

---

## 2. DesktopLauncher（桌面启动器）

**职责**：LWJGL3 桌面端调试入口，仅一个 `main` 方法（DesktopLauncher.java:8）。窗口设为 **1080×1920 竖屏**（约手机纵横比，保证 UI 比例与真机一致，DesktopLauncher.java:11 注释），前台 60 FPS，标题 `DifferentRockets`。桌面端无权限逻辑——`Res` 在桌面直接读写用户目录下的 `DifferentRocket` 文件夹。

---

## 3. DRGame（应用主类）

**职责**：libGDX `Game` 子类，全应用的单例式资源中枢。持有 `SpriteBatch`、`ShapeRenderer`、两档字体、`Ui` 皮肤、三张图集（`shipSprites`/`planetSprites`/`runtimeSprites`）以及 `GameWorld world`。负责启动时的资源初始化顺序和"外部资源根切换"时的整体热重载。

### 常量与字段

| 成员 | 说明 |
|---|---|
| `FONT_SCALE = 1.75f` | 全局 UI 字体缩放（需求：更大的按钮/触摸目标）（DRGame.java:15） |
| `batch / shapes / font / bigFont / ui` | 渲染与 UI 基础设施；`font` 按 `FONT_SCALE` 缩放，`bigFont` 按 2.5 倍缩放（DRGame.java:36-38） |
| `shipSprites / planetSprites / runtimeSprites` | 三张 `AtlasPack` 图集，分别来自 `ShipSprites.xml` / `PlanetSprites.xml` / `Runtime.xml`（DRGame.java:44-46） |
| `world: GameWorld` | 游戏世界实例（DRGame.java:48） |

### 逐函数说明

#### `create()`（DRGame.java:30）
- **功能**：libGDX 生命周期入口。初始化顺序固定：`Res.init()`（玩家可编辑资源根，首运行拷贝默认资源）→ 渲染对象与字体 → `Ui` 皮肤 → `PartList.load()`（零件定义）→ `ModManager.init()`（lua mod）→ 三张图集 → `GameWorld` → 进入 `MenuScreen`。
- **要点**：`Res.init()` 必须最先调用，后续所有资源加载都依赖它解析路径。

#### `reloadResources()`（DRGame.java:58）
- **功能**：在存储权限刚授予后**不重启应用**地把全部资源切换到外部根：先 `world.save()` 保存当前会话 → 重载零件表 → `ModManager.reset()` → 使各 lua 桥接缓存失效（`FlameScript`/`TerrainScript`/`PhysicsScript`/`JointScript` 的 `invalidate()`）→ dispose 并重建三张图集 → dispose 并重建 `GameWorld` → `world.load()` 恢复会话。
- **要点**：调用方是 `AndroidLauncher.onResume()`；会话"先存后取"保证热切换不丢进度。

#### `setTargetHeading(double rad)` / `getTargetHeading()`（DRGame.java:79-80）
- **功能**：转向环（steering ring）的便捷委托，直接转发到 `world.setTargetHeading` / `world.getTargetHeading`。
- **参数/返回值**：`rad` 为目标航向角（弧度）；getter 返回当前目标航向。

#### `dispose()`（DRGame.java:83）
- **功能**：退出时先 `world.save()` 再 `world.dispose()`，随后按序释放 batch、shapes、字体、ui、三张图集（`runtimeSprites` 判空），最后 `super.dispose()`。

---

## 4. MenuScreen（主菜单）

**职责**：应用启动后的第一个 screen（继承 `ScreenAdapter`）。用 scene2d `Table` 纵向排布标题、副标题和三个 400×88 的大按钮：**Build New Rocket**（进 `EditorScreen`，传 `null` 表示新建火箭）、**Continue Sandbox**（`world.load()` 后进 `SandboxScreen`）、**Reset World**（清存档并清空世界）。

### 逐函数说明

#### `MenuScreen(DRGame game)`（MenuScreen.java:21）
- 构造器，仅保存 `game` 引用；UI 实际在 `show()` 中搭建。

#### `show()`（MenuScreen.java:26）
- **功能**：创建 `Stage`（`ScreenViewport`）并设为输入处理器；根 `Table` 铺满屏幕，背景用 `game.ui.tinted()` 染深蓝灰 `(0.05, 0.07, 0.12)`。三个按钮的行为：
  - **Build New Rocket** → `game.setScreen(new EditorScreen(game, null))`（MenuScreen.java:44）；
  - **Continue Sandbox** → `game.world.load()` 后 `setScreen(new SandboxScreen(game))`（MenuScreen.java:49-50）；
  - **Reset World** → `game.world.clearSave()`，销毁 `world.ships` 中全部 `Ship` 并清空列表、`active=null`、`setTime(0)`（MenuScreen.java:55-59）。注意销毁时先复制一份列表（`new ArrayList<>(...)`）避免遍历时修改。
- **要点**：Reset 不切换 screen，停留在菜单。

#### `render(float delta)`（MenuScreen.java:71）
- 清屏为深蓝灰底色后 `stage.act(delta)` + `stage.draw()`。

#### `resize(int w, int h)`（MenuScreen.java:79）
- `stage.getViewport().update(w, h, true)`，居中适配。

#### `dispose()`（MenuScreen.java:84）
- 释放 `stage`（判空）。

---

## 5. Ui（程序化 scene2d 皮肤）

**职责**：不依赖任何 GUI 图集美术资源，用一张 8×8 纯白 `Texture` + `tinted()` 染色，在代码里构建完整的 scene2d `Skin`（Label / TextButton / TextField / Slider / ScrollPane / List / SelectBox / Window 八种样式）。全游戏的 UI 控件样式都取自 `Ui.skin`。

### 逐函数说明

#### `Ui(BitmapFont font)`（Ui.java:21）
- **功能**：构造皮肤。先创建白色底图 `white`（8×8 RGBA8888，Pixmap 用完即 dispose），注册进 skin 为 `"white"`，字体注册为 `"default"`。随后逐个注册样式：
  - `LabelStyle`：白字（Ui.java:32）；
  - `TextButtonStyle`：up/down/over 三态灰蓝底色；**checked 态被刻意设为与 up 相同**（Ui.java:44）——注释说明 round 14 的修复：scene2d `Button` 每次点击都会切换 checked，导致松手后按钮卡在变色状态，把 checked 画成 up 的样子即恢复"瞬时反馈只来自 down/over"（Ui.java:40-43）；
  - `TextFieldStyle`：深色背景、白光标、半透明蓝选区（Ui.java:48-54）；
  - `SliderStyle`（注册名 `"default-horizontal"`）：深蓝轨道 + 蓝色 knob 三态（Ui.java:56-61）；
  - `ScrollPaneStyle` / `ListStyle` / `SelectBoxStyle`（复用前两者）/ `WindowStyle`（Ui.java:63-93）。
- **参数**：`font` —— 已按 `DRGame.FONT_SCALE` 缩放的共享字体，所有控件共用。

#### `tinted(Color c)`（Ui.java:96）
- **功能**：返回白色底图染色后的 `TextureRegionDrawable`。
- **参数**：`c` —— 目标颜色。
- **返回值**：`Drawable`，可直接用作任何样式的背景/旋钮/光标。
- **要点**：全项目 UI 着色的唯一入口；`MenuScreen` 的背景也用它。

#### `dispose()`（Ui.java:101）
- 释放 `skin` 与 `white` 纹理。

---

## 6. AtlasPack（TexturePacker XML 图集解析）

**职责**：解析 CodeAndWeb TexturePacker 导出的 XML 图集（sprite 属性 `n,x,y,w,h`，y 从左上角量起），把每个 sprite 包成 `TextureRegion` 供按名查找。特别处理 `r="y"` 标记的**打包旋转**（图集中顺时针转了 90°，XML 里的 w/h 是旋转后的矩形，原图实为 h×w），并能把旋转的 sprite 烘焙回正立方向的独立纹理。

### 成员字段（AtlasPack.java:20-23）

| 字段 | 说明 |
|---|---|
| `texture: Texture`（public final） | 整张大图，Linear 过滤 |
| `regions: Map<String, TextureRegion>` | 名称 → 图集区域 |
| `rects: Map<String, int[]>` | 名称 → 打包矩形 `{x,y,w,h}` |
| `rotated: Map<String, Boolean>` | 名称 → 是否打包旋转 |

### 逐函数说明

#### `AtlasPack(FileHandle xmlFile)`（AtlasPack.java:25）
- **功能**：解析 XML，读 `imagePath` 找到同目录大图创建 `Texture`（Linear/Linear 过滤），再遍历每个 sprite 子节点填充三张表。
- **异常**：解析失败抛 `RuntimeException("Failed to parse atlas ...")`（AtlasPack.java:43）。
- **要点**：构造即加载，失败直接崩——图集是硬依赖。

#### `find(String name)`（AtlasPack.java:48）
- **功能**：按名查 `TextureRegion`；精确匹配不到时做**大小写不敏感**全表扫描（XML 里 .png/.PNG 混用，AtlasPack.java:47 注释）。
- **返回值**：找不到返回 `null`。

#### `canonical(String name)`（private，AtlasPack.java:59）
- 与 `find` 同款的大小写不敏感查找，但返回表中的**规范键名**，供 `isRotated`/`extractUnrotated` 复用。

#### `isRotated(String name)`（AtlasPack.java:70）
- **返回值**：sprite 在图集中是否被旋转 90° 存储；未知名称返回 `false`。

#### `extractUnrotated(String name)`（AtlasPack.java:80）
- **功能**：把某 sprite 从图集拷出为独立 `Texture`，若是 `r="y"` 则逐像素逆时针旋转 90° 还原（映射 `O(X,Y) = P(w-1-Y, X)`，输出 h 宽 × w 高，AtlasPack.java:93-99），使结果可以直接正立绘制。
- **返回值**：新 `Texture`（Linear 过滤）；**调用方负责 dispose**；未知名称返回 `null`。
- **要点**：走 `texture.getTextureData().prepare() + consumePixmap()` 回读显存，属于慢操作，适合初始化期一次性烘焙，不适合每帧调用。

#### `dispose()`（AtlasPack.java:109）
- 只释放整图 `texture`；`extractUnrotated` 产出的独立纹理归调用方管理。

---

## 7. Json（极简 JSON 读写器）

**职责**：存档系统专用的自包含 JSON 实现，含一个美化输出的 writer 和一个递归下降 parser。刻意不引 gdx-json，以规避其在 Android 上的怪癖（Json.java:8 注释）。功能只覆盖存档所需，不支持注释等非标扩展。

### 7.1 `Json.Writer`（Json.java:13）

链式 JSON 写出器，内部 `StringBuilder` + 缩进状态机。

| 方法 | 功能 |
|---|---|
| `Writer()` / `Writer(boolean pretty)`（Json.java:19-20） | 构造；`pretty=false` 时紧凑输出（无换行缩进、冒号后无空格） |
| `obj()` / `endObj()`（Json.java:34-35） | 开始/结束对象；空对象 `{}` 不换行，非空对象收尾前换行对齐 |
| `arr()` / `endArr()`（Json.java:36-37） | 开始/结束数组，规则同上 |
| `key(String k)`（Json.java:39） | 写一个键（自动处理逗号与换行），之后必须紧跟一个 `val`/`obj`/`arr` |
| `val(String/double/long/int/boolean)`（Json.java:48-52） | 写字面量。**注意**：`double` 为 NaN 或 Infinite 时写出 `0`（Json.java:49），保证存档永远是合法 JSON |
| `set(String k, ...)`（Json.java:54-58） | `key(k); val(v)` 的五种重载便捷组合 |
| `toString()`（Json.java:78） | 取出完整 JSON 文本 |

转义规则（`quote`，Json.java:60-76）：`"` `\` `\n` `\r` `\t` 走标准转义，其余 <32 的控制字符输出 `\uXXXX`。

### 7.2 `Json.Value` / `Json.JObj`（Json.java:82-123）

解析结果树。`Value.o` 的实际类型为 `String / Double / Boolean / List<Value> / JObj / null`（Json.java:83）。

- `Value` 访问器：`isObj/asObj`、`isArr/asArr`、`asStr()`、`asNum(double def)`（字符串会尝试 `Double.parseDouble`，失败回默认值）、`asInt(int def)`、`asBool(boolean def)`（Json.java:87-103）。
- `JObj` 是**保序**的平行双列表 `keys`/`vals`（不是 Map），提供 `put/get/has` 与带默认值的 `getStr/getNum/getInt/getBool/getObj/getArr`（Json.java:106-122）。键查重由调用方保证。

### 7.3 `Json.parse(String text)`（Json.java:125）

- **功能**：解析一段文本为 `JObj`。解析后若还有非空白残余，或根节点不是对象，抛 `RuntimeException`。
- **返回值**：根 `JObj`。
- **要点**：内部 `Parser`（Json.java:134-221）是标准递归下降：`parseValue/parseObj/parseArr/parseStr/parseBool/parseNum`。字符串支持 `\uXXXX`（Json.java:201）；数字扫描字符集 `0-9.eE+-`，直接用 `Double.parseDouble`（Json.java:215-220），对格式错误的数字由它抛异常。

---

## 8. Xml 与 Vec2d（小工具类）

### `Xml`（Xml.java）

- **职责**：libGDX `XmlReader.Element` 没有 `getDoubleAttribute`，这个只有静态方法的类补上缺口（Xml.java:5）。
- `static double getDouble(XmlReader.Element e, String name, double def)`（Xml.java:9）：读属性字符串，`trim()` 后 `Double.parseDouble`；属性缺失或解析失败均返回 `def`。全项目读 XML 数值属性的统一入口（行星参数、零件定义等）。

### `Vec2d`（Vec2d.java）

- **职责**：可变双精度 2D 向量，用于宇宙坐标（float 精度在太阳系尺度不够用）。所有运算**就地修改并返回 this**，可链式调用，调用方需注意别名问题。
- 构造：`Vec2d()` / `Vec2d(double x, double y)` / `Vec2d(Vec2d o)`（Vec2d.java:7-9）。
- 就地运算：`set(x,y)` / `set(Vec2d)` / `add(x,y)` / `add(Vec2d)` / `sub(Vec2d)` / `mul(double s)`（Vec2d.java:11-16）。
- 只读度量：`len()`、`len2()`（平方长度，免开方）、`dist(Vec2d)`、`dist2(Vec2d)`（Vec2d.java:18-21）。
- `nor()`（Vec2d.java:23）：就地归一化；长度 < 1e-12 时不动（防除零）。
- `angleRad()`（Vec2d.java:25）：`atan2(y,x)` 方向角。
- `static Vec2d fromAngle(double rad, double len)`（Vec2d.java:27）：由方向角和长度构造新向量。

---

## 9. TerrainScript（terrain.lua 桥接）

**职责**：把行星地形生成桥接到玩家可修改的 `mod/terrain.lua`（静态类，全 static）。lua 侧 API 为 `terrainHeight(planetName, angleRad) -> 高度米数` 与 `surfaceHeight(planetInfo条目, xArcMeters) -> 绝对半径`；Java 侧注入静态 `planetInfo` 表（每行星的 minHeight/maxHeight/noise/ranges）和确定性噪声函数表 `noise`。**渲染 chunk 网格与碰撞高度场走同一个函数**（`Planet.heightAt`），所以视觉与碰撞永远一致。任何 lua 出错只记一次日志，之后由内置生成器接管（返回值 `NaN` 即"用内置"的约定）。

### 成员字段（TerrainScript.java:26-28）

| 字段 | 说明 |
|---|---|
| `script: LuaScript` | 包装 `terrain.lua` 的加载/热重载 |
| `bound: Globals` | 已注入 planetInfo/noise 的 lua Globals（热重载后身份变化，需重新注入） |
| `callFailed: boolean` | 本代 Globals 是否已出过错（出错后短路，内置接管） |

### 逐函数说明

#### `invalidate()`（TerrainScript.java:32）
- 标记脚本需重载（外部资源根切换或文件变更时由 `DRGame.reloadResources()` 等调用）。

#### `ensureBound(GameWorld world)` / `ensureBound(List<Planet> planets)`（TerrainScript.java:35, 40）
- **功能**：脚本重载后重新注入 `planetInfo` 与 `noise`。若 `Globals` 实例与 `bound` 相同则直接返回（幂等）。List 版可在没有完整 GameWorld/GL 上下文时使用（如单元测试）。
- **注入内容**：
  - `planetInfo`：以行星名为键的表，含 `name/radius/minHeight/maxHeight/noise/ranges[]`；`ranges` 为 1-based 数组，每项 `startAngle/endAngle/minHeight/maxHeight`（TerrainScript.java:47-68）；
  - `noise.value1(x, period, seed)`：无缝 1D value noise（[-1,1]）；`noise.value2(x, y, seed)`：2D value noise；`noise.hash(string)`：Java 兼容字符串哈希（供 lua 派生行星种子）（TerrainScript.java:70-88）。
- **要点**：注入本身被 `LuaError` 兜底，失败置 `bound=null`（TerrainScript.java:90-93）。

#### `heightAt(String planetName, double angleRad)`（TerrainScript.java:97）
- **功能**：调用 lua `terrainHeight(planetName, angleRad)`，返回地表相对高度（米）。
- **返回值**：结果或 `Double.NaN`（未绑定 / 函数不存在 / 调用出错——一律回退内置生成器）。出错只记一次日志（`callFailed` 门闩，TerrainScript.java:104-109）。

#### `surfaceHeight(String planetName, double xArcMeters)`（TerrainScript.java:121）
- **功能**（round 18 引入）：柱状地表函数。调 lua `surfaceHeight(info, xArcMeters)`，返回**绝对半径**（R + 地形高度），同时驱动 `TerrainSystem` 的渲染网格与碰撞四边形列；`TerrainSystem` 按 junction 缓存结果，所以此函数只为**新 junction** 调用。
- **参数**：`xArcMeters` —— 沿地表弧长的水平坐标（米）。
- **返回值**：绝对半径米数，或 `NaN` 回退内置。

#### `heightAboveDatum(String planetName, double angleRad)`（TerrainScript.java:148）
- **功能**（round 18 修复）：给定地表**角度**，返回相对名义半径的高度。内部走 `surfaceHeight`（把 `angleRad * radius` 换成弧长再减回 radius，TerrainScript.java:158-159），保证高度计、出生平台、铁轨地板、水面等所有玩法查询与柱状碰撞/渲染地形一致——包括 specialTerrains 区域（旧 `terrainHeight` 路径不认识这些区域，飞船可能停在可见特殊地形上方/下方的隐形平面上）。
- **返回值**：高度（米）或 `NaN`（调用方回退 terrainHeight/内置）。

#### `loadedToken()`（TerrainScript.java:171）
- **返回值**：当前已加载 `terrain.lua` Globals 的身份 token（热重载后变化），供调用方检测"脚本换了一代"。

#### 确定性噪声（TerrainScript.java:173-203）

与 `Planet` 内置生成器完全镜像的实现，同时暴露给 lua：

- `hash(double i, double seed)`（private，TerrainScript.java:175）：经典 `sin` 哈希，`fract(sin(i*127.1 + seed*311.7) * 43758.5453)`。
- `valueNoise1(double x, double period, double seed)`（TerrainScript.java:181）：**无缝** 1D value noise，格点按整数 `period` 取模回绕（首尾相接，适合环绕行星一周的角度域），smoothstep 插值，输出 [-1,1]。
- `valueNoise2(double x, double y, double seed)`（TerrainScript.java:192）：2D value noise，四格点哈希 + 双方向 smoothstep 双线性插值，输出 [-1,1]。

---

## 10. SteeringIO（转向输入共享状态）

**职责**：仅 8 行的共享状态类（SteeringIO.java:3-8）：sandbox UI（转向环/按钮）写入、转向与引擎控制逻辑读取的输入状态，解耦 UI 与飞控。

| 成员 | 说明 |
|---|---|
| `static volatile boolean ringActive` | 转向环是否激活中 |
| `static volatile int buttonTurn` | 按钮转向：-1 左 / 0 无 / +1 右（按住期间有效） |
| `static volatile double targetHeadingRad` | 目标航向角（弧度） |
| `static boolean hasTarget()` | 当前等价于 `ringActive`（SteeringIO.java:7） |

**要点**：字段为 `volatile`，UI 线程写、逻辑线程读无需额外同步；这是全局单点状态，同时只有一艘受控飞船的假设成立时才安全。
