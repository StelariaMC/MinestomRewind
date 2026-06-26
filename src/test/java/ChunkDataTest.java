import java.util.zip.Deflater;
import java.util.zip.Inflater;
import java.util.Arrays;

public class ChunkDataTest {
    public static void main(String[] args) throws Exception {
        // Simulate section data: bits=4, paletteSize=4, 4 entries, longCount=256, 256 longs
        // + block light 2048 + sky light 2048
        
        byte[] sectionData = new byte[1 + 1 + 4 + 2 + 2048 + 2048 + 2048];
        // 1 byte bits + 1 byte VarInt(4) paletteSize + 4 palette entries + 2 byte VarInt(256) + 256*8 longs
        
        int pos = 0;
        sectionData[pos++] = 4; // bitsPerBlock
        
        // paletteSize VarInt
        sectionData[pos++] = 4; // paletteSize=4
        
        // palette entries
        for (int i = 0; i < 4; i++) {
            sectionData[pos++] = (byte) i; // simple entries
        }
        
        // longCount VarInt (256)
        // 256 as VarInt: 256 = 0x100 = 0x80 0x02
        sectionData[pos++] = (byte) 0x80; 
        sectionData[pos++] = 0x02;
        
        // block data (256 longs = 2048 bytes)
        for (int i = 0; i < 256; i++) {
            pos += 8; // skip, leave zeros
        }
        
        // block light (2048 bytes) - all 0xFF
        Arrays.fill(sectionData, pos, pos + 2048, (byte) 0xFF);
        pos += 2048;
        
        // sky light (2048 bytes) - all 0xFF
        Arrays.fill(sectionData, pos, pos + 2048, (byte) 0xFF);
        pos += 2048;
        
        int sectionSize = pos;
        System.out.println("Section data size: " + sectionSize);
        
        // Add biomes (256 bytes)
        byte[] fullData = new byte[sectionSize + 256];
        System.arraycopy(sectionData, 0, fullData, 0, sectionSize);
        // biomes already zero
        
        int fullSize = fullData.length;
        System.out.println("Full data size: " + fullSize);
        
        // Compress with Deflater level 3
        Deflater deflater = new Deflater(3);
        deflater.setInput(fullData);
        deflater.finish();
        byte[] compressedBuf = new byte[fullSize * 2];
        int compressedSize = deflater.deflate(compressedBuf);
        deflater.reset();
        System.out.println("Compressed size: " + compressedSize + " (ratio " + (compressedSize * 100 / fullSize) + "%)");
        
        // Decompress
        Inflater inflater = new Inflater();
        inflater.setInput(compressedBuf, 0, compressedSize);
        byte[] decompressed = new byte[fullSize];
        int decompressedSize = inflater.inflate(decompressed);
        inflater.end();
        
        System.out.println("Decompressed size: " + decompressedSize);
        
        if (decompressedSize == fullSize) {
            boolean match = Arrays.equals(fullData, 0, fullSize, decompressed, 0, decompressedSize);
            System.out.println("Round-trip: " + (match ? "PASS" : "FAIL"));
            if (!match) {
                for (int i = 0; i < fullSize; i++) {
                    if (fullData[i] != decompressed[i]) {
                        System.out.println("First diff at byte " + i + ": orig=0x" + Integer.toHexString(fullData[i] & 0xFF) + " decomp=0x" + Integer.toHexString(decompressed[i] & 0xFF));
                        break;
                    }
                }
            }
        } else {
            System.out.println("FAIL: size mismatch");
        }
    }
}
