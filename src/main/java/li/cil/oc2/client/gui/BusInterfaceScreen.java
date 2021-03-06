package li.cil.oc2.client.gui;

import com.mojang.blaze3d.matrix.PoseStack;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import li.cil.oc2.api.API;
import li.cil.oc2.client.gui.widget.ImageButton;
import li.cil.oc2.client.gui.widget.Sprite;
import li.cil.oc2.common.Constants;
import li.cil.oc2.common.item.Items;
import li.cil.oc2.common.network.Network;
import li.cil.oc2.common.network.message.BusInterfaceNameMessage;
import li.cil.oc2.common.tileentity.BusCableBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.TranslatableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Direction;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.vector.Vec3;
import net.minecraft.util.text.TranslatableComponent;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

public final class BusInterfaceScreen extends Screen {
    private static final ResourceLocation BACKGROUND_LOCATION = new ResourceLocation(API.MOD_ID, "textures/gui/screen/bus_interface.png");

    private static final Sprite BACKGROUND = new Sprite(BACKGROUND_LOCATION, 256, 240, 30, 0, 0);
    private static final Sprite CONFIRM_BASE = new Sprite(BACKGROUND_LOCATION, 256, 12, 12, 5, 35);
    private static final Sprite CONFIRM_PRESSED = new Sprite(BACKGROUND_LOCATION, 256, 12, 12, 20, 35);
    private static final Sprite CANCEL_BASE = new Sprite(BACKGROUND_LOCATION, 256, 12, 12, 5, 50);
    private static final Sprite CANCEL_PRESSED = new Sprite(BACKGROUND_LOCATION, 256, 12, 12, 20, 50);

    private static final int TEXT_LEFT = 9;
    private static final int TEXT_TOP = 11;
    private static final int CONFIRM_LEFT = 206;
    private static final int CONFIRM_TOP = 9;
    private static final int CANCEL_LEFT = 219;
    private static final int CANCEL_TOP = 9;

    private final BusCableBlockEntity tileEntity;
    private final Direction side;

    private EditBox nameField;

    private int left, top;

    ///////////////////////////////////////////////////////////////////

    public BusInterfaceScreen(final BusCableBlockEntity tileEntity, final Direction side) {
        super(Items.BUS_INTERFACE.get().getDescription());
        this.tileEntity = tileEntity;
        this.side = side;
    }

    ///////////////////////////////////////////////////////////////////

    @Override
    protected void init() {
        super.init();

        Minecraft.getInstance().keyboardHandler.setSendRepeatsToGui(true);

        left = (width - BACKGROUND.width) / 2;
        top = (height - BACKGROUND.height) / 2;

        nameField = new EditBox(font, left + TEXT_LEFT, top + TEXT_TOP, 192, 12, new TranslatableComponent("oc2.gui.bus_interface_name"));
        nameField.setCanLoseFocus(false);
        nameField.setTextColor(0xFFFFFFFF);
        nameField.setBordered(false);
        nameField.setMaxLength(32);
        nameField.setValue(tileEntity.getInterfaceName(side));
        addWidget(nameField);
        setFocused(nameField);

        addButton(new ImageButton(
                this,
                left + CONFIRM_LEFT, top + CONFIRM_TOP,
                CONFIRM_BASE.width, CONFIRM_BASE.height,
                new TranslatableComponent(Constants.TOOLTIP_CONFIRM),
                null,
                CONFIRM_BASE,
                CONFIRM_PRESSED
        ) {
            @Override
            public void onPress() {
                super.onPress();
                setInterfaceName(nameField.getValue());
                onClose();
            }
        });
        addButton(new ImageButton(
                this,
                left + CANCEL_LEFT, top + CANCEL_TOP,
                CANCEL_BASE.width, CANCEL_BASE.height,
                new TranslatableComponent(Constants.TOOLTIP_CANCEL),
                null,
                CANCEL_BASE,
                CANCEL_PRESSED
        ) {
            @Override
            public void onPress() {
                super.onPress();
                onClose();
            }
        });
    }

    @Override
    public void onClose() {
        super.onClose();

        Minecraft.getInstance().keyboardHandler.setSendRepeatsToGui(false);
    }

    @Override
    public void tick() {
        super.tick();
        nameField.tick();

        final Vec3 busCableCenter = Vec3.atCenterOf(tileEntity.getBlockPos());
        if (Minecraft.getInstance().player.distanceToSqr(busCableCenter) > 8 * 8) {
            onClose();
        }
    }

    @Override
    public boolean keyPressed(final int keyCode, final int scanCode, final int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ENTER ||
            keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            setInterfaceName(nameField.getValue());
            onClose();
            return true;
        }

        return nameField.keyPressed(keyCode, scanCode, modifiers) || super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void render(final PoseStack matrixStack, final int mouseX, final int mouseY, final float partialTicks) {
        renderBackground(matrixStack);
        BACKGROUND.draw(matrixStack, left, top);

        super.render(matrixStack, mouseX, mouseY, partialTicks);

        RenderSystem.disableBlend();
        nameField.render(matrixStack, mouseX, mouseY, partialTicks);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    ///////////////////////////////////////////////////////////////////

    private void setInterfaceName(final String name) {
        Network.INSTANCE.sendToServer(new BusInterfaceNameMessage.ToServer(tileEntity, side, name));
    }
}
