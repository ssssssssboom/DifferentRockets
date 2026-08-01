package com.differentrockets.game;

import com.badlogic.gdx.math.Vector2;

import java.util.List;

/**
 * The Java object exposed to Lua scripts as the `part` argument.
 * All positions/velocities are in the physics frame (meters, y-up, relative
 * to the active origin). Planet positions are exposed in the same frame.
 */
public class ModApi {
    public final Part part;

    public ModApi(Part part) { this.part = part; }

    private GameWorld world() { return part.ship.world; }

    // ---------- identity ----------
    public String getTypeId() { return part.type.id; }
    public String getName() { return part.type.name; }
    public String getType() { return part.type.type; }
    /** Activation group of this part: 0 = none, 1..8. */
    public int getGroup() { return part.group; }
    public void setGroup(int g) { part.group = Math.max(0, Math.min(8, g)); }

    // ---------- own physics ----------
    public double getX() { return part.body != null ? part.body.getPosition().x : 0; }
    public double getY() { return part.body != null ? part.body.getPosition().y : 0; }
    public double getVelocityX() { return part.body != null ? part.body.getLinearVelocity().x : 0; }
    public double getVelocityY() { return part.body != null ? part.body.getLinearVelocity().y : 0; }
    public double getAngle() { return part.body != null ? part.body.getAngle() : 0; } // radians
    public double getAngularVelocity() { return part.body != null ? part.body.getAngularVelocity() : 0; }
    public double getMass() { return part.body != null ? part.body.getMass() : 0; }

    public void applyForce(double fx, double fy) {
        if (part.body != null) part.body.applyForceToCenter((float) fx, (float) fy, true);
    }

    /** Apply force at a local offset (meters, part frame). */
    public void applyForceAt(double fx, double fy, double localX, double localY) {
        if (part.body == null) return;
        // register as a continuous frame force: GameWorld.substep re-applies
        // it before EVERY physics step this frame, so thrust covers the full
        // simulated time at any warp level (see Ship.FrameForce). Round 38:
        // the force is stored in the part's LOCAL frame (converted here while
        // the script-time transform is exact) and re-expressed through the
        // LIVE body transform at every substep — stale direction pumped
        // angular momentum just like the stale point did (probe37/probe38).
        Vector2 lf = part.body.getLocalVector(new Vector2((float) fx, (float) fy));
        part.ship.addFrameForce(part.body, lf.x, lf.y, (float) localX, (float) localY);
    }

    public void applyTorque(double t) {
        if (part.body != null) part.body.applyTorque((float) t, true);
    }

    // ---------- wheel (round 27: wheel-*.lua) ----------
    /** Lock/unlock the wheel tire (locked = held rigid by the motor). */
    public void setWheelLocked(boolean locked) { part.setWheelLocked(locked); }
    /** Signed drive fraction -1..1 (0 = free-spin); ignored while locked. */
    public void setWheelDrive(double frac) { part.setWheelDrive(frac); }
    /** Tire angular velocity (rad/s); negative = reverse. */
    public double getWheelSpeed() {
        return part.tireBody != null ? part.tireBody.getAngularVelocity() : 0;
    }

    // ---------- ship / world ----------
    public double getShipX() { return world().origin.x + (part.body != null ? part.body.getPosition().x : 0); }
    public double getShipY() { return world().origin.y + (part.body != null ? part.body.getPosition().y : 0); }
    public double getShipVelocityX() { return part.ship.getUniverseVel().x; }
    public double getShipVelocityY() { return part.ship.getUniverseVel().y; }

    public int getPlanetCount() { return world().planets.size(); }
    public String getPlanetName(int i) { return world().planets.get(i).name; }
    public double getPlanetRadius(int i) { return world().planets.get(i).radius; }
    public double getPlanetX(int i) { return world().planets.get(i).pos.x - world().origin.x; }
    public double getPlanetY(int i) { return world().planets.get(i).pos.y - world().origin.y; }

    /** Current primary body name (nearest planet). */
    public String getCurrentPlanet() { Planet p = world().currentPlanet(); return p != null ? p.name : ""; }

    /** Gravity vector at this part (m/s^2). */
    public double getGravityX() { return world().gravityAt(universeX(), universeY()).x; }
    public double getGravityY() { return world().gravityAt(universeX(), universeY()).y; }

    private double universeX() { return world().origin.x + (part.body != null ? part.body.getPosition().x : 0); }
    private double universeY() { return world().origin.y + (part.body != null ? part.body.getPosition().y : 0); }

