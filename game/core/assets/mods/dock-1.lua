-- v2026.07.21
-- Docking port (ship side). Behavior: none — but its CONNECTIONS are
-- intentionally SOFT (round 9, per-part joint override): a docked joint has
-- a little give (8 Hz / 0.9 damping) so two docked ships don't fight each
-- other rigidly. Rule: when two parts are welded, the override with the
-- HIGHER frequencyHz wins (the stiffer side rules the connection) and its
-- dampingRatio comes along; any key left nil falls back to physics.lua
-- `joints` -> Java defaults.
function onLoad(part)
  part:setJointParams{frequencyHz = 8.0, dampingRatio = 0.9}
end

function onStage(part)
end

function onUpdate(part, dt)
end
