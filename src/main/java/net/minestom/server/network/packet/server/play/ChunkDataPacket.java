package net.minestom.server.network.packet.server.play;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import net.minestom.server.instance.palette.PaletteStorage;
import net.minestom.server.instance.palette.Section;
import net.minestom.server.network.packet.server.ServerPacket;
import net.minestom.server.network.packet.server.ServerPacketIdentifier;
import net.minestom.server.utils.Utils;
import net.minestom.server.utils.binary.BinaryWriter;
import net.minestom.server.utils.cache.CacheablePacket;
import net.minestom.server.utils.cache.TemporaryCache;
import net.minestom.server.utils.cache.TimedBuffer;
import net.minestom.server.world.biomes.Biome;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.UUID;

public class ChunkDataPacket implements ServerPacket, CacheablePacket {
    private static final TemporaryCache<TimedBuffer> CACHE = new TemporaryCache<>(30000L);

    public boolean fullChunk;
    public boolean unloadChunk;
    public Biome[] biomes;
    public int chunkX, chunkZ;

    public PaletteStorage paletteStorage;
    public boolean skylight;

    public int[] sections;

    private static final byte CHUNK_SECTION_COUNT = 16;
    private static final int MAX_BUFFER_SIZE = 200_000;

    // Cacheable data
    private final UUID identifier;
    private final long timestamp;

    public ChunkDataPacket(@Nullable UUID identifier, long timestamp) {
        this.identifier = identifier;
        this.timestamp = timestamp;
    }

    @Override
    public void write(@NotNull BinaryWriter writer) {
        writer.writeInt(chunkX);
        writer.writeInt(chunkZ);
        writer.writeBoolean(fullChunk);

        int mask = 0;

        if (unloadChunk) {
            writer.writeVarInt(mask);
            writer.writeVarInt(0);
            return;
        }

        Section[] includedSections = new Section[CHUNK_SECTION_COUNT];
        int includedCount = 0;

        for (byte i = 0; i < CHUNK_SECTION_COUNT; i++) {
            if (fullChunk || sections == null || (sections.length == CHUNK_SECTION_COUNT && sections[i] != 0)) {
                final Section section = paletteStorage.getSections()[i];
                if (section != null && section.getBlocks().length > 0) {
                    mask |= 1 << i;
                    includedSections[includedCount++] = section;
                }
            }
        }

        writer.writeVarInt(mask);

        ByteBuf data = Unpooled.buffer(MAX_BUFFER_SIZE);

        byte[] lightBytes = new byte[2048];
        Arrays.fill(lightBytes, (byte) 0xFF);

        for (int i = 0; i < includedCount; i++) {
            writeSection(data, includedSections[i], lightBytes);
        }

        if (fullChunk || sections == null) {
            data.writeZero(256);
        }

        writer.writeVarInt(data.writerIndex());
        writer.getBuffer().writeBytes(data);
        data.release();

        writer.writeVarInt(0);
    }

    private void writeSection(ByteBuf data, Section section, byte[] lightBytes) {
        short[] blocks = section.getBlocks();

        if (blocks.length == 0) {
            data.writeByte(4);
            Utils.writeVarIntBuf(data, 1);
            Utils.writeVarIntBuf(data, 0);
            Utils.writeVarIntBuf(data, 256);
            for (int i = 0; i < 256; i++) {
                data.writeLong(0L);
            }
            data.writeBytes(lightBytes);
            if (skylight) {
                data.writeBytes(lightBytes);
            }
            return;
        }

        Int2IntOpenHashMap blockToIndex = new Int2IntOpenHashMap();
        blockToIndex.defaultReturnValue(-1);
        int[] palette = new int[4096];
        int paletteSize = 0;

        blockToIndex.put(0, paletteSize);
        palette[paletteSize++] = 0;

        int[] indexMap = new int[4096];

        for (int i = 0; i < 4096; i++) {
            int blockId = blocks[i] & 0xFFFF;
            int idx = blockToIndex.get(blockId);
            if (idx == -1) {
                idx = paletteSize;
                blockToIndex.put(blockId, idx);
                palette[paletteSize++] = blockId;
            }
            indexMap[i] = idx;
        }

        int bitsPerBlock = paletteSize <= 16 ? 4 : 8;
        int blocksPerLong = 64 / bitsPerBlock;
        int longCount = 4096 / blocksPerLong;

        data.writeByte(bitsPerBlock);
        Utils.writeVarIntBuf(data, paletteSize);
        for (int i = 0; i < paletteSize; i++) {
            Utils.writeVarIntBuf(data, palette[i]);
        }
        Utils.writeVarIntBuf(data, longCount);

        if (bitsPerBlock == 4) {
            for (int i = 0; i < longCount; i++) {
                long val = 0;
                for (int j = 0; j < 16; j++) {
                    val |= ((long) indexMap[i * 16 + j] & 0xF) << (j * 4);
                }
                data.writeLong(val);
            }
        } else {
            for (int i = 0; i < longCount; i++) {
                long val = 0;
                for (int j = 0; j < 8; j++) {
                    val |= ((long) indexMap[i * 8 + j] & 0xFF) << (j * 8);
                }
                data.writeLong(val);
            }
        }

        data.writeBytes(lightBytes);
        if (skylight) {
            data.writeBytes(lightBytes);
        }
    }

    @Override
    public int getId() {
        return ServerPacketIdentifier.CHUNK_DATA;
    }

    @NotNull
    @Override
    public TemporaryCache<TimedBuffer> getCache() {
        return CACHE;
    }

    @Override
    public UUID getIdentifier() {
        return identifier;
    }

    @Override
    public long getTimestamp() {
        return timestamp;
    }
}
