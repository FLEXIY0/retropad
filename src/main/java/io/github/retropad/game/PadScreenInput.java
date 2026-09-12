package io.github.retropad.game;

import io.github.retropad.PadConfig;
import io.github.retropad.RetroPad;
import io.github.retropad.input.GamepadState;
import io.github.retropad.input.MouseInjector;
import io.github.retropad.input.PadAxis;
import io.github.retropad.input.PadInputMode;
import io.github.retropad.input.PadRumble;
import io.github.retropad.input.PadButton;
import io.github.retropad.craft.PadCraftingOverlay;
import io.github.retropad.gui.PadCursor;
import io.github.retropad.gui.PadOutline;
import io.github.retropad.mixins.GuiButtonAccessor;
import io.github.retropad.gui.PadKeyboard;
import io.github.retropad.gui.PadToast;
import io.github.retropad.mixins.GuiContainerAccessor;
import io.github.retropad.mixins.GuiScreenAccessor;
import io.github.retropad.mixins.GuiSlotAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiContainer;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiSlider;
import net.minecraft.client.gui.GuiSlot;
import net.minecraft.common.block.container.Slot;
import org.lwjgl.input.Mouse;

import java.util.ArrayList;
import java.util.List;

/**
 * Runs screens from the pad by driving the game's own mouse.
 *
 * <p>There is only ever one pointer. The stick moves the real hardware cursor and the pad's
 * buttons are written into LWJGL's mouse state, so hover highlights, tooltips, drag splitting
 * and list widgets all behave exactly as they do with a mouse, in every screen — vanilla,
 * modded, or added later. Screens whose widgets read click *events* rather than button state
 * also get a direct call, which covers both styles. What changes is only how the pointer
 * looks: the system cursor is hidden and {@link PadCursor} paints a crosshair in its place.
 */
public final class PadScreenInput {

    /** Cursor travel per second at full stick deflection, in display pixels. */
    private static final float CURSOR_PIXELS_PER_SECOND = 900.0F;
    /** Half the width of an inventory slot, used to find its centre. */
    private static final int SLOT_HALF = 8;
    /** Scroll steps per second while the right stick is held. */
    private static final long SCROLL_INTERVAL_MS = 60L;
    /**
     * How far off the axis of travel a target may sit and still count as "the next one along",
     * in GUI pixels. Half an inventory slot, so that stepping down a column of slots lands on
     * the slot directly below rather than on a diagonal neighbour.
     */
    private static final float SNAP_CONE = 9.0F;
    /** Ignore targets this close: they are the one the cursor is already on. */
    private static final float SNAP_MIN_STEP = 2.0F;

    /** Cursor position in display pixels, origin bottom-left, as LWJGL reports it. */
    private float cursorX;
    private float cursorY;
    private GuiScreen lastScreen;
    private boolean leftDown;
    private boolean rightDown;
    private long nextScrollAt;

    /** What the pointer is over, and since when — the outline animation is timed from this. */
    private float[] hovered;
    private long hoveredSince;

    /** How long the crosshair stays after the stick stops. */
    private static final long POINTING_LINGER_MS = 1500L;
    /** How recently the pad must have been used for a screen to move the pointer at all. */
    private static final long REFOCUS_WINDOW_MS = 1500L;

    /** How many presses it takes to run a slider end to end. */
    private static final int SLIDER_STEPS = 20;

    /** When the stick last moved the pointer, which is what makes the crosshair appear. */
    private long pointingSince;

    /** True while the stick is being used to point, rather than the d-pad to jump. */
    public boolean isPointing() {
        return System.currentTimeMillis() - this.pointingSince < POINTING_LINGER_MS;
    }

    /** The slider currently being adjusted, if any. */
    private GuiButton slider;

    /** True while a pad-driven screen is on top. */
    public boolean isDriving() {
        return this.lastScreen != null;
    }

