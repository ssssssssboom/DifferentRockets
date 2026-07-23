-- v2026.07.21
-- ============================================================================
-- pod-1.lua — Command pod behavior (PLAYER-EDITABLE)
-- ============================================================================
-- The pod is the control-INPUT source of the ship: it reads player input and
-- commands the physical control effectors via the shared world state. It
-- applies NO torque itself — turn authority comes only from:
--   * engine gimbal (engines respond to part:getTurn() while thrusting), and
--   * RCS thrusters (rcs-1.lua burns monopropellant for torque).
-- The turn value is the PI heading-controller output (steering ring): see
-- physics.lua `steering = {kp, ki}` and the SandboxScreen steering ring.
-- The pod also carries a small electric reserve (battery-0 style tank added
-- by PartList for every pod type).
-- ============================================================================

function onLoad(part)
end

function onStage(part)
end

function onUpdate(part, dt)
  -- no built-in torque by design (owner requirement); see header
end
