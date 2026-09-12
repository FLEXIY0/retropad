package io.github.retropad.gui;

import io.github.retropad.RetroPad;
import io.github.retropad.craft.PadCraftingOverlay;
import io.github.retropad.game.PadScreenInput;
import io.github.retropad.game.PadSorter;
import io.github.retropad.input.PadInputMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiContainer;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.common.item.Item;
import net.minecraft.common.item.ItemStack;
import net.minecraft.common.item.block.ItemBlock;
import net.minecraft.common.item.children.ItemBed;
import net.minecraft.common.item.children.ItemBoat;
import net.minecraft.common.item.children.ItemBow;
import net.minecraft.common.item.children.ItemBucketBase;
import net.minecraft.common.item.children.ItemDoor;
import net.minecraft.common.item.children.ItemDye;
import net.minecraft.common.item.children.ItemFishingRod;
import net.minecraft.common.item.children.ItemFlashFlask;
import net.minecraft.common.item.children.ItemFlintAndSteel;
import net.minecraft.common.item.children.ItemFlute;
import net.minecraft.common.item.children.ItemHanging;
import net.minecraft.common.item.children.ItemLilyPad;
import net.minecraft.common.item.children.ItemMap;
import net.minecraft.common.item.children.ItemMinecart;
import net.minecraft.common.item.children.ItemPlaceable;
import net.minecraft.common.item.children.ItemRecord;
import net.minecraft.common.item.children.ItemSeeds;
import net.minecraft.common.item.children.ItemSign;
import net.minecraft.common.item.children.ItemSpawnEgg;
import net.minecraft.common.item.children.ItemThrowable;
import net.minecraft.common.item.children.ItemTool;
import net.minecraft.common.item.children.ItemToolHoe;
import net.minecraft.common.item.children.ItemToolSword;

import java.util.ArrayList;
import java.util.List;

/**
 * The row of button prompts along the bottom of the screen.
 *
 * <p>Real glyphs from the controller sheet rather than the letters A and B spelled out, so a
 * DualShock shows a cross where an Xbox pad shows an A — which is the whole point of a console
 * UI telling you what does what. What it lists changes with what is on screen and, in the world,
 * with what is in your hand: bread reads "Eat", a pickaxe reads "Mine", and an item that does
 * nothing on the trigger says nothing rather than promising a "Use" that never happens.
 */
public final class PadHintBar {

    private static final int TEXT_COLOR = 0xFFE0E0E0;
    /**
     * Nearly opaque, and drawn the full width of the screen.
     *
     * <p>A translucent patch behind the prompts let whatever was underneath — the Delete button
     * on the world list, say — show through and tangle with them. A band reads as part of the
     * interface instead of as something spilled over it, and hides what it covers cleanly.
     */
    private static final int BAND = 0xE6101014;
    private static final int BAND_EDGE = 0xFF3C3C46;
    /** Gap between a glyph and its label, and between one prompt and the next. */
    private static final int LABEL_GAP = 3;
    private static final int PROMPT_GAP = 9;
    private static final int BOTTOM_MARGIN = 4;
    /** The chat line lives in the bottom 14 pixels; the band moves up rather than over it. */
    private static final int CHAT_ROOM = 16;

    /**
     * How far the world HUD moves up to make room for the prompts underneath it. Even, because
     * the crosshair is placed at half this number and has to come back to the exact centre.
     */
    private static final int HUD_LIFT = 20;

    private PadHintBar() {
        throw new AssertionError();
    }

    /** How much room the world HUD gives up. Zero when there is no pad to explain. */
    public static int hudLift() {
        return active() ? HUD_LIFT : 0;
    }

    private static boolean active() {
        return RetroPad.CONFIG.showGlyphs && RetroPad.isPadActive()
                && PadInputMode.padDriving();
    }

    // ---------------------------------------------------------------- screens

    public static void render(GuiScreen screen) {
        if (!RetroPad.CONFIG.showGlyphs) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        FontRenderer font = mc.fontRenderer;
        if (font == null) {
            return;
        }
        List<Prompt> prompts = build(screen);
        if (prompts.isEmpty()) {
            return;
        }

        PadToast.render(screen);

        int width = width(font, prompts);
        float x = (screen.width - width) / 2.0F;
        float bottom = bottomOfBar(screen);
        float y = bottom - PadGlyphs.SIZE - BOTTOM_MARGIN;
        // As wide as what it says, not as wide as the screen: the corners belong to the
        // game's own lines — the author credit on the left, the version on the right — and a
        // band across the full width buries both of them.
        float left = x - 6.0F;
        float right = x + width + 6.0F;
        Gui.drawRect(left, y - 4.0F, right, y - 3.0F, BAND_EDGE);
        Gui.drawRect(left, y - 3.0F, right, bottom, BAND);
        draw(mc, font, prompts, x, y);
    }

