package com.differentrockets.game;

import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.physics.box2d.Fixture;
import com.badlogic.gdx.physics.box2d.Joint;
import com.badlogic.gdx.physics.box2d.RayCastCallback;
import com.badlogic.gdx.physics.box2d.joints.WeldJointDef;
import com.differentrockets.util.Json;
import com.differentrockets.util.Vec2d;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A rocket: a set of independent part bodies connected by spring-damper weld
 * joints. Owns its fuel network, stage list, and universe position. Inactive
 * ships far from the active one are put on rails (bodies deactivated, simple
 * gravity integration) but remain in the world and are switchable.
 */
public class Ship {

    /** Last resolved weld params (smoke-test diagnostics for per-part overrides). */
    public static float debugLastWeldHz = -1, debugLastWeldDamp = -1;

    /**
     * Round 39 floor for the explicit angular spring implemented per weld
     * (applyJointDampers): lua frequencyHz=0 means "SR rigid" intent, but this
     * gdx-box2d build's weld has no angular constraint at 0 Hz (pin joint —
     * MAR.xml top stack folded 0.6 rad in 17 s and tore). 12 Hz / zeta 0.5
     * keep a heavy stack angularly honest while staying inside the explicit
     * integration stability bound (zeta*omega*PHYS_DT <= 0.5, capped in weld()).
     */
    private static final double ANG_FLOOR_HZ = Double.parseDouble(System.getProperty("dr.angFloorHz", "6.0"));
    private static final double ANG_FLOOR_ZETA = Double.parseDouble(System.getProperty("dr.angFloorZeta", "1.0"));

    public static class Link {
        public Joint joint;
        public Joint jointPin; // see comment at weld(): revolute+motor angular lock
        /** round 40c: revolute + max-torque motor at the same anchor — this
         *  build's weld joint has NO angular constraint at any frequencyHz
         *  (probe: 5/15/30 Hz traces byte-identical), so stacks bend on the
         *  pins under thrust and the thrust-line fanning torque tumbles the
         *  ascent (probe 41, vacuum). A revolute motor with motorSpeed=0 is
         *  a SOLVER-side angular velocity lock (implicit, in-step): exactly
         *  the SR rigid weld angular channel. Linear DOF stays with the
         *  weld joint (rigid 2x2, works); the revolute's own pin duplicates
         *  it (same equations, harmless redundancy). */
        public Part a, b;
        public boolean fuelEdge;
        public float breakForce = Float.MAX_VALUE;
        /** round 34 task 2: reaction-TORQUE break limit (kN*m); MAX = unbreakable. */
        public float breakTorque = Float.MAX_VALUE;
        /** index of each side's attach point in its part's attachDefs (-1 = unknown). */
        public int attachIndexA = -1, attachIndexB = -1;
        /**
         * Round 35 (SimpleRockets model): the two bodies' angle difference
         * (a.angle - b.angle) recorded at weld time. checkJointBreaks breaks
         * the link the moment the LIVE difference deviates from this by more
         * than breakAngle — SR's angle channel, single-frame criterion.
         */
        public float initialAngleDiff;
        /**
         * Angle-deviation break threshold in radians (SR default ~0.6).
         * From joints.lua breakAngle or the built-in default.
         */
        public float breakAngle = 0.6f;
        /**
         * Round 33b (angular-viscous bushing): TRUE viscous damping torque
         * applied per substep against the relative angular velocity of the
         * welded pair. Box2D's soft-weld damping is a constraint "magic
         * formula" (gamma = h(d+h*k)) that saturates and cannot decay the
         * structural bending mode of a heavy stack (probe 21: >3 s ringing at
         * any dampingRatio 1.0-1.6); this explicit damper actually removes
         * mode energy. cVisc = angularDampingRatio * 2 * omega_ang * I_red.
         */
        public float cVisc;
        /**
         * Round 34 task 2 (linear-viscous bushing): viscous damping FORCE at
         * the weld anchor against the relative LINEAR velocity of the two
         * anchor points. The soft weld's linear channel is a rigid 2x2
         * constraint with no damping at all, so cross-part impact energy
         * (landing, squeeze) rattles forever; this bushing bleeds it.
         * cViscLin = linearDampingRatio * 2 * omega_ang * m_red.
         */
        public float cViscLin;
        /**
         * Round 39 explicit angular SPRING stiffness (N*m/rad) across this
         * weld — this build's weld joint has no working angular constraint
         * (see weld()), so the Hooke term k*(deviation) is applied per substep
         * in applyJointDampers, floored at ANG_FLOOR_HZ even for 0 Hz "rigid"
         * (SR) lua params.
         */
        public float kSpring;
        /** cached world anchor (set at weld time; joints are rigidly placed). */
        public final com.badlogic.gdx.math.Vector2 anchor = new com.badlogic.gdx.math.Vector2();
        /** body-local copies of the weld anchor, refreshed to world space per
         *  substep by applyJointDampers — the cached WORLD anchor goes stale
         *  the moment the ship moves, and stale lever arms turn the linear
         *  bushing into an energy pump (probe 39e: pad tip-over oscillation). */
        public final com.badlogic.gdx.math.Vector2 localAnchorA = new com.badlogic.gdx.math.Vector2();
        public final com.badlogic.gdx.math.Vector2 localAnchorB = new com.badlogic.gdx.math.Vector2();
    }

    public final GameWorld world;
    public final List<Part> parts = new ArrayList<>();
    public final List<Link> links = new ArrayList<>();

    /**
     * Recorded parent part per child (from connection records / save XML, see
     * weldAt). Geometric fallback welds and legacy saves leave this empty —
     * detachers then fall back to the attach-index<=0 parent rule.
     */
    public final Map<Part, Part> parentOf = new IdentityHashMap<>();
    /**
     * Throttle frozen at the moment this ship was cut loose (round 27):
     * a detached stage keeps burning at the throttle it had at separation;
     * later player throttle moves only affect the ACTIVE ship. -1 = follow
     * the live player throttle (initial value; latched on split / ship
     * switch). Read by ModApi.getThrottle.
     */
    public double latchedThrottle = -1;
    /**
     * Legacy design stage list (design-part indices). Still loaded/saved for
     * backward compatibility, but STAGE activation is group-number based now
     * (see activateStage); this no longer drives anything.
     */
    public final List<List<Integer>> stages = new ArrayList<>();
    /** Last stage NUMBER fired (group-based staging, round 26); 0 = none. */
    public int currentStage = 0;

    /** universe position of this ship's local frame origin */
    public final Vec2d origin = new Vec2d();
    /** universe velocity of the local frame (zero for the active ship) */
    public final Vec2d originVel = new Vec2d();
    public boolean onRails = false;
    public boolean landed = false;
    /**
     * round 34: parked by velocityReanchor while touching kinematic terrain at
     * a frame-relative speed above the fold threshold (bodies deactivated,
     * whole frame-relative velocity carried in originVel). Wakes when
     * |originVel| drops below GameWorld.WAKE_VEL. Cleared on rails transitions
     * and when the player switches to this ship.
     */
    public boolean groundParked = false;
    /**
     * Launch-pad hold (round 39, MAR.xml birth semantics, SR-aligned): set by
     * GameWorld.launchShip after grounding. Bodies are DEACTIVATED — no
     * gravity application, no contacts, no joint-break checks — so a freshly
     * spawned rocket stands motionless exactly like SR's pad spawn, instead
     * of micro-settling into the contact solver. Wakes on the first player
     * action (throttle / turn / stage activation).
     */
    public boolean padHold = false;
    /** substeps of post-wake vertical guidance (round 39c, see GameWorld.substep). */
    public int wakeSettle = 0;

    /** Wake from launch-pad hold (round 39): re-activate all bodies. */
    public void wakePadHold() {
        if (!padHold) return;
        wakeSettle = Integer.parseInt(System.getProperty("dr.wakeSettle", "60"));
        padHold = false;
        setBodiesActive(true);
    }
    public String name = "Ship";

    private final Vec2d tmp = new Vec2d();
    private int id;
    private static int nextId = 1;

    public Ship(GameWorld world) {
        this.world = world;
        this.id = nextId++;
        this.name = "Ship-" + id;
    }

    /**
     * Round 41 (owner directive): the same-ship negative Box2D collision
     * group was REMOVED. In SR, non-mated parts of the same ship DO collide
     * (the crush push-apart system exists for exactly those contacts); the
     * group made clipped designs ghost through each other instead. Mated
     * weld pairs remain contact-free via collideConnected=false; spawn
     * integrity is guarded by padHold + crush + disconnect semantics.
     */

    // ---------------------------------------------------------------- build

