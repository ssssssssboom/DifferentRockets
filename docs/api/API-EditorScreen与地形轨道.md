# API 文档：EditorScreen / TerrainSystem / OrbitPredictor

> 目标读者：刚接手 DifferentRockets 的中国开发者。
> 本文逐函数梳理三个核心类：火箭建造编辑器（`EditorScreen`）、柱状地形系统（`TerrainSystem`）、轨道预测器（`OrbitPredictor`）。
> 所有行号引用格式为 `文件名:行号`，对应 `game/core/src/com/differentrockets/` 下的源码。

---

## 1. EditorScreen — 火箭建造编辑器

### 1.1 类职责

`EditorScreen` 是火箭建造界面（`EditorScreen.java:43`，继承 libGDX `ScreenAdapter`）。功能涵盖：零件面板（palette）列表、拖出/点击两种放置方式、连接点吸附（attach-point snapping，含边缘吸附与 0.25 m 量化）、90° 旋转、删除、多选与 8 个激活组（activation group）分配、分级列表（stages）、飞船保存/加载（JSON）、选择发射星球并进入沙盒。UI 用 Scene2D（`Stage` + `Table`），画布用正交相机 + `ShapeRenderer`/`SpriteBatch` 渲染。输入处理分三层：`InputMultiplexer` 依次为 `DragOutInterceptor`（面板拖出原始拦截）→ `stage`（Scene2D）→ `EditorInput`（画布手势），见 `EditorScreen.java:151-154`。输入生命周期管理极为严格（多点触控指针追踪、touch focus 取消），类注释与大量行内注释记录了多轮（round 8/9/11）触控 bug 的修复逻辑，改动输入代码前务必通读。

### 1.2 重要字段

| 字段 | 类型 | 说明 | 位置 |
|---|---|---|---|
| `game` | `DRGame` | 全局游戏门面（world / ui / batch / shapes / font / shipSprites） | `EditorScreen.java:45` |
| `design` | `ShipDesign` | 当前飞船设计（`parts` 为 `DesignPart` 列表，`stages` 为分级索引列表） | `EditorScreen.java:46` |
| `shipName` | `String` | 飞船名，用于保存文件名，默认 `"Untitled"` | `EditorScreen.java:47` |
| `stage` / `cam` | `Stage` / `OrthographicCamera` | UI 舞台（ScreenViewport）与画布相机（视高 40 m 起步） | `EditorScreen.java:49-50` |
| `editorInput` | `InputAdapter` | 画布手势处理器（`EditorInput` 内部类实例） | `EditorScreen.java:51` |
| `canvasArea` | `Table` | 唯一被 `EditorInput` 视为"画布"的 stage 区域；`stage.hit` 命中其他 actor 即视为 UI | `EditorScreen.java:52` |
| `paletteItems` / `paletteScroll` | `List<PaletteItem>` / `ScrollPane` | 零件面板行列表与其滚动容器（拖出期间暂停滚动） | `EditorScreen.java:53-54` |
| `dragOutType` / `dragOutPointer` / `dragScrX`,`dragScrY` | `PartType` / `int` / `float` | 面板拖出手势进行中状态：非 null 表示拖出活跃 | `EditorScreen.java:55-57` |
| `groupButtons` | `TextButton[9]` | 激活组按钮缓存（1–8 有效，0 未用），供烟测定位 | `EditorScreen.java:58` |
| `placing` | `PartType` | 正在放置的"幽灵"零件类型（点击放置/拖出放置共用） | `EditorScreen.java:61` |
| `dragIndex` / `dragX`,`dragY` / `dragRot` | `int` / `float` / `int` | 已放置零件拖拽移动状态：零件索引、幽灵世界坐标、旋转步数（0–3，每步 90°） | `EditorScreen.java:62-64` |
| `panning` / `panLastX`,`panLastY` | `boolean` / `float` | 单指平移状态 | `EditorScreen.java:65-66` |
| `touchPtrA`,`touchPtrB` / `gpaX`…`gpbY` | `int` / `float` | 双指手势追踪：A=第一指、B=第二指；位置来自自有事件日志而非 `Gdx.input.getX(pointer)`（后者对合成事件与已失效 pointer 不可靠，见 `EditorScreen.java:697-700`） | `EditorScreen.java:68-69` |
| `stageTable` / `statusLabel` / `overlay` | `Table` / `Label` / `Table` | 分级列表内容表、底部状态行、模态覆盖层（发射选择/加载对话框/分级对话框复用） | `EditorScreen.java:70-72` |
| `selected` | `Set<Integer>` | 多选零件索引集合（用于激活组分配与批量删除/旋转） | `EditorScreen.java:75` |
| `downIndex` / `downScrX`,`downScrY` / `dragMoved` | `int` / `float` / `boolean` | 按下时命中的零件索引与屏幕位置；位移超 12 px 提升为拖拽移动 | `EditorScreen.java:76-78` |
| `groupBar` | `Table` | 画布底部的激活组工具条（有选中时可见） | `EditorScreen.java:79` |
| `dragOutInterceptor` | `DragOutInterceptor` | 面板拖出拦截器，位于 stage 之前 | `EditorScreen.java:157` |
| `tmp3` | `Vector3` | 坐标换算临时变量 | `EditorScreen.java:550` |

### 1.3 逐函数说明

#### 构造与测试钩子

