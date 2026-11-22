package dev.seleniummcp.collection.management;

import dev.seleniummcp.support.ToolFailure;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

final class ManagementInput {
    static final int MAX_BYTES = 64 * 1024;
    static final String KEY = "^[A-Za-z0-9_-]{1,64}$";
    static final String UUID = "^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$";
    static final List<String> ENVIRONMENTS = List.of("dev", "qa", "staging");
    static final JsonMapper JSON = JsonMapper.builder(JsonFactory.builder()
                    .streamReadConstraints(StreamReadConstraints.builder().maxDocumentLength(MAX_BYTES)
                            .maxNestingDepth(32).maxTokenCount(8192).build()).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
            .build();

    private ManagementInput() {}

    static Object read(Path path) throws IOException {
        try (var file = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = Channels.newInputStream(file).readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) throw new IOException("Saved configuration exceeds its limit.");
            // readTree checks duplicate keys before converting into plain DTO inputs.
            return JSON.treeToValue(JSON.readTree(bytes), Object.class);
        }
    }

    static Map<String, Object> object(Object value, Set<String> required, Set<String> optional) {
        if (!(value instanceof Map<?, ?> map) || !map.keySet().containsAll(required)) throw invalid();
        var result = new LinkedHashMap<String, Object>();
        for (var entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key) || (!required.contains(key) && !optional.contains(key))) throw invalid();
            result.put(key, entry.getValue());
        }
        return result;
    }

    static Map<?, ?> map(Object value, int limit) {
        if (!(value instanceof Map<?, ?> map) || map.size() > limit) throw invalid();
        return map;
    }

    static String pattern(Object value, String regex) {
        if (!(value instanceof String text) || text.length() > 2048 || !text.matches(regex)) throw invalid();
        return text;
    }

    static String name(Object value) {
        if (!(value instanceof String name) || name.isBlank() || name.length() > 80
                || name.chars().anyMatch(Character::isISOControl)) throw invalid();
        return name;
    }

    static boolean flag(Object value) {
        if (!(value instanceof Boolean flag)) throw invalid();
        return flag;
    }

    static int integer(Object value, int min, int max) {
        if (!(value instanceof Integer || value instanceof Long)
                || ((Number) value).longValue() < min || ((Number) value).longValue() > max) throw invalid();
        return ((Number) value).intValue();
    }

    static ToolFailure invalid() { return new ToolFailure("INVALID_INPUT", "Check the saved collection fields."); }
}
