package io.github.retropad.gui;

import io.github.retropad.RetroPad;
import io.github.retropad.input.GamepadState;
import io.github.retropad.input.PadAxis;
import io.github.retropad.input.PadButton;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.gui.GuiScreen;

import java.lang.reflect.Field;

/**
 * An on-screen keyboard, because a pad cannot reach T.
 *
 * <p>Typed characters are handed to the screen through {@code keyTyped}, the same entry point the
 * real keyboard uses, so chat, its command completion and its history all behave exactly as they
 * do when typed — this types into the game rather than around it.
 *
 * <p>The grid is a fixed ten cells wide and a wide key simply occupies several of them, which
 * keeps moving around it to one rule: left and right step one cell, up and down keep the cell
 * and change the row.
 */
public final class PadKeyboard {

    /** Twelve, because a Russian row has twelve letters in it and a layout cannot be cut short. */
    private static final int COLUMNS = 12;
    private static final int CELL = 15;
    private static final int KEY = 14;
    private static final int PADDING = 4;
    /** Gap between the keys and the prompt band under them. */
    private static final int ABOVE_PROMPTS = 4;

    private static final int PANEL = 0xE0101014;
    private static final int PANEL_EDGE = 0xFF505060;
    private static final int KEY_FACE = 0xFF2A2A34;
    private static final int KEY_FACE_WIDE = 0xFF3A3A46;
    private static final int KEY_TEXT = 0xFFE8E8E8;

    /** Key codes LWJGL would have sent, which is what the text field switches on. */
    private static final int KEY_BACKSPACE = 14;
    private static final int KEY_RETURN = 28;
    private static final int KEY_LEFT = 203;
    private static final int KEY_RIGHT = 205;
    /** Tab, which chat uses to complete a command. */
    private static final int KEY_TAB = 15;

    private static final Key SHIFT = new Key("Shift", (char) 0, 0);
    private static final Key LANGUAGE = new Key("EN/RU", (char) 0, 0);
    private static final Key SPACE = new Key("Space", ' ', 0);
    private static final Key BACK = new Key("Back", (char) 0, KEY_BACKSPACE);
    private static final Key ENTER = new Key("Enter", (char) 0, KEY_RETURN);
    private static final Key TAB = new Key("Tab", (char) 0, KEY_TAB);

    private static final Key[] CONTROL_ROW = {
            SHIFT, SHIFT, TAB, LANGUAGE, SPACE, SPACE, SPACE, SPACE,
            BACK, BACK, ENTER, ENTER,
    };

    /**
     * The quick way to type: eight groups of five, one group per direction of the left stick,
     * one button per letter inside it.
     *
     * <p>A grid keyboard costs a journey across it for every letter — up to twenty presses for
     * a short word. Here the stick and a button land together, so a letter is one motion and the
     * hand never travels. It is the arrangement console games have used for years, and it is
     * worth having both: the wheel for writing, the grid for hunting down a character it does
     * not carry.
     *
     * <p>Directions run clockwise from up: N, NE, E, SE, S, SW, W, NW.
     */
    private static final String[] WHEEL_LATIN = {
            "abcde", "fghij", "klmno", "pqrst", "uvwxy", "z.,!?", "01234", "-_'\"@",
    };
    private static final String[] WHEEL_LATIN_SHIFTED = {
            "ABCDE", "FGHIJ", "KLMNO", "PQRST", "UVWXY", "Z:;()", "56789", "+=/*&",
    };
    private static final String[] WHEEL_CYRILLIC = {
            "абвгд", "еёжзи", "йклмн", "опрст", "уфхцч", "шщъыь", "эюя.,", "01234",
    };
    private static final String[] WHEEL_CYRILLIC_SHIFTED = {
            "АБВГД", "ЕЁЖЗИ", "ЙКЛМН", "ОПРСТ", "УФХЦЧ", "ШЩЪЫЬ", "ЭЮЯ!?", "56789",
    };

    /** Which button picks which of the five, in the order they are shown. */
    private static final PadButton[] WHEEL_PICKS = {
            PadButton.SOUTH, PadButton.WEST, PadButton.NORTH, PadButton.L1, PadButton.R1,
    };
    private static final int[] WHEEL_PICK_GLYPHS = {
            PadGlyphs.A, PadGlyphs.X, PadGlyphs.Y, PadGlyphs.LB, PadGlyphs.RB,
    };

