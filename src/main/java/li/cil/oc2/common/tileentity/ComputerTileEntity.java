package li.cil.oc2.common.tileentity;

import li.cil.oc2.api.bus.DeviceBusElement;
import li.cil.oc2.api.bus.device.Device;
import li.cil.oc2.api.bus.device.DeviceTypes;
import li.cil.oc2.api.bus.device.provider.BlockDeviceQuery;
import li.cil.oc2.api.bus.device.provider.ItemDeviceQuery;
import li.cil.oc2.api.capabilities.TerminalUserProvider;
import li.cil.oc2.client.audio.LoopingSoundManager;
import li.cil.oc2.common.Config;
import li.cil.oc2.common.Constants;
import li.cil.oc2.common.block.ComputerBlock;
import li.cil.oc2.common.bus.CommonDeviceBusController;
import li.cil.oc2.common.bus.BlockEntityDeviceBusController;
import li.cil.oc2.common.bus.BlockEntityDeviceBusElement;
import li.cil.oc2.common.bus.device.util.BlockDeviceInfo;
import li.cil.oc2.common.bus.device.util.Devices;
import li.cil.oc2.common.capabilities.Capabilities;
import li.cil.oc2.common.container.ComputerInventoryContainer;
import li.cil.oc2.common.container.ComputerTerminalContainer;
import li.cil.oc2.common.container.DeviceContainerHelper;
import li.cil.oc2.common.energy.FixedEnergyStorage;
import li.cil.oc2.common.network.Network;
import li.cil.oc2.common.network.message.ComputerBootErrorMessage;
import li.cil.oc2.common.network.message.ComputerBusStateMessage;
import li.cil.oc2.common.network.message.ComputerRunStateMessage;
import li.cil.oc2.common.network.message.ComputerTerminalOutputMessage;
import li.cil.oc2.common.serialization.TagSerialization;
import li.cil.oc2.common.util.*;
import li.cil.oc2.common.vm.*;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TranslatableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.TickableBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.fml.network.NetworkHooks;
import org.jetbrains.annotations.Nullable;

import java.nio.ByteBuffer;
import java.util.*;

import static li.cil.oc2.common.Constants.BLOCK_ENTITY_TAG_NAME_IN_ITEM;
import static li.cil.oc2.common.Constants.ITEMS_TAG_NAME;

public final class ComputerBlockEntity extends AbstractBlockEntity implements TickableBlockEntity, TerminalUserProvider {
    private static final String BUS_ELEMENT_TAG_NAME = "busElement";
    private static final String TERMINAL_TAG_NAME = "terminal";
    private static final String STATE_TAG_NAME = "state";

    private static final int MEMORY_SLOTS = 4;
    private static final int HARD_DRIVE_SLOTS = 4;
    private static final int FLASH_MEMORY_SLOTS = 1;
    private static final int CARD_SLOTS = 4;

    private static final int MAX_RUNNING_SOUND_DELAY = Constants.TICK_SECONDS * 2;

    ///////////////////////////////////////////////////////////////////

    private boolean hasAddedOwnDevices;
    private boolean isNeighborUpdateScheduled;

    ///////////////////////////////////////////////////////////////////

    private final Terminal terminal = new Terminal();
    private final BlockEntityDeviceBusElement busElement = new ComputerBusElement();
    private final ComputerContainerHelpers deviceItems = new ComputerContainerHelpers();
    private final FixedEnergyStorage energy = new FixedEnergyStorage(Config.computerEnergyStorage);
    private final ComputerVirtualMachine virtualMachine = new ComputerVirtualMachine(new BlockEntityDeviceBusController(busElement, Config.computerEnergyPerTick, this), deviceItems::getDeviceAddressBase);
    private final Set<Player> terminalUsers = Collections.newSetFromMap(new WeakHashMap<>());

    ///////////////////////////////////////////////////////////////////

    public ComputerBlockEntity() {
        super(TileEntities.COMPUTER_TILE_ENTITY.get());

        // We want to unload devices even on world unload to free global resources.
        setNeedsWorldUnloadEvent();
    }

    public Terminal getTerminal() {
        return terminal;
    }

    public VirtualMachine getVirtualMachine() {
        return virtualMachine;
    }

    public VMContainerHelpers getContainerHelpers() {
        return deviceItems;
    }

