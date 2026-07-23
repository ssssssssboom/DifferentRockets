# API 参考：SandboxScreen（飞行/沙盒视图）

- 源文件：`game/core/src/com/differentrockets/ui/SandboxScreen.java`（共 1821 行，下文引用简写为 `SandboxScreen.java:行号`）
- 包：`com.differentrockets.ui`
- 基类：`com.badlogic.gdx.ScreenAdapter`

## 1. 类职责概述

`SandboxScreen` 是游戏的**飞行视图（Flight view）**，职责覆盖三块（`SandboxScreen.java:41-45`）：

1. **渲染沙盒世界**：行星大气光晕与本体、地形（`world.terrain.render`）、飞船与引擎火焰、选中零件高亮、油箱液位、气动阻力合力叠加层（CoP + 总矢量）、星空背景。
2. **飞行输入**：键盘 + 屏幕按钮 + 多点触控。包括节流阀（10 段条）、转向环（Steering ring）拖拽设定目标航向、左右转向键按住缓动、单指点击选零件（tap-to-activate）、双指平移/捏合缩放、STAGE 分离。
3. **Map 视图与时间加速**：地图相机、行星轨道环、飞船轨道预测线（`OrbitPredictor`，约 15 Hz 重新外推）、参考系锚点选择（FRAME 按钮）、时间加速阶梯（warp ladder）。

渲染每帧由 `render(float delta)` 驱动：先推进世界仿真（`game.world.update`），再按 `mapMode` 分流到 `renderMap()` / `renderFlight()`，最后画转向环、HUD Stage 与遥测文本。

### 精度设计要点（接手必读）

- **地图相机使用双精度中心** `mapCX/mapCY`，float 版 `mapCam.position` 仅派生：宇宙坐标 ~1e10 m 时 float32 只有 ~1 km 分辨率，会造成预测折线抖动/分段（`SandboxScreen.java:69-73`）。
- **Round 18 抖动修复**：`mapCam.position` 恒为原点，所有世界坐标绘制前先减去 `(mapCX, mapCY)` 再转 float（`SandboxScreen.java:1540-1547`）。
- **轨道预测折线每帧重新锚定**：`OrbitPredictor` 输出偏移链式折线 `xs-fx+off`，绘制时叠加锚定天体当前位置，因此 15 Hz 外推也能 60 Hz 平滑跟随行星（`SandboxScreen.java:1507-1515, 1700-1716`）。

## 2. 重要字段表