    /** How far the stick must be pushed to choose a direction at all. */
    private static final float WHEEL_DEADZONE = 0.5F;
    private static final int WHEEL_CELL = 46;
    private static final int WHEEL_CELL_HEIGHT = 16;

    private static final Key[][] LATIN = {
            letters("1234567890-=", "!@#$%^&*()_+"),
            letters("qwertyuiop[]", "QWERTYUIOP{}"),
            letters("asdfghjkl;'/", "ASDFGHJKL:\"?"),
            letters("zxcvbnm,.?!@", "ZXCVBNM<>?!@"),
            CONTROL_ROW,
    };

    /**
     * The Russian layout, in the order a keyboard has it. ReIndev's font carries Cyrillic and
     * its chat accepts any character above a space, so this types and displays like any other
     * text — checked in the font table rather than assumed.
     */
    private static final Key[][] CYRILLIC = {
            letters("1234567890-=", "!\"№;%:?*()_+"),
            letters("йцукенгшщзхъ",
                    "ЙЦУКЕНГШЩЗХЪ"),
            letters("фывапролджэё",
                    "ФЫВАПРОЛДЖЭЁ"),
            letters("ячсмитьбю.,?",
                    "ЯЧСМИТЬБЮ.,?"),
            CONTROL_ROW,
    };

    private static boolean cyrillic;
    private static boolean wheel;
    private static int sector = -1;

    private static Key[][] rows() {
        return cyrillic ? CYRILLIC : LATIN;
    }

    /** The last screen asked about and what it answered: the prompt row asks every frame. */
    private static GuiScreen askedAbout;
    private static Object askedField;

    private static boolean open;
    private static boolean shifted;
    private static int row;
    private static int column;
    private static long movedAt;

    private PadKeyboard() {
        throw new AssertionError();
    }

    public static boolean isOpen() {
        return open;
    }

    /** Chat is nothing but a text field, so the keyboard belongs open the moment it appears. */
    public static void onScreenOpened(GuiScreen screen) {
        close();
        if (screen instanceof GuiChat && RetroPad.isPadActive()) {
            open();
        }
    }

    public static void open() {
        open = true;
        shifted = false;
        wheel = RetroPad.CONFIG.keyboardWheel;
        sector = -1;
        row = 1;
        column = 0;
        movedAt = System.currentTimeMillis();
    }

    public static void close() {
        open = false;
        shifted = false;
    }

    /** True when a screen has somewhere to type, so offering the keyboard makes sense. */
    public static boolean canType(GuiScreen screen) {
        return screen instanceof GuiChat || textField(screen) != null;
    }

    /**
     * Opens the keyboard on a screen that has a text field, focusing that field first: a field
     * nobody has clicked on swallows everything typed into it otherwise.
     */
    public static void openOn(GuiScreen screen) {
        Object field = textField(screen);
        if (field != null) {
            setFocused(field);
        }
        open();
    }

    // ------------------------------------------------------------------ input

    /** Runs the keyboard. Returns true when it took the input and nothing else should see it. */
    public static boolean handle(GuiScreen screen, GamepadState state, long now) {
        if (!open) {
            return false;
        }
        if (state.isPressed(PadButton.EAST)) {
            close();
            return true;
        }
        if (state.isPressed(PadButton.SELECT)) {
            wheel = !wheel;
            return true;
        }
        if (wheel) {
            return handleWheel(screen, state, now);
        }
        if (state.isPressedOrRepeated(PadButton.NAV_LEFT, now)) {
            column = (column + COLUMNS - 1) % COLUMNS;
            movedAt = now;
        }
        if (state.isPressedOrRepeated(PadButton.NAV_RIGHT, now)) {
            column = (column + 1) % COLUMNS;
            movedAt = now;
        }
        if (state.isPressedOrRepeated(PadButton.NAV_UP, now)) {
            row = (row + rows().length - 1) % rows().length;
            movedAt = now;
        }
        if (state.isPressedOrRepeated(PadButton.NAV_DOWN, now)) {
            row = (row + 1) % rows().length;
            movedAt = now;
        }

        if (state.isPressedOrRepeated(PadButton.SOUTH, now)) {
            press(screen, rows()[row][column]);
        }
        if (state.isPressedOrRepeated(PadButton.WEST, now)) {
            press(screen, BACK);
        }
        if (state.isPressed(PadButton.NORTH)) {
            press(screen, SPACE);
        }
        if (state.isPressed(PadButton.L2)) {
            shifted = !shifted;
        }
        if (state.isPressed(PadButton.R2)) {
            cyrillic = !cyrillic;
        }
        if (state.isPressed(PadButton.START)) {
            press(screen, ENTER);
        }
        // The shoulders walk the caret through what has been typed so far, which is the only
        // way to fix a letter in the middle of a line without deleting back to it.
        if (state.isPressedOrRepeated(PadButton.L1, now)) {
            type(screen, (char) 0, KEY_LEFT);
        }
        if (state.isPressedOrRepeated(PadButton.R1, now)) {
            type(screen, (char) 0, KEY_RIGHT);
        }
        return true;
    }

