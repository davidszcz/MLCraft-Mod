package net.mlcraft.elevationmod.world.gen.chunk;

import com.google.common.annotations.VisibleForTesting;
import net.mlcraft.elevationmod.MLCraft;
import net.mlcraft.elevationmod.accessor.AMISurfaceBuilderAccessor;
import net.mlcraft.elevationmod.world.gen.MLCraftMapInfo;
import net.mlcraft.elevationmod.world.gen.NamespacedMapImage;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.SharedConstants;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Util;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.random.CheckedRandom;
import net.minecraft.util.math.random.ChunkRandom;
import net.minecraft.util.math.random.RandomSeed;
import net.minecraft.world.ChunkRegion;
import net.minecraft.world.HeightLimitView;
import net.minecraft.world.Heightmap;
import net.minecraft.world.SpawnHelper;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.GenerationSettings;
import net.minecraft.world.biome.source.BiomeAccess;
import net.minecraft.world.biome.source.BiomeCoords;
import net.minecraft.world.biome.source.BiomeSource;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.ProtoChunk;
import net.minecraft.world.gen.GenerationStep;
import net.minecraft.world.gen.HeightContext;
import net.minecraft.world.gen.StructureAccessor;
import net.minecraft.world.gen.StructureWeightSampler;
import net.minecraft.world.gen.carver.CarverContext;
import net.minecraft.world.gen.carver.CarvingMask;
import net.minecraft.world.gen.carver.ConfiguredCarver;
import net.minecraft.world.gen.chunk.*;
import net.minecraft.world.gen.noise.NoiseConfig;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Stream;

public class MLCraftChunkGenerator extends ChunkGenerator {
    private static final BlockState AIR = Blocks.AIR.getDefaultState();
    private final NamespacedMapImage heightmap;
    private final NamespacedMapImage aquifer;
    private final NamespacedMapImage roof;
    private final int seaLevel;
    private final int startingY;
    private final int ceilingHeight;
    private final RegistryEntry<ChunkGeneratorSettings> settings;
    private final float verticalScale;
    private final float horizontalScale;
    private final RegistryEntry<MLCraftMapInfo> mapInfo;

    public MLCraftChunkGenerator(
            RegistryEntry<MLCraftMapInfo> ami, String aquiferPath, String roofPath,
            BiomeSource biomeSource, RegistryEntry<ChunkGeneratorSettings> settings,
            int ceilingHeight
    ) {
        super(biomeSource);
        this.mapInfo = ami;
        this.seaLevel = settings.value().seaLevel();
        this.startingY = ami.value().startingY();
        this.ceilingHeight = ceilingHeight;
        this.verticalScale = ami.value().verticalScale();
        if (this.verticalScale != 1) MLCraft.LOGGER.warn("using non-default vertical scale for a dimension! this feature is in alpha, expect weird generation!");
        this.horizontalScale = ami.value().horizontalScale();
        this.heightmap = MLCraft.getOrCreateMap(ami.value().heightmap(), NamespacedMapImage.Type.GRAYSCALE);
        this.aquifer = !aquiferPath.isEmpty() ? MLCraft.getOrCreateMap(aquiferPath, NamespacedMapImage.Type.GRAYSCALE) :null;
        this.roof = !roofPath.isEmpty() ? MLCraft.getOrCreateMap(roofPath, NamespacedMapImage.Type.GRAYSCALE) : null;
        this.settings = settings;
    }

    public void findMaps(MinecraftServer server, String levelName) throws IOException {
        this.heightmap.initialize(server);
        MLCraft.LOGGER.info("found elevation data for dimension " + levelName + " in a " + this.heightmap.getWidth() + "x" + this.heightmap.getHeight() + " map: " + getPath());
        if (!this.getAquiferPath().isEmpty()) {
            this.aquifer.initialize(server);
            MLCraft.LOGGER.info("found aquifer data for dimension " + levelName + " in a " + this.aquifer.getWidth() + "x" + this.aquifer.getHeight() + " map: " + getAquiferPath());
        } else {
            MLCraft.LOGGER.warn("couldn't find aquifer for dimension " + levelName + ", defaulting to sea level!");
        }
        if (!Objects.equals(this.getRoofPath(), "")) {
            this.roof.initialize(server);
            MLCraft.LOGGER.info("found roof data for dimension " + levelName + " in a " + this.roof.getWidth() + "x" + this.roof.getHeight() + " map: " + getRoofPath());
        }
    }

