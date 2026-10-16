package app.realtimetranslator.outbound;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public final class LanguageCodes {
    private static final Map<String, String> SUPPORTED = Map.of(
            "ja", "ja",
            "jp", "ja",
            "japanese", "ja",
            "vi", "vi",
            "vn", "vi",
            "vietnamese", "vi"
    );

    private LanguageCodes() {}

    public static Optional<String> toIso(String whisperLanguage) {
        if (whisperLanguage == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(SUPPORTED.get(whisperLanguage.strip().toLowerCase(Locale.ROOT)));
    }
}
