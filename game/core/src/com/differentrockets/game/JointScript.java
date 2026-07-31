package com.differentrockets.game;

import com.badlogic.gdx.Gdx;

import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.jse.CoerceJavaToLua;

/**
 * Bridges weld-joint resolution to mod/joints.lua (round 11 item 6). For
 * EVERY connection the script's
 *   jointParams(partA, attachPointA, partB, attachPointB)
 *       -> {frequencyHz=…, dampingRatio=…, angularDamping=…, breakForce=…|nil}
 * is consulted first; attach points are passed as small tables
 * {x, y, fuelLine, edge, breakForce}. Any error or a missing script falls
 * back to the built-in rule (per-part overrides, stiffer side wins) —
 * identical to pre-round-11 behavior. Hot-reloads with the file.
 */
public final class JointScript {

    private static final LuaScript script = new LuaScript("joints.lua");
    private static Globals lastSeen;
    private static boolean callFailed;

    private JointScript() {}

    public static void invalidate() { script.invalidate(); }

    /** Resolved joint parameters; fromLua marks the Lua path for smoke tests. */
    public static final class Params {
        public float frequencyHz;
        public float dampingRatio;
        /** angular spring rate override (round 33b); NaN = use frequencyHz. */
        public double angularFrequencyHz = Double.NaN;
        /** explicit viscous bushing ratio (round 33b); NaN = physics.lua/default. */
        public double angularDampingRatio = Double.NaN;
        /** explicit LINEAR viscous bushing ratio (round 34); NaN = physics.lua/default. */
        public double linearDampingRatio = Double.NaN;
        public float breakForce = -1f;   // <0 = caller default (min of attach points)
        /** round 34 task 2: torque break limit (kN*m); <0 = caller default. */
        public float breakTorque = -1f;
        public boolean fromLua;
    }

    /**
     * Ask joints.lua for the connection's params. Returns false when the
     * caller should use the built-in rule (script missing/broken/error).
     */
    public static boolean resolve(Part a, PartType.AttachPoint apA,
                                  Part b, PartType.AttachPoint apB, Params out) {
        Globals g = script.globals();
        if (g == null) return false;
        if (g != lastSeen) { lastSeen = g; callFailed = false; }
        if (callFailed) return false;
        LuaValue fn = g.get("jointParams");
        if (!fn.isfunction()) return false;
        try {
            Varargs r = fn.invoke(new LuaValue[]{
                    CoerceJavaToLua.coerce(a.api), attachTable(apA),
                    CoerceJavaToLua.coerce(b.api), attachTable(apB)});
            LuaValue t = r.arg1();
            if (!t.istable()) return false;
            out.frequencyHz = (float) t.get("frequencyHz")
                    .optdouble(PhysicsScript.jointParam("frequencyHz"));
            out.dampingRatio = (float) t.get("dampingRatio")
                    .optdouble(PhysicsScript.jointParam("dampingRatio"));
            out.breakForce = (float) t.get("breakForce").optdouble(-1.0);
            out.breakTorque = (float) t.get("breakTorque").optdouble(-1.0);
            out.angularFrequencyHz = t.get("angularFrequencyHz").optdouble(Double.NaN);
            out.angularDampingRatio = t.get("angularDampingRatio").optdouble(Double.NaN);
            out.linearDampingRatio = t.get("linearDampingRatio").optdouble(Double.NaN);
            double angDamp = t.get("angularDamping").optdouble(Double.NaN);
            if (!Double.isNaN(angDamp)) {
                // per-connection angular damping applies to both bodies
                if (a.body != null) a.body.setAngularDamping((float) angDamp);
                if (b.body != null) b.body.setAngularDamping((float) angDamp);
            }
            out.fromLua = true;
            return true;
        } catch (LuaError e) {
            if (!callFailed) {
                callFailed = true;
                Gdx.app.error("joints.lua", "jointParams error (built-in rule takes over): "
                        + e.getMessage());
            }
            return false;
        }
    }

    private static LuaTable attachTable(PartType.AttachPoint ap) {
        LuaTable t = new LuaTable();
        t.set("x", ap.x);
        t.set("y", ap.y);
        t.set("fuelLine", LuaValue.valueOf(ap.fuelLine));
        t.set("edge", ap.edge);
        if (ap.breakForce != Float.MAX_VALUE) t.set("breakForce", ap.breakForce);
        return t;
    }
}
