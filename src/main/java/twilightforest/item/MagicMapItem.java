package twilightforest.item;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.material.MaterialColor;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import org.jetbrains.annotations.Nullable;
import twilightforest.TFMagicMapData;
import twilightforest.init.BiomeKeys;
import twilightforest.init.TFItems;
import twilightforest.init.TFLandmark;
import twilightforest.util.LegacyLandmarkPlacements;
import twilightforest.world.registration.TFGenerationSettings;
import twilightforest.TFConfig;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.Consumer;

// [VanillaCopy] super everything, but with appropriate redirections to our own datastructures. finer details noted

public class MagicMapItem extends MapItem {

	public static final String STR_ID = "magicmap";
	private static final Map<ResourceLocation, MapColorBrightness> BIOME_COLORS = new HashMap<>();

	public MagicMapItem(Properties properties) {
		super(properties);
	}

	private static class MapColorBrightness {
		public final MaterialColor color;
		public final int brightness;

		public MapColorBrightness(MaterialColor color, int brightness) {
			this.color = color;
			this.brightness = brightness;
		}

		public MapColorBrightness(MaterialColor color) {
			this.color = color;
			this.brightness = 1;
		}
	}

	public static ItemStack setupNewMap(Level level, int worldX, int worldZ, byte scale, boolean trackingPosition, boolean unlimitedTracking) {
		ItemStack itemstack = new ItemStack(TFItems.FILLED_MAGIC_MAP.get());
		createMapData(itemstack, level, worldX, worldZ, scale, trackingPosition, unlimitedTracking, level.dimension());
		return itemstack;
	}

	@Nullable
	public static TFMagicMapData getData(ItemStack stack, Level level) {
		Integer id = getMapId(stack);
		return id == null ? null : TFMagicMapData.getMagicMapData(level, getMapName(id));
	}

	@Nullable
	@Override
	protected TFMagicMapData getCustomMapData(ItemStack stack, Level level) {
		TFMagicMapData mapdata = getData(stack, level);
		if (mapdata == null && !level.isClientSide()) {
			mapdata = MagicMapItem.createMapData(stack, level, level.getLevelData().getXSpawn(), level.getLevelData().getZSpawn(), 3, false, false, level.dimension());
		}

		return mapdata;
	}

	private static TFMagicMapData createMapData(ItemStack stack, Level level, int x, int z, int scale, boolean trackingPosition, boolean unlimitedTracking, ResourceKey<Level> dimension) {
		int i = level.getFreeMapId();

		// magic maps are aligned to the key biome grid so that 0,0 -> 2048,2048 is the covered area
		int mapSize = 2048;
		int roundX = (int) Math.round((double) (x - 1024) / mapSize);
		int roundZ = (int) Math.round((double) (z - 1024) / mapSize);
		int scaledX = roundX * mapSize + 1024;
		int scaledZ = roundZ * mapSize + 1024;

		TFMagicMapData mapdata = new TFMagicMapData(scaledX, scaledZ, (byte) scale, trackingPosition, unlimitedTracking, false, dimension);
		TFMagicMapData.registerMagicMapData(level, mapdata, getMapName(i)); // call our own register method
		stack.getOrCreateTag().putInt("map", i);
		return mapdata;
	}

	public static String getMapName(int id) {
		return STR_ID + "_" + id;
	}

	private static final Map<ChunkPos, ResourceLocation[]> CACHE = new HashMap<>();
	private static final ResourceLocation NULL_BIOME = new ResourceLocation("null");

