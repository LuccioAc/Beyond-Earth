package com.st0x0ef.beyond_earth.client.renderers.sky;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import com.mojang.math.Axis;
import com.st0x0ef.beyond_earth.common.config.ClientConfig;
import com.st0x0ef.beyond_earth.common.util.Planets;
import com.st0x0ef.beyond_earth.common.util.Planets.Planet;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.*;
import net.minecraft.util.Mth;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import com.st0x0ef.beyond_earth.client.renderers.sky.helper.SkyHelper;
import com.st0x0ef.beyond_earth.client.renderers.sky.helper.StarHelper;
import org.apache.commons.lang3.tuple.Triple;
import org.joml.Matrix4f;

@OnlyIn(Dist.CLIENT)
public class OrbitSky extends DimensionSpecialEffects {
    private static final float MIN_SKY_BRIGHTNESS = 0.15f;
    private static final float MIN_FOG_BRIGHTNESS = 0.1f;

    private Planet planet;
    private VertexBuffer starBuffer;

    public OrbitSky(Planet planet) {
        super(Float.NaN, false, SkyType.NONE, false, false);
        this.planet = planet;
    }

    @Override
    public Vec3 getBrightnessDependentFogColor(Vec3 color, float brightness) {
        float adjustedBrightness = Math.max(MIN_FOG_BRIGHTNESS, brightness);
        return new Vec3(
                color.x * adjustedBrightness,
                color.y * adjustedBrightness,
                color.z * adjustedBrightness
        );
    }

    @Override
    public boolean isFoggyAt(int x, int z) {
        return false;
    }

    @Override
    public float[] getSunriseColor(float time, float partialTick) {
        return null;
    }

    @Override
    public boolean renderClouds(ClientLevel level, int ticks, float partialTick, PoseStack poseStack,
                                double camX, double camY, double camZ, Matrix4f projectionMatrix) {
        return true;
    }

    @Override
    public boolean renderSky(ClientLevel level, int ticks, float partialTick, PoseStack poseStack,
                             Camera camera, Matrix4f projectionMatrix, boolean isFoggy, Runnable setupFog) {
        if (!ClientConfig.ORBIT_CUSTOM_SKY.get()) {
            return false;
        }

        Minecraft mc = Minecraft.getInstance();
        setupFog.run();

        if (!isFoggy && !shouldCancelRendering(mc, camera)) {
            Vec3 skyColor = calculateAdjustedSkyColor(level, partialTick, mc, camera);
            BufferBuilder bufferBuilder = Tesselator.getInstance().getBuilder();
            Matrix4f matrix4f = poseStack.last().pose();
            float dayTime = level.getTimeOfDay(partialTick);

            // Render sky background
            RenderSystem.depthMask(false);
            RenderSystem.setShaderColor(
                    (float)skyColor.x,
                    (float)skyColor.y,
                    (float)skyColor.z,
                    1.0F
            );
            SkyHelper.drawSky(mc, matrix4f, projectionMatrix, RenderSystem.getShader());

            // Render celestial bodies
            renderCelestialBodies(level, poseStack, bufferBuilder, matrix4f, dayTime, partialTick, mc, camera, projectionMatrix, setupFog);

            // Clean up
            RenderSystem.depthMask(true);
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        }
        return true;
    }

    private boolean shouldCancelRendering(Minecraft mc, Camera camera) {
        FogType fogtype = camera.getFluidInCamera();
        return fogtype == FogType.POWDER_SNOW ||
                fogtype == FogType.LAVA ||
                mc.levelRenderer.doesMobEffectBlockSky(camera);
    }

    private Vec3 calculateAdjustedSkyColor(ClientLevel level, float partialTick, Minecraft mc, Camera camera) {
        Vec3 baseColor = level.getSkyColor(mc.gameRenderer.getMainCamera().getPosition(), partialTick);

        float maxComponent = (float)Math.max(
                Math.max(baseColor.x, Math.max(baseColor.y, baseColor.z)),
                MIN_SKY_BRIGHTNESS
        );

        return new Vec3(
                (float)(baseColor.x / maxComponent * MIN_SKY_BRIGHTNESS),
                (float)(baseColor.y / maxComponent * MIN_SKY_BRIGHTNESS),
                (float)(baseColor.z / maxComponent * MIN_SKY_BRIGHTNESS)
        );
    }