    public void start() {
        if (level == null || level.isClientSide) {
            return;
        }

        virtualMachine.start();
    }

    public void stop() {
        if (level == null || level.isClientSide) {
            return;
        }

        virtualMachine.stop();
    }

    public void openTerminalScreen(final ServerPlayer player) {
        NetworkHooks.openGui(player, new INamedContainerProvider() {
            @Override
            public Component getDisplayName() {
                return new TranslatableComponent(getBlockState().getBlock().getDescriptionId());
            }

            @Override
            public Container createMenu(final int id, final Inventory inventory, final Player player) {
                return new ComputerTerminalContainer(id, player, ComputerBlockEntity.this, new IIntArray() {
                    @Override
                    public int get(final int index) {
                        switch (index) {
                            case 0:
                                return energy.getEnergyStored();
                            case 1:
                                return energy.getMaxEnergyStored();
                            case 2:
                                return virtualMachine.busController.getEnergyConsumption();
                            default:
                                return 0;
                        }
                    }

                    @Override
                    public void set(final int index, final int value) {
                    }

                    @Override
                    public int getCount() {
                        return 3;
                    }
                });
            }
        }, getBlockPos());
    }

    public void openContainerScreen(final ServerPlayer player) {
        NetworkHooks.openGui(player, new INamedContainerProvider() {
            @Override
            public Component getDisplayName() {
                return new TranslatableComponent(getBlockState().getBlock().getDescriptionId());
            }

            @Override
            public Container createMenu(final int id, final Inventory inventory, final Player player) {
                return new ComputerInventoryContainer(id, ComputerBlockEntity.this, inventory);
            }
        }, getBlockPos());
    }

    public void addTerminalUser(final Player player) {
        terminalUsers.add(player);
    }

    public void removeTerminalUser(final Player player) {
        terminalUsers.remove(player);
    }

    @Override
    public Iterable<Player> getTerminalUsers() {
        return terminalUsers;
    }

    public void handleNeighborChanged() {
        virtualMachine.busController.scheduleBusScan();
    }

    @Override
    public <T> Optional<T> getCapability(final Capability<T> capability, @Nullable final Direction side) {
        if (isRemoved()) {
            return Optional.empty();
        }

        final Optional<T> optional = super.getCapability(capability, side);
        if (optional.isPresent()) {
            return optional;
        }

        final Direction localSide = HorizontalDirectionalBlockUtils.toLocal(getBlockState(), side);
        for (final Device device : virtualMachine.busController.getDevices()) {
            if (device instanceof ICapabilityProvider) {
                final Optional<T> value = ((ICapabilityProvider) device).getCapability(capability, localSide);
                if (value.isPresent()) {
                    return value;
                }
            }
        }

        return Optional.empty();
    }

    @Override
    public void tick() {
        if (level == null || level.isClientSide) {
            return;
        }

        // Always add devices provided for the computer itself, even if there's no
        // adjacent cable. Because that would just be weird.
        if (!hasAddedOwnDevices) {
            hasAddedOwnDevices = true;
            final BlockDeviceQuery query = Devices.makeQuery(this, (Direction) null);
            for (final Optional<BlockDeviceInfo> optional : Devices.getDevices(query)) {
                optional.ifPresent(info -> busElement.addDevice(info.device));
            }
        }

        if (isNeighborUpdateScheduled) {
            isNeighborUpdateScheduled = false;
            level.updateNeighborsAt(getBlockPos(), getBlockState().getBlock());
        }

        virtualMachine.tick();
    }

    @Override
    public void setRemoved() {
        super.setRemoved();

        // super.remove() calls onUnload. This in turn only suspends, but we want to do
        // a full clean-up when we get destroyed, so stuff inside us can delete out-of-nbt
        // persisted runtime-only data such as ram.
        virtualMachine.state.vmAdapter.unload();
    }

    @Override
    public CompoundTag getUpdateTag() {
        final CompoundTag tag = super.getUpdateTag();

        tag.put(TERMINAL_TAG_NAME, TagSerialization.serialize(terminal));
        tag.putInt(AbstractVirtualMachine.BUS_STATE_TAG_NAME, virtualMachine.getBusState().ordinal());
        tag.putInt(AbstractVirtualMachine.RUN_STATE_TAG_NAME, virtualMachine.getRunState().ordinal());
        tag.putString(AbstractVirtualMachine.BOOT_ERROR_TAG_NAME, Component.Serializer.toJson(virtualMachine.getBootError()));

        return tag;
    }