| 函数 | 签名 / 位置 | 功能 · 参数 · 返回值 · 要点 |
|---|---|---|
| 构造函数 | `EditorScreen(DRGame game, ShipDesign existing)` `EditorScreen.java:81` | 传入既有设计则直接编辑；`parts` 为空时自动放一个指令舱 `pod-1` 并 `autoStage()`（每枚新火箭都从指令舱开始）。无返回值。 |
| `getDesign` | `ShipDesign getDesign()` `EditorScreen.java:91` | 返回当前设计对象。 |
| `getPlacing` | `PartType getPlacing()` `EditorScreen.java:94` | 烟测钩子：返回正在放置的零件类型。 |
| `isSelected` | `boolean isSelected(int index)` `EditorScreen.java:95` | 烟测钩子：查询零件索引是否被选中。 |
| `cancelPlacing` | `void cancelPlacing()` `EditorScreen.java:96` | 烟测钩子：取消放置幽灵。 |
| `actorScreenPos` | `private int[] actorScreenPos(Actor a)` `EditorScreen.java:98` | 把 actor 中心换算为屏幕像素坐标（y 向下）；参数：Scene2D actor；返回 `{x,y}` 或 null（无 stage 时）。 |
| `paletteItemScreenPos` | `int[] paletteItemScreenPos(int i)` `EditorScreen.java:105` | 烟测钩子：第 i 个面板行的屏幕坐标。 |
| `groupButtonScreenPos` | `int[] groupButtonScreenPos(int g)` `EditorScreen.java:108` | 烟测钩子：激活组按钮（1–8）的屏幕坐标。 |
| `partScreenPos` | `int[] partScreenPos(int designIndex)` `EditorScreen.java:111` | 烟测钩子：已放置零件的屏幕坐标。注意 `cam.project()` 输出 y 向上，需用屏幕高度翻转（`EditorScreen.java:115`）。 |
| `hitInfo` | `String hitInfo(int sx, int sy)` `EditorScreen.java:119` | 诊断：返回某屏幕点命中的 stage actor 类名、世界坐标、`partAt` 结果，用于排查点击路由问题。 |

#### 生命周期

| 函数 | 签名 / 位置 | 功能 · 要点 |
|---|---|---|
| `show` | `void show()` `EditorScreen.java:130` | 进入界面：创建相机（视高 40 m，初始位置 (-4.5,-3)，让火箭位于面板右侧可视区，见 `:136`）、Stage、`buildChrome()`、`rebuildStageList()`，并按 `dragOutInterceptor → stage → editorInput` 顺序挂 `InputMultiplexer`。拖出拦截必须在 stage 之前，因为 ScrollPane 会在拖拽中途窃取/取消 Scene2D touch focus（`:144-150`）。 |
| `render` | `void render(float delta)` `EditorScreen.java:1059` | 每帧绘制：背景清屏 → 2 m 网格线 → 所有已放置零件贴图 → 放置幽灵（半透明 alpha 0.6 + 吸附位置实时计算）+ 连接点标记（幽灵 0.25 m 绿点，已有零件 0.18 m 绿点）→ 选中框（青色 rect）与激活组徽章（蓝点+数字，字号随缩放保持恒定屏幕尺寸，`:1134`）→ `stage.act/draw`。 |
| `resize` | `void resize(int w, int h)` `EditorScreen.java:1178` | 更新 stage viewport 与相机宽高比（视高不变）。 |
| `dispose` | `void dispose()` `EditorScreen.java:1187` | 释放 stage。 |
| `hide` | `void hide()` `EditorScreen.java:1197` | 离开界面时清空一切瞬态手势状态（拖出、追踪指针、平移/拖拽索引等）并 `stage.cancelTouchFocus()`，防止指针状态泄漏到下一次 `show()`（item 8，`:1191-1195`）。 |

#### 内部类 `DragOutInterceptor`（面板拖出拦截器，`EditorScreen.java:171`）

类注释（`:159-170`）是关键维护文档：拦截器吞掉抬手 `touchUp` 后，`beginDragOut()` 必须调用 `stage.cancelTouchFocus()`，否则残留 focus 会在下一次手势触发幻影行 `touchUp`，进而导致 `EditorInput` 的 `touchPtrA` 泄漏（"round-8 拖出后输入失灵" bug）。

| 函数 | 签名 / 位置 | 功能 · 参数 · 返回值 |
|---|---|---|
| `reset` | `void reset()` `EditorScreen.java:175` | 清空候选行。 |
| `touchDown` | `boolean touchDown(int screenX, int screenY, int pointer, int button)` `EditorScreen.java:177` | 仅 pointer 0 且无拖出进行中时，记录按下点与候选面板行（`paletteRowAt`）。永远返回 false，不消费按下事件（点击/滚动需要它）。 |
| `touchDragged` | `boolean touchDragged(int screenX, int screenY, int pointer)` `EditorScreen.java:184` | 拖出已激活：更新手指屏幕坐标并返回 true 独占事件。未激活但有候选行：水平位移 >14 px 且大于垂直位移则 `beginDragOut()` 开始拖出并返回 true。 |
| `touchUp` | `boolean touchUp(...)` `EditorScreen.java:202` | 清候选；拖出进行中则 `finishDragOut()` 并返回 true（stage 永远看不到这次抬手）。 |
| `touchCancelled` | `boolean touchCancelled(...)` `EditorScreen.java:209` | 手势被系统取消：清候选，若在拖出则同时清 `dragOutType` 与 `placing`。 |

#### UI 构建与模态对话框

