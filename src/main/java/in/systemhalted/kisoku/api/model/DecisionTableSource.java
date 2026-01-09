package in.systemhalted.kisoku.api.model;

import java.io.IOException;
import java.io.InputStream;

/** Provides access to a decision table from an external source. */
public interface DecisionTableSource {
  String name();

  TableFormat format();

  InputStream openStream() throws IOException;
}
