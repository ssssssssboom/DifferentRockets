package com.differentrockets.game;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.physics.box2d.World;
import com.differentrockets.util.Json;
import com.differentrockets.util.Vec2d;

import java.util.ArrayList;
import java.util.List;

/**
 * The simulation: Box2D world (zero ambient gravity; N-body gravity applied
 * per part), planets on Kepler rails, all launched ships persisting, chunked
 * terrain, floating origin anchored to the active ship.
 */
public class GameWorld {

    public static final float PHYS_DT = 1f / 60f;
    public static final int VEL_ITER = 8, POS_ITER = 3;
    public static final double RAILS_DISTANCE = 20000.0; // beyond this ships go on rails

    /**
     * Time-warp ladder (round 14 item 7, round 19 rewrite): up to
     * PHYS_WARP_MAX the Box2D world is stepped normally (physics warp);
     * beyond it the ACTIVE ship follows a PRE-COMPUTED trajectory (see
     * superWarp — the orbit predictor's own propagator sampled as a pure
     * function of time, no per-frame integration), engines locked, and
     * every other ship rides chunked rails — the only way 25x..250000x can
     * run in real time.
     */
    public static final int PHYS_WARP_MAX = 4;
    public static final int[] WARP_LEVELS = {1, 2, 4, 25, 100, 1000, 7500, 50000, 250000};
    /** Chunk length (s) of a single rails integration during super-warp. */
    private static final double WARP_RAILS_CHUNK = 2000.0;

    /**
     * Adaptive rails chunk (round 15/19): the dynamical-time rule of the
     * orbit predictor (0.004·tau of the ship's nearest body), capped to
     * [simDt/1000, WARP_RAILS_CHUNK]. Round 19: only NON-active ships
     * integrate per chunk now (warpRailsShip, gravityFast built-in law);
     * the active ship samples its precomputed trajectory instead.
     */
    private double warpChunk(double simDt) {
        double h = WARP_RAILS_CHUNK;
        if (active != null) {
            Vec2d c = active.getUniversePos();
            Planet np = nearestPlanetTo(c.x, c.y);
            if (np != null && np.mu() > 0) {
                double br = Math.hypot(c.x - np.pos.x, c.y - np.pos.y);
                double tau = Math.sqrt(br * br * br / np.mu());
                h = Math.max(0.25, Math.min(WARP_RAILS_CHUNK, 0.004 * tau));
            }
        }
        return Math.max(h, simDt / 1000.0);
    }

    public final World boxWorld;
    public Planet sun;
    public final List<Planet> planets = new ArrayList<>();
    public final List<Ship> ships = new ArrayList<>();
    public Ship active;
    public final TerrainSystem terrain;

    /** universe position of the physics frame origin (active ship COM) */
    public final Vec2d origin = new Vec2d();
    /**
     * Universe velocity of the physics frame itself. Bodies only carry velocity
     * relative to this frame, keeping them far below Box2D's max-translation
     * clamp (~120 m/s at 60 Hz). Universe velocity = frameVel + originVel + bodyVel.
     */
    public final Vec2d frameVel = new Vec2d();
    public double time;

    // player input state (read by Lua scripts)
    public double inputTurn;      // -1..1 (set by the steering controller)
    public double inputThrottle;  // 0..1
    public int warp = 1;          // 1, 2, 4
    public boolean paused;

    // steering ring (item 4): PI heading controller
    /** Target ship heading in body-angle convention (radians, CCW from "up"). */
    public double targetHeading;
    /** Latest turn command (-1..1) from SteeringIO that gimbaled engines and RCS respond to. */
    public double turnCommand;
    private boolean steerPrimed;

    private double saveTimer;
    private final Vec2d tmpG = new Vec2d();
    private final Vector2 tmpV = new Vector2();

    // ---- super-warp trajectory following (round 19) ----
    // The ACTIVE ship's future path is PRE-COMPUTED once (OrbitPredictor's
    // own velocity-Verlet propagator, velocities included) and then SAMPLED
    // per frame — no per-chunk integration on the hot path, no accumulated
    // integration drift; the flown path is a pure function of time between
    // recomputes and is by construction the map prediction line.
    private static final int WT_MAX = 40000; // 5 × 40000 × 8 B ≈ 1.6 MB
    private final double[] wtX = new double[WT_MAX];
    private final double[] wtY = new double[WT_MAX];
    private final double[] wtVX = new double[WT_MAX];
    private final double[] wtVY = new double[WT_MAX];
    private final double[] wtT = new double[WT_MAX]; // absolute universe time
    private int wtCount;          // valid points (0 = no trajectory)
    private boolean wtImpact;     // trajectory ends at a surface
    private int wtParts = -1;     // active.parts.size() at compute time
    private int wtWarp = -1;      // warp level at compute time
    private int wtHint;           // monotone sampling hint (times only grow)
    private final OrbitPredictor warpPred = new OrbitPredictor();

    public GameWorld() {
        // round 14 (lone-pod sinking): doSleep MUST be off. A sleeping body
        // ignores forces and is not ejected when the (teleported/kinematic)
        // terrain blocks move into it — the ground slowly swallowed parked
        // ships, most visibly a lone pod with nothing else keeping it awake.
        boxWorld = new World(new Vector2(0, 0), false);
        sun = PlanetDefs.load(); // player-editable Lua defs; SmolarSystem.xml fallback
        sun.flatten(planets);
        sun.updateRails(0);
        terrain = new TerrainSystem(this);
        PhysicsScript.ensureBound(this);
        TerrainScript.ensureBound(this);
    }

