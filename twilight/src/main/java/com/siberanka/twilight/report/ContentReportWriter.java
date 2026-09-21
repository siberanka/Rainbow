package com.siberanka.twilight.report;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.siberanka.twilight.source.ContentReport;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ContentReportWriter {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private ContentReportWriter() {}

    public static void write(Path path, ContentReport report) throws IOException {
        Files.createDirectories(path.getParent());
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
            GSON.toJson(report, writer);
        }
        try {
            Files.move(temporary, path, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
