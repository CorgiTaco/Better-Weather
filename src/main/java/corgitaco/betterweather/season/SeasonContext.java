package corgitaco.betterweather.season;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import corgitaco.betterweather.BetterWeather;
import corgitaco.betterweather.BetterWeatherUtil;
import corgitaco.betterweather.api.SeasonData;
import corgitaco.betterweather.config.season.SeasonConfigHolder;
import corgitaco.betterweather.config.season.overrides.BiomeOverrideJsonHandler;
import corgitaco.betterweather.datastorage.SeasonSavedData;
import corgitaco.betterweather.datastorage.network.NetworkHandler;
import corgitaco.betterweather.datastorage.network.packet.SeasonPacket;
import corgitaco.betterweather.datastorage.network.packet.util.RefreshRenderersPacket;
import corgitaco.betterweather.helpers.IBiomeUpdate;
import corgitaco.betterweather.server.BetterWeatherGameRules;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RegistryKey;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.registry.Registry;
import net.minecraft.world.World;
import net.minecraft.world.server.ServerWorld;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class SeasonContext {
    public static final String CONFIG_NAME = "season-settings.json";

    private Season currentSeason;
    private int currentSeasonTime;
    private int seasonCycleLength;

    private final File seasonConfigFile;
    private final Path seasonOverridesPath;

    private IdentityHashMap<SeasonData.SeasonKey, Season> seasons;

    public SeasonContext(SeasonSavedData seasonData, RegistryKey<World> worldKey) {
        this.currentSeasonTime = seasonData.getSeasonTime();
        this.seasonCycleLength = seasonData.getSeasonCycleLength();

        ResourceLocation dimensionLocation = worldKey.getLocation();
        Path seasonsFolderPath = BetterWeather.CONFIG_PATH.resolve(dimensionLocation.getNamespace()).resolve(dimensionLocation.getPath()).resolve("seasons");
        this.seasonConfigFile = seasonsFolderPath.resolve(CONFIG_NAME).toFile();
        this.seasonOverridesPath = seasonsFolderPath.resolve("overrides");

        this.handleConfig();
        this.currentSeason = seasons.get(BetterWeatherUtil.getSeasonFromTime(currentSeasonTime, seasonCycleLength)).setPhaseForTime(this.currentSeasonTime / 4, this.seasonCycleLength / 4);
    }

    public void handleConfig() {
        Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
        if (!seasonConfigFile.exists()) {
            create(gson);
        }
        if (seasonConfigFile.exists()) {
            read();
        }

        fillSubSeasonOverrideStorage();
    }

    private void fillSubSeasonOverrideStorage() {
        for (Map.Entry<SeasonData.SeasonKey, Season> seasonKeySeasonEntry : this.seasons.entrySet()) {
            IdentityHashMap<SeasonData.Phase, SubSeasonSettings> phaseSettings = seasonKeySeasonEntry.getValue().getPhaseSettings();
            for (Map.Entry<SeasonData.Phase, SubSeasonSettings> phaseSubSeasonSettingsEntry : phaseSettings.entrySet()) {
                BiomeOverrideJsonHandler.handleOverrideJsonConfigs(this.seasonOverridesPath.resolve(seasonKeySeasonEntry.getKey().toString() + "-" + phaseSubSeasonSettingsEntry.getKey() + ".json"), seasonKeySeasonEntry.getKey() == SeasonData.SeasonKey.WINTER ? SubSeasonSettings.WINTER_OVERRIDE : new IdentityHashMap<>(), phaseSubSeasonSettingsEntry.getValue());
            }
        }
    }

    public void setSeason(List<ServerPlayerEntity> players, SeasonData.SeasonKey newSeason, SeasonData.Phase phase) {
        this.currentSeasonTime = BetterWeatherUtil.getTimeInCycleForSeasonAndPhase(newSeason, phase, this.seasonCycleLength);
        this.currentSeason = seasons.get(newSeason);
        this.currentSeason.setPhaseForTime(currentSeasonTime / 4, seasonCycleLength / 4);
        this.updatePacket(players);
    }

    private void create(Gson gson) {
        String toJson = gson.toJson(SeasonConfigHolder.CODEC.encodeStart(JsonOps.INSTANCE, SeasonConfigHolder.DEFAULT_CONFIG_HOLDER).result().get());

        try {
            Files.createDirectories(seasonConfigFile.toPath().getParent());
            Files.write(seasonConfigFile.toPath(), toJson.getBytes());
        } catch (IOException e) {

        }
    }

    private void read() {
        try (Reader reader = new FileReader(seasonConfigFile)) {
            JsonObject jsonObject = new JsonParser().parse(reader).getAsJsonObject();
            Optional<SeasonConfigHolder> configHolder = SeasonConfigHolder.CODEC.parse(JsonOps.INSTANCE, jsonObject).resultOrPartial(BetterWeather.LOGGER::error);
            if (configHolder.isPresent()) {
                this.seasons = configHolder.get().getSeasonKeySeasonMap();
                this.seasonCycleLength = configHolder.get().getSeasonCycleLength();
            } else {
                this.seasons = SeasonConfigHolder.DEFAULT_CONFIG_HOLDER.getSeasonKeySeasonMap();
                this.seasonCycleLength = SeasonConfigHolder.DEFAULT_CONFIG_HOLDER.getSeasonCycleLength();
            }
        } catch (IOException e) {

        }
    }

    public void tick(World world) {
        Season prevSeason = this.currentSeason;
        this.tickSeasonTime(world);

        SeasonData.Phase prevPhase = this.currentSeason.getCurrentPhase();
        this.currentSeason.tick(currentSeasonTime / 4, seasonCycleLength / 4); //TODO: Is this right?!?!

        if (prevSeason != this.currentSeason || prevPhase != this.currentSeason.getCurrentPhase()) {
            ((IBiomeUpdate) world).updateBiomeData(this.getCurrentSubSeasonSettings());
            if (!world.isRemote) {
                updatePacket(((ServerWorld) world).getPlayers());

            }
        }
    }

    public void tickCrops(ServerWorld world, BlockPos posIn, Block block, BlockState self, CallbackInfo ci) {
        SubSeasonSettings subSeason = this.getCurrentSubSeasonSettings();
        if (subSeason.getBiomeToOverrideStorage().isEmpty() && subSeason.getCropToMultiplierStorage().isEmpty()) {
            if (BlockTags.CROPS.contains(block) || BlockTags.BEE_GROWABLES.contains(block) || BlockTags.SAPLINGS.contains(block)) {
                cropTicker(world, posIn, block, subSeason, true, self, ci);
            }
        } else {
            if (BlockTags.CROPS.contains(block) || BlockTags.BEE_GROWABLES.contains(block) || BlockTags.SAPLINGS.contains(block)) {
                cropTicker(world, posIn, block, subSeason, false, self, ci);
            }
        }
    }

    private static void cropTicker(ServerWorld world, BlockPos posIn, Block block, SubSeasonSettings subSeason, boolean useSeasonDefault, BlockState self, CallbackInfo ci) {

        //Collect the crop multiplier for the given subseason.
        double cropGrowthMultiplier = subSeason.getCropGrowthChanceMultiplier(world.func_241828_r().getRegistry(Registry.BIOME_KEY).getKey(world.getBiome(posIn)), block, useSeasonDefault);
        if (cropGrowthMultiplier == 1)
            return;

        //Pretty self explanatory, basically run a chance on whether or not the crop will tick for this tick
        if (cropGrowthMultiplier < 1) {
            if (world.getRandom().nextDouble() < cropGrowthMultiplier) {
                block.randomTick(self, world, posIn, world.getRandom());
            } else
                ci.cancel();
        }
        //Here we gather a random number of ticks that this block will tick for this given tick.
        //We do a random.nextDouble() to determine if we get the ceil or floor value for the given crop growth multiplier.
        else if (cropGrowthMultiplier > 1) {
            int numberOfTicks = world.getRandom().nextInt((world.getRandom().nextDouble() + (cropGrowthMultiplier - 1) < cropGrowthMultiplier) ? (int) Math.ceil(cropGrowthMultiplier) : (int) cropGrowthMultiplier) + 1;
            for (int tick = 0; tick < numberOfTicks; tick++) {
                if (tick > 0) {
                    self = world.getBlockState(posIn);
                    block = self.getBlock();
                }

                block.randomTick(self, world, posIn, world.getRandom());
            }
        }
    }

    public void updatePacket(List<ServerPlayerEntity> players) {
        for (ServerPlayerEntity player : players) {
            NetworkHandler.sendToClient(player, new SeasonPacket(this.currentSeasonTime, this.seasonCycleLength));
            NetworkHandler.sendToClient(player, new RefreshRenderersPacket());
        }
    }

    private void tickSeasonTime(World world) {
        if (world.getGameRules().getBoolean(BetterWeatherGameRules.DO_SEASON_CYCLE)) {
            this.currentSeasonTime = currentSeasonTime > this.seasonCycleLength ? 0 : (currentSeasonTime + 1);
            this.currentSeason = this.seasons.get(BetterWeatherUtil.getSeasonFromTime(this.currentSeasonTime, this.seasonCycleLength)).setPhaseForTime(this.currentSeasonTime / 4, this.seasonCycleLength / 4);

            if (world.getWorldInfo().getGameTime() % 50 == 0) {
                save(world);
            }
        }
    }

    private void save(World world) {
        SeasonSavedData.get(world).setSeasonTime(this.currentSeasonTime);
        SeasonSavedData.get(world).setSeasonCycleLength(this.seasonCycleLength);
    }

    public Season getCurrentSeason() {
        return currentSeason;
    }

    public SubSeasonSettings getCurrentSubSeasonSettings() {
        return this.currentSeason.getCurrentSettings();
    }

    public int getSeasonCycleLength() {
        return seasonCycleLength;
    }

    public int getCurrentSeasonTime() {
        return currentSeasonTime;
    }

    @OnlyIn(Dist.CLIENT)
    public void setCurrentSeasonTime(int currentSeasonTime) {
        this.currentSeasonTime = currentSeasonTime;
    }

    @OnlyIn(Dist.CLIENT)
    public void setSeasonCycleLength(int seasonCycleLength) {
        this.seasonCycleLength = seasonCycleLength;
    }
}