    /**
     * The wheel: the stick says which group, a button says which letter in it.
     *
     * <p>B is left out of the five picks on purpose — it is the way out of the keyboard, and a
     * letter that also closes the thing you are typing into would be unusable.
     */
    private static boolean handleWheel(GuiScreen screen, GamepadState state, long now) {
        float x = state.getAxis(PadAxis.LEFT_X);
        float y = state.getAxis(PadAxis.LEFT_Y);
        if (x * x + y * y >= WHEEL_DEADZONE * WHEEL_DEADZONE) {
            int chosen = sectorOf(x, y);
            if (chosen != sector) {
                sector = chosen;
                movedAt = now;
            }
        }

        if (sector >= 0) {
            String group = wheelGroup(sector);
            for (int i = 0; i < WHEEL_PICKS.length && i < group.length(); i++) {
                if (state.isPressedOrRepeated(WHEEL_PICKS[i], now)) {
                    type(screen, group.charAt(i), 0);
                }
            }
        }

        // Everything else the grid does, on the buttons the picks left free.
        if (state.isPressedOrRepeated(PadButton.DPAD_DOWN, now)) {
            type(screen, (char) 0, KEY_BACKSPACE);
        }
        if (state.isPressed(PadButton.DPAD_UP)) {
            shifted = !shifted;
        }
        if (state.isPressedOrRepeated(PadButton.DPAD_LEFT, now)) {
            type(screen, (char) 0, KEY_LEFT);
        }
        if (state.isPressedOrRepeated(PadButton.DPAD_RIGHT, now)) {
            type(screen, (char) 0, KEY_RIGHT);
        }
        if (state.isPressed(PadButton.L2)) {
            shifted = !shifted;
        }
        if (state.isPressed(PadButton.R2)) {
            cyrillic = !cyrillic;
        }
        if (state.isPressed(PadButton.R3)) {
            type(screen, ' ', 0);
        }
        if (state.isPressed(PadButton.START)) {
            type(screen, (char) 0, KEY_RETURN);
            close();
        }
        return true;
    }

    /** Which of the eight directions the stick is pointing, clockwise from up. */
    private static int sectorOf(float x, float y) {
        // The stick counts downwards, the wheel is drawn the way it is seen.
        double angle = Math.atan2(x, -y);
        int index = (int) Math.round(angle / (Math.PI / 4.0));
        return ((index % 8) + 8) % 8;
    }

    private static String wheelGroup(int index) {
        String[] table;
        if (cyrillic) {
            table = shifted ? WHEEL_CYRILLIC_SHIFTED : WHEEL_CYRILLIC;
        } else {
            table = shifted ? WHEEL_LATIN_SHIFTED : WHEEL_LATIN;
        }
        return table[index];
    }

    /** True while the quick wheel is the mode in use, so the prompts can describe it. */
    public static boolean isWheel() {
        return wheel;
    }

    private static void press(GuiScreen screen, Key key) {
        if (key == SHIFT) {
            shifted = !shifted;
            return;
        }
        if (key == LANGUAGE) {
            cyrillic = !cyrillic;
            return;
        }
        if (key.code != 0) {
            type(screen, (char) 0, key.code);
            // Enter on chat closes the screen from under us.
            if (key == ENTER) {
                close();
            }
            return;
        }
        char typed = shifted ? key.upper : key.lower;
        type(screen, typed, 0);
    }

