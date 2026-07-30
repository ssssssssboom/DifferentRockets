-- v2026.07.31.3
-- ============================================================================
-- joints.lua — 连接（焊接点）规则（玩家可改）
-- ============================================================================
-- 每形成一处连接，游戏都会调用 jointParams(partA, attachA, partB, attachB)，
-- 用返回的表覆盖默认焊接参数。整段函数可删：删除或报错后，游戏退回内置规则
-- （零件各自 setJointParams 的覆盖值，frequencyHz 高者胜，阻尼比随之）。
--
-- 参数：
--   partA / partB —— 两个零件的 Lua API（同 onLoad/onUpdate 里的 part）
--   attachA / attachB —— 连接点表：
--       { x, y            连接点在零件局部坐标中的位置（米）
--         fuelLine        是否燃油管路点（true/false）
--         edge            0=普通单点 1=左边 2=右边 3=顶边 4=底边（整条边可连）
--         breakForce      该连接点的断裂力上限（千牛）；不可断的点省略此键 }
--
-- 返回表（键都可省略，省略即用默认值）：
--   frequencyHz    焊接弹簧频率（赫兹，真实弹性语义）。48 为默认
--                  （v2026.07.31.2 起）：有明确弹性的硬弹簧。物理步进已升至
--                  120 Hz（round 33），稳定求解域上限约 60 Hz——不要超过。
--                  越低越软：2-5 松软像橡胶，20-30 中等。0 = 完全刚性
--                  （无弹性的备选；断裂检测两种模式下都按 breakForce 反作用力）。
--   dampingRatio   阻尼比。<1 欠阻尼（回弹），1 临界阻尼，>1 过阻尼（发黏）。
--                  注意（v2026.07.31.3 源码事实）：本引擎 Box2D 软焊接的
--                  frequencyHz/dampingRatio 只作用于【角向】通道——线向
--                  始终是刚性约束，不会变弹；且其内置阻尼公式对重型堆叠的
--                  弯曲模态几乎无效（probe21 实测 ringing >3 秒）。真正消振
--                  请用下面的 angularDampingRatio。
--   angularFrequencyHz    角向弹簧频率的显式命名（= frequencyHz，二选一，
--                  同时给出时此键优先）。v2026.07.31.3 新增。
--   angularDampingRatio   显式黏性角阻尼比（v2026.07.31.3 新增，真消振）。
--                  每个物理子步对两零件施加 τ=∓c·Δω，c = ζ·2ω·I_red。
--                  1.0 = 临界阻尼（默认，probe21：48Hz 下满推力爬升稳态
--                  0.067°、扰动 0.65 秒收敛、无回摆）。安全上限约 1.5，
--                  2.0 会数值失稳（发散抖动）。
--   angularDamping 两零件的角速度阻尼（每秒衰减比例，0=不衰减）。
--   breakForce     断裂力上限（千牛）。省略时取两连接点中较小的那个；
--                  想造永不分离的连接可填 1e18。
--
-- 下面这个默认实现还原了内置规则，可在此基础上改造，例如：
--   * 按零件类型定软硬    if partA:getTypeId() == "strut-1" then ... end
--   * 按连接点位置定软硬  if attachA.edge ~= 0 then ... end
--   * 让引擎座更容易断    if attachA.breakForce then ... end
-- ============================================================================

