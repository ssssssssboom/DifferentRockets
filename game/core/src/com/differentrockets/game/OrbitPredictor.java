package com.differentrockets.game;

import com.differentrockets.util.Vec2d;

import java.util.List;

/**
 * Map-view orbit prediction (item 10): numerically propagates a ship under
 * ALL planets' gravity — the same ΣGM/r² law as GameWorld.gravityAt, with the
 * same 0.5·radius singularity clamp — while the planets themselves advance
 * along their Kepler rails during the propagation, so SOI exits and long
 * parent-frame cruises stay truthful.
 *
 * Integrator: velocity-Verlet with an adaptive step sized to a fraction of
 * the local dynamical time tau = sqrt(r³/mu) of the nearest body (clamped to
 * [0.5 s, 40000 s]), which keeps low-orbit arcs accurate and interplanetary
 * cruises cheap. Up to MAX_STEPS points are produced; callers decimate to
 * their GL point budget. Propagation stops early on surface impact or when
 * the ship leaves the system (r_sun > 2e12 m).
 */
public class OrbitPredictor {

    public static final int MAX_STEPS = 4200;

    public final double[] xs = new double[MAX_STEPS + 1];
    public final double[] ys = new double[MAX_STEPS + 1];
    /**
     * Per-point anchor position (round 15): fx/fy are ALWAYS the position of
     * ONE body — the anchor, the nearest body at propagation start — at the
     * point's time. The map draws xs[i]-fx[i]+anchor.posNow, i.e. the raw
     * inertial path translated into the anchor's current frame. This is
     * continuous BY CONSTRUCTION: the old per-point nearest-body anchoring
     * subtracted a different frame velocity on either side of every SOI
     * transition (a spurious kink of up to ~110° at 20000 s steps) and
     * zig-zagged whenever the nearest body alternated between samples.
     * frame[] still records the informational nearest body per point.
     */
    public final double[] fx = new double[MAX_STEPS + 1];
    public final double[] fy = new double[MAX_STEPS + 1];
    /**
     * Per-point SUN position (round 16, map frame toggle): same idea as
     * fx/fy but anchored to the sun, so the map can draw the path in a
     * sun-fixed frame without re-evaluating Kepler rails at draw time.
     */
    public final double[] sfx = new double[MAX_STEPS + 1];
    public final double[] sfy = new double[MAX_STEPS + 1];
    /** Absolute universe time per point (world.time + accumulated dt). */
    public final double[] ts = new double[MAX_STEPS + 1];
    /** Informational nearest body per point (not used for drawing anymore). */
    public final int[] frame = new int[MAX_STEPS + 1];
    /** The single draw anchor: index into GameWorld.planets (-1 = none). */
    public int anchor = -1;
    /** Sun index into GameWorld.planets (the parentless body; -1 = none). */
    public int sun = -1;
    /** Unused since round 15 (single-frame drawing); kept for binary shape. */
    public final double[] offx = new double[MAX_STEPS + 1];
    public final double[] offy = new double[MAX_STEPS + 1];
    public int count;            // valid points in xs/ys
    public double simSeconds;    // total propagated time
    public boolean impacted;     // stopped at a planet surface

    // per-planet scratch (positions at the current eval time)
    private double[] px, py, pmu, prad;
    private int[] pidx;          // parent index per planet (-1 for the sun)
    private int n;
    private double ax, ay;       // accel scratch from accelAt

    /**
     * Propagate the ship from its current universe state. Results land in
     * xs/ys[0..count). Safe to call with any ship; count = 0 when there is
     * nothing to propagate.
     */
    public void compute(GameWorld world, Ship ship) {
        compute(world, ship, -1);
    }