| 字段 | 类型 | 说明 |
|---|---|---|
| `game` | `DRGame` | 游戏主类引用，`game.world` 为物理世界（`SandboxScreen.java:47`） |
| `stage` | `Stage` | Scene2D HUD 舞台（ScreenViewport） |
| `cam` | `OrthographicCamera` | **飞行相机**，viewportHeight 默认 45 m（`SandboxScreen.java:49,117`），位置跟随活跃飞船 COM + `camPan` 偏移 |
| `mapCam` | `OrthographicCamera` | **地图相机**，位置恒为原点，视口高度范围 [1000, 1.2e12] m（`SandboxScreen.java:374`） |
| `mapMode` | `boolean` | true = Map 视图，false = 飞行视图 |
| `mapCX, mapCY` | `double` | 地图相机**双精度**中心（世界坐标），所有 pan/zoom/tap 直接改它（`SandboxScreen.java:73`） |
| `anchorIndex` | `int` | 地图轨道参考系锚点：`game.world.planets` 索引，-1 = Auto（自动取飞船主导引力体）；由 FRAME 按钮弹出列表选择（`SandboxScreen.java:52-58`） |
| `lastAnchorBody` / `lastAnchorX/Y` | `Planet` / `double` | 相机跟随状态：地图中心随锚定天体逐帧移动，换锚点时不跳变（`SandboxScreen.java:62-63`） |
| `mapInit` | `boolean` | 进入地图后是否已做过首次 auto-fit |
| `mapPanPointer`, `mapPanLastX/Y`, `mapPanDist`, `mapPanned` | 地图单指平移手势状态（`SandboxScreen.java:65-68`） |
| `camPan` | `Vector2` | 飞行相机双指平移偏移（世界米）；CENTER 按钮清零（`SandboxScreen.java:76,265`） |
| `touchA/touchB`, `paX/paY/pbX/pbY` | 多点触控：活跃 pointer id 与上一帧屏幕坐标（`SandboxScreen.java:77-78`） |
| `ringDrag` | `boolean` | 是否正在拖拽转向环 |
| `ringX/ringY/ringR` | `float` | 转向环屏幕坐标中心与半径 = `0.26 * min(w,h)`（`SandboxScreen.java:1395-1397`） |
| `ringMat` | `Matrix4` | 屏幕空间 ortho 投影矩阵（转向环、地图标签、阻力数值标签共用） |
| `slewDir` | `int` | -1/0/+1，转向键按住期间目标航向以 ~45°/s 缓动（`SandboxScreen.java:84,988-992`） |
| `selectedPart` | `Part` | tap 选中的零件（tap-to-activate） |
| `tapCandidate/tapMoved/tapDist` | 单指点击 vs 拖拽判定状态（阈值 12 px） |
| `ringDelta` | `double` | 抓手偏移：按下点角度与目标航向之差，拖拽时保持环不跳（`SandboxScreen.java:91`） |
| `dragOverlay` | `boolean` | 气动阻力合力叠加层开关，默认开（`SandboxScreen.java:95`） |
| `predictor` | `OrbitPredictor` | 轨道预测器，字段含 `count/xs/ys/fx/fy/anchor` |
| `ORBIT_INTERVAL` | `float` | 重新外推间隔 1/15 s（round 18 由 4 Hz 提至 15 Hz）（`SandboxScreen.java:100`） |
| `orbitTimer` | `float` | 置 `Float.MAX_VALUE` 表示强制下一帧立即重新外推 |
| `telemetry` / `stageLabel` | `Label` | 遥测文本 / 级次与选中零件状态行 |
| `warpLabel` | `Label` | 当前 warp 档位文本（声明在 `SandboxScreen.java:300`） |
| `frameBtn` / `frameList` | FRAME 锚点按钮 / 弹出的天体选择列表 |
| `throttle` | `SegmentedThrottle` | 内部类，10 段节流阀条 |
| `starTex` / `stars` / `atmoTex` | 程序生成的星星 2x2 纹理、300 颗随机星（种子 42）、256x256 大气光环纹理（`SandboxScreen.java:119-149`） |
| `lastSimDt` | `float` | 当前帧的仿真秒数（暂停时为 0，含 warp 倍率），供火焰粒子与 lua 火焰脚本使用 |
| `tmp3` / `tmp2` | 临时 `Vector3`/`Vector2`，避免逐帧分配 |

## 3. 逐函数参考

### 3.1 生命周期

#### `SandboxScreen(DRGame game)` — 构造函数
`SandboxScreen.java:114`

- **参数**：`game` 游戏主类。
- **逻辑**：创建 `cam`（viewportHeight=45）与 `mapCam`；用固定种子 42 生成 300 颗随机星 `{x, y, alpha}`；程序生成 256x256 大气光环纹理 `atmoTex`（r≈0.82 处最亮、内圈保底 0.05 alpha 的环形渐变，`SandboxScreen.java:129-149`）。
- **返回**：无。

#### `show()`
`SandboxScreen.java:153`

- 创建 `Stage`、调用 `buildHud()` 建 UI；用 `InputMultiplexer` 组合 stage 与 `GameInput`；按当前窗口尺寸调用 `resize()`。

#### `render(float delta)`
`SandboxScreen.java:987`

- **参数**：`delta` 帧间隔秒。
- **逻辑要点**：
  1. `slewDir != 0` 时目标航向以 45°/s 缓动（`setSteerTarget`）；
  2. `fd = min(delta, 1/20)` 后 `game.world.update(fd)` 推进仿真；
  3. 镜像 `SteeringIO.targetHeadingRad = world.getTargetHeading()`（PI 控制器可能自行重置目标，如出生/换船）；
  4. `lastSimDt = paused ? 0 : fd * warp`，`FlameFx.update(lastSimDt)` 推进粒子；
  5. 清屏（深蓝 0.02/0.03/0.07）→ `mapMode ? renderMap() : renderFlight()` → 两种视图都画 `drawSteeringRing()`（地图视图下环保持可交互，round 13）→ `updateTelemetry()` → `stage.act/draw`。