    /** Instantiate parts (bodies created at design positions, rotated by spawnAngle). */
    public void buildFromDesign(ShipDesign d, float spawnAngle) {
        // design index -> runtime Part (null for unknown types, which are skipped)
        List<Part> byDesign = new ArrayList<>();
        for (ShipDesign.DesignPart dp : d.parts) {
            PartType t = PartList.get(dp.typeId);
            if (t == null) { byDesign.add(null); continue; }
            Part p = new Part(t, this, dp);
            p.group = dp.group;
            // rotate design offset by spawn angle
            float c = (float) Math.cos(spawnAngle), s = (float) Math.sin(spawnAngle);
            float ox = dp.x * c - dp.y * s;
            float oy = dp.x * s + dp.y * c;
            p.createBody(ox, oy, spawnAngle);
            // round 40h fix: baseline is the part's ACTUAL built body angle
            // (createBody also applies the design 90deg-step rotation), not
            // the ship spawn rotation.
            p.spawnAngle = p.body.getAngle();
            parts.add(p);
            byDesign.add(p);
        }
        for (List<Integer> st : d.stages) stages.add(new ArrayList<>(st));
        // onLoad BEFORE welding (round 9 item 1): part scripts set per-part
        // joint overrides (setJointParams) in onLoad — welds must resolve
        // against those, not against the global defaults.
        for (Part p : parts) p.callOnLoad();
        // round 27: connection-record-driven welding (Show_Rocket Connections).
        // The editor records exactly which attach points mate; weld those.
        // Designs without records (old saves, hand-built) fall back to the
        // geometric overlap sweep.
        if (!d.connections.isEmpty()) {
            for (ShipDesign.Connection c : d.connections) {
                if (c.partA < 0 || c.partA >= byDesign.size()
                        || c.partB < 0 || c.partB >= byDesign.size()) continue;
                Part a = byDesign.get(c.partA), b = byDesign.get(c.partB);
                if (a == null || b == null || a == b) continue;
                weldAt(a, c.attachA, b, c.attachB);
            }
            // a design whose records ALL failed (e.g. every part type missing)
            // still needs structure — fall back if nothing got welded
            if (links.isEmpty() && parts.size() > 1) connectAttachPoints();
        } else {
            connectAttachPoints();
        }
    }

    /**
     * Weld two parts at SPECIFIC attach indices: the anchor is the midpoint of
     * the closest pair between the two attach segments (same contact rule as
     * the geometric sweep), and the weld params resolve against those exact
     * attach defs. Out-of-range indices fall back to nearest-attach welding.
     */
    public void weldAt(Part a, int ai, Part b, int bi) {
        if (ai < 0 || bi < 0 || ai >= a.attachDefs().size() || bi >= b.attachDefs().size()) {
            weldLoaded(a, b);
            return;
        }
        Vector2 a1 = new Vector2(), a2 = new Vector2();
        Vector2 b1 = new Vector2(), b2 = new Vector2();
        Vector2 cA = new Vector2(), cB = new Vector2();
        a.attachWorldSegment(ai, a1, a2);
        b.attachWorldSegment(bi, b1, b2);
        Attach.closestBetweenSegments(a1, a2, b1, b2, cA, cB);
        Vector2 anchor = new Vector2((cA.x + cB.x) / 2f, (cA.y + cB.y) / 2f);
        weld(a, b, anchor, a.attachDefs().get(ai), b.attachDefs().get(bi));
        // Connection records are parent->child (ShipDesign.Connection: partA =
        // parent; save XML parentPart/childPart): remember the recorded parent
        // so detachers sever EXACTLY the parent-side link regardless of which
        // attach index the parent happens to be mated to (flipped detachers).
        if (!parentOf.containsKey(b)) parentOf.put(b, a);
    }

    /**
     * Public wrapper for connectAttachPoints: re-weld by attach-point overlap
     * (sandbox XML loader fallback for saves without a Connections list).
     */
    public void connectOverlaps() { connectAttachPoints(); }

    /** Connect overlapping attach points with spring-damper weld joints. */
    private void connectAttachPoints() {        float threshold = 0.35f;
        Vector2 a1 = new Vector2(), a2 = new Vector2();
        Vector2 b1 = new Vector2(), b2 = new Vector2();
        Vector2 cA = new Vector2(), cB = new Vector2();
        for (int i = 0; i < parts.size(); i++) {
            Part pa = parts.get(i);
            int na = pa.attachDefs().size();
            for (int j = i + 1; j < parts.size(); j++) {
                Part pb = parts.get(j);
                int nb = pb.attachDefs().size();
                float best = Float.MAX_VALUE;
                int bestA = -1, bestB = -1;
                float bestAX = 0, bestAY = 0, bestBX = 0, bestBY = 0;
                for (int ai = 0; ai < na; ai++) {
                    pa.attachWorldSegment(ai, a1, a2);
                    for (int bi = 0; bi < nb; bi++) {
                        // edge-aware contact (round 11 item 5): an edge-type
                        // attach point matches anywhere along its segment, so
                        // rotated parts weld even when only edge CENTERS would
                        // have been near under the old point-to-point rule.
                        pb.attachWorldSegment(bi, b1, b2);
                        float dst = Attach.closestBetweenSegments(a1, a2, b1, b2, cA, cB);
                        if (dst < best) {
                            best = dst; bestA = ai; bestB = bi;
                            bestAX = cA.x; bestAY = cA.y; bestBX = cB.x; bestBY = cB.y;
                        }
                    }
                }
                if (bestA >= 0 && best < threshold) {
                    // weld anchor at the contact midpoint between the segments
                    Vector2 anchor = new Vector2((bestAX + bestBX) / 2f, (bestAY + bestBY) / 2f);
                    weld(pa, pb, anchor, pa.attachDefs().get(bestA), pb.attachDefs().get(bestB));
                }
            }
        }
    }

    /** "lua" when joints.lua resolved the last weld, "fallback" for the built-in rule. */
    public static String debugLastWeldSource = "";