    /**
     * Round 17 (map anchor list): `anchorIdx` picks the draw-frame body
     * (index into world.planets); -1 (or an invalid/massless index) falls
     * back to the automatic dominant body — the nearest body at propagation
     * start, the round-15 behavior. fx/fy then track THAT body, so switching
     * the anchor needs nothing but a recompute (the caller re-propagates at
     * ~4 Hz anyway and forces an immediate one on selection).
     */
    public void compute(GameWorld world, Ship ship, int anchorIdx) {
        count = 0;
        simSeconds = 0;
        impacted = false;
        sun = -1;
        if (world == null || ship == null || ship.parts.isEmpty()) return;

        List<Planet> planets = world.planets;
        bindPlanets(planets);

        Vec2d sp = ship.getUniversePos();
        Vec2d sv = ship.getUniverseVel();
        double x = sp.x, y = sp.y;
        double vx = sv.x, vy = sv.y;
        double t = world.time;

        xs[0] = x; ys[0] = y;
        ts[0] = t;
        count = 1;

        systemAt(planets, t);
        accelAt(x, y);
        // round 17: a valid explicit anchor wins; otherwise the automatic
        // dominant body (nearest at propagation start)
        anchor = (anchorIdx >= 0 && anchorIdx < n && pmu[anchorIdx] > 0)
                ? anchorIdx : nearestBody(x, y);
        recordFrame(0, x, y);
        offx[0] = 0; offy[0] = 0;

        for (int step = 0; step < MAX_STEPS; step++) {
            // adaptive step: fraction of the nearest body's dynamical time
            double dt = adaptiveDt(x, y);

            // velocity-Verlet kick-drift-kick (planets advance mid-step)
            double nx = x + vx * dt + 0.5 * ax * dt * dt;
            double ny = y + vy * dt + 0.5 * ay * dt * dt;
            double pax = ax, pay = ay;
            t += dt;
            systemAt(planets, t);
            accelAt(nx, ny);
            vx += 0.5 * (pax + ax) * dt;
            vy += 0.5 * (pay + ay) * dt;
            x = nx; y = ny;

            xs[count] = x; ys[count] = y;
            ts[count] = t;
            recordFrame(count, x, y);
            count++;

            // stop on surface impact
            boolean hit = false;
            for (int i = 0; i < n; i++) {
                if (pmu[i] <= 0) continue;
                double dx = x - px[i], dy = y - py[i];
                if (dx * dx + dy * dy < prad[i] * prad[i]) { hit = true; break; }
            }
            if (hit) { impacted = true; break; }
            // escaped the system — enough
            if (x * x + y * y > 4e24) break; // r_sun > 2e12 m
        }
        simSeconds = t - world.time;
    }

    /** Shared planet-table setup for compute() and computeWarp(). */
    private void bindPlanets(List<Planet> planets) {
        n = planets.size();
        if (px == null || px.length < n) {
            px = new double[n]; py = new double[n];
            pmu = new double[n]; prad = new double[n];
            pidx = new int[n];
        }
        for (int i = 0; i < n; i++) {
            Planet p = planets.get(i);
            pmu[i] = p.mu();
            prad[i] = p.radius;
            pidx[i] = -1;
            if (p.parent != null) {
                // flatten() is parent-first, so the parent is always earlier
                for (int j = i - 1; j >= 0; j--) {
                    if (planets.get(j) == p.parent) { pidx[i] = j; break; }
                }
            }
            if (pidx[i] < 0 && sun < 0) sun = i; // the parentless body = sun
        }
    }

    /**
     * Warp-trajectory propagation (round 19 super-warp rewrite): the SAME
     * velocity-Verlet + same ΣGM/r² gravity + same adaptive-dt rule as the
     * map prediction (compute) — same source, so the flown path and the map
     * line cannot diverge — but it ALSO records per-point velocities into
     * caller-owned arrays (sized `cap`, all five the same length). dtScale
     * stretches the adaptive dt (high-warp interplanetary cruise tolerates
     * 4x). Runs until the arrays fill, surface impact, or system escape.
     * Returns the point count (0 when there is nothing to propagate);
     * `impacted` flags a surface-impact end. Unlike compute()'s datum-circle
     * test, impact is checked against the REAL terrain surface (radius +
     * heightAt) so the hand-back point sits on the ground, not inside a
     * mountain — the two lines differ only in the final approach meters.
     */
    public int computeWarp(GameWorld world, Ship ship, double dtScale,
                           double[] wx, double[] wy, double[] wvx, double[] wvy, double[] wt) {
        if (world == null || ship == null || ship.parts.isEmpty()) return 0;
        Vec2d sp = ship.getUniversePos();
        Vec2d sv = ship.getUniverseVel();
        return computeWarp(world, sp.x, sp.y, sv.x, sv.y, dtScale, wx, wy, wvx, wvy, wt);
    }