#### `resize(int w, int h)`
`SandboxScreen.java:1810`

- 更新 stage viewport 与相机 viewport。

#### `dispose()`
`SandboxScreen.java:1816`

- 释放 stage、`starTex`、`atmoTex`。

### 3.2 HUD 构建与按钮

#### `buildHud()`（private）
`SandboxScreen.java:165`

- 构建全部 HUD：遥测 Label（fontScale 1.0）、级次 Label、MAP / FRAME:Auto / DRAG:on / II 暂停 / Menu（`world.save()` 后回 `MenuScreen`）按钮、warp 阶梯 `-` `+` 与 `warpLabel`、`SegmentedThrottle` 节流条、STAGE/ACTIVATE 大按钮、左右转向 `holdBtn`、缩放与 CENTER 按钮。
- 布局为竖屏结构：顶部遥测行 + 右上按钮行 + 右缘节流条 + 底部两行按钮（`SandboxScreen.java:268-298`）。
- DRAG/FRAME 按钮状态显示在**文本**中，保持松手回灰（round 14）。

#### `warpStep(int dir)`（private）
`SandboxScreen.java:303`

- 沿 `game.world.WARP_LEVELS` 阶梯上/下一档（1x 2x 4x 物理，之后 25x..250000x on-rails），取 ≤ 当前 warp 的最大档位再加 `dir`，clamp 到数组端点；写回 `world.warp` 并刷新标签。

#### `refreshWarpLabel()`（private）
`SandboxScreen.java:314`

- 设置 `warpLabel` 文本为 `"<warp>x"`（空指针安全）。

#### `warpBtn(String label, int w)`（private）
`SandboxScreen.java:318`

- 生成一个直接设置 `world.warp = w` 的按钮。**注意：当前代码中已无调用方**（被 warpStep 阶梯取代），属遗留工具方法。

#### `holdBtn(String label, int dir)`（private）
`SandboxScreen.java:328`

- 生成按住式转向按钮：`touchDown` 置 `slewDir = dir` 并 `syncButtonTurn()`，`touchUp` 归零。
- 返回 `TextButton`。

#### `syncButtonTurn()`（private）
`SandboxScreen.java:352`

- SteeringIO 契约：转向键按住期间写 `SteeringIO.buttonTurn`。注意**符号翻转**：`slewDir=+1` 是机头左转，对应 `buttonTurn = -1`（+1 为右）。

#### `setSteerTarget(double rad)`（private）
`SandboxScreen.java:357`

- 转向目标的**唯一写入路径**：同时写 `world.setTargetHeading(rad)` 与 `SteeringIO.targetHeadingRad`。

#### `doStage()`（private）
`SandboxScreen.java:362`

- 对活跃飞船调用 `activateStage()`，`stageLabel` 显示 "Stage N fired!" 或 "No stages left"。

#### `zoom(float f)`
`SandboxScreen.java:369`

- **public**。参数 `f` 为倍率（<1 放大、>1 缩小），非有限或 ≤0 直接返回。
- 地图模式：viewportHeight clamp 到 [1000, 1.2e12] m（可缩到全太阳系，Smeptune a≈4.5e11 m）；NaN 时回落 200000。
- 飞行模式：clamp 到 [8, 200000] m；NaN 时回落 45。随后 `updateCamViewport()`。

#### `clamp(float v, double lo, double hi)`（private static）
`SandboxScreen.java:383`

- 浮点 clamp 工具。

### 3.3 输入处理（内部类 GameInput）

`GameInput extends InputAdapter`，`SandboxScreen.java:389`。

#### `keyDown(int keycode)`
`SandboxScreen.java:391`

- 键位映射（桌面/Android 外接键盘）：
  - SPACE：分离；TAB：切换地图；P：暂停；
  - Z/X：节流阀 ±1 段；Shift/Ctrl：`inputThrottle` ±0.05；
  - ←/A、→/D：`slewDir = ±1`（左转/右转）+ `syncButtonTurn()`；
  - 逗号/句号：缩放 0.8/1.25；数字键 1-9：直接设 warp（1, 2, 4, 25, 100, 1000, 7500, 50000, 250000）。
