-- ============================================================================
-- physics.lua — physics laws (PLAYER-EDITABLE)
-- ============================================================================
-- Replaces the built-in physics. All functions are optional: delete one and
-- the built-in law takes over for that one. On any runtime error the whole
-- file is disabled (built-in laws) until you fix + save (hot-reloaded).
--
--   gravityAccel(x, y, timeSec) -> ax, ay
--       Universe coordinates (meters), returns acceleration (m/s^2).
--       Default: Newtonian sum of GM/r^2 over every planet. Replace with
--       n-body propagation, relativistic corrections, J2 oblateness, ...
--
--   atmosphereDensity(planetName, altitude) -> kg/m^3
--       Default: exponential model 1.225 * P0 * exp(-h / scaleHeight).
--
--   steering = { kp = .., ki = .., kd = .. }
--       PID gains for the steering-ring heading controller. kp/ki act on the
--       heading error (proportional / integral, anti-windup |ki*int| <= 1),
--       kd acts on the ship's angular velocity (rate damping — it brakes the
--       rotation so turns don't overshoot; raise it if the nose oscillates,
--       lower it if steering feels sluggish). The controller output is the
--       -1..1 turn command that gimbaled engines and RCS respond to.
--
-- Available globals:
--   world:planetCount()                  number of bodies
--   world:planetName(i)                  name of body i (0-based)
--   world:planetX(i), world:planetY(i)   universe position (meters)
--   world:planetMu(i)                    gravitational parameter GM (m^3/s^2)
--   world:planetRadius(i)                nominal radius (meters)
--   planetEnv[name] = { atmoHeight, surfacePressure, scaleHeight }
--
-- NOTE: gravityAccel runs per part per physics tick — keep it cheap. At
-- current ship sizes (tens of parts) a loop like the default is fine.
-- ============================================================================

steering = { kp = 1.8, ki = 0.5, kd = 1.2 }

-- Weld-joint (part connection) tuning. The ship's parts are held together by
-- spring-damper weld joints; these three values control how rigid the rocket
-- feels. All keys are optional — delete one and the default takes over.
--   frequencyHz    spring stiffness of every part-to-part weld.
--                  Higher = stiffer connection (less sag/flex), but too high
--                  can make the solver jitter.  Sane range 12..25 (must stay
--                  below half the 60 Hz physics rate).  (was 12.0)
--   dampingRatio   weld damping: 1.0 = critically damped (no elastic
--                  oscillation), >1 = over-damped.  Below ~0.9 the rocket
--                  visibly wobbles after burns/turns.  (was 1.05)
--   angularDamping per-part rotational damping (Box2D body property, 0=none).
--                  Small values (0.05..0.2) kill residual spin oscillation
--                  without affecting normal turning authority.
joints = { frequencyHz = 20.0, dampingRatio = 1.1, angularDamping = 0.08 }

function gravityAccel(x, y, timeSec)
  local ax, ay = 0.0, 0.0
  for i = 0, world:planetCount() - 1 do
    local mu = world:planetMu(i)
    if mu > 0 then
      local dx = world:planetX(i) - x
      local dy = world:planetY(i) - y
      local r2 = dx * dx + dy * dy
      local r = math.sqrt(r2)
      local minR = world:planetRadius(i) * 0.5
      if r < minR then r = minR end
      local a = mu / (r2 * r)      -- GM/r^2 times unit vector (dx/r, dy/r)
      ax = ax + a * dx
      ay = ay + a * dy
    end
  end
  return ax, ay
end

function atmosphereDensity(planetName, altitude)
  local e = planetEnv[planetName]
  if e == nil or e.atmoHeight <= 0 or e.surfacePressure <= 0 then return 0 end
  if altitude > e.atmoHeight or altitude < -e.scaleHeight * 3 then return 0 end
  -- 1.225 kg/m^3 at pressure 1.0
  return 1.225 * e.surfacePressure * math.exp(-math.max(altitude, 0) / e.scaleHeight)
end
