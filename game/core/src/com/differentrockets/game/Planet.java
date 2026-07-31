package com.differentrockets.game;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.utils.XmlReader;
import com.differentrockets.util.Vec2d;
import com.differentrockets.util.Xml;

import java.util.ArrayList;
import java.util.List;

/**
 * A celestial body parsed from SmolarSystem.xml.
 * Planets sit on Kepler rails relative to their parent body.
 */
public class Planet {
    public String name;
    public double gravity;        // surface gravity m/s^2
    public double radius;         // m
    public Color mapColor = new Color(0.7f, 0.7f, 0.7f, 1f);
    public String icon;
    public boolean launchEnabled = true;
    public String description = "";

    // Orbit elements (relative to parent)
    public double a, e, w, v0;
    public boolean prograde = true;

    // Terrain
    public double maxHeight, minHeight;
    public double noise = 2.0;    // roughness
    public String crustTexture;
    public Color crustColor = new Color(0.4f, 0.3f, 0.2f, 1f);
    public double waterDensity = 0; // 0 = no ocean

    public static class Range {
        public double startDeg, endDeg, minH, maxH;
    }
    public final List<Range> ranges = new ArrayList<>();

    // Atmosphere
    public double atmoHeight = 0;       // m
    public double surfacePressure = 0;  // atm-ish units (1.0 = Smearth sea level)

    public Planet parent;
    public final List<Planet> children = new ArrayList<>();

    // runtime state
    public final Vec2d pos = new Vec2d();    // universe position (double meters)
    public final Vec2d vel = new Vec2d();    // universe velocity (m/s), derived from rails

    public double mu() { return gravity * radius * radius; }

    public boolean hasAtmosphere() { return atmoHeight > 0 && surfacePressure > 0; }
    public double scaleHeight() { return atmoHeight / 7.0; }

    /** Air density in kg/m^3 at altitude h (exponential model). */
    public double densityAt(double h) {
        if (!hasAtmosphere() || h > atmoHeight || h < -scaleHeight() * 3) return 0;
        // SR RE (docs/sr-physics-re.md §5, priority #5): hard cutoff — SR zeroes
        // density when PRESSURE drops below 0.1 Pa (binary literal @ 0x191db0).
        // Our surfacePressure is in atm-ish units (1.0 = Smearth sea level), so
        // 0.1 Pa = 0.1 / 101325 of one atmosphere.
        double p = pressureAt(h); // units of surface pressure
        if (p * surfacePressure0Pa() < 0.1) return 0;
        // 1.225 kg/m^3 at pressure 1.0
        return 1.225 * surfacePressure * Math.exp(-Math.max(h, 0) / scaleHeight());
    }

    /** Pa represented by surfacePressure = 1.0 (Earth-like 1 atm). */
    private double surfacePressure0Pa() { return 101325.0; }

    /** Pressure at altitude h in units of surface pressure. */
    public double pressureAt(double h) {
        if (!hasAtmosphere() || h > atmoHeight) return 0;
        return surfacePressure * Math.exp(-Math.max(h, 0) / scaleHeight());
    }

    // ---------------- Orbits ----------------

    private double solveKepler(double M) {
        // round 14: wrap M to [-pi, pi] and seed E = M + e·sin(M) — Newton
        // from E=M diverges for large M (long sessions / high warp) and high
        // eccentricity, which folded orbits and predicted trajectories.
        M = (M + Math.PI) % (2 * Math.PI);
        if (M < 0) M += 2 * Math.PI;
        M -= Math.PI;
        double E = M + e * Math.sin(M);
        for (int i = 0; i < 12; i++) {
            E = E - (E - e * Math.sin(E) - M) / (1 - e * Math.cos(E));
        }
        return E;
    }