    public void update(Minecraft mc, GamepadState state, long now, float deltaSeconds) {
        GuiScreen screen = mc.currentScreen;
        if (screen == null) {
            this.onScreenClosed();
            return;
        }
        if (screen != this.lastScreen) {
            this.onScreenOpened(mc, screen);
        }

        this.syncPointer(screen);

        PadConfig config = RetroPad.CONFIG;
        // The keyboard covers the screen it is typing into, so it gets the buttons first.
        if (PadKeyboard.handle(screen, state, now)) {
            return;
        }
        if (this.driveCraftingMenu(mc, screen, state, now)) {
            return;
        }
        // A slider being adjusted owns the d-pad, or the same press would jump the pointer
        // off the slider it is dragging.
        if (this.driveSlider(mc, screen, state, now)) {
            return;
        }
        this.moveCursor(mc, state, config, deltaSeconds);

        GuiSlot list = ((GuiScreenAccessor) screen).retropad$getActiveGuiSlot();
        this.navigate(screen, list, state, now);
        this.scroll(list, state, now);
        this.updateHovered(mc, screen, now);
        this.click(mc, screen, state);

        this.sort(screen, state);
        this.transfer(screen, state);

        // Y opens the keyboard on anything with a text box in it, since there is no other way
        // to name a world or type an address from a pad.
        if (state.isPressed(PadButton.NORTH) && !(screen instanceof GuiContainer)
                && PadKeyboard.canType(screen)) {
            PadKeyboard.openOn(screen);
            return;
        }

        // Y closes the inventory it opened, the way it does on a console; B and Start close
        // anything.
        boolean closeWithFaceButton = state.isPressed(PadButton.NORTH) && screen instanceof GuiContainer;
        if (state.isPressed(PadButton.EAST) || state.isPressed(PadButton.START) || closeWithFaceButton) {
            this.close(mc, screen);
        }
    }

    /** Empties the open chest on one trigger and fills it from the backpack on the other. */
    private void transfer(GuiScreen screen, GamepadState state) {
        if (!(screen instanceof GuiContainer)) {
            return;
        }
        GuiContainer container = (GuiContainer) screen;
        if (state.isPressed(PadButton.L2)) {
            PadToast.show(PadTransfer.depositAll(container));
        }
        if (state.isPressed(PadButton.R2)) {
            PadToast.show(PadTransfer.takeAll(container));
        }
    }

    /**
     * Tidies an inventory on the right shoulder, and the player's own on the right stick when a
     * chest has taken the shoulder. Nothing else on a container screen uses either.
     */
    private void sort(GuiScreen screen, GamepadState state) {
        if (!(screen instanceof GuiContainer)) {
            return;
        }
        GuiContainer container = (GuiContainer) screen;
        if (state.isPressed(PadButton.R1)) {
            PadToast.show(PadSorter.sortStorage(container));
        } else if (state.isPressed(PadButton.R3) && PadSorter.hasStorage(container)) {
            PadToast.show(PadSorter.sortBackpack(container));
        }
    }

    /**
     * Runs the crafting menu when it is open, and opens it on the shoulder button when it is
     * not. Returns true when the menu took the input, so nothing else acts on the same press.
     *
     * <p>The menu is driven entirely by a moving highlight rather than by the pointer: that is
     * what makes it work from a pad without aiming, and it is why input goes here first.
     */
    private boolean driveCraftingMenu(Minecraft mc, GuiScreen screen, GamepadState state, long now) {
        PadCraftingOverlay overlay = PadCraftingOverlay.get();
        if (!(screen instanceof GuiContainer)) {
            return false;
        }
        GuiContainer container = (GuiContainer) screen;

        if (!overlay.isOpen()) {
            if (state.isPressed(PadButton.L1) && overlay.open(container)) {
                this.releaseButtons();
                this.hovered = null;
                return true;
            }
            return false;
        }
        // While the menu is up it draws its own highlight, so the pointer's must not linger.
        this.hovered = null;
        if (!overlay.isOpenOn(container)) {
            overlay.close();
            return false;
        }

        // NAV covers both the d-pad and the stick, so asking for each separately would step
        // the highlight twice on one press.
        if (state.isPressedOrRepeated(PadButton.NAV_UP, now)) {
            overlay.moveSelection(0, -1);
        }
        if (state.isPressedOrRepeated(PadButton.NAV_DOWN, now)) {
            overlay.moveSelection(0, 1);
        }
        if (state.isPressedOrRepeated(PadButton.NAV_LEFT, now)) {
            overlay.moveSelection(-1, 0);
        }
        if (state.isPressedOrRepeated(PadButton.NAV_RIGHT, now)) {
            overlay.moveSelection(1, 0);
        }
        if (state.isPressed(PadButton.L1)) {
            overlay.nextCategory(-1);
        }
        if (state.isPressed(PadButton.R1)) {
            overlay.nextCategory(1);
        }
        if (state.isPressed(PadButton.SOUTH)) {
            overlay.craftSelected();
        }
        if (state.isPressed(PadButton.EAST) || state.isPressed(PadButton.NORTH)) {
            overlay.close();
        }
        if (state.isPressed(PadButton.START)) {
            overlay.close();
            this.close(mc, screen);
        }
        return true;
    }

