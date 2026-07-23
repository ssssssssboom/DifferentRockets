-- v2026.07.21
-- Generic fuel tank: stores fuel, participates in the ship fuel network,
-- and slowly equalizes with other tanks of the same fuel type.
function onLoad(part)
end

function onStage(part)
end

function onUpdate(part, dt)
  local ft = part:getFuelType()
  if ft < 0 then return end
  local cap = part:getFuelMax()
  local totalCap = part:getFuelCapacity(ft)
  if cap <= 0 or totalCap <= 0 then return end
  local myShare = part:getFuel() / cap
  local netShare = part:getFuelTotal(ft) / totalCap
  local diff = myShare - netShare
  if math.abs(diff) > 0.002 then
    -- move a fraction of the imbalance through fuel lines each frame
    part:transferFuel(ft, diff * cap * math.min(1, dt * 2))
  end
end