- 返回 true 表示消费。

#### `keyUp(int keycode)`
`SandboxScreen.java:418`

- 松开方向键时把对应符号的 `slewDir` 归零并 `syncButtonTurn()`。

#### `scrolled(float amountX, float amountY)`
`SandboxScreen.java:433`

- 滚轮缩放：向下 1.15、向上 0.87。

#### `touchDown(int screenX, int screenY, int pointer, int button)`
`SandboxScreen.java:439`

- 多点触控调度：
  - 已有两指则忽略第三指；
  - 第一指：地图模式下若 `nearRing()` 命中环则开始转向（`SteeringIO.ringActive=true`，目标重置为当前航向，记录 `ringDelta` 抓手偏移），否则进入地图平移/点按状态；飞行模式同样环优先，否则记为 tap 候选；
  - 第二指：进入双指手势，取消环拖拽与 tap，置 `mapPanned` 抑制随后的地图点按。

#### `touchDragged(int screenX, int screenY, int pointer)`
`SandboxScreen.java:484`

- 双指在场 → `twoFinger()`；单指时：地图模式下环拖拽走 `steerRingTo()`，否则直接改双精度 `mapCX/mapCY` 平移（移动超 12 px 记 `mapPanned`）；飞行模式下环拖拽转向，tap 候选累计位移超 12 px 判定为移动。

#### `touchUp(int screenX, int screenY, int pointer, int button)`
`SandboxScreen.java:527`

- B 指先抬：B 清除，A 指位置用 `Gdx.input.getX/Y(touchA)` 重新锚定（避免相机跳变）。
- A 指先抬且 B 仍在：**B 提升为主指**（避免查询已失效 pointer id 导致 Android 崩溃）。
- 最后：地图模式下若非平移且非环拖拽 → `mapTap()`；飞行模式下若为未移动 tap → `flightTap()`。

#### `twoFinger(float x, float y, boolean movedIsA)`（private，GameInput 内）
`SandboxScreen.java:567`

- 双指手势：中点移动平移、双指间距比捏合缩放（锚定中点）。
- 地图模式：平移直接改 `mapCX/mapCY`；缩放时把中点下的世界点（double 计算）在缩放前后保持不动，viewport clamp [1000, 1.2e12]。
- 飞行模式：`panCamera(cam, camPan, ...)` 平移；缩放前 unproject 中点世界坐标，缩放后把该点锚回原屏幕位置（改 `camPan` 补偿）。拒绝 NaN/Inf（曾因退化手势数据崩溃）。

#### `panCamera(OrthographicCamera c, Vector2 offset, fromX, fromY, toX, toY)`（private，GameInput 内）
`SandboxScreen.java:621`

- 把屏幕空间拖拽 delta unproject 成世界 delta；`offset != null` 时累加到 `offset`（飞行相机用 `camPan`），否则直接改相机 `position`。

### 3.4 手势辅助与选中

#### `nearRing(float sx, float sy)`（private）
`SandboxScreen.java:641`

- 判断屏幕点是否落在转向环带内：到环心距离在 `(0.55·ringR, 1.6·ringR)` 之间（y 轴翻转为 y-up）。

#### `ringAngle(float sx, float sy)`（private）
`SandboxScreen.java:648`

- 屏幕点对应的环上角度（航向约定）：机头方向为 `(-sinθ, cosθ)`，返回 `atan2(-vx, vy)`。

#### `steerRingTo(float sx, float sy)`（private）
`SandboxScreen.java:656`

- 环拖拽转向：离环心过近（<2 px）忽略；否则 `setSteerTarget(ringAngle - ringDelta)`，即目标航向按抓手偏移跟随手指而非绝对吸附。

#### `mapTap(int screenX, int screenY)`（private）
`SandboxScreen.java:666`

