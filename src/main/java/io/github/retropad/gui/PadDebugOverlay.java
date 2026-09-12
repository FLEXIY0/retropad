package io.github.retropad.gui;

import io.github.retropad.RetroPad;
import io.github.retropad.input.GamepadManager;
import io.github.retropad.input.GamepadState;
import io.github.retropad.input.PadAxis;
import io.github.retropad.input.PadButton;
import io.github.retropad.input.PadLayout;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.util.GameSettings;
import net.minecraft.client.util.KeyBinding;

import java.util.ArrayList;
import java.util.List;

/**
 * Live pad state in the corner of the screen, for pinning down "it does nothing when I press X".
 *
 * <p>It reports three layers separately — what the driver reports, what this mod decided, and
 * what the game's key bindings ended up holding — because a fault in any one of them looks the
 * same from the player's chair.
 */
public final class PadDebugOverlay {

    private static final int TEXT_COLOR = 0xFFE0E0E0;
    private static final int BACKDROP = 0xAA000000;

    private PadDebugOverlay() {
        throw new AssertionError();
    }

    public static void render() {
        if (!RetroPad.CONFIG.debugOverlay) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.fontRenderer == null) {
            return;
        }
        List<String> lines = buildLines(mc);
        FontRenderer font = mc.fontRenderer;

        int widest = 0;
        for (String line : lines) {
            widest = Math.max(widest, font.getStringWidth(line));
        }
        float x = 4.0F;
        float y = 4.0F;
        Gui.drawRect(x - 2.0F, y - 2.0F, x + widest + 2.0F,
                y + lines.size() * (font.FONT_HEIGHT + 1) + 1.0F, BACKDROP);
        for (String line : lines) {
            font.drawStringWithShadow(line, x, y, TEXT_COLOR);
            y += font.FONT_HEIGHT + 1;
        }
    }

    private static List<String> buildLines(Minecraft mc) {
        List<String> lines = new ArrayList<>();
        GamepadManager gamepad = RetroPad.gamepad();
        if (gamepad == null || !gamepad.isConnected()) {
            lines.add("RetroPad: no gamepad connected");
            return lines;
        }

        GamepadState state = gamepad.getState();
        PadLayout layout = state.getLayout();
        lines.add("pad: " + gamepad.getConnectedName()
                + " [" + (layout == null ? "?" : layout.getFamily()) + "]");
        lines.add(String.format("stick L %+.2f %+.2f   R %+.2f %+.2f",
                state.getAxis(PadAxis.LEFT_X), state.getAxis(PadAxis.LEFT_Y),
                state.getAxis(PadAxis.RIGHT_X), state.getAxis(PadAxis.RIGHT_Y)));
        lines.add(String.format("trigger L %.2f (raw %.2f)   R %.2f (raw %.2f)",
                state.getAxis(PadAxis.TRIGGER_L), state.getRawAxis(PadAxis.TRIGGER_L),
                state.getAxis(PadAxis.TRIGGER_R), state.getRawAxis(PadAxis.TRIGGER_R)));
        lines.add("down: " + heldButtons(state));

        if (RetroPad.gameplayInput() != null) {
            lines.add("mod holds: " + RetroPad.gameplayInput().describeHeldBindings());
        }
        GameSettings settings = mc.gameSettings;
        if (settings != null) {
            lines.add("use " + describe(settings.keyBindUseItem)
                    + "   attack " + describe(settings.keyBindAttack));
            lines.add("inventory " + describe(settings.keyBindInventory)
                    + "   using item: " + (mc.thePlayer != null && mc.thePlayer.isUsingItem()));
        }
        return lines;
    }

    private static String heldButtons(GamepadState state) {
        StringBuilder builder = new StringBuilder();
        for (PadButton button : PadButton.VALUES) {
            if (button.isNavigation() || !state.isHeld(button)) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(button.name());
        }
        return builder.length() == 0 ? "-" : builder.toString();
    }

    /** Both halves of a binding's state: the flag a hold needs, and the counter a tap needs. */
    private static String describe(KeyBinding binding) {
        if (binding == null) {
            return "?";
        }
        return "(key " + binding.keyCode + " pressed=" + binding.pressed
                + " time=" + binding.pressTime + ")";
    }
}