    private static void type(GuiScreen screen, char character, int code) {
        try {
            screen.keyTyped(character, code);
        } catch (Throwable throwable) {
            RetroPad.LOGGER.warn("On-screen keyboard could not type into " + screen, throwable);
            close();
        }
    }

    // ----------------------------------------------------------------- render

    public static void render(GuiScreen screen) {
        if (!open) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        FontRenderer font = mc.fontRenderer;
        if (font == null) {
            return;
        }
        if (wheel) {
            renderWheel(screen, mc, font);
            return;
        }
        int width = COLUMNS * CELL - (CELL - KEY) + PADDING * 2;
        int height = rows().length * CELL - (CELL - KEY) + PADDING * 2;
        float x0 = (screen.width - width) / 2.0F;
        // Sits on top of the prompt band rather than at a fixed distance from the bottom, so it
        // can never land on the band, the chat line or the buttons of the screen underneath.
        float y0 = PadHintBar.topOfBar(screen) - height - ABOVE_PROMPTS;

        Gui.drawRect(x0 - 1.0F, y0 - 1.0F, x0 + width + 1.0F, y0 + height + 1.0F, PANEL_EDGE);
        Gui.drawRect(x0, y0, x0 + width, y0 + height, PANEL);

        for (int r = 0; r < rows().length; r++) {
            for (int c = 0; c < COLUMNS; c++) {
                Key key = rows()[r][c];
                if (c > 0 && rows()[r][c - 1] == key) {
                    continue;
                }
                int span = 1;
                while (c + span < COLUMNS && rows()[r][c + span] == key) {
                    span++;
                }
                float keyX = x0 + PADDING + c * CELL;
                float keyY = y0 + PADDING + r * CELL;
                float keyW = span * CELL - (CELL - KEY);
                Gui.drawRect(keyX, keyY, keyX + keyW, keyY + KEY,
                        span > 1 ? KEY_FACE_WIDE : KEY_FACE);

                String label = label(key);
                font.drawString(label, keyX + (keyW - font.getStringWidth(label)) / 2.0F,
                        keyY + 3.0F, KEY_TEXT);
            }
        }

        // The same growing, blinking square the rest of the mod highlights things with.
        Key selected = rows()[row][column];
        int start = column;
        while (start > 0 && rows()[row][start - 1] == selected) {
            start--;
        }
        int span = 1;
        while (start + span < COLUMNS && rows()[row][start + span] == selected) {
            span++;
        }
        float selX = x0 + PADDING + start * CELL;
        float selY = y0 + PADDING + row * CELL;
        PadOutline.draw(selX, selY, selX + span * CELL - (CELL - KEY), selY + KEY,
                System.currentTimeMillis() - movedAt);
    }