    private void weld(Part a, Part b, Vector2 worldAnchor,
                      PartType.AttachPoint apA, PartType.AttachPoint apB) {
        if (Boolean.getBoolean("dr.nojoints")) return; // debug toggle
        WeldJointDef jd = new WeldJointDef();
        jd.initialize(a.body, b.body, worldAnchor);
        // spring-damper joints transmitting forces (owner requirement);
        // parameters editable in mod/joints.lua (round 11 item 6). When the
        // script is missing/broken, fall back to the built-in rule: per-part
        // overrides (Lua part:setJointParams, round 9) where the override
        // with the HIGHER frequencyHz wins — the stiffer side rules the
        // connection — and its dampingRatio comes along.
        JointScript.Params jp = new JointScript.Params();
        float breakForce, breakTorque;
        if (JointScript.resolve(a, apA, b, apB, jp)) {
            jd.frequencyHz = jp.frequencyHz;
            jd.dampingRatio = jp.dampingRatio;
            // round 33b: this Box2D build's soft weld is linearly RIGID and
            // angularly SOFT, so frequencyHz is effectively the angular spring
            // rate; joints.lua may name it explicitly via angularFrequencyHz.
            if (!Double.isNaN(jp.angularFrequencyHz)) jd.frequencyHz = (float) jp.angularFrequencyHz;
            breakForce = jp.breakForce > 0 ? jp.breakForce : Math.min(apA.breakForce, apB.breakForce);
            breakTorque = jp.breakTorque > 0 ? jp.breakTorque : Math.min(apA.breakTorque, apB.breakTorque);
            debugLastWeldSource = "lua";
        } else {
            Part stiff = null;
            if (!Double.isNaN(a.jointFreqHz)
                    && (Double.isNaN(b.jointFreqHz) || a.jointFreqHz >= b.jointFreqHz)) {
                stiff = a;
            } else if (!Double.isNaN(b.jointFreqHz)) {
                stiff = b;
            }
            jd.frequencyHz = (float) (stiff != null
                    ? stiff.jointFreqHz : PhysicsScript.jointParam("frequencyHz"));
            jd.dampingRatio = (float) (stiff != null && !Double.isNaN(stiff.jointDampRatio)
                    ? stiff.jointDampRatio : PhysicsScript.jointParam("dampingRatio"));
            breakForce = Math.min(apA.breakForce, apB.breakForce);
            breakTorque = Math.min(apA.breakTorque, apB.breakTorque);
            debugLastWeldSource = "fallback";
        }
        // debug toggle (probe): force every weld's frequency, e.g. -Dr.weldHz=0
        String hzOv = System.getProperty("dr.weldHz");
        if (hzOv != null) {
            try { jd.frequencyHz = Float.parseFloat(hzOv); } catch (NumberFormatException ignore) {}
        }
        String dampOv = System.getProperty("dr.weldDamp");
        if (dampOv != null) {
            try { jd.dampingRatio = Float.parseFloat(dampOv); } catch (NumberFormatException ignore) {}
        }
        debugLastWeldHz = jd.frequencyHz;
        debugLastWeldDamp = jd.dampingRatio;
        jd.collideConnected = false;
        Joint joint = world.boxWorld.createJoint(jd);
        // round 40c: the weld's angular channel is inert in this build (see
        // Link.jointPin), so add a revolute joint at the same anchor with a
        // max-torque 0-speed motor — a solver-side rigid angular lock (SR
        // weld semantics). Toggle off with -Ddr.noPin=1 for A/B probes.
        Joint pin = null;
        if ("1".equals(System.getProperty("dr.pin"))) {
            com.badlogic.gdx.physics.box2d.joints.RevoluteJointDef rd =
                    new com.badlogic.gdx.physics.box2d.joints.RevoluteJointDef();
            rd.initialize(a.body, b.body, worldAnchor);
            rd.collideConnected = false;
            rd.enableMotor = true;
            rd.motorSpeed = 0;
            rd.maxMotorTorque = 1e12f;
            pin = world.boxWorld.createJoint(rd);
        }
        Link l = new Link();
        l.joint = joint;
        l.jointPin = pin;
        l.a = a;
        l.b = b;
        l.fuelEdge = apA.fuelLine && apB.fuelLine;
        l.breakForce = breakForce;
        l.breakTorque = breakTorque;
        l.anchor.set(worldAnchor);
        l.localAnchorA.set(a.body.getLocalPoint(worldAnchor));
        l.localAnchorB.set(b.body.getLocalPoint(worldAnchor));
        l.attachIndexA = a.attachDefs().indexOf(apA);
        l.attachIndexB = b.attachDefs().indexOf(apB);
        // round 33b explicit viscous angular bushing across this weld. The
        // soft weld's own damping ("magic formula", gamma = h(d + h*k))
        // saturates and cannot decay the structural bending mode of a heavy
        // stack (probe 21: >3 s ringing at dampingRatio 1.0-1.6, worse with
        // higher ratio). Instead apply tau = -+cVisc*dw each substep with
        // cVisc = zeta * 2 * omega_ang * I_red (parallel stiffness k comes
        // from the joint spring, this damper only bleeds mode energy).
        double zeta = !Double.isNaN(jp.angularDampingRatio) ? jp.angularDampingRatio
                : PhysicsScript.jointParam("angularDampingRatio");
        String viscOv = System.getProperty("dr.weldVisc"); // debug toggle (probe)
        if (viscOv != null) {
            try { zeta = Double.parseDouble(viscOv); } catch (NumberFormatException ignore) {}
        }
        float iA = a.body.getInertia(), iB = b.body.getInertia();
        double iRed = (iA + iB) > 0 ? (double) iA * iB / (iA + iB) : 0;
        // round 34 task 2 linear bushing: same zeta form against anchor-point
        // relative LINEAR velocity, reduced MASS. Default 0 (probe matrix
        // decides whether it earns a nonzero default).
        double zetaLin = !Double.isNaN(jp.linearDampingRatio) ? jp.linearDampingRatio
                : PhysicsScript.jointParam("linearDampingRatio");
        String linOv = System.getProperty("dr.weldViscLin"); // debug toggle (probe)
        if (linOv != null) {
            try { zetaLin = Double.parseDouble(linOv); } catch (NumberFormatException ignore) {}
        }
        // Round 39 (MAR.xml birth-collapse root fix): this gdx-box2d build's
        // weld joint has NO effective angular constraint at frequencyHz=0 —
        // round 35's "SR rigid weld" therefore made every connection a PIN.
        // Probe evidence: MAR's top stack folds 0.6 rad in ~17 s on the pin
        // and tears via the angle channel; even a FREE-FALLING ship folds
        // (no contacts), and the fold rate is invariant to solver iterations
        // — pure inverted-pendulum-on-a-pin dynamics. So the angular spring
        // is implemented HERE, per substep (applyJointDampers): Hooke torque
        // k*dev with k = (2*pi*fEff)^2 * I_red, floored at ANG_FLOOR_HZ so a
        // lua 0 Hz ("rigid", SR intent) still gets real angular integrity.
        // The viscous damper is capped for explicit-integration stability
        // (zetaEff*omega*PHYS_DT <= 0.5 — probe: uncapped dampers detonate).
        double fEff = Math.max(jd.frequencyHz, ANG_FLOOR_HZ);
        double omega = 2 * Math.PI * fEff;
        l.kSpring = (float) (omega * omega * iRed);
        double zetaEff = Math.max(zeta, ANG_FLOOR_ZETA);
        // round 46 task 2: the zeta cap below dated from the EXPLICIT damper
        // (probe: uncapped explicit dampers detonate). The round-39b solver
        // is IMPLICIT Euler — unconditionally stable at any cVisc — so the
        // cap only strangled damping exactly where stiff springs need it
        // (12 Hz capped to zeta 0.40, 24 Hz to 0.20, 48 Hz to 0.10: MAR
        // rang itself apart in probe 46's sweep). Cap remains available via
        // -Ddr.zetaCap=<value> for explicit-integration experiments.
        double zetaCap = Double.parseDouble(System.getProperty("dr.zetaCap",
                String.valueOf(0.5 / (omega * GameWorld.PHYS_DT))));
        if (zetaEff > zetaCap) zetaEff = zetaCap;
        l.cVisc = (float) (zetaEff * 2.0 * omega * iRed);
        float mA = a.body.getMass(), mB = b.body.getMass();
        double mRed = (mA + mB) > 0 ? (double) mA * mB / (mA + mB) : 0;
        l.cViscLin = (float) (zetaLin * 2.0 * omega * mRed);
        // SR angle-break channel: remember the weld-time angle difference.
        l.initialAngleDiff = a.body.getAngle() - b.body.getAngle();
        // SR truth (libNativeModule PartConnection): angle threshold is
        // min(attachPoint A, attachPoint B); attachpoints without an explicit
        // breakAngle attribute have NO angle channel (never break by angle).
        // Per-joint lua override only; the lua GLOBAL default is NOT an SR
        // concept (SR reads thresholds from the two attachpoints alone), so
        // it must not arm the angle channel on plain tank/engine welds.
        if (!Double.isNaN(jp.breakAngle) && jp.breakAngle > 0) {
            l.breakAngle = (float) jp.breakAngle;
        } else {
            float deg = Math.min(apA.breakAngle, apB.breakAngle);
            l.breakAngle = deg >= 180f ? Float.MAX_VALUE
                    : (float) Math.toRadians(deg);
        }
        links.add(l);
    }

    /**
     * Round 39d: rigid angular weld lock (velocity level). Two Gauss-Seidel
     * sweeps over the link graph; each pass applies the perfectly inelastic
     * impulse that zeroes a pair's relative spin. Whole-ship rotation is
     * untouched (every body ends with the same w); bending is forbidden.
     */
    /**
     * Round 40h: inertia-weighted mean rotation of the whole ship since
     * build — the best available estimate of the SR rigid-body orientation
     * (this joint library cannot hold angles, so parts individually wander;
     * engine thrust uses spawnAngle + avgRotation as its baseline so a
     * flexed base cannot steer the thrust line, see ModApi.getRigidAngle).
     */
    public float avgRotation() {
        float iSum = 0, acc = 0;
        for (Part p : parts) {
            if (p.body == null) continue;
            float i = p.body.getInertia();
            if (i <= 0) continue;
            float d = p.body.getAngle() - p.spawnAngle;
            while (d > Math.PI) d -= 2 * Math.PI;
            while (d < -Math.PI) d += 2 * Math.PI;
            iSum += i;
            acc += i * d;
        }
        return iSum > 0 ? acc / iSum : 0;
    }

    /**
     * Round 40e/40f pairwise angular lock (RESTORED after probe 45). Round
     * 40i's full kinematic projection (rigid-frame velocity + BETA position
     * correction) was flight-tested in probe 45 and was CATASTROPHIC: in
     * vacuum it spun up to 1.55 rad/s and tore the ship apart (46 -> 3 parts,
     * flex 36 m) within 15 s of ignition, and in atmosphere it diverged to
     * 76 deg by t=40 s shedding parts, ending in a Box2D NPE crash. The
     * pairwise impulse pass below is the validated 40e/40f behavior: each
     * impulse zeroes a weld pair's RELATIVE spin, is momentum-neutral, and
     * leaves whole-ship rotation untouched.
     */
    public void applyWeldAngularLock() {
        for (int it = 0; it < 2; it++) {
            for (Link l : links) {
                if (!l.a.body.isActive() || !l.b.body.isActive()) continue;
                float dw = l.b.body.getAngularVelocity() - l.a.body.getAngularVelocity();
                if (Math.abs(dw) < 1e-6) continue;
                float ia = l.a.body.getInertia(), ib = l.b.body.getInertia();
                if (ia <= 0 || ib <= 0) continue;
                float j = (ia * ib / (ia + ib)) * dw;
                l.a.body.applyAngularImpulse(j, true);
                l.b.body.applyAngularImpulse(-j, true);
            }
        }
    }

    /**
     * once per physics SUBSTEP from GameWorld.substep, alongside the
     * thrust/frame forces. Each opposing pair of torques/forces is internal
     * to the welded pair (momentum-neutral).
     */
    public void applyJointDampers(float h) {
        // round 46 task 2: per-link implicit solves are Gauss-Seidel passes
        // over the weld chain; a single pass per substep leaves the chain's
        // global bending mode under-converged, which destabilises stiff
        // (>=12 Hz) springs on a 45-link stack (probe 46 sweep). Iterate the
        // angular pass; linear bushing stays single-pass.
        int angIters = Integer.parseInt(System.getProperty("dr.dampIters", "1"));
        for (int iter = 0; iter < angIters; iter++)
        for (Link l : links) {
            if (l.cVisc <= 0 && l.cViscLin <= 0 && l.kSpring <= 0) continue;
            if (!l.a.body.isActive() || !l.b.body.isActive()) continue;
            if (l.cVisc > 0 || l.kSpring > 0) {
                // round 39b: IMPLICIT per-link spring-damper solve on the
                // relative rotational coordinate. The old explicit torque
                // (probe 39e) was per-link stable (omega*h = 0.63 < 2*zeta)
                // but a 45-link chain doubles the top eigenfrequency and
                // forward Euler pumped it — the ship rocked itself over on
                // the pad. Implicit Euler on (dev, dw) is unconditionally
                // stable at any chain length:
                //   iRed*(dw'-dw)/h = -k*(dev + dw'*h) - c*dw'
                float dw = l.b.body.getAngularVelocity() - l.a.body.getAngularVelocity();
                float dev = (l.b.body.getAngle() - l.a.body.getAngle()) - l.initialAngleDiff;
                while (dev > Math.PI) dev -= 2 * Math.PI;
                while (dev < -Math.PI) dev += 2 * Math.PI;
                float ia = l.a.body.getInertia(), ib = l.b.body.getInertia();
                if (ia > 0 && ib > 0) {
                    float iRed = ia * ib / (ia + ib);
                    float den = iRed / h + l.kSpring * h + l.cVisc;
                    float dwp = (iRed * dw / h - l.kSpring * dev) / den;
                    float j = iRed * (dw - dwp); // = restoring torque * h
                    if (j != 0) {
                        l.a.body.applyAngularImpulse(j, true);
                        l.b.body.applyAngularImpulse(-j, true);
                    }
                }
            }
            if (l.cViscLin > 0) {
                if (iter > 0) continue; // linear bushing: single pass
                // fresh per-substep anchor positions (the weld-time world
                // anchor is stale once the ship moves/rotates)
                Vector2 wA = l.a.body.getWorldPoint(l.localAnchorA);
                Vector2 wB = l.b.body.getWorldPoint(l.localAnchorB);
                Vector2 va = l.a.body.getLinearVelocityFromWorldPoint(wA);
                Vector2 vb = l.b.body.getLinearVelocityFromWorldPoint(wB);
                float fx = l.cViscLin * (vb.x - va.x), fy = l.cViscLin * (vb.y - va.y);
                l.a.body.applyForce(fx, fy, wA.x, wA.y, true);
                l.b.body.applyForce(-fx, -fy, wB.x, wB.y, true);
            }
        }
    }