    /** Altitude above terrain surface (m) on the current planet. */
    public double getAltitude() { return world().altitudeAt(universeX(), universeY()); }
    public double getAtmoDensity() { return world().densityAt(universeX(), universeY()); }
    public double getAtmoPressure() { return world().pressureAt(universeX(), universeY()); }
    /** true if below sea level of a planet with water. */
    public boolean isInWater() { return world().isInWater(universeX(), universeY()); }
    /** true if sunlit (not occluded by any planet from the Sun). */
    public boolean isInSunlight() { return world().isInSunlight(universeX(), universeY()); }

    // ---------- input ----------
    /** -1 = turn left, 0, +1 = turn right. This is the steering turn command. */
    public double getTurn() { return world().inputTurn; }
    /**
     * 0..1 throttle. Only the ACTIVE ship sees the live player throttle
     * (round 27): a ship that was split off (or left via ship-switch) reads
     * its latched value — the throttle frozen at the moment it was cut
     * loose — so detached stages keep their separation-instant burn and
     * later throttle moves no longer reach them.
     */
    public double getThrottle() {
        GameWorld w = world();
        if (part.ship == null || part.ship == w.active) return w.inputThrottle;
        double l = part.ship.latchedThrottle;
        return l >= 0 ? l : w.inputThrottle;
    }
    /** true only on the frame a stage was activated. */
    public boolean isStageActivated() { return part.stageActivatedThisFrame; }
    /** current stage index the ship is on. */
    public int getStage() { return part.ship.currentStage; }

    // ---------- steering (round 12: SteeringIO + control.lua) ----------
    /** Target heading (radians, body-angle convention: 0 = nose "up", CCW positive). */
    public double getTargetHeading() { return world().getTargetHeading(); }
    /** Command a heading (ring semantics): activates ring mode. */
    public void setTargetHeading(double rad) { world().setTargetHeading(rad); }
    /** Current ship heading (radians, same convention as target). */
    public double getShipHeading() { return world().currentHeading(); }

    /** Round 40h: part spawn angle + ship mean rotation — SR rigid-weld
     *  thrust baseline (see Ship.avgRotation). Engines aim thrust along
     *  this instead of their own flopping body angle. */
    public double getRigidAngle() { return part.spawnAngle + part.ship.avgRotation(); }
    /** Latest turn command in -1..1 (same value as getTurn()). */
    public double getTurnCommand() { return world().getTurnCommand(); }

    /**
     * Raw steering input state (round 12), mirrored from SteeringIO:
     *   active     bool — ring mode on (engines track targetRad)
     *   buttonTurn int  — -1/0/+1 while a turn button is held (overrides ring)
     *   targetRad  num  — ring target heading (radians, body-angle convention)
     * Round 34 task 3 (RCS pad): holdUp/holdDown/holdLeft/holdRight and
     * rotLeft/rotRight booleans (true while the pad button is held) plus the
     * rcsEnabled master switch — all gated to the ACTIVE ship like the rest.
     * The engine control law lives in mod/control.lua (controlLaw(part));
     * angle errors must be wrapped to [-pi, pi] (see control.lua).
     */
    public org.luaj.vm2.LuaTable getSteering() {
        // Player input only reaches the ACTIVE ship (round 27): non-active
        // ships' engines keep running (at their latched throttle) but see
        // "no input" here, so their gimbals center instead of following the
        // player's buttons/ring.
        boolean ownShip = part.ship == null || part.ship == world().active;
        org.luaj.vm2.LuaTable t = new org.luaj.vm2.LuaTable();
        t.set("active", org.luaj.vm2.LuaValue.valueOf(ownShip && SteeringIO.ringActive));
        t.set("buttonTurn", ownShip ? SteeringIO.buttonTurn : 0);
        t.set("targetRad", SteeringIO.targetHeadingRad);
        t.set("holdUp", org.luaj.vm2.LuaValue.valueOf(ownShip && SteeringIO.holdUp));
        t.set("holdDown", org.luaj.vm2.LuaValue.valueOf(ownShip && SteeringIO.holdDown));
        t.set("holdLeft", org.luaj.vm2.LuaValue.valueOf(ownShip && SteeringIO.holdLeft));
        t.set("holdRight", org.luaj.vm2.LuaValue.valueOf(ownShip && SteeringIO.holdRight));
        t.set("rotLeft", org.luaj.vm2.LuaValue.valueOf(ownShip && SteeringIO.rotLeft));
        t.set("rotRight", org.luaj.vm2.LuaValue.valueOf(ownShip && SteeringIO.rotRight));
        t.set("rcsEnabled", org.luaj.vm2.LuaValue.valueOf(ownShip && SteeringIO.rcsEnabled));
        return t;
    }

