package immersive_machinery.entity;

import immersive_machinery.Items;
import immersive_machinery.Sounds;
import immersive_machinery.Utils;
import immersive_machinery.config.Config;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import org.jetbrains.annotations.NotNull;

import java.util.*;

public class RedstoneSheep extends NavigatingMachine {
    private static final int INVENTORY_BUFFER_SPACE = 3;
    private static final int RESCAN_INTERVAL = 100;

    private BlockPos home;
    private BlockPos task;
    private int reloadingTicks;
    private int rescanningTicks;
    private int tasksHarvested;

    // The set of blocks which require ac
    private Set<BlockPos> workingSet = new HashSet<>();
    private Set<BlockPos> backlogSet = new HashSet<>();

    public RedstoneSheep(EntityType<? extends MachineEntity> entityType, Level world) {
        super(entityType, world, false, false, 0);

        setMaxUpStep(1.1f);
    }

    @Override
    public boolean isNoGravity() {
        return false;
    }

    @Override
    protected SoundEvent getEngineSound() {
        return Sounds.REDSTONE_SHEEP.get();
    }

    @Override
    protected float getEnginePitch() {
        float speed = (float) getSpeedVector().length();
        return Math.min(1.0f, 0.75f + speed * 10.0f);
    }

    @Override
    protected float getEngineReactionSpeed() {
        return 10f;
    }

    @Override
    public void tick() {
        super.tick();

        // Update home
        if (home == null || home.getX() == 0 && home.getY() == 0 && home.getZ() == 0) {
            home = this.blockPosition();
        }

        // Rotate to target
        double dx = getX() - lastX;
        double dz = getZ() - lastZ;
        if (dx * dx + dz * dz > 0.00001) {
            setYRot(Utils.lerpAngle(getYRot(), (float) Math.toDegrees(Math.atan2(dz, dx)) + 90.0f, 10.0f));
        } else {
            setYRot(Utils.lerpAngle(getYRot(), (float) (Math.floor(getYRot() / 90.0f + 0.5f) * 90.0f), 5.0f));
        }

        if (level().isClientSide) {
            return;
        }

        // Time to return home
        if (isFuelLow() || isInventoryFull()) {
            reloadingTicks = 60;
        }

        rescanningTicks--;

        setEngineTarget(task != null ? 1.0f : 0.0f);

        // The sheep's state machine (loading/unloading -> working -> rescanning)
        if (reloadingTicks > 0) {
            // Head home, wait until loading and unloading is done
            if (moveTo(home) || !navigator.hasPath()) {
                reloadingTicks--;
            }
        } else if (task != null) {
            // Head to target and do work
            if (moveTo(task)) {
                VerifyState state = verify(task);
                if (state == VerifyState.VALID) {
                    work(task);
                }
                task = null;
            } else if (!navigator.hasPath()) {
                // Target is unreachable, remove from list
                backlogSet.remove(task);
                task = null;
            }
        } else if (!workingSet.isEmpty()) {
            // Pick the closest task, verify, set as task, and move to backlog
            if ((level().getGameTime() + getId()) % 5 == 0) {
                //noinspection OptionalGetWithoutIsPresent
                BlockPos closest = workingSet.stream().min(Comparator.comparingDouble(a -> a.distToCenterSqr(getX(), getY(), getZ()))).get();
                VerifyState state = verify(closest);
                if (state == VerifyState.VALID) {
                    task = closest;
                    backlogSet.add(closest);
                    tasksHarvested++;
                    rescanningTicks = RESCAN_INTERVAL;
                } else if (state == VerifyState.NOT_MATURE) {
                    backlogSet.add(closest);
                }
                workingSet.remove(closest);
            }
        } else if (!backlogSet.isEmpty()) {
            // Switch sets
            workingSet = backlogSet;
            backlogSet = new HashSet<>();

            // If last run was unproductive, rescan
            if (tasksHarvested == 0) {
                if (rescanningTicks <= 0) {
                    // If no players are nearby, why would the world change noticeable?
                    if (playerIsClose()) {
                        rescan();
                    }
                    rescanningTicks = RESCAN_INTERVAL;
                }

                // Return home and wait
                reloadingTicks = 60;
            }
            tasksHarvested = 0;
        } else if (rescanningTicks <= 0) {
            // Never found any tasks, rescan
            rescan();
            rescanningTicks = RESCAN_INTERVAL;
        }
    }

