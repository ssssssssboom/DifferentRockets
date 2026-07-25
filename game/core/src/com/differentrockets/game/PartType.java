package com.differentrockets.game;

import com.differentrockets.util.Xml;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.utils.XmlReader;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Static definition of a part type, parsed from PartList.xml. */
public class PartType {

    public static final int FUEL_LIQUID = 0;
    public static final int FUEL_MONO = 1;
    public static final int FUEL_ELECTRIC = 2;
    public static final int FUEL_SOLID = 3;

    public static class Vertex {
        public float x, y;
        public Vertex(float x, float y) { this.x = x; this.y = y; }
    }

    public static class ShapeDef {
        public List<Vertex> verts = new ArrayList<>();
        public boolean sensor;
    }

    public static class AttachPoint {
        public float x, y;          // local meters, y-up
        public boolean fuelLine;
        public float breakAngle = 180f;
        public float breakForce = Float.MAX_VALUE;
        public int group;
        public boolean flipX;
        public int order;
        /**
         * Edge-type attach (round 11 item 5): EDGE_* means the WHOLE side
         * segment is attachable — a mating part may contact anywhere along it
         * (editor snaps slide along the edge; the weld anchors at the contact
         * point). EDGE_NONE = classic single point (TopCenter etc.).
         * LeftSide/RightSide/Top/Bottom (no "Center") are the edge locations.
         */
        public static final int EDGE_NONE = 0, EDGE_LEFT = 1, EDGE_RIGHT = 2,
                EDGE_TOP = 3, EDGE_BOTTOM = 4;
        public int edge = EDGE_NONE;
    }

    public static class EngineDef {
        public double power;          // -> thrust N = power * 1e6
        public double consumption;    // fuel units per second at full throttle
        public float size = 1f;
        public float turnDeg;         // gimbal range
        public boolean throttleExponential;
        public int fuelType = FUEL_LIQUID;
    }

    public static class TankDef {
        public double fuel;
        public double dryMassTons;
        public int fuelType = FUEL_LIQUID;
    }

    public static class RcsDef {
        public double power;
        public double consumption;
        public float size = 1f;
    }

    public static class SolarDef {
        public double chargeRate;
    }

    public static class LanderDef {
        public float maxAngle, minLength, maxLength, angleSpeed, lengthSpeed, width;
    }

    /**
     * Wheel (round 27): the part body is a small AXLE circle (radius
     * axleRadius, collides with other parts/terrain as usual); a second body,
     * the TIRE (diameter = the part's width), hangs on a revolute joint with
     * a motor and only collides with terrain and other tires — never with
     * parts — so the chassis can sit between the wheels without contact
     * fighting. Locked until staged (wheel-*.lua); once unlocked the motor
     * torque follows the nav ring / turn buttons.
     */
    public static class WheelDef {
        public float axleRadius = 1.0f;  // XML length units (0.5 m each)
        public float maxTorque = 4000f;  // N*m at full drive
        public float maxSpeed = 10f;     // motor target rad/s (tire peripheral = 20 m/s at r=2)
        public float lockTorque = 8000f; // holding torque while locked/pre-stage
    }

    /** Box2D collision categories: parts/axles, terrain blocks, wheel tires. */
    public static final short CAT_PART = 0x0001, CAT_TERRAIN = 0x0002, CAT_TIRE = 0x0004;

    public String id;
    public String name;
    public String sprite;
    public String type;
    /** XML mass unit = 500 kg (owner calibration: pod mass=1.0 means 500 kg). */
    public double massTons;
    public float width, height;       // XML length unit = 0.5 m (strut width=16 is 8 m)
    public float buoyancy = 0f;
    public String category = "";
    public boolean hidden, sandboxOnly;
    public boolean ignoreEditorIntersections, disableEditorRotation;
    public int maxOccurrences = -1;
    public boolean canExplode = true;
    public float friction = 0.4f;
    public float drag = 0f;           // nosecone uses negative
    public float coverHeight = 0f;

