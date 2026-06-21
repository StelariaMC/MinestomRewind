package net.minestom.server.instance;

import net.minestom.server.MinecraftServer;
import net.minestom.server.instance.block.Block;
import net.minestom.server.instance.block.BlockVariation;
import net.minestom.server.registry.Registries;
import net.minestom.server.utils.NamespaceID;
import net.minestom.server.utils.callback.OptionalCallback;
import net.minestom.server.utils.chunk.ChunkCallback;
import net.minestom.server.world.biomes.Biome;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jglrxavpok.hephaistos.mca.*;
import org.jglrxavpok.hephaistos.nbt.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

public class AnvilLoader implements IChunkLoader {
    private final static Logger LOGGER = LoggerFactory.getLogger(AnvilLoader.class);

    private final Map<String, RegionFile> alreadyLoaded = new ConcurrentHashMap<>();
    private final Path path;
    private final Path levelPath;
    private final Path regionPath;

    public AnvilLoader(@NotNull Path path) {
        this.path = path;
        this.levelPath = path.resolve("level.dat");
        this.regionPath = path.resolve("region");
    }

    public AnvilLoader(@NotNull String path) {
        this(Path.of(path));
    }

    public void loadInstance(@NotNull Instance instance) {
        if (!Files.exists(levelPath)) {
            return;
        }
        try (var reader = new NBTReader(Files.newInputStream(levelPath))) {
            final NBTCompound tag = (NBTCompound) reader.read();
            Files.copy(levelPath, path.resolve("level.dat_old"), StandardCopyOption.REPLACE_EXISTING);
            LOGGER.info("Loaded level.dat for instance {}", instance.getUniqueId());
        } catch (IOException | NBTException e) {
            MinecraftServer.getExceptionManager().handleException(e);
        }
    }

    public void saveInstance(@NotNull Instance instance) {
        LOGGER.info("Instance saving to level.dat is not supported in this version");
    }

    @Override
    public boolean loadChunk(@NotNull Instance instance, int chunkX, int chunkZ, @Nullable ChunkCallback callback) {
        LOGGER.debug("Attempt loading at {} {}", chunkX, chunkZ);
        if (!Files.exists(path)) {
            return false;
        }
        try {
            return loadMCA(instance, chunkX, chunkZ, callback);
        } catch (Exception e) {
            MinecraftServer.getExceptionManager().handleException(e);
            return false;
        }
    }

    private boolean loadMCA(Instance instance, int chunkX, int chunkZ, @Nullable ChunkCallback callback)
            throws IOException, AnvilException {
        final RegionFile mcaFile = getMCAFile(chunkX, chunkZ);
        if (mcaFile == null)
            return false;

        final ChunkColumn fileChunk = readChunkColumn(mcaFile, chunkX, chunkZ);
        if (fileChunk == null)
            return false;

        Biome[] biomes = new Biome[Chunk.BIOME_COUNT];
        Arrays.fill(biomes, Biome.PLAINS);
        Chunk chunk = new DynamicChunk(biomes, chunkX, chunkZ, instance.getDimensionType().getHasSky());

        loadBiomes(chunk, fileChunk);
        loadBlocks(chunk, fileChunk);
        loadTileEntities(chunk, fileChunk);

        // Column may have been created via raw read (not in RegionFile cache)
        try {
            mcaFile.forget(fileChunk);
        } catch (IllegalArgumentException ignored) {
        }

        OptionalCallback.execute(callback, chunk);
        return true;
    }

    @Nullable
    private ChunkColumn readChunkColumn(@NotNull RegionFile mcaFile, int chunkX, int chunkZ)
            throws IOException {
        try {
            return mcaFile.getChunk(chunkX, chunkZ);
        } catch (AnvilException e) {
            if (e.getMessage() != null && e.getMessage().contains("DataVersion")) {
                return readChunkColumnRaw(chunkX, chunkZ);
            }
            return null;
        }
    }