	@Override
	public void update(Level level, Entity viewer, MapItemSavedData data) {
		if (level.dimension() == data.dimension && viewer instanceof Player && level instanceof ServerLevel serverLevel && TFGenerationSettings.usesTwilightChunkGenerator(serverLevel)) {
			// 配置开关：如禁用则跳过地标搜索
			if (!TFConfig.COMMON_CONFIG.enableMagicMapLandmarkSearch.get()) {
				return;
			}
			int biomesPerPixel = 4;
			int blocksPerPixel = 16;
			int centerX = data.x;
			int centerZ = data.z;
			int viewerX = Mth.floor(viewer.getX() - centerX) / blocksPerPixel + 64;
			int viewerZ = Mth.floor(viewer.getZ() - centerZ) / blocksPerPixel + 64;
			int viewRadiusPixels = 512 / blocksPerPixel;

			int startX = (centerX / blocksPerPixel - 64) * biomesPerPixel;
			int startZ = (centerZ / blocksPerPixel - 64) * biomesPerPixel;
			ResourceLocation[] biomes = CACHE.computeIfAbsent(new ChunkPos(startX, startZ), pos -> {
				ResourceLocation[] array = new ResourceLocation[128 * biomesPerPixel * 128 * biomesPerPixel];
				for (int l = 0; l < 128 * biomesPerPixel; ++l) {
					for (int i1 = 0; i1 < 128 * biomesPerPixel; ++i1) {
						array[l * 128 * biomesPerPixel + i1] = level
								.getBiome(new BlockPos(startX * biomesPerPixel + i1 * biomesPerPixel, 0, startZ * biomesPerPixel + l * biomesPerPixel))
								.unwrapKey()
								.map(ResourceKey::location)
								.orElse(NULL_BIOME);
					}
				}
				return array;
			});

			// 地标冷却与缓存
			int searchCooldown = TFConfig.COMMON_CONFIG.magicMapLandmarkSearchCooldown.get();
			int searchRadius = TFConfig.COMMON_CONFIG.magicMapLandmarkSearchRadius.get();
			long now = level.getGameTime();
			if (data instanceof TFMagicMapData tfData) {
				if (tfData.lastLandmarkSearchTick != null && now - tfData.lastLandmarkSearchTick < searchCooldown) {
					// 冷却中，直接返回
					return;
				}
				tfData.lastLandmarkSearchTick = now;
				tfData.tfDecorations.clear();
			}

			for (int xPixel = viewerX - viewRadiusPixels + 1; xPixel < viewerX + viewRadiusPixels; ++xPixel) {
				for (int zPixel = viewerZ - viewRadiusPixels - 1; zPixel < viewerZ + viewRadiusPixels; ++zPixel) {
					if (xPixel >= 0 && zPixel >= 0 && xPixel < 128 && zPixel < 128) {
						int xPixelDist = xPixel - viewerX;
						int zPixelDist = zPixel - viewerZ;
						boolean shouldFuzz = xPixelDist * xPixelDist + zPixelDist * zPixelDist > (viewRadiusPixels - 2) * (viewRadiusPixels - 2);

						ResourceLocation biome = biomes[xPixel * biomesPerPixel + zPixel * biomesPerPixel * 128 * biomesPerPixel];
						ResourceLocation overBiome = biomes[xPixel * biomesPerPixel + zPixel * biomesPerPixel * 128 * biomesPerPixel + 1];
						ResourceLocation downBiome = biomes[xPixel * biomesPerPixel + (zPixel * biomesPerPixel + 1) * 128 * biomesPerPixel];
						biome = overBiome != null && BiomeKeys.STREAM.location().equals(overBiome) ? overBiome : downBiome != null && BiomeKeys.STREAM.location().equals(downBiome) ? downBiome : biome;

						MapColorBrightness colorBrightness = this.getMapColorPerBiome(level, biome);
						MaterialColor mapcolor = colorBrightness.color;
						int brightness = colorBrightness.brightness;

						if (xPixelDist * xPixelDist + zPixelDist * zPixelDist < viewRadiusPixels * viewRadiusPixels && (!shouldFuzz || (xPixel + zPixel & 1) != 0)) {
							byte orgPixel = data.colors[xPixel + zPixel * 128];
							byte ourPixel = (byte) (mapcolor.id * 4 + brightness);

							if (orgPixel != ourPixel) {
								data.setColor(xPixel, zPixel, ourPixel);
								data.setDirty();
							}

							// 地标异步查找
							int worldX = (centerX / blocksPerPixel + xPixel - 64) * blocksPerPixel;
							int worldZ = (centerZ / blocksPerPixel + zPixel - 64) * blocksPerPixel;
							if (LegacyLandmarkPlacements.blockIsInLandmarkCenter(worldX, worldZ)) {
								byte mapX = (byte) ((worldX - centerX) / (float) blocksPerPixel * 2F);
								byte mapZ = (byte) ((worldZ - centerZ) / (float) blocksPerPixel * 2F);
								ChunkPos chunk = new ChunkPos(worldX >> 4, worldZ >> 4);
								LandmarkSearchManager.requestLandmarkAsync(serverLevel, chunk, feature -> {
									if (data instanceof TFMagicMapData tfData2) {
										tfData2.tfDecorations.add(new TFMagicMapData.TFMapDecoration(feature, mapX, mapZ, (byte) 8));
									}
								});
							}
						}
					}
				}
			}
		}
	}