    @Override
    public void handleUpdateTag(final BlockState blockState, final CompoundTag tag) {
        super.handleUpdateTag(blockState, tag);

        TagSerialization.deserialize(tag.getCompound(TERMINAL_TAG_NAME), terminal);
        virtualMachine.setBusStateClient(CommonDeviceBusController.BusState.values()[tag.getInt(AbstractVirtualMachine.BUS_STATE_TAG_NAME)]);
        virtualMachine.setRunStateClient(VMRunState.values()[tag.getInt(AbstractVirtualMachine.RUN_STATE_TAG_NAME)]);
        virtualMachine.setBootErrorClient(Component.Serializer.fromJson(tag.getString(AbstractVirtualMachine.BOOT_ERROR_TAG_NAME)));
    }

    @Override
    public CompoundTag save(final CompoundTag tag) {
        super.save(tag);

        tag.put(STATE_TAG_NAME, virtualMachine.serialize());
        tag.put(TERMINAL_TAG_NAME, TagSerialization.serialize(terminal));
        tag.put(BUS_ELEMENT_TAG_NAME, TagSerialization.serialize(busElement));
        tag.put(Constants.ITEMS_TAG_NAME, deviceItems.serialize());

        return tag;
    }

    @Override
    public void load(final BlockState blockState, final CompoundTag tag) {
        super.load(blockState, tag);

        virtualMachine.deserialize(tag.getCompound(STATE_TAG_NAME));
        TagSerialization.deserialize(tag.getCompound(TERMINAL_TAG_NAME), terminal);

        if (tag.contains(BUS_ELEMENT_TAG_NAME, NBTTagIds.TAG_COMPOUND)) {
            TagSerialization.deserialize(tag.getCompound(BUS_ELEMENT_TAG_NAME), busElement);
        }

        deviceItems.deserialize(tag.getCompound(Constants.ITEMS_TAG_NAME));
    }

    public void exportToItemStack(final ItemStack stack) {
        deviceItems.serialize(NBTUtils.getOrCreateChildTag(stack.getOrCreateTag(), BLOCK_ENTITY_TAG_NAME_IN_ITEM, ITEMS_TAG_NAME));
    }

    ///////////////////////////////////////////////////////////////////

    @Override
    protected void collectCapabilities(final CapabilityCollector collector, @Nullable final Direction direction) {
        collector.offer(Capabilities.ITEM_HANDLER, deviceItems.combinedItemHandlers);
        collector.offer(Capabilities.DEVICE_BUS_ELEMENT, busElement);
        collector.offer(Capabilities.TERMINAL_USER_PROVIDER, this);

        if (Config.computersUseEnergy()) {
            collector.offer(Capabilities.ENERGY_STORAGE, energy);
        }
    }

    @Override
    protected void loadClient() {
        super.loadClient();

        terminal.setDisplayOnly(true);
    }

    @Override
    protected void loadServer() {
        super.loadServer();

        busElement.initialize();
        virtualMachine.state.builtinDevices.rtcMinecraft.setWorld(level);
    }

    @Override
    protected void unloadServer() {
        super.unloadServer();

        virtualMachine.unload();

        // This is necessary in case some other controller found us before our controller
        // did its scan, which can happen because the scan can happen with a delay. In
        // that case we don't know that controller and disposing our controller won't
        // notify it, so we also send out a notification through our bus element, which
        // would be registered with other controllers in that case.
        busElement.scheduleScan();
    }

    ///////////////////////////////////////////////////////////////////

    private final class ComputerContainerHelpers extends AbstractVMContainerHelpers {
        public ComputerContainerHelpers() {
            super(
                    GroupDefinition.of(DeviceTypes.MEMORY, MEMORY_SLOTS),
                    GroupDefinition.of(DeviceTypes.HARD_DRIVE, HARD_DRIVE_SLOTS),
                    GroupDefinition.of(DeviceTypes.FLASH_MEMORY, FLASH_MEMORY_SLOTS),
                    GroupDefinition.of(DeviceTypes.CARD, CARD_SLOTS)
            );
        }

        @Override
        protected ItemDeviceQuery getDeviceQuery(final ItemStack stack) {
            return Devices.makeQuery(ComputerBlockEntity.this, stack);
        }

