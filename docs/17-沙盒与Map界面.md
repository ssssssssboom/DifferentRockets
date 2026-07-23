# 17 · 沙盒与 Map 界面（SandboxScreen）

> 源码：`game/core/src/com/differentrockets/ui/SandboxScreen.java`（1821 行）
> 本文全部引用均为该文件内的行号。

`SandboxScreen` 是飞行沙盒的主界面（`SandboxScreen.java:45`）：负责渲染行星/地形/飞船/火焰、处理飞行输入（键盘 + 触屏 HUD）、Map 视图与时间加速。它继承 `ScreenAdapter`，持有一台飞行相机 `cam` 和一台 Map 相机 `mapCam`（`SandboxScreen.java:49-51`），输入用 `InputMultiplexer` 串联 `stage`（HUD 按钮优先）与内部类 `GameInput`（`SandboxScreen.java:156-159`）。

---

## 1. HUD 布局与按钮体系

### 1.1 整体布局

`buildHud()`（`SandboxScreen.java:165`）用一个填满父节点的 `Table root` 自上而下排五行（竖屏布局，注释见 `SandboxScreen.java:268-269`）：

| 行 | 内容 | 代码位置 |
| --- | --- | --- |
| 顶部遥测行 | `telemetry` 多行 Label（ALT/SPD/BODY/FUEL/THR/WARP） | `SandboxScreen.java:271` |
| 顶部按钮条 | 右上 `topRight`：`-` `warpLabel` `+` / `II` / `DRAG:on` / `MAP` / `FRAME:Auto` / `Menu` | `SandboxScreen.java:225-233, 273-276` |
| 中部 | 右缘竖直的 10 段油门条 `SegmentedThrottle`（90×320） | `SandboxScreen.java:278-281` |
| 底部 A 行 | `<` `>` 转向微调 / `stageLabel` / 缩放 `+` `-` / `CENTER` | `SandboxScreen.java:283-290` |
| 底部 B 行 | `ACTIVATE` / `STAGE (Space)`（居中两个大按钮） | `SandboxScreen.java:292-297` |

### 1.2 油门条（ThrottleLevel 贴图条）

`SegmentedThrottle`（`SandboxScreen.java:804-879`）是 10 段油门条：

- **贴图来源**：Runtime atlas 中 `ThrottleControl.png` 作轨道底框，`ThrottleLevel1..10.png` 各为一段白色 alpha-mask 精灵（段号越大越宽），自下而上堆叠；点亮段染绿色 `(0.30,1.00,0.35)`、未点段染暗红 `(0.42,0.10,0.10)`（`SandboxScreen.java:836-867`）。`ensureSprites()` 在 atlas 重建后自动重载并用 `extractUnrotated` 保证段图不侧转（`SandboxScreen.java:828-841`）。atlas 不可用时退化为纯色矩形（`SandboxScreen.java:869-877`）。
- **交互**：按下或拖动时按 y 归一化位置取 `round(yNorm * 10)`，得到 0..10 共 11 档（10% 步进），直接写 `game.world.inputThrottle`（`SandboxScreen.java:811-827`）。
- **键盘**：`Z`/`X` 整档加减，`Shift`/`Ctrl` 细调 ±0.05（`SandboxScreen.java:396-399`）。`throttleLevel()`/`setThrottleLevel()` 是档位数与 0..1 油门之间的换算（`SandboxScreen.java:781-788`）。

### 1.3 按钮语义一览