| 函数 | 签名 / 位置 | 功能 · 要点 |
|---|---|---|
| `paletteRowAt` | `private PartType paletteRowAt(float screenX, float screenY)` `EditorScreen.java:221` | 命中检测：屏幕点落在哪个面板行上（遍历 `paletteItems` 的 stage 坐标包围盒）。返回 `PartType` 或 null。 |
| `buildChrome` | `private void buildChrome()` `EditorScreen.java:237` | 搭建全部静态 UI：顶栏第一行（Menu / 船名输入框 / LAUNCH >>）、第二行（Rotate (R) / Save / Load / Stages）、中部左侧零件面板列（屏幕宽 40%，ScrollPane 仅垂直滚动）+ 右侧画布区（含 `groupBar`）、底部状态行。最后 `rebuildGroupBar()`。 |
| `rebuildGroupBar` | `private void rebuildGroupBar()` `EditorScreen.java:338` | 重建激活组工具条；`selected` 为空则只清空并返回。按钮布局三行：Grp 1–4 / 5–8 / Clear grp + DEL parts。"Clear grp" 把选中零件 group 置 0；"DEL parts" 按索引倒序删除选中零件（触屏无右键的删除途径，`:371`），随后 `autoStage()` + 重建列表。 |
| `toggleGroup` | `private void toggleGroup(int grp)` `EditorScreen.java:388` | 给所有选中零件分配/取消激活组（每零件一个组）：若全部已在该组则清除，否则统一设为 `grp`。 |
| `rebuildStageList` | `private void rebuildStageList()` `EditorScreen.java:399` | 按 `design.stages` 重建分级列表文本（每级一行：序号 + 零件名逗号串，空级显示 `(empty)`）。 |
| `showStagesDialog` | `private void showStagesDialog()` `EditorScreen.java:418` | 竖屏下分级列表以模态对话框呈现（屏幕空间所限）。 |
| `newOverlay` | `private Table newOverlay()` `EditorScreen.java:436` | 创建全屏半透明模态覆盖层，`touchDown` 返回 true 吞掉所有点击，防止泄漏到下方画布。 |
| `closeOverlay` | `private void closeOverlay()` `EditorScreen.java:450` | 移除当前模态覆盖层（可重入安全）。 |
| `showLaunchPicker` | `private void showLaunchPicker()` `EditorScreen.java:457` | 发射星球选择框：遍历 `game.world.sun.flatten(flat)`，仅列出 `launchEnabled` 的星球，按钮显示名称与表面重力 `g`；点击即 `launch(p)`。 |
| `showLoadDialog` | `private void showLoadDialog()` `EditorScreen.java:485` | 加载对话框：列出 `Gdx.files.local("save/ships")` 下所有 `.json`，点击后 `ShipDesign.fromJson` 反序列化、清空选择、重建 UI；失败仅状态行报错。无存档时显示 `(no saved ships)`。 |
| `saveShip` | `private void saveShip()` `EditorScreen.java:525` | 保存：`shipName` 清洗非法字符（`[^a-zA-Z0-9_ -]` → `_`）后写入 `save/ships/<名>.json`（本地存储目录）。 |
| `status` | `private void status(String s)` `EditorScreen.java:536` | 更新底部状态行文本。 |
| `launch` | `private void launch(Planet planet)` `EditorScreen.java:542` | 发射：`autoStage()` 后调用 `game.world.launchShip(design, planet)`，切换到 `SandboxScreen`。 |

#### 编辑核心逻辑

| 函数 | 签名 / 位置 | 功能 · 参数 · 返回值 · 要点 |
|---|---|---|
| `screenToWorld` | `private Vector2 screenToWorld(float sx, float sy)` `EditorScreen.java:552` | 屏幕像素 → 画布世界坐标（`cam.unproject`）。 |
| `partAt` | `private int partAt(Vector2 w)` `EditorScreen.java:558` | 命中检测：世界坐标点中的零件索引，无命中返回 -1。拾取容差为 `max(2 m, 48 px 换算世界单位)`（`:561`）；在零件局部坐标系做带旋转的包围盒测试，重叠时取中心最近者。 |
| `snap` | `private Vector2 snap(float px, float py, int rot, PartType type, int ignoreIndex)` `EditorScreen.java:581` | **连接点吸附核心**。参数：幽灵位置/旋转/类型、忽略的零件索引（拖自身时跳过）；返回吸附后的位置。吸附半径 2.2 m。对幽灵与每个其他零件的所有 `AttachPoint` 两两求线段最近距离（`Attach.closestBetweenSegments`，边缘型连接点接受沿整条边接触，幽灵沿边滑动，`:585-586`）；胜出接触涉及边缘型连接点时，位置沿边量化到 0.25 m 网格（`Attach.quantizeAlongSegment`），中心-中心连接保持精确接触位置（`:604-614`）。 |
| `attachWorldSeg` | `private static void attachWorldSeg(PartType t, float px, float py, int rot, AttachPoint ap, Vector2 outA, Vector2 outB)` `EditorScreen.java:623` | 求某连接点在设计空间的世界线段：`Attach.localSegment` 取局部段，再按 `rot*90°` 旋转、平移输出到 outA/outB。 |
| `snapForTest` | `Vector2 snapForTest(float px, float py, int rot, String typeId, int ignoreIndex)` `EditorScreen.java:634` | 烟测钩子：按 typeId 模拟拖动到 (px,py) 求吸附结果；typeId 无效时原样返回。 |
| `attachWorld` | `private List<Vector2> attachWorld(PartType t, float px, float py, int rot)` `EditorScreen.java:639` | 返回某零件所有连接点中心的世界坐标列表（渲染连接点标记用）。 |
| `rotateGhost` | `private void rotateGhost()` `EditorScreen.java:650` | 旋转目标三级优先：① 放置幽灵（`placing`，跳过 `disableEditorRotation` 类型）；② 拖拽中的零件（`dragIndex`）；③ 所有点选中的零件（修复 round-9 "点选零件无法旋转" bug，`:665-667`）。`dragRot`/`dp.rot` 按 (rot+1)%4 循环。 |
| `dragRotForTest` | `int dragRotForTest()` `EditorScreen.java:685` | 烟测钩子：返回当前幽灵/拖拽旋转步数。 |
| `selectPart` | `void selectPart(PartType t)` `EditorScreen.java:922` | 点按面板行：进入"点击放置"模式（`placing=t`，`dragRot=0`）。 |
| `actorBoundsInfo` | `String actorBoundsInfo(int i)` `EditorScreen.java:929` | 烟测诊断：面板行包围盒的屏幕坐标字符串。 |
| `beginDragOut` | `void beginDragOut(PartType t, int pointer, float screenX, float screenY)` `EditorScreen.java:940` | 拖出开始：**先 `stage.cancelTouchFocus()`**（防残留 focus 幻影事件，见 `DragOutInterceptor` 类注释），再置 `placing`/`dragOutType` 并记录手指位置。 |
| `finishDragOut` | `void finishDragOut(float screenX, float screenY)` `EditorScreen.java:983` | 拖出抬手：清 `dragOutType` 并防御性 `cancelTouchFocus()`（`:986-987`）；落点在面板列右侧（>40% 屏宽）→ 吸附放置新零件并 `autoStage()`；落回面板 → 取消。 |
| `lastStatus` / `camXForTest` / `camYForTest` / `zoomForTest` | `EditorScreen.java:956-961` | 烟测钩子：状态行文本、相机 X/Y、视高。 |
| `emptyCanvasPointForTest` | `int[] emptyCanvasPointForTest()` `EditorScreen.java:967` | 烟测钩子：在固定网格中扫描返回一个既无 UI 遮挡又无零件的画布屏幕点。 |

