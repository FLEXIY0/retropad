package io.github.retropad.craft;

import io.github.retropad.gui.ItemTint;
import io.github.retropad.input.PadRumble;
import io.github.retropad.gui.PadOutline;
import io.github.retropad.gui.PadRenderState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiContainer;
import net.minecraft.client.renderer.entity.RenderItem;
import net.minecraft.client.renderer.world.RenderHelper;
import net.minecraft.common.entity.player.InventoryPlayer;
import net.minecraft.common.item.ItemStack;
import net.minecraft.common.recipe.Ingredient;
import com.indigo3d.util.RenderSystem;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The console crafting menu, wearing Better Than Legacy's own interface.
 *
 * <p>The window graphic, tab strip, cell sprites and category icons come from that mod
 * (CC0-1.0, licence kept beside the textures), and so does the layout: a 273x175 window, eight
 * 35-wide tabs overlapping by a pixel, a strip of recipe cells 18 apart at y=56, the ingredient
 * grid at (20,109), the result at (103,123), the player's inventory drawn small from (152,112).
 * Recipes that cannot be made right now are drawn faded rather than washed over: the original's
 * coloured square sits on the tile and reads as a sticker, while opacity follows the item's own
 * shape.
 *
 * <p>It is drawn over the crafting screen rather than instead of it: switching screens runs
 * {@code GuiContainer.onGuiClosed}, which closes the container on the server, and a workbench
 * would shut the moment the menu opened. Selection is an index, not a pointer, so the d-pad
 * alone drives it.
 */
public final class PadCraftingOverlay {

    /** Recipe cells across the strip — what the window graphic has room for. */
    private static final int STRIP_CELLS = 14;
    private static final int STRIP_ITEM_Y = 56;
    private static final int CELL_STEP = 18;
    private static final int CELL_ORIGIN_X = 12;
    /** Item icons are 16 wide inside an 18 cell, which is what the outline covers. */
    private static final int ICON = 16;
    /** The cell an item is drawn into. Anything wider scales the item up to match. */
    private static final int SLOT = 18;

    private static final int INGREDIENT_X = 20;
    private static final int INGREDIENT_Y = 109;
    private static final int RESULT_X = 103;
    private static final int RESULT_Y = 123;
    /** The result box is a wider cell than the rest, so its item is drawn bigger to fill it. */
    private static final int RESULT_SLOT = 26;

    private static final int INVENTORY_X = 152;
    private static final int INVENTORY_Y = 112;
    private static final int INVENTORY_STEP = 12;
    private static final int HOTBAR_Y = 154;
    /** The inventory on the right uses 12-wide cells. */
    private static final int INVENTORY_SLOT = 12;

    /**
     * How solid an item is drawn when it cannot be made. Applied to the item's own pixels rather
     * than as a square over its cell, so the shape stays readable and the tile does not change.
     */
    private static final float LOCKED_ALPHA = 0.35F;
    private static final int SCREEN_DIM = 0xC0101010;
    private static final int TEXT_BAD = 0xFFAA3333;

    /** How long a tab's icon takes to swell when it becomes the active one. */
    private static final long TAB_GROW_MS = 130L;
    /** A status line stays for this long, fading over the last part of it. */
    private static final long STATUS_MS = 2600L;
    private static final long STATUS_FADE_MS = 600L;

    /** Craftability is rechecked on this interval rather than every frame. */
    private static final long CRAFTABLE_REFRESH_MS = 250L;

    private static final RenderItem ITEM_RENDERER = new RenderItem();
    private static final Random RANDOM = new Random();

    private static final PadCraftingOverlay INSTANCE = new PadCraftingOverlay();

    /** Which icon sits on each category's tab. */
    private static final String[] TAB_ICONS = {
            "bricks", "painting", "tools", "armor", "lever", "rail", "health", "unknown"
    };

    private GuiContainer screen;
    private RecipeCategory category = RecipeCategory.BUILDING;
    private int selectedGroup;
    private int variant;
    private int stripStart;

    private long selectionChangedAt;
    private long categoryChangedAt;
    private long statusAt;
    private String status;

    private ItemStack[] ingredientSamples;
    private int samplesForGroup = -1;
    private int samplesForVariant = -1;

    /** The category's groups, craftable ones first; rebuilt when the category changes. */
    private List<PadRecipeGroup> ordered = new ArrayList<>();
    private boolean[] craftable = new boolean[0];
    private long craftableCheckedAt;

