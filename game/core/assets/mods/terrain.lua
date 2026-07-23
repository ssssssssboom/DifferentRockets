-- v2026.07.24.1
-- ============================================================================
-- terrain.lua — planet terrain generation (PLAYER-EDITABLE)
-- ============================================================================
-- COLUMNAR TERRAIN (round 18). The surface is a ring of columns
-- blockWidthM meters wide; column i is the quadrilateral between junction
-- heights h[i], h[i+1] (top) and the same edge depthM meters down (bottom).
-- Junction heights come from surfaceHeight() below, so columns share their
-- junctions and are seamless by construction; the SAME data builds the
-- render mesh and the collision fixtures (what you see is what you hit).
--
-- terrainRender table (hot-reloaded):
--   blockWidthM      meters of surface arc per column (4). Smaller =
--                    smoother ground, more columns/fixtures.
--   depthM           collision/shell depth below the skin (32) — this depth,
--                    plus the parts' bullet-CCD, stops high-speed tunneling
--   rangeM           load/render window around the ship, +/- meters (100000)
--   physicsRangeM    collider window, +/- meters (10000). Box2D fixtures
--                    exist only inside this range; the mesh covers rangeM.
--   friction         column surface friction; ~1.0 stops post-spawn sideslip
--   restitution      bounce factor; 0 = no elasticity
--   topBrightness    surface-skin brightness multiplier on the planet crust
--                    color (planets.lua terrain color={r,g,b}), clamped (1.35)
--   bottomBrightness shell-bottom brightness multiplier (0.25)
--   bandVariation    deterministic per-column top-brightness jitter, +/-
--                    this fraction (0.06); 0 = perfectly smooth gradient
--   texture          nil = procedural gradient; or an asset name like
--                    "PlanetCrustSmearth.png" stretched across each column
--                    quad (player copy in assets/ wins over the built-in)
--   deepColor        {r,g,b} 0..1 for the solid block below the shell, down
--                    to the visible crust bottom (dark brown default)
terrainRender = {
  blockWidthM = 4.0,
  depthM = 32.0,
  rangeM = 100000.0,
  physicsRangeM = 10000.0,
  friction = 1.0,
  restitution = 0.0,
  topBrightness = 1.35,
  bottomBrightness = 0.25,
  bandVariation = 0.06,
  texture = nil,
  deepColor = { 0.23, 0.15, 0.09 },
}
-- ============================================================================
-- SPECIAL TERRAINS (round 18): per-planet list of hand-authored regions.
-- Inside |x - center| < range (arc meters) the surface base is INTERPOLATED
-- between the keypoints (smoothstep) instead of the natural band height,
-- plus `noise` meters of ABSOLUTE deterministic jitter on top (round 18 fix:
-- older versions multiplied the jitter by the FULL natural height, which
-- buried the region whenever the natural terrain was mountainous). The
-- outer `blend` fraction (default 0.2) of the range smoothsteps back to
-- natural terrain — widen it when the region height differs a lot from the
-- surrounding terrain, so the rim is a ramp, not a wall.
-- x/h in points are arc meters / meters above the nominal radius; list them
-- left to right.
specialTerrains = {
  Smearth = {
    -- coastal plain: a level +6 m shelf rising from a shallow-sea area
    -- (arc 720-740 km is the flattest low terrain on Smearth); the wide
    -- 50% blend turns the ~1.3 km height difference into a long ramp
    { center = 730000, range = 10000, blend = 0.5, noise = 2.0,
      points = { {x = 720000, h = 6}, {x = 740000, h = 6} } },
    -- ridge on the equatorial highlands: peaks ~800 m above the natural
    -- mountains around it (arc 52-64 km), endpoints match the local natural
    -- height so the rim blend is small
    { center = 58000, range = 8000, noise = 40.0,
      points = { {x = 50000, h = 2300}, {x = 56000, h = 2900},
                 {x = 60000, h = 3200}, {x = 66000, h = 2350} } },
  },
}
-- ============================================================================
-- Launch-pad flattening (round 13 item 1d): ships always spawn at 90 deg
-- (top of the planet, see padAngle in the launch code). A sloped pad makes
-- the freshly spawned ship sideslip no matter how high the friction is, so
-- the height function is blended toward a level pad around the spawn angle:
--   flattenPad.enabled     master switch (true)
--   flattenPad.angleDeg    pad center angle in degrees (90 = spawn site)
--   flattenPad.halfWidthM  half-width of the leveled area in meters of
--                          surface arc (24). Heights blend back to the
--                          natural terrain with a smoothstep across this
--                          half-width, so pad center is perfectly level and
--                          the rim joins the slopes tangentially.
-- Meter->angle conversion needs planet radii; keep padRadii in sync with
-- the radius= values in planets.lua (planets missing from the table are
-- simply not flattened).
flattenPad = {
  enabled = true,
  angleDeg = 90.0,
  halfWidthM = 24.0,
}
local padRadii = {
  Sun = 69634200.0, Smercury = 243970.0, Smenus = 605180.0,
  Smearth = 637100.0, Smoon = 173710.0, ["Smalley's Comet"] = 8000.0,
  Smars = 339600.0, Smupiter = 6991100.0, ["Ganymede Jr"] = 263410.0,
  ["Europa Jr"] = 156000.0, ["Io Jr"] = 182160.0, ["Callisto Jr"] = 241030.0,
  Smaturn = 6026800.0, ["Titan Jr"] = 257600.0,
  Smuranus = 2555900.0, Smeptune = 2476400.0,
}
local padHeightCache = {} -- planetName -> natural height at the pad center
-- ============================================================================
-- Called by the chunk generator for BOTH the visible crust and the collision
-- heightfield (one function, always in sync). Signature:
--
--   terrainHeight(planetName, angleRad) -> heightMeters
--       planetName  e.g. "Smearth"
--       angleRad    surface angle in radians (world frame, 0 = +x axis)
--       returns     terrain height in meters above/below the nominal radius
--
-- Available data + helpers:
--   planetInfo[planetName] = { minHeight, maxHeight, noise,
--                              ranges = { {startAngle, endAngle,
--                                          minHeight, maxHeight}, ... } }
--       (values come from that planet's definePlanet{...} in planets.lua)
--   noise.value1(x, period, seed)  seam-free 1D value noise in [-1,1];
--                                  the lattice wraps at `period`, so x and
--                                  x+period join seamlessly around a planet
--   noise.value2(x, y, seed)       2D value noise in [-1,1]
--   noise.hash(string)             Java-compatible string hash — use it to
--                                  derive a per-planet seed
--
-- Determinism: same planet + same angle must ALWAYS return the same height
-- (collision and visuals are generated at different times). Use noise.* with
-- fixed seeds, never math.random().
--
-- The default below reproduces the built-in generator: per-range height
-- bands, 4 octaves of wrapped value noise, and a roughness shaping curve.
-- If this file errors, the built-in generator silently takes over.
-- ============================================================================

-- Natural terrain height WITHOUT the pad flattening (used internally for the
-- pad-center reference height as well).
local function baseTerrainHeight(planetName, angleRad)
  local info = planetInfo[planetName]
  if info == nil then return 0 end

  local deg = math.deg(angleRad) % 360

  -- height band: the first matching range wins
  local lo, hi = info.minHeight, info.maxHeight
  for _, r in ipairs(info.ranges) do
    local s = r.startAngle % 360
    local e = r.endAngle % 360
    local inside
    if s <= e then inside = (deg >= s and deg <= e)
    else inside = (deg >= s or deg <= e) end
    if inside then lo, hi = r.minHeight, r.maxHeight break end
  end

  local span = hi - lo
  if span <= 0.0001 then return lo end

  -- 4 octaves of wrapped value noise, seeded per planet
  local seed = noise.hash(planetName)
  local sum, amp, norm = 0.0, 1.0, 0.0
  local baseFreq = math.floor(math.max(2.0, 6.0 + info.noise * 0.6) + 0.5)
  for oct = 0, 3 do
    local f = baseFreq * 2^oct
    sum = sum + amp * noise.value1(deg / 360.0 * f, f, seed + oct * 131.7)
    norm = norm + amp
    amp = amp * 0.5
  end
  local n01 = (sum / norm + 1) * 0.5          -- [0,1]
  local rough = math.min(2.5, 0.25 + info.noise * 0.28)
  return lo + span * (n01 ^ rough)
end

function terrainHeight(planetName, angleRad)
  local h = baseTerrainHeight(planetName, angleRad)
  if not flattenPad.enabled then return h end
  local radius = padRadii[planetName]
  if radius == nil or radius <= 0 then return h end

  -- angular distance to the pad center, wrapped to [-pi, pi]
  local padA = math.rad(flattenPad.angleDeg)
  local dA = (angleRad - padA) % (2 * math.pi)
  if dA > math.pi then dA = dA - 2 * math.pi end
  local halfA = flattenPad.halfWidthM / radius
  if halfA <= 0 then return h end
  local t = math.abs(dA) / halfA
  if t >= 1 then return h end

  -- natural height at the pad center (cached; deterministic anyway)
  local cacheKey = planetName .. "@" .. tostring(flattenPad.angleDeg)
  local hc = padHeightCache[cacheKey]
  if hc == nil then
    hc = baseTerrainHeight(planetName, padA)
    padHeightCache[cacheKey] = hc
  end
  -- smoothstep blend: 0 at pad center (level), 1 at the rim (natural)
  local s = t * t * (3 - 2 * t)
  return hc + (h - hc) * s
end

-- ============================================================================
-- Columnar surface function (round 18) — called by the terrain system once
-- per NEW junction (results are cached Java-side; this must stay a
-- deterministic pure function of (info, x), never use math.random).
--
--   surfaceHeight(info, x) -> absolute radius in meters
--       info    the planetInfo entry for this planet (injected by the game:
--               { name, radius, minHeight, maxHeight, noise, ranges })
--       x       arc position in meters along the surface, from angle 0
--
-- Default: natural terrain (terrainHeight above, including pad flattening)
-- with specialTerrains keypoint regions spliced in.
-- ============================================================================

-- Deterministic absolute jitter for special regions, in [-1,1]: 3 octaves of
-- the same seam-free value noise, lattice period wrapped to the planet
-- circumference so x and x + circumference join seamlessly.
local function regionJitter(info, x, seed)
  local R = info.radius
  local s = noise.hash(info.name) + seed
  local sum, amp, norm = 0.0, 1.0, 0.0
  for oct = 0, 2 do
    local f = 2^oct
    local period = (2 * math.pi * R / 1000.0) * f -- ~1 km base features
    sum = sum + amp * noise.value1(x / 1000.0 * f, period, s + oct * 57.3)
    norm = norm + amp
    amp = amp * 0.5
  end
  return sum / norm
end

function surfaceHeight(info, x)
  local R = info.radius
  local natural = terrainHeight(info.name, x / R)
  local regions = specialTerrains[info.name]
  if regions ~= nil then
    for i, rg in ipairs(regions) do
      local d = math.abs(x - rg.center)
      if d < rg.range then
        -- keypoint base: smoothstep interpolation between neighbors,
        -- clamped to the end heights outside the point span
        local pts = rg.points
        local base
        if x <= pts[1].x then
          base = pts[1].h
        elseif x >= pts[#pts].x then
          base = pts[#pts].h
        else
          for k = 1, #pts - 1 do
            local a, b = pts[k], pts[k + 1]
            if x >= a.x and x <= b.x then
              local t = (x - a.x) / (b.x - a.x)
              t = t * t * (3 - 2 * t)
              base = a.h + (b.h - a.h) * t
              break
            end
          end
        end
        -- ABSOLUTE jitter in meters (round 18 fix — was noise * natural,
        -- which drowned authored regions in mountainous natural terrain)
        local special = base + (rg.noise or 0) * regionJitter(info, x, i * 7919)
        -- blend back to natural over the outer `blend` fraction of the range
        local edge = d / rg.range
        local blendFrac = rg.blend or 0.2
        local edge0 = 1.0 - blendFrac
        local w = 1.0
        if edge > edge0 then
          local t = (edge - edge0) / blendFrac
          w = 1.0 - t * t * (3 - 2 * t)
        end
        return R + natural + (special - natural) * w
      end
    end
  end
  return R + natural
end
