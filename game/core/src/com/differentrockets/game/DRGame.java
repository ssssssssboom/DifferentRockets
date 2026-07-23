package com.differentrockets.game;

import com.badlogic.gdx.Game;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.differentrockets.ui.MenuScreen;
import com.differentrockets.ui.Ui;
import com.differentrockets.util.AtlasPack;
import com.differentrockets.util.Res;
public class DRGame extends Game {

    /** Global UI font scale (owner requirement: bigger buttons/touch targets). */
    public static final float FONT_SCALE = 1.75f;

    public SpriteBatch batch;
    public ShapeRenderer shapes;
    public BitmapFont font;
    public BitmapFont bigFont;
    public Ui ui;

    public AtlasPack shipSprites;
    public AtlasPack planetSprites;
    public AtlasPack runtimeSprites;

    public GameWorld world;

    @Override
    public void create() {
        Res.init(); // player-editable resource root (copies defaults on first run)

        batch = new SpriteBatch();
        shapes = new ShapeRenderer();
        font = new BitmapFont();
        font.getData().setScale(FONT_SCALE);
        bigFont = new BitmapFont();
        bigFont.getData().setScale(2.5f);
        ui = new Ui(font);

        PartList.load();
        ModManager.init();

        shipSprites = new AtlasPack(Res.asset("ShipSprites.xml"));
        planetSprites = new AtlasPack(Res.asset("PlanetSprites.xml"));
        runtimeSprites = new AtlasPack(Res.asset("Runtime.xml"));

        world = new GameWorld();

        setScreen(new MenuScreen(this));
    }

    /**
     * Re-read all resources from the (newly granted) external root without an
     * app restart: atlases, part defs, Lua mods, and the world itself (the
     * running session is saved first and restored afterwards).
     */
    public void reloadResources() {
        Gdx.app.log("res", "switching resource loading to the external root (no restart needed)");
        world.save();
        PartList.load();
        ModManager.reset();
        FlameScript.invalidate();
        TerrainScript.invalidate();
        PhysicsScript.invalidate();
        JointScript.invalidate();
        shipSprites.dispose();
        planetSprites.dispose();
        runtimeSprites.dispose();
        shipSprites = new AtlasPack(Res.asset("ShipSprites.xml"));
        planetSprites = new AtlasPack(Res.asset("PlanetSprites.xml"));
        runtimeSprites = new AtlasPack(Res.asset("Runtime.xml"));
        world.dispose();
        world = new GameWorld();
        world.load();
    }

    // steering ring convenience delegates (see GameWorld)
    public void setTargetHeading(double rad) { world.setTargetHeading(rad); }
    public double getTargetHeading() { return world.getTargetHeading(); }

    @Override
    public void dispose() {
        if (world != null) world.save();
        if (world != null) world.dispose();
        batch.dispose();
        shapes.dispose();
        font.dispose();
        bigFont.dispose();
        ui.dispose();
        shipSprites.dispose();
        planetSprites.dispose();
        if (runtimeSprites != null) runtimeSprites.dispose();
        super.dispose();
    }
}