| 按钮 | 行为 | 代码位置 |
| --- | --- | --- |
| `STAGE (Space)` | `doStage()`：调用 `active.activateStage()`，stageLabel 显示 "Stage N fired!" / "No stages left" | `SandboxScreen.java:239-242, 362-367` |
| `ACTIVATE` | `activateSelected()`：对点选零件及其 activation group 触发 `onStage` | `SandboxScreen.java:244-247, 741-766` |
| `CENTER` | `camPan.setZero()`：清除双指拖拽偏移，飞行相机瞬间回到飞船（"丢失飞船救援"） | `SandboxScreen.java:263-266` |
| `DRAG:on/off` | 切换气动阻力合力叠加层；状态写在按钮文字里（round 14：按钮弹起必须恢复灰色，不用卡住的高亮态） | `SandboxScreen.java:192-198` |
| `MAP` | `toggleMap()` 进出 Map 视图 | `SandboxScreen.java:177-180, 882-891` |
| `FRAME:xxx` | 弹出锚天体列表（见 §4.3） | `SandboxScreen.java:185-188` |
| `-` / `+` | `warpStep()` 沿 `world.WARP_LEVELS` 阶梯升降档（1x/2x/4x 物理，25x..250000x on-rails），label 显示当前倍率 | `SandboxScreen.java:214-223, 303-316` |
| `II` | 切换 `world.paused` | `SandboxScreen.java:199-204` |
| `Menu` | `world.save()` 后回 `MenuScreen` | `SandboxScreen.java:205-211` |
| `<` / `>` | 按住时 slewDir=±1，每帧以 45°/s 微调目标航向；同时写 `SteeringIO.buttonTurn`（符号翻转：-1 左 / +1 右）作为对转向环的覆盖输入 | `SandboxScreen.java:250-251, 328-354, 989-992` |

桌面键盘快捷键（`SandboxScreen.java:391-415`）：`Space` 分级、`Tab` Map、`P` 暂停、`A/D` 或方向键转向、`,`/`.` 缩放、`1..9` 直接设 warp 档位。

---

## 2. 转向环（Steering Ring）

SimpleRockets 风格的屏幕中央转向环（`drawSteeringRing()`，`SandboxScreen.java:1389-1481`）：圆心 `(w/2, h/2)`，半径 `ringR = 0.26·min(w,h)`（`SandboxScreen.java:1394-1397`），绘制在屏幕空间正交矩阵 `ringMat` 上。航向约定：机头方向为 `(-sinθ, cosθ)`（`SandboxScreen.java:1483-1485`）。

### 2.1 激活逻辑（点击激活 + 固定夹角跟随）

- **命中区域**：`nearRing()` 判定为环带 `0.55·ringR < d < 1.6·ringR`（`SandboxScreen.java:641-645`）。普通视图中转向环优先于零件点选（`SandboxScreen.java:457-458`）。
- **点击激活**：touchDown 命中环带时置 `ringDrag=true`、`SteeringIO.ringActive=true`，目标航向重置为飞船**当前**航向，并记录抓取偏移 `ringDelta = 触摸角 − 目标航向`（`SandboxScreen.java:458-468`）。
- **固定夹角跟随**：拖动中 `steerRingTo()` 设目标为 `ringAngle(手指) − ringDelta`——即航向跟随手指但保持按下瞬间的夹角，环上的目标标记不会"跳"到手指处（item 11，`SandboxScreen.java:656-663`）。
- **写入路径**：所有目标航向都走 `setSteerTarget()` 单一路径，同时写 `world.setTargetHeading()` 与 `SteeringIO.targetHeadingRad`（`SandboxScreen.java:357-360`）；`render()` 每帧把 world 侧 PI 控制器可能重置的航向回灌 `SteeringIO`（`SandboxScreen.java:995-997`）。

### 2.2 取消规则

1. **第二指落下**：任何单指操作（环拖动、点选候选、Map 点击）立即取消，转入双指相机手势（`SandboxScreen.java:475-480`）。
2. **点空处停用**：普通视图中一次 tap 既没命中按钮也没命中零件时，置 `SteeringIO.ringActive = false`（注释："engines center"，`SandboxScreen.java:730-734`）。
3. **着陆隐藏**：`game.world.active.landed` 为 true 时 `drawSteeringRing()` 直接返回——地面上没东西可转向，不把大环盖在拉远的景物上（round 19，`SandboxScreen.java:1391-1393`）。