    public void setTime(double t) {
        time = t;
        sun.updateRails(t);
    }

    // ---------------------------------------------------------------- environment

    public Vec2d gravityAt(double x, double y) {
        // player-editable physics law (mod/physics.lua); falls back to built-in
        if (PhysicsScript.gravity(x, y, time, tmpG)) return tmpG;
        double gx = 0, gy = 0;
        for (Planet p : planets) {
            if (p.mu() <= 0) continue;
            double dx = p.pos.x - x, dy = p.pos.y - y;
            double r2 = dx * dx + dy * dy;
            double r = Math.sqrt(r2);
            if (r < p.radius * 0.5) r = p.radius * 0.5;
            double a = p.mu() / (r2 * r); // mu/r^2 * (dx/r)
            gx += a * dx;
            gy += a * dy;
        }
        tmpG.set(gx, gy);
        return tmpG;
    }

    private final Vec2d tmpGF = new Vec2d();

    /**
     * Built-in ΣGM/r² gravity WITHOUT the Lua hook (round 18): the super-warp
     * integrator evaluates gravity up to ~1000x per frame; routing that
     * through physics.lua (an interpreted per-planet loop) was the real
     * chunk-count limiter. Identical to the default law; a player-modded
     * gravity in physics.lua applies at physical warp only.
     */
    public Vec2d gravityFast(double x, double y) {
        double gx = 0, gy = 0;
        for (Planet p : planets) {
            if (p.mu() <= 0) continue;
            double dx = p.pos.x - x, dy = p.pos.y - y;
            double r2 = dx * dx + dy * dy;
            double r = Math.sqrt(r2);
            double rmin = p.radius * 0.5;
            if (r < rmin) r = rmin; // mirror gravityAt
            double a = p.mu() / (r2 * r);
            gx += a * dx;
            gy += a * dy;
        }
        tmpGF.set(gx, gy);
        return tmpGF;
    }

    /** Nearest planet by surface distance to the active ship. */
    public Planet currentPlanet() {
        if (active == null) return planets.isEmpty() ? null : planets.get(0);
        Vec2d sp = active.getUniversePos();
        Planet best = null;
        double bestAlt = Double.MAX_VALUE;
        for (Planet p : planets) {
            double d = Math.sqrt(p.pos.dist2(sp)) - p.radius;
            if (d < bestAlt) { bestAlt = d; best = p; }
        }
        return best;
    }

    public Planet nearestPlanetTo(double x, double y) {
        Planet best = null;
        double bestAlt = Double.MAX_VALUE;
        for (Planet p : planets) {
            double dx = x - p.pos.x, dy = y - p.pos.y;
            double d = Math.sqrt(dx * dx + dy * dy) - p.radius;
            if (d < bestAlt) { bestAlt = d; best = p; }
        }
        return best;
    }

    public double altitudeAt(double x, double y) {
        Planet p = nearestPlanetTo(x, y);
        if (p == null) return 0;
        double dx = x - p.pos.x, dy = y - p.pos.y;
        double ang = Math.atan2(dy, dx);
        return Math.sqrt(dx * dx + dy * dy) - p.radius - p.heightAt(ang);
    }

    public double densityAt(double x, double y) {
        Planet p = nearestPlanetTo(x, y);
        if (p == null) return 0;
        double alt = altitudeAt(x, y);
        // player-editable law (mod/physics.lua); falls back to built-in model
        double d = PhysicsScript.density(p.name, alt);
        return Double.isNaN(d) ? p.densityAt(alt) : d;
    }

    public double pressureAt(double x, double y) {
        Planet p = nearestPlanetTo(x, y);
        if (p == null) return 0;
        return p.pressureAt(altitudeAt(x, y));
    }

    public boolean isInWater(double x, double y) {
        Planet p = nearestPlanetTo(x, y);
        if (p == null || p.waterDensity <= 0) return false;
        double dx = x - p.pos.x, dy = y - p.pos.y;
        double ang = Math.atan2(dy, dx);
        return Math.sqrt(dx * dx + dy * dy) < p.radius && p.heightAt(ang) < 0;
    }

    public boolean isInSunlight(double x, double y) {
        // segment from point to sun center; occluded by any planet disk
        double sx = sun.pos.x - x, sy = sun.pos.y - y;
        double segLen2 = sx * sx + sy * sy;
        if (segLen2 < 1) return true;
        for (Planet p : planets) {
            if (p == sun) continue;
            double cx = p.pos.x - x, cy = p.pos.y - y;
            double t = (cx * sx + cy * sy) / segLen2;
            if (t < 0 || t > 1) continue;
            double px = cx - sx * t, py = cy - sy * t;
            if (px * px + py * py < p.radius * p.radius) return false;
        }
        return true;
    }

    // ---------------------------------------------------------------- ships

    public void addShip(Ship s) {
        if (!ships.contains(s)) ships.add(s);
    }

    // Structural changes triggered from Lua callbacks (detach, future
    // destroy/spawn APIs) must NEVER run inline: the callback may be deep
    // inside a parts/ships iteration, and mutating the graph there crashes
    // (concurrent modification / stale refs). They are queued here and run at
    // a safe point — after the frame's script callbacks or right after the
    // activation call that enqueued them.
    private final List<Runnable> deferredStructure = new ArrayList<>();

    public void deferStructure(Runnable r) { deferredStructure.add(r); }

