package in.ac.iitm.evenhand.ingest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Excel workbooks built part by part, as Excel writes them. */
class XlsxReaderTest {

    private static byte[] zip(Map<String, String> parts) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(out)) {
            for (Map.Entry<String, String> e : parts.entrySet()) {
                z.putNextEntry(new ZipEntry(e.getKey()));
                z.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                z.closeEntry();
            }
        }
        return out.toByteArray();
    }

    private static final String NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    private static final String RNS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";

    private static Map<String, String> workbook(String sheetXml) {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("[Content_Types].xml", "<Types/>");
        p.put("xl/workbook.xml", "<workbook xmlns='" + NS + "' xmlns:r='" + RNS + "'><sheets>"
                + "<sheet name='Marks' sheetId='1' r:id='rId1'/></sheets></workbook>");
        p.put("xl/_rels/workbook.xml.rels", "<Relationships xmlns='http://schemas.openxmlformats.org/package/2006/relationships'>"
                + "<Relationship Id='rId1' Type='worksheet' Target='worksheets/sheet1.xml'/></Relationships>");
        p.put("xl/sharedStrings.xml", "<sst xmlns='" + NS + "'><si><t>roll_no</t></si><si><t>ta</t></si>"
                + "<si><t>Q1 (10)</t></si><si><t>Q1 answer</t></si><si><t>CE23B092</t></si><si><t>Ravi</t></si>"
                + "<si><r><t>Moment is max, </t></r><r><t>where \"V = 0\"</t></r></si></sst>");
        p.put("xl/worksheets/sheet1.xml", sheetXml);
        return p;
    }

    private static final String SHEET = "<worksheet xmlns='" + NS + "'><sheetData>"
            + "<row r='1'><c r='A1' t='s'><v>0</v></c><c r='B1' t='s'><v>1</v></c><c r='C1' t='s'><v>2</v></c>"
            + "<c r='D1' t='s'><v>3</v></c></row>"
            + "<row r='2'><c r='A2' t='s'><v>4</v></c><c r='B2' t='s'><v>5</v></c><c r='C2'><v>7.499999999999999</v></c>"
            + "<c r='D2' t='s'><v>6</v></c></row>"
            + "<row r='4'><c r='A4' t='inlineStr'><is><t>ME21B145</t></is></c><c r='B4' t='inlineStr'><is><t>Meena</t></is></c>"
            + "<c r='C4'><v>6</v></c></row>"
            + "</sheetData></worksheet>";

    @Test
    @DisplayName("a workbook's first sheet reads as CSV: shared and inline strings, rich text, numbers, gaps")
    void readsFirstSheet() throws IOException {
        String csv = XlsxReader.toCsv(zip(workbook(SHEET)));
        assertThat(csv).isEqualTo("roll_no,ta,Q1 (10),Q1 answer\n"
                + "CE23B092,Ravi,7.5,\"Moment is max, where \"\"V = 0\"\"\"\n"
                + "ME21B145,Meena,6,\n");
        CsvIngest.IngestResult r = CsvIngest.read(new BufferedReader(new StringReader(csv)),
                Pseudonymiser.withSalt(new byte[32]));
        assertThat(r.responses()).hasSize(2);
        assertThat(r.answerCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("an XML part carrying a DOCTYPE is refused, so an external entity cannot read local files")
    void refusesXxe() throws IOException {
        String evil = "<?xml version='1.0'?><!DOCTYPE x [<!ENTITY e SYSTEM 'file:///c:/windows/win.ini'>]>"
                + "<worksheet xmlns='" + NS + "'><sheetData><row r='1'><c r='A1' t='str'><v>&e;</v></c></row>"
                + "</sheetData></worksheet>";
        assertThatThrownBy(() -> XlsxReader.toCsv(zip(workbook(evil)))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("could not be read");
    }

    @Test
    @DisplayName("a zip that expands past 200 MB is refused before it can exhaust memory")
    void refusesZipBomb() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(out)) {
            z.putNextEntry(new ZipEntry("xl/workbook.xml"));
            z.write("<workbook/>".getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
            z.putNextEntry(new ZipEntry("xl/worksheets/sheet1.xml"));
            byte[] zeros = new byte[1 << 20];
            for (int i = 0; i < 210; i++) z.write(zeros);
            z.closeEntry();
        }
        assertThatThrownBy(() -> XlsxReader.toCsv(out.toByteArray())).hasMessageContaining("200 MB");
    }

    @Test
    @DisplayName("recognises a workbook by its zip signature, and leaves CSV text alone")
    void detects() throws IOException {
        assertThat(XlsxReader.looksLikeXlsx(zip(workbook(SHEET)))).isTrue();
        assertThat(XlsxReader.looksLikeXlsx("roll_no,ta\n".getBytes(StandardCharsets.UTF_8))).isFalse();
        assertThat(XlsxReader.number("12")).isEqualTo("12");
        assertThat(XlsxReader.number("0.30000000000000004")).isEqualTo("0.3");
        assertThat(XlsxReader.column("AB12")).isEqualTo(27);
    }
}