### 2.3 速度矢量可视化

环上有三类标记（`SandboxScreen.java:1416-1466`）：

- 白色径向刻度：当前航向；绿色刻度 + 端点圆点：目标航向；黄色圆弧：从当前到目标的最短误差弧（28 段，`err` 归一化到 `[-π, π]`）。
- **速度矢量**（item 3）：取飞船宇宙速度减去当前行星速度得到行星相对速度 `rv`，`spd > 0.5` 时在速度航向 `atan2(-rvx, rvy)` 处画青色刻度 + 环外箭头三角，并在 1.22·ringR 处画屏幕空间数值读数 `fmt(spd) + " m/s"`（`SandboxScreen.java:1408-1414, 1440-1445, 1456-1480`）。

Map 视图中转向环**保持可见且可交互**（round 13 item 2）：命中环带的触摸仍是转向，其余才是 Map 平移/点击（`SandboxScreen.java:443-451, 496-501, 1005-1008`）。

---

## 3. 零件点选判定

`flightTap()`（`SandboxScreen.java:703-738`）实现普通视图的 tap-to-activate 点选：

- **判定算法（round 11 item 8）**：对激活飞船每个零件的每个 fixture，遍历 `PolygonShape` 的**每条边**，用 `Attach.closestOnSegment` 算点击世界坐标到边的最近距离，**最近边距离最小者胜出**（`SandboxScreen.java:712-728`）。
- **历史问题**：旧实现用 Box2D `QueryAABB`/`testPoint` 做点内判定，细长零件（strut、panel）几乎点不中；改成"到边距离"后细零件和粗油箱一样好选（注释 `SandboxScreen.java:699-702`）。
- **阈值**：`max(3 m, 64 px 换算的世界长度)`——缩得再远也保持约 64 像素的可点手感（`SandboxScreen.java:708`）。
- **反馈**：选中零件画青色高亮圆（半径为零件 max(宽,高)×0.75，`SandboxScreen.java:1101-1111`）；`stageLabel` 显示 "Selected 名字 [group N]"；若选中的是油箱/电池/SRB，遥测行还会实时显示 `FUEL/CHARGE/SOLID 当前/容量`（`SandboxScreen.java:1786-1797`）。

`ACTIVATE` 按钮（`activateSelected()`，`SandboxScreen.java:741-766`）的要点：

- 未选中或选中零件已不在激活飞船上时提示 "Tap a part first, then ACTIVATE"。
- **先快照 group 成员再逐个 `callOnStage()`**：分离器（detacher）的 `onStage` 会延迟触发飞船拆分、改动 live parts 列表，边遍历边修改曾是 group 激活崩溃的根因（`SandboxScreen.java:748-755`）；激活前还要跳过已被前面成员摧毁/移走的零件（`SandboxScreen.java:757-758`）。
- 结束后立即 `processDeferredStructure()` 应用拆分（`SandboxScreen.java:762`）。

另有测试钩子 `debugSelectPart()`（`SandboxScreen.java:769-772`）。

---

## 4. Map 视图

`renderMap()`（`SandboxScreen.java:1505-1658`）。进出由 `toggleMap()` 控制：进入时 `mapInit=false`（触发首帧 autoFit）、清空相机跟随锚、强制立即重算轨道（`SandboxScreen.java:882-891`）。

### 4.1 double 基准化（防 float 抖动）

宇宙坐标量级约 1e10 m，float32 在该量级分辨率约 1 km——float 相机位置会让预测折线、行星、标签全部以 ~km 步长跳动/断裂。修复（round 13/18，`SandboxScreen.java:69-73, 1540-1547`）：