    public EngineDef engine;
    public TankDef tank;
    public RcsDef rcs;
    public SolarDef solar;
    public LanderDef lander;
    public WheelDef wheel;
    public final List<ShapeDef> shapes = new ArrayList<>();
    public final List<AttachPoint> attach = new ArrayList<>();

    /** kg = XML mass units * 500 (unit = 500 kg, owner calibration round 27). */
    public double massKg() { return massTons * 500.0; }

    public boolean isEngine() { return engine != null; }
    public boolean isTank() { return tank != null; }

    public static Map<String, PartType> load(FileHandle file) {
        Map<String, PartType> map = new LinkedHashMap<>();
        try {
            String text = file.readString();
            if (!text.isEmpty() && text.charAt(0) == '﻿') text = text.substring(1);
            XmlReader.Element root = new XmlReader().parse(text);
            for (int i = 0; i < root.getChildCount(); i++) {
                XmlReader.Element e = root.getChild(i);
                if (!"PartType".equals(e.getName())) continue;
                PartType t = new PartType();
                t.id = e.getAttribute("id");
                t.name = e.getAttribute("name", t.id);
                t.sprite = e.getAttribute("sprite", null);
                t.type = e.getAttribute("type", "structural");
                t.massTons = Xml.getDouble(e, "mass", 1.0);
                t.width = e.getFloatAttribute("width", 1f);
                t.height = e.getFloatAttribute("height", 1f);
                t.buoyancy = e.getFloatAttribute("buoyancy", 0f);
                t.category = e.getAttribute("category", "");
                t.hidden = e.getBooleanAttribute("hidden", false);
                t.sandboxOnly = e.getBooleanAttribute("sandboxOnly", false);
                t.ignoreEditorIntersections = e.getBooleanAttribute("ignoreEditorIntersections", false);
                t.disableEditorRotation = e.getBooleanAttribute("disableEditorRotation", false);
                t.maxOccurrences = e.getIntAttribute("maxOccurrences", -1);
                t.canExplode = e.getBooleanAttribute("canExplode", true);
                t.friction = e.getFloatAttribute("friction", 0.4f);
                t.drag = e.getFloatAttribute("drag", 0f);
                t.coverHeight = e.getFloatAttribute("coverHeight", 0f);

                for (int c = 0; c < e.getChildCount(); c++) {
                    XmlReader.Element ch = e.getChild(c);
                    String cn = ch.getName();
                    if ("Engine".equals(cn)) {
                        EngineDef d = new EngineDef();
                        d.power = Xml.getDouble(ch, "power", 0);
                        d.consumption = Xml.getDouble(ch, "consumption", 0);
                        d.size = ch.getFloatAttribute("size", 1f);
                        d.turnDeg = ch.getFloatAttribute("turn", 0f);
                        d.throttleExponential = ch.getBooleanAttribute("throttleExponential", false);
                        d.fuelType = ch.getIntAttribute("fuelType", FUEL_LIQUID);
                        t.engine = d;
                    } else if ("Tank".equals(cn)) {
                        TankDef d = new TankDef();
                        d.fuel = Xml.getDouble(ch, "fuel", 0);
                        d.dryMassTons = Xml.getDouble(ch, "dryMass", 0.1);
                        d.fuelType = ch.getIntAttribute("fuelType", FUEL_LIQUID);
                        t.tank = d;
                    } else if ("Rcs".equals(cn)) {
                        RcsDef d = new RcsDef();
                        d.power = Xml.getDouble(ch, "power", 0);
                        d.consumption = Xml.getDouble(ch, "consumption", 0);
                        d.size = ch.getFloatAttribute("size", 1f);
                        t.rcs = d;
                    } else if ("Solar".equals(cn)) {
                        SolarDef d = new SolarDef();
                        d.chargeRate = Xml.getDouble(ch, "chargeRate", 0);
                        t.solar = d;
                    } else if ("Lander".equals(cn)) {
                        LanderDef d = new LanderDef();
                        d.maxAngle = ch.getFloatAttribute("maxAngle", 140);
                        d.minLength = ch.getFloatAttribute("minLength", 2);
                        d.maxLength = ch.getFloatAttribute("maxLength", 4);
                        d.angleSpeed = ch.getFloatAttribute("angleSpeed", 25);
                        d.lengthSpeed = ch.getFloatAttribute("lengthSpeed", 0.5f);
                        d.width = ch.getFloatAttribute("width", 0.5f);
                        t.lander = d;
                    } else if ("Wheel".equals(cn)) {
                        WheelDef d = new WheelDef();
                        d.axleRadius = ch.getFloatAttribute("axleRadius", 1.0f);
                        d.maxTorque = ch.getFloatAttribute("maxTorque", 4000f);
                        d.maxSpeed = ch.getFloatAttribute("maxSpeed", 10f);
                        d.lockTorque = ch.getFloatAttribute("lockTorque", 8000f);
                        t.wheel = d;
                    } else if ("Shape".equals(cn)) {
                        ShapeDef sd = new ShapeDef();
                        sd.sensor = ch.getBooleanAttribute("sensor", false);
                        for (int v = 0; v < ch.getChildCount(); v++) {
                            XmlReader.Element ve = ch.getChild(v);
                            sd.verts.add(new Vertex(ve.getFloatAttribute("x"), ve.getFloatAttribute("y")));
                        }
                        t.shapes.add(sd);
                    } else if ("AttachPoints".equals(cn)) {
                        for (int a = 0; a < ch.getChildCount(); a++) {
                            XmlReader.Element ae = ch.getChild(a);
                            AttachPoint ap = new AttachPoint();
                            String loc = ae.getAttribute("location", null);
                            if (loc != null) {
                                applyLocation(ap, loc, t.width, t.height);
                            } else {
                                ap.x = ae.getFloatAttribute("x", 0f);
                                ap.y = ae.getFloatAttribute("y", 0f);
                            }
                            ap.fuelLine = ae.getBooleanAttribute("fuelLine", false);
                            ap.breakAngle = ae.getFloatAttribute("breakAngle", 180f);
                            ap.breakForce = (float) Xml.getDouble(ae, "breakForce", Double.MAX_VALUE);
                            ap.group = ae.getIntAttribute("group", 0);
                            ap.flipX = ae.getBooleanAttribute("flipX", false);
                            ap.order = ae.getIntAttribute("order", 0);
                            t.attach.add(ap);
                        }
                    }
                }
                map.put(t.id, t);
            }
        } catch (Exception ex) {
            throw new RuntimeException("Failed to parse PartList.xml", ex);
        }
        return map;
    }

    private static void applyLocation(AttachPoint ap, String loc, float w, float h) {
        switch (loc) {
            case "TopCenter": ap.x = 0; ap.y = h / 2f; break;
            case "BottomCenter": ap.x = 0; ap.y = -h / 2f; break;
            case "LeftCenter": ap.x = -w / 2f; ap.y = 0; break;
            case "RightCenter": ap.x = w / 2f; ap.y = 0; break;
            // whole-edge attach locations: the anchor sits at the edge CENTER
            // and the `edge` tag marks the segment as slidable (item 5)
            case "LeftSide": ap.x = -w / 2f; ap.y = 0; ap.edge = AttachPoint.EDGE_LEFT; break;
            case "RightSide": ap.x = w / 2f; ap.y = 0; ap.edge = AttachPoint.EDGE_RIGHT; break;
            case "Top": ap.x = 0; ap.y = h / 2f; ap.edge = AttachPoint.EDGE_TOP; break;
            case "Bottom": ap.x = 0; ap.y = -h / 2f; ap.edge = AttachPoint.EDGE_BOTTOM; break;
            default: ap.x = 0; ap.y = 0; break;
        }
    }
}
