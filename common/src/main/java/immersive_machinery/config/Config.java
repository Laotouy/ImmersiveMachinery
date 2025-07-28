package immersive_machinery.config;

import immersive_aircraft.config.JsonConfig;
import immersive_aircraft.config.configEntries.BooleanConfigEntry;
import immersive_aircraft.config.configEntries.IntegerConfigEntry;
import immersive_machinery.Common;

import java.util.HashMap;
import java.util.Map;

public final class Config extends JsonConfig {
    private static final Config INSTANCE = loadOrCreate(new Config(Common.MOD_ID), Config.class);

    public Config(String name) {
        super(name);
    }

    public static Config getInstance() {
        return INSTANCE;
    }

    @BooleanConfigEntry(true)
    public boolean waterRenderingFixForCopperfin;

    @IntegerConfigEntry(5)
    public int redstoneSheepMinHorizontalScanRange;

    @IntegerConfigEntry(20)
    public int fuelTicksPerHarvest;

    public Map<String, Boolean> validCrops = new HashMap<String, Boolean>() {{
        put("minecraft:grass", true);
        // 农夫乐事 (Farmer's Delight) 作物
        put("farmersdelight:cabbage_crop", true);
        put("farmersdelight:tomato_crop", true);
        put("farmersdelight:onion_crop", true);
        put("farmersdelight:rice_crop", true);
        put("farmersdelight:rice_crop_water", true);
        // 沉浸农艺 (Immersive Agriculture) 作物
        put("immersive_agriculture:wheat_crop", true);
        put("immersive_agriculture:carrot_crop", true);
        put("immersive_agriculture:potato_crop", true);
        put("immersive_agriculture:beetroot_crop", true);
        // 作物盛景 (Croparia) 作物
        put("croparia:crop_elem_0", true);
        put("croparia:crop_elem_1", true);
        put("croparia:crop_elem_2", true);
        put("croparia:crop_elem_3", true);
        put("croparia:crop_elem_4", true);
        // 其他常见模组作物
        put("pamhc2crops:tomato_crop", true);
        put("pamhc2crops:lettuce_crop", true);
        put("pamhc2crops:corn_crop", true);
        put("mysticalworld:aubergine_crop", true);
        put("mysticalworld:pearl_bean_crop", true);
        put("supplementaries:flax_crop", true);
        put("croptopia:artichoke_crop", true);
        put("croptopia:asparagus_crop", true);
        put("croptopia:barley_crop", true);
        put("croptopia:broccoli_crop", true);
        put("croptopia:cabbage_crop", true);
        put("croptopia:corn_crop", true);
        put("croptopia:cucumber_crop", true);
        put("croptopia:eggplant_crop", true);
        put("croptopia:greenbean_crop", true);
        put("croptopia:kale_crop", true);
        put("croptopia:leek_crop", true);
        put("croptopia:lettuce_crop", true);
        put("croptopia:oat_crop", true);
        put("croptopia:onion_crop", true);
        put("croptopia:pepper_crop", true);
        put("croptopia:radish_crop", true);
        put("croptopia:rice_crop", true);
        put("croptopia:spinach_crop", true);
        put("croptopia:squash_crop", true);
        put("croptopia:sweetpotato_crop", true);
        put("croptopia:tomato_crop", true);
        put("croptopia:turnip_crop", true);
        put("croptopia:zucchini_crop", true);
    }};

    // 支持的成熟度属性名称列表
    public String[] supportedMaturityProperties = {
            "age",           // 默认原版属性
            "maturity",      // 一些模组使用的属性
            "growth",        // 另一种常见属性
            "stage",         // 阶段属性
            "progress",      // 进度属性
            "level"          // 等级属性
    };

    // 是否启用模组作物的自动检测
    @BooleanConfigEntry(true)
    public boolean enableModdedCropAutoDetection;

    // 是否启用通过类名检测作物的功能
    @BooleanConfigEntry(true)
    public boolean enableCropClassDetection;
}
