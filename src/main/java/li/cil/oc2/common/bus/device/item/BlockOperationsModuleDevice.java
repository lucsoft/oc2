package li.cil.oc2.common.bus.device.item;

import li.cil.oc2.api.bus.device.ItemDevice;
import li.cil.oc2.api.bus.device.object.Callback;
import li.cil.oc2.api.bus.device.object.ObjectDevice;
import li.cil.oc2.api.bus.device.object.Parameter;
import li.cil.oc2.api.bus.device.rpc.RPCDevice;
import li.cil.oc2.api.bus.device.rpc.RPCMethod;
import li.cil.oc2.api.capabilities.Robot;
import li.cil.oc2.common.Config;
import li.cil.oc2.common.Constants;
import li.cil.oc2.common.bus.device.util.IdentityProxy;
import li.cil.oc2.common.tags.ItemTags;
import li.cil.oc2.common.util.FakePlayerUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.logging.Level;

public final class BlockOperationsModuleDevice extends IdentityProxy<ItemStack> implements RPCDevice, ItemDevice {
    private static final String LAST_OPERATION_TAG_NAME = "cooldown";

    private static final int COOLDOWN = Constants.TICK_SECONDS;

    ///////////////////////////////////////////////////////////////////

    public enum PlacementDirection {
        FRONT,
        UP,
        DOWN,
    }

    ///////////////////////////////////////////////////////////////////

    private final Entity entity;
    private final Robot robot;
    private final ObjectDevice device;
    private long lastOperation;

    ///////////////////////////////////////////////////////////////////

    public BlockOperationsModuleDevice(final ItemStack identity, final Entity entity, final Robot robot) {
        super(identity);
        this.entity = entity;
        this.robot = robot;
        this.device = new ObjectDevice(this, "block_operations");
    }

    ///////////////////////////////////////////////////////////////////

    @Override
    public CompoundTag serializeNBT() {
        final CompoundTag tag = new CompoundTag();
        tag.putLong(LAST_OPERATION_TAG_NAME, lastOperation);
        return tag;
    }

    @Override
    public void deserializeNBT(final CompoundTag tag) {
        lastOperation = Mth.clamp(tag.getLong(LAST_OPERATION_TAG_NAME), 0, entity.getCommandSenderWorld().getGameTime());
    }

    @Override
    public List<String> getTypeNames() {
        return device.getTypeNames();
    }

    @Override
    public List<RPCMethod> getMethods() {
        return device.getMethods();
    }

    @Callback
    public boolean excavate(@Parameter("direction") @Nullable final PlacementDirection direction) {
        if (isOnCooldown()) {
            return false;
        }

        beginCooldown();

        final Level world = entity.getCommandSenderWorld();
        if (!(world instanceof ServerLevel)) {
            return false;
        }

        final int selectedSlot = robot.getSelectedSlot(); // Get once to avoid change due to threading.
        final ContainerHelper inventory = robot.getInventory();

        final List<ItemEntity> oldItems = getItemsInRange();

        if (!tryHarvestBlock(world, entity.blockPosition().relative(getAdjustedDirection(direction)))) {
            return false;
        }

        final List<ItemEntity> droppedItems = getItemsInRange();
        droppedItems.removeAll(oldItems);

        for (final ItemEntity itemEntity : droppedItems) {
            ItemStack stack = itemEntity.getItem();
            stack = insertStartingAt(inventory, stack, selectedSlot, false);
            itemEntity.setItem(stack);
        }

        return true;
    }

    @Callback
    public boolean place(@Parameter("direction") @Nullable final PlacementDirection direction) {
        if (isOnCooldown()) {
            return false;
        }

        beginCooldown();

        final net.minecraft.world.level.Level world = entity.getCommandSenderWorld();
        if (!(world instanceof ServerLevel) ) {
            return false;
        }

        final int selectedSlot = robot.getSelectedSlot(); // Get once to avoid change due to threading.
        final ContainerHelper inventory = robot.getInventory();

        final ItemStack extracted = inventory.extractItem(selectedSlot, 1, true);
        if (extracted.isEmpty() || !(extracted.getItem() instanceof BlockItem)) {
            return false;
        }

        final BlockPos blockPos = entity.blockPosition().relative(getAdjustedDirection(direction));
        final Direction side = getAdjustedDirection(direction).getOpposite();
        final BlockHitResult hit = new BlockHitResult(
                Vec3.atCenterOf(blockPos).add(Vec3.atCenterOf(side.getNormal()).scale(0.5)),
                side,
                blockPos,
                false);

        final ItemStack itemStack = extracted.copy();
        final BlockItem blockItem = (BlockItem) itemStack.getItem();
        final ServerPlayer player = FakePlayerUtils.getFakePlayer((ServerLevel) world, entity);
        final UseOnContext context = new BlockPlaceContext(player, InteractionHand.MAIN_HAND, itemStack, hit);

        final InteractionResult result = blockItem.place(context);
        if (!result.consumesAction()) {
            return false;
        }

        if (itemStack.isEmpty()) {
            inventory.extractItem(selectedSlot, 1, false);
        }

        return true;
    }