	private MapColorBrightness getMapColorPerBiome(Level level, ResourceLocation biome) {
		if (BIOME_COLORS.isEmpty()) {
			setupBiomeColors();
		}
		if (biome == NULL_BIOME)
			return new MapColorBrightness(MaterialColor.COLOR_BLACK);
		MapColorBrightness color = BIOME_COLORS.get(biome);
		if (color != null) {
			return color;
		}
		//FIXME surface builder where
		return new MapColorBrightness(MaterialColor.COLOR_MAGENTA); //biome.getGenerationSettings().getSurfaceBuilderConfig().getTopMaterial().getMapColor(world, BlockPos.ZERO));
	}

	private static void setupBiomeColors() {
		putBiomeColor(BiomeKeys.FOREST, new MapColorBrightness(MaterialColor.PLANT, 1));
		putBiomeColor(BiomeKeys.DENSE_FOREST, new MapColorBrightness(MaterialColor.PLANT, 0));
		putBiomeColor(BiomeKeys.LAKE, new MapColorBrightness(MaterialColor.WATER, 3));
		putBiomeColor(BiomeKeys.STREAM, new MapColorBrightness(MaterialColor.WATER, 1));
		putBiomeColor(BiomeKeys.SWAMP, new MapColorBrightness(MaterialColor.DIAMOND, 3));
		putBiomeColor(BiomeKeys.FIRE_SWAMP, new MapColorBrightness(MaterialColor.NETHER, 1));
		putBiomeColor(BiomeKeys.CLEARING, new MapColorBrightness(MaterialColor.GRASS, 2));
		putBiomeColor(BiomeKeys.OAK_SAVANNAH, new MapColorBrightness(MaterialColor.GRASS, 0));
		putBiomeColor(BiomeKeys.HIGHLANDS, new MapColorBrightness(MaterialColor.DIRT, 0));
		putBiomeColor(BiomeKeys.THORNLANDS, new MapColorBrightness(MaterialColor.WOOD, 3));
		putBiomeColor(BiomeKeys.FINAL_PLATEAU, new MapColorBrightness(MaterialColor.COLOR_LIGHT_GRAY, 2));
		putBiomeColor(BiomeKeys.FIREFLY_FOREST, new MapColorBrightness(MaterialColor.EMERALD, 1));
		putBiomeColor(BiomeKeys.DARK_FOREST, new MapColorBrightness(MaterialColor.COLOR_GREEN, 3));
		putBiomeColor(BiomeKeys.DARK_FOREST_CENTER, new MapColorBrightness(MaterialColor.COLOR_ORANGE, 3));
		putBiomeColor(BiomeKeys.SNOWY_FOREST, new MapColorBrightness(MaterialColor.SNOW, 1));
		putBiomeColor(BiomeKeys.GLACIER, new MapColorBrightness(MaterialColor.ICE, 1));
		putBiomeColor(BiomeKeys.MUSHROOM_FOREST, new MapColorBrightness(MaterialColor.COLOR_ORANGE, 0));
		putBiomeColor(BiomeKeys.DENSE_MUSHROOM_FOREST, new MapColorBrightness(MaterialColor.COLOR_PINK, 0));
		putBiomeColor(BiomeKeys.ENCHANTED_FOREST, new MapColorBrightness(MaterialColor.COLOR_CYAN, 2));
		putBiomeColor(BiomeKeys.SPOOKY_FOREST, new MapColorBrightness(MaterialColor.COLOR_PURPLE, 0));
	}

