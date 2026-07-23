-- v2026.07.21
-- RCS thrusters: rotation authority burning monopropellant (fuelType 1).
-- Mono supply is ship-wide (round 11): every mono tank aboard feeds every RCS
-- block, no fuel lines needed — see part:drainFuel rule.
-- 单组元(mono)全船共享: 船上任何 mono 油箱都给所有 RCS 供油, 无需管路。
function onLoad(part)
end

function onStage(part)
end

function onUpdate(part, dt)
  local turn = part:getTurn()
  if turn == 0 then return end
  local need = part:getRcsConsumption() * 10 * dt
  local got = part:drainFuel(1, need)
  if need > 0 and got / need > 0.2 then
    part:applyTorque(-turn * part:getRcsPower() * 60000)
    part:emitFlame(0.2, 90 * turn)
  end
end