    public void processDeferredStructure() {
        if (deferredStructure.isEmpty()) return;
        List<Runnable> ops = new ArrayList<>(deferredStructure);
        deferredStructure.clear();
        for (Runnable r : ops) {
            try {
                r.run();
            } catch (Throwable t) {
                Gdx.app.error("world", "deferred structure op failed", t);
            }
        }
    }

    /** Spawn a ship from a design on the launch pad of the given planet. */
    public Ship launchShip(ShipDesign design, Planet planet) {
        FlameFx.reset(); // no stale exhaust particles from a previous ship/scene
        // The frame move below (translateFrame + frameVel change) would otherwise
        // teleport/reset every existing ship to the launch site (owner bug):
        // capture their universe transforms now and restore them afterwards.
        List<Ship> old = new ArrayList<>(ships);
        double[] oux = new double[old.size()], ouy = new double[old.size()];
        double[] ovx = new double[old.size()], ovy = new double[old.size()];
        for (int i = 0; i < old.size(); i++) {
            Vec2d u = old.get(i).getUniversePos();
            Vec2d v = old.get(i).getUniverseVel();
            oux[i] = u.x; ouy[i] = u.y; ovx[i] = v.x; ovy[i] = v.y;
        }

        double padAngle = Math.PI / 2; // top of the planet
        double surfR = planet.radius + planet.heightAt(padAngle); // incl. noise terrain
        // full design bounding box (every part, not just root)
        float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (ShipDesign.DesignPart dp : design.parts) {
            PartType t = PartList.get(dp.typeId);
            if (t == null) continue;
            minY = Math.min(minY, dp.y - t.height / 2f);
            maxY = Math.max(maxY, dp.y + t.height / 2f);
        }
        if (minY == Float.MAX_VALUE) { minY = -2; maxY = 2; }

        // place the design origin so the LOWEST part point sits just above the
        // terrain surface (lowest = spawnR + minY == surfR + margin)
        // round 13: margin 1.2 -> 0.1 m. Restitution is now 0 everywhere, but
        // the 1.2 m free-fall still built up impact speed that showed up as a
        // landing transient; 0.1 m keeps clearance without the drop.
        double ux = Math.cos(padAngle), uy = Math.sin(padAngle);
        double spawnR = surfR - minY + 0.1;
        double sx = planet.pos.x + ux * spawnR;
        double sy = planet.pos.y + uy * spawnR;

        Ship ship = new Ship(this);
        ship.origin.set(origin); // bodies are built in the current physics frame
        // rotate ship so design +y points radially out
        float spawnAngle = (float) (padAngle - Math.PI / 2);
        ship.buildFromDesign(design, spawnAngle);
        // the frame carries the planet's velocity; bodies start at rest so the
        // ship is at rest relative to the launch site (avoids Box2D's velocity cap)
        frameVel.set(planet.vel);

        addShip(ship);
        // move the world frame onto the launch site without moving any bodies
        translateFrame(sx - origin.x, sy - origin.y);
        setActive(ship);
        targetHeading = spawnAngle; // steering holds the launch heading

        // restore every pre-existing ship's universe position and velocity
        for (int i = 0; i < old.size(); i++) {
            Ship s = old.get(i);
            Vec2d now = s.getUniversePos();
            double dx = oux[i] - now.x, dy = ouy[i] - now.y;
            if (dx * dx + dy * dy > 1e-12) {
                for (Part p : s.parts) {
                    if (p.body != null) {
                        p.body.setTransform(p.body.getPosition().x + (float) dx,
                                p.body.getPosition().y + (float) dy, p.body.getAngle());
                    }
                }
            }
            Vec2d vn = s.getUniverseVel();
            double dvx = ovx[i] - vn.x, dvy = ovy[i] - vn.y;
            if (dvx * dvx + dvy * dvy > 1e-12) {
                if (s.onRails) {
                    s.originVel.add(dvx, dvy);
                } else {
                    for (Part p : s.parts) {
                        if (p.body != null) {
                            p.body.setLinearVelocity(p.body.getLinearVelocity().x + (float) dvx,
                                    p.body.getLinearVelocity().y + (float) dvy);
                        }
                    }
                }
            }
        }
        updateRailsFlags();
        System.out.println("[launch] planet.vel=" + planet.vel.x + "," + planet.vel.y
                + " body0.vel=" + ship.parts.get(0).body.getLinearVelocity()
                + " shipUniverseVel=" + ship.getUniverseVel().x + "," + ship.getUniverseVel().y
                + " origin=" + origin.x + "," + origin.y
                + " preservedShips=" + old.size());
        save();
        return ship;
    }

    /** Make a ship the active one: re-anchor the floating origin on it. */
    public void setActive(Ship s) {
        if (s == null) return;
        active = s;
        reanchorToActive();
        updateRailsFlags();
    }

    /** Change the universe position assigned to the physics origin; bodies do NOT move. */
    private void translateFrame(double dx, double dy) {
        if (dx == 0 && dy == 0) return;
        origin.x += dx;
        origin.y += dy;
        for (Ship s : ships) {
            s.origin.x += dx;
            s.origin.y += dy;
        }
    }

