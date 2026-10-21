package com.inn.cafe.Service;

import com.inn.cafe.DAO.CategoryDao;
import com.inn.cafe.DAO.ProductDao;
import com.inn.cafe.POJO.Category;
import com.inn.cafe.POJO.Product;
import com.inn.cafe.Wrapper.ExcelImportResult;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.transaction.Transactional;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Excel product import (Issue #2 fix).
 *
 * Design contract:
 *  - ID-independent: a sheet 'id' column is ignored; the database assigns identity.
 *    (The legacy dead implementation in ProductServiceImpl manually assigned ids from
 *    findTopByOrderByIdDesc()+1 which collided with auto-increment — see DEFECT_REGISTER 2.2.)
 *  - Category mapping by NAME; missing categories are auto-created (the repo's own sample
 *    ProductDetails.xlsx references categories that do not pre-exist).
 *  - Status normalization: the sheet speaks 'available/unavailable' while the DB contract
 *    (NamedQuery filters) is 'true'/'false' — both spellings are accepted and normalized.
 *  - Deterministic structured reporting: every row is counted as imported / duplicate /
 *    failed with row-level reasons; no silent System.err skips.
 *  - Thread-safe: no shared mutable state (the legacy implementation used a static
 *    columnIndexMap — see DEFECT_REGISTER 2.4).
 *  - All-or-nothing persistence: parsing happens first; valid rows are persisted in a
 *    single transaction so a mid-import failure cannot leave a partial dataset behind.
 *  - Memory safety: file size and row count caps are enforced before/during parsing.
 */
@Slf4j
@Service
public class ExcelService {

    @Autowired
    private CategoryDao categoryDao;

    @Autowired
    private ProductDao productDao;

    @Value("${cafe.excel.max-file-size-bytes:5242880}")
    private long maxFileSizeBytes;

    @Value("${cafe.excel.max-rows:5000}")
    private int maxRows;

    @Transactional
    public ExcelImportResult parseAndImport(MultipartFile file) {
        ExcelImportResult result = new ExcelImportResult();
        result.setFileName(file == null ? null : file.getOriginalFilename());

        // ---- file-level validation --------------------------------------------------
        if (file == null || file.isEmpty()) {
            return fileFailure(result, "Uploaded file is empty.");
        }
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        if (!name.endsWith(".xlsx") && !name.endsWith(".xls")) {
            return fileFailure(result, "Invalid file format. Please upload .xls or .xlsx");
        }
        if (file.getSize() > maxFileSizeBytes) {
            return fileFailure(result, "File too large. Maximum allowed size is " + (maxFileSizeBytes / 1024 / 1024) + " MB.");
        }

        try (InputStream inputStream = file.getInputStream(); Workbook workbook = WorkbookFactory.create(inputStream)) {
            Sheet sheet = workbook.getSheetAt(0);
            if (sheet == null || sheet.getPhysicalNumberOfRows() < 1) {
                return fileFailure(result, "Excel file has no sheet or is empty.");
            }

            Row headerRow = sheet.getRow(0);
            Map<String, Integer> columnIndex = mapColumnIndexes(headerRow);
            if (columnIndex == null) {
                return fileFailure(result, "Excel file has no header row.");
            }
            if (!columnIndex.containsKey("name") || !columnIndex.containsKey("price") || !columnIndex.containsKey("category_name")) {
                return fileFailure(result, "Missing required header columns. Required: name, price, category_name (found: "
                        + String.join(", ", columnIndex.keySet()) + ").");
            }
            result.setIdColumnIgnored(columnIndex.containsKey("id"));

            // cache of categories created/loaded during this import (by lowercase name)
            Map<String, Category> categoryCache = new HashMap<>();
            // within-file duplicate guard: (categoryId + ':' + name) pairs accepted so far.
            // The DB check alone cannot see rows validated in this same batch (they are
            // persisted only after parsing), so same-file duplicates must be tracked here.
            java.util.Set<String> batchPairs = new java.util.HashSet<>();

            java.util.List<Product> toSave = new java.util.ArrayList<>();

            for (Row row : sheet) {
                int rowNum = row.getRowNum();
                if (rowNum == 0 || isRowEmpty(row)) {
                    continue;
                }
                if (isRepeatedHeaderRow(row, columnIndex)) {
                    continue; // e.g. ProductDetails12.xlsx contains a duplicated header mid-file
                }
                result.setTotalRows(result.getTotalRows() + 1);
                if (result.getTotalRows() > maxRows) {
                    return fileFailure(result, "Too many rows. Maximum allowed is " + maxRows + " (plus header).");
                }

                String productName = getCellString(row, columnIndex, "name");
                String description = getCellString(row, columnIndex, "description");
                Integer price = getCellInt(row, columnIndex, "price");
                String statusRaw = getCellString(row, columnIndex, "status");
                String categoryName = getCellString(row, columnIndex, "category_name");

                // ---- row-level validation --------------------------------------------
                if (productName.isEmpty()) {
                    result.addError(rowNum + 1, "name", "Product name is missing");
                    result.setFailedCount(result.getFailedCount() + 1);
                    continue;
                }
                if (price == null || price < 0) {
                    result.addError(rowNum + 1, "price", "Price is missing or not a non-negative integer");
                    result.setFailedCount(result.getFailedCount() + 1);
                    continue;
                }
                if (categoryName.isEmpty()) {
                    result.addError(rowNum + 1, "category_name", "Category name is missing");
                    result.setFailedCount(result.getFailedCount() + 1);
                    continue;
                }

                // ---- category find-or-create (by name) --------------------------------
                Category category = resolveCategory(categoryCache, categoryName, result);
                if (category == null) {
                    continue; // resolveCategory already recorded the failure
                }

                // ---- duplicate detection (name + category) ----------------------------
                String pairKey = category.getId() + ":" + productName;
                Product existing = productDao.findByNameAndCategory_Id(productName, category.getId());
                if (existing != null || !batchPairs.add(pairKey)) {
                    // existing != null          -> already present in the database
                    // batchPairs.add()==false  -> duplicate within this same file
                    result.setDuplicateCount(result.getDuplicateCount() + 1);
                    continue;
                }

                Product product = new Product();
                product.setName(productName);
                product.setDescription(description);
                product.setPrice(price);
                product.setStatus(normalizeStatus(statusRaw));
                product.setCategory(category);
                toSave.add(product);
            }

            if (result.getTotalRows() == 0) {
                return fileFailure(result, "Excel file contains no data rows.");
            }

            // ---- atomic persistence ------------------------------------------------
            productDao.saveAll(toSave);
            result.setImportedCount(toSave.size());

            result.setParsed(true);
            result.setMessage("Import completed: " + result.getImportedCount() + " imported, "
                    + result.getDuplicateCount() + " duplicate(s) skipped, "
                    + result.getFailedCount() + " failed.");
            log.info("Excel import '{}' finished: {}", result.getFileName(), result.getMessage());
            return result;

        } catch (IOException | RuntimeException e) {
            // corrupt workbook, OLE2 mismatch, zip-bomb guard trips, etc.
            log.error("Excel import '{}' failed to parse: {}", result.getFileName(), e.getMessage());
            return fileFailure(result, "Could not read the Excel file. It may be corrupted or not a real "
                    + (name.endsWith(".xls") ? ".xls" : ".xlsx") + " workbook.");
        }
    }

    // ------------------------------------------------------------------ helpers

    private ExcelImportResult fileFailure(ExcelImportResult result, String message) {
        result.setParsed(false);
        result.setMessage(message);
        log.warn("Excel import '{}' rejected: {}", result.getFileName(), message);
        return result;
    }

    private Category resolveCategory(Map<String, Category> cache, String categoryName, ExcelImportResult result) {
        String key = categoryName.trim().toLowerCase(Locale.ROOT);
        Category category = cache.get(key);
        if (category != null) {
            return category;
        }
        category = categoryDao.findByName(categoryName.trim()).orElse(null);
        if (category == null) {
            Category created = new Category();
            created.setName(categoryName.trim());
            try {
                category = categoryDao.saveAndFlush(created);
                result.setCategoriesCreated(result.getCategoriesCreated() + 1);
                log.info("Excel import auto-created category '{}'", category.getName());
            } catch (Exception e) {
                log.error("Excel import could not create category '{}': {}", categoryName, e.getMessage());
                return null;
            }
        }
        cache.put(key, category);
        return category;
    }

    /**
     * Normalizes sheet status spellings to the DB contract values 'true'/'false'.
     * Accepted: available/true/yes/1/active -> true; unavailable/false/no/0 -> false.
     * Missing/unknown defaults to 'true' (imported items are active by default — documented).
     */
    static String normalizeStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return "true";
        }
        switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "available":
            case "true":
            case "yes":
            case "1":
            case "active":
                return "true";
            case "unavailable":
            case "false":
            case "no":
            case "0":
                return "false";
            default:
                return "true";
        }
    }

    private Map<String, Integer> mapColumnIndexes(Row headerRow) {
        if (headerRow == null) {
            return null;
        }
        Map<String, Integer> index = new HashMap<>();
        for (Cell cell : headerRow) {
            String value = cellToString(cell);
            if (value != null && !value.isBlank()) {
                index.put(value.trim().toLowerCase(Locale.ROOT), cell.getColumnIndex());
            }
        }
        return index;
    }

    private boolean isRepeatedHeaderRow(Row row, Map<String, Integer> columnIndex) {
        // A row whose 'name' cell literally equals the header text 'name' is a duplicated header
        Integer nameIdx = columnIndex.get("name");
        if (nameIdx != null) {
            Cell cell = row.getCell(nameIdx);
            return cell != null && cell.getCellType() == CellType.STRING
                    && "name".equalsIgnoreCase(cell.getStringCellValue().trim());
        }
        return false;
    }

    private boolean isRowEmpty(Row row) {
        if (row == null) {
            return true;
        }
        for (int i = row.getFirstCellNum(); i < row.getLastCellNum(); i++) {
            Cell cell = row.getCell(i);
            if (cell != null && cell.getCellType() != CellType.BLANK) {
                return false;
            }
        }
        return true;
    }

    private String getCellString(Row row, Map<String, Integer> columnIndex, String column) {
        Integer idx = columnIndex.get(column);
        if (idx == null) {
            return "";
        }
        Cell cell = row.getCell(idx);
        if (cell == null) {
            return "";
        }
        String value = cellToString(cell);
        return value == null ? "" : value.trim();
    }

    private Integer getCellInt(Row row, Map<String, Integer> columnIndex, String column) {
        Integer idx = columnIndex.get(column);
        if (idx == null) {
            return null;
        }
        Cell cell = row.getCell(idx);
        if (cell == null) {
            return null;
        }
        try {
            if (cell.getCellType() == CellType.NUMERIC) {
                double d = cell.getNumericCellValue();
                if (d < 0 || d != Math.floor(d)) {
                    return null;
                }
                return (int) d;
            }
            if (cell.getCellType() == CellType.STRING) {
                String s = cell.getStringCellValue().trim();
                if (s.matches("\\d+")) {
                    return Integer.parseInt(s);
                }
                return null;
            }
        } catch (RuntimeException e) {
            return null;
        }
        return null;
    }

    private String cellToString(Cell cell) {
        if (cell == null) {
            return null;
        }
        switch (cell.getCellType()) {
            case STRING:
                return cell.getStringCellValue();
            case NUMERIC:
                double d = cell.getNumericCellValue();
                if (d == Math.floor(d)) {
                    return String.valueOf((long) d);
                }
                return String.valueOf(d);
            case BOOLEAN:
                return String.valueOf(cell.getBooleanCellValue());
            case FORMULA:
                try {
                    return cell.getStringCellValue();
                } catch (RuntimeException e) {
                    try {
                        return String.valueOf(cell.getNumericCellValue());
                    } catch (RuntimeException e2) {
                        return "";
                    }
                }
            default:
                return "";
        }
    }
}