    @Override
    public boolean isVehicle() {
        return true;
    }

    private void rescan() {
        workingSet.clear();
        backlogSet.clear();

        int range = Config.getInstance().redstoneSheepMinHorizontalScanRange;

        // Breadth-first search with up to "range" of skip range
        LongOpenHashSet visited = new LongOpenHashSet();
        Queue<BlockPos> queue = new LinkedList<>();
        queue.add(home);
        visited.add(home.asLong());

        while (!queue.isEmpty()) {
            BlockPos origin = queue.poll();
            for (int x = -range; x <= range; x++) {
                for (int z = -range; z <= range; z++) {
                    BlockPos pos = new BlockPos(x + origin.getX(), origin.getY(), z + origin.getZ());
                    if (visited.contains(pos.asLong())) {
                        continue;
                    }
                    visited.add(pos.asLong());
                    if (verify(pos) != VerifyState.INVALID) {
                        workingSet.add(pos);
                        queue.add(pos);
                    }
                }
            }
        }
    }

    private boolean playerIsClose() {
        return level().getNearestPlayer(this, 32.0) != null;
    }

    private void work(BlockPos pos) {
        BlockState state = level().getBlockState(pos);
        if (level() instanceof ServerLevel serverLevel) {
            Block block = state.getBlock();
            String blockKey = BuiltInRegistries.BLOCK.getKey(block).toString();
            
            // 收集掉落物
            Block.getDrops(state, serverLevel, pos, null).forEach(stack -> {
                ItemStack remainder = addItem(stack);
                if (!remainder.isEmpty()) {
                    Block.popResource(serverLevel, pos, remainder);
                }
            });

            // 收获或重置成长阶段
            boolean harvestSuccessful = false;
            
            // 尝试重置成长阶段而不是破坏方块（对于可重复收获的作物）
            Optional<Property<Integer>> ageProperty = getAgeProperty(state);
            if (ageProperty.isPresent()) {
                Property<Integer> property = ageProperty.get();
                
                // 对于某些模组作物，可能需要特殊的收获处理
                if (isReharvestable(blockKey)) {
                    // 重复收获的作物，重置到某个阶段而不是0
                    int resetAge = getResetAge(blockKey, property);
                    serverLevel.setBlockAndUpdate(pos, state.setValue(property, resetAge));
                    harvestSuccessful = true;
                } else {
                    // 一次性收获的作物，重置到0
                    serverLevel.setBlockAndUpdate(pos, state.setValue(property, 0));
                    harvestSuccessful = true;
                }
            }
            
            // 如果没有成长属性或特殊处理失败，直接破坏方块
            if (!harvestSuccessful) {
                if (shouldDestroyBlock(blockKey)) {
                    serverLevel.destroyBlock(pos, false);
                } else {
                    // 对于一些永久性作物（如浆果丛），什么都不做
                }
            }

            // 消耗燃料
            consumeFuel(Config.getInstance().fuelTicksPerHarvest);

            // 生成粒子效果
            serverLevel.sendParticles(new BlockParticleOption(ParticleTypes.FALLING_DUST, state), 
                pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 10, 0.5, 0.5, 0.5, 1.0);

            // 播放声音
            SoundEvent harvestSound = getHarvestSound(blockKey);
            serverLevel.playSound(null, pos, harvestSound, SoundSource.BLOCKS, 1.0f, 1.2f);
        }
    }
    
    /**
     * 检查作物是否可以重复收获（如浆果丛、竹子等）
     */
    private static boolean isReharvestable(String blockKey) {
        return blockKey.contains("berry") || blockKey.contains("bush") || 
               blockKey.contains("vine") || blockKey.contains("bamboo") ||
               blockKey.startsWith("farmersdelight:rice") ||
               blockKey.contains("sweet_berry") || blockKey.contains("glow_berry");
    }
    
    /**
     * 获取作物重置后的年龄
     */
    private static int getResetAge(String blockKey, Property<Integer> property) {
        // 对于大多数可重复收获的作物，重置到某个中间阶段
        if (blockKey.contains("berry") || blockKey.contains("bush")) {
            // 浆果丛类型，通常重置到阶段1或2
            return Math.max(1, Collections.min(property.getPossibleValues()));
        }
        
        // 默认重置到最小值
        return Collections.min(property.getPossibleValues());
    }
    
