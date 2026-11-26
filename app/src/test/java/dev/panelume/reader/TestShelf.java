package dev.panelume.reader;

import android.content.Context;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;
import java.util.stream.Stream;

/** Each database test owns its own database and chapter storage. */
final class TestShelf implements AutoCloseable {
    final ShelfDb shelf;
    private final Context context;
    private final String name;
    private final Path files;

    TestShelf(Context context) throws IOException {
        this.context = context;
        name = "fixture-" + UUID.randomUUID() + ".db";
        files = Files.createTempDirectory(context.getCacheDir().toPath(), "panelume-fixture-");
        shelf = new ShelfDb(context, name, new File(files.toFile(), "chapters"));
    }

    @Override public void close() throws IOException {
        shelf.close();
        context.deleteDatabase(name);
        try (Stream<Path> paths = Files.walk(files)) {
            Path[] ordered = paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new);
            for (Path path : ordered) Files.deleteIfExists(path);
        }
    }
}