    private PadCraftingOverlay() {}

    public static PadCraftingOverlay get() {
        return INSTANCE;
    }

    public boolean isOpen() {
        return this.screen != null;
    }

    public boolean isOpenOn(GuiContainer gui) {
        return this.screen != null && this.screen == gui;
    }

    /** Opens the menu over a crafting screen. Does nothing on a screen with no grid. */
    public boolean open(GuiContainer gui) {
        if (gui == null || CraftExecutor.resultSlot(gui.inventorySlots) == null) {
            return false;
        }
        this.screen = gui;
        this.status = null;
        this.selectedGroup = 0;
        this.variant = 0;
        this.stripStart = 0;
        this.samplesForGroup = -1;
        this.selectionChangedAt = System.currentTimeMillis();
        this.categoryChangedAt = this.selectionChangedAt;
        RecipeCatalog.get();
        this.reorder();
        return true;
    }

    public void close() {
        this.screen = null;
        this.status = null;
    }

    // ------------------------------------------------------------------ input

    /**
     * Left and right walk the strip; up and down cycle the ways of making the selected item,
     * which is what the arrows above and below the cell stand for.
     */
    public void moveSelection(int dx, int dy) {
        List<PadRecipeGroup> groups = this.groups();
        if (groups.isEmpty()) {
            return;
        }
        if (dy != 0) {
            PadRecipeGroup group = groups.get(this.clampSelection(groups));
            if (group.hasAlternatives()) {
                this.variant += dy;
                this.touchSelection();
            }
            return;
        }
        int index = this.selectedGroup + dx;
        if (index < 0 || index >= groups.size()) {
            return;
        }
        this.selectedGroup = index;
        this.variant = 0;
        this.scrollStripToSelection(groups.size());
        this.touchSelection();
    }

    public void nextCategory(int direction) {
        int index = this.category.ordinal() + direction;
        if (index < 0) {
            index = RecipeCategory.VALUES.length - 1;
        } else if (index >= RecipeCategory.VALUES.length) {
            index = 0;
        }
        this.category = RecipeCategory.VALUES[index];
        this.selectedGroup = 0;
        this.variant = 0;
        this.stripStart = 0;
        this.categoryChangedAt = System.currentTimeMillis();
        this.reorder();
        this.touchSelection();
    }

    /** Crafts the highlighted recipe, leaving a message about what happened. */
    public void craftSelected() {
        PadRecipe recipe = this.selectedRecipe();
        if (recipe == null) {
            return;
        }
        String reason = CraftExecutor.reasonCannotCraft(this.screen, recipe);
        if (reason != null) {
            this.setStatus(reason);
            return;
        }
        boolean crafted = CraftExecutor.craft(this.screen, recipe);
        this.setStatus(crafted ? "Crafted" : "Could not craft");
        if (crafted) {
            PadRumble.play(PadRumble.Effect.CRAFT);
        }
        // Refresh which cells are dimmed, but leave the order alone: resorting under the
        // player's cursor the moment they craft something would move everything they were
        // looking at.
        this.craftableCheckedAt = 0L;
    }

    /** Routes a click inside the menu. Returns true when the click belonged to the menu. */
    public boolean click(float guiX, float guiY) {
        if (!this.isOpen()) {
            return false;
        }
        float winX = this.windowX();
        float winY = this.windowY();

        for (int i = 0; i < RecipeCategory.VALUES.length; i++) {
            float tabX = winX + LegacySkin.TAB_STEP * i;
            float tabY = winY - 2;
            if (guiX >= tabX && guiX < tabX + LegacySkin.TAB_WIDTH
                    && guiY >= tabY && guiY < tabY + LegacySkin.TAB_HEIGHT) {
                this.nextCategory(i - this.category.ordinal());
                return true;
            }
        }

        float stripY = winY + LegacySkin.STRIP_Y;
        if (guiY >= stripY && guiY < stripY + CELL_STEP) {
            for (int cell = 0; cell < STRIP_CELLS; cell++) {
                float cellX = winX + CELL_ORIGIN_X + CELL_STEP * cell;
                if (guiX >= cellX && guiX < cellX + CELL_STEP) {
                    int index = this.stripStart + cell;
                    if (index < this.groups().size()) {
                        if (index == this.selectedGroup) {
                            this.craftSelected();
                        } else {
                            this.selectedGroup = index;
                            this.variant = 0;
                            this.touchSelection();
                        }
                    }
                    return true;
                }
            }
        }

        // Anywhere else on the window is still the menu's, so a stray click cannot reach the
        // inventory underneath and scatter a stack.
        return guiX >= winX && guiX <= winX + LegacySkin.WINDOW_WIDTH
                && guiY >= winY - 2 && guiY <= winY + LegacySkin.WINDOW_HEIGHT;
    }

