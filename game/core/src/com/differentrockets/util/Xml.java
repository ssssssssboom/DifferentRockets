package com.differentrockets.util;

import com.badlogic.gdx.utils.XmlReader;

/** XmlReader.Element lacks getDoubleAttribute; this fills the gap. */
public final class Xml {
    private Xml() {}

    public static double getDouble(XmlReader.Element e, String name, double def) {
        String v = e.getAttribute(name, null);
        if (v == null) return def;
        try { return Double.parseDouble(v.trim()); } catch (Exception ex) { return def; }
    }
}
