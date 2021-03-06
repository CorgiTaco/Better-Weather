package corgitaco.betterweather.mixin.server.world;

import corgitaco.betterweather.BetterWeather;
import corgitaco.betterweather.api.Climate;
import corgitaco.betterweather.api.season.Season;
import corgitaco.betterweather.api.weather.WeatherEvent;
import corgitaco.betterweather.config.BetterWeatherConfig;
import corgitaco.betterweather.data.storage.SeasonSavedData;
import corgitaco.betterweather.data.storage.WeatherEventSavedData;
import corgitaco.betterweather.helpers.BetterWeatherWorldData;
import corgitaco.betterweather.helpers.BiomeModifier;
import corgitaco.betterweather.helpers.BiomeUpdate;
import corgitaco.betterweather.mixin.access.ChunkManagerAccess;
import corgitaco.betterweather.mixin.access.ServerChunkProviderAccess;
import corgitaco.betterweather.mixin.access.ServerWorldAccess;
import corgitaco.betterweather.season.SeasonContext;
import corgitaco.betterweather.util.WorldDynamicRegistry;
import corgitaco.betterweather.weather.BWWeatherEventContext;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.effect.LightningBoltEntity;
import net.minecraft.entity.passive.horse.SkeletonHorseEntity;
import net.minecraft.nbt.INBT;
import net.minecraft.nbt.NBTDynamicOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.RegistryKey;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraft.util.registry.DynamicRegistries;
import net.minecraft.util.registry.Registry;
import net.minecraft.util.registry.WorldSettingsImport;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.DimensionType;
import net.minecraft.world.GameRules;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.listener.IChunkStatusListener;
import net.minecraft.world.gen.ChunkGenerator;
import net.minecraft.world.server.ServerChunkProvider;
import net.minecraft.world.server.ServerWorld;
import net.minecraft.world.spawner.ISpecialSpawner;
import net.minecraft.world.storage.IServerWorldInfo;
import net.minecraft.world.storage.SaveFormat;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;


@Mixin(ServerWorld.class)
public abstract class MixinServerWorld implements BiomeUpdate, BetterWeatherWorldData, Climate {

    @Shadow
    @Final
    private ServerChunkProvider serverChunkProvider;

    private DynamicRegistries registry;

    @Nullable
    private SeasonContext seasonContext;

    @Nullable
    private BWWeatherEventContext weatherContext;

    @SuppressWarnings("ALL")
    @Inject(method = "<init>", at = @At("RETURN"))
    private void storeUpgradablePerWorldRegistry(MinecraftServer server, Executor executor, SaveFormat.LevelSave save, IServerWorldInfo worldInfo, RegistryKey<World> key, DimensionType dimensionType, IChunkStatusListener statusListener, ChunkGenerator generator, boolean b, long seed, List<ISpecialSpawner> specialSpawners, boolean b1, CallbackInfo ci) {
        ResourceLocation worldKeyLocation = key.getLocation();
        boolean hasPerWorldRegistry = Stream.concat(BetterWeatherConfig.WEATHER_EVENT_DIMENSIONS.stream(), BetterWeatherConfig.SEASON_DIMENSIONS.stream()).collect(Collectors.toSet()).size() > 1;

        boolean isValidWeatherEventDimension = BetterWeatherConfig.WEATHER_EVENT_DIMENSIONS.contains(worldKeyLocation.toString());
        boolean isValidSeasonDimension = BetterWeatherConfig.SEASON_DIMENSIONS.contains(worldKeyLocation.toString());

        if (hasPerWorldRegistry && (isValidWeatherEventDimension || isValidSeasonDimension)) {
            this.registry = new WorldDynamicRegistry((DynamicRegistries.Impl) server.getDynamicRegistries());
            BetterWeather.LOGGER.warn("Swapping server world gen datapack registry for \"" + key.getLocation().toString() + "\" to a per world registry... This may have unintended side effects like mod incompatibilities in this world...");

            // Reload the world settings import with OUR implementation of the registry.
            WorldSettingsImport<INBT> worldSettingsImport = WorldSettingsImport.create(NBTDynamicOps.INSTANCE, server.getDataPackRegistries().getResourceManager(), (DynamicRegistries.Impl) this.registry);
            ChunkGenerator dimensionChunkGenerator = save.readServerConfiguration(worldSettingsImport, server.getServerConfiguration().getDatapackCodec()).getDimensionGeneratorSettings().func_236224_e_().getOptional(worldKeyLocation).get().getChunkGenerator();

            // Reset the chunk generator fields in both the chunk provider and chunk manager. This is required for chunk generators to return the current biome object type required by our registry.
            // TODO: Do this earlier so mods mixing here can capture our version of the chunk generator.
            BetterWeather.LOGGER.warn("Swapping chunk generator for \"" + key.getLocation().toString() + "\" to use the per world registry... This may have unintended side effects like mod incompatibilities in this world...");
            ((ServerChunkProviderAccess) this.serverChunkProvider).setGenerator(dimensionChunkGenerator);
            ((ChunkManagerAccess) this.serverChunkProvider.chunkManager).setGenerator(dimensionChunkGenerator);
            BetterWeather.LOGGER.info("Swapped the chunk generator for \"" + key.getLocation().toString() + "\" to use the per world registry!");

            BetterWeather.LOGGER.info("Swapped world gen datapack registry for \"" + key.getLocation().toString() + "\" to the per world registry!");
        } else {
            this.registry = server.getDynamicRegistries();
        }

        if (isValidWeatherEventDimension) {
            this.weatherContext = new BWWeatherEventContext(WeatherEventSavedData.get((ServerWorld) (Object) this), key, this.registry.getRegistry(Registry.BIOME_KEY));
        }

        if (isValidSeasonDimension) {
            this.seasonContext = new SeasonContext(SeasonSavedData.get((ServerWorld) (Object) this), key, this.registry.getRegistry(Registry.BIOME_KEY));
        }

        updateBiomeData();
    }

