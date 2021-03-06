package corgitaco.betterweather.mixin.biome;

import corgitaco.betterweather.api.BiomeClimate;
import corgitaco.betterweather.helpers.BiomeModifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.biome.Biome;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;


@Mixin(Biome.class)
public abstract class MixinBiome implements BiomeModifier, BiomeClimate {

    @Shadow
    @Final
    private Biome.Climate climate;

    @Inject(method = "getDownfall", at = @At("RETURN"), cancellable = true)
    private void modifyDownfall(CallbackInfoReturnable<Float> cir) {
        cir.setReturnValue(this.climate.downfall + (float) ((BiomeClimate) climate).getHumidityModifier());
    }

    @Inject(method = "getTemperature()F", at = @At("RETURN"), cancellable = true)
    private void modifyTemperature(CallbackInfoReturnable<Float> cir) {
        cir.setReturnValue(this.climate.temperature + (float) ((BiomeClimate) climate).getTemperatureModifier());
    }

    @Override
    public double getTemperatureModifier() {
        return ((BiomeClimate) climate).getTemperatureModifier();
    }

    @Override
    public double getSeasonTemperatureModifier() {
        return ((BiomeClimate) climate).getSeasonTemperatureModifier();
    }

    @Override
    public double getWeatherTemperatureModifier(BlockPos pos) {
        return ((BiomeClimate) climate).getWeatherTemperatureModifier(pos);
    }

    @Override
    public double getHumidityModifier() {
        return ((BiomeClimate) climate).getHumidityModifier();
    }

    @Override
    public double getSeasonHumidityModifier() {
        return ((BiomeClimate) climate).getSeasonHumidityModifier();
    }

    @Override
    public double getWeatherHumidityModifier(BlockPos pos) {
        return ((BiomeClimate) climate).getWeatherHumidityModifier(pos);
    }

    @Override
    public void setSeasonTempModifier(float tempModifier) {
        ((BiomeModifier) this.climate).setSeasonTempModifier(tempModifier);
    }

    @Override
    public void setSeasonHumidityModifier(float humidityModifier) {
        ((BiomeModifier) this.climate).setSeasonHumidityModifier(humidityModifier);
    }

    @Override
    public void setWeatherTempModifier(float tempModifier) {
        ((BiomeModifier) this.climate).setWeatherTempModifier(tempModifier);
    }

    @Override
    public void setWeatherHumidityModifier(float humidityModifier) {
        ((BiomeModifier) this.climate).setWeatherHumidityModifier(humidityModifier);
    }
}