    /** Ship centre of mass, box-local coords (same frame as getX/getY). */
    public double getShipComX() { return part.ship != null ? part.ship.centerOfMass(comTmp).x : getX(); }
    public double getShipComY() { return part.ship != null ? part.ship.centerOfMass(comTmp).y : getY(); }
    private final com.badlogic.gdx.math.Vector2 comTmp = new com.badlogic.gdx.math.Vector2();

    /** Editor Flip X mirror flag (round 34: RCS nozzle sidedness). */
    public boolean isFlippedX() { return part.flippedX; }
    /** Editor Flip Y mirror flag. */
    public boolean isFlippedY() { return part.flippedY; }

    /**
     * Queue an RCS jet puff for this frame (round 34 task 3): size ~0..1.5,
     * dirX/dirY = PLUME direction (world unit vector, opposite the thrust),
     * localX/localY = nozzle offset in body-local metres. Rendered by
     * SandboxScreen.drawRcsJets as small white particles.
     */
    public void emitJet(double size, double dirX, double dirY, double localX, double localY) {
        part.emitJet((float) size, (float) dirX, (float) dirY, (float) localX, (float) localY);
    }

    /**
     * Read a mod file's text (player mod dir first, built-in assets as
     * fallback — same resolution as part scripts). Plain file names only.
     * Engine scripts use this to load control.lua into their own Lua state.
     */
    public String readModText(String name) {
        if (name == null || name.length() == 0 || name.indexOf('/') >= 0
                || name.indexOf('\\') >= 0 || name.indexOf("..") >= 0) return null;
        com.badlogic.gdx.files.FileHandle dir = com.differentrockets.util.Res.modDir();
        if (dir != null) {
            com.badlogic.gdx.files.FileHandle f = dir.child(name);
            if (f.exists()) {
                try { return f.readString(); } catch (Exception ignored) {}
            }
        }
        com.badlogic.gdx.files.FileHandle in = com.badlogic.gdx.Gdx.files.internal("mods/" + name);
        if (in.exists()) {
            try { return in.readString(); } catch (Exception ignored) {}
        }
        return null;
    }

    // ---------- fuel network ----------
    /** Total fuel of the given type in the whole ship network. */
    public double getFuelTotal(int fuelType) { return part.ship.fuelTotal(fuelType); }
    public double getFuelCapacity(int fuelType) { return part.ship.fuelCapacity(fuelType); }
    /**
     * Drain up to `amount` units of the given fuel type for THIS part; returns
     * actually drained. Supply scope: liquid (type 0) comes only from tanks
     * connected to this part through fuel lines (fuelLine attach points) —
     * tanks separated by parts without fuelLine points (pods, detachers,
     * batteries) are isolated and will NOT supply this part. Monopropellant
     * (type 1) and electric (type 2) are shared ship-wide; solid (type 3)
     * only burns the consumer's own tank.
     */
    public double drainFuel(int fuelType, double amount) { return part.ship.drainFuel(part, fuelType, amount); }
    /** Move fuel between tanks of this part's supply scope (liquid: fuel-line network; mono/electric: ship-wide); returns amount moved out of this tank (negative = into). */
    public double transferFuel(int fuelType, double amount) { return part.ship.transferFuel(part, fuelType, amount); }

    // ---------- own tank ----------
    public double getFuel() { return part.getFuel(); }
    public double getFuelMax() { return part.getFuelCapacity(); }
    public int getFuelType() { return part.getFuelType(); }
    public void setFuel(double v) { part.setFuel(v); }

    /** Add fuel into the network (e.g. solar charging); returns amount actually added. */
    public double addFuel(int fuelType, double amount) { return part.ship.addFuel(fuelType, amount); }

    // ---------- part definition ----------
    public double getWidth() { return part.type.width; }
    public double getHeight() { return part.type.height; }
    public double getEnginePower() { return part.type.engine != null ? part.type.engine.power : 0; }
    public double getEngineConsumption() { return part.type.engine != null ? part.type.engine.consumption : 0; }
    public double getEngineTurn() { return part.type.engine != null ? part.type.engine.turnDeg : 0; }
    public double getEngineSize() { return part.type.engine != null ? part.type.engine.size : 0; }
    public boolean isThrottleExponential() { return part.type.engine != null && part.type.engine.throttleExponential; }
    public int getEngineFuelType() { return part.type.engine != null ? part.type.engine.fuelType : 0; }
    public double getRcsPower() { return part.type.rcs != null ? part.type.rcs.power : 0; }
    public double getRcsConsumption() { return part.type.rcs != null ? part.type.rcs.consumption : 0; }
    public double getSolarChargeRate() { return part.type.solar != null ? part.type.solar.chargeRate : 0; }
    public boolean hasLander() { return part.type.lander != null; }