    @SuppressWarnings("ConstantConditions")
    @Override
    public void updateBiomeData() {
        List<Biome> validBiomes = this.serverChunkProvider.getChunkGenerator().getBiomeProvider().getBiomes();
        for (Map.Entry<RegistryKey<Biome>, Biome> entry : this.registry.getRegistry(Registry.BIOME_KEY).getEntries()) {
            Biome biome = entry.getValue();
            RegistryKey<Biome> biomeKey = entry.getKey();

            if (seasonContext != null && validBiomes.contains(biome)) {
                float seasonHumidityModifier = (float) this.seasonContext.getCurrentSubSeasonSettings().getHumidityModifier(biomeKey);
                float seasonTemperatureModifier = (float) this.seasonContext.getCurrentSubSeasonSettings().getTemperatureModifier(biomeKey);
                ((BiomeModifier) (Object) biome).setSeasonTempModifier(seasonTemperatureModifier);
                ((BiomeModifier) (Object) biome).setSeasonHumidityModifier(seasonHumidityModifier);
            }

            if (weatherContext != null && validBiomes.contains(biome) && weatherContext.getCurrentEvent().isValidBiome(biome)) {
                float weatherHumidityModifier = (float) this.weatherContext.getCurrentEvent().getHumidityModifierAtPosition(null);
                float weatherTemperatureModifier = (float) this.weatherContext.getCurrentWeatherEventSettings().getTemperatureModifierAtPosition(null);
                ((BiomeModifier) (Object) biome).setWeatherTempModifier(weatherTemperatureModifier);
                ((BiomeModifier) (Object) biome).setWeatherHumidityModifier(weatherHumidityModifier);
            }


        }
    }

