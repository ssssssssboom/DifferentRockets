package com.differentrockets.game;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Global registry of part types parsed from PartList.xml. */
public final class PartList {
    private static Map<String, PartType> types;

    private PartList() {}

    public static void load() {
        types = PartType.load(com.differentrockets.util.Res.asset("PartList.xml"));
        // command pods carry a small electric reserve (part of the electric network)
        for (PartType t : types.values()) {
            if ("pod".equals(t.type) && t.tank == null) {
                PartType.TankDef d = new PartType.TankDef();
                d.fuel = 50.0;
                d.dryMassTons = Math.max(0.05, t.massTons - 0.05);
                d.fuelType = PartType.FUEL_ELECTRIC;
                t.tank = d;
            }
        }
    }

    public static PartType get(String id) { return types.get(id); }

    public static List<PartType> all() { return new ArrayList<>(types.values()); }

    /** Parts shown in the editor palette (not hidden). */
    public static List<PartType> palette() {
        List<PartType> out = new ArrayList<>();
        for (PartType t : types.values()) {
            if (!t.hidden) out.add(t);
        }
        return out;
    }
}
