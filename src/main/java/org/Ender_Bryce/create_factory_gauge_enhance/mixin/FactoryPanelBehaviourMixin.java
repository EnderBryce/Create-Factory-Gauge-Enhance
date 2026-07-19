package org.Ender_Bryce.create_factory_gauge_enhance.mixin;

import com.google.common.collect.Multimap;
import com.simibubi.create.Create;
import com.simibubi.create.content.logistics.BigItemStack;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBehaviour;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlockEntity;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelConnection;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelPosition;
import com.simibubi.create.content.logistics.packager.InventorySummary;
import com.simibubi.create.content.logistics.packager.PackagerBlockEntity;
import com.simibubi.create.content.logistics.packager.PackagingRequest;
import com.simibubi.create.content.logistics.packagerLink.LogisticallyLinkedBehaviour;
import com.simibubi.create.content.logistics.packagerLink.LogisticsManager;
import com.simibubi.create.content.logistics.packagerLink.RequestPromise;
import com.simibubi.create.content.logistics.packagerLink.RequestPromiseQueue;
import com.simibubi.create.content.logistics.stockTicker.PackageOrder;
import com.simibubi.create.content.logistics.stockTicker.PackageOrderWithCrafts;
import com.simibubi.create.content.logistics.stockTicker.PackageOrderWithCrafts.CraftingEntry;
import com.simibubi.create.foundation.advancement.AllAdvancements;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.filtering.FilteringBehaviour;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.*;

@Mixin(value = FactoryPanelBehaviour.class, remap = false)
public abstract class FactoryPanelBehaviourMixin {

    @Shadow
    public Map<FactoryPanelPosition, FactoryPanelConnection> targetedBy;

    @Shadow
    public boolean satisfied;

    @Shadow
    public boolean promisedSatisfied;

    @Shadow
    public boolean waitingForNetwork;

    @Shadow
    public boolean redstonePowered;

    @Shadow
    public int recipeOutput;

    @Shadow
    public String recipeAddress;

    @Shadow
    public List<ItemStack> activeCraftingArrangement;

    @Shadow
    public UUID network;

    @Shadow
    private int timer;

    @Shadow
    public abstract boolean isActive();

    @Shadow
    public abstract int getLevelInStorage();

    @Shadow
    public abstract int getPromised();

    @Shadow
    public abstract FactoryPanelBlockEntity panelBE();

    @Shadow
    public native void resetTimer();

    @Shadow
    private native void sendEffect(FactoryPanelPosition fromPos, boolean success);

    @Shadow
    private native int getConfigRequestIntervalInTicks();

    @Unique
    private FilteringBehaviour create_factory_gauge_enhance$asFiltering() {
        return (FilteringBehaviour) (Object) this;
    }

    @Unique
    private ItemStack create_factory_gauge_enhance$getFilter() {
        return create_factory_gauge_enhance$asFiltering().getFilter();
    }

    @Unique
    private int create_factory_gauge_enhance$getAmount() {
        return create_factory_gauge_enhance$asFiltering().getAmount();
    }

    @Unique
    private boolean create_factory_gauge_enhance$isUpTo() {
        return create_factory_gauge_enhance$asFiltering().upTo;
    }

    @Unique
    private Level create_factory_gauge_enhance$getLevel() {
        return ((BlockEntityBehaviour) (Object) this).blockEntity.getLevel();
    }

