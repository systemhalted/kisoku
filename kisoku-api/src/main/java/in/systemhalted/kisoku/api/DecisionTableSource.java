package in.systemhalted.kisoku.api;

import java.io.IOException;
import java.io.InputStream;

/** Provides access to a decision table from an external source. */
public interface DecisionTableSource {
  String name();

  TableFormat format();

  /**
   * Opens a fresh stream over the table data.
   *
   * <p>Must be callable more than once, each call yielding the same content from the start: the
   * compiler streams the source in multiple passes rather than holding rows in memory. File-backed
   * sources (see {@link DecisionTableSources}) satisfy this naturally; a one-shot stream does not.
   *
   * @return a new input stream positioned at the beginning of the data
   * @throws IOException if the source cannot be opened
   */
  InputStream openStream() throws IOException;
}