- Map 相机中心保存在 **double** `mapCX/mapCY`；`mapCam.position` 永远钉在原点。
- 所有世界→屏幕换算先用 double 减去 `(mapCX, mapCY)`，差值（视口量级）才转 float。预测线、行星圆、飞船三角、轨道环全部遵守此规则（如 `SandboxScreen.java:1571, 1624, 1672-1673, 1742-1745`）。
- 单指平移、双指捏合、点击定位都**直接改 double 中心**，不做 float unproject（注释 `SandboxScreen.java:503-504`）；捏合缩放以双指中点下的世界点为锚（`SandboxScreen.java:579-591`）。
- 缩放范围：`mapCam.viewportHeight` 夹在 `[1000, 1.2e12]` m（拉满可框住整个太阳系，Smeptune a≈4.5e11 m），普通视图夹 `[8, 200000]`（`SandboxScreen.java:369-381`）。

### 4.2 相机跟随锚天体 + 用户偏移

每帧把预测器当前锚天体 `predictor.anchor` 的**帧间位移**累加进 `mapCX/mapCY`：用户手势改的偏移保留，相机净效果 = 天体位置 + 用户偏移；切换锚天体时只记录新基准、不动相机（无跳变）（`SandboxScreen.java:1525-1539`）。

### 4.3 FRAME 锚天体列表（按实时引力排序）

- `FRAME` 按钮弹出 `toggleFrameList()`（`SandboxScreen.java:907-940`）：收集所有 `mu() > 0` 的天体，按 `gOn() = mu/r²`（该天体对激活飞船**当前位置**的引力加速度）降序排列，外加一个 `Auto (dominant)` 项（`anchorIndex = -1`，自动主导天体）。
- 选中后 `selectAnchor()` 更新 `FRAME:` 按钮文字、折叠列表、强制立即重算轨道线（`SandboxScreen.java:942-947`）。再次点 FRAME 或点 Map 空白处仅折叠不修改（`frameList` 会"吃掉"下一次 mapTap，`SandboxScreen.java:668-671`）。
- 预测线与 Map 相机共用同一锚（`SandboxScreen.java:52-58`）；`OrbitPredictor.compute(world, ship, anchorIndex)` 负责解析显式选择或自动主导天体（`SandboxScreen.java:1514, 1726`）。

### 4.4 轨道预测线（15 Hz 刷新）

- **刷新频率**：`ORBIT_INTERVAL = 1/15`（round 18 由 4 Hz 提到 15 Hz——用户嫌线太旧；重锚定仍每帧发生，所以线本身 60 Hz 平滑）（`SandboxScreen.java:99-101, 1507-1516`）。
- **绘制**（`drawOrbitPrediction()`，`SandboxScreen.java:1717-1757`）：
  - 整条折线用**单一锚天体参考系**：原始惯性坐标 `xs/ys` 减去传播时锚点 `fx/fy` 再加锚天体**当前**位置，跨主导天体转换的洞被 run 偏移链消除（round 15/13 注释 `SandboxScreen.java:1706-1725`）。
  - 世界→屏幕全程 double 计算，仅在屏幕空间转 float。
  - 抽稀到 ≤2000 个 GL 点；前 ~30% 为实线，后段按段线性淡出到尾部 alpha=0（`SandboxScreen.java:1728-1755`）。
- **autoFitMap**（`SandboxScreen.java:955-982`）：首次打开时取预测轨迹包围盒（draw-anchored 坐标）×1.3 作为视口高度，保证短弹道跳跃也可见；无预测时退化为当前行星半径×4（最小 200 km），整体夹在 `[2e4, 1.2e12]` m。
- 行星公转轨道环用 Kepler 方程采样一整周期 96 段（`drawOrbitPath()`/`orbitRelAt()`，`SandboxScreen.java:1660-1698`）；平近点角 M 先 wrap 到 `[-π, π]` 再 Newton 迭代，否则长 warp 后大 M 会发散、轨道线折叠（`SandboxScreen.java:1686-1691`）。

### 4.5 飞船三角（指向机头）