    @Callback(synchronize = false)
    public int durability() {
        return identity.getMaxDamage() - identity.getDamageValue();
    }

    @Callback
    public boolean repair() {
        if (isOnCooldown()) {
            return false;
        }

        beginCooldown();

        if (identity.getDamageValue() == 0) {
            return false;
        }

        final int selectedSlot = robot.getSelectedSlot(); // Get once to avoid change due to threading.
        final ContainerHelper inventory = robot.getInventory();

        final ItemStack extracted = inventory.extractItem(selectedSlot, 1, true);

        final ItemTier tier = getRepairItemTier(extracted);
        if (tier == null) {
            return false;
        }

        final int repairValue = tier.getUses();
        if (repairValue == 0) {
            return false;
        }

        // Extra check just to ease my paranoia.
        if (inventory.extractItem(selectedSlot, 1, false).isEmpty()) {
            return false;
        }

        identity.setDamageValue(identity.getDamageValue() - repairValue);

        return true;
    }

    ///////////////////////////////////////////////////////////////////

    private void beginCooldown() {
        lastOperation = entity.getCommandSenderWorld().getGameTime();
    }

    private boolean isOnCooldown() {
        return entity.getCommandSenderWorld().getGameTime() - lastOperation < COOLDOWN;
    }

    private Direction getAdjustedDirection(@Nullable final PlacementDirection placementDirection) {
        if (placementDirection == PlacementDirection.UP) {
            return Direction.UP;
        } else if (placementDirection == PlacementDirection.DOWN) {
            return Direction.DOWN;
        } else {
            Direction direction = Direction.SOUTH;
            final int horizontalIndex = entity.getDirection().get2DDataValue();
            for (int i = 0; i < horizontalIndex; i++) {
                direction = direction.getClockWise();
            }
            return direction;
        }
    }

    private List<ItemEntity> getItemsInRange() {
        return entity.getCommandSenderWorld().getEntitiesOfClass(ItemEntity.class, entity.getBoundingBox().inflate(2));
    }

    private boolean tryHarvestBlock(final Level world, final BlockPos blockPos) {
        // This method is based on PlayerInteractionManager::tryHarvestBlock. Simplified for our needs.
        final BlockState blockState = world.getBlockState(blockPos);
        if (blockState.isAir(world, blockPos)) {
            return false;
        }

        final ServerPlayer player = FakePlayerUtils.getFakePlayer((ServerLevel) world, entity);
        final int experience = net.minecraftforge.common.ForgeHooks.onBlockBreakEvent(world, GameType.NOT_SET, player, blockPos);
        if (experience == -1) {
            return false;
        }

        final BlockEntity tileEntity = world.getBlockEntity(blockPos);
        final Block block = blockState.getBlock();
        final boolean isCommandBlock = block instanceof CommandBlockBlock || block instanceof StructureBlock || block instanceof JigsawBlock;
        if (isCommandBlock && !player.canUseGameMasterBlocks()) {
            return false;
        }

        if (player.blockActionRestricted(world, blockPos, GameType.NOT_SET)) {
            return false;
        }

        final boolean canHarvest = Config.blockOperationsModuleToolLevel >= blockState.getHarvestLevel();
        if (!canHarvest) {
            return false;
        }

        if (!ForgeEventFactory.doPlayerHarvestCheck(player, blockState, canHarvest)) {
            return false;
        }

        if (identity.hurt(1, world.random, null)) {
            return false;
        }

        if (!blockState.removedByPlayer(world, blockPos, player, true, world.getFluidState(blockPos))) {
            return false;
        }

        block.destroy(world, blockPos, blockState);
        block.playerDestroy(world, player, blockPos, blockState, tileEntity, ItemStack.EMPTY);

        return true;
    }

    @Nullable
    private ItemTier getRepairItemTier(final ItemStack stack) {
        if (stack.isEmpty()) {
            return null;
        }

        final Item item = stack.getItem();
        if (ItemTags.TOOL_MATERIAL_NETHERITE.contains(item)) {
            return ItemTier.NETHERITE;
        } else if (ItemTags.TOOL_MATERIAL_GOLD.contains(item)) {
            return ItemTier.DIAMOND;
        } else if (ItemTags.TOOL_MATERIAL_DIAMOND.contains(item)) {
            return ItemTier.GOLD;
        } else if (ItemTags.TOOL_MATERIAL_IRON.contains(item)) {
            return ItemTier.IRON;
        } else if (ItemTags.TOOL_MATERIAL_STONE.contains(item)) {
            return ItemTier.STONE;
        } else if (ItemTags.TOOL_MATERIAL_WOOD.contains(item)) {
            return ItemTier.WOOD;
        }

        return null;
    }

    private ItemStack insertStartingAt(final IItemHandler handler, ItemStack stack, final int startSlot, final boolean simulate) {
        for (int i = 0; i < handler.getSlots(); i++) {
            final int slot = (startSlot + i) % handler.getSlots();
            stack = handler.insertItem(slot, stack, simulate);
            if (stack.isEmpty()) {
                return ItemStack.EMPTY;
            }
        }

        return stack;
    }
}
