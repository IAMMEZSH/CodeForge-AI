package codeforge.server;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;

/**
 * Decodes captured child-process output. Compilers and .NET console programs on
 * Chinese Windows emit GBK bytes even when the parent speaks UTF-8, so fall back
 * to the active code page instead of leaving replacement characters.
 */
public final class Encodings {
    private Encodings() {
    }

    public static String decode(byte[] bytes) {
        return decode(bytes, bytes == null ? 0 : bytes.length);
    }

    public static String decode(byte[] bytes, int length) {
        if (bytes == null || length <= 0) {
            return "";
        }
        int size = Math.min(length, bytes.length);
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, 0, size)).toString();
        } catch (CharacterCodingException ignored) {
            boolean windows = System.getProperty("os.name", "")
                    .toLowerCase(Locale.ROOT).contains("win");
            Charset charset = Charset.forName(windows ? "GBK" : "ISO-8859-1");
            return new String(Arrays.copyOf(bytes, size), charset);
        }
    }
}