- 每艘飞船画成三角箭头，半径为视口高度的 1%：激活船绿色、其他橙色（`SandboxScreen.java:1592-1629`）。
- **朝向 = 姿态（机头方向）**：取 `controlPart()` 的 body angle，按 `(-sinθ, cosθ)`  convention 转方向向量（round 17，`SandboxScreen.java:1598-1608`）。on-rails 飞船没有 body，退化为旧的行星相对速度方向，速度过小时默认朝上（`SandboxScreen.java:1609-1621`）。
- 行星画真半径圆（半透明填充 + 描边环，`SandboxScreen.java:1564-1582`）；行星名/船名标签在屏幕空间固定像素大小绘制，过小的天体标签按 `radius/viewportHeight < 0.0025` 跳过（`SandboxScreen.java:1631-1657`）。

### 4.6 Map 点击（mapTap）

`mapTap()`（`SandboxScreen.java:666-696`）按优先级：

1. 锚列表开着 → 仅折叠列表。
2. 点击 5% 视口高度内最近的**其他飞船** → `setActive()` 切换并退出 Map。
3. 点击 `max(1.5×半径, 3% 视口高度)` 内的行星 → 把 double 相机中心移到该行星。

---

## 5. 普通视图相机与 CENTER 回中

- 飞行世界以飞船 COM 为原点渲染，所以相机位置就是双指拖拽偏移量本身：`cam.position.set(camPan.x, camPan.y, 0)`（`SandboxScreen.java:1040-1043`）。
- **双指手势**（`twoFinger()`，`SandboxScreen.java:566-618`）：中点位移平移 `camPan`；捏合按 `prevDist/dist` 缩放，且以中点下的世界点为锚——缩放前后对该点做两次 unproject，把差值补回 `camPan`，并拒绝 NaN/Inf 结果（退化手势曾导致崩溃，`SandboxScreen.java:601-614`）。
- **指针管理**：第三指忽略；A 指抬起而 B 指仍按住时把 B 提升为主指并重新锚定位置，避免 `Gdx.input.getX(死id)` 崩溃（`SandboxScreen.java:527-549`）。
- **CENTER**：`camPan.setZero()`，相机瞬移回飞船（`SandboxScreen.java:263-266`）。
- 其余渲染要点：行星大气辉光 + 本体圆按 `dist - outer > 3×halfView` 裁剪（`SandboxScreen.java:1052-1087`）；3 万 m 内的其他飞船画橙色圆点（`SandboxScreen.java:1118-1130`）；油箱液位条、DRAG 阻力合力箭头（CoP + 对数缩放箭头 + 屏幕空间数值标签）分别见 `drawTankLevels()`（`SandboxScreen.java:1140-1171`）与 `drawDragOverlay()`（`SandboxScreen.java:1179-1261`）；引擎火焰优先走 `mod/flame.lua`（`FlameScript`），缺失时用内置三层锥形羽流（`SandboxScreen.java:1300-1372`）。

---

## 6. 遥测与测试钩子

`updateTelemetry()`（`SandboxScreen.java:1761-1798`）每帧刷新：ALT（`world.altitudeAt`）、SPD（行星相对速度）、BODY（`currentPlanet()`，着陆附 `[landed]`）、FUEL/MONO/BATT 三种资源总量、THR 百分比、WARP 倍率及 `[PAUSED]`/`[MAP]` 标记。数值格式化 `fmt()`：≥1e6 用 `%.2fM`、≥1e3 用 `%.1fk`（`SandboxScreen.java:1803-1807`）。

冒烟测试钩子：`throttleLevelForTest`/`setThrottleLevelForTest`（`SandboxScreen.java:791-792`）、`setFlightZoom`/`flightZoomForTest`（`SandboxScreen.java:1020-1026`）、`flameParticlesForTest`/`flameParticlesMaxForTest`（`SandboxScreen.java:1029-1030`）、`stageLabelForTest`（`SandboxScreen.java:1801`）、`debugSelectPart`（`SandboxScreen.java:769-772`）。
