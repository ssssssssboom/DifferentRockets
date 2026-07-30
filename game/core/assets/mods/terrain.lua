-- v2026.07.30.1
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
--
-- Round 21 fix (item A2): start/end mapping. OUTSIDE the keypoint span the
-- base used to CLAMP to the endpoint height, so the special terrain only
-- started rising BEYOND the first keypoint — the region's start point had
-- no transition and the terrain effect was offset from the authored start.
-- Now the region edges (center -/+ range) act as virtual keypoints at the
-- NATURAL height: from the edge boundary the surface smoothsteps up (or
-- down) from natural terrain so each authored keypoint — peak or pit — is
-- reached exactly at its specified x.
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
--                          surface arc (120). Heights blend toward exact
--                          datum 0 at the pad angle with a smoothstep across
--                          this half-width, so the pad center is at EXACT
--                          datum (ground-to-center == nominal radius),
--                          overriding noise AND specialTerrains, and the rim
--                          joins tangentially. Round 25: back to 120 m (the
--                          round-24 12 km "wide valley" was REVERTED) — with
--                          Smearth's default-region amplitude now 0, the pad
--                          surroundings ARE the datum sphere, so no wide
--                          blend is needed; padFlatten only damps the last
--                          meters of residual noise/special-terrain overlap.
-- Meter->angle conversion needs planet radii; keep padRadii in sync with
-- the radius= values in planets.lua (planets missing from the table are
-- simply not flattened).
flattenPad = {
  enabled = true,
  angleDeg = 90.0,
  halfWidthM = 120.0,
}
-- ============================================================================
-- DEFAULT-REGION RELIEF (round 25, v2026.07.30.1): the base terrain is the
-- EXACT nominal sphere (surface-to-center == planet radius) plus optional
-- ZERO-MEAN noise. Noise amplitude per planet, in meters:
--   amplitude = 0   -> default regions are at precisely the planet radius
--   amplitude > 0   -> default regions get zero-mean noise +/- amplitude
-- Planets not listed fall back to (maxHeight - minHeight) / 2 from their
-- planets.lua terrain entry, preserving their relief span (recentered onto
-- datum). Authored bands (ranges) and specialTerrains are unaffected —
-- they are the configurable modifications stacked ON TOP of the datum base.
defaultNoiseAmplitude = {
  Smearth = 0.0,  -- launch planet: default surface == exact datum sphere
}
local padRadii = {
  Sun = 69634200.0, Smercury = 243970.0, Smenus = 605180.0,
  Smearth = 637100.0, Smoon = 173710.0, ["Smalley's Comet"] = 8000.0,
  Smars = 339600.0, Smupiter = 6991100.0, ["Ganymede Jr"] = 263410.0,
  ["Europa Jr"] = 156000.0, ["Io Jr"] = 182160.0, ["Callisto Jr"] = 241030.0,
  Smaturn = 6026800.0, ["Titan Jr"] = 257600.0,
  Smuranus = 2555900.0, Smeptune = 2476400.0,
}
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
-- The default below EXTENDS the built-in generator (round 19): per-range
-- height bands with smoothstep-blended edges, 7 octaves of wrapped value
-- noise, and a roughness shaping curve. If this file errors, the built-in
-- generator silently takes over (4 octaves, hard band edges — same family,
-- slightly different heights).
-- ============================================================================

-- Natural terrain height WITHOUT the pad flattening (used internally for the
-- pad-center reference height as well).
-- Round 19: height BANDS used to switch hard at their boundaries (the Smearth
-- mountain band 91-93 deg rose 4.8 km in a single sample = the "cliff/step"
-- players reported) and the 4-octave noise's finest feature (~6 deg) was much
-- WIDER than the 2 deg band, leaving the mesa top flat. Now: 7 octaves
-- (finest ~0.6 deg features) and every band edge smoothsteps over up to 1.2
-- deg (capped at half the band width) INSIDE the band (round 24: the blend
-- no longer straddles the edge — band influence is strictly confined to
-- [startAngle, endAngle]), so edges are ramps and narrow bands stay rugged.
-- Round 21: the single-nearest-boundary blend was replaced by continuous
-- per-band membership weights inside baseTerrainHeight below (see there).

