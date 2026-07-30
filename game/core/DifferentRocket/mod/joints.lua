-- v2026.07.31
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
--   frequencyHz    焊接弹簧频率（赫兹）。0 = 完全刚性（v2026.07.31 起默认）：
--                  连接点无线性/角向柔度，断裂仍按 breakForce 反作用力检测。
--                  >0 = 弹簧软连接：2-5 松软像橡胶，20-30 硬弹簧。注意软弹簧
--                  在重载（如侧挂助推）下会产生毫米-厘米级静载变形和可见扭转，
--                  且 30+ 超出 60 Hz 物理步长能稳定求解的范围。
--   dampingRatio   阻尼比（仅 frequencyHz>0 时有效）。<1 欠阻尼（回弹），
--                  1 临界阻尼，>1 过阻尼（发黏）。
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
-- 爬升时助推器外倾约 5°（弹簧线变形差所致），与求解迭代数无关；0 Hz 刚性
-- 焊接 + 固定 24/4 求解迭代 + 小零件角惯量下限后，全程焊缝拉伸/角位移均
-- 为噪声级。断裂检测不变（getReactionForce 超 breakForce 持续 5 帧即断）。
local DEFAULT_FREQ = 0.0      -- frequencyHz 默认：0 = 完全刚性（round 32: 28 -> 0）
local DEFAULT_DAMP = 1.0      -- dampingRatio 默认：临界阻尼（仅软弹簧时有效）
local DEFAULT_ANGDAMP = 1.0   -- angularDamping 默认：每秒衰减比例（was 0.6）

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
        -- 角速度阻尼：任一侧有覆盖就用覆盖，否则用全局默认
        angularDamping = oA.angularDamping or oB.angularDamping
                         or DEFAULT_ANGDAMP,
        -- breakForce 省略 → 取两连接点的较小值（见文件头说明）
    }
end
