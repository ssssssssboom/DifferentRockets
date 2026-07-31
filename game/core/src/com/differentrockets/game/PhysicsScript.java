package com.differentrockets.game;

import com.badlogic.gdx.Gdx;
import com.differentrockets.util.Vec2d;

import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.jse.CoerceJavaToLua;

/**
 * Bridges the physics laws to mod/physics.lua (item 8c). Exposed Lua API:
 *   gravityAccel(x, y, timeSec) -> ax, ay     (universe coords, m/s^2)
 *   atmosphereDensity(planetName, altitude) -> kg/m^3
 *   steering = { kp = .., ki = .. }           (PI steering gains, item 4)
 * The script sees a live `world` proxy (planet positions/mu) and a static
 * `planetEnv` table (atmosphere parameters per planet name). Any Lua error
 * disables the script (logged once) and the built-in laws take over.
 *
 * Performance note: gravityAccel runs per part per physics tick in Lua. At
 * current part counts (tens of parts) that is fine; if profiling ever shows
 * a problem, batch per ship per frame instead and pass arrays.
 */
public final class PhysicsScript {

    /** Live view of the world's planets, coerced into the Lua state. */
    public static class WorldProxy {
        private final GameWorld world;
        public WorldProxy(GameWorld world) { this.world = world; }
        public int planetCount() { return world.planets.size(); }
        public String planetName(int i) { return world.planets.get(i).name; }
        public double planetX(int i) { return world.planets.get(i).pos.x; }
        public double planetY(int i) { return world.planets.get(i).pos.y; }
        public double planetMu(int i) { return world.planets.get(i).mu(); }
        public double planetRadius(int i) { return world.planets.get(i).radius; }
    }

    private static final LuaScript script = new LuaScript("physics.lua");
    private static Globals bound;          // globals the env was injected into
    private static boolean callFailed;

    private PhysicsScript() {}

    public static void invalidate() { script.invalidate(); }

    /** (Re)inject planet tables when the script reloaded or the world changed. */
    public static void ensureBound(GameWorld world) {
        Globals g = script.globals();
        if (g == null) { bound = null; return; }
        if (g == bound) return;
        bound = g;
        callFailed = false;
        try {
            g.set("world", CoerceJavaToLua.coerce(new WorldProxy(world)));
            LuaTable env = new LuaTable();
            for (Planet p : world.planets) {
                LuaTable e = new LuaTable();
                e.set("atmoHeight", p.atmoHeight);
                e.set("surfacePressure", p.surfacePressure);
                e.set("scaleHeight", p.scaleHeight());
                env.set(p.name, e);
            }
            g.set("planetEnv", env);
            Gdx.app.log("physics.lua", "planet tables injected (" + world.planets.size() + " bodies)");
        } catch (LuaError e) {
            Gdx.app.error("physics.lua", "bind failed: " + e.getMessage());
            bound = null;
        }
    }

    /** Gravity from Lua; returns false when the built-in law should be used. */
    public static boolean gravity(double x, double y, double timeSec, Vec2d out) {
        Globals g = bound;
        if (g == null || callFailed) return false;
        LuaValue fn = g.get("gravityAccel");
        if (!fn.isfunction()) return false;
        try {
            Varargs r = fn.invoke(new LuaValue[]{
                    LuaValue.valueOf(x), LuaValue.valueOf(y), LuaValue.valueOf(timeSec)});
            out.set(r.arg1().todouble(), r.arg(2).todouble());
            return true;
        } catch (LuaError e) {
            fail("gravityAccel", e);
            return false;
        }
    }

    /** Atmosphere density from Lua; NaN means use the built-in model. */
    public static double density(String planetName, double altitude) {
        Globals g = bound;
        if (g == null || callFailed) return Double.NaN;
        LuaValue fn = g.get("atmosphereDensity");
        if (!fn.isfunction()) return Double.NaN;
        try {
            LuaValue r = fn.call(LuaValue.valueOf(planetName), LuaValue.valueOf(altitude));
            return r.todouble();
        } catch (LuaError e) {
            fail("atmosphereDensity", e);
            return Double.NaN;
        }
    }