    // ----------------------------------------------------------------- render

    public void render(GuiContainer gui, float partialTicks) {
        if (!this.isOpenOn(gui)) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        FontRenderer font = mc.fontRenderer;
        long now = System.currentTimeMillis();
        float winX = this.windowX();
        float winY = this.windowY();

        // Mute the screen underneath: its own labels and buttons sit outside this window and
        // would otherwise read as part of the menu.
        Gui.drawRect(0.0F, 0.0F, gui.width, gui.height, SCREEN_DIM);

        this.refreshCraftable(now);

        LegacySkin.bindAtlas(mc);
        LegacySkin.blit(winX, winY, 0.0F, 0.0F, LegacySkin.WINDOW_WIDTH, LegacySkin.WINDOW_HEIGHT);

        int tab = this.category.ordinal();
        LegacySkin.blit(winX + LegacySkin.TAB_STEP * tab, winY - 2,
                LegacySkin.TAB_WIDTH * tab, LegacySkin.TAB_SELECTED_V,
                LegacySkin.TAB_WIDTH, LegacySkin.TAB_HEIGHT);

        this.drawTabIcons(mc, winX, winY, tab, now);
        this.drawRecipeStrip(mc, font, winX, winY);
        this.drawSelectedRecipe(mc, font, winX, winY);
        this.drawInventoryPreview(mc, font, winX, winY);
        this.drawLabels(font, winX, winY);
        // The cell frame and its arrows go on last, exactly as the original draws them in its
        // foreground layer: the arrow below the cell reaches down into the heading, and it is
        // the arrow that should cover the text rather than the text cutting through it.
        LegacySkin.bindAtlas(mc);
        this.drawSelectionCursor(winX, winY);
        this.drawSelectionOutline(winX, winY, now);
        this.drawStatus(font, winX, winY, now);
    }

    private void drawTabIcons(Minecraft mc, float winX, float winY, int selectedTab, long now) {
        // The active tab's icon swells into place rather than snapping, which is what makes
        // flicking through categories feel like the console menu it is copying.
        float swell = ease(Math.min(1.0F, (now - this.categoryChangedAt) / (float) TAB_GROW_MS));
        for (int i = 0; i < RecipeCategory.VALUES.length && i < TAB_ICONS.length; i++) {
            boolean isSelected = i == selectedTab;
            float scale = isSelected ? 0.75F + 0.15F * swell : 0.75F;
            float size = 32.0F * scale;
            float x = winX + (isSelected ? 3.0F + 2.5F * (1.0F - swell) : 5.5F) + LegacySkin.TAB_STEP * i;
            float y = winY + (isSelected ? 2.0F - 3.0F * swell : 2.0F);
            LegacySkin.icon(mc, TAB_ICONS[i], x, y, size);
        }
        LegacySkin.bindAtlas(mc);
    }

    /** The frame around the chosen cell, and the arrows when the item has other recipes. */
    private void drawSelectionCursor(float winX, float winY) {
        List<PadRecipeGroup> groups = this.groups();
        if (groups.isEmpty()) {
            return;
        }
        int cell = this.selectedGroup - this.stripStart;
        float x = winX + 8.0F + CELL_STEP * cell;
        float y = winY + 52.0F;
        PadRecipeGroup group = groups.get(this.clampSelection(groups));
        if (group.hasAlternatives()) {
            LegacySkin.blit(x - 1.0F, y, LegacySkin.CELL_TALL_U, LegacySkin.CELL_TALL_V,
                    LegacySkin.CELL_TALL_W, LegacySkin.CELL_TALL_H);
            LegacySkin.blit(x - 1.0F, y - 31.0F, LegacySkin.ARROW_UP_U, LegacySkin.ARROW_UP_V,
                    LegacySkin.ARROW_W, LegacySkin.ARROW_H);
            LegacySkin.blit(x - 1.0F, y + 24.0F, LegacySkin.ARROW_DOWN_U, LegacySkin.ARROW_DOWN_V,
                    LegacySkin.ARROW_W, LegacySkin.ARROW_H);
        } else {
            LegacySkin.blit(x, y, LegacySkin.CELL_PLAIN_U, LegacySkin.CELL_PLAIN_V,
                    LegacySkin.CELL_PLAIN_SIZE, LegacySkin.CELL_PLAIN_SIZE);
        }
    }

