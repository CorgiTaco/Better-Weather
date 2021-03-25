package corgitaco.betterweather.datastorage.network.packet.season;

import corgitaco.betterweather.helpers.BetterWeatherWorldData;
import corgitaco.betterweather.season.SeasonContext;
import net.minecraft.client.Minecraft;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.registry.Registry;
import net.minecraftforge.fml.network.NetworkEvent;

import java.io.IOException;
import java.util.function.Supplier;

public class SeasonPacket {

    private final SeasonContext seasonContext;

    public SeasonPacket(SeasonContext seasonContext) {
        this.seasonContext = seasonContext;
    }

    public static void writeToPacket(SeasonPacket packet, PacketBuffer buf) {
        try {
            buf.func_240629_a_(SeasonContext.PACKET_CODEC, packet.seasonContext);
        } catch (IOException e) {
            throw new IllegalStateException("Season packet could not be written to. This is really really bad...\n\n" + e.getMessage());

        }
    }

    public static SeasonPacket readFromPacket(PacketBuffer buf) {
        try {
            return new SeasonPacket(buf.func_240628_a_(SeasonContext.PACKET_CODEC));
        } catch (IOException e) {
            throw new IllegalStateException("Season packet could not be read. This is really really bad...\n\n" + e.getMessage());
        }
    }

    public static void handle(SeasonPacket message, Supplier<NetworkEvent.Context> ctx) {
        if (ctx.get().getDirection().getReceptionSide().isClient()) {
            ctx.get().enqueueWork(() -> {
                Minecraft minecraft = Minecraft.getInstance();

                if (minecraft.world != null && minecraft.player != null) {
                    SeasonContext seasonContext = ((BetterWeatherWorldData) minecraft.world).getSeasonContext();
                    if (seasonContext == null) {
                        seasonContext = ((BetterWeatherWorldData) minecraft.world).setSeasonContext(new SeasonContext(message.seasonContext.getCurrentYearTime(), message.seasonContext.getYearLength(), minecraft.world.getDimensionKey().getLocation(), minecraft.world.func_241828_r().getRegistry(Registry.BIOME_KEY), message.seasonContext.getSeasons()));
                    }

                    seasonContext.setCurrentYearTime(seasonContext.getCurrentYearTime());
                }
            });
        }
        ctx.get().setPacketHandled(true);
    }
}