    /**
     * 检查是否应该破坏方块
     */
    private static boolean shouldDestroyBlock(String blockKey) {
        // 对于永久性的作物，不要破坏方块
        if (blockKey.contains("berry") && blockKey.contains("bush")) {
            return false; // 浆果丛不破坏
        }
        
        if (blockKey.contains("vine") || blockKey.contains("bamboo")) {
            return false; // 藤蔓和竹子不破坏
        }
        
        // 默认情况下破坏方块
        return true;
    }
    
    /**
     * 获取收获时的声音效果
     */
    private static SoundEvent getHarvestSound(String blockKey) {
        if (blockKey.contains("berry") || blockKey.contains("fruit")) {
            return SoundEvents.SWEET_BERRY_BUSH_PICK_BERRIES;
        } else if (blockKey.contains("bamboo")) {
            return SoundEvents.BAMBOO_BREAK;
        } else if (blockKey.contains("vine")) {
            return SoundEvents.VINE_BREAK;
        } else {
            return SoundEvents.PLAYER_ATTACK_SWEEP;
        }
    }

    enum VerifyState {
        VALID,
        NOT_MATURE,
        INVALID
    }

    /**
     * @param pos BlockPos to verify
     * @return The crop state of the block
     */
    private VerifyState verify(BlockPos pos) {
        BlockState state = level().getBlockState(pos);
        Block block = state.getBlock();

        if (isCrop(block)) {
            if (isMature(state)) {
                return VerifyState.VALID;
            } else {
                return VerifyState.NOT_MATURE;
            }
        } else {
            return VerifyState.INVALID;
        }
    }

    /**
     * @param block Block to check
     * @return Whether the block is harvestable crop
     */
    public static boolean isCrop(Block block) {
        String key = BuiltInRegistries.BLOCK.getKey(block).toString();
        
        // 首先检查配置文件中明确指定的作物
        if (Config.getInstance().validCrops.containsKey(key)) {
            return Config.getInstance().validCrops.get(key);
        }
        
        // 如果启用了类检测，检查是否为原版作物类型
        if (Config.getInstance().enableCropClassDetection) {
            if (block instanceof CropBlock || block instanceof NetherWartBlock || 
                block instanceof CocoaBlock || block instanceof PitcherCropBlock) {
                return true;
            }
        }
        
        // 如果启用了模组作物自动检测，使用更智能的检测方法
        if (Config.getInstance().enableModdedCropAutoDetection) {
            String blockName = key.toLowerCase();
            String className = block.getClass().getSimpleName().toLowerCase();
            
            // 通过方块名称检测作物
            if (blockName.contains("crop") || blockName.contains("plant") || 
                blockName.contains("berry") || blockName.contains("bush") ||
                blockName.contains("vine") || blockName.contains("stem")) {
                return true;
            }
            
            // 通过类名检测作物
            if (className.contains("crop") || className.contains("plant") || 
                className.contains("berry") || className.contains("bush") ||
                className.contains("vine") || className.contains("stem")) {
                return true;
            }
            
            // 检查常见模组前缀
            String[] modPrefixes = {
                "farmersdelight:", "croptopia:", "pamhc2crops:", "mysticalworld:",
                "supplementaries:", "immersive_agriculture:", "croparia:",
                "mysticalagriculture:", "harvestcraft:", "actuallyadditions:",
                "botania:", "forestry:", "industrialcraft:", "thermalexpansion:"
            };
            
            for (String prefix : modPrefixes) {
                if (blockName.startsWith(prefix)) {
                    return true;
                }
            }
        }
        
        return false;
    }

    public static Optional<Property<Integer>> getAgeProperty(BlockState state) {
        // 尝试所有支持的成熟度属性名称
        for (String propertyName : Config.getInstance().supportedMaturityProperties) {
            for (Property<?> property : state.getProperties()) {
                if (property.getName().equals(propertyName)) {
                    try {
                        //noinspection unchecked
                        return Optional.of((Property<Integer>) property);
                    } catch (ClassCastException e) {
                        // 如果不是Integer类型，继续尝试下一个
                        continue;
                    }
                }
            }
        }
        
        // 如果没有找到标准属性名，尝试寻找任何包含相关关键词的Integer属性
        for (Property<?> property : state.getProperties()) {
            String propName = property.getName().toLowerCase();
            if ((propName.contains("age") || propName.contains("growth") || 
                 propName.contains("stage") || propName.contains("maturity") ||
                 propName.contains("progress") || propName.contains("level")) &&
                property.getValueClass() == Integer.class) {
                try {
                    //noinspection unchecked
                    return Optional.of((Property<Integer>) property);
                } catch (ClassCastException e) {
                    // 继续寻找
                }
            }
        }
        
        return Optional.empty();
    }