    private void renderCelestialBodies(ClientLevel level, PoseStack poseStack, BufferBuilder bufferBuilder,
                                       Matrix4f matrix4f, float dayTime, float partialTick,
                                       Minecraft mc, Camera camera, Matrix4f projectionMatrix, Runnable setupFog) {
        float dayAngle = dayTime * 360f % 360f;

        // Initialize star buffer if needed
        if (starBuffer == null) {
            starBuffer = StarHelper.createStars(0.1F,
                    ClientConfig.ORBIT_FAST_STARS_COUNT.get(),
                    ClientConfig.ORBIT_FANCY_STARS_COUNT.get(),
                    190, 160, -1);
        }

        // Render stars
        matrix4f = SkyHelper.setMatrixRot(poseStack,
                Triple.of(Axis.YP.rotationDegrees(-90), Axis.XP.rotationDegrees(dayTime), null));
        RenderSystem.setShaderColor(0.8F, 0.8F, 0.8F, 0.8F);
        SkyHelper.drawStars(starBuffer, matrix4f, projectionMatrix,
                GameRenderer.getPositionColorShader(), setupFog, true);

        // Render sun
        matrix4f = SkyHelper.setMatrixRot(poseStack,
                Triple.of(Axis.YP.rotationDegrees(-90), Axis.XP.rotationDegrees(dayTime), null));
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        SkyHelper.drawPlanet(SkyHelper.WHITE_SUN, new Vec3(255, 255, 255), bufferBuilder, matrix4f, 30, 100, true);

        // Render planet and moons
        planet = Planets.getLocationForOrbit(level);
        if (planet != null) {
            if (planet._parent != null) {
                boolean inner = true;
                for (Planet p : planet._parent.children) {
                    if (p == planet) {
                        inner = false;
                        continue;
                    }
                    float distance = (float)(inner ? planet.orbitRadius / p.orbitRadius : p.orbitRadius / planet.orbitRadius);
                    float phase = p.orbitPhase - planet.orbitPhase;
                    float dAngle = 90 * Mth.sin(phase) / distance;
                    float angle = dayAngle + (inner ? dAngle : phase);

                    matrix4f = SkyHelper.setMatrixRot(poseStack, Triple.of(
                            Axis.YP.rotationDegrees(-90),
                            Axis.XP.rotationDegrees(angle),
                            null
                    ));
                    RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
                    SkyHelper.drawPlanetWithLight(p.texture, new Vec3(232, 219, 176),
                            bufferBuilder, matrix4f, 3, 3 * 4, 100 * distance, false);
                }
            }

            // Render moons
            for (Planet p : planet.moons) {
                p.orbitPhase = Planets.getRotation(p, dayTime, 1);
                matrix4f = SkyHelper.setMatrixRot(poseStack, Triple.of(
                        Axis.YP.rotationDegrees(-90),
                        Axis.XP.rotationDegrees(p.orbitPhase),
                        Axis.ZP.rotationDegrees(1)
                ));
                RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
                SkyHelper.drawPlanetWithLight(p.texture, new Vec3(232, 219, 176),
                        bufferBuilder, matrix4f, 3, 3 * 4, 100, false);
            }

            // Render planet below
            matrix4f = SkyHelper.setMatrixRot(poseStack, Triple.of(
                    Axis.XP.rotationDegrees(180),
                    null,
                    null
            ));
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);

            Vec3 cameraPos = camera.getPosition();
            float y = (float)cameraPos.y();
            float posScale = -3000.0F + y * 6F;
            float scale = 50 * (0.2F - posScale / 10000.0F);
            float yScale = Math.max(scale, 4.0F);

            SkyHelper.drawPlanetWithLight(planet.texture, new Vec3(0, 177, 242),
                    bufferBuilder, matrix4f, yScale, yScale * 3, 30, false);
        }
    }

    @Override
    public boolean tickRain(ClientLevel level, int ticks, Camera camera) {
        return true;
    }

    @Override
    public boolean renderSnowAndRain(ClientLevel level, int ticks, float partialTick,
                                     LightTexture lightTexture, double camX, double camY, double camZ) {
        return true;
    }
}