    /**
     * Sliders, which a pad cannot drag.
     *
     * <p>Pressing A on one grabs it — the mouse button is held down over its handle, exactly as
     * a hand would — and from then on left and right move the pointer a fixed step along the
     * bar, so the value goes up and down in even amounts instead of wherever a stick drifts.
     * Pressing A again lets go. Driving the real pointer rather than writing the value means
     * this works for the game's own sliders and for the ones FoxLoader builds for mod settings,
     * which are a different class entirely.
     *
     * @return true while a slider is being adjusted and nothing else should read the buttons
     */
    private boolean driveSlider(Minecraft mc, GuiScreen screen, GamepadState state, long now) {
        if (this.slider == null) {
            if (!state.isPressed(PadButton.SOUTH)) {
                return false;
            }
            GuiButton found = sliderUnder(screen, this.guiX(mc, screen), this.guiY(mc, screen));
            if (found == null) {
                return false;
            }
            this.slider = found;
            MouseInjector.setButton(0, true);
            ((GuiScreenAccessor) screen).retropad$mouseClicked(
                    this.guiX(mc, screen), this.guiY(mc, screen), 0);
            this.leftDown = true;
            PadToast.show("Left and right to adjust");
            return true;
        }

        if (state.isPressed(PadButton.SOUTH) || state.isPressed(PadButton.EAST)
                || !this.isStillThere(screen)) {
            this.releaseSlider(mc, screen);
            return true;
        }

        int step = 0;
        if (state.isPressedOrRepeated(PadButton.NAV_LEFT, now)) {
            step -= 1;
        }
        if (state.isPressedOrRepeated(PadButton.NAV_RIGHT, now)) {
            step += 1;
        }
        if (step != 0) {
            GuiButtonAccessor size = (GuiButtonAccessor) this.slider;
            float width = size.retropad$getWidth();
            float guiX = clamp(this.guiX(mc, screen) + step * width / SLIDER_STEPS,
                    this.slider.xPosition, this.slider.xPosition + width);
            float guiY = this.slider.yPosition + size.retropad$getHeight() / 2.0F;
            this.moveToGui(mc, screen, guiX, guiY);
            PadRumble.play(PadRumble.Effect.UI, 0.4F);
        }
        return true;
    }

    /** True while the slider being dragged is still on the screen that owns it. */
    private boolean isStillThere(GuiScreen screen) {
        List<?> controls = ((GuiScreenAccessor) screen).retropad$getControlList();
        return controls != null && controls.contains(this.slider);
    }

    private void releaseSlider(Minecraft mc, GuiScreen screen) {
        this.slider = null;
        if (this.leftDown) {
            MouseInjector.setButton(0, false);
            ((GuiScreenAccessor) screen).retropad$mouseMovedOrUp(
                    this.guiX(mc, screen), this.guiY(mc, screen), 0);
            this.leftDown = false;
        }
    }

