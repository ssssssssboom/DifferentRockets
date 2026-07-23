-- v2026.07.21
-- Docking connector (port side). Behavior: none — connections intentionally
-- SOFT (round 9, per-part joint override), matching dock-1: 8 Hz / 0.9
-- damping gives docked assemblies a little flex. Rule: when two parts are
-- welded, the override with the HIGHER frequencyHz wins (the stiffer side
-- rules the connection) and its dampingRatio comes along; any key left nil
-- falls back to physics.lua `joints` -> Java defaults.
function onLoad(part)
  part:setJointParams{frequencyHz = 8.0, dampingRatio = 0.9}
end

function onStage(part)
end

function onUpdate(part, dt)
end