    /** Position relative to parent at time t (seconds since epoch), in parent's frame. */
    private void localPosVel(double t, Vec2d outPos, Vec2d outVel) {
        if (parent == null) { outPos.set(0, 0); outVel.set(0, 0); return; }
        double muP = parent.mu();
        double n = Math.sqrt(muP / (a * a * a)); // mean motion
        double M = n * t + v0;
        if (!prograde) M = -M;
        double E = solveKepler(M);
        double cosE = Math.cos(E), sinE = Math.sin(E);
        // position in orbital plane (periapsis-aligned)
        double xp = a * (cosE - e);
        double yp = a * Math.sqrt(1 - e * e) * sinE;
        // velocity in orbital plane
        double fac = n * a / (1 - e * cosE);
        double vxp = -fac * sinE;
        double vyp = fac * Math.sqrt(1 - e * e) * cosE;
        if (!prograde) { vxp = -vxp; vyp = -vyp; }
        // rotate by argument of periapsis
        double cw = Math.cos(w), sw = Math.sin(w);
        outPos.set(xp * cw - yp * sw, xp * sw + yp * cw);
        outVel.set(vxp * cw - vyp * sw, vxp * sw + vyp * cw);
    }

    private final Vec2d tmpP = new Vec2d();
    private final Vec2d tmpV = new Vec2d();

    /** Update this planet's universe position/velocity at time t. */
    public void updateRails(double t) {
        if (parent != null) {
            localPosVel(t, tmpP, tmpV);
            pos.set(parent.pos).add(tmpP);
            vel.set(parent.vel).add(tmpV);
        } else {
            pos.set(0, 0);
            vel.set(0, 0);
        }
        for (Planet c : children) c.updateRails(t);
    }

    // ---------------- Terrain height ----------------

    /** Height above/below nominal radius at angle (radians, world frame). */
    public double heightAt(double angleRad) {
        // player-editable generator (mod/terrain.lua); falls back to built-in.
        // Round 18 fix: route through surfaceHeight FIRST so gameplay queries
        // (altitude, spawn, rails floors) match the collision/render columns,
        // specialTerrains included; terrainHeight (no special regions) is the
        // fallback for older player scripts without surfaceHeight.
        double h = TerrainScript.heightAboveDatum(name, angleRad);
        if (!Double.isNaN(h) && !Double.isInfinite(h)) return h;
        h = TerrainScript.heightAt(name, angleRad);
        if (!Double.isNaN(h)) return h;
        return builtinHeightAt(angleRad);
    }

    /** Built-in generator (used as fallback and mirrored by mods/terrain.lua). */
    public double builtinHeightAt(double angleRad) {
        double deg = Math.toDegrees(angleRad);
        deg = ((deg % 360) + 360) % 360;
        double lo = minHeight, hi = maxHeight;
        for (Range r : ranges) {
            double s = norm(r.startDeg), en = norm(r.endDeg);
            boolean in = s <= en ? (deg >= s && deg <= en) : (deg >= s || deg <= en);
            if (in) { lo = r.minH; hi = r.maxH; break; }
        }
        double span = hi - lo;
        if (span <= 0.0001) return lo;
        // deterministic seeded value noise across [0..360)
        double seed = name.hashCode();
        double sum = 0, amp = 1, norm = 0;
        int baseFreq = (int) Math.round(Math.max(2.0, 6.0 + noise * 0.6));
        for (int oct = 0; oct < 4; oct++) {
            int f = baseFreq * (1 << oct);
            sum += amp * valueNoise(deg / 360.0 * f, f, seed + oct * 131.7);
            norm += amp;
            amp *= 0.5;
        }
        double n01 = (sum / norm + 1) * 0.5; // [0,1]
        double rough = Math.min(2.5, 0.25 + noise * 0.28);
        double shaped = Math.pow(n01, rough);
        return lo + span * shaped;
    }

    private static double norm(double d) { return ((d % 360) + 360) % 360; }

    private static double hash(double i, double seed) {
        double x = Math.sin(i * 127.1 + seed * 311.7) * 43758.5453;
        return x - Math.floor(x);
    }

    /** Seam-free 1D value noise in [-1,1]; lattice wraps with the given integer period. */
    private static double valueNoise(double x, double period, double seed) {
        double xi = Math.floor(x);
        double xf = x - xi;
        double u = xf * xf * (3 - 2 * xf);
        double i0 = ((xi % period) + period) % period;
        double i1 = (i0 + 1) % period;
        return lerp(hash(i0, seed), hash(i1, seed), u) * 2 - 1;
    }

    private static double lerp(double a, double b, double t) { return a + (b - a) * t; }