    /**
     * The slider under the pointer, if there is one. Matched on the name as well as the type
     * because a mod's settings screen builds its own slider class that shares nothing with the
     * game's but behaves the same way.
     */
    private static GuiButton sliderUnder(GuiScreen screen, float x, float y) {
        List<?> controls = ((GuiScreenAccessor) screen).retropad$getControlList();
        if (controls == null) {
            return null;
        }
        for (Object control : controls) {
            if (!(control instanceof GuiButton)) {
                continue;
            }
            GuiButton button = (GuiButton) control;
            if (!button.enabled || !isSlider(button)) {
                continue;
            }
            GuiButtonAccessor size = (GuiButtonAccessor) button;
            if (x >= button.xPosition && x <= button.xPosition + size.retropad$getWidth()
                    && y >= button.yPosition && y <= button.yPosition + size.retropad$getHeight()) {
                return button;
            }
        }
        return null;
    }

    private static boolean isSlider(GuiButton button) {
        return button instanceof GuiSlider
                || button.getClass().getSimpleName().contains("Slider");
    }

    /** True when a slider is being adjusted, so the prompts can say what the d-pad does. */
    public boolean isAdjustingSlider() {
        return this.slider != null;
    }

    private void moveCursor(Minecraft mc, GamepadState state, PadConfig config, float deltaSeconds) {
        float speed = config.cursorSpeed * CURSOR_PIXELS_PER_SECOND * deltaSeconds;
        float dx = signedSquare(state.getAxis(PadAxis.LEFT_X)) * speed;
        // Display coordinates count upwards from the bottom, the stick counts downwards.
        float dy = -signedSquare(state.getAxis(PadAxis.LEFT_Y)) * speed;
        if (dx == 0.0F && dy == 0.0F) {
            return;
        }
        this.pointingSince = System.currentTimeMillis();
        this.cursorX = clamp(this.cursorX + dx, 0.0F, mc.displayWidth - 1.0F);
        this.cursorY = clamp(this.cursorY + dy, 0.0F, mc.displayHeight - 1.0F);
        MouseInjector.setCursorPosition((int) this.cursorX, (int) this.cursorY);
        PadInputMode.noteCursorWrite((int) this.cursorX, (int) this.cursorY);
    }

    /**
     * The d-pad jumps the pointer between the things worth pointing at: buttons, item slots and
     * the rows of a list. It moves the pointer rather than asking a widget to change selection,
     * because {@code GuiSlot}'s selection methods are empty in ReIndev and a row is only ever
     * chosen by a click landing on it.
     */
    private void navigate(GuiScreen screen, GuiSlot list, GamepadState state, long now) {
        boolean up = state.isPressedOrRepeated(PadButton.DPAD_UP, now);
        boolean down = state.isPressedOrRepeated(PadButton.DPAD_DOWN, now);
        boolean left = state.isPressedOrRepeated(PadButton.DPAD_LEFT, now);
        boolean right = state.isPressedOrRepeated(PadButton.DPAD_RIGHT, now);

        if (up) {
            this.snap(screen, 0, -1);
        }
        if (down) {
            this.snap(screen, 0, 1);
        }
        if (left) {
            this.snap(screen, -1, 0);
        }
        if (right) {
            this.snap(screen, 1, 0);
        }
    }

    private void scroll(GuiSlot list, GamepadState state, long now) {
        if (list == null) {
            return;
        }
        float amount = state.getAxis(PadAxis.RIGHT_Y);
        if (amount == 0.0F || now < this.nextScrollAt) {
            return;
        }
        this.nextScrollAt = now + SCROLL_INTERVAL_MS;
        list.scrollBy((int) (amount * 12.0F));
    }

    private void click(Minecraft mc, GuiScreen screen, GamepadState state) {
        GuiScreenAccessor access = (GuiScreenAccessor) screen;
        float guiX = this.guiX(mc, screen);
        float guiY = this.guiY(mc, screen);

        // Left click: press a button, pick a list entry, take a stack.
        if (state.isPressed(PadButton.SOUTH)) {
            MouseInjector.setButton(0, true);
            access.retropad$mouseClicked(guiX, guiY, 0);
            this.leftDown = true;
        } else if (this.leftDown && !state.isHeld(PadButton.SOUTH)) {
            MouseInjector.setButton(0, false);
            access.retropad$mouseMovedOrUp(guiX, guiY, 0);
            this.leftDown = false;
        }
        // Right click: split a stack, place a single item.
        if (state.isPressed(PadButton.WEST)) {
            MouseInjector.setButton(1, true);
            access.retropad$mouseClicked(guiX, guiY, 1);
            this.rightDown = true;
        } else if (this.rightDown && !state.isHeld(PadButton.WEST)) {
            MouseInjector.setButton(1, false);
            access.retropad$mouseMovedOrUp(guiX, guiY, 1);
            this.rightDown = false;
        }
    }