    // ------------------------------------------------------------ crush (round 34 task 2)

    /** live same-ship non-mate part contacts -> seconds spent squeezing. */
    private final Map<com.badlogic.gdx.physics.box2d.Contact, Float> crush =
            new java.util.LinkedHashMap<>();

    /** Contact listener callback (GameWorld.crush): register a squeeze contact. */
    void setCrush(Part pa, Part pb, com.badlogic.gdx.physics.box2d.Contact c, boolean begin) {
        // direct weld mates never collide (collideConnected=false), but guard
        // anyway: a mated pair is a joint, not a squeeze.
        for (Link l : links) {
            if ((l.a == pa && l.b == pb) || (l.a == pb && l.b == pa)) return;
        }
        if (begin) crush.put(c, 0f); else crush.remove(c);
    }

    /**
     * SimpleRockets-style crush response: two parts of the SAME ship squeezed
     * together get a separating force that grows LINEARLY with squeeze time
     * until they pop apart — replacing the contact solver's one-frame impulse
     * spike (the "squeeze explosion" that snaps welds on landing). Applied
     * per substep alongside the joint dampers. forceRate (1000-N units per
     * second of squeeze) lives in physics.lua `crush = {forceRate=.., maxForce=..}`;
     * 0 disables. probe 23 calibrates the default. Round 37 unit migration:
     * the lua values were /10 with all other force constants (500->50,
     * 1500->150); the x1000 below keeps the applied N identical.
     */
    public void applyCrushForces(float h) {
        if (crush.isEmpty()) return;
        double rate = PhysicsScript.tableNumber("crush", "forceRate");
        double fmax = PhysicsScript.tableNumber("crush", "maxForce");
        String ov = System.getProperty("dr.crushRate"); // debug toggle (probe)
        if (ov != null) {
            try { rate = Double.parseDouble(ov); } catch (NumberFormatException ignore) {}
        }
        if (rate <= 0) return;
        if (fmax <= 0) fmax = 500; // safety cap (500 kN after the /10 migration)
        List<com.badlogic.gdx.physics.box2d.Contact> dead = null;
        for (Map.Entry<com.badlogic.gdx.physics.box2d.Contact, Float> e : crush.entrySet()) {
            com.badlogic.gdx.physics.box2d.Contact c = e.getKey();
            Object ua = c.getFixtureA().getBody().getUserData();
            Object ub = c.getFixtureB().getBody().getUserData();
            if (!(ua instanceof Part) || !(ub instanceof Part)) { if (dead == null) dead = new ArrayList<>(); dead.add(c); continue; }
            Part pa = (Part) ua, pb = (Part) ub;
            if (pa.ship != this || pb.ship != this || pa.body == null || pb.body == null
                    || !c.isTouching()) { if (dead == null) dead = new ArrayList<>(); dead.add(c); continue; }
            float t = e.getValue() + h;
            e.setValue(t);
            double kn = Math.min(fmax, rate * t);
            com.badlogic.gdx.physics.box2d.WorldManifold wm = c.getWorldManifold();
            float nx = wm.getNormal().x, ny = wm.getNormal().y; // from A to B
            Vector2[] pts = wm.getPoints();
            float px = pts.length > 0 ? pts[0].x : (pa.body.getPosition().x + pb.body.getPosition().x) * 0.5f;
            float py = pts.length > 0 ? pts[0].y : (pa.body.getPosition().y + pb.body.getPosition().y) * 0.5f;
            float fx = (float) (kn * 1000) * nx, fy = (float) (kn * 1000) * ny;
            pa.body.applyForce(-fx, -fy, px, py, true); // push A back along -normal
            pb.body.applyForce(fx, fy, px, py, true);   // push B forward along +normal
        }
        if (dead != null) for (com.badlogic.gdx.physics.box2d.Contact c : dead) crush.remove(c);
    }

    /** Structural edits invalidate squeeze bookkeeping (parts moved to a new ship). */
    void clearCrush() { crush.clear(); }

    public void removeJointsOf(Part p) {
        List<Link> dead = new ArrayList<>();
        for (Link l : links) {
            if (l.a == p || l.b == p) dead.add(l);
        }
        for (Link l : dead) destroyLink(l);
        splitIfDisconnected();
    }

    /**
     * Detach MODE 2 (detacher-*.lua, round 26/27): sever ONLY the link to this
     * part's RECORDED PARENT (weldAt records it from the design/save
     * connection list — the parent is partA/parentPart there). This is
     * orientation-independent: a flipped detacher whose parent mates on
     * attach index 1 (BottomCenter instead of TopCenter) still cuts the pod
     * side, never the tank side. Joints on every other attach point survive,
     * so the detacher ring stays with the lower stage.
     * Fallback (geometric welds, legacy saves, links without a recorded
     * parent): sever the joint on attach index <= 0 — links without a
     * recorded attach index (-1) are treated as parent joints and severed
     * too, so a detacher that keeps a mystery link would never stage.
     */
    public void removeParentJointOf(Part p) {
        List<Link> dead = new ArrayList<>();
        Part parent = parentOf.get(p);
        if (parent != null) {
            for (Link l : links) {
                if ((l.a == p && l.b == parent) || (l.b == p && l.a == parent)) dead.add(l);
            }
        } else {
            for (Link l : links) {
                if (l.a == p && l.attachIndexA <= 0) dead.add(l);
                else if (l.b == p && l.attachIndexB <= 0) dead.add(l);
            }
        }
        for (Link l : dead) destroyLink(l);
        if (!dead.isEmpty()) splitIfDisconnected();
    }

    private void destroyLink(Link l) {
        if (l.joint != null) {
            world.boxWorld.destroyJoint(l.joint);
            l.joint = null;
        }
        if (l.jointPin != null) {
            world.boxWorld.destroyJoint(l.jointPin);
            l.jointPin = null;
        }
        links.remove(l);
    }

    /**
     * SR impact-damage removal channel (docs/sr-physics-re.md §6, round 36):
     * full removal chain for a part killed by a collision / water-entry
     * impulse — sever its joints, destroy its body (wheel tire + revolute
     * joint go with it), drop it from `parts` (the fuel network is derived
     * live from parts+links, so it rebuilds implicitly), then split the ship
     * if the joint graph lost connectivity. SandboxScreen's selectedPart is
     * guarded by `active.parts.contains(...)` at every use site, so removal
     * from `parts` is the complete selection cleanup. `explode` additionally
     * emits a short explosion flash via the existing FlameFx pool (SR
     * PartObject::Explode); canExplode=false parts (strut/parachute/dock-1)
     * only ever take the silent destroy path (enforced by the caller).
     * Must run OUTSIDE Box2D callbacks (bodies/joints are mutated).
     */
    public void removePart(Part p, boolean explode) {
        if (!parts.contains(p)) return;
        List<Link> dead = new ArrayList<>();
        for (Link l : links) {
            if (l.a == p || l.b == p) dead.add(l);
        }
        for (Link l : dead) destroyLink(l);
        parentOf.remove(p);
        if (explode && p.body != null) explosionFx(p);
        p.destroyBody();
        parts.remove(p);
        clearCrush(); // stale contacts referencing the dead body re-resolve
        splitIfDisconnected();
    }

    /** Short explosion flash at the part's position (pooled FlameFx particles, no new art). */
    private void explosionFx(Part p) {
        float bx = p.body.getPosition().x, by = p.body.getPosition().y;
        Vector2 v = p.body.getLinearVelocity();
        float scale = Math.max(0.6f, Math.min(3f, (p.type.width + p.type.height) * 0.25f));
        for (int i = 0; i < 18; i++) {
            double a = Math.random() * 2 * Math.PI;
            float sp = (float) (2 + Math.random() * 9) * scale;
            boolean spark = (i & 1) == 0;
            FlameFx.emit(spark ? FlameFx.TEX_SPARK : FlameFx.TEX_GLOW,
                    bx, by,
                    v.x + (float) (Math.cos(a) * sp), v.y + (float) (Math.sin(a) * sp),
                    1.2f,                              // drag
                    0.35f + (float) Math.random() * 0.45f, // life
                    (1.2f + (float) Math.random()) * scale, 0.3f * scale, // size grow->shrink
                    1f, spark ? 0.85f : 0.55f, 0.15f,  // orange
                    1f, 0f);
        }
    }