- 地图点按：
  1. 锚点列表打开时，任何点按只收起列表；
  2. 用双精度中心把屏幕点换算成世界坐标；
  3. 距点 5% 视口高内最近的**其他飞船** → `world.setActive(best)` 并退出地图；
  4. 否则若点在行星 `max(1.5·radius, 3% 视口高)` 内 → 地图中心移到该行星。

#### `flightTap(int screenX, int screenY)`（private）
`SandboxScreen.java:703`

- 飞行视图点按选零件（item 6b）：
  - unproject 得世界点，阈值 = `max(3 m, 64 px 对应的世界长度)`；
  - 遍历活跃飞船所有 Part 的所有 `PolygonShape` fixture 的**每条边**，用 `Attach.closestOnSegment` 求点到线段最近距离（最近边胜出，细长零件如 strut 与粗油箱一样好点）；
  - 选中结果存 `selectedPart`；未命中任何零件时 `SteeringIO.ringActive = false`（关闭转向环，引擎回中）；
  - `stageLabel` 显示 "Selected <name> [group N]"。

#### `activateSelected()`
`SandboxScreen.java:741`

- **public**。ACTIVATE 按钮：对 `selectedPart` 及其 activation group（`group > 0` 且同组）触发 `callOnStage()`。
- 先快照目标列表（detacher 的 onStage 会延迟拆分飞船、改 parts 列表，边迭代边改曾导致崩溃）；逐个调用前检查零件未被销毁/移走；最后 `world.processDeferredStructure()` 立即应用分离/拆分。
- 无选中时提示 "Tap a part first, then ACTIVATE"。

#### `debugSelectPart(Part p)`
`SandboxScreen.java:769`

- 测试钩子：不模拟 tap 直接选中零件；传 null 时清空状态行（对齐 tap 路径的取消选中行为）。

#### `setThrottle(double v)`（private）
`SandboxScreen.java:774`

- 写 `world.inputThrottle`，clamp 到 [0,1]。

### 3.5 分段节流阀

#### `throttleLevel()`（private）
`SandboxScreen.java:781`

- 当前节流阀段 0..10：`round(inputThrottle * 10)`。

#### `setThrottleLevel(int k)`（private）
`SandboxScreen.java:785`

- 按段设置节流阀，clamp 后 `inputThrottle = k/10`。

#### `throttleLevelForTest()` / `setThrottleLevelForTest(int k)`
`SandboxScreen.java:791-792`

- 冒烟测试钩子：按整段读写节流阀。

#### 内部类 `SegmentedThrottle extends Actor`
`SandboxScreen.java:804`

- 10 段节流条（round 11 item 10）：`ThrottleControl.png` 为轨道框，`ThrottleLevel{1..10}.png` 为各段白色 alpha-mask 精灵（随段位变宽），自底向上堆叠；点亮段染绿色、未点亮染暗红；图集缺失时退化为纯色矩形（用 `starTex` 拉伸）。
- **常量**：`SEGMENTS = 10`。
- 成员方法（均 private 或包内）：
  - 构造 `SegmentedThrottle()`（`SandboxScreen.java:810`）：注册 InputListener，touchDown/touchDragged 都调 `scrub(y)`。
  - `scrub(float y)`（`SandboxScreen.java:823`）：按 y 归一化位置取段数 `round(yNorm * 10)`，11 档（0..100% 步进 10%）。
  - `ensureSprites()`（`SandboxScreen.java:828`）：`game.runtimeSprites` 图集换载时重建纹理（旧纹理 dispose），`extractUnrotated` 取未旋转的段精灵。
  - `draw(Batch, float parentAlpha)`（`SandboxScreen.java:843`）：先 `ensureSprites()`；有图集时画轨道框 + 按段宽高比居中绘制 10 段；无图集时画 10 个纯色矩形条。

### 3.6 地图模式与锚点列表

#### `toggleMap()`
`SandboxScreen.java:882`

- **public**。切换 `mapMode`；进入地图时置 `mapInit=false`（触发 `renderMap` 中的 auto-fit）、清 `lastAnchorBody`（相机重新锚定不跳变）、强制立即重新外推；退出地图时收起锚点列表（避免残留悬浮在飞行视图上）。

#### `gOn(Vec2d p, Planet b)`（private static）
`SandboxScreen.java:894`