	private static void putBiomeColor(ResourceKey<Biome> biome, MapColorBrightness color) {
		BIOME_COLORS.put(biome.location(), color);
	}

	public static int getBiomeColor(Level level, Biome biome) {
		if (BIOME_COLORS.isEmpty()) {
			setupBiomeColors();
		}

		MapColorBrightness c = BIOME_COLORS.get(level.registryAccess().ownedRegistryOrThrow(Registry.BIOME_REGISTRY).getKey(biome));

		return c != null ? getMapColor(c) : 0xFF000000;
	}

	public static int getMapColor(MapColorBrightness mcb) {
		int i = switch (mcb.color.id) {
			case 3 -> 135;
			case 2 -> 255;
			case 0 -> 180;
			default -> 220;
		};

		int j = (mcb.color.col >> 16 & 255) * i / 255;
		int k = (mcb.color.col >> 8 & 255) * i / 255;
		int l = (mcb.color.col & 255) * i / 255;
		return 0xFF000000 | l << 16 | k << 8 | j;
	}

	@Override
	public void onCraftedBy(ItemStack stack, Level world, Player player) {
		// disable zooming
	}

	@Override
	@Nullable
	public Packet<?> getUpdatePacket(ItemStack stack, Level world, Player player) {
		Integer id = getMapId(stack);
		TFMagicMapData mapdata = getCustomMapData(stack, world);
		return id == null || mapdata == null ? null : mapdata.getUpdatePacket(id, player);
	}

	@Override
	public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
		Integer integer = getMapId(stack);
		TFMagicMapData mapitemsaveddata = level == null ? null : getData(stack, level);
		if (flag.isAdvanced()) {
			if (mapitemsaveddata != null) {
				tooltip.add((Component.translatable("filled_map.id", integer)).withStyle(ChatFormatting.GRAY));
				tooltip.add((Component.translatable("filled_map.scale", 1 << mapitemsaveddata.scale)).withStyle(ChatFormatting.GRAY));
				tooltip.add((Component.translatable("filled_map.level", mapitemsaveddata.scale, 4)).withStyle(ChatFormatting.GRAY));
			} else {
				tooltip.add((Component.translatable("filled_map.unknown")).withStyle(ChatFormatting.GRAY));
			}
		} else {
			if (integer != null) {
				tooltip.add(Component.literal("#" + integer).withStyle(ChatFormatting.GRAY));
			}
		}

	}

	// 地标异步搜索与缓存管理器
	private static class LandmarkSearchManager {
		private static final ExecutorService executor = Executors.newFixedThreadPool(2);
		private static final ConcurrentHashMap<String, TFLandmark> cache = new ConcurrentHashMap<>();

		/**
		 * 异步请求地标，key 由世界+区块坐标唯一确定。
		 * @param level 世界
		 * @param chunk 区块
		 * @param callback 结果回调（主线程调用）
		 */
		public static void requestLandmarkAsync(ServerLevel level, ChunkPos chunk, Consumer<TFLandmark> callback) {
			String key = level.dimension().location() + ":" + chunk.x + "," + chunk.z;
			TFLandmark cached = cache.get(key);
			if (cached != null) {
				callback.accept(cached);
				return;
			}
			executor.submit(() -> {
				TFLandmark result = twilightforest.util.LegacyLandmarkPlacements.pickLandmarkForChunk(chunk.x, chunk.z, level);
				cache.put(key, result);
				// 回到主线程
				level.getServer().execute(() -> callback.accept(result));
			});
		}
	}
}