    @Nullable
    private ChunkColumn readChunkColumnRaw(int chunkX, int chunkZ) throws IOException {
        final int regionX = CoordinatesKt.chunkToRegion(chunkX);
        final int regionZ = CoordinatesKt.chunkToRegion(chunkZ);
        final String fileName = RegionFile.Companion.createFileName(regionX, regionZ);
        final Path filePath = this.regionPath.resolve(fileName);

        if (!Files.exists(filePath)) {
            return null;
        }

        final int localX = chunkX & 31;
        final int localZ = chunkZ & 31;
        final int index = localX + localZ * 32;

        try (RandomAccessFile raf = new RandomAccessFile(filePath.toFile(), "r")) {
            // Read location entry (matches RegionFile.readColumn layout)
            raf.seek(index * 4L);
            int location = raf.readInt();
            int sectorOffset = location >>> 8;
            int sectorCount = location & 0xFF;

            if (sectorOffset == 0 || sectorCount == 0) {
                return null;
            }

            // Read chunk data packet (standard MCA format)
            //   4 bytes: total length (including the compression type byte)
            //   1 byte:  compression type (1=gzip, 2=zlib, 3=uncompressed)
            //   length-1 bytes: compressed data
            raf.seek(sectorOffset * 4096L);
            int totalLength = raf.readInt();
            int compressionScheme = raf.readByte() & 0xFF;
            int compressedSize = totalLength - 1;

            byte[] compressedData = new byte[compressedSize];
            raf.readFully(compressedData);

            // Decompress
            byte[] data;
            switch (compressionScheme) {
                case 1: // Gzip
                    data = decompress(new ByteArrayInputStream(compressedData), true);
                    break;
                case 2: // Zlib
                    data = decompress(new ByteArrayInputStream(compressedData), false);
                    break;
                case 3: // Uncompressed
                    data = compressedData;
                    break;
                default:
                    throw new IOException("Unknown compression scheme: " + compressionScheme);
            }

            // Parse NBT
            try (ByteArrayInputStream bais = new ByteArrayInputStream(data)) {
                NBTCompound nbt = (NBTCompound) new NBTReader(bais, false).read();

                // Pre-1.13 chunks lack DataVersion and Status; add defaults
                if (nbt.getInt("DataVersion") == null) {
                    nbt.setInt("DataVersion", 1343);
                }

                NBTCompound level = nbt.getCompound("Level");
                if (level != null && level.getString("Status") == null) {
                    // Use "spawn" (ordinal 10 < Heightmaps=11) so the constructor
                    // skips reading the Heightmaps compound which pre-1.13 chunks lack.
                    level.setString("Status", "spawn");
                }

                ChunkColumn column = new ChunkColumn(nbt);

                // Old-format sections (Blocks/Data byte arrays) are not parsed
                // by ChunkSection; populate them manually.
                if (level != null) {
                    populateOldBlocks(column, level);
                }

                return column;
            } catch (NBTException | AnvilException e) {
                LOGGER.warn("Failed to parse chunk at {} {}: {}", chunkX, chunkZ, e.getMessage());
                return null;
            }
        }
    }

    private void populateOldBlocks(ChunkColumn column, NBTCompound level) {
        NBTList<NBTCompound> sections = level.getList("Sections");
        if (sections == null) return;

        for (int i = 0; i < sections.getLength(); i++) {
            NBTCompound section = sections.get(i);
            Byte yByte = section.getByte("Y");
            if (yByte == null) continue;
            int sectionY = yByte & 0xFF;

            byte[] blocks = section.getByteArray("Blocks");
            if (blocks == null) continue;

            byte[] data = section.getByteArray("Data");
            byte[] add = section.getByteArray("Add");

            for (int x = 0; x < Chunk.CHUNK_SECTION_SIZE; x++) {
                for (int z = 0; z < Chunk.CHUNK_SECTION_SIZE; z++) {
                    for (int y = 0; y < Chunk.CHUNK_SECTION_SIZE; y++) {
                        int index = (y * Chunk.CHUNK_SECTION_SIZE + z) * Chunk.CHUNK_SECTION_SIZE + x;
                        int blockId = blocks[index] & 0xFF;
                        if (add != null) {
                            int addNibble = (add[index >> 1] >> ((index & 1) << 2)) & 0xF;
                            blockId |= (addNibble << 8);
                        }

                        int meta = 0;
                        if (data != null) {
                            meta = (data[index >> 1] >> ((index & 1) << 2)) & 0xF;
                        }

                        short stateId = (short) ((blockId << 4) | meta);
                        if (stateId == 0) continue;

                        Block block = Block.fromStateId(stateId);
                        if (block == null) continue;
                        String blockName = block.getName();
                        if (blockName.equals("minecraft:air")) continue;

                        Map<String, String> properties;
                        if (block.getVariations() != null) {
                            properties = getPropertiesFromBlock(block, (byte) meta);
                        } else {
                            properties = (meta != 0)
                                    ? Collections.singletonMap("_meta", String.valueOf(meta))
                                    : Collections.emptyMap();
                        }
                        column.setBlockState(x, y + sectionY * 16, z,
                                new BlockState(blockName, properties));
                    }
                }
            }
        }
    }