        @Override
        protected void onContentsChanged(final DeviceContainerHelper itemStackHandler, final int slot) {
            super.onContentsChanged(itemStackHandler, slot);
            setChanged();
            isNeighborUpdateScheduled = true;
        }
    }

    private final class ComputerBusElement extends BlockEntityDeviceBusElement {
        public ComputerBusElement() {
            super(ComputerBlockEntity.this);
        }

        @Override
        public Optional<Collection<Optional<DeviceBusElement>>> getNeighbors() {
            return super.getNeighbors().map(neighbors -> {
                final ArrayList<Optional<DeviceBusElement>> list = new ArrayList<>(neighbors);
                list.add(Optional.of(deviceItems.busElement));
                return list;
            });
        }

        @Override
        public boolean canScanContinueTowards(@Nullable final Direction direction) {
            return getBlockState().getValue(ComputerBlock.FACING) != direction;
        }
    }

    private final class ComputerVMRunner extends AbstractTerminalVMRunner {
        public ComputerVMRunner(final AbstractVirtualMachine virtualMachine, final Terminal terminal) {
            super(virtualMachine, terminal);
        }

        @Override
        protected void sendTerminalUpdateToClient(final ByteBuffer output) {
            Network.sendToClientsTrackingChunk(new ComputerTerminalOutputMessage(ComputerBlockEntity.this, output), virtualMachine.chunk);
        }
    }

    private final class ComputerVirtualMachine extends AbstractVirtualMachine {
        private LevelChunk chunk;

        private ComputerVirtualMachine(final CommonDeviceBusController busController, final BaseAddressProvider baseAddressProvider) {
            super(busController);
            state.vmAdapter.setBaseAddressProvider(baseAddressProvider);
        }

        @Override
        public void setRunStateClient(final VMRunState value) {
            super.setRunStateClient(value);

            if (value == VMRunState.RUNNING) {
                if (!LoopingSoundManager.isPlaying(ComputerBlockEntity.this)) {
                    LoopingSoundManager.play(ComputerBlockEntity.this, SoundEvents.COMPUTER_RUNNING, level.getRandom().nextInt(MAX_RUNNING_SOUND_DELAY));
                }
            } else {
                LoopingSoundManager.stop(ComputerBlockEntity.this);
            }
        }

        @Override
        public void tick() {
            if (chunk == null) {
                chunk = level.getChunkAt(getBlockPos());
            }

            if (isRunning()) {
                chunk.markUnsaved();
            }

            super.tick();
        }

        @Override
        protected boolean consumeEnergy(final int amount, final boolean simulate) {
            if (!Config.computersUseEnergy()) {
                return true;
            }

            if (amount > energy.getEnergyStored()) {
                return false;
            }

            energy.extractEnergy(amount, simulate);
            return true;
        }

        @Override
        public void stopRunnerAndReset() {
            super.stopRunnerAndReset();

            TerminalUtils.resetTerminal(terminal, output -> Network.sendToClientsTrackingChunk(
                    new ComputerTerminalOutputMessage(ComputerBlockEntity.this, output), virtualMachine.chunk));
        }

        @Override
        protected AbstractTerminalVMRunner createRunner() {
            return new ComputerVMRunner(this, terminal);
        }

        @Override
        protected void handleBusStateChanged(final CommonDeviceBusController.BusState value) {
            Network.sendToClientsTrackingChunk(new ComputerBusStateMessage(ComputerBlockEntity.this), chunk);

            if (value == CommonDeviceBusController.BusState.READY) {
                // Bus just became ready, meaning new devices may be available, meaning new
                // capabilities may be available, so we need to tell our neighbors.
                level.updateNeighborsAt(getBlockPos(), getBlockState().getBlock());
            }
        }

        @Override
        protected void handleRunStateChanged(final VMRunState value) {
            // This method can be called from disposal logic, so if we are disposed quickly enough
            // chunk may not be initialized yet. Avoid resulting NRE in network logic.
            if (chunk != null) {
                Network.sendToClientsTrackingChunk(new ComputerRunStateMessage(ComputerBlockEntity.this), chunk);
            }
        }

        @Override
        protected void handleBootErrorChanged(@Nullable final Component value) {
            Network.sendToClientsTrackingChunk(new ComputerBootErrorMessage(ComputerBlockEntity.this), chunk);
        }
    }
}