    /**
     * SimpleRockets break model (round 35, SR APK ARM disassembly verified):
     * SINGLE-FRAME criterion, checked every physics step, THREE channels —
     * any one tripping destroys the link immediately:
     *   1. force:  |getReactionForce(invDt)|  > breakForce   (kN)
     *   2. torque: |getReactionTorque(invDt)| > breakTorque  (kN*m)
     *   3. angle:  |current angle diff - weld-time angle diff| > breakAngle
     *      (rad, SR default ~0.6)
     * No debounce / persistence window — SR breaks on the first over-limit
     * frame. Spawn-settling spikes are gone now that welds are rigid 0 Hz
     * and the old soft-spring jitter they fed on no longer exists.
     */
    public void checkJointBreaks(float invDt) {
        // round 46 (Wheel.xml wake break cascade): weld reaction-force spikes
        // during the post-wake settle window are the structure seating onto
        // its contacts, not crash loads — SR does not fail welds on pad
        // release. Grace the break channels for the whole wakeSettle window
        // plus a 2 s tail (settle overshoot outlives the guide itself).
        if (wakeSettle > -120) return;
        List<Link> dead = new ArrayList<>();
        for (Link l : links) {
            if (l.breakForce == Float.MAX_VALUE && l.breakTorque == Float.MAX_VALUE
                    && l.breakAngle == Float.MAX_VALUE) continue;
            boolean over = false;
            if (l.breakForce != Float.MAX_VALUE) {
                Vector2 f = l.joint.getReactionForce(invDt);
                over = f.len() / 1000f > l.breakForce; // reaction force in kN
            }
            if (!over && l.breakTorque != Float.MAX_VALUE) {
                float tq = Math.abs(l.joint.getReactionTorque(invDt)) / 1000f; // kN*m
                over = tq > l.breakTorque;
            }
            // SR angle channel: rigid welds barely bend in the solver, so a
            // large live deviation from the weld-time angle difference means
            // the structure has been genuinely torn.
            if (!over && l.breakAngle != Float.MAX_VALUE) {
                float diff = l.a.body.getAngle() - l.b.body.getAngle();
                over = Math.abs(diff - l.initialAngleDiff) > l.breakAngle;
            }
            if (over) {
                if ("1".equals(System.getProperty("dr.debugImpact"))) {
                    String why = "";
                    if (l.breakForce != Float.MAX_VALUE) {
                        Vector2 f = l.joint.getReactionForce(invDt);
                        why += " F=" + (f.len() / 1000f) + "/" + l.breakForce;
                    }
                    if (l.breakTorque != Float.MAX_VALUE)
                        why += " T=" + (Math.abs(l.joint.getReactionTorque(invDt)) / 1000f) + "/" + l.breakTorque;
                    if (l.breakAngle != Float.MAX_VALUE) {
                        float diff = l.a.body.getAngle() - l.b.body.getAngle();
                        why += " A=" + Math.abs(diff - l.initialAngleDiff) + "/" + l.breakAngle;
                    }
                    System.out.println("[jointBreak]" + why + " parts=" + l.a.type.id + "<->" + l.b.type.id);
                }
                dead.add(l);
            }
        }
        for (Link l : dead) destroyLink(l);
        if (!dead.isEmpty()) splitIfDisconnected();
    }

    /** Split into multiple ships if the joint graph is disconnected. */
    public void splitIfDisconnected() {
        if (parts.size() < 2) return;
        Map<Part, Integer> comp = new IdentityHashMap<>();
        int ncomp = 0;
        for (Part p : parts) {
            if (comp.containsKey(p)) continue;
            // BFS
            List<Part> stack = new ArrayList<>();
            stack.add(p);
            comp.put(p, ncomp);
            while (!stack.isEmpty()) {
                Part cur = stack.remove(stack.size() - 1);
                for (Link l : links) {
                    Part other = l.a == cur ? l.b : (l.b == cur ? l.a : null);
                    if (other != null && !comp.containsKey(other)) {
                        comp.put(other, ncomp);
                        stack.add(other);
                    }
                }
            }
            ncomp++;
        }
        if (ncomp < 2) return;
        clearCrush(); // round 34: contacts re-resolve under the new ship split
        // main component = the one containing a pod, else the largest
        int mainComp = 0;
        int bestScore = -1;
        Map<Integer, Integer> sizes = new java.util.HashMap<>();
        for (Map.Entry<Part, Integer> e : comp.entrySet()) {
            sizes.merge(e.getValue(), 1, Integer::sum);
        }
        for (Map.Entry<Part, Integer> e : comp.entrySet()) {
            int score = sizes.get(e.getValue()) + ("pod".equals(e.getKey().type.type) ? 10000 : 0);
            if (score > bestScore) { bestScore = score; mainComp = e.getValue(); }
        }
        for (int c = 0; c < ncomp; c++) {
            if (c == mainComp) continue;
            Ship ns = new Ship(world);
            ns.origin.set(this.origin);
            ns.originVel.set(this.originVel);
            // freeze the fragment's throttle at the separation instant
            ns.latchedThrottle = world.inputThrottle;
            List<Part> moving = new ArrayList<>();
            for (Part p : parts) if (comp.get(p) == c) moving.add(p);
            for (Part p : moving) {
                parts.remove(p);
                ns.parts.add(p);
                p.ship = ns;
                Part par = parentOf.remove(p);
                if (par != null) ns.parentOf.put(p, par);
                // round 41: no re-grouping needed — fixtures carry no
                // collision group anymore; the fragment collides with its
                // former shipmates naturally (SR stage-bump semantics).
            }
            // move links
            List<Link> mv = new ArrayList<>();
            for (Link l : links) if (moving.contains(l.a) || moving.contains(l.b)) mv.add(l);
            links.removeAll(mv);
            ns.links.addAll(mv);
            // detached fragments cannot stage further (indices no longer valid)
            ns.currentStage = Integer.MAX_VALUE / 2;
            world.addShip(ns);
        }
        // remap remaining stage indices for the main ship (parts shifted)
        if (!stages.isEmpty()) {
            // rebuild index: original design index is no longer tracked post-split;
            // safest is to drop un-fired stages that reference missing parts.
            List<List<Integer>> kept = new ArrayList<>();
            for (int i = currentStage; i < stages.size(); i++) {
                List<Integer> st = new ArrayList<>();
                for (int idx : stages.get(i)) {
                    // keep only indices still valid
                    if (idx < parts.size()) st.add(idx);
                }
                if (!st.isEmpty()) kept.add(st);
            }
            stages.clear();
            stages.addAll(kept);
            currentStage = 0;
        }
    }

    // ---------------------------------------------------------------- fuel

    /** parts in the same fuel-network component as `from` (fuelLine edges). */
    private List<Part> fuelComponent(Part from, int fuelType) {
        List<Part> out = new ArrayList<>();
        Set<Part> seen = new HashSet<>();
        List<Part> stack = new ArrayList<>();
        stack.add(from);
        seen.add(from);
        while (!stack.isEmpty()) {
            Part cur = stack.remove(stack.size() - 1);
            if (cur.type.tank != null && cur.getFuelType() == fuelType) out.add(cur);
            for (Link l : links) {
                if (!l.fuelEdge) continue;
                Part other = l.a == cur ? l.b : (l.b == cur ? l.a : null);
                if (other != null && !seen.contains(other)) {
                    seen.add(other);
                    stack.add(other);
                }
            }
        }
        return out;
    }

    private List<Part> tanksOf(int fuelType) {
        List<Part> out = new ArrayList<>();
        for (Part p : parts) {
            if (p.type.tank != null && p.getFuelType() == fuelType) out.add(p);
        }
        return out;
    }

    public double fuelTotal(int fuelType) {
        double t = 0;
        for (Part p : tanksOf(fuelType)) t += p.fuel;
        return t;
    }

    public double fuelCapacity(int fuelType) {
        double t = 0;
        for (Part p : tanksOf(fuelType)) t += p.getFuelCapacity();
        return t;
    }

    /**
     * Tanks a consumer is allowed to draw from (item: fuel-line supply).
     *  - solid (3): only the consumer's own tank (SRBs burn internally);
     *  - electric (2): the whole ship's grid (batteries have no fuel lines);
     *  - monopropellant (1): the whole ship too (round 11 item 4 — RCS
     *    thrusters sip from any mono tank aboard, matching KSP rules);
     *  - liquid (0): the fuel-line networks of every tank directly attached
     *    to the consumer (any link), plus the consumer's own network when it
     *    is a tank itself. A tank reachable only through parts without
     *    fuelLine attach points (pods, detachers, batteries...) is isolated.
     */
    private List<Part> drainScope(Part consumer, int fuelType) {
        if (fuelType == 3) {
            List<Part> s = new ArrayList<>();
            if (consumer != null && consumer.type.tank != null && consumer.getFuelType() == 3) {
                s.add(consumer);
            }
            return s;
        }
        if (fuelType == 2) return tanksOf(2);
        if (fuelType == 1) return tanksOf(1);
        Set<Part> scope = new LinkedHashSet<>();
        List<Part> seeds = new ArrayList<>();
        if (consumer != null) {
            if (consumer.type.tank != null) seeds.add(consumer);
            for (Link l : links) {
                Part o = l.a == consumer ? l.b : (l.b == consumer ? l.a : null);
                if (o != null && o.type.tank != null) seeds.add(o);
            }
        }
        for (Part s : seeds) scope.addAll(fuelComponent(s, fuelType));
        return new ArrayList<>(scope);
    }