    public static boolean isMature(BlockState state) {
        Optional<Property<Integer>> ageProperty = getAgeProperty(state);
        
        if (ageProperty.isPresent()) {
            Property<Integer> property = ageProperty.get();
            Integer currentValue = state.getValue(property);
            Integer maxValue = Collections.max(property.getPossibleValues());
            
            // 检查当前值是否等于最大值
            return Objects.equals(currentValue, maxValue);
        }
        
        // 如果没有找到成熟度属性，尝试一些特殊的检测方法
        Block block = state.getBlock();
        String blockKey = BuiltInRegistries.BLOCK.getKey(block).toString();
        
        // 对于一些特殊的模组作物，可能需要特殊的成熟度检测
        if (blockKey.startsWith("farmersdelight:")) {
            // 农夫乐事的一些作物可能有特殊的成熟检测
            return checkFarmersDelightMaturity(state);
        } else if (blockKey.startsWith("mysticalagriculture:")) {
            // 神秘农艺的作物可能有特殊的成熟检测
            return checkMysticalAgricultureMaturity(state);
        }
        
        // 如果没有成熟度属性且不是特殊作物，假设它总是成熟的
        // 这对于一些简单的模组作物（如浆果丛）可能是合适的
        return true;
    }
    
    private static boolean checkFarmersDelightMaturity(BlockState state) {
        // 农夫乐事的特殊成熟度检测逻辑
        // 检查常见的农夫乐事属性
        for (Property<?> property : state.getProperties()) {
            String propName = property.getName();
            if (propName.equals("age") || propName.equals("maturity")) {
                if (property.getValueClass() == Integer.class) {
                    @SuppressWarnings("unchecked")
                    Property<Integer> intProperty = (Property<Integer>) property;
                    Integer currentValue = state.getValue(intProperty);
                    Integer maxValue = Collections.max(intProperty.getPossibleValues());
                    return Objects.equals(currentValue, maxValue);
                }
            }
        }
        return true; // 如果没有找到特定属性，假设成熟
    }
    
    private static boolean checkMysticalAgricultureMaturity(BlockState state) {
        // 神秘农艺的特殊成熟度检测逻辑
        for (Property<?> property : state.getProperties()) {
            String propName = property.getName();
            if (propName.equals("age") || propName.equals("growth") || propName.equals("stage")) {
                if (property.getValueClass() == Integer.class) {
                    @SuppressWarnings("unchecked")
                    Property<Integer> intProperty = (Property<Integer>) property;
                    Integer currentValue = state.getValue(intProperty);
                    Integer maxValue = Collections.max(intProperty.getPossibleValues());
                    return Objects.equals(currentValue, maxValue);
                }
            }
        }
        return true; // 如果没有找到特定属性，假设成熟
    }

    private boolean isInventoryFull() {
        return countItems() > getContainerSize() - INVENTORY_BUFFER_SPACE;
    }

    /**
     * @return Number of occupied slots in the inventory
     */
    private int countItems() {
        int i = 0;
        for (int j = 0; j < getContainerSize(); ++j) {
            ItemStack itemStack = getItem(j);
            if (!itemStack.isEmpty()) {
                i += 1;
            }
        }
        return i;
    }

    @Override
    public Item asItem() {
        return Items.REDSTONE_SHEEP.get();
    }

    @Override
    protected void addAdditionalSaveData(@NotNull CompoundTag tag) {
        super.addAdditionalSaveData(tag);

        tag.putInt("HomeX", this.home.getX());
        tag.putInt("HomeY", this.home.getY());
        tag.putInt("HomeZ", this.home.getZ());
    }

    @Override
    protected void readAdditionalSaveData(@NotNull CompoundTag tag) {
        super.readAdditionalSaveData(tag);

        this.home = new BlockPos(tag.getInt("HomeX"), tag.getInt("HomeY"), tag.getInt("HomeZ"));
    }

    @Override
    public void containerChanged(Container sender) {
        if (reloadingTicks > 0) {
            reloadingTicks = 60;
        }
    }

    @Override
    public float getFuelConsumption() {
        return 0.0f;
    }
}
