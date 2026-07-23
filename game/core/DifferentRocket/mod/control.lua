-- v2026.07.22.1
-- ============================================================================
-- control.lua — 发动机摇摆控制律（玩家可改，round 12 / round 13 修订）
-- ============================================================================
-- 所有引擎脚本共用这【一个】控制函数 controlLaw(part)，每个物理帧调用一次，
-- 返回值就是该引擎这一帧的摇摆偏转角（度，正 = 推力向顺时针偏，产生顺时针
-- 力矩；负 = 逆时针）。规则刻意简单，没有 PID：
--
--   按钮模式 BUTTON（SteeringIO.buttonTurn = -1/+1，UI 按住转向键）：
--       所有可摇摆发动机统一打到该方向的【最大】摇摆角，覆盖转向环。
--   转向环模式 RING（SteeringIO.ringActive = true）：
--       摇摆角 = 航向误差（目标航向 − 当前船首向），按本引擎自己的最大
--       摇摆角截断 —— 误差大时满偏，接近目标时线性回中。
--   无输入（环未激活且没按按钮）：摇摆回中（0）。
--
-- 角度约定：船首向与目标航向都是 Box2D body 角（弧度，0 = 船头朝"上"，
-- 逆时针为正）。round 13 起误差角改用【矢量法】计算（owner 决策）：把船头
-- 指向与目标指向都变成单位向量，误差 = atan2(二维叉积, 点积)。叉积与点积
-- 都是连续函数，船转过 ±180° 时误差角平滑环绕、没有取模运算的跳变沿；
-- 且该式与"航向 0 点朝哪"的全局约定无关 —— 只要 p、t 用同一约定，参考系
-- 在叉积/点积中相互抵消。船首向取指令舱角 part:getShipHeading()；想让每台
-- 发动机按自身角度控制，把 getShipHeading() 换成 part:getAngle()。
-- （luaj 注意：必须用 math.atan2(y,x)；math.atan 的双参数形式会静默忽略
-- 第二个参数，已实测。）
--
-- 引擎脚本在 onLoad 里通过 part:readModText("control.lua") 把本文件载入
-- 自己的 Lua 状态 —— 修改保存后，新造的船（或资源重载）才会用新版本。
-- 无 control.lua 或加载失败时，所有发动机摇摆保持 0（安全回中）。
--
-- 历史：本文件取代了 round 9 的每引擎 PID 摇摆执行机构（physics.lua 的
-- gimbal 表）与 GameWorld 的船级 PI 转向（physics.lua 的 steering 表），
-- 两张表仅作保留，不再被读取。
-- ============================================================================

function controlLaw(part)
  local s = part:getSteering()
  local maxDeg = part:getEngineTurn()
  if maxDeg <= 0 then return 0 end

  -- BUTTON: 统一满偏，覆盖转向环
  if s.buttonTurn ~= 0 then
    return s.buttonTurn * maxDeg
  end

  -- RING: 摇摆角 = 航向误差（度），按引擎量程截断
  if s.active then
    -- 矢量法误差角：p = 船头指向单位向量，t = 目标指向单位向量
    -- （体角 a 下设计 +y 的指向为 (-sin a, cos a)，目标同理）
    local a = part:getShipHeading()
    local px, py = -math.sin(a), math.cos(a)
    local tx, ty = -math.sin(s.targetRad), math.cos(s.targetRad)
    -- atan2(叉积, 点积)：连续环绕，无 ±180° 跳变
    local err = math.atan2(px * ty - py * tx, px * tx + py * ty)
    local g = -math.deg(err) -- 误差为正(需逆时针) → 负摇摆(逆时针力矩)
    if g > maxDeg then g = maxDeg elseif g < -maxDeg then g = -maxDeg end
    return g
  end

  return 0 -- 无输入：回中
end