local function baseTerrainHeight(planetName, angleRad)
  local info = planetInfo[planetName]
  if info == nil then return 0 end

  local deg = math.deg(angleRad) % 360

  -- 7 octaves of wrapped value noise, seeded per planet
  local seed = noise.hash(planetName)
  local sum, amp, norm = 0.0, 1.0, 0.0
  local baseFreq = math.floor(math.max(2.0, 6.0 + info.noise * 0.6) + 0.5)
  for oct = 0, 6 do
    local f = baseFreq * 2^oct
    sum = sum + amp * noise.value1(deg / 360.0 * f, f, seed + oct * 131.7)
    norm = norm + amp
    amp = amp * 0.5
  end
  local n01 = (sum / norm + 1) * 0.5          -- [0,1]
  local nSym = sum / norm                     -- [-1,1], zero mean (round 25)
  local rough = math.min(2.5, 0.25 + info.noise * 0.28)
  local shaped = n01 ^ rough

  -- micro-scale roughness (round 20): the octave stack's finest features are
  -- ~9 km wide, so ground inside the walking/driving view (45-200 m) was
  -- glass smooth (probe: RMS residual 0.0002 m at 8 m scale, 0.01 m at
  -- 128 m). Add three seam-free ABSOLUTE micro octaves (~256 m / ~64 m /
  -- ~24 m wavelengths, +/- 5 m / 1.5 m / 0.6 m) so the surface has visible
  -- texture; max slope stays ~5 deg, safe for landing and driving. Needs
  -- the planet radius to place the lattice on a meter scale; planets
  -- missing from padRadii skip this.
  local micro = 0.0
  local Rm = padRadii[planetName]
  if Rm ~= nil and Rm > 0 then
    local cf = 2 * math.pi * Rm
    local f1 = math.floor(cf / 256.0 + 0.5)
    local f2 = math.floor(cf / 64.0 + 0.5)
    local f3 = math.floor(cf / 24.0 + 0.5)
    micro = 5.0 * noise.value1(deg / 360.0 * f1, f1, seed + 511.3)
          + 1.5 * noise.value1(deg / 360.0 * f2, f2, seed + 917.9)
          + 0.6 * noise.value1(deg / 360.0 * f3, f3, seed + 1377.1)
  end

  local function bandHeight(lo, hi)
    local span = hi - lo
    if span <= 0.0001 then return lo end
    return lo + span * shaped
  end

  local function smooth01(x)
    if x <= 0 then return 0 end
    if x >= 1 then return 1 end
    return x * x * (3 - 2 * x)
  end

  -- Round 21 fix (spawn-pad crack): the old code blended across only the
  -- SINGLE nearest band boundary; exactly midway between two boundaries the
  -- "nearest" boundary flips and the two sides blend DIFFERENT band pairs —
  -- a ~65-80 m height discontinuity at the Smearth spawn site (90 deg).
  -- Round 24 fix (strict band confinement): the round-21 membership weight
  -- was smooth01(d/rim*0.5+0.5), which is 0.5 AT the boundary and >0 up to
  -- `rim` degrees OUTSIDE [startAngle, endAngle] — mountain/ocean bands
  -- leaked beyond their authored range. Now the weight is 0 AT and OUTSIDE
  -- every edge and rises to 1 over `rim` degrees INSIDE the band: every
  -- band's influence — including its transition zone — is strictly confined
  -- to [startAngle, endAngle]; outside, the height is exactly the default
  -- band (which itself joins continuously at the edge since w=0 there).
  local h, wsum = 0.0, 0.0
  for _, r in ipairs(info.ranges) do
    local s = r.startAngle % 360
    local e = r.endAngle % 360
    local rim = math.min(1.2, ((e - s) % 360) / 2)
    if rim > 1e-6 then
      local dS = (deg - s + 540) % 360 - 180  -- >0 inside (CCW of start edge)
      local dE = (e - deg + 540) % 360 - 180  -- >0 inside (CW of end edge)
      local w = smooth01(dS / rim) * smooth01(dE / rim)
      if w > 0 then
        h = h + w * bandHeight(r.minHeight, r.maxHeight)
        wsum = wsum + w
      end
    end
  end
  local wdef = 1.0 - wsum
  if wdef < 0 then wdef = 0 end

  -- Round 25 (v2026.07.30.1) — datum-centered default terrain: OUTSIDE every
  -- authored band the surface is the EXACT nominal sphere plus ZERO-MEAN
  -- noise with a configurable amplitude (defaultNoiseAmplitude table above;
  -- fallback (maxHeight-minHeight)/2 keeps unlisted planets' relief span,
  -- recentered onto datum):
  --     default height = amp * nSym
  -- amp == 0 (e.g. Smearth, the launch planet) => the default region is at
  -- PRECISELY the planet radius. The micro texture is noise too, so it is
  -- faded out across the same wdef weight when amp == 0 (kept inside bands
  -- where authored relief lives; the fade is smooth because wdef is).
  local amp = defaultNoiseAmplitude[planetName]
  if amp == nil then amp = (info.maxHeight - info.minHeight) / 2 end
  local microGate = (amp == 0) and 1 or 0
  return h + wdef * (amp * nSym) + micro * (1 - wdef * microGate)