- 天体 `b` 对位置 `p` 处飞船的引力加速度 `mu/r²`，锚点列表排序键。

#### `toggleFrameList()`（private）
`SandboxScreen.java:907`

- 打开/收起地图锚点列表：列出所有 `mu() > 0` 的天体，按对活跃飞船的当前引力从强到弱排序，顶部加 "Auto (dominant)" 项（index -1）；定位在右上按钮栏下方；选中走 `selectAnchor()`，再次点 FRAME 或点地图仅收起。

#### `selectAnchor(int idx, String label)`（private）
`SandboxScreen.java:942`

- 设 `anchorIndex`、更新 FRAME 按钮文本、强制立即重新外推（轨道线瞬间切换参考系）、收起列表。

#### `autoFitMap()`（private）
`SandboxScreen.java:955`

- 地图首次打开的自动取景：以预测轨迹（draw-anchored 坐标 `xs-fx+anchor.pos`）的包围盒加 30% 余量定 viewport 高度，考虑屏幕宽高比；无预测时退回 `max(4·当前行星半径, 200000)`；最终 clamp [2e4, 1.2e12] m。

### 3.7 渲染（飞行视图）

#### `setFlightZoom(float viewportHeight)` / `flightZoomForTest()`
`SandboxScreen.java:1020,1026`

- 测试钩子：设置/读取飞行相机视口高度。

#### `flameParticlesForTest()` / `flameParticlesMaxForTest()`
`SandboxScreen.java:1029-1030`

- 测试钩子：火焰粒子池当前/历史峰值数量（Item 2 上限断言）。

#### `updateCamViewport()`（private）
`SandboxScreen.java:1032`

- 按屏幕宽高比同步两个相机的 viewportWidth 并 `update()`。

#### `renderFlight()`（private）
`SandboxScreen.java:1040`

- 飞行视图主渲染：
  1. 相机位置 = `camPan`（活跃飞船 COM 在世界原点附近）；`drawStars()`；
  2. 行星：batch 画大气光晕（`atmoTex`，超出视口 3 倍半径跳过），shapes 画本体圆（太阳固定金黄，其余用 `crustColor`）；
  3. `world.terrain.render(cam)` 画地形；
  4. 逐船 `drawShip()`（on-rails 的非活跃船跳过；活跃船即使 on-rails 也画）；
  5. `drawFlames()`；选中零件高亮圆（青色，半径 = 0.75·max(宽,高)）；
  6. `drawTankLevels()`、`drawDragOverlay()`；
  7. 30 km 内的其他飞船画橙色标记点。

#### `drawTankLevels()`（private）
`SandboxScreen.java:1140`

- 油箱实时液位叠加（round 9 item 5）：仅油箱类（`getFuelCapacity() > 0`）且屏幕高度 ≥24 px 时绘制；细长条（宽度的 16%）随零件 body 旋转；液位线上方画 30% 透明暗区，下方按燃料类型着色——液体 cyan（0.15/0.85/1）、电（fuelType 2）黄绿、固体（fuelType 3）橙。用 `levelQuad` 画两个三角组成的旋转四边形。

#### `drawDragOverlay()`（private）
`SandboxScreen.java:1179`

- 气动阻力合力叠加（item 6，`dragOverlay` 开关）：
  - 对活跃飞船（on-rails 跳过）逐零件按与物理 pass 相同的 ½ρv²·CdA·dragExposure 定律聚合：密度用玩家可改的 `world.densityAt`（mod/physics.lua），相对风速含 frame/originVel/body 速度减行星速度；
  - 求合力与力矩加权中心（CoP）；
  - 画 CoP 圆点 + 箭头（箭头长度对数缩放 `log10(1+F/2000)/1.5`，0.1 kN 与 500 kN 都可读）；
  - 箭头尖端用屏幕空间 ortho 画数值标签（N / kN / MN 自适应）。

#### `levelQuad(Vector2 c, float cos, float sin, x1, y1, x2, y2, r, g, b, a)`（private）
`SandboxScreen.java:1264`

- 在零件局部系画旋转四边形（两三角形），(x1,y1) 左下 (x2,y2) 右上。

#### `drawShip(Ship s)`（private）
`SandboxScreen.java:1276`

