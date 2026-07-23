# DifferentRockets 交接文档索引

面向新接手开发者。建议按编号顺序阅读：先总览，再按你负责的模块深入，最后把 `api/` 当字典随用随查。

## 第一层：入门（必读）

| 文档 | 内容 |
|---|---|
| [00-项目总览](00-项目总览.md) | 游戏玩法闭环、四层架构、主循环数据流、六大关键设计决策（含模块依赖图） |
| [01-构建与协作指南](01-构建与协作指南.md) | clone 后直接编译的三条命令、APK 产出路径、桌面版运行、玩家资源目录机制、git 协作注意 |

## 第二层：Java 模块机制（按模块深入）

| 文档 | 内容 |
|---|---|
| [10-游戏循环与物理-GameWorld](10-游戏循环与物理-GameWorld.md) | 物理步进主循环、多体引力、弹性阻尼连接点、加速档位体系（含 >4x 预计算轨道跟随）、存读档 |
| [11-零件飞船与连接](11-零件飞船与连接.md) | PartType/ShipDesign/Ship 三层模型、连接点类型系统（整边 vs 单点、0.25 吸附）、激活组、fuelline 规则 |
| [12-轨道行星与转向](12-轨道行星与转向.md) | velocity-Verlet 轨道预测器、单锚帧防折叠设计、行星 Kepler 推进、SteeringIO 转向链路 |
| [13-地形系统](13-地形系统.md) | 柱状四边形列环、lua surfaceHeight 与列缓存、10Hz 加载窗口、碰撞防回归、渲染与高度单轨制 |
| [14-Mod系统与Lua桥接](14-Mod系统与Lua桥接.md) | 每零件独立 Globals、三钩子生命周期、ModApi 全能力清单、PID 库、程序化尾焰 |
| [15-资源同步与工具类](15-资源同步与工具类.md) | /sdcard 双根策略、.defaults 版本化同步算法（六级判定）、版本头回退识别、图集/JSON/XML 工具 |
| [16-建造编辑器界面](16-建造编辑器界面.md) | 拖出手势状态机、触摸分发优先级（含"按压未释放"历史 bug）、吸附与激活组 UI |
| [17-沙盒与Map界面](17-沙盒与Map界面.md) | HUD 按钮体系、转向环交互、零件点选判定、Map 视图（double 基准化/锚点列表/相机跟随） |
| [18-平台层与构建配置](18-平台层与构建配置.md) | 两个启动器、存储权限策略（MANAGE_EXTERNAL_STORAGE）、gradle 配置、APK 签名现状 |

## 第三层：lua 模组脚本（玩家可改的行为定义）

| 文档 | 内容 |
|---|---|
| [20-lua引擎与推进脚本](20-lua引擎与推进脚本.md) | 6 个引擎 + 电推、摆角控制率（control.lua 矢量法）、flame.lua 气压分层尾焰（马赫环） |
| [21-lua结构与油箱脚本](21-lua结构与油箱脚本.md) | 油箱系列、机身/鼻锥/支杆/着陆腿/轮子，含三处"注释与代码不一致"的核查发现 |
| [22-lua功能零件脚本](22-lua功能零件脚本.md) | pod/对接口/分离器/降落伞/RCS/电池/太阳能，燃料供给范围总表 |
| [23-lua系统脚本](23-lua系统脚本.md) | physics.lua（引力/大气阻力）、joints.lua（连接点通用行为）、terrain.lua 全接口、planets.lua 16 天体 |

## 第四层：API 参考（逐函数字典，含 private）

| 文档 | 覆盖类 |
|---|---|
| [api/API-GameWorld](api/API-GameWorld.md) | GameWorld（41 个函数 + 字段表） |
| [api/API-SandboxScreen](api/API-SandboxScreen.md) | SandboxScreen（全部方法 + 内部类） |
| [api/API-EditorScreen与地形轨道](api/API-EditorScreen与地形轨道.md) | EditorScreen、TerrainSystem、OrbitPredictor |
| [api/API-零件飞船行星](api/API-零件飞船行星.md) | Part、PartType、PartList、ShipDesign、Ship、Attach、Planet、PlanetDefs、SteeringIO |
| [api/API-Mod桥接与资源](api/API-Mod桥接与资源.md) | ModManager、ModApi（60+ lua 可见方法逐个列）、LuaScript、JointScript、PhysicsScript、FlameScript、FlameFx、Res |
| [api/API-界面工具与启动器](api/API-界面工具与启动器.md) | DRGame、MenuScreen、Ui、AtlasPack、Json、Xml、Vec2d、两个 Launcher、TerrainScript |

## 阅读约定

- 代码引用统一为 `文件名:行号`（省略包路径前缀）。
- 术语保留英文原文（velocity-Verlet、fixture、SOI、weld joint 等）。
- 文档中标注的 round-N 是迭代历史记录，帮助理解设计动因。
- 已知"代码与注释不一致"处均已如实标注（如 OrbitPredictor 步长钳制、nosecone 负阻力、angularDamping 三处数值），接手时以文档标注的生效值为准。