#### 内部类 `EditorInput`（画布手势处理，`EditorScreen.java:687`）

| 函数 | 签名 / 位置 | 功能 · 要点 |
|---|---|---|
| `touchDown` | `boolean touchDown(int screenX, int screenY, int pointer, int button)` `EditorScreen.java:689` | 拖出进行中直接放行（第二指不得在拖出下放置/平移，`:692`）。已有 A 指时：第二指升级为双指相机手势，取消单指操作（不完成它），忽略第三指。用 `stage.hit` 判断点击归属：命中非 `canvasArea` 的 actor（顶栏/面板/工具条/覆盖层）则放行给 stage；否则交 `firstFingerDown`，处理成功则登记为 A 指。 |
| `firstFingerDown` | `private boolean firstFingerDown(int screenX, int screenY, int button)` `EditorScreen.java:719` | 单指画布按下路由：右键 → 取消放置幽灵或删除命中零件（含 `autoStage`）；放置模式 → 吸附落点添加零件，按住 SHIFT_LEFT 可连续放置（`:741`）；命中零件 → 记录 `downIndex` 等待"点选/拖动"二选一；空白处 → 进入平移。返回是否消费。 |
| `pinchDrag` | `private void pinchDrag(float x, float y, boolean movedIsA)` `EditorScreen.java:762` | 双指相机：中点位移精确平移（unproject 前后两个中点求差），双指距离比做缩放且锚定中点（`:774-787`）；视高钳制 [5, 200] m，双指距离 <10 px 时不缩放防抖动。 |
| `touchDragged` | `boolean touchDragged(int screenX, int screenY, int pointer)` `EditorScreen.java:794` | 双指状态 → `pinchDrag`。单指：先更新自有 A 指位置日志（`:804`）；`downIndex` 位移超 12 px 提升为拖拽移动（`dragIndex=downIndex`，继承零件原旋转）；拖拽中每帧重算吸附并直接改写零件 x/y/rot；否则平移相机（按视高/屏高比例换算）。 |
| `touchUp` | `boolean touchUp(...)` `EditorScreen.java:836` | B 指抬起：清 B，A 指从自有日志重新锚定平移基准（`:839-844`）。A 指抬起但 B 仍在：B 提升为 A。完全结束：`downIndex` 未拖动 → 点选切换选中集合并刷新 groupBar；已拖动 → `autoStage()` + 重建分级。平移结束且位移 <12 px 且有选中 → 点空白清除选择。 |
| `scrolled` | `boolean scrolled(float amountX, float amountY)` `EditorScreen.java:887` | 桌面滚轮缩放，视高 ×1.1/×0.9，钳制 [5, 200]。 |
| `touchCancelled` | `boolean touchCancelled(...)` `EditorScreen.java:896` | 手势被 OS 中断：丢弃全部瞬态状态。 |
| `keyDown` | `boolean keyDown(int keycode)` `EditorScreen.java:907` | R → `rotateGhost()`；ESC → 关覆盖层 / 取消放置 / 返回主菜单（逐级）；DEL/BACKSPACE → 消费但不动作（删除走右键或 groupBar 的 DEL 按钮）。 |

#### 内部类 `PaletteItem`（面板行控件，`EditorScreen.java:1007`）

| 函数 | 签名 / 位置 | 功能 · 要点 |
|---|---|---|
| 构造函数 | `PaletteItem(PartType t)` `EditorScreen.java:1010` | 一行零件项：背景色块 + 名称（2 倍字号自动换行）+ 类型/质量信息（1.5 倍）。整行即触控目标（`:1023`）。内嵌 `InputListener`：`touchDown` 记录按下点与 ScrollPane 滚动位置（`:1034`）；`touchDragged` 位移 >14 px 标记 moved；`touchUp` 在未移动、面板未滚动（ScrollPane 窃取垂直拖拽时不会回调本行，故用滚动量 >2 px 兜底判断，`:1042-1047`）、事件未取消时视为点按 → `selectPart(t)`。水平拖出由上游 `DragOutInterceptor` 原始拦截，不走这里。 |

