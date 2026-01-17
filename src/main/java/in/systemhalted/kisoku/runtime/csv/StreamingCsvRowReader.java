package in.systemhalted.kisoku.runtime.csv;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * CSV row reader that streams from an input stream, trimming cell values and
 * skipping whitespace-only lines.
 */
public final class StreamingCsvRowReader implements CsvRowReader {
    private final BufferedReader reader;
    private long rowNumber;

    public StreamingCsvRowReader(InputStream inputStream) {
        this.reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
    }

    @Override
    public String[] readNext() throws IOException {
        String line;
        while ((line = reader.readLine()) != null) {
            rowNumber++;
            if (line.trim().isEmpty()) {
                continue;
            }

            List<String> cells = new ArrayList<>();
            StringBuilder cell = new StringBuilder();
            int depth = 0;

            for (int i = 0; i < line.length(); i++) {
                char ch = line.charAt(i);
                if (ch == '(') {
                    depth++;
                    cell.append(ch);
                } else if (ch == ')') {
                    depth--;
                    if (depth < 0) {
                        throw new IOException("Unbalanced parentheses at row " + rowNumber);
                    }
                    cell.append(ch);
                } else if (ch == ',' && depth == 0) {
                    cells.add(cell.toString().trim());
                    cell.setLength(0);
                } else {
                    cell.append(ch);
                }
            }

            if (depth != 0) {
                throw new IOException("Unbalanced parentheses at row " + rowNumber);
            }
            cells.add(cell.toString().trim());

            return cells.toArray(new String[0]);
        }
        return null;
    }

    @Override
    public long rowNumber() {
        return rowNumber;
    }

    @Override
    public void close() throws IOException {
        reader.close();
    }
}