    @Inject(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/DimensionType;hasSkyLight()Z"))
    private void tick(BooleanSupplier hasTimeLeft, CallbackInfo ci) {
        if (seasonContext != null) {
            this.seasonContext.tick((ServerWorld) (Object) this);
        }
        if (weatherContext != null) {
            this.weatherContext.tick((ServerWorld) (Object) this);
        }
    }

    @Inject(method = "func_241828_r", at = @At("HEAD"), cancellable = true)
    private void dynamicRegistryWrapper(CallbackInfoReturnable<DynamicRegistries> cir) {
        cir.setReturnValue(this.registry);
    }

    @ModifyConstant(method = "tick", constant = {@Constant(intValue = 168000), @Constant(intValue = 12000, ordinal = 1), @Constant(intValue = 12000, ordinal = 4)})
    private int modifyWeatherTime(int arg0) {
        SeasonContext seasonContext = this.seasonContext;
        return seasonContext != null ? (int) (arg0 * (1 / seasonContext.getCurrentSeason().getCurrentSettings().getWeatherEventChanceMultiplier())) : arg0;
    }


    @Inject(method = "setWeather", at = @At("HEAD"))
    private void setWeatherForced(int clearWeatherTime, int weatherTime, boolean rain, boolean thunder, CallbackInfo ci) {
        if (this.weatherContext != null) {
            this.weatherContext.setWeatherForced(true);
        }
    }

    @Inject(method = "tickEnvironment", at = @At("HEAD"), cancellable = true)
    private void tickLiveChunks(Chunk chunkIn, int randomTickSpeed, CallbackInfo ci) {
        if (weatherContext != null) {
            weatherContext.getCurrentEvent().doChunkTick(chunkIn, (ServerWorld) (Object) this);
            doLightning((ServerWorld) (Object) this, chunkIn.getPos());
        }
    }

    private void doLightning(ServerWorld world, ChunkPos chunkpos) {
        if (weatherContext == null) {
            return;
        }

        int xStart = chunkpos.getXStart();
        int zStart = chunkpos.getZStart();
        WeatherEvent currentEvent = weatherContext.getCurrentEvent();
        if (currentEvent.isThundering() && world.rand.nextInt(currentEvent.getLightningChance()) == 0) {
            BlockPos blockpos = ((ServerWorldAccess) world).invokeAdjustPosToNearbyEntity(world.getBlockRandomPos(xStart, 0, zStart, 15));
            Biome biome = world.getBiome(blockpos);
            if (currentEvent.isValidBiome(biome)) {
                DifficultyInstance difficultyinstance = world.getDifficultyForLocation(blockpos);
                boolean flag1 = world.getGameRules().getBoolean(GameRules.DO_MOB_SPAWNING) && world.rand.nextDouble() < (double) difficultyinstance.getAdditionalDifficulty() * 0.01D;
                if (flag1) {
                    SkeletonHorseEntity skeletonhorseentity = EntityType.SKELETON_HORSE.create(world);
                    skeletonhorseentity.setTrap(true);
                    skeletonhorseentity.setGrowingAge(0);
                    skeletonhorseentity.setPosition((double) blockpos.getX(), (double) blockpos.getY(), (double) blockpos.getZ());
                    world.addEntity(skeletonhorseentity);
                }

                LightningBoltEntity lightningboltentity = EntityType.LIGHTNING_BOLT.create(world);
                lightningboltentity.moveForced(Vector3d.copyCenteredHorizontally(blockpos));
                lightningboltentity.setEffectOnly(flag1);
                world.addEntity(lightningboltentity);
            }
        }
    }


    @Redirect(method = "tickEnvironment", at = @At(value = "INVOKE", target = "Ljava/util/Random;nextInt(I)I", ordinal = 0))
    private int neverSpawnLightning(Random random, int bound) {
        return weatherContext != null ? -1 : random.nextInt(bound);
    }

    @Redirect(method = "tickEnvironment", at = @At(value = "INVOKE", target = "Ljava/util/Random;nextInt(I)I", ordinal = 1))
    private int takeAdvantageOfExistingChunkIterator(Random random, int bound, Chunk chunk, int randomTickSpeed) {
        return weatherContext != null ? -1 : ((ServerWorld) (Object) this).rand.nextInt(bound);
    }

    @Nullable
    @Override
    public SeasonContext getSeasonContext() {
        return this.seasonContext;
    }

    @Nullable
    @Override
    public SeasonContext setSeasonContext(SeasonContext seasonContext) {
        this.seasonContext = seasonContext;
        return this.seasonContext;
    }

    @Nullable
    @Override
    public BWWeatherEventContext getWeatherEventContext() {
        return this.weatherContext;
    }

    @Nullable
    @Override
    public BWWeatherEventContext setWeatherEventContext(BWWeatherEventContext weatherEventContext) {
        this.weatherContext = weatherEventContext;
        return this.weatherContext;
    }

    @Nullable
    @Override
    public Season getSeason() {
        return this.seasonContext;
    }
}
