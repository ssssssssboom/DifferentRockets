-- v2026.07.27
-- Wheel (round 27 rewrite). Physics: the part body is the AXLE (small circle,
-- collides with parts/terrain); the TIRE is a second body on a motorized
-- revolute joint that only collides with terrain and other tires (never with
-- parts). Until staged the wheel is LOCKED (motor holds it rigid); once
-- staged it drives from player input:
--   * turn buttons < / > (SteeringIO.buttonTurn): full torque while held;
--   * nav ring (ring active): torque proportional to the angle between the
--     target heading and the ship's heading — linear below 15 deg, full
--     torque at >= 15 deg;
--   * no input: free-spin (unlocked, no drive torque).
-- Non-active ships see "no input" via part:getSteering() (round 27 input
-- shielding), so a dropped wheel just free-spins.
-- Tunables live in PartList.xml <Wheel axleRadius maxTorque maxSpeed
-- lockTorque/>; with the defaults (tire radius = width/2, maxSpeed 10 rad/s)
-- the acceptance config (pod+strut+wheel+wheel, flat ground) tops out at
-- 20 m/s.

local staged = false

function onLoad(part)
  -- re-arm after a save/load roundtrip (same rule as engines, round 26 B4)
  local grp = part:getGroup()
  staged = grp > 0 and part:getStage() >= grp
end

function onStage(part)
  staged = true
end

function onUpdate(part, dt)
  if not staged then
    part:setWheelLocked(true)
    return
  end
  part:setWheelLocked(false)

  local s = part:getSteering()
  -- BUTTON: full torque in the button's direction while held
  if s.buttonTurn ~= 0 then
    part:setWheelDrive(s.buttonTurn)
    return
  end
  -- RING: torque linear in heading error (<15 deg), full torque beyond
  if s.active then
    local a = part:getShipHeading()
    local px, py = -math.sin(a), math.cos(a)
    local tx, ty = -math.sin(s.targetRad), math.cos(s.targetRad)
    local err = math.atan2(px * ty - py * tx, px * tx + py * ty)
    local frac = err / math.rad(15)
    if frac > 1 then frac = 1 elseif frac < -1 then frac = -1 end
    part:setWheelDrive(frac)
    return
  end
  -- no input: free-spin
  part:setWheelDrive(0)
end
