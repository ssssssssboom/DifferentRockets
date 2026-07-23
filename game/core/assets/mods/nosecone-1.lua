-- v2026.07.21
-- Nosecone: structural part with aerodynamic benefit.
-- Drag model: each part's drag force = 0.5 * rho * v^2 * Cd * A.
-- A part's default Cd = 0.75 (baseline) + its PartList.xml `drag` attribute.
-- The nosecone declares drag="-1.0", so its Cd = -0.25 -> it SUBTRACTS drag
-- from the ship total (aerodynamic cover). Lua can override any part's drag
-- at runtime with part:setDrag(absoluteCd) / part:setDragArea(m^2);
-- part:resetDrag() / part:resetDragArea() restore the XML defaults.
function onLoad(part)
end

function onStage(part)
end

function onUpdate(part, dt)
end
