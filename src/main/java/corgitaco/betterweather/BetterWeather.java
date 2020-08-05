package corgitaco.betterweather;

import com.google.common.collect.Lists;
import corgitaco.betterweather.weather.AcidRain;
import corgitaco.betterweather.weather.Blizzard;
import corgitaco.betterweather.weather.HailStorm;
import corgitaco.betterweather.weather.SandStorm;
import net.minecraft.entity.Entity;
import net.minecraft.util.DamageSource;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.GameRules;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.server.ChunkHolder;
import net.minecraft.world.server.ServerWorld;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.Optional;
import java.util.Random;

@Mod("betterweather")
public class BetterWeather {
    public static Logger LOGGER = LogManager.getLogger();
    public static final String MOD_ID = "betterweather";
    public static boolean isAcidRain = false;

    public BetterWeather() {
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::commonSetup);
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::clientSetup);
    }

    public void commonSetup(FMLCommonSetupEvent event) {

    }

    public void clientSetup(FMLClientSetupEvent event) {
    }

    @Mod.EventBusSubscriber(modid = BetterWeather.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
    public static class BetterWeatherEvents {

        @SubscribeEvent
        public static void worldTick(TickEvent.WorldTickEvent event) {
            if (event.side.isServer() && event.phase == TickEvent.Phase.END) {
                ServerWorld serverWorld = (ServerWorld) event.world;
                World world = event.world;
                int tickSpeed = world.getGameRules().getInt(GameRules.RANDOM_TICK_SPEED);
                long worldTime = world.getWorldInfo().getGameTime();

                if (worldTime % 4000 == 0 && !event.world.isRaining()) {
                    Random random = new Random();
                    int randomChance = random.nextInt(3);
                    isAcidRain = randomChance == 0;
                }

                List<ChunkHolder> list = Lists.newArrayList((serverWorld.getChunkProvider()).chunkManager.getLoadedChunksIterable());
                list.forEach(chunkHolder -> {
                    Optional<Chunk> optional = chunkHolder.getTickingFuture().getNow(ChunkHolder.UNLOADED_CHUNK).left();
                    //Gets chunks to tick
                    if (optional.isPresent()) {
                        Optional<Chunk> optional1 = chunkHolder.getEntityTickingFuture().getNow(ChunkHolder.UNLOADED_CHUNK).left();
                        if (optional1.isPresent()) {
                            Chunk chunk = optional1.get();
                            SandStorm.sandStormEvent(chunk, serverWorld, tickSpeed);
                            HailStorm.hailStormEvent(chunk, serverWorld, tickSpeed);
                            Blizzard.blizzardEvent(chunk, serverWorld, tickSpeed);
                            AcidRain.acidRainEvent(chunk, serverWorld, tickSpeed, worldTime);
                        }
                    }
                });
            }
        }

        @SubscribeEvent
        public static void renderTickEvent(TickEvent.RenderTickEvent event) {

        }

        @SubscribeEvent
        public static void playerTickEvent(TickEvent.PlayerTickEvent event) {
        }

        @SubscribeEvent
        public static void playerTickEvent(LivingEvent.LivingUpdateEvent event) {
            Entity entity = event.getEntity();
            World world = entity.world;
            BlockPos entityPos = new BlockPos(entity.getPositionVec());

            if (world.canSeeSky(entityPos) && isAcidRain && world.isRaining() && world.getGameTime() % 250 == 0) {
                entity.attackEntityFrom(DamageSource.GENERIC, 0.5F);
            }
        }
    }

    public enum WeatherType {
        BLIZZARD,
        HAIL,
        HEATWAVE,
        WINDSTORM,
        SANDSTORM,
        ACIDRAIN
    }
}