package com.inn.cafe.Service;

import com.inn.cafe.DAO.CategoryDao;
import com.inn.cafe.DAO.ProductDao;
import com.inn.cafe.POJO.Category;
import com.inn.cafe.POJO.Product;
import com.inn.cafe.Wrapper.ExcelImportResult;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * ExcelServiceTest — regression coverage for the Issue #2 fix.
 * Uses real in-memory XSSF workbooks so the parsing logic is exercised exactly
 * as it runs in production.
 */
@ExtendWith(MockitoExtension.class)
class ExcelServiceTest {

    @InjectMocks
    private ExcelService excelService;

    @Mock
    private CategoryDao categoryDao;

    @Mock
    private ProductDao productDao;

    @TempDir
    Path tempDir;

    @BeforeEach
    void limits() {
        ReflectionTestUtils.setField(excelService, "maxFileSizeBytes", 5_242_880L);
        ReflectionTestUtils.setField(excelService, "maxRows", 5000);
    }

    private Category category(int id, String name) {
        Category c = new Category();
        c.setId(id);
        c.setName(name);
        return c;
    }

    /** Builds an xlsx with header (id, description, name, price, status, category_name) like the repo samples. */
    private byte[] workbook(String[][] rows) throws Exception {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("Sheet1");
            int r = 0;
            for (String[] row : rows) {
                Row xrow = sheet.createRow(r++);
                for (int i = 0; i < row.length; i++) {
                    String value = row[i];
                    if (value != null) {
                        try {
                            xrow.createCell(i).setCellValue(Double.parseDouble(value));
                        } catch (NumberFormatException e) {
                            xrow.createCell(i).setCellValue(value);
                        }
                    }
                }
            }
            wb.write(out);
            return out.toByteArray();
        }
    }

    private MockMultipartFile file(String name, byte[] content) {
        return new MockMultipartFile("file", name, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", content);
    }

    @Test
    void import_isIdIndependent_andCreatesMissingCategories() throws Exception {
        // sample-like rows; categories do NOT pre-exist; the 'id' column is present and must be ignored
        byte[] xlsx = workbook(new String[][]{
                {"id", "description", "name", "price", "status", "category_name"},
                {"8", "Veggie Pizza", "Veggie Pizza", "199", "available", "Pizza"},
                {"9", "Masala Dosa", "Masala Dosa", "139", "available", "Dosa"},
        });
        when(categoryDao.findByName("Pizza")).thenReturn(Optional.empty());
        when(categoryDao.findByName("Dosa")).thenReturn(Optional.empty());
        when(categoryDao.saveAndFlush(any(Category.class)))
                .thenAnswer(inv -> {
                    Category c = inv.getArgument(0);
                    c.setId(77); // DB-assigned identity
                    return c;
                });
        when(productDao.findByNameAndCategory_Id(anyString(), any())).thenReturn(null);
        when(productDao.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        ExcelImportResult result = excelService.parseAndImport(file("ProductDetails.xlsx", xlsx));

        assertTrue(result.isParsed());
        assertEquals(2, result.getTotalRows());
        assertEquals(2, result.getImportedCount());
        assertEquals(2, result.getCategoriesCreated());
        assertTrue(result.isIdColumnIgnored()); // id column present and ignored by design
        assertEquals(0, result.getFailedCount());
    }

    @Test
    void import_normalizesStatusAndSkipsDuplicates() throws Exception {
        byte[] xlsx = workbook(new String[][]{
                {"id", "description", "name", "price", "status", "category_name"},
                {"1", "d1", "Cold Coffee", "159", "available", "Beverages"},
                {"2", "d2", "Cold Coffee", "159", "unavailable", "Beverages"}, // duplicate name+category
        });
        Category bev = category(5, "Beverages");
        when(categoryDao.findByName("Beverages")).thenReturn(Optional.of(bev));
        when(productDao.findByNameAndCategory_Id("Cold Coffee", 5))
                .thenReturn(null)                     // first row: not yet present
                .thenReturn(new Product());           // second row: duplicate
        when(productDao.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        ExcelImportResult result = excelService.parseAndImport(file("x.xlsx", xlsx));

        assertTrue(result.isParsed());
        assertEquals(1, result.getImportedCount());
        assertEquals(1, result.getDuplicateCount());
        assertEquals(0, result.getFailedCount());
    }

    @Test
    void import_reportsRowLevelErrors() throws Exception {
        byte[] xlsx = workbook(new String[][]{
                {"name", "price", "category_name"},
                {"", "100", "Pizza"},             // missing name
                {"Good Item", "abc", "Pizza"},    // invalid price
                {"Another", "50", ""},            // missing category
                {"Valid", "60", "Pizza"},         // valid
        });
        when(categoryDao.findByName("Pizza")).thenReturn(Optional.of(category(1, "Pizza")));
        when(productDao.findByNameAndCategory_Id(anyString(), any())).thenReturn(null);
        when(productDao.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        ExcelImportResult result = excelService.parseAndImport(file("x.xlsx", xlsx));

        assertTrue(result.isParsed());
        assertEquals(4, result.getTotalRows());
        assertEquals(1, result.getImportedCount());
        assertEquals(3, result.getFailedCount());
        assertEquals(3, result.getErrors().size());
        assertTrue(result.getErrors().stream().anyMatch(e -> e.getField().equals("name")));
        assertTrue(result.getErrors().stream().anyMatch(e -> e.getField().equals("price")));
        assertTrue(result.getErrors().stream().anyMatch(e -> e.getField().equals("category_name")));
    }

    @Test
    void import_skipsRepeatedHeaderRows_midFile() throws Exception {
        // ProductDetails12.xlsx contains a duplicated header row in the middle
        byte[] xlsx = workbook(new String[][]{
                {"id", "description", "name", "price", "status", "category_name"},
                {"id", "description", "name", "price", "status", "category_name"}, // repeated header
                {"13", "d", "Veg Fried Rice", "129", "available", "Fried-Rice"},
        });
        when(categoryDao.findByName("Fried-Rice")).thenReturn(Optional.empty());
        when(categoryDao.saveAndFlush(any(Category.class))).thenAnswer(inv -> {
            Category c = inv.getArgument(0);
            c.setId(90);
            return c;
        });
        when(productDao.findByNameAndCategory_Id(anyString(), any())).thenReturn(null);
        when(productDao.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        ExcelImportResult result = excelService.parseAndImport(file("x.xlsx", xlsx));

        assertTrue(result.isParsed());
        assertEquals(1, result.getTotalRows());  // repeated header not counted as data
        assertEquals(1, result.getImportedCount());
    }

    @Test
    void import_rejectsWrongExtension() {
        ExcelImportResult result = excelService.parseAndImport(file("data.csv", "a,b\n1,2".getBytes()));
        assertFalse(result.isParsed());
        assertTrue(result.getMessage().contains(".xls"));
    }

    @Test
    void import_rejectsEmptyFile() {
        ExcelImportResult result = excelService.parseAndImport(file("empty.xlsx", new byte[0]));
        assertFalse(result.isParsed());
    }

    @Test
    void import_rejectsOversizedFile() {
        byte[] big = new byte[10];
        ReflectionTestUtils.setField(excelService, "maxFileSizeBytes", 5L);
        ExcelImportResult result = excelService.parseAndImport(file("big.xlsx", big));
        assertFalse(result.isParsed());
        assertTrue(result.getMessage().toLowerCase().contains("large"));
    }

    @Test
    void import_rejectsCorruptWorkbook() {
        // real zip magic but garbage content => POI parse failure must not leak raw exceptions
        byte[] garbage = new byte[]{'P', 'K', 3, 4, 0, 0, 0, 0, 1, 2, 3, 4, 5, 6, 7, 8};
        ExcelImportResult result = excelService.parseAndImport(file("corrupt.xlsx", garbage));
        assertFalse(result.isParsed());
        assertTrue(result.getMessage().contains("Could not read"));
        assertFalse(result.getMessage().contains("ZipException"));
    }

    @Test
    void import_rejectsMissingRequiredHeaders() throws Exception {
        byte[] xlsx = workbook(new String[][]{
                {"foo", "bar"},
                {"1", "2"},
        });
        ExcelImportResult result = excelService.parseAndImport(file("x.xlsx", xlsx));
        assertFalse(result.isParsed());
        assertTrue(result.getMessage().contains("Missing required header"));
    }

    @Test
    void import_rejectsHeaderOnlyFile() throws Exception {
        byte[] xlsx = workbook(new String[][]{
                {"name", "price", "category_name"},
        });
        ExcelImportResult result = excelService.parseAndImport(file("x.xlsx", xlsx));
        assertFalse(result.isParsed());
        assertTrue(result.getMessage().contains("no data rows"));
    }

    @Test
    void statusNormalization_matchesDbContract() {
        assertEquals("true", ExcelService.normalizeStatus("available"));
        assertEquals("true", ExcelService.normalizeStatus("Available"));
        assertEquals("false", ExcelService.normalizeStatus("unavailable"));
        assertEquals("false", ExcelService.normalizeStatus("FALSE"));
        assertEquals("false", ExcelService.normalizeStatus("0"));
        assertEquals("true", ExcelService.normalizeStatus(""));   // default active
        assertEquals("true", ExcelService.normalizeStatus(null));
    }

    @Test
    void import_detectsDuplicatesWithinTheSameFile() throws Exception {
        // both rows have the same name+category but neither exists in the DB yet —
        // the second must be counted as a duplicate (batch-level detection)
        byte[] xlsx = workbook(new String[][]{
                {"name", "price", "category_name"},
                {"Same Item", "100", "Pizza"},
                {"Same Item", "100", "Pizza"},
        });
        when(categoryDao.findByName("Pizza")).thenReturn(Optional.of(category(1, "Pizza")));
        when(productDao.findByNameAndCategory_Id(anyString(), any())).thenReturn(null); // DB has neither
        when(productDao.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        ExcelImportResult result = excelService.parseAndImport(file("x.xlsx", xlsx));

        assertTrue(result.isParsed());
        assertEquals(1, result.getImportedCount());
        assertEquals(1, result.getDuplicateCount());
    }
}