- 逐零件画精灵（`shipSprites.find(p.type.sprite)`，按 body 位置/角度旋转）；降落伞（`type.type == "parachute"` 且 `deployed`）在零件顶部画 22x22 伞盖。引擎火焰不在此画（见 drawFlames）。

#### `drawFlames()`（private）
`SandboxScreen.java:1301`

- 引擎火焰：优先走 lua 脚本 `mod/flame.lua`（`FlameScript.begin(lastSimDt)` 返回 false 表示脚本缺失/损坏 → 退回 `drawFlamesBuiltin()`）。
- lua 路径：逐引擎零件计算喷口世界点、含 gimbal 的喷焰方向（推力沿 (-sin,cos)，羽流反向）、喷口宽（0.3·宽·engine.size），调用 `FlameScript.drawPart(...)`（带喷口处气压/密度，供 Mach diamond/羽流膨胀），然后 `FlameScript.flush(shapes)` 画形状层、`flushSprites(batch)` 画贴图层；最后 `FlameFx.render(batch)` 画粒子池。

#### `drawFlamesBuiltin()`（private）
`SandboxScreen.java:1341`

- 内置三层羽流（fallback）：满油门长度 = 3.2× 引擎可视高度（ion 乘 0.8），每层带 ±15% 随机抖动；ion（fuelType 2）用蓝色系三层，化学引擎用蓝芯/橙中/白外三层，均调 `flameCone()`。

#### `flameCone(Vector2 nozzle, dx, dy, len, half, lenF, widF, r, g, b, a)`（private）
`SandboxScreen.java:1375`

- 单层火焰：顶点在喷口、向羽流末端展宽的三角形（lenF 长度系数、widF 宽度系数）。

#### `drawSteeringRing()`（private）
`SandboxScreen.java:1389`

- SimpleRockets 风格转向环（item 4），地图/飞行视图都画；活跃飞船 `landed` 时直接不画（round 19）。
- 内容：半透明白环（72 段）；当前→目标的最短误差弧（黄色 28 段）；当前航向白色径向刻度 + 环上白点；目标航向绿色刻度 + 外侧绿点；行星相对速度矢量（item 3）青色刻度 + 环上箭头（速度 >0.5 m/s 时）；环心十字点。
- 速度数值（`fmt(spd) m/s`）画在速度方位环外 1.22·ringR 处（屏幕空间）。
- 本函数同时**更新 `ringX/ringY/ringR`**，供 `nearRing`/`ringAngle` 命中测试使用。

#### `ringPtX(double heading)` / `ringPtY(double heading)`（private）
`SandboxScreen.java:1484-1485`

- 航向角 → 环上屏幕点：x = ringX - sinθ·ringR，y = ringY + cosθ·ringR（y-up）。

#### `drawStars()`（private）
`SandboxScreen.java:1487`

- 星空背景：屏幕空间 ortho，300 颗 2x2 白点按各自 alpha 绘制；随相机位置 2% 视差滚动，取模回绕屏幕。

### 3.8 渲染（Map 视图）

#### `renderMap()`（private）
`SandboxScreen.java:1505`

- 地图主渲染：
  1. 15 Hz 重新外推（`orbitTimer > ORBIT_INTERVAL` 或 `predictor.count == 0` 时 `predictor.compute(world, active, anchorIndex)`）；
  2. 首次进入（`!mapInit`）：中心置活跃飞船位置，`autoFitMap()`；
  3. 相机跟随锚定天体：锚点未变时把天体逐帧位移加到 `mapCX/mapCY`，换锚点只记录不移动（无跳变）；
  4. `mapCam.position` 置原点，之后所有世界绘制先减 `(mapCX,mapCY)`；
  5. 画星空、行星轨道环（有 parent 的，经 `drawOrbitPath`）、行星本体（半透明填充 + 描边，真半径圆）；
  6. `drawOrbitPrediction()`（注意其后要恢复 `mapCam.combined` 投影，因为预测线切到了屏幕空间 ortho——否则船箭头画在宇宙坐标不可见，round 14 修复）；
  7. 飞船箭头：方向取控制零件 body 角度（on-rails 无 body 时退回行星相对速度方向，默认朝上）；活跃船绿色、其余橙色；
  8. 行星/飞船名字标签画在屏幕空间（固定像素大小，任何缩放级别可读；过小天体按 `radius/viewportHeight < 0.0025` 跳过）。

