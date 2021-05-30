package li.cil.oc2.common.vm;

import li.cil.oc2.api.bus.device.DeviceType;
import net.minecraftforge.items.IItemHandler;

import java.util.Optional;

public interface VMContainerHelpers {
    Optional<IItemHandler> getItemHandler(DeviceType deviceType);

    boolean isEmpty();

    void exportDeviceDataToItemStacks();
}
