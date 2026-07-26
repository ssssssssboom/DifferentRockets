-- v2026.07.27
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

-- SUPERSEDED (round 12): the ship-level PI steering controller was removed.
-- The turn command now comes from SteeringIO (ring/buttons), and the engine
-- gimbal control law lives in mod/control.lua (controlLaw). This table is
-- kept only so old player scripts reading it don't break — nothing reads it.
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
--                  Round 12: raised 0.08 -> 0.6 because the control.lua law is
--                  pure proportional (gimbal = clamped heading error, no
--                  derivative term), so mechanical damping is the only legal
--                  stabilizer. Round 13: 0.6 -> 2.5. Full-trajectory telemetry
--                  (HDG/ANGVEL in --smoke) showed the ship does NOT settle
--                  exponentially under this law: it enters a +-3..4 deg limit
--                  cycle around the target whose amplitude is set by the
--                  nonlinear plant (fuel burn shifts CoM/inertia), NOT by
--                  damping, and ~11 s into the turn-hold the growing plant
--                  leaves the P-law's region of attraction and the stack
--                  gravity-turns over (HDG runs past -90 deg) at ANY damping
--                  0.6..3.0. What damping DOES control is the phase and the
--                  width of the bounded plateau around the target. Measured
--                  plateaus (per-second |err| around the step-600 check):
--                  2.0 -> passes only at t=10s (neighbours fail); 3.0 -> t=9s
--                  margin 0.1 deg; 2.5 -> every sample t=6..10s within 1.85
--                  deg of the target = ~4 s of phase tolerance, the most
--                  robust window available. Cost: sustained turn rate scales
--                  ~1/damping, so coarse slews feel heavier than the PID era.
-- Round 23 (v2026.07.27): unified to 28 Hz / 1.0 / 1.0 for EVERY connection,
-- matching joints.lua's DEFAULT_* constants; the per-part overrides shipped
-- in earlier rounds (strut-1, dock-1, port-1) were removed.
joints = { frequencyHz = 28.0, dampingRatio = 1.0, angularDamping = 1.0 }

-- Per-part override (round 9): a part's own Lua script may call
--   part:setJointParams{frequencyHz=…, dampingRatio=…, angularDamping=…}
-- in onLoad to override ANY of these keys for itself (nil key -> this table
-- -> Java defaults). When two parts are welded, the override with the HIGHER
-- frequencyHz wins — the stiffer side rules the connection — and its
-- dampingRatio comes along. angularDamping applies to the part's own body.
-- (Round 23: no shipped part uses this anymore — all connections use the
-- unified 28 / 1.0 / 1.0 above.)
--
-- Per-connection rules (round 11): mod/joints.lua decides the final
-- frequencyHz / dampingRatio / angularDamping / breakForce of EVERY weld and
-- sees both parts and both attach points. Its default implementation folds
-- the per-part overrides above with the stiffer-wins rule; edit joints.lua to
-- make connections depend on part types, attach-point edges, or break forces.

-- Occlusion-aware drag (round 11). Atmospheric drag is per part:
--   F = 0.5 * rho * v^2 * Cd * area * exposure
-- Cd is the part's own override (part:setDragCd) or 0.75 + its PartList.xml
-- `drag` attribute; `exposure` (0..1) is computed by the game: 8 sample rays
-- are cast upwind across each part's silhouette and a part fully hidden
-- behind shipmates (e.g. a tank inside a fairing stack) feels almost no drag,
-- while a part on the windward edge keeps it all. A nosecone ahead therefore
-- genuinely shields the parts behind it. Exposure refreshes ~15x/sec and
-- whenever the airflow direction swings more than ~8 degrees. Scripts can
-- read the current value via part:getDragExposure().
--
-- DRAG SCALE (round 21, item A1): drag force is F = k above, and k scales
-- linearly with rho — the ONLY part of the drag law routed through this file
-- is atmosphereDensity below (GameWorld.java hardcodes 0.5 * rho * v^2 * Cd *
-- area * exposure and calls densityAt -> physics.lua since round 14). To cut
-- ALL aerodynamic drag to 1/10 as requested, atmosphereDensity returns 0.1x
-- the physical density (dragScale below). Buoyancy is unaffected (it uses
-- water density), but part scripts reading part:getAtmoDensity() see the
-- scaled value too.
local dragScale = 0.1

-- SUPERSEDED (round 12): the per-engine gimbal PID actuator was replaced by
-- the shared control law in mod/control.lua — gimbal deflection now EQUALS
-- the clamped heading error, no PID anywhere in the control path. This table
-- is kept only for old player scripts; engine scripts no longer read it.
gimbal = { kp = 8.0, ki = 0.1, kd = 0.6, maxRateDeg = 90.0 }

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
  -- 1.225 kg/m^3 at pressure 1.0; dragScale (round 21) cuts all drag to 1/10
  return 1.225 * e.surfacePressure * math.exp(-math.max(altitude, 0) / e.scaleHeight) * dragScale
end