#### 其余私有绘制辅助

| 函数 | 签名 / 位置 | 功能 |
|---|---|---|
| `hasAnyGroup` | `private boolean hasAnyGroup()` `EditorScreen.java:1152` | 是否存在已分组的零件（决定是否绘制组徽章）。 |
| `drawPart` | `private void drawPart(String typeId, float x, float y, int rot, float alpha)` `EditorScreen.java:1157` | 绘制单个零件：从 `game.shipSprites` 取贴图区域按 `rot*90°` 旋转绘制；贴图缺失时退化画橙色线框矩形（`:1167-1172`）。 |

---

## 2. TerrainSystem — 柱状星球地形系统

### 2.1 类职责

`TerrainSystem`（`TerrainSystem.java:69`，实现 `Disposable`）负责行星表面地形的渲染与碰撞，round 18 重写为**柱状模型**：地表是一圈四边形"列"，每列宽 `terrainRender.blockWidthM` 米弧长（默认 4 m），列 i 是交界高度 h[i] 与 h[i+1] 之间的四边形——顶边为地表、底边下移 `depthM` 米（默认 32）。交界高度由**同一个 Lua 函数** `surfaceHeight(planetInfo[name], xArcMeters)` 给出（`mod/terrain.lua`，热重载），因此列间共享交界、天然无缝；同一套顶点数据同时构建渲染 mesh 与碰撞 fixture（"所见即所撞"，`TerrainSystem.java:31-46`）。

性能设计（`:48-56`）：窗口管理 10 Hz（`REFRESH_S=0.1`）只在窗口边缘增删 chunk；交界高度按索引缓存（Lua 为确定性纯函数，每个新交界只调一次 Lua）；碰撞 fixture 只存在于 `physicsRangeM`（默认 ±10 km）内，渲染窗口 `rangeM`（默认 ±100 km）——全程 2.5 万列若全建 fixture 不可行。

反回归措施（`:58-67`，继承自分块旧系统）：每 chunk 一个 **KINEMATIC** body，每帧以**速度驱动**追随行星（接触求解器能看到地面真实速度，着陆飞船靠摩擦随行星走，而不是被瞬移的静态碰撞体吞掉）；多边形用 chunk 局部坐标（行星中心 ~637 km 量级的顶点会在 Box2D 2.3 float32 质心计算中触发 native assert）；列间 0.05 m 重叠（`SEAM_OVERLAP_M`）防穿透缝隙与侧滑卡顿；restitution 0、默认高摩擦 1.0；交给 Box2D 前用双精度做退化四边形防护。

### 2.2 重要字段

| 字段 | 类型 | 说明 | 位置 |
|---|---|---|---|
| `DEF_BLOCK_W` / `DEF_DEPTH` / `DEF_RANGE` / `DEF_PHYS_RANGE` | `double` 常量 | 默认列宽 4 m / 壳深 32 m / 渲染窗口 ±100 km / 碰撞窗口 ±10 km | `TerrainSystem.java:72-75` |
| `DEF_FRICTION` / `DEF_RESTITUTION` | `float` 常量 | 默认摩擦 1.0 / 弹性 0（不弹跳） | `TerrainSystem.java:76-77` |
| `DEF_TOP_B` / `DEF_BOT_B` / `DEF_BAND` | `float` 常量 | 默认表皮亮度 1.35 / 壳底亮度 0.25 / 每列亮度抖动幅度 0.06 | `TerrainSystem.java:78-80` |
| `DEF_DEEP_R/G/B` | `float` 常量 | 深层填充默认颜色 (0.23, 0.15, 0.09) | `TerrainSystem.java:81` |
| `MAX_CRUST` | `double = 260` | 视觉地壳底部下沿封顶 | `TerrainSystem.java:83` |
| `COLS_PER_CHUNK` | `int = 64` | 每 chunk 列数（4 m 列宽时 256 m） | `TerrainSystem.java:84` |
| `SEAM_OVERLAP_M` | `double = 0.05` | 列间重叠量：无缝隙/无卡顿 | `TerrainSystem.java:85` |
| `REFRESH_S` | `double = 0.1` | 窗口管理周期（10 Hz） | `TerrainSystem.java:86` |
| `HEIGHT_CACHE_CAP` | `int = 200000` | 高度缓存安全上限，超限清空重建（确定性可重填） | `TerrainSystem.java:87` |
| `world` | `GameWorld` | 物理世界引用（boxWorld / planets / origin） | `TerrainSystem.java:89` |
| `planet` | `Planet` | 当前附着行星；离所有行星表面 >200 km 时为 null | `TerrainSystem.java:90` |
| `colW` / `totalCols` / `totalChunks` | `double` / `int` / `int` | 调整后的实际列宽（整周均匀平铺）、总列数、总 chunk 数 | `TerrainSystem.java:91-93` |
| `loaded` | `Map<Integer, Chunk>` | 已加载 chunk（chunk 索引 → 对象） | `TerrainSystem.java:94` |
| `hCache` | `Map<Integer, Double>` | 交界高度缓存：交界索引（mod totalCols）→ 绝对半径 | `TerrainSystem.java:96` |
| `refreshT` | `double` | 窗口管理计时器，初值 REFRESH_S（首帧即管理） | `TerrainSystem.java:97` |
| `cfgScript` | `LuaScript` | `terrain.lua` 配置脚本句柄 | `TerrainSystem.java:100` |
| `blockW` … `deepB`、`textureName` | 各种 | 从 `terrainRender{...}` 读出的实时参数（热重载，改动触发整体重建） | `TerrainSystem.java:101-111` |
| `texture` / `whiteTex` | `Texture` | 可选地表贴图；null 时绑定静态 1×1 白图（shader 统一采样路径） | `TerrainSystem.java:112-113` |
| `degenerateSkips` | `int` | 退化四边形跳过计数（诊断，最多记 5 条日志） | `TerrainSystem.java:114` |
| `cfgToken` / `heightToken` | `Object` | terrain.lua 重载检测身份令牌 | `TerrainSystem.java:115` |
| `shapes` / `shader` / `model` / `tmpMat` | 渲染资源 | 水面 ShapeRenderer、静态地形 shader（顶点色×贴图）、模型矩阵 | `TerrainSystem.java:117-120` |