    /** The square that grows onto the chosen cell and then pulses — the same one menus use. */
    private void drawSelectionOutline(float winX, float winY, long now) {
        if (this.groups().isEmpty()) {
            return;
        }
        int cell = this.selectedGroup - this.stripStart;
        // Exactly the item's own 16 pixels. Drawn around the 18-wide cell instead, it reads as
        // a box that is too big for what it is pointing at.
        float x0 = winX + CELL_ORIGIN_X + CELL_STEP * cell;
        float y0 = winY + STRIP_ITEM_Y;
        PadOutline.draw(x0, y0, x0 + ICON, y0 + ICON, now - this.selectionChangedAt);
    }

    /** True when the highlighted item can be made in more than one way. */
    public boolean selectionHasAlternatives() {
        List<PadRecipeGroup> groups = this.groups();
        return !groups.isEmpty() && groups.get(this.clampSelection(groups)).hasAlternatives();
    }

    private void drawRecipeStrip(Minecraft mc, FontRenderer font, float winX, float winY) {
        List<PadRecipeGroup> groups = this.groups();
        for (int cell = 0; cell < STRIP_CELLS; cell++) {
            int index = this.stripStart + cell;
            if (index >= groups.size()) {
                break;
            }
            float x = winX + CELL_ORIGIN_X + CELL_STEP * cell;
            float y = winY + STRIP_ITEM_Y;
            boolean locked = index < this.craftable.length && !this.craftable[index];
            if (locked) {
                ItemTint.fade(LOCKED_ALPHA);
            }
            try {
                drawItem(mc, font, groups.get(index).getOutput(), x, y, SLOT);
            } finally {
                ItemTint.clear();
            }
        }
    }

    /** The ingredients of the chosen recipe, laid into the window's own grid and result box. */
    private void drawSelectedRecipe(Minecraft mc, FontRenderer font, float winX, float winY) {
        PadRecipe recipe = this.selectedRecipe();
        if (recipe == null) {
            return;
        }
        ItemStack[] samples = this.ingredientSamples(recipe);
        int next = 0;
        for (int row = 0; row < recipe.getHeight(); row++) {
            for (int column = 0; column < recipe.getWidth(); column++) {
                Ingredient ingredient = recipe.cellAt(column, row);
                if (ingredient == null) {
                    continue;
                }
                ItemStack sample = next < samples.length ? samples[next] : null;
                next++;
                drawItem(mc, font, sample,
                        winX + INGREDIENT_X + CELL_STEP * column,
                        winY + INGREDIENT_Y + CELL_STEP * row, SLOT);
            }
        }
        drawItem(mc, font, recipe.getOutput(), winX + RESULT_X, winY + RESULT_Y, RESULT_SLOT);
    }

    /**
     * The player's own items, small, on the right — the half of the window the original gives to
     * the inventory. Read-only here: this menu crafts, it does not move stacks about.
     */
    private void drawInventoryPreview(Minecraft mc, FontRenderer font, float winX, float winY) {
        if (mc.thePlayer == null) {
            return;
        }
        InventoryPlayer inventory = mc.thePlayer.inventory;
        for (int slot = 0; slot < 36 && slot < inventory.mainInventory.length; slot++) {
            ItemStack stack = inventory.mainInventory[slot];
            if (stack == null) {
                continue;
            }
            float x;
            float y;
            if (slot < 9) {
                x = winX + INVENTORY_X + INVENTORY_STEP * slot;
                y = winY + HOTBAR_Y;
            } else {
                x = winX + INVENTORY_X + INVENTORY_STEP * ((slot - 9) % 9);
                y = winY + INVENTORY_Y + INVENTORY_STEP * ((slot - 9) / 9);
            }
            drawItem(mc, font, stack, x, y, INVENTORY_SLOT);
        }
    }