    /**
     * The wheel, drawn as the eight directions are felt: a three by three block with the middle
     * left out, so the group up and to the left is up and to the left on screen. The chosen
     * group's letters are listed underneath with the button that types each one.
     */
    private static void renderWheel(GuiScreen screen, Minecraft mc, FontRenderer font) {
        int width = WHEEL_CELL * 3 + PADDING * 2;
        int height = WHEEL_CELL_HEIGHT * 3 + PadGlyphs.SIZE + PADDING * 3;
        float x0 = (screen.width - width) / 2.0F;
        float y0 = PadHintBar.topOfBar(screen) - height - ABOVE_PROMPTS;

        Gui.drawRect(x0 - 1.0F, y0 - 1.0F, x0 + width + 1.0F, y0 + height + 1.0F, PANEL_EDGE);
        Gui.drawRect(x0, y0, x0 + width, y0 + height, PANEL);

        // Column and row of each direction inside the block, clockwise from up.
        int[] columns = {1, 2, 2, 2, 1, 0, 0, 0};
        int[] lines = {0, 0, 1, 2, 2, 2, 1, 0};
        for (int i = 0; i < 8; i++) {
            float cellX = x0 + PADDING + columns[i] * WHEEL_CELL;
            float cellY = y0 + PADDING + lines[i] * WHEEL_CELL_HEIGHT;
            boolean chosen = i == sector;
            Gui.drawRect(cellX, cellY, cellX + WHEEL_CELL - 2, cellY + WHEEL_CELL_HEIGHT - 2,
                    chosen ? KEY_FACE_WIDE : KEY_FACE);
            String group = spaced(wheelGroup(i));
            font.drawString(group, cellX + (WHEEL_CELL - 2 - font.getStringWidth(group)) / 2.0F,
                    cellY + 4.0F, KEY_TEXT);
            if (chosen) {
                PadOutline.draw(cellX, cellY, cellX + WHEEL_CELL - 2,
                        cellY + WHEEL_CELL_HEIGHT - 2, System.currentTimeMillis() - movedAt);
            }
        }

        // The chosen group spelled out against the buttons that type it.
        String group = sector < 0 ? null : wheelGroup(sector);
        float rowY = y0 + PADDING * 2 + WHEEL_CELL_HEIGHT * 3;
        if (group == null) {
            String hint = "Push the stick";
            font.drawString(hint, x0 + (width - font.getStringWidth(hint)) / 2.0F,
                    rowY + 3.0F, KEY_TEXT);
            return;
        }
        float stride = (width - PADDING * 2) / (float) WHEEL_PICKS.length;
        for (int i = 0; i < WHEEL_PICKS.length && i < group.length(); i++) {
            float itemX = x0 + PADDING + stride * i;
            PadGlyphs.draw(mc, WHEEL_PICK_GLYPHS[i], itemX, rowY);
            font.drawString(String.valueOf(group.charAt(i)),
                    itemX + PadGlyphs.SIZE + 2.0F, rowY + 3.0F, KEY_TEXT);
        }
    }

    /** Letters with a gap between them, which is what makes a group of five readable at a glance. */
    private static String spaced(String group) {
        StringBuilder spelled = new StringBuilder(group.length() * 2);
        for (int i = 0; i < group.length(); i++) {
            if (i > 0) {
                spelled.append(' ');
            }
            spelled.append(group.charAt(i));
        }
        return spelled.toString();
    }

    private static String label(Key key) {
        if (key == LANGUAGE) {
            return cyrillic ? "RU" : "EN";
        }
        if (key.name != null) {
            return key == SHIFT && shifted ? "SHIFT" : key.name;
        }
        return String.valueOf(shifted ? key.upper : key.lower);
    }

    // ------------------------------------------------------------- text field

    /** The screen's first text box, found by looking rather than by knowing every screen. */
    private static Object textField(GuiScreen screen) {
        if (screen == null) {
            return null;
        }
        if (screen == askedAbout) {
            return askedField;
        }
        askedAbout = screen;
        askedField = findTextField(screen);
        return askedField;
    }

    private static Object findTextField(GuiScreen screen) {
        for (Class<?> type = screen.getClass(); type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (!field.getType().getSimpleName().equals("GuiTextField")) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    Object value = field.get(screen);
                    if (value != null) {
                        return value;
                    }
                } catch (Throwable ignored) {
                    // A field that will not open is a field this keyboard cannot use.
                }
            }
        }
        return null;
    }

    private static void setFocused(Object textField) {
        try {
            Field focused = textField.getClass().getDeclaredField("isFocused");
            focused.setAccessible(true);
            focused.setBoolean(textField, true);
        } catch (Throwable ignored) {
            // Without focus the field ignores what is typed; nothing else breaks.
        }
    }

    // ------------------------------------------------------------------- keys

    /**
     * A row of character keys. A layout with fewer characters than the grid is wide keeps its
     * last key for the rest of the row rather than leaving a hole the cursor could fall into.
     */
    private static Key[] letters(String lower, String upper) {
        Key[] keys = new Key[COLUMNS];
        for (int i = 0; i < COLUMNS; i++) {
            int index = Math.min(i, Math.min(lower.length(), upper.length()) - 1);
            keys[i] = new Key(lower.charAt(index), upper.charAt(index));
        }
        return keys;
    }

    /** One key: either a character pair, or a named key with a key code behind it. */
    private static final class Key {

        private final String name;
        private final char lower;
        private final char upper;
        private final int code;

        Key(char lower, char upper) {
            this.name = null;
            this.lower = lower;
            this.upper = upper;
            this.code = 0;
        }

        Key(String name, char character, int code) {
            this.name = name;
            this.lower = character;
            this.upper = character;
            this.code = code;
        }
    }
}