#### 内部类 `Chunk`（`TerrainSystem.java:122`）

每个 chunk 字段：`index`（chunk 索引）、`body`（KINEMATIC 碰撞体，仅物理窗口内存在）、`cx,cy`（chunk 中心，行星系坐标）、`lastBX,lastBY,hasLast`（上一物理帧目标位置，用于速度驱动）、`mesh`（渲染网格）、`waterPoly`（水面多边形顶点对，行星系）、`arcCenter`（chunk 中心弧长位置）。

| 函数 | 位置 | 功能 |
|---|---|---|
| `destroyBody()` | `TerrainSystem.java:132` | 销毁 Box2D body（fixture 随之销毁）并置 null。 |
| `dispose()` | `TerrainSystem.java:139` | `destroyBody()` + 释放 mesh。 |

### 2.3 逐函数说明

| 函数 | 签名 / 位置 | 功能 · 参数 · 返回值 · 要点 |
|---|---|---|
| 构造函数 | `TerrainSystem(GameWorld world)` `TerrainSystem.java:148` | 编译静态地形 shader（顶点色 + 单贴图采样，编译失败抛异常）；创建静态 1×1 白色纹理 `whiteTex`（无贴图路径时绑定，保证同一 shader 两条路径通用）。 |
| `currentPlanet` | `Planet currentPlanet()` `TerrainSystem.java:170` | 返回当前附着行星（可能为 null）。 |
| `loadedCount` | `int loadedCount()` `TerrainSystem.java:172` | 已加载 chunk 数（诊断用）。 |
| `refreshParams` | `private void refreshParams()` `TerrainSystem.java:179` | 重读 `terrainRender{...}`。先用 `TerrainScript.ensureBound(world)` 确保 Lua 绑定；通过 `cfgScript.globals()` 与 `TerrainScript.loadedToken()` 两个身份令牌检测 terrain.lua 热重载——**编辑 `surfaceHeight`/`specialTerrains` 不改任何 terrainRender 值，也必须清高度缓存与全部 chunk**（否则旧碰撞形状成为"隐形遗迹边界"，`:181-185`）。逐字段读取并钳制（列宽 [0.5,64]、壳深 [2,500]、渲染窗口 [2 km,500 km]、物理窗口 [500, rangeM] 等，`:222-230`）；任一参数变化则写回字段、按需重建贴图（`Res.asset` 加载 + mipmap 过滤，失败退回渐变并记日志）、清高度缓存、`setupPlanet()`、`clearChunks()`。 |
| `setupPlanet` | `private void setupPlanet()` `TerrainSystem.java:261` | 由行星周长与 `blockW` 推导 `totalCols`（≥8）、实际列宽 `colW = circ/totalCols`（保证整周无缝平铺）、`totalChunks`。 |
| `update` | `void update(Vec2d shipUniverse, double simDt)` `TerrainSystem.java:269` | **每帧调用**。参数：飞船宇宙坐标、本帧模拟秒数。流程：`refreshParams()` → 选表面距离最近的行星，>200 km 则清空卸载返回 → 行星切换时清 chunk 与高度缓存并 `setupPlanet()` → 对每个有 body 的 chunk 做**速度驱动**（目标位置 = 行星浮点原点系位置 + chunk 中心偏移；有上一帧记录且 simDt 有效时 `setLinearVelocity(Δ/dt)`，否则 `setTransform` 瞬移并清零速度，`:296-308`）→ 10 Hz 节拍触发 `manage()`。 |
| `manage` | `private void manage(Vec2d shipUniverse)` `TerrainSystem.java:320` | 窗口管理：由飞船相对行星的角度算弧长位置与中心 chunk，按 `rangeM` 计算跨度集合 `want`（环绕取模），缺失的 `loadChunk`；遍历已加载 chunk，按 `wrappedArcDist` 与 `physRangeM` 决定 `createBody`/`destroyBody`（物理成员随较小物理窗口增减）；最后卸载窗口外 chunk。 |
| `wrappedArcDist` | `private double wrappedArcDist(double a, double b)` `TerrainSystem.java:356` | 两个弧长位置间的最短环绕距离（米）。 |
| `junctionHeight` | `private double junctionHeight(int j)` `TerrainSystem.java:363` | 交界高度（绝对半径，带缓存）。Lua 返回 NaN/Inf 或低于行星半径一半时回退到内置 `planet.heightAt`（`:369-371`）；缓存超 `HEIGHT_CACHE_CAP` 清空后重填。 |
| `colJitter` | `private static float colJitter(int colIdx)` `TerrainSystem.java:378` | 确定性每列亮度抖动（hash → [-1,1]），充当贴图颗粒感替代品。 |
| `loadChunk` | `private void loadChunk(int idx)` `TerrainSystem.java:384` | 加载一个 chunk：计算中心弧长/行星系中心坐标；构建渲染 mesh——每列 8 顶点（壳四边形渐变 4 点 + 深层四边形纯色 4 点）、12 索引两个四边形；壳顶颜色 = `planet.crustColor × topB × (1+jitter·bandVar)`，壳底 = `crustColor × botB`；**深层填充一直延伸到基准半径 R**（`botL = min(hL-crust, R)`，round 19"浮山"修复：高山地形否则会在星空背景上悬空成棕色条，`:407-412`）；`crust` 取 `clamp(max(depthM, max(40, R·5%)), …, MAX_CRUST)`（`:395`）。有海洋（`waterDensity>0`）且任一交界低于海平面时构建 `waterPoly`：上边缘为海平面弧、下边缘为淹没地形。 |
| `putVert` | `private static int putVert(float[] mv, int v, float x, float y, float r, float g, float b, float u, float vv)` `TerrainSystem.java:481` | 向顶点数组写入一个顶点（x,y,r,g,b,a=1,u,v 共 8 float），返回下一个写入位置。 |
| `createBody` | `private void createBody(Chunk c)` `TerrainSystem.java:496` | 为 chunk 建碰撞体：一个 KINEMATIC body 置于 chunk 中心，每列一个四边形 fixture——顶点与渲染 mesh **同源**（`junctionHeight`），chunk 局部坐标（减 `cx,cy`），两侧各扩 `SEAM_OVERLAP_M` 对应角度防缝隙；交给 Box2D 前用双精度算面积叉积，`|area2| < 0.01`（含 NaN）跳过并最多记 5 条错误日志（`:524-534`）；fixture 摩擦/弹性取 Lua 参数。 |
| `clearChunks` | `private void clearChunks()` `TerrainSystem.java:546` | 销毁并清空全部已加载 chunk。 |
| `render` | `void render(OrthographicCamera cam)` `TerrainSystem.java:551` | 绘制：行星浮点原点系平移模型矩阵 → 绑定 shader（`u_projTrans = cam.combined × model`；`u_texture` 绑 0 号单元，贴图或白图）→ 逐 chunk 渲染 mesh；存在水面时再以半透明蓝色 (0.15,0.35,0.75,0.75) 用 ShapeRenderer 从顶点 0 扇形三角化 `waterPoly`（`:581-586`）。 |
| `renderDebug` | `void renderDebug(OrthographicCamera, Box2DDebugRenderer)` `TerrainSystem.java:592` | 空实现（保留接口）。 |
| `dispose` | `void dispose()` `TerrainSystem.java:595` | 清 chunk、释放贴图与 ShapeRenderer。注意静态 `shader`/`whiteTex` 不在此释放。 |

