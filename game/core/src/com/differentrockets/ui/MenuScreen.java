package com.differentrockets.ui;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.ScreenAdapter;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;
import com.badlogic.gdx.utils.viewport.ScreenViewport;
import com.differentrockets.game.DRGame;

public class MenuScreen extends ScreenAdapter {

    private final DRGame game;
    private Stage stage;

    public MenuScreen(DRGame game) {
        this.game = game;
    }

    @Override
    public void show() {
        stage = new Stage(new ScreenViewport());
        Gdx.input.setInputProcessor(stage);

        Table root = new Table();
        root.setFillParent(true);
        root.setBackground(game.ui.tinted(new Color(0.05f, 0.07f, 0.12f, 1f)));
        stage.addActor(root);

        Label title = new Label("DifferentRockets", new Label.LabelStyle(game.bigFont, Color.WHITE));
        Label sub = new Label("A SimpleRockets-style 2D space sandbox", new Label.LabelStyle(game.font, new Color(0.6f, 0.7f, 0.9f, 1f)));

        TextButton build = new TextButton("Build New Rocket", game.ui.skin);
        TextButton cont = new TextButton("Continue Sandbox", game.ui.skin);
        TextButton reset = new TextButton("Reset World", game.ui.skin);

        build.addListener(new ClickListener() {
            @Override public void clicked(InputEvent e, float x, float y) {
                game.setScreen(new EditorScreen(game, null));
            }
        });
        cont.addListener(new ClickListener() {
            @Override public void clicked(InputEvent e, float x, float y) {
                game.world.load();
                // user request: pick WHICH ship to continue as before entering
                // the sandbox (skipped when the save holds 0 or 1 ship)
                java.util.List<com.differentrockets.game.Ship> ships = new java.util.ArrayList<>();
                for (com.differentrockets.game.Ship s : game.world.ships) {
                    if (!s.parts.isEmpty()) ships.add(s);
                }
                if (ships.size() <= 1) {
                    if (ships.size() == 1) game.world.setActive(ships.get(0));
                    game.setScreen(new SandboxScreen(game));
                } else {
                    showShipPicker(ships);
                }
            }
        });
        reset.addListener(new ClickListener() {
            @Override public void clicked(InputEvent e, float x, float y) {
                game.world.clearSave();
                for (com.differentrockets.game.Ship s : new java.util.ArrayList<>(game.world.ships)) s.destroy();
                game.world.ships.clear();
                game.world.active = null;
                game.world.setTime(0);
            }
        });

        root.add(title).padBottom(12).row();
        root.add(sub).padBottom(80).row();
        root.add(build).width(560).height(120).pad(16).row();
        root.add(cont).width(560).height(120).pad(16).row();
        root.add(reset).width(560).height(120).pad(16).row();

        // build version stamp (yyyymmddhhmm from assets/version.txt, "dev" otherwise)
        Label ver = new Label("v" + game.version, game.ui.skin);
        ver.setFontScale(0.55f);
        ver.setColor(new Color(0.5f, 0.55f, 0.68f, 1f));
        ver.setPosition(10, 8);
        stage.addActor(ver);
    }

    /** Continue-ship picker: modal list of the saved ships; CANCEL stays. */
    private Table shipPicker;

    private void showShipPicker(final java.util.List<com.differentrockets.game.Ship> ships) {
        closeShipPicker();
        shipPicker = new Table();
        shipPicker.setBackground(game.ui.tinted(new Color(0.10f, 0.11f, 0.16f, 0.95f)));
        shipPicker.add(new Label("Continue as which ship?", game.ui.skin)).pad(14).row();
        for (final com.differentrockets.game.Ship s : ships) {
            TextButton b = new TextButton(s.name, game.ui.skin);
            b.addListener(new ClickListener() {
                @Override public void clicked(InputEvent e, float x, float y) {
                    game.world.setActive(s);
                    game.setScreen(new SandboxScreen(game));
                }
            });
            shipPicker.add(b).width(420).height(80).pad(4).row();
        }
        TextButton cancel = new TextButton("CANCEL", game.ui.skin);
        cancel.addListener(new ClickListener() {
            @Override public void clicked(InputEvent e, float x, float y) { closeShipPicker(); }
        });
        shipPicker.add(cancel).width(420).height(80).pad(8).row();
        shipPicker.pack();
        shipPicker.setPosition((Gdx.graphics.getWidth() - shipPicker.getWidth()) / 2f,
                (Gdx.graphics.getHeight() - shipPicker.getHeight()) / 2f);
        stage.addActor(shipPicker);
    }

    private void closeShipPicker() {
        if (shipPicker != null) {
            shipPicker.remove();
            shipPicker = null;
        }
    }

    @Override
    public void render(float delta) {
        Gdx.gl.glClearColor(0.05f, 0.07f, 0.12f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        stage.act(delta);
        stage.draw();
    }

    @Override
    public void resize(int w, int h) {
        if (stage != null) stage.getViewport().update(w, h, true);
    }

    @Override
    public void dispose() {
        if (stage != null) stage.dispose();
    }
}
