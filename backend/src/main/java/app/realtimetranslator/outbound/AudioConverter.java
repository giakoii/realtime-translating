package app.realtimetranslator.outbound;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Converts raw PCM data to WAV format by prepending the standard 44-byte
 * RIFF/WAV header. Whisper API requires a recognized audio container —
 * raw PCM is not accepted directly.
 */
public final class AudioConverter {

    private static final int WAV_HEADER_SIZE = 44;

    private AudioConverter() {}

    /**
     * Wraps raw PCM bytes in a WAV container.
     *
     * @param pcmData    raw PCM audio samples
     * @param sampleRate samples per second (typically 16000)
     * @param channels   number of audio channels (1 = mono)
     * @param bitDepth   bits per sample (typically 16)
     * @return complete WAV file as byte array
     */
    public static byte[] addWavHeader(byte[] pcmData, int sampleRate, int channels, int bitDepth) {
        int dataSize = pcmData.length;
        int byteRate = sampleRate * channels * bitDepth / 8;
        int blockAlign = channels * bitDepth / 8;

        ByteBuffer buffer = ByteBuffer.allocate(WAV_HEADER_SIZE + dataSize)
                .order(ByteOrder.LITTLE_ENDIAN);

        // RIFF header
        buffer.put("RIFF".getBytes());
        buffer.putInt(36 + dataSize);    // file size minus 8
        buffer.put("WAVE".getBytes());

        // fmt sub-chunk
        buffer.put("fmt ".getBytes());
        buffer.putInt(16);                // sub-chunk size (PCM)
        buffer.putShort((short) 1);       // audio format: PCM
        buffer.putShort((short) channels);
        buffer.putInt(sampleRate);
        buffer.putInt(byteRate);
        buffer.putShort((short) blockAlign);
        buffer.putShort((short) bitDepth);

        // data sub-chunk
        buffer.put("data".getBytes());
        buffer.putInt(dataSize);
        buffer.put(pcmData);

        return buffer.array();
    }
}