    private byte[] decompress(InputStream input, boolean gzip) throws IOException {
        try (InputStream decompressed = gzip
                ? new GZIPInputStream(input)
                : new InflaterInputStream(input)) {
            return decompressed.readAllBytes();
        }
    }

    private void loadBiomes(Chunk chunk, ChunkColumn fileChunk) {
        Biome[] biomes = chunk.getBiomes();
        for (int z = 0; z < Chunk.CHUNK_SIZE_Z; z++) {
            for (int x = 0; x < Chunk.CHUNK_SIZE_X; x++) {
                int biomeId = fileChunk.getBiome(x, 0, z);
                Biome biome = MinecraftServer.getBiomeManager().getById(biomeId);
                if (biome != null) {
                    biomes[z * Chunk.CHUNK_SIZE_X + x] = biome;
                }
            }
        }
    }

    private void loadBlocks(Chunk chunk, ChunkColumn fileChunk) {
        ChunkSection[] sections = fileChunk.getSections();
        for (int i = 0; i < sections.length; i++) {
            ChunkSection section = sections[i];
            if (section == null || section.getEmpty())
                continue;
            final int yOffset = Chunk.CHUNK_SECTION_SIZE * i;
            for (int x = 0; x < Chunk.CHUNK_SECTION_SIZE; x++) {
                for (int z = 0; z < Chunk.CHUNK_SECTION_SIZE; z++) {
                    for (int y = 0; y < Chunk.CHUNK_SECTION_SIZE; y++) {
                        try {
                            final BlockState blockState = section.get(x, y, z);
                            final String blockName = blockState.getName();
                            if (blockName.equals("minecraft:air"))
                                continue;

                            short blockStateId = getBlockStateId(blockName, blockState.getProperties());
                            if (blockStateId != 0) {
                                chunk.UNSAFE_setBlock(x, y + yOffset, z, blockStateId, (short) 0, null, false);
                            }
                        } catch (Exception e) {
                            MinecraftServer.getExceptionManager().handleException(e);
                        }
                    }
                }
            }
        }
    }

    private short getBlockStateId(String blockName, Map<String, String> properties) {
        Block block = Registries.getBlock(blockName);
        if (block == Block.AIR && !blockName.equals("minecraft:air"))
            return 0;

        byte metadata = 0;
        if (properties != null && !properties.isEmpty()) {
            metadata = getMetadataFromProperties(block, properties);
        }
        return block.toStateId(metadata);
    }

    private byte getMetadataFromProperties(Block block, Map<String, String> properties) {
        if (properties != null && !properties.isEmpty()) {
            // Direct metadata encoding for blocks without variations
            String metaStr = properties.get("_meta");
            if (metaStr != null) {
                return Byte.parseByte(metaStr);
            }

            if (block.getVariations() != null) {
                for (BlockVariation variation : block.getVariations()) {
                    String displayName = variation.getDisplayName().toLowerCase(Locale.ROOT).replace(' ', '_');
                    for (var prop : properties.entrySet()) {
                        if (prop.getValue().equals(displayName)) {
                            return variation.getMetadata();
                        }
                    }
                }
            }
        }
        return 0;
    }

    private void loadTileEntities(Chunk chunk, ChunkColumn fileChunk) {
        // Tile entity loading is not supported in this version
    }