    // ---------------- Parsing ----------------

    public static Planet loadSolarSystem(FileHandle file) {
        try {
            String text = file.readString();
            if (!text.isEmpty() && text.charAt(0) == '﻿') text = text.substring(1);
            XmlReader.Element root = new XmlReader().parse(text);
            XmlReader.Element sunEl = root.getChildByName("Planet");
            if (sunEl == null) sunEl = root.getChild(0);
            Planet sun = parsePlanet(sunEl, null);
            return sun;
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse SmolarSystem.xml", e);
        }
    }

    private static Planet parsePlanet(XmlReader.Element e, Planet parent) {
        Planet p = new Planet();
        p.parent = parent;
        p.name = e.getAttribute("name");
        p.gravity = Xml.getDouble(e, "gravity", 0);
        p.radius = Xml.getDouble(e, "radius", 1000);
        p.mapColor = parseColor(e.getAttribute("mapColor", "180,180,180"));
        p.icon = e.getAttribute("icon", null);
        p.launchEnabled = e.getBooleanAttribute("launchEnabled", true);
        p.description = e.getAttribute("description", "");

        XmlReader.Element orbit = e.getChildByName("Orbit");
        if (orbit != null) {
            p.a = Xml.getDouble(orbit, "a", 0);
            p.e = Xml.getDouble(orbit, "e", 0);
            p.w = Xml.getDouble(orbit, "w", 0);
            p.v0 = Xml.getDouble(orbit, "v", 0);
            p.prograde = orbit.getIntAttribute("prograde", 1) != 0;
            // use initial true anomaly to derive time offset: handled by GameWorld via epoch
        }
        XmlReader.Element terrain = e.getChildByName("Terrain");
        if (terrain != null) {
            p.maxHeight = Xml.getDouble(terrain, "maxHeight", 0);
            p.minHeight = Xml.getDouble(terrain, "minHeight", 0);
            p.noise = Xml.getDouble(terrain, "noise", 2.0);
            p.crustTexture = terrain.getAttribute("texture", null);
            p.crustColor = parseColor(terrain.getAttribute("color", "100,80,60"));
            p.waterDensity = Xml.getDouble(terrain, "waterDensity", 0);
            XmlReader.Element ranges = terrain.getChildByName("Ranges");
            if (ranges != null) {
                for (int i = 0; i < ranges.getChildCount(); i++) {
                    XmlReader.Element re = ranges.getChild(i);
                    Range r = new Range();
                    r.startDeg = Xml.getDouble(re, "startAngle", 0);
                    r.endDeg = Xml.getDouble(re, "endAngle", 0);
                    r.minH = Xml.getDouble(re, "minHeight", 0);
                    r.maxH = Xml.getDouble(re, "maxHeight", 0);
                    p.ranges.add(r);
                }
            }
        }
        XmlReader.Element atmo = e.getChildByName("Atmosphere");
        if (atmo != null) {
            p.atmoHeight = Xml.getDouble(atmo, "height", 0);
            p.surfacePressure = Xml.getDouble(atmo, "surfacePressure", 0);
        }
        XmlReader.Element children = e.getChildByName("Children");
        if (children != null) {
            for (int i = 0; i < children.getChildCount(); i++) {
                p.children.add(parsePlanet(children.getChild(i), p));
            }
        }
        return p;
    }

    private static Color parseColor(String s) {
        try {
            String[] parts = s.split(",");
            return new Color(Integer.parseInt(parts[0].trim()) / 255f,
                    Integer.parseInt(parts[1].trim()) / 255f,
                    Integer.parseInt(parts[2].trim()) / 255f, 1f);
        } catch (Exception ex) {
            return new Color(0.7f, 0.7f, 0.7f, 1f);
        }
    }

    /** Flatten into a list (parent-first). */
    public void flatten(List<Planet> out) {
        out.add(this);
        for (Planet c : children) c.flatten(out);
    }

    public Planet findLaunchable(String name) {
        if (this.name.equalsIgnoreCase(name) && launchEnabled) return this;
        for (Planet c : children) {
            Planet r = c.findLaunchable(name);
            if (r != null) return r;
        }
        return null;
    }
}