    /** Raw-state entry (probes/tests); the Ship overload delegates here. */
    public int computeWarp(GameWorld world, double x0, double y0, double vx0, double vy0,
                           double dtScale,
                           double[] wx, double[] wy, double[] wvx, double[] wvy, double[] wt) {
        impacted = false;
        warpHitBody = -1;
        if (world == null) return 0;
        List<Planet> planets = world.planets;
        bindPlanets(planets);

        double x = x0, y = y0, vx = vx0, vy = vy0;
        double t = world.time;

        wx[0] = x; wy[0] = y; wvx[0] = vx; wvy[0] = vy; wt[0] = t;
        int cnt = 1;
        systemAt(planets, t);
        accelAt(x, y);

        int cap = wx.length;
        int hitBody = -1;
        for (int step = 0; step < cap - 1; step++) {
            double dt = adaptiveDt(x, y) * dtScale;
            // velocity-Verlet kick-drift-kick (planets advance mid-step)
            double nx = x + vx * dt + 0.5 * ax * dt * dt;
            double ny = y + vy * dt + 0.5 * ay * dt * dt;
            double pax = ax, pay = ay;
            t += dt;
            systemAt(planets, t);
            accelAt(nx, ny);
            vx += 0.5 * (pax + ax) * dt;
            vy += 0.5 * (pay + ay) * dt;
            x = nx; y = ny;

            wx[cnt] = x; wy[cnt] = y; wvx[cnt] = vx; wvy[cnt] = vy; wt[cnt] = t;
            cnt++;

            // stop on REAL surface impact (terrain-aware, see javadoc)
            boolean hit = false;
            for (int i = 0; i < n; i++) {
                if (pmu[i] <= 0) continue;
                double dx = x - px[i], dy = y - py[i];
                double r2 = dx * dx + dy * dy;
                Planet pl = planets.get(i);
                double rim = prad[i] + Math.max(0, pl.maxHeight);
                if (r2 < rim * rim) {
                    double surf = pl.radius + pl.heightAt(Math.atan2(dy, dx));
                    if (r2 < surf * surf) { hit = true; hitBody = i; break; }
                }
            }
            if (hit) {
                impacted = true;
                // round 19 (moon-crash fix): the raw impact point is already
                // INSIDE the ground — the adaptive step near the surface is
                // several seconds, so the first subsurface sample can be many
                // km deep, and super-warp used to hand THAT to physics (no
                // terrain colliders loaded yet inside a mountain). Truncate
                // the trajectory at the last sample still clearing the
                // surface by a speed-scaled margin, so the hand-back happens
                // in open air with time for colliders (10 Hz, +-10 km window)
                // to appear before the final approach.
                Planet hb = planets.get(hitBody);
                double vi = Math.hypot(vx - hb.vel.x, vy - hb.vel.y); // impact speed, planet-relative
                double margin = Math.max(2000.0, 2.0 * vi);
                while (cnt > 2) {
                    int j = cnt - 1;
                    double dx = wx[j] - px[hitBody], dy = wy[j] - py[hitBody];
                    double surf = hb.radius
                            + hb.heightAt(Math.atan2(dy, dx));
                    double alt = Math.hypot(dx, dy) - surf;
                    if (alt >= margin) break;
                    cnt--;
                }
                warpHitBody = hitBody;
                break;
            }
            // escaped the system — enough
            if (x * x + y * y > 4e24) break; // r_sun > 2e12 m
        }
        return cnt;
    }

