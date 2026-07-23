-- v2026.07.21
-- Structural strut. Behavior: none — but its CONNECTIONS are extra-rigid
-- (round 9, per-part joint override): struts exist to stiffen the rocket, so
-- every weld touching a strut resolves to 35 Hz / 1.25 damping. Rule: when
-- two parts are welded, the override with the HIGHER frequencyHz wins (the
-- stiffer side rules the connection) and its dampingRatio comes along; any
-- key left nil falls back to physics.lua `joints` -> Java defaults.
-- angularDamping would apply to the strut's own body (not used here).
function onLoad(part)
  part:setJointParams{frequencyHz = 35.0, dampingRatio = 1.25}
end

function onStage(part)
end

function onUpdate(part, dt)
end
