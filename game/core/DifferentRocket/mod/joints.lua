-- v2026.07.31.5
-- ============================================================================
-- joints.lua — 连接（焊接点）规则（玩家可改）
-- ============================================================================
-- SimpleRockets 连接模型（round 35，已从 SR 的 APK ARM 汇编逐条核实）：
--
--   * 零件连接 = 完全刚性的焊接关节（frequencyHz = 0），没有任何弹簧，
--     也没有任何显式阻尼。
--   * 物理固定 1/60 秒步长，求解器 6 次速度迭代 / 2 次位置迭代。
--     刚性约束在 6/2 迭代下欠收敛，残余变形本身就是 SR 火箭"软/摇摆"
--     手感的来源——迭代数越低越软，越高越硬。玩家可用 JVM 属性
--     -Dr.velIter= / -Dr.posIter= 调整（如 8/3 更硬，4/1 非常软）。
--   * 断裂判据：每个物理帧单帧判定，三通道任一超限立即断：
--       1) 反作用力  > breakForce（千牛）
--       2) 反作用扭矩 > breakTorque（千牛·米）
--       3) 角度偏差  |当前两体夹角 − 焊接时夹角| > breakAngle（弧度，
--          SR 默认约 0.6）
--
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
--         breakForce      该连接点的断裂力上限（千牛）；不可断的点省略此键
--         breakTorque     该连接点的断裂扭矩上限（千牛·米）}
--
-- 返回表（键都可省略，省略即用默认值）：
--   frequencyHz    焊接弹簧频率（赫兹）。默认 0 = 完全刚性（SR 模型）。
--                  >0 会变回软弹簧焊接（旧行为，仅供实验）；刚性模式下弹性
--                  手感完全由求解器欠收敛提供，不需要它。
--   dampingRatio   Box2D 软焊接阻尼比，仅 frequencyHz > 0 时有意义。默认 1。
--   angularFrequencyHz    角向弹簧频率的显式命名（= frequencyHz，二选一，
--                  同时给出时此键优先）。
--   angularDampingRatio   显式黏性角阻尼比（调试键，默认 0 = 关闭）。
--                  每个物理子步对两零件施加 τ=∓c·Δω，c = ζ·2ω·I_red。
--                  SR 模型下不使用；仅在实验软弹簧时有用。
--   angularDamping 两零件的角速度阻尼（每秒衰减比例，默认 0 = 不衰减）。
--   linearDampingRatio  线向锚点黏性阻尼比（调试键，默认 0 = 关闭；
--                  实测 >0 在重载下数值失稳，不要开启）。
--   breakForce     断裂力上限（千牛）。省略时取两连接点中较小的那个；
--                  想造永不分离的连接可填 1e18。单帧超限即断。
--   breakTorque    断裂扭矩上限（千牛·米）。省略时取两连接点中较小的那个。
--                  单帧超限即断。
--   breakAngle     断裂角度偏差上限（弧度，默认 0.6，SR 的值）。
--                  |当前两体夹角 − 焊接时夹角| 单帧超过即断。
--
-- 下面这个默认实现还原了内置规则，可在此基础上改造，例如：
--   * 按零件类型定软硬    if partA:getTypeId() == "strut-1" then ... end
--   * 按连接点位置定软硬  if attachA.edge ~= 0 then ... end
--   * 让引擎座更容易断    if attachA.breakForce then ... end
-- ============================================================================
-- Round 35 (v2026.07.31.5): 完整切换到 SimpleRockets 模型 —— 刚性 0 Hz 焊接
-- + 60 Hz / 6+2 迭代欠收敛手感；断裂改为单帧三通道（力 / 扭矩 / 角度偏差，
-- 新增 breakAngle 默认 0.6 rad）；删除 5 帧持续判据；angularDampingRatio /
-- linearDampingRatio / angularDamping 全部默认 0（保留键供调试）；
-- Part.java 的人造惯量下限（I>=m*25）同步删除，恢复真实箱型惯量。
local DEFAULT_FREQ = 0.0      -- frequencyHz 默认：0 = 完全刚性焊接（SR）
local DEFAULT_DAMP = 1.0      -- dampingRatio 默认：仅软弹簧模式下有意义
local DEFAULT_ANGDAMP = 0.0   -- angularDamping 默认：不衰减
local DEFAULT_ANGVISC = 0.0   -- angularDampingRatio 默认：关闭（SR 无显式阻尼）
local DEFAULT_LINVISC = 0.0   -- linearDampingRatio 默认：关闭（开启即失稳）

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
        frequencyHz    = freq or DEFAULT_FREQ,
        dampingRatio   = damp or DEFAULT_DAMP,
        -- 显式黏性阻尼（调试键，SR 模型默认 0 关闭）：任一侧有覆盖就用覆盖
        angularDampingRatio = oA.angularDampingRatio or oB.angularDampingRatio
                              or DEFAULT_ANGVISC,
        angularDamping = oA.angularDamping or oB.angularDamping
                         or DEFAULT_ANGDAMP,
        linearDampingRatio = oA.linearDampingRatio or oB.linearDampingRatio
                             or DEFAULT_LINVISC,
        -- breakForce/breakTorque 省略 → 取两连接点的较小值（见文件头说明）
        -- breakAngle 省略 → 用内置默认 0.6 rad
    }
end