    /**
     * Finds whatever the pointer is sitting on, and notes when that changed.
     *
     * <p>Highlighting what is under the pointer — rather than only what the d-pad last jumped to
     * — is what makes the outline behave like a console menu: it grows onto a thing as you come
     * to rest on it, however you got there.
     */
    private void updateHovered(Minecraft mc, GuiScreen screen, long now) {
        float[] found = this.targetAt(screen, this.guiX(mc, screen), this.guiY(mc, screen));
        if (found == null) {
            this.hovered = null;
            return;
        }
        if (this.hovered == null || this.hovered[0] != found[0] || this.hovered[1] != found[1]) {
            this.hoveredSince = now;
        }
        this.hovered = found;
    }

    /**
     * Keeps the system pointer and the drawn crosshair from ever being visible at once.
     *
     * <p>One of them is always right: the crosshair while the stick is pointing, the real
     * pointer while a hand is on the mouse, and neither while the d-pad is jumping between
     * controls that already show which one is chosen.
     */
    private void syncPointer(GuiScreen screen) {
        // Hiding is about whose hand is on the controls, not about whether a crosshair happens
        // to be drawn this instant. Tying the two together meant that walking a menu with the
        // d-pad — where no crosshair is wanted — handed the system pointer back and put an
        // arrow on screen out of nowhere.
        if (PadInputMode.padDriving() && RetroPad.CONFIG.padCursor) {
            PadCursor.hide();
        } else {
            PadCursor.restore();
        }
    }

    /** Whichever target contains this point, or null. */
    private float[] targetAt(GuiScreen screen, float x, float y) {
        for (float[] target : collectTargets(screen)) {
            if (Math.abs(x - target[0]) <= target[2] && Math.abs(y - target[1]) <= target[3]) {
                return target;
            }
        }
        return null;
    }

    /** Draws the growing, pulsing square around whatever the pointer is on. */
    public void drawHoverOutline() {
        float[] target = this.hovered;
        if (target == null) {
            return;
        }
        PadOutline.draw(target[0] - target[2], target[1] - target[3],
                target[0] + target[2], target[1] + target[3],
                System.currentTimeMillis() - this.hoveredSince);
    }

    private void onScreenOpened(Minecraft mc, GuiScreen screen) {
        this.lastScreen = screen;
        PadKeyboard.onScreenOpened(screen);
        this.releaseButtons();
        this.cursorX = Mouse.getX();
        this.cursorY = Mouse.getY();
        PadInputMode.forget();
        PadCursor.reassert();
        // Start on something useful — but only when the pad is what brought us here. A menu
        // reached with the mouse keeps the pointer where the hand left it; dragging it to the
        // nearest button and holding it there is how it ended up pinned in a corner at startup,
        // with no way out until the stick was touched.
        if (PadInputMode.padRecentlyUsed(REFOCUS_WINDOW_MS)) {
            this.snapToNearest(mc, screen);
        }
    }

    public void onScreenClosed() {
        if (this.lastScreen == null) {
            return;
        }
        this.lastScreen = null;
        this.hovered = null;
        this.releaseButtons();
        PadCursor.restore();
    }

    /**
     * Writes the pad's click state into LWJGL again.
     *
     * <p>Called from the head of every screen draw, and it has to be: LWJGL refreshes its
     * button buffer from the operating system inside {@code Display.update()}, once per frame,
     * which would wipe an injected press before the screen that needs to see it ever draws.
     * Re-asserting immediately before the draw is what makes list widgets respond at all.
     */
    public void reassertButtons() {
        if (this.lastScreen == null) {
            return;
        }
        if (this.leftDown) {
            MouseInjector.setButton(0, true);
        }
        if (this.rightDown) {
            MouseInjector.setButton(1, true);
        }
    }