#### `drawOrbitPath(Planet p)`（private）
`SandboxScreen.java:1660`

- 用 Kepler 方程在一个周期内采样 96 点，画行星相对 parent 的椭圆轨道环（相对双精度地图中心）。

#### `orbitRelAt(Planet p, double t)`（private）
`SandboxScreen.java:1681`

- 求行星在绝对时刻 `t` 相对 parent 的位置（镜像 `Planet.localPosVel` 的 rails 数学）：平近点角 `M = n·t + v0`（逆行取负），**round 14 把 M 包裹到 [-π, π]**（长 warp 后大 M + 高偏心率下 Newton 迭代从 E=M 出发会发散，轨道线折回自身）；Newton 迭代 12 次解偏近点角 E，按轨道参数 `a/e/w` 旋转输出。返回 `double[]{x, y}`。

#### `drawOrbitPrediction()`（private）
`SandboxScreen.java:1717`

- 画数值外推的飞船轨迹（item 10 / round 13）：
  - 整条折线用单一参考系（`predictor.anchor` 解析显式锚点或自动主导体）；世界位置 = `xs[idx]-fx[idx]+anchor.pos`（偏移链跨主导体切换连续）；
  - 抽取至 ≤2000 个 GL 点（stride）；前 30% 实线，之后逐段 alpha 从 0.95 线性渐隐到 0；
  - 投影全程 double 计算（对 `mapCX/mapCY`），仅在屏幕空间转 float，消除 ~1e10 m 坐标下的 float32 抖动/断缝。

### 3.9 遥测与杂项

#### `updateTelemetry()`（private）
`SandboxScreen.java:1761`

- 每帧刷新：warp 标签；无活跃船显示 "No active ship"。
- 遥测四行：`ALT`（`world.altitudeAt`）、`SPD`（行星相对速度）、`BODY`（`currentPlanet`，落地附 "[landed]"）、`FUEL/MONO/BATT`（`fuelTotal(0/1/2)`）、`THR%` 与 `WARP`，暂停/地图模式附标记。
- 选中零件若是油箱/电池/SRB，`stageLabel` 追加实时数值（CHARGE/SOLID/FUEL 当前/容量）。

#### `stageLabelForTest()`
`SandboxScreen.java:1801`

- 诊断钩子：返回 `stageLabel` 当前文本（冒烟测试用）。

#### `fmt(double v)`（private static）
`SandboxScreen.java:1803`

- 数值缩写：≥1e6 → "%.2fM"，≥1e3 → "%.1fk"，否则 "%.0f"。

## 4. 与其他模块的关键交互

| 交互点 | 说明 |
|---|---|
| `game.world` (GameWorld) | `update/paused/warp/WARP_LEVELS/inputThrottle/active/ships/planets/setActive/setTargetHeading/currentHeading/getTargetHeading/currentPlanet/altitudeAt/densityAt/pressureAt/processDeferredStructure/save` |
| `OrbitPredictor` | 地图轨道外推；输出字段 `count/xs/ys/fx/fy/anchor` 被本类直接消费 |
| `SteeringIO` | 静态契约：`ringActive/buttonTurn/targetHeadingRad`，lua 可编程零件的转向输入来源 |
| `FlameScript` / `FlameFx` | lua 火焰脚本桥接与粒子池（粒子走仿真时间 `lastSimDt`） |
| `Planet` | `pos/vel/radius/mu()/hasAtmosphere()/atmoHeight/maxHeight/mapColor/crustColor/parent/a/e/w/v0/prograde` |
| `Ship` / `Part` | `getUniversePos/getUniverseVel/activateStage/controlPart/fuelTotal/onRails/landed`；Part 的 `body/callOnStage/group/flameLevel/flameGimbalDeg/dragCd/dragArea/dragExposure/deployed` |
| `Attach.closestOnSegment` | 点到线段最近点，tap 选零件的核心几何工具 |
