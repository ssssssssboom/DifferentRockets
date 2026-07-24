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
                game.setScreen(new SandboxScreen(game));
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