    /**
     * Drain fuel for a specific consumer from its supply scope: liquid fuel
     * comes only through fuel lines, mono/electric from the whole ship —
     * see drainScope. Returns the amount actually drained, spread evenly
     * across the scope.
     */
    public double drainFuel(Part consumer, int fuelType, double amount) {
        List<Part> tanks = drainScope(consumer, fuelType);
        double total = 0;
        for (Part p : tanks) total += p.fuel;
        double want = Math.min(amount, total);
        if (want <= 0) return 0;
        double frac = want / total;
        for (Part p : tanks) p.setFuel(p.fuel * (1 - frac));
        return want;
    }

    /** Drain fuel evenly across all tanks of that type in the ship; returns amount actually drained. */
    public double drainFuel(int fuelType, double amount) {
        List<Part> tanks = tanksOf(fuelType);
        double total = 0;
        for (Part p : tanks) total += p.fuel;
        double want = Math.min(amount, total);
        if (want <= 0) return 0;
        double frac = want / total;
        for (Part p : tanks) p.setFuel(p.fuel * (1 - frac));
        return want;
    }

    /** Transfer fuel between tanks: takes from `part`'s tank and spreads to others (or reverse if negative). */
    public double transferFuel(Part part, int fuelType, double amount) {
        if (part.type.tank == null || part.getFuelType() != fuelType) return 0;
        // equalize only inside this tank's own supply scope: the fuel-line
        // network for liquid, the ship-wide grid for electric/mono. Solid
        // fuel never transfers.
        List<Part> others = (fuelType == 2 || fuelType == 1)
                ? tanksOf(fuelType) : fuelComponent(part, fuelType);
        others.remove(part);
        if (others.isEmpty()) return 0;
        if (amount > 0) {
            double give = Math.min(amount, part.fuel);
            double space = 0;
            for (Part o : others) space += o.getFuelCapacity() - o.fuel;
            give = Math.min(give, space);
            if (give <= 0) return 0;
            double frac = give / space;
            for (Part o : others) o.setFuel(o.fuel + (o.getFuelCapacity() - o.fuel) * frac);
            part.setFuel(part.fuel - give);
            return give;
        } else {
            double take = Math.min(-amount, part.getFuelCapacity() - part.fuel);
            double avail = 0;
            for (Part o : others) avail += o.fuel;
            take = Math.min(take, avail);
            if (take <= 0) return 0;
            double frac = take / avail;
            for (Part o : others) o.setFuel(o.fuel * (1 - frac));
            part.setFuel(part.fuel + take);
            return -take;
        }
    }

    /** Add fuel into the network, spread across tanks with free space; returns amount actually added. */
    public double addFuel(int fuelType, double amount) {
        List<Part> tanks = tanksOf(fuelType);
        double space = 0;
        for (Part p : tanks) space += p.getFuelCapacity() - p.fuel;
        double add = Math.min(amount, space);
        if (add <= 0 || space <= 0) return 0;
        double frac = add / space;
        for (Part p : tanks) p.setFuel(p.fuel + (p.getFuelCapacity() - p.fuel) * frac);
        return add;
    }

    // ------------------------------------------- drag occlusion (round 11 item 2)

    private float dragCacheTime = -100f;
    private float dragCacheDirX, dragCacheDirY;
    private int dragCachePartCount = -1;
    private final Vector2 dragP1 = new Vector2(), dragP2 = new Vector2();

    /** Reusable raycast callback: does this sample ray hit another part of this ship? */
    private final class OcclusionQuery implements RayCastCallback {
        Part self;
        boolean blocked;
        @Override
        public float reportRayFixture(Fixture fixture, Vector2 point, Vector2 normal, float fraction) {
            Object ud = fixture.getBody().getUserData();
            if (ud instanceof Part && ud != self && ((Part) ud).ship == Ship.this) {
                blocked = true;
                return 0; // shadowed — terminate the ray
            }
            return -1; // ignore other ships / ground, keep looking
        }
    }
    private final OcclusionQuery occlusionQuery = new OcclusionQuery();

    /**
     * Recompute each part's aerodynamic exposure (0..1): 8 sample points are
     * spread across the part's silhouette perpendicular to the airflow, and a
     * sample counts as exposed when a ray cast upwind hits no other part of
     * THIS ship. Cached at ~15 Hz; recomputed early when the flow direction
     * turns more than ~8° or the part count changes. Must be called BEFORE
     * boxWorld.step (raycasts are illegal while the world is locked).
     */
    public void updateDragExposure(float rvx, float rvy, float time) {
        float sp2 = rvx * rvx + rvy * rvy;
        if (sp2 < 1f) { // no meaningful airflow — everything fully exposed
            for (Part p : parts) p.dragExposure = 1f;
            return;
        }
        float inv = 1f / (float) Math.sqrt(sp2);
        float dx = rvx * inv, dy = rvy * inv; // upwind direction
        boolean dirty = dragCachePartCount != parts.size()
                || time - dragCacheTime > 1f / 15f
                || Math.abs(dx * dragCacheDirY - dy * dragCacheDirX) > 0.1392f; // sin(8°)
        if (!dirty) return;
        dragCacheTime = time;
        dragCacheDirX = dx;
        dragCacheDirY = dy;
        dragCachePartCount = parts.size();
        if (world == null || world.boxWorld == null) return;
        // ray length: furthest upwind extent of the ship + margin
        float rayLen = 30f;
        for (Part p : parts) {
            if (p.body == null) continue;
            Vector2 c = p.body.getWorldCenter();
            rayLen = Math.max(rayLen, Math.abs(c.x * dx + c.y * dy) + 30f);
        }
        final int SAMPLES = 8;
        float px = -dy, py = dx; // unit vector perpendicular to the flow
        for (Part p : parts) {
            if (p.body == null) { p.dragExposure = 1f; continue; }
            float ang = p.body.getAngle();
            float ca = (float) Math.cos(ang), sa = (float) Math.sin(ang);
            // projected half-width of the part's box onto the perpendicular:
            // (w/2)|ex·perp| + (h/2)|ey·perp|, shrunk 10% to skip grazing rays
            float halfW = ((p.type.width / 2f) * Math.abs(ca * px + sa * py)
                    + (p.type.height / 2f) * Math.abs(-sa * px + ca * py)) * 0.9f;
            Vector2 center = p.body.getWorldCenter();
            int exposed = 0;
            occlusionQuery.self = p;
            for (int i = 0; i < SAMPLES; i++) {
                float t = (i / (float) (SAMPLES - 1)) * 2f - 1f; // -1..1
                dragP1.set(center.x + px * halfW * t, center.y + py * halfW * t);
                dragP2.set(dragP1.x + dx * rayLen, dragP1.y + dy * rayLen);
                occlusionQuery.blocked = false;
                world.boxWorld.rayCast(occlusionQuery, dragP1, dragP2);
                if (!occlusionQuery.blocked) exposed++;
            }
            p.dragExposure = exposed / (float) SAMPLES;
        }
    }

    // ---------------------------------------------------------------- stages

    /**
     * STAGE semantics (round 26, item B4): a part's `group` IS its stage
     * number (0 = not staged, set in the editor by dragging parts into STAGE
     * slots). Pressing STAGE fires the NEXT stage number — the smallest
     * group number greater than `currentStage` among this ship's parts — by
     * calling onStage on every part in it (engines ignite at the current
     * throttle, detachers sever per their MODE, chutes/legs deploy).
     * Returns the stage number fired, or -1 when no staged parts remain.
     *
     * This replaces the old design-stage-list semantics (the `stages` list
     * is still loaded/saved for backward compatibility but no longer drives
     * activation) and supersedes the ACTIVATE-by-group behavior.
     * `currentStage` now means "last stage number fired".
     */
    public int activateStage() {
        wakePadHold(); // staging counts as a player action (round 39)
        int next = -1;
        for (Part p : parts) {
            if (p.group > currentStage && (next < 0 || p.group < next)) next = p.group;
        }
        if (next < 0) return -1;
        // snapshot targets BEFORE firing: a detacher's onStage defers joint
        // destruction + ship split, which mutates `parts` after we return —
        // resolving the group to references first keeps every member reachable.
        // QUEUE semantics (round 27, owner ruling): the stage opens a queue and
        // every part's full response is activated one by one — DETACHERS ALWAYS
        // LAST. Non-detacher parts not only get onStage here: when the stage
        // also contains a detacher, they get one full script frame
        // (updateScripts) BEFORE the cut, so a staged-away engine is already
        // RUNNING (thrust applied, flameLevel lit) at the separation instant
        // instead of waking up dead on the dropped stage.
        List<Part> targets = new ArrayList<>();
        List<Part> detachers = new ArrayList<>();
        for (Part p : parts) {
            if (p.group == next) {
                if ("detacher".equals(p.type.type)) detachers.add(p);
                else targets.add(p);
            }
        }
        for (Part p : targets) {
            if (p.body == null || p.ship == null || !p.ship.parts.contains(p)) continue;
            p.callOnStage();
        }
        if (!targets.isEmpty() && !detachers.isEmpty()) {
            updateScripts(GameWorld.PHYS_DT); // complete their response pre-cut
        }
        for (Part p : detachers) {
            if (p.body == null || p.ship == null || !p.ship.parts.contains(p)) continue;
            p.callOnStage();
        }
        world.processDeferredStructure();
        currentStage = next;
        return next;
    }