    /**
     * Teleport the ACTIVE ship to altAbove metres above another planet's
     * surface (round 13; the smoke test moves the steering-convergence
     * scenario into Smoon's vacuum this way). Only the active ship's universe
     * anchor (double) moves — body-local float coords are untouched, so there
     * is no precision loss. The ship's planet-relative velocity is preserved
     * in LOCAL terms: radial/tangential components are kept but re-expressed
     * against the new planet's radial, so "ascending straight up" stays
     * "ascending straight up". frameVel absorbs the resulting universe-velocity
     * delta; every OTHER ship is held in place by subtracting the same delta
     * from its own velocity channel (mirrors the preserve/restore pattern in
     * launchShip).
     */
    public void teleportActiveToPlanet(Planet target, double altAbove) {
        if (active == null || target == null) return;
        Planet from = currentPlanet();
        Vec2d u = active.getUniversePos();
        double ang = Math.atan2(u.y - target.pos.y, u.x - target.pos.x);
        double r = target.radius + target.heightAt(ang) + altAbove;
        double nx = target.pos.x + Math.cos(ang) * r;
        double ny = target.pos.y + Math.sin(ang) * r;
        active.origin.x += nx - u.x;
        active.origin.y += ny - u.y;
        // Velocity: re-express the ship's planet-relative velocity in the NEW
        // local frame — keep the radial/tangential components, swap the
        // radial direction (else "ascending straight up" over the old planet
        // becomes "skimming sideways" over the new one and the ship plows
        // the terrain — first Smoon run failure).
        Vec2d v0 = active.getUniverseVel();
        double relx = v0.x, rely = v0.y;
        if (from != null) { relx -= from.vel.x; rely -= from.vel.y; }
        double ux0 = 0, uy0 = 1;
        if (from != null) {
            double dx0 = u.x - from.pos.x, dy0 = u.y - from.pos.y;
            double l0 = Math.hypot(dx0, dy0);
            if (l0 > 1e-9) { ux0 = dx0 / l0; uy0 = dy0 / l0; }
        }
        double vR = relx * ux0 + rely * uy0;          // radial component (old frame)
        double vT = -relx * uy0 + rely * ux0;         // tangential component (old frame)
        double ux1 = Math.cos(ang), uy1 = Math.sin(ang); // new local radial
        double nrelx = vR * ux1 - vT * uy1;
        double nrely = vR * uy1 + vT * ux1;
        double nwx = target.vel.x + nrelx, nwy = target.vel.y + nrely;
        double dvx = nwx - v0.x, dvy = nwy - v0.y;
        frameVel.add(dvx, dvy);
        for (Ship s : ships) {
            if (s == active) continue;
            if (s.onRails) {
                s.originVel.add(-dvx, -dvy);
            } else {
                for (Part p : s.parts) {
                    if (p.body != null) {
                        p.body.setLinearVelocity(p.body.getLinearVelocity().x - (float) dvx,
                                p.body.getLinearVelocity().y - (float) dvy);
                    }
                }
            }
        }
        updateRailsFlags();
    }

    /** Shift all bodies so the active ship's COM sits at the physics origin. */
    private void reanchorToActive() {
        if (active == null) return;
        Vector2 com = active.centerOfMass(tmpV);
        double dx = com.x, dy = com.y;
        if (dx * dx + dy * dy < 1e-6) return;
        for (Ship s : ships) {
            s.shiftBodies(-dx, -dy);
        }
        origin.x += dx;
        origin.y += dy;
    }

    /**
     * Transfer the active ship's COM velocity into the frame so body velocities
     * stay small (relative) and never hit Box2D's max-translation clamp.
     */
    private void velocityReanchor() {
        if (active == null) return;
        Vector2 cv = active.velocity(tmpV);
        if (cv.len2() < 1e-4f) return;
        for (Ship s : ships) {
            if (s.onRails) {
                s.originVel.sub(tmp2d.set(cv.x, cv.y));
            } else {
                for (Part p : s.parts) {
                    if (p.body != null) {
                        p.body.setLinearVelocity(
                                p.body.getLinearVelocity().x - cv.x,
                                p.body.getLinearVelocity().y - cv.y);
                    }
                }
            }
        }
        frameVel.add(cv.x, cv.y);
    }

    private final Vec2d tmp2d = new Vec2d();

    private void updateRailsFlags() {
        if (active == null) return;
        Vec2d ap = active.getUniversePos();
        for (Ship s : ships) {
            // round 14: during super-warp EVERY ship rides rails (the frame
            // carries the active ship — see superWarp); at physical warp only
            // distant ships rail.
            boolean rails = warp > PHYS_WARP_MAX
                    || (s != active && s.getUniversePos().dist(ap) > RAILS_DISTANCE);
            if (rails != s.onRails) {
                s.onRails = rails;
                if (rails) {
                    // freeze: store frame-relative velocity, park the bodies rigidly
                    Vector2 cv = s.velocity(tmpV);
                    s.originVel.set(cv.x, cv.y);
                    for (Part p : s.parts) {
                        if (p.body != null) p.body.setLinearVelocity(0, 0);
                    }
                } else {
                    // reactivate: give bodies the ship's frame-relative velocity
                    for (Part p : s.parts) {
                        if (p.body != null) {
                            p.body.setLinearVelocity((float) s.originVel.x, (float) s.originVel.y);
                        }
                    }
                    s.originVel.set(0, 0);
                }
                s.setBodiesActive(!rails);
            }
        }
    }

    // ---------------------------------------------------------------- update

