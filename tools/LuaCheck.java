import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.jse.JsePlatform;
import java.nio.file.*;

/** Syntax-check the round-12 Lua files through the same luaj the game uses. */
public class LuaCheck {
    public static void main(String[] args) throws Exception {
        String dir = "D:/DifferentRockets/game/core/assets/mods/";
        String[] files = {"control.lua", "engine-0.lua", "engine-1.lua", "engine-2.lua",
                "engine-3.lua", "engine-4.lua", "ion-0.lua", "physics.lua"};
        boolean ok = true;
        for (String f : files) {
            String src = new String(Files.readAllBytes(Paths.get(dir + f)), "UTF-8");
            Globals g = JsePlatform.standardGlobals();
            LuaValue chunk = g.load(src, f);
            try {
                chunk.call();
                System.out.println("OK   " + f);
            } catch (Throwable t) {
                ok = false;
                System.out.println("FAIL " + f + " : " + t);
            }
        }
        // control.lua must define a global controlLaw function
        Globals g = JsePlatform.standardGlobals();
        g.load(new String(Files.readAllBytes(Paths.get(dir + "control.lua")), "UTF-8"), "control.lua").call();
        System.out.println("controlLaw is function: " + g.get("controlLaw").isfunction());
        if (!ok) System.exit(1);
    }
}