---

## 3. OrbitPredictor — 轨道预测器

### 3.1 类职责

`OrbitPredictor`（`OrbitPredictor.java:21`）为 Map 视图轨道预测线与超倍速时间加速（warp）提供数值轨道外推。它对飞船施加**所有行星**的 ΣGM/r² 引力（与 `GameWorld.gravityAt` 同一公式、同一个 0.5·radius 奇点钳制），且传播期间行星自身沿 Kepler 轨道前进，因此 SOI 穿越与长程巡航保持真实（`:7-12`）。积分器为 **velocity-Verlet**，自适应步长取最近天体局部动力学时间 τ=√(r³/μ) 的 0.004 倍（约每圈 250 点），钳制在 [0.05 s, 20000 s]（类注释写 [0.5 s, 40000 s]，以 `adaptiveDt` 实际代码为准，`OrbitPredictor.java:248-250`）；最多产出 `MAX_STEPS=4200` 点，调用方自行抽稀到 GL 点预算；撞击行星表面或飞出星系（r_sun > 2e12 m）提前停止。

两个入口：`compute()` 服务于 Map 视图预测线（记录锚点系/太阳系的逐点参照位置，供绘制时平移）；`computeWarp()` 服务于 >4x 加速的预计算轨道跟随（额外记录逐点速度，且撞击检测使用真实地形高度）。

### 3.2 重要字段

| 字段 | 类型 | 说明 | 位置 |
|---|---|---|---|
| `MAX_STEPS` | `int = 4200` | 最大传播点数 | `OrbitPredictor.java:23` |
| `xs` / `ys` | `double[]` | 逐点惯性系宇宙坐标（长度 MAX_STEPS+1） | `OrbitPredictor.java:25-26` |
| `fx` / `fy` | `double[]` | 逐点**锚点天体**（传播起点最近天体或调用方指定）在该点时刻的位置；Map 绘制 `xs[i]-fx[i]+anchor.posNow`，把惯性路径平移到锚点当前系——构造上连续，消除了旧的"逐点最近天体锚定"在 SOI 边界产生的折角（20000 s 步长下可达 ~110°，`:27-37`） | `OrbitPredictor.java:38-39` |
| `sfx` / `sfy` | `double[]` | 逐点**太阳**位置（round 16 Map 系切换）：同 fx/fy 思路但锚定太阳，绘制日心系路径无需重算 Kepler 轨道 | `OrbitPredictor.java:45-46` |
| `ts` | `double[]` | 逐点绝对宇宙时间（world.time + 累计 dt） | `OrbitPredictor.java:48` |
| `frame` | `int[]` | 逐点信息性最近天体索引（仅信息展示，绘制已不用） | `OrbitPredictor.java:50` |
| `anchor` | `int` | 唯一绘制锚点：`GameWorld.planets` 索引，-1 表示无 | `OrbitPredictor.java:52` |
| `sun` | `int` | 太阳索引（无父天体者），-1 表示无 | `OrbitPredictor.java:54` |
| `offx` / `offy` | `double[]` | round 15 起废弃（单帧绘制），仅为保持二进制结构保留 | `OrbitPredictor.java:56-57` |
| `count` | `int` | xs/ys 中有效点数 | `OrbitPredictor.java:58` |
| `simSeconds` | `double` | 本次传播总时长 | `OrbitPredictor.java:59` |
| `impacted` | `boolean` | 是否因撞击行星表面而停止 | `OrbitPredictor.java:60` |
| `px`,`py`,`pmu`,`prad` / `pidx` / `n` | `double[]` / `int[]` / `int` | 行星表 scratch：当前评估时刻的位置、μ、半径、父索引（-1=太阳）、行星数 | `OrbitPredictor.java:63-65` |
| `ax`,`ay` | `double` | `accelAt` 输出的加速度 scratch | `OrbitPredictor.java:66` |