    /** Never leave a button stuck down in LWJGL's state — the mouse would appear jammed. */
    private void releaseButtons() {
        if (this.leftDown) {
            MouseInjector.setButton(0, false);
            this.leftDown = false;
        }
        if (this.rightDown) {
            MouseInjector.setButton(1, false);
            this.rightDown = false;
        }
    }

    private void close(Minecraft mc, GuiScreen screen) {
        this.onScreenClosed();
        if (screen instanceof GuiContainer && mc.thePlayer != null) {
            mc.thePlayer.closeScreen();
        } else {
            mc.displayGuiScreen(null);
        }
    }

    /**
     * Moves the cursor to the nearest target in the given direction. Perpendicular distance is
     * weighted heavily so "down" prefers the thing directly below over something further aside.
     */
    private void snap(GuiScreen screen, int dirX, int dirY) {
        Minecraft mc = Minecraft.getInstance();
        List<float[]> targets = collectTargets(screen);
        float fromX = this.guiX(mc, screen);
        float fromY = this.guiY(mc, screen);

        // Prefer whatever lines up with the direction of travel; only if nothing does — the
        // edge of a slot grid, a lone button off to one side — widen the search. Scoring the
        // two cases together is what used to skip slots, because a diagonal neighbour that sat
        // slightly nearer could outscore the slot directly ahead.
        float[] best = pick(targets, fromX, fromY, dirX, dirY, SNAP_CONE);
        if (best == null) {
            best = pick(targets, fromX, fromY, dirX, dirY, Float.MAX_VALUE);
        }
        if (best != null) {
            this.moveToGui(mc, screen, best[0], best[1]);
        }
    }

    /** Nearest target in the given direction whose sideways offset is within {@code maxAcross}. */
    private static float[] pick(List<float[]> targets, float fromX, float fromY,
                                int dirX, int dirY, float maxAcross) {
        float bestAlong = Float.MAX_VALUE;
        float bestAcross = Float.MAX_VALUE;
        float[] best = null;
        for (float[] target : targets) {
            float dx = target[0] - fromX;
            float dy = target[1] - fromY;
            float along = dx * dirX + dy * dirY;
            float across = Math.abs(dx * dirY) + Math.abs(dy * dirX);
            if (along < SNAP_MIN_STEP || across > maxAcross) {
                continue;
            }
            boolean closer = along < bestAlong - 0.5F;
            boolean straighter = along < bestAlong + 0.5F && across < bestAcross;
            if (closer || straighter) {
                bestAlong = along;
                bestAcross = across;
                best = target;
            }
        }
        return best;
    }

    private void snapToNearest(Minecraft mc, GuiScreen screen) {
        List<float[]> targets = collectTargets(screen);
        float fromX = this.guiX(mc, screen);
        float fromY = this.guiY(mc, screen);
        float bestScore = Float.MAX_VALUE;
        float[] best = null;
        for (float[] target : targets) {
            float dx = target[0] - fromX;
            float dy = target[1] - fromY;
            float score = dx * dx + dy * dy;
            if (score < bestScore) {
                bestScore = score;
                best = target;
            }
        }
        if (best != null) {
            this.moveToGui(mc, screen, best[0], best[1]);
        }
    }

    /** Centres of everything on this screen worth pointing at: buttons, slots, list rows. */
    private static List<float[]> collectTargets(GuiScreen screen) {
        List<float[]> targets = new ArrayList<>();

        GuiSlot list = ((GuiScreenAccessor) screen).retropad$getActiveGuiSlot();
        if (list != null) {
            addListRows(targets, list);
        }

        List<?> controls = ((GuiScreenAccessor) screen).retropad$getControlList();
        if (controls != null) {
            for (Object control : controls) {
                if (!(control instanceof GuiButton)) {
                    continue;
                }
                GuiButton button = (GuiButton) control;
                if (!button.enabled) {
                    continue;
                }
                GuiButtonAccessor size = (GuiButtonAccessor) button;
                targets.add(new float[]{
                        button.xPosition + size.retropad$getWidth() / 2.0F,
                        button.yPosition + size.retropad$getHeight() / 2.0F,
                        size.retropad$getWidth() / 2.0F,
                        size.retropad$getHeight() / 2.0F});
            }
        }

        if (screen instanceof GuiContainer) {
            GuiContainer container = (GuiContainer) screen;
            GuiContainerAccessor sizes = (GuiContainerAccessor) container;
            float left = (screen.width - sizes.retropad$getXSize()) / 2.0F;
            float top = (screen.height - sizes.retropad$getYSize()) / 2.0F;
            for (Object entry : container.inventorySlots.slots) {
                if (!(entry instanceof Slot)) {
                    continue;
                }
                Slot slot = (Slot) entry;
                targets.add(new float[]{
                        left + slot.xDisplayPosition + SLOT_HALF,
                        top + slot.yDisplayPosition + SLOT_HALF,
                        SLOT_HALF, SLOT_HALF});
            }
        }
        return targets;
    }

