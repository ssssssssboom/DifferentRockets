-- v2026.07.31
-- ============================================================================
-- RCS 推进器块：3 个子喷口 u/d/r（羽流方向默认 上/下/右，随零件旋转/翻转）。
-- 单组元(mono)全船共享（round 11）：船上任何 mono 油箱喂所有 RCS，无需管路。
--
-- 操控语义（SteeringIO RCS 面板 + 导航环，round 34 task 3）：
--   <     → 所有【未镜像】RCS 的 r 喷口（羽流向右 → 船向左平移）
--   >     → 所有【已镜像】RCS 的 r 喷口（羽流向左 → 船向右平移）
--   UP    → 所有 d 喷口（羽流向下 → 船向上平移）
--   DOWN  → 所有 u 喷口（羽流向上 → 船向下平移）
--   ROTL/ROTR → 每个 RCS 实时取飞船质心，按自己相对质心的位置与各喷口
--               可用朝向，选【贡献角加速度最大】的那个喷口点火。
--   导航环 → 等效 ROTL/ROTR：目标夹角 <10° 时强度从 100% 线性衰减到 0。
-- 长按期间：喷白色小股粒子（emitJet，FlameFx 小尺度加法粒子）+ 最大推力
-- + 消耗 mono。rcsEnabled 总开关关闭时本脚本完全不动。
-- ============================================================================

local FORCE = 850.0               -- 每喷口最大推力基准（N）：power(1.0) → 850 N（round 37 单位迁移：全部力常量 ÷10）
local RING_FADE = math.rad(10)     -- 导航环强度线性衰减区间（<10° → 0）

local function wrapPi(a)
  a = a % (2 * math.pi)
  if a > math.pi then a = a - 2 * math.pi end
  return a
end

-- 本零件的三个子喷口：{羽流局部方向x, y, 局部位置x, y}（米，零件局部坐标）
local function nozzles(part)
  local w, h = part:getWidth(), part:getHeight()
  local rd = part:isFlippedX() and -1 or 1   -- 镜像零件的 r 喷口在左侧、羽流向左
  local u = { 0, 1, 0, h / 2 }
  local d = { 0, -1, 0, -h / 2 }
  if part:isFlippedY() then u, d = d, u end  -- 垂直翻转：上下喷口对调
  return { u = u, d = d, r = { rd, 0, rd * w / 2, 0 } }
end

function onLoad(part)
end

function onStage(part)
end

function onUpdate(part, dt)
  local st = part:getSteering()
  if not st.rcsEnabled then return end

  local a = part:getAngle()
  local ca, sa = math.cos(a), math.sin(a)
  local nz = nozzles(part)
  local firing = {}   -- 喷口名 -> 强度 0..1

  -- 平移指令（按钮语义见文件头）
  if st.holdLeft and not part:isFlippedX() then firing.r = 1 end
  if st.holdRight and part:isFlippedX() then firing.r = 1 end
  if st.holdUp then firing.d = 1 end
  if st.holdDown then firing.u = 1 end

  -- 旋转指令：q>0 = 逆时针（ROTL / 导航环误差为正），|q| 为强度
  local q = 0
  if st.rotLeft then q = 1
  elseif st.rotRight then q = -1
  elseif st.active then
    local err = wrapPi(st.targetRad - part:getShipHeading())
    local s = math.min(1, math.abs(err) / RING_FADE)   -- <10° 线性衰减到 0
    q = err < 0 and -s or s
  end
  if q ~= 0 then
    local rx = part:getX() - part:getShipComX()
    local ry = part:getY() - part:getShipComY()
    local best, bestVal = nil, 0
    for key, n in pairs(nz) do
      -- 单位推力（世界）= -羽流方向，随船体角 a 旋转
      local tx = -(n[1] * ca - n[2] * sa)
      local ty = -(n[1] * sa + n[2] * ca)
      local tq = rx * ty - ry * tx        -- 相对质心的单位力矩 z
      if q * tq > bestVal then bestVal, best = q * tq, key end
    end
    if best then firing[best] = math.max(firing[best] or 0, math.abs(q)) end
  end

  -- 点火：耗燃料 → 施加推力（frame force，全帧连续）→ 白色粒子
  local power = part:getRcsPower()
  local cons = part:getRcsConsumption()
  for key, s in pairs(firing) do
    local n = nz[key]
    local need = cons * 10 * dt * s
    local got = need > 0 and part:drainFuel(1, need) or 0
    if need > 0 and got / need > 0.2 then
      local f = FORCE * power * s
      part:applyForceAt(-(n[1] * ca - n[2] * sa) * f,
                        -(n[1] * sa + n[2] * ca) * f, n[3], n[4])
      part:emitJet(0.5 * s, n[1] * ca - n[2] * sa,
                     n[1] * sa + n[2] * ca, n[3], n[4])
    end
  end
end