    /** The room the band takes along the bottom, which screens have to keep clear. */
    public static int reservedHeight() {
        return PadGlyphs.SIZE + BOTTOM_MARGIN + 6;
    }

    /** Where the band ends: the screen's bottom, or above the chat line when it is in use. */
    private static float bottomOfBar(GuiScreen screen) {
        return screen.height - (PadKeyboard.isOpen() ? CHAT_ROOM : 0);
    }

    /** The line the band starts at, so anything else the pad draws can stay clear of it. */
    public static float topOfBar(GuiScreen screen) {
        return bottomOfBar(screen) - PadGlyphs.SIZE - BOTTOM_MARGIN - 4.0F;
    }

    // ------------------------------------------------------------------ world

    /**
     * The same row under the hotbar, in the strip the HUD moved up to free. No backdrop here —
     * the world is behind it, and the drop shadow carries the text on its own.
     */
    public static void renderInGame() {
        if (!active()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        FontRenderer font = mc.fontRenderer;
        if (font == null || mc.thePlayer == null || mc.currentScreen != null
                || mc.gameSettings.hideGUI) {
            return;
        }
        List<Prompt> prompts = inWorld(heldItem(mc));
        if (prompts.isEmpty()) {
            return;
        }
        int height = ScaledResolution.instance.getScaledHeight();
        PadRenderState.begin();
        draw(mc, font, prompts, 6.0F, height - PadGlyphs.SIZE - BOTTOM_MARGIN);
    }

    private static ItemStack heldItem(Minecraft mc) {
        try {
            return mc.thePlayer.getHeldItem();
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** What the triggers and face buttons do right now, given what is in hand. */
    private static List<Prompt> inWorld(ItemStack held) {
        List<Prompt> prompts = new ArrayList<>();
        prompts.add(new Prompt(attackLabel(held), PadGlyphs.RT));
        String use = useLabel(held);
        if (use != null) {
            prompts.add(new Prompt(use, PadGlyphs.LT));
        }
        prompts.add(new Prompt("Zoom", PadGlyphs.DPAD_DOWN));
        prompts.add(new Prompt("Inventory", PadGlyphs.Y));
        if (held != null) {
            prompts.add(new Prompt("Drop", PadGlyphs.X));
        }
        return prompts;
    }

    /** The left trigger. Null when the item does nothing with it, so nothing is promised. */
    private static String useLabel(ItemStack stack) {
        if (stack == null) {
            return null;
        }
        Item item;
        try {
            item = stack.getItem();
        } catch (Throwable ignored) {
            return null;
        }
        if (item == null) {
            return null;
        }
        try {
            Item.Action action = item.getItemUseAction(stack);
            if (action == Item.Action.EAT) {
                return "Eat";
            }
            if (action == Item.Action.DRINK) {
                return "Drink";
            }
        } catch (Throwable ignored) {
            // An item that cannot say what it does is treated as one that does nothing.
        }
        if (item instanceof ItemBow) {
            return "Shoot";
        }
        if (item instanceof ItemThrowable || item instanceof ItemFlashFlask) {
            return "Throw";
        }
        if (item instanceof ItemFishingRod) {
            return "Cast";
        }
        if (item instanceof ItemFlintAndSteel) {
            return "Light";
        }
        if (item instanceof ItemBucketBase) {
            return "Fill";
        }
        if (item instanceof ItemSeeds) {
            return "Plant";
        }
        if (item instanceof ItemToolHoe) {
            return "Till";
        }
        if (item instanceof ItemRecord || item instanceof ItemFlute) {
            return "Play";
        }
        if (item instanceof ItemSpawnEgg) {
            return "Spawn";
        }
        if (item instanceof ItemDye) {
            return "Dye";
        }
        if (item instanceof ItemMap) {
            return "Read";
        }
        if (item instanceof ItemBlock || item instanceof ItemPlaceable || item instanceof ItemDoor
                || item instanceof ItemBed || item instanceof ItemSign || item instanceof ItemBoat
                || item instanceof ItemMinecart || item instanceof ItemHanging
                || item instanceof ItemLilyPad) {
            return "Place";
        }
        // Swords and the rest of the tools have no right-hand action in ReIndev; nor do ingots,
        // sticks and everything else that is only ever a crafting ingredient.
        return null;
    }

    /** The right trigger, which always does something — even a bare fist breaks blocks. */
    private static String attackLabel(ItemStack stack) {
        if (stack != null) {
            try {
                Item item = stack.getItem();
                if (item instanceof ItemToolSword) {
                    return "Attack";
                }
                if (item instanceof ItemTool) {
                    return "Mine";
                }
            } catch (Throwable ignored) {
                // Fall through to the plain label.
            }
        }
        return "Hit";
    }

    // ----------------------------------------------------------------- shared

    /** What to advertise, given what is on screen right now. */
    private static List<Prompt> build(GuiScreen screen) {
        List<Prompt> prompts = new ArrayList<>();
        PadCraftingOverlay crafting = PadCraftingOverlay.get();

        PadScreenInput input = RetroPad.screenInput();
        if (input != null && input.isAdjustingSlider()) {
            prompts.add(new Prompt("adjust", PadGlyphs.DPAD_LEFT, PadGlyphs.DPAD_RIGHT));
            prompts.add(new Prompt("done", PadGlyphs.A));
            return prompts;
        }

        if (PadKeyboard.isOpen()) {
            if (PadKeyboard.isWheel()) {
                prompts.add(new Prompt("pick", PadGlyphs.A, PadGlyphs.X, PadGlyphs.Y));
                prompts.add(new Prompt("back", PadGlyphs.DPAD_DOWN));
                prompts.add(new Prompt("space", PadGlyphs.STICK_RIGHT));
                prompts.add(new Prompt("keys", PadGlyphs.SELECT));
                prompts.add(new Prompt("send", PadGlyphs.START));
                prompts.add(new Prompt("close", PadGlyphs.B));
                return prompts;
            }
            prompts.add(new Prompt("type", PadGlyphs.A));
            prompts.add(new Prompt("space", PadGlyphs.Y));
            prompts.add(new Prompt("back", PadGlyphs.X));
            prompts.add(new Prompt("shift", PadGlyphs.LT));
            prompts.add(new Prompt("wheel", PadGlyphs.SELECT));
            prompts.add(new Prompt("send", PadGlyphs.START));
            prompts.add(new Prompt("close", PadGlyphs.B));
            return prompts;
        }

        if (crafting.isOpen()) {
            prompts.add(new Prompt("craft", PadGlyphs.A));
            prompts.add(new Prompt("browse", PadGlyphs.DPAD_LEFT, PadGlyphs.DPAD_RIGHT));
            if (crafting.selectionHasAlternatives()) {
                prompts.add(new Prompt("recipe", PadGlyphs.DPAD_UP, PadGlyphs.DPAD_DOWN));
            }
            prompts.add(new Prompt("category", PadGlyphs.LB, PadGlyphs.RB));
            prompts.add(new Prompt("back", PadGlyphs.B));
            return prompts;
        }

        if (screen instanceof GuiContainer) {
            GuiContainer container = (GuiContainer) screen;
            prompts.add(new Prompt("take", PadGlyphs.A));
            prompts.add(new Prompt("split", PadGlyphs.X));
            prompts.add(new Prompt("crafting", PadGlyphs.LB));
            prompts.add(new Prompt(PadSorter.hasStorage(container) ? "sort chest" : "sort",
                    PadGlyphs.RB));
            if (PadSorter.hasStorage(container)) {
                prompts.add(new Prompt("sort bag", PadGlyphs.STICK_RIGHT));
                prompts.add(new Prompt("store all", PadGlyphs.LT));
                prompts.add(new Prompt("take all", PadGlyphs.RT));
            }
            prompts.add(new Prompt("close", PadGlyphs.B));
            return prompts;
        }

        prompts.add(new Prompt("select", PadGlyphs.A));
        if (PadKeyboard.canType(screen)) {
            prompts.add(new Prompt("keyboard", PadGlyphs.Y));
        }
        prompts.add(new Prompt("back", PadGlyphs.B));
        return prompts;
    }

    private static int width(FontRenderer font, List<Prompt> prompts) {
        int width = -PROMPT_GAP;
        for (Prompt prompt : prompts) {
            width += prompt.width(font) + PROMPT_GAP;
        }
        return width;
    }

    private static void draw(Minecraft mc, FontRenderer font, List<Prompt> prompts, float x, float y) {
        for (Prompt prompt : prompts) {
            x = prompt.draw(mc, font, x, y) + PROMPT_GAP;
        }
    }

    /** One or two glyphs and the word for what they do. */
    private static final class Prompt {

        private final String label;
        private final int[] buttons;

        Prompt(String label, int... buttons) {
            this.label = label;
            this.buttons = buttons;
        }

        int width(FontRenderer font) {
            return this.buttons.length * PadGlyphs.SIZE + LABEL_GAP + font.getStringWidth(this.label);
        }

        /** Draws the prompt and returns the x it ended at. */
        float draw(Minecraft mc, FontRenderer font, float x, float y) {
            for (int button : this.buttons) {
                PadGlyphs.draw(mc, button, x, y);
                x += PadGlyphs.SIZE;
            }
            x += LABEL_GAP;
            // Nudge the text down so it sits on the glyphs' centre line.
            font.drawStringWithShadow(this.label, x, y + 3.0F, TEXT_COLOR);
            return x + font.getStringWidth(this.label);
        }
    }
}