    /** Advance the simulation by frameDt seconds (scaled by warp). */
    public void update(float frameDt) {
        if (paused) return;
        // (re)bind gameplay scripts after hot-reload / resource reload
        PhysicsScript.ensureBound(this);
        TerrainScript.ensureBound(this);
        updateRailsFlags(); // super-warp parks/reactivates the active ship
        double simDt;
        if (warp <= PHYS_WARP_MAX) {
            int steps = Math.max(1, Math.min(8, Math.round(frameDt * warp / PHYS_DT)));
            for (int i = 0; i < steps; i++) {
                substep(PHYS_DT);
            }
            simDt = steps * PHYS_DT;
            // scripts run once per frame with the full simulated dt
            if (active != null) {
                updateSteering(frameDt * warp);
                active.updateScripts(frameDt * warp);
            }
            // apply any structural changes the scripts requested (detach etc.)
            processDeferredStructure();
            // post-step housekeeping
            if (active != null) {
                float invDt = 1f / PHYS_DT;
                active.checkJointBreaks(invDt);
                reanchorToActive();
                velocityReanchor();
                updateLanded();
            }
        } else {
            // super-warp (round 14 item 7): every ship rides rails (see
            // updateRailsFlags) and the PHYSICS FRAME itself rides the active
            // ship — frameVel carries the active ship's universe velocity, so
            // parked bodies, the camera and the terrain stay coherent while
            // time advances in chunked semi-implicit Euler steps. No Box2D,
            // no scripts, engines dark.
            simDt = frameDt * warp;
            superWarp(simDt);
            if (active != null) {
                for (Part p : active.parts) p.flameLevel = 0f;
                updateLanded();
            }
        }
        updateRailsFlags();
        terrain.update(active != null ? active.getUniversePos() : origin, simDt);

        saveTimer += frameDt;
        if (saveTimer > 5) {
            saveTimer = 0;
            save();
        }
    }

    /**
     * Super-warp time advance (round 19 rewrite: precomputed-trajectory
     * following). Entering a >4x warp level (and afterwards only at LOW
     * frequency — see warpTrajValid) the active ship's future inertial path
     * is propagated ONCE by OrbitPredictor.computeWarp: the same velocity-
     * Verlet, the same ΣGM/r² gravity and the same adaptive-dt rule as the
     * map prediction line, so what you fly IS what the map drew. Each frame
     * the ship's state is a pure FUNCTION OF TIME on that trajectory (cubic
     * Hermite between samples, velocity = exact Hermite derivative) — no
     * per-chunk integration on the hot path and zero accumulated drift;
     * periodic recompute (one visible hitch, accepted) resets any
     * interpolation bias. The physics frame rides the sampled state:
     * frameVel = sampled velocity − active.originVel, and the frame is
     * translated so the active ship sits exactly on the sampled position.
     * Reaching a surface-impact endpoint hands control back to physics
     * (warp = 1) at the impact point with the impact velocity. Parked on
     * the ground the trajectory is meaningless — the original riding logic
     * (frame rides the planet, time just runs fast) is kept. Non-active
     * ships keep their existing chunked rails advance (warpRailsShip) with
     * the frame's dv compensated once per frame.
     */
    private void superWarp(double simDt) {
        if (active == null) { time += simDt; sun.updateRails(time); return; }
        Vec2d acom = active.getUniversePos();
        Planet anp = nearestPlanetTo(acom.x, acom.y);
        // parked on a surface? the frame rides the planet (unchanged logic)
        boolean riding = false;
        if (anp != null) {
            double dx = acom.x - anp.pos.x, dy = acom.y - anp.pos.y;
            double aAlt = Math.hypot(dx, dy) - anp.radius - anp.heightAt(Math.atan2(dy, dx));
            double rvx = frameVel.x - anp.vel.x, rvy = frameVel.y - anp.vel.y;
            riding = aAlt < 50.0 && Math.hypot(rvx, rvy) < 1.0;
        }
        if (riding) {
            wtCount = 0; // grounded: no valid trajectory, recompute on liftoff
            double dvx = anp.vel.x - frameVel.x, dvy = anp.vel.y - frameVel.y;
            if (dvx != 0 || dvy != 0) {
                for (Ship s : ships) {
                    if (s != active) { s.originVel.x -= dvx; s.originVel.y -= dvy; }
                }
                frameVel.add(dvx, dvy);
            }
            time += simDt;
            sun.updateRails(time);
            double fx = frameVel.x * simDt, fy = frameVel.y * simDt;
            origin.add(fx, fy);
            for (Ship s : ships) {
                s.origin.add(fx, fy);
                if (s != active) warpRailsShip(s, simDt);
            }
            return;
        }

        // low-frequency (re)compute: on entry, on warp-level change, on
        // staging (parts count), or when <20% of the trajectory remains.
        // dtScale stays 1.0 at every warp level: the map line uses the
        // predictor's unscaled adaptive dt, and flying the SAME step rule
        // is what keeps the flown path glued to the map line (a 4x stretch
        // measured 5.4e-4 rad/orbit of extra phase error in low orbit);
        // 40000 unscaled samples still cover ~25 low orbits (~5 s of real
        // time between recomputes at 250000x), so recomputes stay rare.
        double dtScale = 1.0;
        if (!warpTrajValid()) {
            wtCount = warpPred.computeWarp(this, active, dtScale, wtX, wtY, wtVX, wtVY, wtT);
            wtImpact = warpPred.impacted;
            wtWarp = warp;
            wtParts = active.parts.size();
            wtHint = 0;
        }
        if (wtCount < 2) { // propagation produced nothing: just run the clock
            time += simDt;
            sun.updateRails(time);
            return;
        }

        double target = time + simDt;
        boolean hitEnd = false;
        if (target >= wtT[wtCount - 1]) {
            target = wtT[wtCount - 1]; // clamp; impact below, else recompute next frame
            hitEnd = wtImpact;
        }

        // Hermite sample at the target time (position + exact derivative)
        int lo = Math.min(wtHint, wtCount - 2);
        if (target < wtT[lo] || target > wtT[lo + 1]) {
            int a = 0, b = wtCount - 1;
            while (b - a > 1) {
                int m = (a + b) >>> 1;
                if (wtT[m] <= target) a = m; else b = m;
            }
            lo = a;
        }
        wtHint = lo;
        double t0 = wtT[lo], span = wtT[lo + 1] - t0;
        double u = span > 0 ? (target - t0) / span : 0;
        double u2 = u * u, u3 = u2 * u;
        double h00 = 2 * u3 - 3 * u2 + 1, h10 = u3 - 2 * u2 + u;
        double h01 = -2 * u3 + 3 * u2, h11 = u3 - u2;
        double sx = h00 * wtX[lo] + h10 * span * wtVX[lo] + h01 * wtX[lo + 1] + h11 * span * wtVX[lo + 1];
        double sy = h00 * wtY[lo] + h10 * span * wtVY[lo] + h01 * wtY[lo + 1] + h11 * span * wtVY[lo + 1];
        double d00 = (6 * u2 - 6 * u) / span, d10 = 3 * u2 - 4 * u + 1;
        double d01 = (-6 * u2 + 6 * u) / span, d11 = 3 * u2 - 2 * u;
        double svx = d00 * wtX[lo] + d10 * wtVX[lo] + d01 * wtX[lo + 1] + d11 * wtVX[lo + 1];
        double svy = d00 * wtY[lo] + d10 * wtVY[lo] + d01 * wtY[lo + 1] + d11 * wtVY[lo + 1];

        // advance the clock + planets in chunks while railing non-active ships
        double remain = target - time;
        while (remain > 1e-9) {
            double h = Math.min(remain, warpChunk(simDt));
            remain -= h;
            time += h;
            sun.updateRails(time);
            for (Ship s : ships) {
                if (s != active) warpRailsShip(s, h);
            }
        }

        // put the active ship exactly on the sampled state
        Vec2d cur = active.getUniversePos();
        translateFrame(sx - cur.x, sy - cur.y);
        double fvx = svx - active.originVel.x, fvy = svy - active.originVel.y;
        double dvx = fvx - frameVel.x, dvy = fvy - frameVel.y;
        if (dvx != 0 || dvy != 0) {
            for (Ship s : ships) {
                if (s != active) { s.originVel.x -= dvx; s.originVel.y -= dvy; }
            }
            frameVel.set(fvx, fvy);
        }

        if (hitEnd) {
            warp = 1;      // hand back to physics at the impact point
            wtCount = 0;
        }
    }

