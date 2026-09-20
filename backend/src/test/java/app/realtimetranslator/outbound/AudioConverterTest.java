package app.realtimetranslator.outbound;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.*;

class AudioConverterTest {

    @Test
    void addWavHeader_producesCorrectRiffHeader() {
        byte[] pcm = new byte[100];
        byte[] wav = AudioConverter.addWavHeader(pcm, 16000, 1, 16);

        // Total should be 44 header + 100 data
        assertEquals(144, wav.length);

        // RIFF marker
        assertEquals('R', wav[0]);
        assertEquals('I', wav[1]);
        assertEquals('F', wav[2]);
        assertEquals('F', wav[3]);

        // File size minus 8 bytes (RIFF + size field)
        ByteBuffer buf = ByteBuffer.wrap(wav, 4, 4).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(136, buf.getInt()); // 36 + dataSize

        // WAVE marker
        assertEquals('W', wav[8]);
        assertEquals('A', wav[9]);
        assertEquals('V', wav[10]);
        assertEquals('E', wav[11]);
    }

    @Test
    void addWavHeader_producesCorrectFmtChunk() {
        byte[] pcm = new byte[100];
        byte[] wav = AudioConverter.addWavHeader(pcm, 16000, 1, 16);

        ByteBuffer buf = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);

        // fmt chunk starts at byte 12
        assertEquals('f', wav[12]);
        assertEquals('m', wav[13]);
        assertEquals('t', wav[14]);
        assertEquals(' ', wav[15]);

        buf.position(16);
        assertEquals(16, buf.getInt());       // chunk size
        assertEquals(1, buf.getShort());       // PCM format
        assertEquals(1, buf.getShort());       // channels
        assertEquals(16000, buf.getInt());     // sample rate
        assertEquals(32000, buf.getInt());     // byte rate (16000 * 1 * 16/8)
        assertEquals(2, buf.getShort());       // block align (1 * 16/8)
        assertEquals(16, buf.getShort());      // bit depth
    }

    @Test
    void addWavHeader_producesCorrectDataChunk() {
        byte[] pcm = {0x01, 0x02, 0x03, 0x04};
        byte[] wav = AudioConverter.addWavHeader(pcm, 16000, 1, 16);

        // data chunk starts at byte 36
        assertEquals('d', wav[36]);
        assertEquals('a', wav[37]);
        assertEquals('t', wav[38]);
        assertEquals('a', wav[39]);

        ByteBuffer buf = ByteBuffer.wrap(wav, 40, 4).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(4, buf.getInt()); // data size

        // PCM data follows immediately
        assertEquals(0x01, wav[44]);
        assertEquals(0x02, wav[45]);
        assertEquals(0x03, wav[46]);
        assertEquals(0x04, wav[47]);
    }

    @Test
    void addWavHeader_emptyPcmData() {
        byte[] pcm = new byte[0];
        byte[] wav = AudioConverter.addWavHeader(pcm, 16000, 1, 16);

        assertEquals(44, wav.length);

        ByteBuffer buf = ByteBuffer.wrap(wav, 40, 4).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(0, buf.getInt()); // data size is 0
    }

    @Test
    void addWavHeader_stereoSettings() {
        byte[] pcm = new byte[200];
        byte[] wav = AudioConverter.addWavHeader(pcm, 44100, 2, 16);

        ByteBuffer buf = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
        buf.position(22);
        assertEquals(2, buf.getShort());       // 2 channels
        assertEquals(44100, buf.getInt());     // sample rate
        assertEquals(176400, buf.getInt());    // byte rate (44100 * 2 * 16/8)
        assertEquals(4, buf.getShort());       // block align (2 * 16/8)
    }
}