-- Round 22 (v2026.07.26): 通用默认值改为 20 Hz / 1.0 / 1.0，直接写在本
-- 文件里（此前转读 physics.lua 的 `joints` 表，该表仍是旧的 20/1.1/0.6，
-- 已不再是默认链路的一环）。
-- Round 23 (v2026.07.27): 全部连接点性质统一为 28 Hz / 1.0 / 1.0 ——
-- DEFAULT_FREQ 20.0 -> 28.0，physics.lua 的 `joints` 表同步为同一组值，
-- 零件 lua 里的显式覆盖（strut-1 24Hz/1.25、dock-1/port-1 8Hz/0.9）已删除。
-- Round 32 (v2026.07.31): 默认改为 0 Hz（完全刚性焊接）。根因排查（probe19）
-- 证实 28 Hz 软弹簧在重载剪切耦合下产生真实静载变形：侧挂三联助推满推力
-- 爬升时助推器外倾约 5°（弹簧线变形差所致），与求解迭代数无关。
-- Round 33 (v2026.07.31.2): 恢复真实弹性默认 48 Hz / 1.0（用户否决绝对刚性）。
-- 配套：物理步进 60->120 Hz（稳定域上限 ~60 Hz，probe20 实测 48 Hz 满推力
-- 爬升最大角位移 0.067°、拉伸 <0.1 mm、无振荡，28 Hz 在同条件下 0.22°
-- 也及格但余量小）；求解迭代固定 24/4；小零件角惯量下限 I>=m*25（round 32
-- 保留，它同时把角向弹簧刚度放大了 ~17 倍，是弹性回归的关键支撑）。
-- Round 33b (v2026.07.31.3): 拆分角向语义 + 显式黏性角阻尼。源码核实本引擎
-- Box2D 软焊接：线向刚性 2x2 约束，弹性只在角向通道，且其内置阻尼公式
-- （gamma=h(d+h·k)）对重型堆叠弯曲模态无效（probe21：dampingRatio 1.0-1.6
-- 均 ringing >3 秒且越调越差）。新增 angularDampingRatio（默认 1.0，每个
-- 物理子步施加 τ=∓c·Δω，c=ζ·2ω·I_red）：probe21 实测 48Hz/ζ=1.0 满推力
-- 稳态 0.067°、overshoot 1.00、5.4° 冲击 0.65 秒收敛、无回摆（regrow=1.00）；
-- ζ=2.0 失稳，安全域 ζ<=1.5。angularFrequencyHz 为 frequencyHz 的显式别名。
-- 断裂检测不变（getReactionForce 超 breakForce 持续 5 帧即断）。
local DEFAULT_FREQ = 48.0     -- frequencyHz 默认：48 Hz 硬弹簧（round 33: 0 -> 48）
local DEFAULT_DAMP = 1.0      -- dampingRatio 默认：临界阻尼
local DEFAULT_ANGDAMP = 1.0   -- angularDamping 默认：每秒衰减比例（was 0.6）
local DEFAULT_ANGVISC = 1.0   -- angularDampingRatio 默认：显式黏性角阻尼（round 33b）

function jointParams(partA, attachA, partB, attachB)
    -- 两侧零件在 onLoad 里通过 part:setJointParams{...} 设置的覆盖值
    local oA = partA:getJointParams()
    local oB = partB:getJointParams()

    -- frequencyHz 高者胜（更硬的一侧说了算），它的 dampingRatio 随同
    local fA, fB = oA.frequencyHz, oB.frequencyHz
    local freq, damp
    if fA and (not fB or fA >= fB) then
        freq, damp = fA, oA.dampingRatio
    elseif fB then
        freq, damp = fB, oB.dampingRatio
    end

    return {
        -- 覆盖值缺省时用本文件的通用默认（round 22: 20 / 1.0 / 1.0）
        frequencyHz    = freq or DEFAULT_FREQ,
        dampingRatio   = damp or DEFAULT_DAMP,
        -- 显式黏性角阻尼（round 33b 真消振）：任一侧有覆盖就用覆盖
        angularDampingRatio = oA.angularDampingRatio or oB.angularDampingRatio
                              or DEFAULT_ANGVISC,
        -- 角速度阻尼：任一侧有覆盖就用覆盖，否则用全局默认
        angularDamping = oA.angularDamping or oB.angularDamping
                         or DEFAULT_ANGDAMP,
        -- breakForce 省略 → 取两连接点的较小值（见文件头说明）
    }
end