    /** Trajectory still usable? (recompute triggers are the false branches) */
    private boolean warpTrajValid() {
        if (wtCount < 2 || active == null) return false;
        if (wtWarp != warp) return false;                  // warp-level change
        if (wtParts != active.parts.size()) return false;  // staging/detach
        double total = wtT[wtCount - 1] - wtT[0];
        if (total <= 0 || time < wtT[0] || time > wtT[wtCount - 1]) return false;
        return (wtT[wtCount - 1] - time) >= 0.2 * total;   // <20% left: rebuild
    }

    /** One non-active ship's rails chunk during super-warp (frame-relative). */
    private void warpRailsShip(Ship s, double h) {
        Vec2d com = s.getUniversePos();
        Planet np = nearestPlanetTo(com.x, com.y);
        if (np != null) {
            double dx = com.x - np.pos.x, dy = com.y - np.pos.y;
            double dist = Math.hypot(dx, dy);
            double alt = dist - np.radius - np.heightAt(Math.atan2(dy, dx));
            double uvx = s.originVel.x + frameVel.x - np.vel.x;
            double uvy = s.originVel.y + frameVel.y - np.vel.y;
            if (alt < 50.0 && Math.hypot(uvx, uvy) < 1.0) {
                // parked: ride the planet (see Ship.integrateRails)
                s.originVel.set(np.vel.x - frameVel.x, np.vel.y - frameVel.y);
                return;
            }
        }
        Vec2d gs = gravityFast(com.x, com.y); // built-in law: hot warp path (round 18)
        s.originVel.add(gs.x * h, gs.y * h);
        s.origin.add(s.originVel.x * h, s.originVel.y * h);
        if (np != null) {
            Vec2d c1 = s.getUniversePos();
            double dx = c1.x - np.pos.x, dy = c1.y - np.pos.y;
            double dist = Math.hypot(dx, dy);
            double surf = np.radius + np.heightAt(Math.atan2(dy, dx));
            if (dist < surf + 0.5 && dist > 1e-9) {
                double ux = dx / dist, uy = dy / dist;
                double push = (surf + 0.5) - dist;
                s.origin.x += ux * push;
                s.origin.y += uy * push;
                double rv = s.originVel.x * ux + s.originVel.y * uy;
                if (rv < 0) { s.originVel.x -= rv * ux; s.originVel.y -= rv * uy; }
            }
        }
    }

    private void substep(float h) {
        time += h;
        sun.updateRails(time);
        if (active != null) applyEnvironmentForces(active, h);
        boxWorld.step(h, VEL_ITER, POS_ITER);
        // advance the inertial frame: the physics origin moves with frameVel
        double fx = frameVel.x * h, fy = frameVel.y * h;
        if (fx != 0 || fy != 0) {
            origin.add(fx, fy);
            for (Ship s : ships) s.origin.add(fx, fy);
        }
        for (Ship s : ships) {
            if (s.onRails) s.integrateRails(h);
        }
    }

