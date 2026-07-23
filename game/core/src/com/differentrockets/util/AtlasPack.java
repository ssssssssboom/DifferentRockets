package com.differentrockets.util;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.utils.XmlReader;

import java.util.HashMap;
import java.util.Map;

/**
 * Tiny parser for TexturePacker XML atlases (sprite n,x,y,w,h; y measured from top-left).
 * Sprites flagged r="y" are rotated 90° clockwise in the atlas (CodeAndWeb
 * TexturePacker convention); w/h in the XML are the PACKED rectangle, so the
 * unrotated sprite is h×w. {@link #extractUnrotated(String)} bakes such a
 * sprite back to its original orientation for direct drawing.
 */
public class AtlasPack {
    public final Texture texture;
    private final Map<String, TextureRegion> regions = new HashMap<>();
    private final Map<String, int[]> rects = new HashMap<>();      // x,y,w,h (packed)
    private final Map<String, Boolean> rotated = new HashMap<>();

    public AtlasPack(FileHandle xmlFile) {
        try {
            XmlReader.Element root = new XmlReader().parse(xmlFile);
            FileHandle img = xmlFile.sibling(root.getAttribute("imagePath"));
            texture = new Texture(img);
            texture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
            for (int i = 0; i < root.getChildCount(); i++) {
                XmlReader.Element s = root.getChild(i);
                String n = s.getAttribute("n");
                int x = s.getIntAttribute("x");
                int y = s.getIntAttribute("y");
                int w = s.getIntAttribute("w");
                int h = s.getIntAttribute("h");
                regions.put(n, new TextureRegion(texture, x, y, w, h));
                rects.put(n, new int[]{x, y, w, h});
                rotated.put(n, "y".equals(s.getAttribute("r", "")));
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse atlas " + xmlFile, e);
        }
    }

    /** Lookup with case-insensitive extension handling (xml mixes .png/.PNG). */
    public TextureRegion find(String name) {
        if (name == null) return null;
        TextureRegion r = regions.get(name);
        if (r != null) return r;
        String lower = name.toLowerCase();
        for (Map.Entry<String, TextureRegion> e : regions.entrySet()) {
            if (e.getKey().toLowerCase().equals(lower)) return e.getValue();
        }
        return null;
    }

    private String canonical(String name) {
        if (name == null) return null;
        if (rects.containsKey(name)) return name;
        String lower = name.toLowerCase();
        for (String k : rects.keySet()) {
            if (k.toLowerCase().equals(lower)) return k;
        }
        return null;
    }

    /** True when the sprite is stored rotated 90° CW in the atlas. */
    public boolean isRotated(String name) {
        String k = canonical(name);
        return k != null && Boolean.TRUE.equals(rotated.get(k));
    }

    /**
     * Copy a sprite out of the atlas into its own Texture, undoing the r="y"
     * packing rotation (90° CCW restore) so the result draws upright. Caller
     * owns the returned Texture. Returns null when the sprite is unknown.
     */
    public Texture extractUnrotated(String name) {
        String k = canonical(name);
        if (k == null) return null;
        int[] r = rects.get(k);
        texture.getTextureData().prepare();
        Pixmap pm = texture.getTextureData().consumePixmap();
        int x = r[0], y = r[1], w = r[2], h = r[3];
        Pixmap sub = new Pixmap(w, h, Pixmap.Format.RGBA8888);
        sub.drawPixmap(pm, x, y, w, h, 0, 0, w, h);
        Texture out;
        if (!Boolean.TRUE.equals(rotated.get(k))) {
            out = new Texture(sub);
        } else {
            // CCW restore: O(X,Y) = P(w-1-Y, X), O is h wide × w tall
            Pixmap rot = new Pixmap(h, w, Pixmap.Format.RGBA8888);
            for (int Y = 0; Y < w; Y++) {
                for (int X = 0; X < h; X++) {
                    rot.drawPixel(X, Y, sub.getPixel(w - 1 - Y, X));
                }
            }
            out = new Texture(rot);
            rot.dispose();
        }
        sub.dispose();
        pm.dispose();
        out.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
        return out;
    }

    public void dispose() { texture.dispose(); }
}