    /** Steering PI gains from Lua (`steering = {kp=.., ki=..}`), with defaults. */
    public static double steeringGain(String key, double def) {
        Globals g = bound;
        if (g == null) return def;
        LuaValue t = g.get("steering");
        if (!t.istable()) return def;
        LuaValue v = t.get(key);
        return v.isnumber() ? v.todouble() : def;
    }

    // Round 35 (SimpleRockets model, SR APK ARM disassembly verified):
    // built-in joint defaults are RIGID welds — frequencyHz 0 — with NO
    // explicit damping anywhere. The "soft / wobbly" feel comes from the
    // 6/2-iteration solver being under-converged at the fixed 1/60 step, not
    // from springs or dampers. The damping keys stay editable (debug only)
    // but default to 0; breakAngle (rad) is the SR angle-deviation break
    // channel (|current - weld-time angle diff| > threshold, single frame).
    private static final double DEF_JOINT_FREQ = 0.0, DEF_JOINT_DAMP = 1.0,
            DEF_ANG_DAMP = 0.0, DEF_ANG_VISC = 0.0, DEF_LIN_VISC = 0.0,
            DEF_BREAK_ANGLE = 0.6;

    /**
     * Weld-joint parameters from Lua (`joints = {frequencyHz=.., dampingRatio=..,
     * angularDamping=.., angularFrequencyHz=.., angularDampingRatio=..,
     * linearDampingRatio=.., breakAngle=..}`), falling back to the tuned
     * defaults per key.
     */
    public static double jointParam(String key) {
        double def = "frequencyHz".equals(key) ? DEF_JOINT_FREQ
                : "dampingRatio".equals(key) ? DEF_JOINT_DAMP
                : "angularFrequencyHz".equals(key) ? DEF_JOINT_FREQ
                : "linearDampingRatio".equals(key) ? DEF_LIN_VISC
                : "breakAngle".equals(key) ? DEF_BREAK_ANGLE
                : "angularDampingRatio".equals(key) ? DEF_ANG_VISC : DEF_ANG_DAMP;
        Globals g = bound;
        if (g == null) return def;
        LuaValue t = g.get("joints");
        if (!t.istable()) return def;
        LuaValue v = t.get(key);
        return v.isnumber() ? v.todouble() : def;
    }

    /**
     * Generic reader for numeric entries in physics.lua tables, e.g.
     * part:physicsNumber("gimbal", "kp") reads `gimbal = { kp = ... }`.
     * Falls back to the built-in default for that section.key.
     */
    public static double tableNumber(String section, String key) {
        double def = "gimbal".equals(section) ? gimbalDefault(key)
                : "joints".equals(section) ? jointDefault(key) : 0;
        Globals g = bound;
        if (g == null) return def;
        LuaValue t = g.get(section);
        if (!t.istable()) return def;
        LuaValue v = t.get(key);
        return v.isnumber() ? v.todouble() : def;
    }

    /** Weld-joint defaults; keep in sync with jointParam's DEF_* constants. */
    private static double jointDefault(String key) {
        switch (key) {
            case "frequencyHz": return DEF_JOINT_FREQ;
            case "dampingRatio": return DEF_JOINT_DAMP;
            case "angularDamping": return DEF_ANG_DAMP;
            case "angularFrequencyHz": return DEF_JOINT_FREQ;
            case "angularDampingRatio": return DEF_ANG_VISC;
            case "linearDampingRatio": return DEF_LIN_VISC;
            case "breakAngle": return DEF_BREAK_ANGLE;
            default: return 0;
        }
    }

    /** Engine gimbal PID actuator defaults (editable via `gimbal` in physics.lua). */
    private static double gimbalDefault(String key) {
        switch (key) {
            case "kp": return 8.0;
            case "ki": return 0.1;
            case "kd": return 0.6;
            case "maxRateDeg": return 90.0;
            default: return 0;
        }
    }

    private static void fail(String fn, LuaError e) {
        if (!callFailed) {
            callFailed = true;
            Gdx.app.error("physics.lua", fn + " error (built-in laws take over): " + e.getMessage());
        }
    }
}