    /** Gravity, aerodynamic drag and buoyancy for every part of the ship. */
    private void applyEnvironmentForces(Ship ship, float h) {
        // ship-level airflow direction for the occlusion sweep (item 2): the
        // freestream is the ship's universe velocity relative to the planet.
        // Uses the first live body as reference — per-part velocities differ
        // only by structural wobble, negligible for shadowing.
        for (Part ref : ship.parts) {
            if (ref.body == null || !ref.body.isActive()) continue;
            Planet np0 = nearestPlanetTo(origin.x + ref.body.getPosition().x,
                    origin.y + ref.body.getPosition().y);
            if (np0 != null && np0.hasAtmosphere()) {
                Vector2 v0 = ref.body.getLinearVelocity();
                ship.updateDragExposure(
                        (float) (frameVel.x + ship.originVel.x + v0.x - np0.vel.x),
                        (float) (frameVel.y + ship.originVel.y + v0.y - np0.vel.y),
                        (float) time);
            }
            break;
        }
        for (Part p : ship.parts) {
            if (p.body == null || !p.body.isActive()) continue;
            Vector2 bp = p.body.getPosition();
            double ux = origin.x + bp.x;
            double uy = origin.y + bp.y;
            float m = p.body.getMass();

            // gravity
            Vec2d g = gravityAt(ux, uy);
            p.body.applyForceToCenter((float) (g.x * m), (float) (g.y * m), false);

            // drag
            Planet np = nearestPlanetTo(ux, uy);
            if (np != null && np.hasAtmosphere()) {
                double alt = altitudeAt(ux, uy);
                // round 14 fix: drag must use the player-editable density law
                // (mod/physics.lua atmosphereDensity) — it used to call the
                // built-in Planet.densityAt directly, so editing physics.lua
                // had no effect on drag.
                double rho = densityAt(ux, uy);
                if (rho > 1e-9) {
                    Vector2 v = p.body.getLinearVelocity();
                    // wind-relative velocity in the universe frame (planet rotation ignored)
                    double rvx = frameVel.x + ship.originVel.x + v.x - np.vel.x;
                    double rvy = frameVel.y + ship.originVel.y + v.y - np.vel.y;
                    double speed2 = rvx * rvx + rvy * rvy;
                    if (speed2 > 0.01) {
                        double speed = Math.sqrt(speed2);
                        // per-part drag: Lua-set absolute Cd wins; otherwise the
                        // 0.75 baseline adjusted by the PartList.xml `drag` attr
                        // (nosecone drag="-1.0" subtracts from the ship total).
                        double cd = !Double.isNaN(p.dragCd)
                                ? p.dragCd
                                : Math.max(0.0, 0.75 + p.type.drag);
                        double area = !Double.isNaN(p.dragArea) ? p.dragArea : p.type.width;
                        double fmag = 0.5 * rho * speed2 * cd * area;
                        // parts shadowed by upstream structure feel less drag
                        fmag *= p.dragExposure;
                        p.body.applyForceToCenter(
                                (float) (-fmag * rvx / speed),
                                (float) (-fmag * rvy / speed), false);
                    }
                }
                // buoyancy
                if (np.waterDensity > 0) {
                    double dx = ux - np.pos.x, dy = uy - np.pos.y;
                    double rr = Math.sqrt(dx * dx + dy * dy);
                    double ang = Math.atan2(dy, dx);
                    if (rr < np.radius && np.heightAt(ang) < 0) {
                        double submersion = Math.min(1.0, (np.radius - rr) / Math.max(1, p.type.height));
                        double vol = p.type.width * p.type.height;
                        double glen = g.len();
                        double fb = np.waterDensity * vol * Math.max(0, p.type.buoyancy) * submersion * glen;
                        // buoyancy acts radially outward (opposite local gravity)
                        p.body.applyForceToCenter((float) (dx / rr * fb), (float) (dy / rr * fb), false);
                    }
                }
            }
        }
    }

    private void updateLanded() {
        if (active == null) return;
        Planet cp = currentPlanet();
        Vec2d sv = active.getUniverseVel();
        double rel = cp != null ? Math.hypot(sv.x - cp.vel.x, sv.y - cp.vel.y) : sv.len();
        active.landed = rel < 0.5;
    }

    // ---------------------------------------------------------------- steering

    /** Current heading of the active ship (pod body angle, or first part). */
    public double currentHeading() {
        if (active == null) return 0;
        Part ref = active.controlPart();
        return ref != null && ref.body != null ? ref.body.getAngle() : 0;
    }

    /** Command a heading (ring semantics): activates ring mode on SteeringIO. */
    public void setTargetHeading(double rad) {
        targetHeading = rad;
        SteeringIO.targetHeadingRad = rad;
        SteeringIO.ringActive = true;
    }
    public double getTargetHeading() { return targetHeading; }
    public double getTurnCommand() { return turnCommand; }

