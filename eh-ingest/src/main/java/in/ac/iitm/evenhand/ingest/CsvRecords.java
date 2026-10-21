package in.ac.iitm.evenhand.ingest;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;

/**
 * RFC 4180 records: commas separate fields, a field in double quotes may hold commas, doubled
 * quotes and line breaks. A written answer routinely spans several lines, so a record is not a
 * line; each record remembers the line it started on, so a diagnostic still points at the row
 * the instructor sees in Excel.
 *
 * <p>Excel's "CSV UTF-8" byte-order mark is dropped, and {@code \r\n}, {@code \n} and {@code \r}
 * line ends are all accepted.
 */
final class CsvRecords {

    /** One record and the 1-based line it started on. */
    record Record(String[] fields, int line) {
        boolean isBlank() {
            for (String f : fields) if (!f.isBlank()) return false;
            return true;
        }
    }

    private static final int MAX_FIELD_CHARS = 1_000_000;

    private final Reader in;
    private int line = 1;
    private int pushedBack = -2;
    private boolean started;

    CsvRecords(Reader in) {
        this.in = in;
    }

    /** The next record, or null at the end of the file. */
    Record next() throws IOException {
        int c = read();
        if (!started) {
            started = true;
            if (c == 0xFEFF) c = read();
        }
        if (c == -1) return null;
        int startLine = line;
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        boolean wasQuoted = false;
        while (true) {
            if (c == -1) {
                if (quoted) {
                    throw new IllegalArgumentException("line " + startLine + ": a quoted field is never closed, so the "
                            + "rest of the file would be read as one answer. Check for a stray double quote.");
                }
                fields.add(field.toString());
                return new Record(fields.toArray(new String[0]), startLine);
            }
            if (quoted) {
                if (c == '"') {
                    int d = read();
                    if (d == '"') {
                        field.append('"');
                    } else {
                        quoted = false;
                        c = d;
                        continue;
                    }
                } else {
                    if (c == '\n') line++;
                    if (c == '\r') {
                        int d = read();
                        if (d != '\n') unread(d);
                        line++;
                        field.append('\n');
                    } else {
                        field.append((char) c);
                    }
                }
            } else if (c == '"' && field.length() == 0 && !wasQuoted) {
                quoted = true;
                wasQuoted = true;
            } else if (c == ',') {
                fields.add(field.toString());
                field.setLength(0);
                wasQuoted = false;
            } else if (c == '\n' || c == '\r') {
                if (c == '\r') {
                    int d = read();
                    if (d != '\n') unread(d);
                }
                line++;
                fields.add(field.toString());
                return new Record(fields.toArray(new String[0]), startLine);
            } else {
                field.append((char) c);
            }
            if (field.length() > MAX_FIELD_CHARS) {
                throw new IllegalArgumentException("line " + startLine + ": a field is longer than a million characters;"
                        + " this is not a marks file, or a quote was left open.");
            }
            c = read();
        }
    }

    private int read() throws IOException {
        if (pushedBack != -2) {
            int c = pushedBack;
            pushedBack = -2;
            return c;
        }
        return in.read();
    }

    private void unread(int c) {
        pushedBack = c;
    }
}