    public static final Codec<MLCraftChunkGenerator> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            MLCraftMapInfo.REGISTRY_CODEC.fieldOf("map_info").forGetter(MLCraftChunkGenerator::getMapInfo),
            Codec.STRING.optionalFieldOf("aquifer", "").forGetter(MLCraftChunkGenerator::getAquiferPath),
            Codec.STRING.optionalFieldOf("roof", "").forGetter(MLCraftChunkGenerator::getRoofPath),
            BiomeSource.CODEC.fieldOf("biome_source").forGetter(MLCraftChunkGenerator::getBiomeSource),
            ChunkGeneratorSettings.REGISTRY_CODEC.fieldOf("settings").forGetter(MLCraftChunkGenerator::getSettings),
            Codec.INT.optionalFieldOf("ceiling_height", Integer.MIN_VALUE).forGetter(MLCraftChunkGenerator::getCeilingHeight)
    ).apply(instance, instance.stable(MLCraftChunkGenerator::new)));

    private int getCeilingHeight() {return this.ceilingHeight;}
    private RegistryEntry<MLCraftMapInfo> getMapInfo() {return this.mapInfo;}
    private String getRoofPath() {return this.roof == null ? "" : (this.roof.getPath());}
    private String getAquiferPath() {return this.aquifer == null ? "" : (this.aquifer.getPath());}

    private double getFromMap(int x, int z, @NotNull NamespacedMapImage nmi) {
        float xR = (x/horizontalScale);
        float zR = (z/horizontalScale);
        xR += nmi.getWidth()  / 2f; // these will always be even numbers
        zR += nmi.getHeight() / 2f;
        if (xR < 0 || zR < 0 || xR >= nmi.getWidth() || zR >= nmi.getHeight()) return this.getMinimumY()-1;
        int truncatedX = (int)Math.floor(xR);
        int truncatedZ = (int)Math.floor(zR);
        double d = nmi.lerp(truncatedX, xR-truncatedX, truncatedZ, zR-truncatedZ);
        return this.verticalScale*d+ startingY;
    }

    public RegistryEntry<ChunkGeneratorSettings> getSettings() {return this.settings;}

    private String getPath() {return this.heightmap.getPath();}
    @Override
    protected Codec<? extends ChunkGenerator> getCodec() {
        return CODEC;
    }

    @Override
    @SuppressWarnings("deprecation")
    public void carve(ChunkRegion chunkRegion, long seed, NoiseConfig noiseConfig, BiomeAccess biomeAccess, StructureAccessor structureAccessor, Chunk chunk2, GenerationStep.Carver carverStep) {

        BiomeAccess biomeAccess2 = biomeAccess.withSource((biomeX, biomeY, biomeZ) -> this.biomeSource.getBiome(biomeX, biomeY, biomeZ, noiseConfig.getMultiNoiseSampler()));
        ChunkRandom chunkRandom = new ChunkRandom(new CheckedRandom(RandomSeed.getSeed()));
        int i = 8;
        ChunkPos chunkPos = chunk2.getPos();
        ChunkNoiseSampler chunkNoiseSampler = chunk2.getOrCreateChunkNoiseSampler(chunk -> this.createChunkNoiseSampler(chunk, structureAccessor, Blender.getBlender(chunkRegion), noiseConfig));
        AquiferSampler aquiferSampler = chunkNoiseSampler.getAquiferSampler();
        CarverContext carverContext = new CarverContext(new NoiseChunkGenerator(this.biomeSource, this.settings),
                /*this is fine because the only thing the NCG is used for is like, the height limit or something*/
                chunkRegion.getRegistryManager(), chunk2.getHeightLimitView(), chunkNoiseSampler, noiseConfig, this.settings.value().surfaceRule());
        CarvingMask carvingMask = ((ProtoChunk)chunk2).getOrCreateCarvingMask(carverStep);
        for (int j = -i; j <= i; ++j) {
            for (int k = -i; k <= i; ++k) {
                ChunkPos chunkPos2 = new ChunkPos(chunkPos.x + j, chunkPos.z + k);
                Chunk chunk22 = chunkRegion.getChunk(chunkPos2.x, chunkPos2.z);
                RegistryEntry<Biome> biome = this.biomeSource.getBiome(BiomeCoords.fromBlock(chunkPos2.getStartX()), 0, BiomeCoords.fromBlock(chunkPos2.getStartZ()), noiseConfig.getMultiNoiseSampler());
                GenerationSettings generationSettings = chunk22.getOrCreateGenerationSettings(() -> this.getGenerationSettings(biome));
                Iterable<RegistryEntry<ConfiguredCarver<?>>> iterable = generationSettings.getCarversForStep(carverStep);
                int l = 0;
                for (RegistryEntry<ConfiguredCarver<?>> registryEntry : iterable) {
                    ConfiguredCarver<?> configuredCarver = registryEntry.value();
                    chunkRandom.setCarverSeed(seed + (long)l, chunkPos2.x, chunkPos2.z);
                    if (configuredCarver.shouldCarve(chunkRandom)) {
                        configuredCarver.carve(carverContext, chunk2, biomeAccess2::getBiome, chunkRandom, aquiferSampler, chunkPos2, carvingMask);
                    }
                    ++l;
                }
            }
        }
    }

    @Override
    public void buildSurface(ChunkRegion region, StructureAccessor structures, NoiseConfig noiseConfig, Chunk chunk) {
        if (SharedConstants.isOutsideGenerationArea(chunk.getPos())) {
            return;
        }
        HeightContext heightContext = new HeightContext(this, region);
        this.buildSurface(chunk, heightContext, noiseConfig, structures, region.getBiomeAccess(), region.getRegistryManager().get(RegistryKeys.BIOME), Blender.getBlender(region));
    }

    @VisibleForTesting
    public void buildSurface(Chunk chunk, HeightContext heightContext, NoiseConfig noiseConfig, StructureAccessor structureAccessor, BiomeAccess biomeAccess, Registry<Biome> biomeRegistry, Blender blender) {
        ChunkNoiseSampler chunkNoiseSampler = chunk.getOrCreateChunkNoiseSampler(chunk3 -> this.createChunkNoiseSampler(chunk3, structureAccessor, blender, noiseConfig));
        ChunkGeneratorSettings chunkGeneratorSettings = this.settings.value();
        ((AMISurfaceBuilderAccessor) noiseConfig.getSurfaceBuilder()).buildSurface(noiseConfig, biomeAccess, biomeRegistry, chunkGeneratorSettings.usesLegacyRandom(), heightContext, chunk, chunkNoiseSampler, chunkGeneratorSettings.surfaceRule(), this.mapInfo);
    }

    @Override
    public void populateEntities(ChunkRegion region) {
        ChunkPos chunkPos = region.getCenterPos();
        RegistryEntry<Biome> registryEntry = region.getBiome(chunkPos.getStartPos().withY(region.getTopY() - 1));
        ChunkRandom chunkRandom = new ChunkRandom(new CheckedRandom(RandomSeed.getSeed()));
        chunkRandom.setPopulationSeed(region.getSeed(), chunkPos.getStartX(), chunkPos.getStartZ());
        SpawnHelper.populateEntities(region, registryEntry, chunkPos, chunkRandom);
    }

    @Override
    public int getWorldHeight() {
        return this.settings.value().generationShapeConfig().height();
    }

    @Override
    public CompletableFuture<Chunk> populateNoise(Executor executor, Blender blender, NoiseConfig noiseConfig, StructureAccessor structureAccessor, Chunk chunk) {
        GenerationShapeConfig generationShapeConfig = this.settings.value().generationShapeConfig().trimHeight(chunk.getHeightLimitView());
        int k = MathHelper.floorDiv(generationShapeConfig.height(), generationShapeConfig.verticalSize());
        if (k <= 0) {
            return CompletableFuture.completedFuture(chunk);
        }
        int x = chunk.getPos().x << 4;
        int z = chunk.getPos().z << 4;
        float xR = (x/horizontalScale);
        float zR = (z/horizontalScale);
        xR += this.heightmap.getWidth()  / 2f; // these will always be even numbers
        zR += this.heightmap.getHeight() / 2f;
        int truncatedX = (int)Math.floor(xR);
        int truncatedZ = (int)Math.floor(zR);
        int minimumCellY = MathHelper.floorDiv(generationShapeConfig.minimumY(), generationShapeConfig.verticalCellBlockCount());
        int cellHeight = MathHelper.floorDiv(generationShapeConfig.height(), generationShapeConfig.verticalCellBlockCount());
        if (truncatedX < -16 || truncatedZ < -16 || truncatedX > this.heightmap.getWidth() || truncatedZ > this.heightmap.getHeight()) return CompletableFuture.completedFuture(chunk);
        this.heightmap.loadPixelsInRange(truncatedX, truncatedZ, true, MLCraft.GEN_RADIUS);
        if (this.aquifer != null) this.aquifer.loadPixelsInRange(truncatedX, truncatedZ, true, MLCraft.GEN_RADIUS);
        return CompletableFuture.supplyAsync(Util.debugSupplier("wgen_fill_noise", () -> this.populateNoise(chunk, structureAccessor, blender, noiseConfig, minimumCellY, cellHeight)), Util.getMainWorkerExecutor());
    }
    private Chunk populateNoise(Chunk chunk, StructureAccessor accessor, Blender blender, NoiseConfig noiseConfig, int minimumCellY, int cellHeight) {
        ChunkNoiseSampler chunkNoiseSampler = chunk.getOrCreateChunkNoiseSampler(chunk1 -> this.createChunkNoiseSampler(chunk, accessor, blender, noiseConfig));
        Heightmap oceanHeightmap = chunk.getHeightmap(Heightmap.Type.OCEAN_FLOOR_WG);
        Heightmap surfaceHeightmap = chunk.getHeightmap(Heightmap.Type.WORLD_SURFACE_WG);
        ChunkPos chunkPos = chunk.getPos();
        int i = chunkPos.getStartX();
        int j = chunkPos.getStartZ();
        AquiferSampler aquiferSampler = chunkNoiseSampler.getAquiferSampler();
        chunkNoiseSampler.sampleStartDensity();
        BlockPos.Mutable mutable = new BlockPos.Mutable();
        int minY = settings.value().generationShapeConfig().minimumY();
        int k = chunkNoiseSampler.getHorizontalCellBlockCount();
        int l = chunkNoiseSampler.getVerticalCellBlockCount();
        int m = 16 / k;
        int n = 16 / k;
        BlockState defaultBlock = this.settings.value().defaultBlock();
        BlockState defaultFluid = this.settings.value().defaultFluid();
        for (int o = 0; o < m; ++o) {
            chunkNoiseSampler.sampleEndDensity(o);
            for (int p = 0; p < n; ++p) {
                int q1 = chunk.countVerticalSections() - 1;
                ChunkSection chunkSection = chunk.getSection(q1);
                for (int q = cellHeight - 1; q >= 0; --q) {
                    chunkNoiseSampler.onSampledCellCorners(q, p);
                    for (int r = l - 1; r >= 0; --r) {
                        int s = (minimumCellY + q) * l + r;
                        int t = s & 0xF;
                        int u = chunk.getSectionIndex(s);
                        if (q1 != u) {
                            q1 = u;
                            chunkSection = chunk.getSection(u);
                        }
                        double d = (double) r / (double) l;
                        chunkNoiseSampler.interpolateY(s, d);
                        for (int v = 0; v < k; ++v) {
                            int w = i + o * k + v;
                            int x = w & 0xF;
                            double e = (double) v / (double) k;
                            chunkNoiseSampler.interpolateX(w, e);
                            for (int y = 0; y < k; ++y) {
                                int z = j + p * k + y;
                                int aa = z & 0xF;
                                double f = (double) y / (double) k;
                                chunkNoiseSampler.interpolateZ(z, f);
                                int blockX = chunkNoiseSampler.blockX();
                                int blockY = chunkNoiseSampler.blockY();
                                int blockZ = chunkNoiseSampler.blockZ();
                                mutable.set(blockX, blockY, blockZ);
                                int seaLevel = this.getSeaLevel(blockX, blockZ);
                                int elevation = (int) Math.min(this.getFromMap(blockX, blockZ, this.heightmap), this.startingY + this.getWorldHeight());
                                if (blockY >= seaLevel && blockY >= elevation || elevation < this.getMinimumY()) continue;
                                int height = blockY - minY;
                                int maxHeight = elevation - minY;
                                double cave;
                                BlockState state;
                                if (maxHeight - height <= 10) {
                                    cave = noiseConfig.getNoiseRouter().initialDensityWithoutJaggedness().sample(chunkNoiseSampler);
                                    BlockState caveAir;
                                    if (elevation < seaLevel) {
                                        caveAir = defaultFluid;
                                    } else {
                                        caveAir = AIR;
                                    }
                                    if (blockY < elevation) {
                                        if (cave > 0) {
                                            state = defaultBlock;
                                        } else {
                                            state = caveAir;
                                        }
                                    } else if (blockY < seaLevel) {
                                        state = defaultFluid;
                                    }
                                    else {
                                        state = AIR;
                                    }
                                    chunk.setBlockState(mutable, state, false);
                                    surfaceHeightmap.trackUpdate(blockX & 0xF, blockY, blockZ & 0xF, state);
                                    oceanHeightmap.trackUpdate(blockX & 0xF, blockY, blockZ & 0xF, state);
                                } else {
                                    state = chunkNoiseSampler.sampleBlockState();
                                    if (state == null) {
                                        state = this.settings.value().defaultBlock();
                                    }
                                    if ((state == AIR || SharedConstants.isOutsideGenerationArea(chunk.getPos()))) continue;
                                    chunkSection.setBlockState(x, t, aa, state, false);
                                    oceanHeightmap.trackUpdate(x, s, aa, state);
                                    surfaceHeightmap.trackUpdate(x, s, aa, state);
                                }
                                if (!aquiferSampler.needsFluidTick() || state.getFluidState().isEmpty()) continue;
                                mutable.set(w, s, z);
                                chunk.markBlockForPostProcessing(mutable);
                            }
                        }
                    }
                }
            }
            chunkNoiseSampler.swapBuffers();
        }
        chunkNoiseSampler.stopInterpolation();
        return chunk;
    }

    @Override
    public int getSeaLevel() {
        return this.seaLevel;
    }
    public int getSeaLevel(int x, int z) {
        if (this.aquifer != null) {
            return (int) Math.min(Math.max(this.getFromMap(x, z, this.aquifer), this.seaLevel), this.startingY +this.getWorldHeight());
        }
        return seaLevel;
    }

    @Override
    public int getMinimumY() {
        return this.settings.value().generationShapeConfig().minimumY();
    }

    @Override
    public int getHeight(int x, int z, Heightmap.Type heightmap, HeightLimitView world, NoiseConfig noiseConfig) {
        return (int) (
//                (heightmap == Heightmap.Type.OCEAN_FLOOR_WG || heightmap == Heightmap.Type.OCEAN_FLOOR)
//                        ? this.getFromMap(x, z, this.heightmap) :
//                Math.max(this.seaLevel,
                this.getFromMap(x, z, this.heightmap)
//                )
        );
    }

    @Override
    public VerticalBlockSample getColumnSample(int x, int z, HeightLimitView world, NoiseConfig noiseConfig) {
        int elevation = (int) this.getFromMap(x, z, this.heightmap);
        int seaLevel = this.getSeaLevel(x, z);
        if (elevation < this.getMinimumY()) return new VerticalBlockSample(world.getBottomY(), new BlockState[]{Blocks.AIR.getDefaultState()});
        if (elevation < seaLevel) {
            return new VerticalBlockSample(
                    this.settings.value().generationShapeConfig().minimumY(),
                    Stream.concat(
                            Stream.generate(() -> this.settings.value().defaultBlock()).limit(elevation-this.getMinimumY()),
                            Stream.generate(() -> this.settings.value().defaultFluid()).limit(seaLevel - elevation-this.getMinimumY())
                    ).toArray(BlockState[]::new));
        }
        return new VerticalBlockSample(
                this.settings.value().generationShapeConfig().minimumY(),
                Stream.generate(() -> this.settings.value().defaultBlock()).limit(elevation-this.getMinimumY()+1).toArray(BlockState[]::new)

        );
    }

    @Override
    public void getDebugHudText(List<String> text, NoiseConfig noiseConfig, BlockPos pos) {
        text.add("[Map CG] elevation: "+ this.getFromMap(pos.getX(), pos.getZ(), this.heightmap));
    }

    private ChunkNoiseSampler createChunkNoiseSampler(Chunk chunk, StructureAccessor world, Blender blender, NoiseConfig noiseConfig) {
        return ChunkNoiseSampler.create(chunk, noiseConfig, StructureWeightSampler.createStructureWeightSampler(world, chunk.getPos()), this.settings.value(), this.createFluidLevelSampler(this.settings.value()), blender);
    }
    private AquiferSampler.FluidLevelSampler createFluidLevelSampler(ChunkGeneratorSettings settings) {
        AquiferSampler.FluidLevel fluidLevel = new AquiferSampler.FluidLevel(-54, Blocks.LAVA.getDefaultState());
        int i = settings.seaLevel();
        return (x, y, z) -> {
            if (y < Math.min(-54, i)) {
                return fluidLevel;
            }
//            else if (this.getFromMap(x, z, this.heightmap) < (this.aquifer == null ? this.seaLevel : this.getFromMap(x, z, this.aquifer))) {
            return new AquiferSampler.FluidLevel((this.aquifer == null ? this.seaLevel : ((int) this.getFromMap(x, z, this.aquifer))), settings.defaultFluid());
//            }
//            return fluidLevel3;
        };
    }
}