    private void drawLabels(FontRenderer font, float winX, float winY) {
        PadRecipe recipe = this.selectedRecipe();
        String heading = recipe == null ? "Crafting" : trim(safeName(recipe.getOutput()));
        drawCentered(font, heading, winX + LegacySkin.LABEL_CRAFTING_X, winY + LegacySkin.LABEL_Y);
        drawCentered(font, "Inventory", winX + LegacySkin.LABEL_RIGHT_X, winY + LegacySkin.LABEL_Y);

        String title = this.category.getLabel();
        List<PadRecipeGroup> groups = this.groups();
        if (!groups.isEmpty()) {
            title = title + "   " + (this.selectedGroup + 1) + "/" + groups.size();
        }
        drawCentered(font, title, winX + LegacySkin.WINDOW_WIDTH / 2.0F,
                winY + LegacySkin.CATEGORY_LABEL_Y);
    }

    /** Whatever the last craft attempt had to say, fading out on its own. */
    private void drawStatus(FontRenderer font, float winX, float winY, long now) {
        if (this.status == null) {
            return;
        }
        long age = now - this.statusAt;
        if (age > STATUS_MS) {
            this.status = null;
            return;
        }
        float fade = age > STATUS_MS - STATUS_FADE_MS
                ? (STATUS_MS - age) / (float) STATUS_FADE_MS : 1.0F;
        int color = ((int) (fade * 255.0F) << 24) | (TEXT_BAD & 0xFFFFFF);
        font.drawStringWithShadow(this.status, winX + 8.0F,
                winY + LegacySkin.WINDOW_HEIGHT + 4.0F, color);
    }

    private static void drawCentered(FontRenderer font, String text, float centerX, float y) {
        if (text == null || font == null) {
            return;
        }
        font.drawString(text, centerX - font.getStringWidth(text) / 2.0F, y, LegacySkin.LABEL_COLOR);
    }

    /**
     * Draws an item filling a cell of the given width.
     *
     * <p>An item icon is 16 wide and sits inside an 18 cell, so anything else — the 26 result
     * box, the 12 inventory cells — has to be scaled by the ratio of the two, exactly as the
     * original does it. Scaling rather than nudging is what keeps the item centred: the cell's
     * own 1px margin scales with it. All three axes are scaled because an item that is a block
     * is a 3D cube and would otherwise keep its full depth.
     *
     * <p>The lights are placed after a 120 degree turn, exactly as {@code GuiContainer} does it
     * before drawing its slots. Light positions are taken through the matrix that is current
     * when they are set, so without the turn they shine into the screen instead of down onto
     * the item and every block comes out a dark silhouette of itself. Rescaling normals is the
     * other half of it: scaling the item scales its normals with it, and unscaled normals make
     * the result box darker and the inventory cells brighter than they should be.
     */
    private static void drawItem(Minecraft mc, FontRenderer font, ItemStack stack,
                                 float x, float y, int slotWidth) {
        if (stack == null) {
            return;
        }
        float scale = slotWidth / (float) SLOT;
        GL11.glPushMatrix();
        GL11.glRotatef(120.0F, 1.0F, 0.0F, 0.0F);
        RenderHelper.enableStandardItemLighting();
        GL11.glPopMatrix();
        RenderSystem.enableRescaleNormal();
        GL11.glPushMatrix();
        try {
            GL11.glScalef(scale, scale, scale);
            ITEM_RENDERER.renderItemIntoGUI(font, mc.renderEngine, stack, x / scale, y / scale);
            ITEM_RENDERER.renderItemOverlayIntoGUI(font, mc.renderEngine, stack, x / scale, y / scale);
        } catch (Throwable ignored) {
            // A broken item model must not take the whole menu down with it.
        } finally {
            GL11.glPopMatrix();
            RenderSystem.disableRescaleNormal();
            RenderHelper.disableStandardItemLighting();
            PadRenderState.begin();
        }
    }

    // ------------------------------------------------------------------ state

    /** Ease-out, so growth is quick at the start and settles rather than stopping dead. */
    private static float ease(float t) {
        float inverse = 1.0F - t;
        return 1.0F - inverse * inverse;
    }

    private void touchSelection() {
        this.selectionChangedAt = System.currentTimeMillis();
        this.samplesForGroup = -1;
        this.status = null;
    }

    private void setStatus(String message) {
        this.status = message;
        this.statusAt = System.currentTimeMillis();
    }

    /** Keeps the selected cell inside the visible strip, scrolling as it reaches an edge. */
    private void scrollStripToSelection(int size) {
        if (this.selectedGroup < this.stripStart) {
            this.stripStart = this.selectedGroup;
        } else if (this.selectedGroup >= this.stripStart + STRIP_CELLS) {
            this.stripStart = this.selectedGroup - STRIP_CELLS + 1;
        }
        int maxStart = Math.max(0, size - STRIP_CELLS);
        if (this.stripStart > maxStart) {
            this.stripStart = maxStart;
        }
        if (this.stripStart < 0) {
            this.stripStart = 0;
        }
    }