    @Inject(method = "tickRequests", at = @At("HEAD"), cancellable = true)
    private void onTickRequests(CallbackInfo ci) {
        // 只有动力合成模式才启用
        if (activeCraftingArrangement.isEmpty()) {
            return;
        }

        var panelBE = panelBE();
        if (targetedBy.isEmpty() && !panelBE.restocker) {
            return;
        }
        if (panelBE.restocker) {
            return;
        }

        if (satisfied || promisedSatisfied || waitingForNetwork || redstonePowered) {
            ci.cancel();
            return;
        }

        if (timer > 0) {
            timer = Math.min(timer, getConfigRequestIntervalInTicks());
            --timer;
            ci.cancel();
            return;
        }
        resetTimer();

        if (recipeAddress.isBlank()) {
            ci.cancel();
            return;
        }

        ItemStack filterItem = create_factory_gauge_enhance$getFilter();
        if (filterItem.isEmpty() || recipeOutput <= 0) {
            ci.cancel();
            return;
        }

        int demand = create_factory_gauge_enhance$getAmount() * (create_factory_gauge_enhance$isUpTo() ? 1 : filterItem.getMaxStackSize());
        int inStorage = getLevelInStorage();
        int promised = getPromised();
        int gap = demand - inStorage - promised;

        if (gap <= 0) {
            ci.cancel();
            return;
        }

        int batchesNeeded = (gap + recipeOutput - 1) / recipeOutput;

        Map<UUID, Map<ItemStack, List<FactoryPanelConnection>>> connectionsByNetworkAndItem = new HashMap<>();
        Level level = create_factory_gauge_enhance$getLevel();
        for (FactoryPanelConnection connection : targetedBy.values()) {
            FactoryPanelBehaviour source = FactoryPanelBehaviour.at(level, connection.from);
            if (source == null) {
                ci.cancel();
                return;
            }
            ItemStack item = source.getFilter();
            if (item.isEmpty()) {
                ci.cancel();
                return;
            }

            Map<ItemStack, List<FactoryPanelConnection>> itemMap = connectionsByNetworkAndItem.computeIfAbsent(source.network, k -> new HashMap<>());
            List<FactoryPanelConnection> list = itemMap.computeIfAbsent(item, k -> new ArrayList<>());
            list.add(connection);
        }

        // 计算每种原料的单批总需求
        Map<UUID, Map<ItemStack, Integer>> totalPerBatchByNetwork = new HashMap<>();
        for (Map.Entry<UUID, Map<ItemStack, List<FactoryPanelConnection>>> entry : connectionsByNetworkAndItem.entrySet()) {
            UUID net = entry.getKey();
            Map<ItemStack, Integer> perBatchMap = new HashMap<>();
            for (Map.Entry<ItemStack, List<FactoryPanelConnection>> itemEntry : entry.getValue().entrySet()) {
                int totalAmount = 0;
                for (FactoryPanelConnection conn : itemEntry.getValue()) {
                    totalAmount += conn.amount;
                }
                perBatchMap.put(itemEntry.getKey(), totalAmount);
            }
            totalPerBatchByNetwork.put(net, perBatchMap);
        }

        // 反推实际可执行的批次（取所有原料库存的最小值）
        int maxBatchesPerRequest = 64;
        int actualBatches = Math.min(batchesNeeded, maxBatchesPerRequest);
        for (Map.Entry<UUID, Map<ItemStack, Integer>> entry : totalPerBatchByNetwork.entrySet()) {
            UUID net = entry.getKey();
            InventorySummary summary = LogisticsManager.getSummaryOfNetwork(net, true);
            for (Map.Entry<ItemStack, Integer> itemEntry : entry.getValue().entrySet()) {
                int perBatchNeed = itemEntry.getValue();
                if (perBatchNeed <= 0 || itemEntry.getKey().isEmpty()) {
                    actualBatches = 0;
                    continue;
                }
                int available = summary.getCountOf(itemEntry.getKey());
                int batchesThisItemCanSupport = available / perBatchNeed;
                actualBatches = Math.min(actualBatches, batchesThisItemCanSupport);
            }
        }

        if (actualBatches <= 0) {
            // 发送失败
            for (FactoryPanelConnection conn : targetedBy.values()) {
                sendEffect(conn.from, false);
            }
            ci.cancel();
            return;
        }

        // 构造实际请求（包含 actualBatches 次合成）
        Map<UUID, List<BigItemStack>> toRequest = new HashMap<>();
        for (Map.Entry<UUID, Map<ItemStack, Integer>> entry : totalPerBatchByNetwork.entrySet()) {
            UUID net = entry.getKey();
            List<BigItemStack> list = new ArrayList<>();
            for (Map.Entry<ItemStack, Integer> itemEntry : entry.getValue().entrySet()) {
                int total = itemEntry.getValue() * actualBatches;
                list.add(new BigItemStack(itemEntry.getKey(), total));
                // 发送成功
                for (FactoryPanelConnection conn : connectionsByNetworkAndItem.get(net).get(itemEntry.getKey())) {
                    sendEffect(conn.from, true);
                }
            }
            toRequest.put(net, list);
        }

        // 构建合成上下文
        PackageOrderWithCrafts craftContext = PackageOrderWithCrafts.empty();
        if (!activeCraftingArrangement.isEmpty()) {
            List<BigItemStack> singleRecipeIngredients = new ArrayList<>();
            for (ItemStack stack : activeCraftingArrangement) {
                singleRecipeIngredients.add(new BigItemStack(stack.copyWithCount(1), 1));
            }
            craftContext = new PackageOrderWithCrafts(
                    PackageOrder.empty(),
                    List.of(new CraftingEntry(
                            new PackageOrder(singleRecipeIngredients),
                            actualBatches
                    ))
            );
        }

        // 查找可用的打包机
        List<Multimap<PackagerBlockEntity, PackagingRequest>> requests = new ArrayList<>();
        for (Map.Entry<UUID, List<BigItemStack>> entry : toRequest.entrySet()) {
            PackageOrderWithCrafts order = new PackageOrderWithCrafts(
                    new PackageOrder(entry.getValue()),
                    craftContext.orderedCrafts()
            );
            Multimap<PackagerBlockEntity, PackagingRequest> found =
                    LogisticsManager.findPackagersForRequest(entry.getKey(), order, null, recipeAddress);
            if (found.isEmpty()) {
                // 没有可用的打包机
                for (FactoryPanelConnection conn : targetedBy.values()) {
                    sendEffect(conn.from, false);
                }
                ci.cancel();
                return;
            }
            requests.add(found);
        }

        // 检查打包机是否空闲
        for (Multimap<PackagerBlockEntity, PackagingRequest> multimap : requests) {
            for (PackagerBlockEntity packager : multimap.keySet()) {
                if (packager.isTooBusyFor(LogisticallyLinkedBehaviour.RequestType.RESTOCK)) {
                    ci.cancel();
                    return;
                }
            }
        }

        // 发送请求
        for (Multimap<PackagerBlockEntity, PackagingRequest> multimap : requests) {
            LogisticsManager.performPackageRequests(multimap);
        }

        // 添加承诺
        RequestPromiseQueue promises = Create.LOGISTICS.getQueuedPromises(network);
        if (promises != null) {
            promises.add(new RequestPromise(new BigItemStack(filterItem, actualBatches * recipeOutput)));
        }

        panelBE.advancements.awardPlayer(AllAdvancements.FACTORY_GAUGE);
        ci.cancel();
    }
}