package com.differentrockets.game;

import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.physics.box2d.Body;
import com.badlogic.gdx.physics.box2d.BodyDef;
import com.badlogic.gdx.physics.box2d.FixtureDef;
import com.badlogic.gdx.physics.box2d.Joint;
import com.badlogic.gdx.physics.box2d.MassData;
import com.badlogic.gdx.physics.box2d.PolygonShape;
import com.differentrockets.game.PartType.AttachPoint;

import org.luaj.vm2.Globals;

import java.util.ArrayList;
import java.util.List;

/**
 * A single rocket part as an independent Box2D dynamic body.
 * Behaviors are driven by its per-instance Lua script (ModManager).
 */
public class Part {
    public final PartType type;
    public Ship ship;
    public final ShipDesign.DesignPart design;
    public Body body;
    public final ModApi api;
    public Globals lua;

    public double fuel;              // current fuel units (tank parts / SRB / battery)
    public boolean deployed;         // parachute / lander state
    public boolean stageActivatedThisFrame;

    /** Lua-overridable absolute drag coefficient (NaN = use type.drag adjustment). */
    public double dragCd = Double.NaN;

    /** Lua-overridable drag reference area in m^2 (NaN = use type width). */
    public double dragArea = Double.NaN;

    /**
     * Per-part weld-joint overrides set via Lua part:setJointParams{...}
     * (NaN = inherit physics.lua `joints` table / Java defaults). When two
     * parts are welded, the override with the HIGHER frequencyHz wins (the
     * stiffer side rules the connection) and its dampingRatio comes along.
     */
    public double jointFreqHz = Double.NaN;
    public double jointDampRatio = Double.NaN;
    /** Per-part override of the body's Box2D angular damping. */
    public double jointAngDamp = Double.NaN;

    /**
     * Aerodynamic exposure of this part, 0..1 (1 = fully exposed to the
     * airflow). Recomputed by Ship.updateDragExposure from a raycast
     * occlusion sweep against the ship's other parts; GameWorld multiplies
     * the drag force by it. Scripts may read it via part:getDragExposure().
     */
    public float dragExposure = 1f;

    /** activation group 0 = none, 1..8 (item 6); ACTIVATE fires the whole group. */
    public int group;

    // flame fx for this frame (set via Lua emitFlame)
    public float flameLevel;         // 0 = none, ~1 = full
    public float flameGimbalDeg;

    /**
     * ACTUAL gimbal deflection (deg), driven per physics tick by the engine
     * Lua's PID actuator (round 9): the target is turnCommand*turnDeg, the
     * PID + rate limiter moves this toward the target, and thrust uses THIS
     * value — so gimbal lag/overshoot is physically real. Not persisted in
     * saves; it re-converges within a fraction of a second after load.
     */
    public float gimbalDeg;

    public float angleOffset;        // design rotation applied (radians)

    public Part(PartType type, Ship ship, ShipDesign.DesignPart design) {
        this.type = type;
        this.ship = ship;
        this.design = design;
        this.api = new ModApi(this);
        if (type.tank != null) this.fuel = type.tank.fuel;
    }

    /** Create the physics body at ship-local position (ox,oy) with the ship's rotation. */
    public void createBody(float ox, float oy, float shipAngle) {
        BodyDef bd = new BodyDef();
        bd.type = BodyDef.BodyType.DynamicBody;
        // CCD vs the static terrain blocks (round 11 item 7): at impact speeds
        // (500+ m/s = 8+ m per 1/60 step) non-bullet bodies tunneled straight
        // through the ground.
        bd.bullet = true;
        angleOffset = (float) (design.rot * Math.PI / 2.0);
        bd.position.set(ox, oy);
        bd.angle = shipAngle + angleOffset;
        bd.angularDamping = (float) (!Double.isNaN(jointAngDamp)
                ? jointAngDamp : PhysicsScript.jointParam("angularDamping"));
        bd.linearDamping = 0.0f;
        body = ship.world.boxWorld.createBody(bd);
        body.setUserData(this);

        List<PartType.ShapeDef> shapes = type.shapes;
        if (shapes.isEmpty()) {
            PartType.ShapeDef sd = new PartType.ShapeDef();
            float hw = type.width / 2f, hh = type.height / 2f;
            sd.verts.add(new PartType.Vertex(-hw, -hh));
            sd.verts.add(new PartType.Vertex(hw, -hh));
            sd.verts.add(new PartType.Vertex(hw, hh));
            sd.verts.add(new PartType.Vertex(-hw, hh));
            shapes = new ArrayList<>();
            shapes.add(sd);
        }
        for (PartType.ShapeDef sd : shapes) {
            int n = Math.min(sd.verts.size(), 8);
            Vector2[] vs = new Vector2[n];
            for (int i = 0; i < n; i++) {
                vs[i] = new Vector2(sd.verts.get(i).x, sd.verts.get(i).y);
            }
            PolygonShape ps = new PolygonShape();
            ps.set(vs);
            FixtureDef fd = new FixtureDef();
            fd.shape = ps;
            fd.density = 1f;
            // round 13 item 1a: Box2D mixes contact friction as sqrt(fA*fB).
            // With the old 0.4 part default, terrain friction was nearly
            // irrelevant (sqrt(0.4*1.0)=0.63 — the ship slid on any slope no
            // matter how high the owner raised terrain friction in lua).
            // Floor part fixtures at 1.5 so landing contact actually grips.
            fd.friction = Math.max(1.5f, type.friction);
            // round 13 item 1b: zero bounce on landing (was 0.05 — that plus
            // the 1.2 m spawn-drop made the ship hop after spawn).
            fd.restitution = 0.0f;
            fd.isSensor = sd.sensor;
            body.createFixture(fd);
            ps.dispose();
        }
        updateMass();
    }