    // ---------- actions ----------
    /** Sever all joints connecting this part (used by detachers). */
    public void detach() { part.detachJoints(); }

    public void setDeployed(boolean b) { part.deployed = b; }
    public boolean isDeployed() { return part.deployed; }

    // ---------- aerodynamics ----------
    /**
     * Effective drag coefficient of this part. If Lua set an absolute Cd via
     * setDrag, that value; otherwise the 0.75 baseline + PartList.xml `drag`
     * adjustment (nosecone drag="-1.0" -> 0.25, i.e. subtracts from ship total).
     */
    public double getDrag() {
        return !Double.isNaN(part.dragCd) ? part.dragCd : Math.max(0.0, 0.75 + part.type.drag);
    }
    /** Set this part's absolute drag coefficient (e.g. 8 for an open parachute). */
    public void setDrag(double cd) { part.dragCd = cd; }
    /** Reset this part's drag to the PartList.xml-derived default. */
    public void resetDrag() { part.dragCd = Double.NaN; }
    /** Drag reference area in m^2 (defaults to the part's width). */
    public double getDragArea() {
        return !Double.isNaN(part.dragArea) ? part.dragArea : part.type.width;
    }
    /** Set the drag reference area in m^2 (e.g. 36 for an open parachute canopy). */
    public void setDragArea(double a) { part.dragArea = a; }
    /** Reset the drag reference area to the default (part width). */
    public void resetDragArea() { part.dragArea = Double.NaN; }

    /** Spawn engine flame fx: size 0..1+, angleOffset in degrees from the part's down-nozzle direction. */
    public void emitFlame(double size, double angleOffsetDeg) { part.emitFlame((float) size, (float) angleOffsetDeg); }

    // ---------- joint & actuator customization (round 9) ----------

    /**
     * Override this part's weld-joint spring-damper params, e.g.
     *   part:setJointParams{frequencyHz=35, dampingRatio=1.2, angularDamping=0.05}
     * Any key may be omitted (nil -> inherit physics.lua `joints` table ->
     * Java defaults). When two parts are welded together, the override with
     * the HIGHER frequencyHz wins (the stiffer side rules the connection) and
     * its dampingRatio comes along; angularDamping applies to this part's own
     * body only.
     */
    public void setJointParams(org.luaj.vm2.LuaTable t) {
        if (t == null) return;
        part.jointFreqHz = optJointNum(t, "frequencyHz");
        part.jointDampRatio = optJointNum(t, "dampingRatio");
        part.jointAngDamp = optJointNum(t, "angularDamping");
        if (!Double.isNaN(part.jointAngDamp) && part.body != null) {
            part.body.setAngularDamping((float) part.jointAngDamp);
        }
    }

    private static double optJointNum(org.luaj.vm2.LuaTable t, String key) {
        org.luaj.vm2.LuaValue v = t.get(key);
        return v.isnumber() ? v.todouble() : Double.NaN;
    }

    /**
     * This part's own joint overrides as a Lua table (round 11 item 6) —
     * {frequencyHz=.., dampingRatio=.., angularDamping=..}, each key nil when
     * unset. joints.lua folds these into its default resolution rule.
     */
    public org.luaj.vm2.LuaTable getJointParams() {
        org.luaj.vm2.LuaTable t = new org.luaj.vm2.LuaTable();
        if (!Double.isNaN(part.jointFreqHz)) t.set("frequencyHz", part.jointFreqHz);
        if (!Double.isNaN(part.jointDampRatio)) t.set("dampingRatio", part.jointDampRatio);
        if (!Double.isNaN(part.jointAngDamp)) t.set("angularDamping", part.jointAngDamp);
        return t;
    }

    /** 0..1: how much of this part's cross-section is exposed to the airflow
     *  (occlusion-aware drag, round 11 item 2). 1 = fully exposed. */
    public double getDragExposure() { return part.dragExposure; }

    /**
     * Read a numeric entry from a physics.lua table, e.g.
     * part:physicsNumber("gimbal", "kp") -> `gimbal = { kp = ... }`, with
     * built-in defaults when the table/key is absent.
     */
    public double physicsNumber(String section, String key) {
        return PhysicsScript.tableNumber(section, key);
    }

    /** Actual gimbal deflection of this engine (deg), driven by the Lua PID. */
    public double getGimbalDeg() { return part.gimbalDeg; }
    public void setGimbalDeg(double deg) { part.gimbalDeg = (float) deg; }

    /** Log to console. */
    public void log(String msg) { System.out.println("[lua:" + part.type.id + "] " + msg); }
}