    /** map from design-part index to runtime Part (same order, parts may have been filtered). */
    public Part partAt(int designIndex) {
        return (designIndex >= 0 && designIndex < parts.size()) ? parts.get(designIndex) : null;
    }

    /** Runtime id of this ship (session-unique; written as ShipNode id in sandbox saves). */
    public int getId() { return id; }

    /**
     * Re-weld two parts restored from a save file: anchor at the midpoint of
     * their nearest attach points and resolve the weld params from those
     * attach defs (same rule as the JSON loader used).
     */
    public void weldLoaded(Part a, Part b) {
        Vector2 anchor = bestAnchor(a, b);
        if (anchor != null) {
            weld(a, b, anchor, nearestAttach(a, anchor), nearestAttach(b, anchor));
        }
    }

    // ---------------------------------------------------------------- update

    /**
     * A continuous force registered by a script this frame. Box2D clears
     * applied forces after every step, so a plain body.applyForce from a
     * script only acts on the NEXT single substep; at warp >1 (several
     * substeps per frame) thrust would cover only 1/N of the simulated time
     * while fuel drain covered all of it. Scripts therefore REGISTER forces
     * here and GameWorld.substep re-applies them before every step.
     */
    public static class FrameForce {
        public com.badlogic.gdx.physics.box2d.Body body;
        /** Force vector and application point in the body's LOCAL frame.
         * Round 38 (probe38 spin fix): BOTH are re-expressed through the
         * body's live transform at every substep. A point captured once per
         * frame goes stale as the body rotates (probe37); a DIRECTION
         * captured once per frame lags the rotation and its lever arm about
         * the COM systematically PUMPS angular momentum (probe38: fresh
         * point + stale direction spun the stack to 10 rad/s). */
        public float fx, fy;   // local force vector
        public float lx, ly;   // local point (meters)
    }
    public final List<FrameForce> frameForces = new ArrayList<>();

    /** Register a continuous force for this frame (see FrameForce). */
    public void addFrameForce(com.badlogic.gdx.physics.box2d.Body b,
                              float fx, float fy, float localX, float localY) {
        FrameForce f = new FrameForce();
        f.body = b; f.fx = fx; f.fy = fy; f.lx = localX; f.ly = localY;
        frameForces.add(f);
    }

    private final Vector2 tmpFF = new Vector2();
    private final Vector2 tmpFF2 = new Vector2();

    /** Re-apply this frame's registered forces (called before every substep). */
    public void applyFrameForces() {
        for (FrameForce f : frameForces) {
            if (f.body != null) {
                Vector2 wp = f.body.getWorldPoint(tmpFF.set(f.lx, f.ly));
                Vector2 wf = f.body.getWorldVector(tmpFF2.set(f.fx, f.fy));
                f.body.applyForce(wf.x, wf.y, wp.x, wp.y, true);
            }
        }
    }

    public void updateScripts(double dt) {
        frameForces.clear(); // scripts re-register their continuous forces below
        for (Part p : parts) { p.flameLevel = 0f; p.rcsJets.clear(); }
        for (Part p : parts) p.callOnUpdate(dt);
        // stage flags persist until the end of the frame so Lua onUpdate can read them
        for (Part p : parts) p.stageActivatedThisFrame = false;
    }

    /**
     * onUpdate for ENGINE parts only, for ships that are NOT the active one
     * (round 27): a stage that was fired and then cut loose by a detacher in
     * the same activation keeps burning — splitIfDisconnected reuses the Part
     * objects, so the engine's Lua `staged` flag survives the split, and
     * part:getThrottle() reads the live player throttle, so the dropped stage
     * thrusts at exactly throttle x max power from the separation instant on.
     * Every other script type stays inert on non-active ships (no steering,
     * no wheels, no chutes) — only engines have a meaningful unattended
     * behavior. Skipped while the ship rides rails (super-warp parks it).
     */
    public void updateEngineScripts(double dt) {
        frameForces.clear();
        for (Part p : parts) {
            if (p.body != null && "engine".equals(p.type.type)) {
                p.flameLevel = 0f;
                p.rcsJets.clear();
                p.callOnUpdate(dt);
            }
        }
    }

    public Vector2 centerOfMass(Vector2 out) {
        out.set(0, 0);
        if (parts.isEmpty()) return out;
        float m = 0;
        for (Part p : parts) {
            if (p.body == null) continue;
            float pm = p.body.getMass();
            out.x += p.body.getPosition().x * pm;
            out.y += p.body.getPosition().y * pm;
            m += pm;
        }
        if (m > 0) out.scl(1f / m);
        return out;
    }

    public Vector2 velocity(Vector2 out) {
        out.set(0, 0);
        if (parts.isEmpty()) return out;
        float m = 0;
        for (Part p : parts) {
            if (p.body == null) continue;
            float pm = p.body.getMass();
            out.x += p.body.getLinearVelocity().x * pm;
            out.y += p.body.getLinearVelocity().y * pm;
            m += pm;
        }
        if (m > 0) out.scl(1f / m);
        return out;
    }

    public Vec2d getUniverseVel() {
        Vector2 v = velocity(new Vector2());
        return new Vec2d(world.frameVel.x + originVel.x + v.x, world.frameVel.y + originVel.y + v.y);
    }

    public Vec2d getUniversePos() {
        Vector2 c = centerOfMass(new Vector2());
        return new Vec2d(origin.x + c.x, origin.y + c.y);
    }

    public void setBodiesActive(boolean active) {
        forEachBody(b -> b.setActive(active));
    }

    /** One windward-exposed leading edge (shock origin) of the ship. */
    public static class WindwardEdge {
        public float x, y;     // tip position (physics-frame coords)
        public float half;     // this part's half-width across the wind axis
        public boolean sharp;  // pointed (nosecone) -> oblique cone; else bow shock
        public Part part;      // source part (stable id / per-part shimmer phase)
        public float waistX, waistY; // downstream edge center (the part's "waist" — skirt origin, round 31)
        public float waistHalf;      // half-width of the downstream edge
    }

    /**
     * All windward-EXPOSED leading edges (round 28 v2): (ux,uy) is the UPWIND
     * unit vector — the direction the airflow comes FROM (the ship's
     * atmosphere-relative velocity direction). A part counts as exposed when
     * no other part sits strictly ahead of it (greater upwind projection)
     * while fully covering its lateral span; flush junctions between equal
     * projections expose only the foremost part, and a wider part behind a
     * narrower one stays exposed at its shoulders. Each edge carries the tip
     * (max-projection hull corner), the part's own half-width and a
     * pointed/blunt flag for oblique-cone vs bow-shock drawing.
     * Corner math is manual (pos + rot(angle) * local), no native calls.
     */
    public List<WindwardEdge> windwardEdges(float ux, float uy) {
        List<WindwardEdge> out = new ArrayList<>();
        int n = parts.size();
        float[] proj = new float[n], pMin = new float[n], pMax = new float[n];
        WindwardEdge[] edges = new WindwardEdge[n];
        int m = 0;
        for (Part p : parts) {
            if (p.body == null) continue;
            float ang = p.body.getAngle();
            float ca = (float) Math.cos(ang), sa = (float) Math.sin(ang);
            Vector2 bp = p.body.getPosition();
            float hw = p.type.width / 2f, hh = p.type.height / 2f;
            float best = -Float.MAX_VALUE, tipX = 0, tipY = 0, tipPerp = 0;
            float mn = Float.MAX_VALUE, mx = -Float.MAX_VALUE;
            float[] prC = new float[4], peC = new float[4], cxC = new float[4], cyC = new float[4];
            for (int ci = 0; ci < 4; ci++) {
                float lx = (ci == 0 || ci == 3) ? -hw : hw;
                float ly = (ci < 2) ? -hh : hh;
                float cx = bp.x + lx * ca - ly * sa;
                float cy = bp.y + lx * sa + ly * ca;
                float pr = cx * ux + cy * uy;
                float pe = cx * uy - cy * ux; // signed offset from the wind axis
                prC[ci] = pr; peC[ci] = pe; cxC[ci] = cx; cyC[ci] = cy;
                if (pr > best) { best = pr; tipX = cx; tipY = cy; tipPerp = pe; }
                if (pe < mn) mn = pe;
                if (pe > mx) mx = pe;
            }
            // waist (round 31 skirt origin): midpoint + half-width of the two
            // DOWNSTREAM-most corners (the part's trailing edge across the wind)
            int w0 = -1, w1 = -1;
            for (int ci = 0; ci < 4; ci++) {
                if (w0 < 0 || prC[ci] < prC[w0]) { w1 = w0; w0 = ci; }
                else if (w1 < 0 || prC[ci] < prC[w1]) { w1 = ci; }
            }
            WindwardEdge e = new WindwardEdge();
            e.x = tipX; e.y = tipY;
            e.waistX = (cxC[w0] + cxC[w1]) * 0.5f;
            e.waistY = (cyC[w0] + cyC[w1]) * 0.5f;
            e.waistHalf = Math.max(0.3f, Math.abs(peC[w0] - peC[w1]) * 0.5f);
            e.half = Math.max(0.3f, Math.max(mx - tipPerp, tipPerp - mn));
            e.sharp = "nosecone".equals(p.type.type);
            e.part = p;
            proj[m] = best; pMin[m] = mn; pMax[m] = mx; edges[m] = e;
            m++;
        }
        // occlusion: j covers i when j is strictly AHEAD and its lateral span
        // fully contains i's span (shoulders of a wider back part stay exposed)
        final float AHEAD = 0.01f, EPS = 0.05f;
        for (int i = 0; i < m; i++) {
            boolean covered = false;
            for (int j = 0; j < m; j++) {
                if (i == j) continue;
                if (proj[j] > proj[i] + AHEAD
                        && pMin[j] <= pMin[i] + EPS && pMax[j] >= pMax[i] - EPS) {
                    covered = true;
                    break;
                }
            }
            if (!covered) out.add(edges[i]);
        }
        // foremost first (stable drawing order: nose shock under shoulder shocks)
        out.sort((a, b) -> {
            float pa = a.x * ux + a.y * uy, pb = b.x * ux + b.y * uy;
            return Float.compare(pb, pa);
        });
        return out;
    }

