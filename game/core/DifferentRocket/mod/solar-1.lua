-- v2026.07.21
-- Solar panel: recharges the electric network while in sunlight.
function onLoad(part)
end

function onStage(part)
end

function onUpdate(part, dt)
  if part:isInSunlight() then
    part:addFuel(2, part:getSolarChargeRate() * dt)
  end
end
