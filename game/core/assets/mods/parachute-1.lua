-- v2026.07.21
-- Parachute: packed it has small drag; on stage activation inside an
-- atmosphere it deploys and gets a LARGE drag coefficient + reference area,
-- producing real deceleration (F = 0.5 * rho * v^2 * Cd * A per part).
-- Drag is a per-part parameter: part:setDrag(absoluteCd), part:setDragArea(m^2).
-- Use part:resetDrag()/part:resetDragArea() to restore PartList.xml defaults.
function onLoad(part)
  part:setDrag(1.2)                    -- packed canopy: small absolute Cd
  part:setDragArea(part:getWidth())    -- packed: only the casing area
end

function onStage(part)
  if part:getAtmoDensity() > 0.0005 then
    part:setDeployed(true)
    part:setDrag(8)        -- open canopy: huge absolute Cd
    part:setDragArea(36)   -- open canopy reference area, m^2
  end
end

function onUpdate(part, dt)
  -- repack if we somehow left the atmosphere
  if part:isDeployed() and part:getAtmoDensity() < 0.00001 then
    part:setDeployed(false)
    part:setDrag(1.2)
    part:setDragArea(part:getWidth())
  end
end
