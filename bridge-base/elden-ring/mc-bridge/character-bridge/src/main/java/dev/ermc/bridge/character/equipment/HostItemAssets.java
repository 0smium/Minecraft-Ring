package dev.ermc.bridge.character.equipment;

import com.google.gson.JsonParser;
import dev.ermc.bridge.link.BridgePaths;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.LoggerFactory;

/** Local, read-only names and pictures extracted from this installation; never an inventory authority. */
public final class HostItemAssets {
    public record ItemInfo(String name, int icon, int armorSlot) {}
    private static volatile Map<Integer, ItemInfo> items;
    private HostItemAssets() {}

    public static Path root() { return Path.of(BridgePaths.file("host-assets")); }

    public static Optional<ItemInfo> item(int rawItemId) {
        if (items == null) reload();
        return Optional.ofNullable(items.get(rawItemId));
    }

    public static synchronized void reload() {
        Map<Integer, ItemInfo> loaded = new HashMap<>();
        Path file = root().resolve("items.json");
        try {
            if (Files.isRegularFile(file)) {
                if (Files.size(file) > 16 * 1024 * 1024) throw new IllegalArgumentException("Oversized host item manifest");
                var document = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
                if (document.get("schema").getAsInt() != 1) throw new IllegalArgumentException("Unknown host asset schema");
                var rows = document.getAsJsonArray("items");
                if (rows.size() > 50000) throw new IllegalArgumentException("Too many host items");
                for (var value : rows) {
                    var row = value.getAsJsonObject();
                    long id = row.get("id").getAsLong();
                    String name = row.get("name").getAsString();
                    int icon = row.get("icon").getAsInt(), slot = row.get("armorSlot").getAsInt();
                    if (id < 0 || id > 0xffffffffL || name.isBlank() || name.length() > 256
                            || icon < 0 || icon > 999999 || slot != -1 && (slot < 12 || slot > 15))
                        throw new IllegalArgumentException("Invalid host item metadata");
                    if (loaded.put((int) id, new ItemInfo(name, icon, slot)) != null)
                        throw new IllegalArgumentException("Duplicate host item metadata");
                }
            }
        } catch (Exception error) {
            loaded.clear();
            LoggerFactory.getLogger("erbridge").warn("Local host item pictures/names unavailable: {}", file, error);
        }
        items = Map.copyOf(loaded);
    }
}