    /**
     * Works out which of the visible recipes can actually be made, on a timer rather than every
     * frame: each check walks the player's inventory once per recipe.
     */
    private void refreshCraftable(long now) {
        if (now - this.craftableCheckedAt < CRAFTABLE_REFRESH_MS) {
            return;
        }
        this.craftableCheckedAt = now;
        List<PadRecipeGroup> groups = this.groups();
        if (this.craftable.length != groups.size()) {
            this.craftable = new boolean[groups.size()];
        }
        // Only the cells on screen are worth rechecking; each check walks the inventory once.
        int from = Math.max(0, this.stripStart);
        int to = Math.min(groups.size(), this.stripStart + STRIP_CELLS);
        for (int i = from; i < to; i++) {
            this.craftable[i] = this.canCraftAny(groups.get(i));
        }
    }

    private float windowX() {
        return (this.screen.width - LegacySkin.WINDOW_WIDTH) / 2.0F;
    }

    private float windowY() {
        return (this.screen.height - LegacySkin.WINDOW_HEIGHT) / 2.0F;
    }

    private ItemStack[] ingredientSamples(PadRecipe recipe) {
        if (this.samplesForGroup == this.selectedGroup && this.samplesForVariant == this.variant
                && this.ingredientSamples != null) {
            return this.ingredientSamples;
        }
        Ingredient[] ingredients = recipe.ingredients();
        ItemStack[] samples = new ItemStack[ingredients.length];
        for (int i = 0; i < ingredients.length; i++) {
            try {
                samples[i] = ingredients[i].getRandomItemStack(RANDOM);
            } catch (Throwable ignored) {
                samples[i] = null;
            }
        }
        this.ingredientSamples = samples;
        this.samplesForGroup = this.selectedGroup;
        this.samplesForVariant = this.variant;
        return samples;
    }

    private int clampSelection(List<PadRecipeGroup> groups) {
        if (this.selectedGroup < 0) {
            this.selectedGroup = 0;
        } else if (this.selectedGroup >= groups.size()) {
            this.selectedGroup = groups.size() - 1;
        }
        return this.selectedGroup;
    }

    private List<PadRecipeGroup> groups() {
        return this.ordered;
    }

    /**
     * Rebuilds the category's list with everything you can make right now at the front.
     *
     * <p>Done when the category is entered rather than continuously: the order has to hold still
     * while you browse it, or crafting something would rearrange the strip under your hands. The
     * dimming stays live either way — only the order is a snapshot.
     */
    private void reorder() {
        List<PadRecipeGroup> source = RecipeCatalog.get().inCategory(this.category);
        List<PadRecipeGroup> available = new ArrayList<>();
        List<PadRecipeGroup> rest = new ArrayList<>();
        for (PadRecipeGroup group : source) {
            if (this.canCraftAny(group)) {
                available.add(group);
            } else {
                rest.add(group);
            }
        }
        // Two passes rather than a comparator, so the game's own recipe order survives inside
        // each half.
        List<PadRecipeGroup> combined = new ArrayList<>(available.size() + rest.size());
        combined.addAll(available);
        combined.addAll(rest);
        this.ordered = combined;

        this.craftable = new boolean[combined.size()];
        for (int i = 0; i < combined.size(); i++) {
            this.craftable[i] = i < available.size();
        }
        this.craftableCheckedAt = System.currentTimeMillis();
    }

    private boolean canCraftAny(PadRecipeGroup group) {
        for (PadRecipe recipe : group.variants()) {
            if (CraftExecutor.reasonCannotCraft(this.screen, recipe) == null) {
                return true;
            }
        }
        return false;
    }

    private PadRecipe selectedRecipe() {
        List<PadRecipeGroup> groups = this.groups();
        if (groups.isEmpty()) {
            return null;
        }
        return groups.get(this.clampSelection(groups)).variant(this.variant);
    }

    private static String trim(String name) {
        if (name == null) {
            return "";
        }
        return name.length() > 21 ? name.substring(0, 18) + "..." : name;
    }

    private static String safeName(ItemStack stack) {
        try {
            String name = stack.getDisplayName();
            return name == null ? "?" : name;
        } catch (Throwable ignored) {
            return "?";
        }
    }
}