    /**
     * Row centres of a list widget, in the same coordinates its click handler works in.
     *
     * <p>The widget turns a pointer position into a row with
     * {@code (mouseY - top - headerPadding + amountScrolled - 4) / slotHeight}; this inverts
     * that exactly, so a snapped pointer lands in the middle of a row rather than on a seam.
     */
    private static void addListRows(List<float[]> targets, GuiSlot list) {
        GuiSlotAccessor access = (GuiSlotAccessor) list;
        int slotHeight = access.retropad$getSlotHeight();
        if (slotHeight <= 0) {
            return;
        }
        int top = access.retropad$getTop();
        int bottom = access.retropad$getBottom();
        // A row is drawn slotWidth across, centred in the widget and nudged by slotOffset — not
        // the full width of the list, which is what made the outline run off both edges.
        float centerX = (access.retropad$getLeft() + access.retropad$getRight()) / 2.0F
                + access.retropad$getSlotOffset();
        float halfWidth = access.retropad$getSlotWidth() / 2.0F;
        float base = top + access.retropad$getHeaderPadding()
                - (int) access.retropad$getAmountScrolled() + 4.0F;

        int contentHeight = access.retropad$getContentHeight();
        int size = Math.max(0, (contentHeight - access.retropad$getHeaderPadding()) / slotHeight);
        for (int row = 0; row < size; row++) {
            float centerY = base + row * slotHeight + slotHeight / 2.0F;
            // Only rows actually on screen: the pointer cannot click what is scrolled away.
            if (centerY >= top && centerY <= bottom) {
                targets.add(new float[]{centerX, centerY, halfWidth, slotHeight / 2.0F});
            }
        }
    }

    // The game maps the hardware cursor into GUI space with these two formulas; snapping has
    // to invert them exactly, or the cursor lands half a slot off at non-integer GUI scales.
    //
    // These read the position this class commanded rather than the one LWJGL reports back.
    // LWJGL only refreshes its copy when it polls the device, once a frame, so reading it back
    // straight after moving the cursor returns the previous position — which made a second
    // d-pad press in the same frame measure from the wrong place and skip a slot.

    /** Where the pointer is, in the GUI space a screen draws in. */
    public float guiX(Minecraft mc, GuiScreen screen) {
        return this.cursorX * screen.width / mc.displayWidth;
    }

    public float guiY(Minecraft mc, GuiScreen screen) {
        return screen.height - this.cursorY * screen.height / mc.displayHeight - 1.0F;
    }

    private void moveToGui(Minecraft mc, GuiScreen screen, float guiX, float guiY) {
        this.cursorX = clamp(guiX * mc.displayWidth / screen.width, 0.0F, mc.displayWidth - 1.0F);
        this.cursorY = clamp((screen.height - guiY - 1.0F) * mc.displayHeight / screen.height,
                0.0F, mc.displayHeight - 1.0F);
        MouseInjector.setCursorPosition((int) this.cursorX, (int) this.cursorY);
        PadInputMode.noteCursorWrite((int) this.cursorX, (int) this.cursorY);
    }

    private static float clamp(float value, float min, float max) {
        return value < min ? min : (value > max ? max : value);
    }

    private static float signedSquare(float value) {
        return value * Math.abs(value);
    }
}
