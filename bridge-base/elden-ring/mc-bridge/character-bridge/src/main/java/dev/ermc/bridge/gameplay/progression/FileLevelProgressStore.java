package dev.ermc.bridge.gameplay.progression;

import dev.ermc.bridge.api.CharacterBinding;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

/** Small, versioned, single-writer store; replaces one complete record atomically. */
public final class FileLevelProgressStore implements LevelProgressStore {
    private static final Set<String> KEYS = Set.of("version", "player", "campaign", "character",
            "baseline", "highest", "pointsPerLevel", "startingPoints");
    private final Path root;

    public FileLevelProgressStore(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    private Path path(CharacterBinding binding) {
        return root.resolve(binding.campaignId().toString()).resolve(binding.playerId().toString())
                .resolve(binding.characterId() + ".properties");
    }

    @Override
    public Optional<LevelProgress> load(CharacterBinding binding) throws IOException {
        Path path = path(binding);
        Properties values = new Properties();
        try {
            if (Files.size(path) > 4096) throw new IOException("Oversized level progress: " + path);
            try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                values.load(reader);
            }
        } catch (NoSuchFileException absent) {
            return Optional.empty();
        } catch (IllegalArgumentException malformedProperties) {
            throw new IOException("Invalid level progress: " + path, malformedProperties);
        }
        try {
            if (!values.stringPropertyNames().equals(KEYS) || !"1".equals(values.getProperty("version"))) {
                throw new IllegalArgumentException("Unsupported version or fields");
            }
            var storedBinding = new CharacterBinding(UUID.fromString(values.getProperty("player")),
                    UUID.fromString(values.getProperty("campaign")), UUID.fromString(values.getProperty("character")));
            if (!storedBinding.equals(binding)) throw new IllegalArgumentException("Binding mismatch");
            return Optional.of(new LevelProgress(binding, integer(values, "baseline"), integer(values, "highest"),
                    integer(values, "pointsPerLevel"), integer(values, "startingPoints")));
        } catch (IllegalArgumentException | ArithmeticException invalid) {
            throw new IOException("Invalid level progress: " + path, invalid);
        }
    }

    @Override
    public void save(LevelProgress progress) throws IOException {
        var binding = progress.binding();
        Properties values = new Properties();
        values.setProperty("version", "1");
        values.setProperty("player", binding.playerId().toString());
        values.setProperty("campaign", binding.campaignId().toString());
        values.setProperty("character", binding.characterId().toString());
        values.setProperty("baseline", Integer.toString(progress.baselineLevel()));
        values.setProperty("highest", Integer.toString(progress.highestLevel()));
        values.setProperty("pointsPerLevel", Integer.toString(progress.pointsPerLevel()));
        values.setProperty("startingPoints", Integer.toString(progress.startingPoints()));
        StringWriter encoded = new StringWriter();
        values.store(encoded, "Minecraft-Ring level entitlement v1");

        Path target = path(binding);
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), ".level-", ".tmp");
        try {
            try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer bytes = StandardCharsets.UTF_8.encode(encoded.toString());
                while (bytes.hasRemaining()) channel.write(bytes);
                channel.force(true);
            }
            // Fail closed if atomic replacement is unavailable; never fall back to in-place truncation.
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static int integer(Properties values, String key) {
        return Integer.parseInt(values.getProperty(key));
    }
}
