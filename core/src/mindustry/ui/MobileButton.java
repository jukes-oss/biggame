package mindustry.ui;

import arc.scene.style.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.util.*;

/** 手机主菜单：图标按钮和文字分开，文字在按钮下面。 */
public class MobileButton extends Table{

    public MobileButton(Drawable icon, String text, Runnable listener){
        ImageButton button = new ImageButton(icon);
        button.clicked(listener);

        add(button).grow().padBottom(8f);
        row();

        Label label = new Label(text);
        label.setAlignment(Align.center);
        label.setWrap(true);
        label.clicked(listener);
        add(label).growX().center().padTop(2f);
    }
}
