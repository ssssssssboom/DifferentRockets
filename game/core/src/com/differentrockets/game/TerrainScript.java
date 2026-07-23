package com.differentrockets.game;

import com.badlogic.gdx.Gdx;

import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.VarArgFunction;

/**
 * Bridges planet terrain generation to mod/terrain.lua (item 8b). Lua API:
 *   terrainHeight(planetName, angleRad) -> heightMeters
 * The script receives a static `planetInfo` table (minHeight/maxHeight/noise/
 * ranges per planet name) and deterministic noise helpers:
 *   noise.value1(x, period, seed)  seam-free 1D value noise in [-1,1]
 *   noise.value2(x, y, seed)       2D value noise in [-1,1]
 *   noise.hash(string)             Java-compatible string hash (planet seeds)
 * Both the visual chunk meshes and the collision heightfield call through the
 * same function (Planet.heightAt), so they always match. On any Lua error the
 * built-in generator takes over (logged once).
 */
public final class TerrainScript {

    private static final LuaScript script = new LuaScript("terrain.lua");
    private static Globals bound;
    private static boolean callFailed;

    private TerrainScript() {}

    public static void invalidate() { script.invalidate(); }

    /** (Re)inject planetInfo + noise helpers when the script reloaded. */
    public static void ensureBound(GameWorld world) {
        ensureBound(world.planets);
    }

    /** List-based entry (also usable without a full GameWorld/GL context). */
    public static void ensureBound(java.util.List<Planet> planets) {
        Globals g = script.globals();
        if (g == null) { bound = null; return; }
        if (g == bound) return;
        bound = g;
        callFailed = false;
        try {
            LuaTable info = new LuaTable();
            for (Planet p : planets) {
                LuaTable e = new LuaTable();
                e.set("name", p.name);
                e.set("radius", p.radius); // surfaceHeight works in arc meters
                e.set("minHeight", p.minHeight);
                e.set("maxHeight", p.maxHeight);
                e.set("noise", p.noise);
                LuaTable ranges = new LuaTable();
                for (int i = 0; i < p.ranges.size(); i++) {
                    Planet.Range r = p.ranges.get(i);
                    LuaTable re = new LuaTable();
                    re.set("startAngle", r.startDeg);
                    re.set("endAngle", r.endDeg);
                    re.set("minHeight", r.minH);
                    re.set("maxHeight", r.maxH);
                    ranges.set(i + 1, re);
                }
                e.set("ranges", ranges);
                info.set(p.name, e);
            }
            g.set("planetInfo", info);

            LuaTable noise = new LuaTable();
            noise.set("value1", new VarArgFunction() {
                @Override public Varargs invoke(Varargs a) {
                    return LuaValue.valueOf(valueNoise1(a.arg(1).todouble(),
                            a.arg(2).todouble(), a.arg(3).todouble()));
                }
            });
            noise.set("value2", new VarArgFunction() {
                @Override public Varargs invoke(Varargs a) {
                    return LuaValue.valueOf(valueNoise2(a.arg(1).todouble(),
                            a.arg(2).todouble(), a.arg(3).todouble()));
                }
            });
            noise.set("hash", new VarArgFunction() {
                @Override public Varargs invoke(Varargs a) {
                    return LuaValue.valueOf(a.arg1().checkjstring().hashCode());
                }
            });
            g.set("noise", noise);
            Gdx.app.log("terrain.lua", "planetInfo injected (" + planets.size() + " bodies)");
        } catch (LuaError e) {
            Gdx.app.error("terrain.lua", "bind failed: " + e.getMessage());
            bound = null;
        }
    }

    /** Terrain height from Lua; NaN means use the built-in generator. */
    public static double heightAt(String planetName, double angleRad) {
        Globals g = bound;
        if (g == null || callFailed) return Double.NaN;
        LuaValue fn = g.get("terrainHeight");
        if (!fn.isfunction()) return Double.NaN;
        try {
            return fn.call(LuaValue.valueOf(planetName), LuaValue.valueOf(angleRad)).todouble();
        } catch (LuaError e) {
            if (!callFailed) {
                callFailed = true;
                Gdx.app.error("terrain.lua", "terrainHeight error (built-in generator takes over): "
                        + e.getMessage());
            }
            return Double.NaN;
        }
    }