    /** Set mass from definition (tank mass varies with fuel). */
    public void updateMass() {
        if (body == null) return;
        double kg = type.massKg();
        if (type.tank != null) {
            kg = type.tank.dryMassTons * 1000.0 + fuel;
        }
        MassData md = new MassData();
        md.mass = (float) Math.max(0.05, kg);
        // moment of inertia: approximate as box
        float w = Math.max(0.2f, type.width), h = Math.max(0.2f, type.height);
        md.I = md.mass * (w * w + h * h) / 12f;
        md.center.set(0, 0);
        body.setMassData(md);
    }

    public double getFuel() { return fuel; }
    public double getFuelCapacity() { return type.tank != null ? type.tank.fuel : 0; }
    public int getFuelType() { return type.tank != null ? type.tank.fuelType : -1; }

    public void setFuel(double v) {
        fuel = Math.max(0, Math.min(getFuelCapacity(), v));
        updateMass();
    }

    /**
     * Destroy joints connecting this part, honoring the detacher's MODE
     * (detacher-*.lua, round 26 item B2):
     *   MODE 1 — sever ALL joints of this part (classic behavior);
     *   MODE 2 — sever only the joint on the FIRST (parent) attach point
     *            (default), leaving the ring welded to the lower stage.
     * The mode is read from the part script's global `MODE` (Lua locals are
     * not visible to Java, hence a global). DEFERRED to a post-callback
     * queue — detacher onStage runs while callers iterate part lists, and
     * splitting the ship inline there corrupts those iterations.
     */
    public void detachJoints() {
        final Part self = this;
        final int mode = detachMode();
        ship.world.deferStructure(() -> {
            // the part may already have been moved/destroyed by an earlier op
            if (self.body != null && self.ship != null && self.ship.parts.contains(self)) {
                if (mode == 1) self.ship.removeJointsOf(self);
                else self.ship.removeParentJointOf(self);
            }
        });
    }

    /** Detach mode from the Lua global MODE (1 or 2); default 2 when unset/unreadable. */
    private int detachMode() {
        if (lua != null) {
            try {
                return lua.get("MODE").optint(2) == 1 ? 1 : 2;
            } catch (Throwable ignored) {}
        }
        return 2;
    }

    public void emitFlame(float size, float gimbalDeg) {
        flameLevel = Math.max(flameLevel, size);
        flameGimbalDeg = gimbalDeg;
    }

    public void callOnLoad() {
        if (lua == null) lua = ModManager.createState(type.id);
        if (lua != null) ModManager.callHook(lua, "onLoad", api, 0);
    }

    public void callOnUpdate(double dt) {
        if (lua != null) ModManager.callHook(lua, "onUpdate", api, dt);
    }

    public void callOnStage() {
        stageActivatedThisFrame = true;
        if (lua != null) ModManager.callHook(lua, "onStage", api, 0);
    }

    public void clearFrameFlags() {
        stageActivatedThisFrame = false;
        flameLevel = 0f;
    }

    public void destroyBody() {
        if (body != null) {
            ship.world.boxWorld.destroyBody(body);
            body = null;
        }
    }

    // ---------- attach points in world space ----------
    public List<Vector2> attachWorldPositions() {
        List<Vector2> out = new ArrayList<>();
        if (body == null) return out;
        for (AttachPoint ap : type.attach) {
            // body.getWorldPoint already applies the FULL body transform
            // (ship angle + design rotation) — rotating the local point by
            // angleOffset first double-rotated 90°-parts and welded them at
            // wrong offsets (round 11 item 3).
            // NOTE: new gdx-box2d binding returns a shared Vector2 — copy it
            Vector2 wp = new Vector2(body.getWorldPoint(new Vector2(ap.x, ap.y)));
            out.add(wp);
        }
        return out;
    }

    /** World-space attach segment (item 5): endpoints of the attach edge. */
    public void attachWorldSegment(int index, Vector2 outA, Vector2 outB) {
        Attach.localSegment(type, type.attach.get(index), outA, outB);
        outA.set(body.getWorldPoint(outA));
        outB.set(body.getWorldPoint(outB));
    }

    public List<AttachPoint> attachDefs() { return type.attach; }
}
