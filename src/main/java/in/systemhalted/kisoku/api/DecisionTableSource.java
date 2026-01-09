package in.systemhalted.kisoku.api;

import java.io.IOException;
import java.io.InputStream;

public interface DecisionTableSource {
    String name();

    TableFormat format();

    InputStream openStream() throws IOException;
}
