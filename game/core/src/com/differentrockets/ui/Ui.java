package com.differentrockets.ui;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.Slider;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable;
import com.badlogic.gdx.utils.Disposable;

/** Programmatic scene2d skin (clean functional UI without GUI atlas art). */
public class Ui implements Disposable {

    /** Global UI scale-up (task C1): applied on top of DRGame.FONT_SCALE. */
    public static final float UI_SCALE = 1.35f;

    public final Skin skin;
    /** Effective font scale after the C1 global upscale; screens that reset
     *  game.font mid-render should restore this value, not DRGame.FONT_SCALE. */
    public final float fontScale;
    private final Texture white;

    public Ui(BitmapFont font) {
        fontScale = font.getData().scaleX * UI_SCALE;
        font.getData().setScale(fontScale);

        Pixmap pm = new Pixmap(8, 8, Pixmap.Format.RGBA8888);
        pm.setColor(Color.WHITE);
        pm.fill();
        white = new Texture(pm);
        pm.dispose();

        skin = new Skin();
        skin.add("white", white);
        skin.add("default", font);

        Label.LabelStyle label = new Label.LabelStyle(font, Color.WHITE);
        skin.add("default", label);

        TextButton.TextButtonStyle tb = new TextButton.TextButtonStyle();
        tb.font = font;
        tb.up = tinted(new Color(0.22f, 0.25f, 0.33f, 1f));
        tb.down = tinted(new Color(0.35f, 0.4f, 0.55f, 1f));
        tb.over = tinted(new Color(0.3f, 0.34f, 0.45f, 1f));
        // round 14: scene2d Buttons toggle `checked` on EVERY click, which left
        // the greenish checked tint stuck on after the finger lifted. Make the
        // checked face identical to `up` so buttons always return to gray on
        // touch-up (momentary feedback comes from `down`/`over` only).
        tb.checked = tinted(new Color(0.22f, 0.25f, 0.33f, 1f));
        tb.fontColor = Color.WHITE;
        skin.add("default", tb);

        TextField.TextFieldStyle tf = new TextField.TextFieldStyle();
        tf.font = font;
        tf.fontColor = Color.WHITE;
        tf.background = tinted(new Color(0.12f, 0.13f, 0.18f, 1f));
        tf.cursor = tinted(Color.WHITE);
        tf.selection = tinted(new Color(0.3f, 0.4f, 0.6f, 0.7f));
        skin.add("default", tf);

        Slider.SliderStyle sl = new Slider.SliderStyle();
        sl.background = tinted(new Color(0.15f, 0.16f, 0.22f, 1f));
        sl.knob = tinted(new Color(0.45f, 0.6f, 0.9f, 1f));
        sl.knobOver = tinted(new Color(0.55f, 0.7f, 1f, 1f));
        sl.knobDown = tinted(new Color(0.6f, 0.75f, 1f, 1f));
        skin.add("default-horizontal", sl);

        com.badlogic.gdx.scenes.scene2d.ui.ScrollPane.ScrollPaneStyle sp =
                new com.badlogic.gdx.scenes.scene2d.ui.ScrollPane.ScrollPaneStyle();
        sp.background = tinted(new Color(0.1f, 0.11f, 0.15f, 1f));
        sp.vScroll = tinted(new Color(0.15f, 0.16f, 0.22f, 1f));
        sp.vScrollKnob = tinted(new Color(0.45f, 0.6f, 0.9f, 1f));
        skin.add("default", sp);

        com.badlogic.gdx.scenes.scene2d.ui.List.ListStyle ls =
                new com.badlogic.gdx.scenes.scene2d.ui.List.ListStyle();
        ls.font = font;
        ls.fontColorSelected = Color.WHITE;
        ls.fontColorUnselected = new Color(0.85f, 0.85f, 0.9f, 1f);
        ls.selection = tinted(new Color(0.3f, 0.4f, 0.6f, 1f));
        ls.background = tinted(new Color(0.1f, 0.11f, 0.15f, 1f));
        skin.add("default", ls);

        com.badlogic.gdx.scenes.scene2d.ui.SelectBox.SelectBoxStyle sb =
                new com.badlogic.gdx.scenes.scene2d.ui.SelectBox.SelectBoxStyle();
        sb.font = font;
        sb.fontColor = Color.WHITE;
        sb.background = tinted(new Color(0.22f, 0.25f, 0.33f, 1f));
        sb.scrollStyle = sp;
        sb.listStyle = ls;
        skin.add("default", sb);

        com.badlogic.gdx.scenes.scene2d.ui.Window.WindowStyle ws =
                new com.badlogic.gdx.scenes.scene2d.ui.Window.WindowStyle();
        ws.titleFont = font;
        ws.titleFontColor = Color.WHITE;
        ws.background = tinted(new Color(0.12f, 0.13f, 0.18f, 1f));
        skin.add("default", ws);
    }

    public com.badlogic.gdx.scenes.scene2d.utils.Drawable tinted(Color c) {
        return new TextureRegionDrawable(white).tint(c);
    }

    @Override
    public void dispose() {
        skin.dispose();
        white.dispose();
    }
}
