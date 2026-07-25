package com.differentrockets.game;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.physics.box2d.World;
import com.badlogic.gdx.utils.XmlReader;
import com.differentrockets.util.Json;
import com.differentrockets.util.Res;
import com.differentrockets.util.Vec2d;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
        // Per-part ground clearance (round 27 spawn-settle fix): the spawn
        // height must put the LOWEST part exactly ON the collision surface,
        // and the collision surface is TerrainScript's lua height (with the
        // built-in fallback), not just planet.heightAt at the pad top. Using
        // a single center sample + a 0.1 m air gap made every spawn a short
        // free-fall followed by an impact — a 170-250 kN one-frame weld-force
        // spike that has nothing to do with static load. Compute the required
        // lift for EVERY part (its own terrain sample at its own surface
        // angle, its own bottom extent incl. 90-degree rotations) and take
        // the max, with a 2 mm slop instead of 100 mm.
        double spawnR = 0;
        boolean any = false;
        for (ShipDesign.DesignPart dp : design.parts) {
            PartType t = PartList.get(dp.typeId);
            if (t == null) continue;
            double halfH = (dp.rot % 2 == 0) ? t.height / 2.0 : t.width / 2.0;
            // design +y maps to radial-out; design +x maps to surface-east,
            // which DECREASES the angle at the pad top
            double ang = padAngle - dp.x / planet.radius;
            double arc = ang * planet.radius;
            double r = TerrainScript.surfaceHeight(planet.name, arc);
            if (Double.isNaN(r) || Double.isInfinite(r) || r < planet.radius * 0.5) {
                r = planet.radius + planet.heightAt(ang); // built-in fallback
            }
            double need = r - (dp.y - halfH);
            if (!any || need > spawnR) { spawnR = need; any = true; }
        }
        if (!any) { spawnR = planet.radius + planet.heightAt(padAngle) + 2; }

        double ux = Math.cos(padAngle), uy = Math.sin(padAngle);
        spawnR += 0.002; // 2 mm slop: no free-fall drop, no initial penetration
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
        // build the terrain colliders under the newborn ship NOW: chunk
        // creation normally runs at 10 Hz AFTER the physics substep, so the
        // first frames otherwise step with no ground at all — a hidden
        // free-fall + impact spike on every weld.
        terrain.forceRefresh(ship.getUniversePos());
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
        // round 20 item 6 (switch ship): the steering target and the
        // super-warp trajectory belong to the PREVIOUS ship. Drop both:
        // steerPrimed=false re-primes the target to the new ship's own
        // heading next frame, the ring falls back to inactive (gray), and
        // wtCount=0 forces a fresh trajectory for the new active ship.
        steerPrimed = false;
        wtCount = 0;
        SteeringIO.ringActive = false;
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
        // round 20 (probe-found): the WORLD origin must move with the active
        // ship — applyEnvironmentForces samples gravity/density/altitude at
        // `origin + bodyPos`, which is only the ship's universe position when
        // world.origin == active frame origin. Teleporting without it left
        // environment sampling behind at the old site (surface g + sea-level
        // density at orbital altitude = mega-drag that killed the orbit).
        origin.x += nx - u.x;
        origin.y += ny - u.y;
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
            // round 26 (burn-then-warp continuity fix): ANY frame at physical
            // warp can change the ship's state — engine burns, drag,
            // collisions, staging. The precomputed super-warp trajectory
            // (wtX/wtVX/...) is a pure function of the state at compute time,
            // so it is VOID the moment physics runs again. Without this,
            // dropping from 25x to 4x for a course change and re-entering
            // 25x resurrected the PRE-BURN trajectory: warpTrajValid only
            // checks warp level / part count / time window, none of which a
            // burn touches — the ship visibly snapped back onto its old
            // orbit, undoing the maneuver ("回到变轨前状态"). Zeroing wtCount
            // here forces superWarp() to recompute from the LIVE state on
            // the next super-warp entry.
            wtCount = 0;
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
            // engines on non-active ships (staged-away boosters) keep burning
            for (Ship s : ships) {
                if (s != active && !s.onRails) s.updateEngineScripts(frameDt * warp);
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
        // round 26 (debris-acceleration fix): environment forces for EVERY
        // in-physics ship, not just the active one. Ships within
        // RAILS_DISTANCE are stepped by boxWorld too, but used to get NO
        // gravity/drag — a freshly detached stage kept a perfectly straight
        // inertial velocity while the active ship fell through curved
        // gravity, so relative to the ship (and the planet) the debris
        // appeared to pick up a phantom acceleration ("跟着有加速度").
        // Rails ships are unaffected: they integrate gravity in
        // integrateRails/warpRailsShip instead.
        for (Ship s : ships) {
            if (!s.onRails) applyEnvironmentForces(s, h);
        }
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

    // ---------------------------------------------------------------- save/load (Show_sandbox XML, round 26)

    /**
     * Sandbox save file: Show_sandbox-compatible XML at
     * <resource root>/Sandboxs/world.xml (see Res.sandboxDir). The legacy
     * JSON save (<resource root>/save/world.json — moved under the shared
     * root in round 28) is no longer WRITTEN but is still READ as a one-time
     * fallback when no XML save exists.
     */
    private FileHandle saveFile() {
        return Res.sandboxDir().child("world.xml");
    }

    private FileHandle legacySaveFile() {
        return Res.saveDir().child("world.json");
    }

    private static String f6(double v) {
        return String.format(Locale.US, "%.6f", v);
    }

    /**
     * trueAnomaly needs more digits than the %.6f convention: 5e-7 rad of
     * rounding at a 1e10 m orbital radius is a 5 km position error on load.
     */
    private static String f9(double v) {
        return String.format(Locale.US, "%.9f", v);
    }

    /** XML-attribute escaping for names that may contain & < > ". */
    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    // ---- trueAnomaly <-> Kepler rail state (PlanetNode) ----

    private static double keplerE(double M, double e) {
        M = (M + Math.PI) % (2 * Math.PI);
        if (M < 0) M += 2 * Math.PI;
        M -= Math.PI;
        double E = M + e * Math.sin(M);
        for (int k = 0; k < 12; k++) {
            E = E - (E - e * Math.sin(E) - M) / (1 - e * Math.cos(E));
        }
        return E;
    }

    /**
     * True anomaly (rad, wrapped to [-pi, pi]) of a planet on its Kepler rail
     * at absolute time t — mirrors Planet.localPosVel's M = n·t + v0 rule
     * (negated for retrograde orbits). NaN for parentless/invalid planets.
     */
    public static double planetTrueAnomaly(Planet p, double t) {
        if (p == null || p.parent == null || p.a <= 0) return Double.NaN;
        double n = Math.sqrt(p.parent.mu() / (p.a * p.a * p.a));
        double M = n * t + p.v0;
        if (!p.prograde) M = -M;
        double E = keplerE(M, p.e);
        double nu = 2 * Math.atan2(Math.sqrt(1 + p.e) * Math.sin(E / 2),
                Math.sqrt(1 - p.e) * Math.cos(E / 2));
        return wrapPi(nu);
    }

    /**
     * Inverse of planetTrueAnomaly: set p.v0 so the planet sits at true
     * anomaly nu at time t (prograde: M = n·t + v0; retrograde: M = -(n·t + v0)).
     */
    private static void planetSetV0FromTrueAnomaly(Planet p, double nu, double t) {
        if (p == null || p.parent == null || p.a <= 0 || Double.isNaN(nu)) return;
        double n = Math.sqrt(p.parent.mu() / (p.a * p.a * p.a));
        double E = 2 * Math.atan2(Math.sqrt(1 - p.e) * Math.sin(nu / 2),
                Math.sqrt(1 + p.e) * Math.cos(nu / 2));
        double M = E - p.e * Math.sin(E); // mean anomaly equivalent (mod 2pi)
        p.v0 = wrapPi(p.prograde ? M - n * t : -M - n * t);
    }

    /**
     * Save the world as a Show_sandbox-compatible XML record:
     *   <Runtime time firstStageActivated solarSystem shipId podId><Nodes>
     *     <PlanetNode name trueAnomaly/> ...
     *     <ShipNode id planet planetRadius x y vx vy><Ship version liftedOff
     *       touchingGround><Parts><Part ...>[Tank/Engine/Pod...]</Part></Parts>
     *       <Connections>...</Connections></Ship></ShipNode> ...
     *   </Nodes></Runtime>
     * Ship/part coordinates are PLANET-RELATIVE (position and velocity), the
     * same convention as the reference Show_sandbox sample; part ids are
     * assigned per ship with the pod = 1.
     */
    public void save() {
        try {
            // per-ship part ids (pod = 1, then 2..N in parts order)
            Map<Ship, Map<Part, Integer>> shipIds = new IdentityHashMap<>();
            for (Ship s : ships) {
                Map<Part, Integer> m = new IdentityHashMap<>();
                Part pod = s.controlPart();
                int n = 1;
                if (pod != null && s.parts.contains(pod)) m.put(pod, n++);
                for (Part p : s.parts) if (!m.containsKey(p)) m.put(p, n++);
                shipIds.put(s, m);
            }
            int podId = 0;
            if (active != null && active.controlPart() != null) {
                Integer pi = shipIds.get(active).get(active.controlPart());
                podId = pi != null ? pi : 0;
            }
            // ship -> nearest planet (its ShipNode frame body)
            Map<Ship, Planet> shipPlanet = new IdentityHashMap<>();
            for (Ship s : ships) {
                Vec2d u = s.getUniversePos();
                shipPlanet.put(s, nearestPlanetTo(u.x, u.y));
            }

            StringBuilder sb = new StringBuilder(1 << 16);
            sb.append("<Runtime time=\"").append(f6(time))
              .append("\" firstStageActivated=\"")
              .append(active != null && active.currentStage > 0 ? 1 : 0)
              .append("\" solarSystem=\"SmolarSystem.xml\"")
              .append(" shipId=\"").append(active != null ? active.getId() : 0)
              .append("\" podId=\"").append(podId).append("\">");
            sb.append("<Nodes>");
            List<Ship> orphans = new ArrayList<>();
            for (Planet p : planets) {
                sb.append("<PlanetNode name=\"").append(esc(p.name)).append("\"");
                double nu = planetTrueAnomaly(p, time);
                if (!Double.isNaN(nu)) sb.append(" trueAnomaly=\"").append(f9(nu)).append("\"");
                sb.append("/>");
                for (Ship s : ships) {
                    if (shipPlanet.get(s) == p) writeShipNode(sb, s, p, shipIds.get(s));
                }
            }
            for (Ship s : ships) {
                if (shipPlanet.get(s) == null) orphans.add(s);
            }
            for (Ship s : orphans) {
                writeShipNode(sb, s, planets.isEmpty() ? null : planets.get(0), shipIds.get(s));
            }
            sb.append("</Nodes></Runtime>");
            FileHandle f = saveFile();
            f.parent().mkdirs();
            f.writeString(sb.toString(), false, "UTF-8");
        } catch (Exception e) {
            Gdx.app.error("save", "failed to save world", e);
        }
    }

    /** One ship's <ShipNode> subtree; positions/velocities planet-relative. */
    private void writeShipNode(StringBuilder sb, Ship s, Planet pl, Map<Part, Integer> ids) {
        if (pl == null) return;
        Vec2d u = s.getUniversePos();
        Vec2d v = s.getUniverseVel();
        sb.append("<ShipNode id=\"").append(s.getId())
          .append("\" planet=\"").append(esc(pl.name))
          .append("\" planetRadius=\"").append(f6(pl.radius))
          .append("\" x=\"").append(f6(u.x - pl.pos.x))
          .append("\" y=\"").append(f6(u.y - pl.pos.y))
          .append("\" vx=\"").append(f6(v.x - pl.vel.x))
          .append("\" vy=\"").append(f6(v.y - pl.vel.y)).append("\">");
        sb.append("<Ship version=\"1\" liftedOff=\"").append(s.landed ? 0 : 1)
          .append("\" touchingGround=\"").append(s.landed ? 1 : 0).append("\">");
        sb.append("<Parts>");
        for (Part p : s.parts) {
            Integer pid = ids.get(p);
            if (pid == null || p.body == null) continue;
            double pux = origin.x + p.body.getPosition().x;
            double puy = origin.y + p.body.getPosition().y;
            sb.append("<Part partType=\"").append(esc(p.type.id))
              .append("\" id=\"").append(pid)
              .append("\" x=\"").append(f6(pux - pl.pos.x))
              .append("\" y=\"").append(f6(puy - pl.pos.y))
              .append("\" angle=\"").append(f6(p.body.getAngle()))
              .append("\" angleV=\"").append(f6(p.body.getAngularVelocity()))
              .append("\" editorAngle=\"").append(p.design.rot).append("\"");
            boolean activated = p.deployed || (p.group > 0 && s.currentStage >= p.group);
            sb.append(" activated=\"").append(activated ? 1 : 0)
              .append("\" exploded=\"0\"")
              .append(" flippedX=\"").append(p.flippedX ? 1 : 0)
              .append("\" flippedY=\"").append(p.flippedY ? 1 : 0).append("\"");
            if (p.deployed) sb.append(" extension=\"1.000000\"");
            StringBuilder kids = new StringBuilder();
            if (p.type.tank != null) {
                kids.append("<Tank fuel=\"").append(f6(p.fuel)).append("\"/>");
            }
            if (p.type.engine != null) {
                kids.append("<Engine fuel=\"").append(f6(p.type.tank != null ? p.fuel : 0)).append("\"/>");
            }
            if ("pod".equals(p.type.type)) {
                kids.append("<Pod throttle=\"").append(f6(s == active ? inputThrottle : 0))
                    .append("\" name=\"").append(esc(s.name)).append("\">");
                kids.append("<Staging currentStage=\"").append(s.currentStage).append("\">");
                List<Integer> groups = new ArrayList<>();
                for (Part q : s.parts) {
                    if (q.group > 0 && !groups.contains(q.group)) groups.add(q.group);
                }
                groups.sort(null);
                for (int g : groups) {
                    kids.append("<Step>");
                    for (Part q : s.parts) {
                        if (q.group == g && ids.get(q) != null) {
                            kids.append("<Activate Id=\"").append(ids.get(q)).append("\" moved=\"1\"/>");
                        }
                    }
                    kids.append("</Step>");
                }
                kids.append("</Staging></Pod>");
            }
            if (kids.length() == 0) sb.append("/>");
            else sb.append(">").append(kids).append("</Part>");
        }
        sb.append("</Parts>");
        sb.append("<Connections>");
        for (Ship.Link l : s.links) {
            Integer ia = ids.get(l.a), ib = ids.get(l.b);
            if (ia == null || ib == null) continue;
            sb.append("<Connection parentAttachPoint=\"").append(Math.max(1, l.attachIndexA + 1))
              .append("\" childAttachPoint=\"").append(Math.max(1, l.attachIndexB + 1))
              .append("\" parentPart=\"").append(ia)
              .append("\" childPart=\"").append(ib).append("\"/>");
        }
        sb.append("</Connections></Ship></ShipNode>");
    }

    /**
     * Load the sandbox save (Show_sandbox-compatible XML). Reads everything
     * this game writes, and tolerates reference-format files: any missing
     * attribute falls back to a default (unknown planet names keep their
     * rail state, missing trueAnomaly skips the orbit restore, ships without
     * Connections are re-welded by attach-point overlap). When no XML save
     * exists, the legacy JSON save (save/world.json) is read once as a
     * migration path. Returns true if anything was loaded.
     */
    public boolean load() {
        FileHandle f = saveFile();
        if (!f.exists()) return loadLegacyJson();
        try {
            FlameFx.reset(); // drop exhaust particles from whatever ran before
            String text = f.readString("UTF-8");
            if (!text.isEmpty() && text.charAt(0) == '﻿') text = text.substring(1);
            XmlReader.Element root = new XmlReader().parse(text);
            if (!"Runtime".equals(root.getName())) return loadLegacyJson();

            // reset the live world (same discipline as the JSON loader)
            for (Ship s : new ArrayList<>(ships)) s.destroy();
            ships.clear();
            active = null;
            wtCount = 0;
            wtImpact = false;
            steerPrimed = false;
            saveTimer = 0;

            time = getNumAttr(root, "time", 0);
            // planet rail states from trueAnomaly (parentless / nan / unknown
            // names are skipped; empty-name nodes from modded files too)
            XmlReader.Element nodes = root.getChildByName("Nodes");
            List<XmlReader.Element> shipNodes = new ArrayList<>();
            if (nodes != null) {
                for (int i = 0; i < nodes.getChildCount(); i++) {
                    XmlReader.Element n = nodes.getChild(i);
                    if ("PlanetNode".equals(n.getName())) {
                        String nm = n.getAttribute("name", "");
                        double nu = getNumAttr(n, "trueAnomaly", Double.NaN);
                        Planet p = findPlanet(nm);
                        if (p != null && !Double.isNaN(nu)) {
                            planetSetV0FromTrueAnomaly(p, nu, time);
                        }
                    } else if ("ShipNode".equals(n.getName())) {
                        shipNodes.add(n);
                    }
                }
            }
            sun.updateRails(time);

            // first pass: parse ships into records (universe frame resolved
            // per ship against its node planet)
            class RPart {
                String typeId; int id; double x, y, angle, angleV; int rot;
                boolean deployed; double fuel = -1; int group;
                boolean fx, fy;
            }
            class RShip {
                int id; Planet planet; String name = "Ship"; int currentStage;
                double x, y, vx, vy; double throttle;
                List<RPart> parts = new ArrayList<>();
                List<int[]> conns = new ArrayList<>(); // {parentId, childId}
            }
            List<RShip> records = new ArrayList<>();
            for (XmlReader.Element sn : shipNodes) {
                RShip rs = new RShip();
                rs.id = (int) getNumAttr(sn, "id", 0);
                rs.planet = findPlanet(sn.getAttribute("planet", ""));
                if (rs.planet == null) rs.planet = nearestPlanetTo(origin.x, origin.y);
                if (rs.planet == null && !planets.isEmpty()) rs.planet = planets.get(0);
                rs.x = getNumAttr(sn, "x", 0);
                rs.y = getNumAttr(sn, "y", rs.planet != null ? rs.planet.radius + 10 : 0);
                rs.vx = getNumAttr(sn, "vx", 0);
                rs.vy = getNumAttr(sn, "vy", 0);
                XmlReader.Element sh = sn.getChildByName("Ship");
                XmlReader.Element partsEl = sh != null ? sh.getChildByName("Parts") : null;
                if (partsEl == null) { records.add(rs); continue; }
                // staging: step index (1-based) -> part ids, per Pod element
                Map<Integer, Integer> stageOf = new java.util.HashMap<>();
                for (int i = 0; i < partsEl.getChildCount(); i++) {
                    XmlReader.Element pe = partsEl.getChild(i);
                    if (!"Part".equals(pe.getName())) continue;
                    RPart rp = new RPart();
                    rp.typeId = pe.getAttribute("partType", "");
                    rp.id = (int) getNumAttr(pe, "id", 0);
                    rp.x = getNumAttr(pe, "x", rs.x);
                    rp.y = getNumAttr(pe, "y", rs.y);
                    rp.angle = getNumAttr(pe, "angle", 0);
                    rp.angleV = getNumAttr(pe, "angleV", 0);
                    rp.rot = (int) getNumAttr(pe, "editorAngle", 0);
                    boolean act = getNumAttr(pe, "activated", 0) > 0.5;
                    boolean ext = pe.getAttribute("extension", null) != null
                            && getNumAttr(pe, "extension", 0) > 0;
                    rp.deployed = act || ext;
                    rp.fx = getNumAttr(pe, "flippedX", 0) != 0;
                    rp.fy = getNumAttr(pe, "flippedY", 0) != 0;
                    XmlReader.Element tank = pe.getChildByName("Tank");
                    XmlReader.Element eng = pe.getChildByName("Engine");
                    if (tank != null) rp.fuel = getNumAttr(tank, "fuel", -1);
                    else if (eng != null) rp.fuel = getNumAttr(eng, "fuel", -1);
                    XmlReader.Element pod = pe.getChildByName("Pod");
                    if (pod != null) {
                        rs.name = pod.getAttribute("name", rs.name);
                        if (rs.name.isEmpty()) rs.name = "Ship";
                        rs.throttle = getNumAttr(pod, "throttle", 0);
                        XmlReader.Element stg = pod.getChildByName("Staging");
                        if (stg != null) {
                            rs.currentStage = Math.max(rs.currentStage,
                                    (int) getNumAttr(stg, "currentStage", 0));
                            for (int si = 0; si < stg.getChildCount(); si++) {
                                XmlReader.Element step = stg.getChild(si);
                                if (!"Step".equals(step.getName())) continue;
                                for (int ai = 0; ai < step.getChildCount(); ai++) {
                                    XmlReader.Element av = step.getChild(ai);
                                    if (!"Activate".equals(av.getName())) continue;
                                    stageOf.put((int) getNumAttr(av, "Id", 0), si + 1);
                                }
                            }
                        }
                    }
                    rs.parts.add(rp);
                }
                for (RPart rp : rs.parts) {
                    Integer g = stageOf.get(rp.id);
                    if (g != null) rp.group = g;
                }
                XmlReader.Element connsEl = sh.getChildByName("Connections");
                if (connsEl != null) {
                    for (int i = 0; i < connsEl.getChildCount(); i++) {
                        XmlReader.Element ce = connsEl.getChild(i);
                        if (!"Connection".equals(ce.getName())) continue;
                        // {parentPartId, childPartId, parentAttach(0-based, -1
                        // = unknown), childAttach}; XML numbering is 1-based
                        rs.conns.add(new int[] {
                                (int) getNumAttr(ce, "parentPart", -1),
                                (int) getNumAttr(ce, "childPart", -1),
                                (int) getNumAttr(ce, "parentAttachPoint", 0) - 1,
                                (int) getNumAttr(ce, "childAttachPoint", 0) - 1 });
                    }
                }
                records.add(rs);
            }

            // frame: anchor origin on the active ship's node position and let
            // frameVel carry its universe velocity (planet-relative + planet)
            int activeId = (int) getNumAttr(root, "shipId", -1);
            RShip activeRec = null;
            for (RShip rs : records) if (rs.id == activeId) { activeRec = rs; break; }
            if (activeRec == null && !records.isEmpty()) activeRec = records.get(0);
            if (activeRec != null && activeRec.planet != null) {
                origin.set(activeRec.planet.pos.x + activeRec.x,
                        activeRec.planet.pos.y + activeRec.y);
                frameVel.set(activeRec.planet.vel.x + activeRec.vx,
                        activeRec.planet.vel.y + activeRec.vy);
            } else {
                origin.set(0, 0);
                frameVel.set(0, 0);
            }

            // second pass: instantiate ships/parts/joints
            for (RShip rs : records) {
                if (rs.planet == null) continue;
                Ship s = new Ship(this);
                s.name = rs.name;
                s.currentStage = rs.currentStage;
                s.origin.set(origin);
                double uvx = rs.planet.vel.x + rs.vx;
                double uvy = rs.planet.vel.y + rs.vy;
                Map<Integer, Part> byId = new java.util.HashMap<>();
                for (RPart rp : rs.parts) {
                    PartType t = PartList.get(rp.typeId);
                    if (t == null) continue;
                    ShipDesign.DesignPart dp = new ShipDesign.DesignPart(t.id, 0, 0, rp.rot);
                    dp.flippedX = rp.fx;   // Part ctor copies these; collider
                    dp.flippedY = rp.fy;   // verts + attach defs mirror off them
                    Part p = new Part(t, s, dp);
                    double ux = rs.planet.pos.x + rp.x;
                    double uy = rs.planet.pos.y + rp.y;
                    p.createBody((float) (ux - origin.x), (float) (uy - origin.y), 0);
                    p.body.setTransform(p.body.getPosition(), (float) rp.angle);
                    p.body.setLinearVelocity((float) (uvx - frameVel.x), (float) (uvy - frameVel.y));
                    p.body.setAngularVelocity((float) rp.angleV);
                    if (rp.fuel >= 0) p.setFuel(rp.fuel);
                    p.deployed = rp.deployed;
                    p.group = rp.group;
                    p.updateMass();
                    s.parts.add(p);
                    byId.put(rp.id, p);
                }
                // onLoad BEFORE welding (per-part joint overrides must resolve first)
                for (Part p : s.parts) p.callOnLoad();
                if (!rs.conns.isEmpty()) {
                    for (int[] c : rs.conns) {
                        Part a = byId.get(c[0]), b = byId.get(c[1]);
                        if (a != null && b != null && a != b) s.weldAt(a, c[2], b, c[3]);
                    }
                } else if (s.parts.size() > 1) {
                    s.connectOverlaps(); // no Connections in file: re-weld by overlap
                }
                ships.add(s);
                if (rs == activeRec) {
                    active = s;
                    inputThrottle = Math.max(0, Math.min(1, rs.throttle));
                }
            }
            if (active == null && !ships.isEmpty()) active = ships.get(0);
            if (active != null) setActive(active);
            return !ships.isEmpty();
        } catch (Exception e) {
            Gdx.app.error("save", "failed to load world", e);
            return false;
        }
    }

    private static double getNumAttr(XmlReader.Element e, String name, double def) {
        try {
            String v = e.getAttribute(name, null);
            if (v == null) return def;
            double d = Double.parseDouble(v.trim());
            return d;
        } catch (Throwable t) {
            return def;
        }
    }

    /** First planet with this exact name (null/empty-safe). */
    private Planet findPlanet(String name) {
        if (name == null || name.isEmpty()) return null;
        for (Planet p : planets) {
            if (name.equals(p.name)) return p;
        }
        return null;
    }

    /** Legacy JSON save (pre-round-26); read-only migration fallback. */
    private boolean loadLegacyJson() {
        try {
            FileHandle f = legacySaveFile();
            if (!f.exists()) return false;
            FlameFx.reset(); // drop exhaust particles from whatever ran before
            Json.JObj root = Json.parse(f.readString());
            // round 20 item 5: REPLACE the live world, never append to it —
            // Continue Sandbox used to add the saved ships on top of whatever
            // was already flying (launch -> Menu -> Continue duplicated the
            // ship in place). Destroy every existing ship (bodies + joints)
            // and reset per-ship run state before restoring.
            for (Ship s : new ArrayList<>(ships)) s.destroy();
            ships.clear();
            active = null;
            wtCount = 0;
            wtImpact = false;
            steerPrimed = false;
            saveTimer = 0;
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
        try {
            FileHandle f = legacySaveFile();
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