    /** The part that provides control input/heading reference: the pod, else the first part. */
    public Part controlPart() {        for (Part p : parts) if ("pod".equals(p.type.type)) return p;
        return parts.isEmpty() ? null : parts.get(0);
    }

    /** On-rails integration for distant inactive ships (bodies parked, origin moved). */
    public void integrateRails(double dt) {
        Vec2d com0 = getUniversePos();
        Planet np = world.nearestPlanetTo(com0.x, com0.y);
        if (np != null) {
            double dx = com0.x - np.pos.x, dy = com0.y - np.pos.y;
            double dist = Math.hypot(dx, dy);
            double surf = np.radius + np.heightAt(Math.atan2(dy, dx));
            double alt = dist - surf;
            // round 13 fix: a ship PARKED on the surface must not gravity-sink
            // through the terrain while on rails (no collision in rails mode).
            // Landed = near the surface AND slow relative to the planet.
            // Round 14: the hold must RIDE the planet — the body translates
            // along its rail (and super-warp advances it fast); a parked ship
            // that just froze its origin was left behind by the moving planet.
            double uvx = originVel.x + world.frameVel.x - np.vel.x;
            double uvy = originVel.y + world.frameVel.y - np.vel.y;
            if (alt < 50.0 && Math.hypot(uvx, uvy) < 1.0) {
                origin.add(np.vel.x * dt, np.vel.y * dt);
                originVel.set(np.vel.x - world.frameVel.x, np.vel.y - world.frameVel.y);
                return; // hold position relative to the surface
            }
        }
        Vec2d g = world.gravityAt(com0.x, com0.y);
        originVel.add(g.x * dt, g.y * dt);
        origin.add(originVel.x * dt, originVel.y * dt);
        // Hard floor: never end a rails step below the terrain (ballistic
        // stages falling in while distant get clamped to the surface instead
        // of tunneling through the planet).
        if (np != null) {
            Vec2d com1 = getUniversePos();
            double dx = com1.x - np.pos.x, dy = com1.y - np.pos.y;
            double dist = Math.hypot(dx, dy);
            double ang = Math.atan2(dy, dx);
            double surf = np.radius + np.heightAt(ang);
            if (dist < surf + 0.5 && dist > 1e-9) {
                double ux = dx / dist, uy = dy / dist;
                double push = (surf + 0.5) - dist;
                origin.x += ux * push;
                origin.y += uy * push;
                double rv = originVel.x * ux + originVel.y * uy; // radial (frame dirs match universe)
                if (rv < 0) { originVel.x -= rv * ux; originVel.y -= rv * uy; }
            }
        }
    }

    /** Shift every body of this ship by (dx,dy) (used when the floating origin moves). */
    /** All physics bodies of this ship: part bodies plus wheel tire bodies. */
    public void forEachBody(java.util.function.Consumer<com.badlogic.gdx.physics.box2d.Body> c) {
        for (Part p : parts) {
            if (p.body != null) c.accept(p.body);
            if (p.tireBody != null) c.accept(p.tireBody);
        }
    }

    public void shiftBodies(double dx, double dy) {
        forEachBody(b -> b.setTransform((float) (b.getPosition().x + dx),
                (float) (b.getPosition().y + dy), b.getAngle()));
        origin.x -= dx;
        origin.y -= dy;
    }

    public void destroy() {
        // joints first: Box2D auto-destroys attached joints when a body dies
        for (Link l : new ArrayList<>(links)) destroyLink(l);
        for (Part p : parts) p.destroyBody();
        parts.clear();
    }

    // ---------------------------------------------------------------- save

    public String toJson() {
        Json.Writer w = new Json.Writer();
        w.obj();
        w.set("name", name);
        w.set("originX", origin.x);
        w.set("originY", origin.y);
        w.set("velX", originVel.x);
        w.set("velY", originVel.y);
        w.set("stage", currentStage);
        w.key("parts"); w.arr();
        Vector2 com = centerOfMass(new Vector2());
        for (Part p : parts) {
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
            if (p.flippedX) w.set("fx", 1);
            if (p.flippedY) w.set("fy", 1);
            w.endObj();
        }
        w.endArr();
        // save link structure as index pairs
        w.key("links"); w.arr();
        for (Link l : links) {
            int ia = parts.indexOf(l.a), ib = parts.indexOf(l.b);
            if (ia < 0 || ib < 0) continue;
            w.obj();
            w.set("a", ia);
            w.set("b", ib);
            w.endObj();
        }
        w.endArr();
        w.key("stages"); w.arr();
        for (List<Integer> st : stages) {
            w.arr();
            for (int i : st) w.val(i);
            w.endArr();
        }
        w.endArr();
        w.endObj();
        return w.toString();
    }

    /** Rebuild a ship from its JSON record. */
    public static Ship fromJson(GameWorld world, Json.JObj o) {
        Ship s = new Ship(world);
        s.name = o.getStr("name", "Ship");
        s.origin.set(o.getNum("originX", 0), o.getNum("originY", 0));
        s.originVel.set(o.getNum("velX", 0), o.getNum("velY", 0));
        s.currentStage = o.getInt("stage", 0);
        List<Json.Value> ps = o.getArr("parts");
        if (ps != null) {
            for (Json.Value v : ps) {
                Json.JObj po = v.asObj();
                PartType t = PartList.get(po.getStr("t", "fuselage-1"));
                if (t == null) continue;
                ShipDesign.DesignPart dp = new ShipDesign.DesignPart(t.id, 0, 0, 0);
                Part p = new Part(t, s, dp);
                // mirror flags BEFORE createBody: collider verts and attach
                // defs mirror off these (round 27)
                p.flippedX = po.getInt("fx", 0) != 0;
                p.flippedY = po.getInt("fy", 0) != 0;
                p.createBody((float) po.getNum("x", 0), (float) po.getNum("y", 0),
                        (float) po.getNum("a", 0));
                p.body.setTransform(p.body.getPosition(), (float) po.getNum("a", 0));
                p.body.setLinearVelocity((float) po.getNum("vx", 0), (float) po.getNum("vy", 0));
                p.body.setAngularVelocity((float) po.getNum("va", 0));
                p.fuel = po.getNum("fuel", p.fuel);
                p.deployed = po.getBool("dep", false);
                p.group = po.getInt("grp", 0);
                p.updateMass();
                s.parts.add(p);
            }
        }
        // onLoad BEFORE re-welding saved links (round 9 item 1): per-part
        // joint overrides set in onLoad must drive the weld resolution.
        for (Part p : s.parts) p.callOnLoad();
        List<Json.Value> ls = o.getArr("links");
        if (ls != null) {
            for (Json.Value v : ls) {
                Json.JObj lo = v.asObj();
                int ia = lo.getInt("a", -1), ib = lo.getInt("b", -1);
                if (ia < 0 || ib < 0 || ia >= s.parts.size() || ib >= s.parts.size()) continue;
                Part a = s.parts.get(ia), b = s.parts.get(ib);
                // weld at the average of their nearest attach points (approx: midpoint of COMs is wrong; use closest attach)
                Vector2 anchor = bestAnchor(a, b);
                if (anchor != null) {
                    PartType.AttachPoint apA = nearestAttach(a, anchor);
                    PartType.AttachPoint apB = nearestAttach(b, anchor);
                    s.weld(a, b, anchor, apA, apB);
                }
            }
        }
        List<Json.Value> ss = o.getArr("stages");
        if (ss != null) {
            for (Json.Value v : ss) {
                List<Integer> st = new ArrayList<>();
                for (Json.Value i : v.asArr()) st.add(i.asInt(0));
                s.stages.add(st);
            }
        }
        return s;
    }

    private static Vector2 bestAnchor(Part a, Part b) {
        List<Vector2> wa = a.attachWorldPositions();
        List<Vector2> wb = b.attachWorldPositions();
        float best = Float.MAX_VALUE;
        Vector2 bestP = null;
        for (Vector2 x : wa) for (Vector2 y : wb) {
            float d = x.dst(y);
            if (d < best) { best = d; bestP = new Vector2((x.x + y.x) / 2, (x.y + y.y) / 2); }
        }
        return bestP;
    }

    private static PartType.AttachPoint nearestAttach(Part p, Vector2 world) {
        PartType.AttachPoint best = null;
        float bd = Float.MAX_VALUE;
        List<Vector2> ws = p.attachWorldPositions();
        for (int i = 0; i < ws.size(); i++) {
            float d = ws.get(i).dst(world);
            if (d < bd) { bd = d; best = p.attachDefs().get(i); }
        }
        if (best == null) best = new PartType.AttachPoint();
        return best;
    }
}