end

-- Round 24/25 (v2026.07.30.1): the pad flattens toward EXACT datum — height
-- precisely 0 at the pad angle (ground distance to planet center == nominal
-- radius), overriding natural noise AND specialTerrains. The smoothstep
-- factor s is exactly 0 at the pad angle and exactly 1 at/ beyond the rim,
-- so heights blend back to whatever the unflattened terrain is across
-- halfWidthM (120 m) with a tangential join. On planets whose default-region
-- amplitude is 0 (Smearth) the surroundings are already the datum sphere —
-- this is only a last-meters guarantee, no wide valley.
local function padFlatten(planetName, angleRad, h)
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

  -- smoothstep blend: exactly 0 at pad center (datum), 1 at the rim
  local s = t * t * (3 - 2 * t)
  return h * s
end

function terrainHeight(planetName, angleRad)
  local h = baseTerrainHeight(planetName, angleRad)
  return padFlatten(planetName, angleRad, h)
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
-- Default: natural terrain (baseTerrainHeight) with specialTerrains keypoint
-- regions spliced in (each strictly confined to center +/- range), and the
-- launch-pad datum flatten applied LAST (round 24) so the spawn angle is
-- exactly height 0 regardless of noise or regions.
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
  local angleRad = x / R
  -- Round 24: use the UNFLATTENED natural height here; the pad flatten is
  -- applied once at the very end so it overrides specialTerrains too —
  -- the spawn angle's surface height is exactly datum (0) no matter what.
  local natural = baseTerrainHeight(info.name, angleRad)
  local h = natural
  local regions = specialTerrains[info.name]
  if regions ~= nil then
    for i, rg in ipairs(regions) do
      local d = math.abs(x - rg.center)
      -- STRICT confinement (round 24): a region — keypoints, ramps, jitter
      -- and the natural-blend rim — exists ONLY inside |x - center| < range;
      -- at and beyond the boundary the height is exactly natural terrain.
      if d < rg.range then
        -- keypoint base: smoothstep interpolation between neighbors. Round 21
        -- fix (item A2): instead of clamping to the endpoint heights outside
        -- the point span, the region edges (center -/+ range) are virtual
        -- keypoints at the NATURAL height, so the special terrain starts its
        -- smooth rise/descent exactly at the edge boundary and reaches the
        -- authored peak/pit exactly at its keypoint x.
        local pts = rg.points
        local leftEdge = rg.center - rg.range
        local rightEdge = rg.center + rg.range
        local base
        if x <= pts[1].x then
          -- left ramp: natural at leftEdge -> pts[1].h at pts[1].x
          local span = pts[1].x - leftEdge
          if span <= 0 then
            base = pts[1].h
          else
            local t = (x - leftEdge) / span
            t = t * t * (3 - 2 * t)
            base = natural + (pts[1].h - natural) * t
          end
        elseif x >= pts[#pts].x then
          -- right ramp: pts[#pts].h at pts[#pts].x -> natural at rightEdge
          local span = rightEdge - pts[#pts].x
          if span <= 0 then
            base = pts[#pts].h
          else
            local t = (x - pts[#pts].x) / span
            t = t * t * (3 - 2 * t)
            base = pts[#pts].h + (natural - pts[#pts].h) * t
          end
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
        h = natural + (special - natural) * w
        break
      end
    end
  end
  -- Round 24: pad flatten LAST (overrides specialTerrains as well), so the
  -- spawn angle's height above datum is exactly 0 — the ground under the
  -- freshly spawned ship is at precisely the nominal planet radius.
  return R + padFlatten(info.name, angleRad, h)
end
