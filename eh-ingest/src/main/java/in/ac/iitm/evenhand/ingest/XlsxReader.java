package in.ac.iitm.evenhand.ingest;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Reads the first worksheet of an Excel {@code .xlsx} workbook into CSV text, so the instructor can
 * drop the file they already have instead of exporting it.
 *
 * <p>An {@code .xlsx} file is a zip of XML parts; this reads only the shared-string table, the
 * workbook's sheet list and the first sheet, with the JDK's own zip and XML parsers. Two guards
 * make that safe on an untrusted file: the zip is read with a cap on its uncompressed size (a
 * "zip bomb" cannot exhaust memory), and the XML parser refuses DTDs and external entities (an
 * XXE attack cannot read files from this computer).
 *
 * <p>Numbers are written exactly as Excel stores them, shortened where binary floating point
 * left a tail ({@code 7.499999999999999} is read as 7.5); text cells are quoted as CSV needs.
 */
public final class XlsxReader {

    private static final long MAX_UNCOMPRESSED = 200L * 1024 * 1024;
    private static final int MAX_ENTRIES = 5_000;

    private XlsxReader() {
    }

    /** True if the bytes are a zip archive, the container every .xlsx is. */
    public static boolean looksLikeXlsx(byte[] bytes) {
        return bytes.length > 4 && bytes[0] == 'P' && bytes[1] == 'K' && bytes[2] == 3 && bytes[3] == 4;
    }

    /** The first worksheet as RFC 4180 CSV text. */
    public static String toCsv(byte[] xlsx) throws IOException {
        Map<String, byte[]> parts = unzip(xlsx);
        List<String> shared = sharedStrings(parts.get("xl/sharedStrings.xml"));
        byte[] sheet = firstSheet(parts);
        if (sheet == null) throw new IllegalArgumentException("This Excel file has no worksheet.");
        Document doc = parse(sheet);
        NodeList rows = doc.getElementsByTagNameNS("*", "row");
        TreeMap<Integer, TreeMap<Integer, String>> grid = new TreeMap<>();
        for (int r = 0; r < rows.getLength(); r++) {
            Element row = (Element) rows.item(r);
            int rowIndex = row.hasAttribute("r") ? Integer.parseInt(row.getAttribute("r")) : r + 1;
            NodeList cells = row.getElementsByTagNameNS("*", "c");
            int nextColumn = 0;
            for (int c = 0; c < cells.getLength(); c++) {
                Element cell = (Element) cells.item(c);
                int col = cell.hasAttribute("r") ? column(cell.getAttribute("r")) : nextColumn;
                nextColumn = col + 1;
                String value = value(cell, shared);
                if (!value.isEmpty()) grid.computeIfAbsent(rowIndex, k -> new TreeMap<>()).put(col, value);
            }
        }
        StringBuilder csv = new StringBuilder();
        int width = 0;
        for (TreeMap<Integer, String> row : grid.values()) width = Math.max(width, row.lastKey() + 1);
        for (TreeMap<Integer, String> row : grid.values()) {
            for (int c = 0; c < width; c++) {
                if (c > 0) csv.append(',');
                csv.append(quote(row.getOrDefault(c, "")));
            }
            csv.append('\n');
        }
        return csv.toString();
    }

    private static String value(Element cell, List<String> shared) {
        String type = cell.getAttribute("t");
        if (type.equals("inlineStr")) return text(cell, "t");
        Element v = child(cell, "v");
        if (v == null) return "";
        String raw = v.getTextContent().strip();
        return switch (type) {
            case "s" -> {
                int i = Integer.parseInt(raw);
                yield i >= 0 && i < shared.size() ? shared.get(i) : "";
            }
            case "str", "e" -> raw;
            case "b" -> raw.equals("1") ? "TRUE" : "FALSE";
            default -> number(raw);
        };
    }

    /** A stored number as it would be typed: 7 not 7.0, 7.5 not 7.499999999999999. */
    static String number(String raw) {
        try {
            double d = Double.parseDouble(raw);
            BigDecimal b = new BigDecimal(raw).round(new java.math.MathContext(12)).stripTrailingZeros();
            if (Math.abs(d) < 1e15 && b.scale() <= 0) return b.toBigInteger().toString();
            return b.toPlainString();
        } catch (NumberFormatException e) {
            return raw;
        }
    }

