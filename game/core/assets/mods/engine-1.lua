-- v2026.08.01
-- v2026.07.31: unit convention migration (round 37, all forces /10): thrust N
-- = power * 8.5e3 (SR native: double 8500.0 @0x1cdb78; Blasto 425 = power 5.0
-- -> 425 kN in SR's own mass scale). Mass unit = XML mass * 50 kg (SR GetMass,
-- .data:0x2542d8 = 50.0f); fuel kg = fuel * 0.1 (6000 fuel = 600 kg). Same
-- moon hop: 600 kg / 12.5 kg/s = 48 s, vmax ~= 4523 m/s -- dynamics identical
-- to the previous 500-kg-unit system.
-- DifferentRockets generic engine behavior.
-- Thrust vector = power * throttle along the nozzle, gimbal follows the SHARED
-- control law in mod/control.lua (round 12), fuel drained through the ship's
-- fuel network. SRBs (fuelType 3) burn their internal tank at full power with
-- no throttle/gimbal. Ion (fuelType 2) drains the electric network.
--
-- 激活即按当前油门 (round 26, item B1): onStage 只是解除保险; 推力在
-- onUpdate 里每帧实时读取 part:getThrottle() (SteeringIO/油门条共享的当前
-- 油门), 所以引擎在激活瞬间以及之后的每一帧都严格按当前油门运行 ——
-- 0 油门激活不点火, 之后推油门即点火, 不存在默认满油门。
-- Activation honors the CURRENT throttle (round 26, item B1): onStage only
-- re-arms the engine; thrust reads part:getThrottle() live every onUpdate,
-- so the engine runs at whatever the throttle is the instant it activates
-- and thereafter — staging at 0 throttle stays dark until throttle is
-- raised; there is no implicit full-throttle default. (SRBs stay
-- full-throttle by design: a lit solid booster cannot be throttled.)
--
-- 燃油供给规则 (part:drainFuel): 化油(type 0)发动机只从通过 fuelLine 连接点
-- 与它相连的油箱(以及直接贴在它身上的油箱)组成的燃料管网中取油; 被无
-- fuelLine 的零件(指令舱/分离器/电池等)隔开的油箱不会给它供油。
-- 单组元(type 1, RCS)与电力(type 2)全船共享, 固体燃料(type 3)只烧自身。
-- Fuel supply rule: chemical engines (fuelType 0) draw only from tanks
-- reachable through fuelLine attach points (plus tanks bolted directly onto
-- this part); tanks cut off by non-fuelLine parts (pod/detacher/battery)
-- stay isolated. Monopropellant (1) and electric (2) are shared ship-wide,
-- solid (3) burns its internal tank only.
--
-- 摇摆控制 (round 12): 偏转角由 control.lua 的 controlLaw(part) 直接给出
-- (按钮=满偏 / 转向环=航向误差截断 / 无输入=回中), 不再有每引擎 PID;
-- 推力方向使用 controlLaw 返回的【实际】偏转角 part:getGimbalDeg()。
-- Gimbal control (round 12): deflection comes straight from controlLaw(part)
-- in mod/control.lua (button = max deflection / ring = clamped heading error
-- / no input = centered) — the round-9 per-engine PID is superseded.
local staged = false
local ignitionThrottle = 0 -- B1: throttle captured at the activation instant

function onLoad(part)
  -- re-arm after a save/load roundtrip: staging is group-number based now
  -- (round 26, item B4 — part:getGroup() is this part's stage number,
  -- part:getStage() is the last stage number fired), so this engine is
  -- already burning iff its own stage has fired. Group-0 parts (activated
  -- manually via ACTIVATE) are NOT re-armed: re-activate them after load.
  local grp = part:getGroup()
  staged = grp > 0 and part:getStage() >= grp
  -- load the shared control law into THIS engine's Lua state (every part
  -- instance owns a separate state; control.lua edits apply to newly
  -- created ships / after a resource reload)
  if controlLaw == nil then
    local src = part:readModText("control.lua")
    if src ~= nil then pcall(load(src, "control.lua")) end
  end
end

function onStage(part)
  staged = true
  -- B1: read the current throttle at the activation instant too (the live
  -- per-frame read in onUpdate is what actually drives thrust); igniting at
  -- 0 throttle is legal and simply waits for throttle.
  ignitionThrottle = part:getThrottle()
end

local function drainOwn(part, dt)
  local need = part:getEngineConsumption() * dt
  local have = part:getFuel()
  local use = math.min(need, have)
  part:setFuel(have - use)
  if need > 0 then return use / need else return 0 end
end

-- 每物理帧向控制律要偏转角; 即使油门为 0 也运行(关车后摇摆回中)。
-- Returns the ACTUAL deflection (deg) to aim thrust/flame along this tick.
local function updateGimbal(part, dt)
  if part:getEngineFuelType() == 3 or part:getEngineTurn() <= 0 then return 0 end
  if controlLaw == nil then return 0 end -- control.lua 缺失/加载失败: 安全回中
  local g = controlLaw(part)
  part:setGimbalDeg(g)
  return g
end

function onUpdate(part, dt)
  if not staged then return end
  local gimbal = updateGimbal(part, dt)
  local ft = part:getEngineFuelType()
  local th = part:getThrottle()
  local thrust = 0

  if ft == 3 then
    -- solid rocket booster
    local frac = drainOwn(part, dt)
    thrust = part:getEnginePower() * 8.5e3 * frac
    gimbal = 0 -- SRB: rigid nozzle, no gimbal
  else
    if th <= 0 then return end
    local te = th
    if part:isThrottleExponential() then te = th * th end
    local need = part:getEngineConsumption() * te * dt
    local got = part:drainFuel(ft, need)
    local frac = need > 0 and (got / need) or 0
    thrust = part:getEnginePower() * 8.5e3 * te * frac
  end

  if thrust <= 0 then return end
  local ang = part:getRigidAngle() + math.rad(gimbal) -- round 40h: SR rigid-weld thrust baseline
  local dx = -math.sin(ang)
  local dy = math.cos(ang)
  -- apply at the nozzle so gimbaling produces torque
  part:applyForceAt(dx * thrust, dy * thrust, 0, -part:getHeight() / 2)
  part:emitFlame(math.min(1.2, thrust / (part:getEnginePower() * 8.5e3)), gimbal)
end