    /** Body index of the warp-trajectory impact (-1 when none). */
    public int warpHitBody = -1;

    /** Adaptive dt from the nearest body's local dynamical time. */
    private double adaptiveDt(double x, double y) {
        int bi = nearestBody(x, y);
        if (bi < 0) return 60;
        double dx = x - px[bi], dy = y - py[bi];
        double br = Math.sqrt(dx * dx + dy * dy);
        double tau = Math.sqrt(br * br * br / pmu[bi]); // local orbital timescale
        // 0.004·tau ≈ 250 pts/orbit; ~1 s near a Smearth-like surface, so
        // short ballistic hops still produce a dense arc before impact
        double dt = 0.004 * tau;
        if (dt < 0.05) dt = 0.05;
        if (dt > 20000) dt = 20000;
        return dt;
    }

    /** Nearest body by surface distance (same rule as GameWorld.currentPlanet). */
    private int nearestBody(double x, double y) {
        double bestAlt = Double.MAX_VALUE;
        int best = -1;
        for (int i = 0; i < n; i++) {
            if (pmu[i] <= 0) continue;
            double dx = x - px[i], dy = y - py[i];
            double alt = Math.sqrt(dx * dx + dy * dy) - prad[i];
            if (alt < bestAlt) { bestAlt = alt; best = i; }
        }
        return best;
    }

    /** Store the informational nearest body + the anchor/sun positions for point i. */
    private void recordFrame(int i, double x, double y) {
        frame[i] = nearestBody(x, y);
        if (anchor >= 0) { fx[i] = px[anchor]; fy[i] = py[anchor]; }
        if (sun >= 0) { sfx[i] = px[sun]; sfy[i] = py[sun]; }
    }

    /** ΣGM/r² gravity at (x, y) using the planet positions cached by systemAt. */
    private void accelAt(double x, double y) {
        double gx = 0, gy = 0;
        for (int i = 0; i < n; i++) {
            if (pmu[i] <= 0) continue;
            double dx = px[i] - x, dy = py[i] - y;
            double r2 = dx * dx + dy * dy;
            double r = Math.sqrt(r2);
            double rmin = prad[i] * 0.5;
            if (r < rmin) r = rmin; // mirror GameWorld.gravityAt
            double a = pmu[i] / (r2 * r);
            gx += a * dx;
            gy += a * dy;
        }
        ax = gx; ay = gy;
    }

    /** Evaluate every planet's universe position at absolute time t (Kepler rails). */
    private void systemAt(List<Planet> planets, double t) {
        for (int i = 0; i < n; i++) {
            Planet p = planets.get(i);
            if (pidx[i] < 0) { px[i] = 0; py[i] = 0; continue; }
            double muP = pmu[pidx[i]];
            double nn = Math.sqrt(muP / (p.a * p.a * p.a));
            double M = nn * t + p.v0;
            if (!p.prograde) M = -M;
            // round 14: wrap M to [-pi, pi] + better seed (see Planet.solveKepler)
            M = (M + Math.PI) % (2 * Math.PI);
            if (M < 0) M += 2 * Math.PI;
            M -= Math.PI;
            // Kepler's equation (same Newton iteration as Planet.solveKepler)
            double E = M + p.e * Math.sin(M);
            for (int k = 0; k < 12; k++) {
                E = E - (E - p.e * Math.sin(E) - M) / (1 - p.e * Math.cos(E));
            }
            double xp = p.a * (Math.cos(E) - p.e);
            double yp = p.a * Math.sqrt(1 - p.e * p.e) * Math.sin(E);
            double cw = Math.cos(p.w), sw = Math.sin(p.w);
            px[i] = px[pidx[i]] + xp * cw - yp * sw;
            py[i] = py[pidx[i]] + xp * sw + yp * cw;
        }
    }
}
