package in.systemhalted.kisoku.io;

import in.systemhalted.kisoku.api.DecisionTableSource;
import in.systemhalted.kisoku.api.TableFormat;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

public final class DecisionTableSources {
  private DecisionTableSources() {}

  public static DecisionTableSource csv(Path path) {
    return new PathDecisionTableSource(path, TableFormat.CSV);
  }

  private static final class PathDecisionTableSource implements DecisionTableSource {
    private final Path path;
    private final TableFormat format;

    private PathDecisionTableSource(Path path, TableFormat format) {
      this.path = Objects.requireNonNull(path, "path");
      this.format = Objects.requireNonNull(format, "format");
    }

    @Override
    public String name() {
      return path.getFileName().toString();
    }

    @Override
    public TableFormat format() {
      return format;
    }

    @Override
    public InputStream openStream() throws IOException {
      return Files.newInputStream(path);
    }
  }
}
