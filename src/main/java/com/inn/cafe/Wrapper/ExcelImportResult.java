package com.inn.cafe.Wrapper;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Structured result of an Excel product import (Issue #2 fix).
 *
 * The import is ID-independent: any 'id' column present in the sheet is ignored and
 * database-generated identity is always used. This object reports exactly what happened
 * per row so the client can render imported/skipped/failed counts and row-level errors.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ExcelImportResult {

    private String fileName;
    private boolean parsed;              // false => file-level failure, see message
    private String message;              // file-level message (failure reason or summary)
    private int totalRows;               // data rows seen (excluding header + blank rows)
    private int importedCount;           // rows successfully persisted
    private int duplicateCount;          // rows skipped because product exists in same category
    private int failedCount;             // rows rejected due to validation errors
    private int categoriesCreated;       // categories auto-created by name
    private boolean idColumnIgnored;     // true when the sheet contained an 'id' column (ignored by design)
    private List<RowError> errors = new ArrayList<>(); // row-level errors (first 50)

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RowError {
        private int row;       // 1-based sheet row number as seen in Excel
        private String field;  // offending field or "row"
        private String reason; // human-readable reason
    }

    public void addError(int row, String field, String reason) {
        if (errors.size() < 50) {
            errors.add(new RowError(row, field, reason));
        }
    }
}
