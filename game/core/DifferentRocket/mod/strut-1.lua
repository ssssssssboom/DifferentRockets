-- v2026.07.26
-- Structural strut. Behavior: none — but its CONNECTIONS are extra-rigid
-- (round 9, per-part joint override): struts exist to stiffen the rocket, so
-- every weld touching a strut resolves to 24 Hz / 1.25 damping. Rule: when
-- two parts are welded, the override with the HIGHER frequencyHz wins (the
-- stiffer side rules the connection) and its dampingRatio comes along; any
-- key left nil falls back to physics.lua `joints` -> Java defaults.
-- v2026.07.26: 35.0 -> 24.0 Hz. 35 Hz exceeded the documented safe range
-- (physics.lua joints: 12..25, below half the 60 Hz physics rate) and
-- amplified ground-contact jitter into 700+ kN weld forces that snapped
-- strut connections on the pad. 24 Hz stays clearly stiffer than the 20 Hz
-- default while transmitting measurably lower peak forces.
-- angularDamping would apply to the strut's own body (not used here).
function onLoad(part)
  part:setJointParams{frequencyHz = 24.0, dampingRatio = 1.25}
end

function onStage(part)
end

function onUpdate(part, dt)
end