### 3.3 逐函数说明

| 函数 | 签名 / 位置 | 功能 · 参数 · 返回值 · 要点 |
|---|---|---|
| `compute`（重载 1） | `void compute(GameWorld world, Ship ship)` `OrbitPredictor.java:73` | 便捷入口：以自动锚点（-1）调用三参版本。结果写入 xs/ys[0..count)；飞船无零件时 count=0。 |
| `compute`（重载 2） | `void compute(GameWorld world, Ship ship, int anchorIdx)` `OrbitPredictor.java:85` | **Map 预测线主入口**。参数 `anchorIdx` 指定绘制参考系天体（round 17 锚点列表），-1 或无效/无质量索引回退为传播起点最近天体（round 15 行为）；切换锚点只需重算（调用方本就 ~4 Hz 重传播且选择变更时立即强制一次，`:79-84`）。流程：重置状态 → `bindPlanets` → 取飞船宇宙位置/速度 → 记录首点并定锚（`:109-110`）→ 主循环最多 MAX_STEPS 步：`adaptiveDt` 取步长 → velocity-Verlet kick-drift-kick（**行星在步中点随时间推进**，`:122-124`）→ 记点 → 撞击检测（对任一 μ>0 天体 r < radius，基准圆测试）→ 逃逸检测（r² > 4e24，即 r_sun > 2e12 m）。结束后写 `simSeconds`。 |
| `bindPlanets` | `private void bindPlanets(List<Planet> planets)` `OrbitPredictor.java:149` | `compute`/`computeWarp` 共用的行星表初始化：按需扩容 scratch 数组，填充 μ/半径；父索引利用 `flatten()` 父先序保证向前线性查找（`:162-165`）；无父天体者记为 `sun`。 |
| `computeWarp` | `int computeWarp(GameWorld world, Ship ship, double dtScale, double[] wx, double[] wy, double[] wvx, double[] wvy, double[] wt)` `OrbitPredictor.java:185` | **warp 轨迹外推**（round 19 超级加速重写）：与 `compute` 同一 velocity-Verlet、同一 ΣGM/r²、同一自适应步长规则（同源保证飞行路径与 Map 线不发散），但额外记录逐点速度到调用方数组（wx/wy/wvx/wvy/wt 五数组等长，容量 cap）；`dtScale` 拉伸步长（高倍加速的行星间巡航容忍 4x）。返回点数（无可传播时 0），`impacted` 标记撞地结束。与 `compute` 的基准圆撞击检测不同，这里对**真实地形**（`radius + heightAt`，先以 `radius + max(0, maxHeight)` 外圈粗测再精测，`:226-229`）判定，保证交接点落在地面而非山体内——两条线仅在最后进近几米内有差异。 |
| `adaptiveDt` | `private double adaptiveDt(double x, double y)` `OrbitPredictor.java:240` | 自适应步长：最近天体局部轨道时间尺度 τ=√(r³/μ) 的 0.004 倍（≈250 点/圈；类 Smearth 表面附近约 1 s，短弹道跳跃也能在撞击前产出致密弧线，`:246-247`）；钳制 [0.05, 20000] 秒；无有效天体时返回 60。 |
| `nearestBody` | `private int nearestBody(double x, double y)` `OrbitPredictor.java:255` | 按**表面距离**（中心距 − 半径）取最近天体索引（与 `GameWorld.currentPlanet` 同规则）；跳过 μ≤0 天体，无则返回 -1。 |
| `recordFrame` | `private void recordFrame(int i, double x, double y)` `OrbitPredictor.java:268` | 记录第 i 点的信息性最近天体 `frame[i]`，以及该时刻锚点位置 `fx/fy[i]` 与太阳位置 `sfx/sfy[i]`。 |
| `accelAt` | `private void accelAt(double x, double y)` `OrbitPredictor.java:275` | 用 `systemAt` 缓存的行星位置计算 (x,y) 处 ΣGM/r² 引力加速度，写入 `ax/ay`；r 钳制下限为 0.5·radius（镜像 `GameWorld.gravityAt`，`:282-283`）。 |
| `systemAt` | `private void systemAt(List<Planet> planets, double t)` `OrbitPredictor.java:292` | 求绝对时刻 t 所有行星的宇宙位置（Kepler 轨道）：太阳置原点；其余天体由平均运动 n=√(μ_p/a³) 推平近点角 M=n·t+v0（逆行轨道取负），M 环绕到 [-π, π] 后以牛顿迭代 12 次解开普勒方程（与 `Planet.solveKepler` 同一迭代，`:304-308`），再按真近点位置旋转 ω 后叠加父天体位置。 |