    @Override
    public void saveChunk(@NotNull Chunk chunk, @Nullable Runnable callback) {
        final int chunkX = chunk.getChunkX();
        final int chunkZ = chunk.getChunkZ();

        RegionFile mcaFile;
        synchronized (alreadyLoaded) {
            mcaFile = getOrCreateMCAFile(chunkX, chunkZ);
            if (mcaFile == null) {
                OptionalCallback.execute(callback);
                return;
            }
        }

        ChunkColumn column;
        try {
            column = mcaFile.getOrCreateChunk(chunkX, chunkZ);
        } catch (AnvilException | IOException e) {
            LOGGER.error("Failed to save chunk " + chunkX + ", " + chunkZ, e);
            MinecraftServer.getExceptionManager().handleException(e);
            OptionalCallback.execute(callback);
            return;
        }

        save(chunk, column);

        try {
            LOGGER.debug("Attempt saving at {} {}", chunkX, chunkZ);
            mcaFile.writeColumn(column);
            mcaFile.forget(column);
        } catch (IOException e) {
            LOGGER.error("Failed to save chunk " + chunkX + ", " + chunkZ, e);
            MinecraftServer.getExceptionManager().handleException(e);
        }

        OptionalCallback.execute(callback);
    }

    private void save(Chunk chunk, ChunkColumn chunkColumn) {
        chunkColumn.setVersion(SupportedVersion.Companion.getLatest());
        chunkColumn.setGenerationStatus(ChunkColumn.GenerationStatus.Full);

        for (int x = 0; x < Chunk.CHUNK_SIZE_X; x++) {
            for (int z = 0; z < Chunk.CHUNK_SIZE_Z; z++) {
                for (int y = 0; y < Chunk.CHUNK_SIZE_Y; y++) {
                    final short blockStateId = chunk.getBlockStateId(x, y, z);
                    if (blockStateId == 0)
                        continue;

                    Block block = Block.fromStateId(blockStateId);
                    byte metadata = Block.toMetadata(blockStateId);

                    Map<String, String> properties = getPropertiesFromBlock(block, metadata);
                    chunkColumn.setBlockState(x, y, z, new BlockState(block.getName(), properties));
                }
            }
        }
    }

    private Map<String, String> getPropertiesFromBlock(Block block, byte metadata) {
        if (block.getVariations() != null) {
            BlockVariation variation = block.getVariation(metadata);
            if (variation != null) {
                String displayName = variation.getDisplayName().toLowerCase(Locale.ROOT).replace(' ', '_');
                String blockName = block.getName().replace("minecraft:", "");
                if (!displayName.equals(blockName)) {
                    return Collections.singletonMap("variant", displayName);
                }
            }
            return Collections.emptyMap();
        }
        // Blocks without variations: encode metadata directly
        return (metadata != 0)
                ? Collections.singletonMap("_meta", String.valueOf(metadata))
                : Collections.emptyMap();
    }

    @Nullable
    private RegionFile getMCAFile(int chunkX, int chunkZ) {
        final int regionX = CoordinatesKt.chunkToRegion(chunkX);
        final int regionZ = CoordinatesKt.chunkToRegion(chunkZ);
        return alreadyLoaded.computeIfAbsent(RegionFile.Companion.createFileName(regionX, regionZ), n -> {
            try {
                final Path regionPath = this.regionPath.resolve(n);
                if (!Files.exists(regionPath)) {
                    return null;
                }
                return new RegionFile(new RandomAccessFile(regionPath.toFile(), "rw"), regionX, regionZ);
            } catch (IOException | AnvilException e) {
                MinecraftServer.getExceptionManager().handleException(e);
                return null;
            }
        });
    }

    @Nullable
    private RegionFile getOrCreateMCAFile(int chunkX, int chunkZ) {
        final int regionX = CoordinatesKt.chunkToRegion(chunkX);
        final int regionZ = CoordinatesKt.chunkToRegion(chunkZ);
        final String fileName = RegionFile.Companion.createFileName(regionX, regionZ);

        return alreadyLoaded.computeIfAbsent(fileName, n -> {
            try {
                File regionFile = new File(regionPath.toFile(), n);
                if (!regionFile.exists()) {
                    if (!regionFile.getParentFile().exists()) {
                        regionFile.getParentFile().mkdirs();
                    }
                    regionFile.createNewFile();
                }
                return new RegionFile(new RandomAccessFile(regionFile, "rw"), regionX, regionZ);
            } catch (AnvilException | IOException e) {
                LOGGER.error("Failed to create region file for chunk " + chunkX + ", " + chunkZ, e);
                MinecraftServer.getExceptionManager().handleException(e);
                return null;
            }
        });
    }

    @Override
    public boolean supportsParallelLoading() {
        return true;
    }

    @Override
    public boolean supportsParallelSaving() {
        return true;
    }
}