    /**
     * Columnar surface function (round 18): surfaceHeight(info, xArcMeters)
     * -> ABSOLUTE radius in meters (R + terrain height). Drives both the
     * render mesh and the collision quads in TerrainSystem, which caches the
     * results per junction, so this is only called for NEW junctions. NaN
     * means use the built-in generator (via terrainHeight / Planet.heightAt).
     */
    public static double surfaceHeight(String planetName, double xArcMeters) {
        Globals g = bound;
        if (g == null || callFailed) return Double.NaN;
        LuaValue fn = g.get("surfaceHeight");
        if (!fn.isfunction()) return Double.NaN;
        try {
            LuaValue info = g.get("planetInfo").get(planetName);
            return fn.call(info, LuaValue.valueOf(xArcMeters)).todouble();
        } catch (LuaError e) {
            if (!callFailed) {
                callFailed = true;
                Gdx.app.error("terrain.lua", "surfaceHeight error (built-in generator takes over): "
                        + e.getMessage());
            }
            return Double.NaN;
        }
    }

    /**
     * Height above the nominal radius at a surface ANGLE (round 18 fix):
     * routes through surfaceHeight so EVERY gameplay surface query
     * (altimeter, spawn pad, rails floors, water) agrees with the columnar
     * collision/render terrain — including specialTerrains regions, which
     * the legacy terrainHeight path does NOT know about (a ship could sit on
     * an invisible plane at the natural height above/below the visible
     * special terrain). NaN = caller falls back to terrainHeight/built-in.
     */
    public static double heightAboveDatum(String planetName, double angleRad) {
        Globals g = bound;
        if (g == null || callFailed) return Double.NaN;
        LuaValue fn = g.get("surfaceHeight");
        if (!fn.isfunction()) return Double.NaN;
        try {
            LuaValue info = g.get("planetInfo").get(planetName);
            if (!info.istable()) return Double.NaN;
            double radius = info.get("radius").optdouble(Double.NaN);
            if (Double.isNaN(radius) || radius <= 0) return Double.NaN;
            double abs = fn.call(info, LuaValue.valueOf(angleRad * radius)).todouble();
            return abs - radius;
        } catch (LuaError e) {
            if (!callFailed) {
                callFailed = true;
                Gdx.app.error("terrain.lua", "surfaceHeight error (built-in generator takes over): "
                        + e.getMessage());
            }
            return Double.NaN;
        }
    }

    /** Identity token of the loaded terrain.lua globals (changes on hot-reload). */
    public static Object loadedToken() { return script.globals(); }

    // ---------------- deterministic noise (mirrors Planet's built-in generator) ----------------

    private static double hash(double i, double seed) {
        double x = Math.sin(i * 127.1 + seed * 311.7) * 43758.5453;
        return x - Math.floor(x);
    }

    /** Seam-free 1D value noise in [-1,1]; lattice wraps with the given integer period. */
    public static double valueNoise1(double x, double period, double seed) {
        double xi = Math.floor(x);
        double xf = x - xi;
        double u = xf * xf * (3 - 2 * xf);
        double i0 = ((xi % period) + period) % period;
        double i1 = (i0 + 1) % period;
        double a = hash(i0, seed), b = hash(i1, seed);
        return (a + (b - a) * u) * 2 - 1;
    }

    /** 2D value noise in [-1,1] with smooth interpolation. */
    public static double valueNoise2(double x, double y, double seed) {
        double xi = Math.floor(x), yi = Math.floor(y);
        double xf = x - xi, yf = y - yi;
        double u = xf * xf * (3 - 2 * xf), v = yf * yf * (3 - 2 * yf);
        double n00 = hash(xi * 157.31 + yi * 113.97, seed);
        double n10 = hash((xi + 1) * 157.31 + yi * 113.97, seed);
        double n01 = hash(xi * 157.31 + (yi + 1) * 113.97, seed);
        double n11 = hash((xi + 1) * 157.31 + (yi + 1) * 113.97, seed);
        double top = n00 + (n10 - n00) * u;
        double bot = n01 + (n11 - n01) * u;
        return (top + (bot - top) * v) * 2 - 1;
    }
}