    private static List<String> sharedStrings(byte[] xml) throws IOException {
        List<String> out = new ArrayList<>();
        if (xml == null) return out;
        NodeList items = parse(xml).getElementsByTagNameNS("*", "si");
        for (int i = 0; i < items.getLength(); i++) {
            StringBuilder b = new StringBuilder();
            NodeList ts = ((Element) items.item(i)).getElementsByTagNameNS("*", "t");
            for (int k = 0; k < ts.getLength(); k++) {
                // Phonetic runs (rPh) carry a reading guide, not the cell's text.
                if (!"rPh".equals(ts.item(k).getParentNode().getLocalName())) b.append(ts.item(k).getTextContent());
            }
            out.add(b.toString());
        }
        return out;
    }

    /** The worksheet the workbook lists first, by following its relationship id. */
    private static byte[] firstSheet(Map<String, byte[]> parts) throws IOException {
        byte[] workbook = parts.get("xl/workbook.xml");
        byte[] rels = parts.get("xl/_rels/workbook.xml.rels");
        if (workbook != null && rels != null) {
            NodeList sheets = parse(workbook).getElementsByTagNameNS("*", "sheet");
            if (sheets.getLength() > 0) {
                Element first = (Element) sheets.item(0);
                String id = first.getAttributeNS("http://schemas.openxmlformats.org/officeDocument/2006/relationships", "id");
                NodeList rel = parse(rels).getElementsByTagNameNS("*", "Relationship");
                for (int i = 0; i < rel.getLength(); i++) {
                    Element e = (Element) rel.item(i);
                    if (e.getAttribute("Id").equals(id)) {
                        String target = e.getAttribute("Target");
                        String path = target.startsWith("/") ? target.substring(1) : "xl/" + target;
                        byte[] sheet = parts.get(path.replace("xl/./", "xl/"));
                        if (sheet != null) return sheet;
                    }
                }
            }
        }
        return parts.get("xl/worksheets/sheet1.xml");
    }

    private static Map<String, byte[]> unzip(byte[] bytes) throws IOException {
        Map<String, byte[]> out = new HashMap<>();
        long total = 0;
        int entries = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry e;
            byte[] buf = new byte[1 << 16];
            while ((e = zip.getNextEntry()) != null) {
                if (++entries > MAX_ENTRIES) throw new IllegalArgumentException("This Excel file has too many parts.");
                String name = e.getName();
                boolean wanted = name.equals("xl/workbook.xml") || name.equals("xl/_rels/workbook.xml.rels")
                        || name.equals("xl/sharedStrings.xml") || name.startsWith("xl/worksheets/sheet");
                ByteArrayOutputStream part = wanted ? new ByteArrayOutputStream() : null;
                int n;
                while ((n = zip.read(buf)) > 0) {
                    total += n;
                    if (total > MAX_UNCOMPRESSED) {
                        throw new IllegalArgumentException("This Excel file expands to more than 200 MB; it cannot be a marks sheet.");
                    }
                    if (part != null) part.write(buf, 0, n);
                }
                if (part != null) out.put(name, part.toByteArray());
            }
        } catch (java.util.zip.ZipException e) {
            throw new IllegalArgumentException("This file looks like an Excel workbook but could not be opened: " + e.getMessage());
        }
        if (!out.containsKey("xl/workbook.xml")) {
            throw new IllegalArgumentException("This zip file is not an Excel workbook (.xlsx).");
        }
        return out;
    }

    private static Document parse(byte[] xml) throws IOException {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
            DocumentBuilder b = f.newDocumentBuilder();
            try (InputStream in = new ByteArrayInputStream(xml)) {
                return b.parse(in);
            }
        } catch (ParserConfigurationException | SAXException e) {
            throw new IllegalArgumentException("This Excel file could not be read: " + e.getMessage());
        }
    }

    /** "C12" -> 2 (zero-based column). */
    static int column(String ref) {
        int col = 0;
        for (int i = 0; i < ref.length(); i++) {
            char ch = ref.charAt(i);
            if (ch < 'A' || ch > 'Z') break;
            col = col * 26 + (ch - 'A' + 1);
        }
        return col - 1;
    }

    private static Element child(Element e, String local) {
        for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element el && local.equals(el.getLocalName())) return el;
        }
        return null;
    }

    private static String text(Element e, String local) {
        StringBuilder b = new StringBuilder();
        NodeList ts = e.getElementsByTagNameNS("*", local);
        for (int i = 0; i < ts.getLength(); i++) b.append(ts.item(i).getTextContent());
        return b.toString();
    }

    private static String quote(String v) {
        if (v.indexOf(',') < 0 && v.indexOf('"') < 0 && v.indexOf('\n') < 0 && v.indexOf('\r') < 0) return v;
        return '"' + v.replace("\"", "\"\"") + '"';
    }
}