    /**
     * Steering command resolution (round 12): the turn command now comes from
     * SteeringIO, NOT from the old ship-level PI controller (superseded, see
     * mod/control.lua for the engine control law and physics.lua for notes).
     *  - BUTTON mode (SteeringIO.buttonTurn != 0): uniform full-rate command,
     *    overrides the ring; every gimbaled engine deflects to its own max.
     *  - RING mode (SteeringIO.ringActive): engine gimbals track the target
     *    via control.lua (gimbal = heading error, clamped per engine); the
     *    ship-level command here is a simple proportional fallback (-1..1,
     *    full beyond 15° of error) for non-engine consumers like RCS.
     *  - no input: command 0 — gimbals center, RCS idle.
     * Sign convention unchanged: positive turn command produces CLOCKWISE
     * torque (engine gimbal + RCS scripts follow it).
     */
    private void updateSteering(double dt) {
        Part ref = active != null ? active.controlPart() : null;
        if (ref == null || ref.body == null) {
            inputTurn = 0;
            turnCommand = 0;
            return;
        }
        if (!steerPrimed) { // first frame with a ship: hold the spawn heading
            steerPrimed = true;
            targetHeading = ref.body.getAngle();
            SteeringIO.targetHeadingRad = targetHeading;
        }
        if (SteeringIO.buttonTurn != 0) {
            turnCommand = SteeringIO.buttonTurn;
        } else if (SteeringIO.ringActive) {
            targetHeading = SteeringIO.targetHeadingRad; // SteeringIO is authoritative
            double err = wrapPi(targetHeading - ref.body.getAngle());
            // negative sign: positive error (target CCW of the nose) needs
            // CCW torque, which is a NEGATIVE command in our convention
            turnCommand = Math.max(-1, Math.min(1, -err / Math.toRadians(15)));
        } else {
            turnCommand = 0;
        }
        inputTurn = turnCommand;
    }

    private static double wrapPi(double a) {
        a = a % (2 * Math.PI);
        if (a > Math.PI) a -= 2 * Math.PI;
        if (a < -Math.PI) a += 2 * Math.PI;
        return a;
    }

    // ---------------------------------------------------------------- save/load

    private FileHandle saveFile() {
        return Gdx.files.local("save/world.json");
    }

    public void save() {
        try {
            Json.Writer w = new Json.Writer();
            w.obj();
            w.set("time", time);
            w.set("originX", origin.x);   // floating-origin universe position —
            w.set("originY", origin.y);   // WITHOUT this, restored ships evaluate
            w.set("frameVelX", frameVel.x); // gravity/altitude/planet distance
            w.set("frameVelY", frameVel.y); // offset by the whole frame origin
            w.set("active", active != null ? ships.indexOf(active) : -1);
            w.key("ships"); w.arr();
            for (Ship s : ships) {
                w.obj();
                w.set("name", s.name);
                w.set("originX", s.origin.x);
                w.set("originY", s.origin.y);
                w.set("velX", s.originVel.x);
                w.set("velY", s.originVel.y);
                w.set("stage", s.currentStage);
                w.set("rails", s.onRails);
                w.key("parts"); w.arr();
                for (Part p : s.parts) {
                    w.obj();
                    w.set("t", p.type.id);
                    if (p.body != null) {
                        w.set("x", (double) p.body.getPosition().x);
                        w.set("y", (double) p.body.getPosition().y);
                        w.set("a", (double) p.body.getAngle());
                        Vector2 v = p.body.getLinearVelocity();
                        w.set("vx", (double) v.x);
                        w.set("vy", (double) v.y);
                        w.set("va", (double) p.body.getAngularVelocity());
                    }
                    w.set("fuel", p.fuel);
                    w.set("dep", p.deployed);
                    if (p.group > 0) w.set("grp", p.group);
                    w.endObj();
                }
                w.endArr();
                w.key("links"); w.arr();
                for (Ship.Link l : s.links) {
                    int ia = s.parts.indexOf(l.a), ib = s.parts.indexOf(l.b);
                    if (ia < 0 || ib < 0) continue;
                    w.obj();
                    w.set("a", ia);
                    w.set("b", ib);
                    w.endObj();
                }
                w.endArr();
                w.key("stages"); w.arr();
                for (List<Integer> st : s.stages) {
                    w.arr();
                    for (int idx : st) w.val(idx);
                    w.endArr();
                }
                w.endArr();
                w.endObj();
            }
            w.endArr();
            w.endObj();
            FileHandle f = saveFile();
            f.parent().mkdirs();
            f.writeString(w.toString(), false);
        } catch (Exception e) {
            Gdx.app.error("save", "failed to save world", e);
        }
    }

    /** Load persisted world; returns true if anything was loaded. */
    public boolean load() {
        try {
            FileHandle f = saveFile();
            if (!f.exists()) return false;
            FlameFx.reset(); // drop exhaust particles from whatever ran before
            Json.JObj root = Json.parse(f.readString());
            time = root.getNum("time", 0);
            // restore the floating origin BEFORE ships: body positions are
            // frame-relative, and every environment query resolves universe
            // coords as world.origin + bodyPos (old saves without these keys
            // keep the previous behavior: origin stays 0)
            origin.set(root.getNum("originX", 0), root.getNum("originY", 0));
            frameVel.set(root.getNum("frameVelX", 0), root.getNum("frameVelY", 0));
            sun.updateRails(time);
            List<Json.Value> ss = root.getArr("ships");
            if (ss != null) {
                for (Json.Value v : ss) {
                    Ship s = Ship.fromJson(this, v.asObj());
                    ships.add(s);
                }
            }
            int ai = root.getInt("active", -1);
            if (ai >= 0 && ai < ships.size()) {
                setActive(ships.get(ai));
            } else if (!ships.isEmpty()) {
                setActive(ships.get(0));
            }
            return !ships.isEmpty();
        } catch (Exception e) {
            Gdx.app.error("save", "failed to load world", e);
            return false;
        }
    }

    public void clearSave() {
        try {
            FileHandle f = saveFile();
            if (f.exists()) f.delete();
        } catch (Exception ignored) {}
    }

    public void dispose() {
        for (Ship s : ships) s.destroy();
        ships.clear();
        terrain.dispose();
        boxWorld.dispose();
    }
}
