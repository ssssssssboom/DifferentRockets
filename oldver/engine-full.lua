-- DifferentRockets generic engine behavior.
-- Thrust vector = power * throttle along the nozzle, gimbal follows turn input,
-- fuel drained through the ship's fuel network. SRBs (fuelType 3) burn their
-- internal tank at full power with no throttle/gimbal. Ion (fuelType 2) drains
-- the electric network.
--
-- 燃油供给规则 (part:drainFuel): 发动机只从通过 fuelLine 连接点与它相连
-- 的油箱(以及直接贴在它身上的油箱)组成的燃料管网中取油; 被无 fuelLine
-- 的零件(指令舱/分离器/电池等)隔开的油箱不会给它供油。电力(type 2)
-- 全船共享, 固体燃料(type 3)只烧自身。
-- Fuel supply rule: only tanks reachable through fuelLine attach points
-- (plus tanks bolted directly onto this part) feed this engine; tanks cut
-- off by non-fuelLine parts (pod/detacher/battery) stay isolated.
local staged = false

function onLoad(part)
  -- re-arm after a save/load roundtrip: the stage list persists, but this
  -- Lua upvalue does not — derive it from the ship's stage counter instead
  staged = part:getStage() > 0
end

function onStage(part)
  staged = true
end

local function drainOwn(part, dt)
  local need = part:getEngineConsumption() * dt
  local have = part:getFuel()
  local use = math.min(need, have)
  part:setFuel(have - use)
  if need > 0 then return use / need else return 0 end
end

function onUpdate(part, dt)
  if not staged then return end
  local ft = part:getEngineFuelType()
  local th = part:getThrottle()
  local thrust = 0
  local gimbal = 0

  if ft == 3 then
    -- solid rocket booster
    local frac = drainOwn(part, dt)
    thrust = part:getEnginePower() * 1e5 * frac
  else
    if th <= 0 then return end
    local te = th
    if part:isThrottleExponential() then te = th * th end
    local need = part:getEngineConsumption() * te * dt
    local got = part:drainFuel(ft, need)
    local frac = need > 0 and (got / need) or 0
    thrust = part:getEnginePower() * 1e5 * te * frac
    gimbal = part:getTurn() * part:getEngineTurn()
  end

  if thrust <= 0 then return end
  local ang = part:getAngle() + math.rad(gimbal)
  local dx = -math.sin(ang)
  local dy = math.cos(ang)
  -- apply at the nozzle so gimbaling produces torque
  part:applyForceAt(dx * thrust, dy * thrust, 0, -part:getHeight() / 2)
  part:emitFlame(math.min(1.2, thrust / (part:getEnginePower() * 1e5)), gimbal)
end
