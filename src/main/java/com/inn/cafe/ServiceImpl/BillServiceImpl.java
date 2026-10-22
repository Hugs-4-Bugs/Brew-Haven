package com.inn.cafe.ServiceImpl;

import com.inn.cafe.Constents.CafeConstants;
import com.inn.cafe.DAO.BillDao;
import com.inn.cafe.JWT.JwtFilter;
import com.inn.cafe.POJO.Bill;
import com.inn.cafe.POJO.PaymentIntent;
import com.inn.cafe.Service.BillService;
import com.inn.cafe.Utils.CafeUtils;
import com.itextpdf.text.*;
import com.itextpdf.text.pdf.PdfPCell;
import com.itextpdf.text.pdf.PdfPTable;
import com.itextpdf.text.pdf.PdfWriter;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.io.IOUtils;
import org.json.JSONArray;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Bill + PDF service.
 *
 * Fixes applied (see docs/audit/DEFECT_REGISTER.md B.1-B.7):
 *  - B.1: PDF store location is configurable (cafe.bill.store-location, default
 *    ./Bill Document) instead of a hardcoded developer-machine path; the directory is
 *    created on demand. The legacy constant failed on every other machine.
 *  - B.2: generation order is now validate -> write PDF -> insert bill. Previously the
 *    bill row was persisted BEFORE the (failing) PDF write, so a 500 response left a
 *    persisted bill behind and client retries created duplicate bills.
 *  - B.4: uuid values are strictly validated (BILL-<digits>) before being used in file
 *    paths — path traversal (../../etc/passwd) is rejected.
 *  - B.5: bill deletion is admin-only.
 *  - B.6: PDF header now prints contactNumber (the validated key) instead of the
 *    non-existent "contact" key (which rendered null).
 */
@Slf4j
@Service
public class BillServiceImpl implements BillService {

    /** Strict uuid format — anything else is rejected before touching the filesystem. */
    private static final Pattern SAFE_UUID = Pattern.compile("^[A-Za-z0-9\\-]{1,64}$");

    @Autowired
    JwtFilter jwtFilter;

    @Autowired
    BillDao billDao;

    /** Revenue attribution: records the server-verified CONVERTED campaign event. */
    @Autowired
    com.inn.cafe.ServiceImpl.CampaignServiceImpl campaignServiceImpl;

    @Value("${cafe.bill.store-location:./Bill Document}")
    private String storeLocation;

    @Override
    public ResponseEntity<String> generateReport(Map<String, Object> requestMap) {
        log.info("Inside generateReport");
        try {
            if (!validateRequestMap(requestMap)) {
                return CafeUtils.getResponseEntity("Required data not found", HttpStatus.BAD_REQUEST);
            }
            // validate productDetails is a JSON array up-front (fail fast, no partial state)
            JSONArray jsonArray;
            try {
                jsonArray = CafeUtils.getJsonArrayFromString(String.valueOf(requestMap.get("productDetails")));
            } catch (Exception e) {
                return CafeUtils.getResponseEntity("Invalid Data: productDetails must be a JSON array", HttpStatus.BAD_REQUEST);
            }

            String fileName;
            boolean isRegenerate = requestMap.containsKey("isGenerate") && !Boolean.parseBoolean(String.valueOf(requestMap.get("isGenerate")));
            if (isRegenerate && requestMap.get("uuid") != null && SAFE_UUID.matcher(String.valueOf(requestMap.get("uuid"))).matches()) {
                fileName = String.valueOf(requestMap.get("uuid"));
            } else {
                fileName = CafeUtils.getUUID();
                requestMap.put("uuid", fileName);
            }

            // FIX B.2 ordering: write the PDF first; only persist the bill once the file exists.
            writePdf(fileName, requestMap, jsonArray);
            Bill saved = insertBill(requestMap);

            // Revenue attribution (additive): if the customer applied a campaign
            // code at checkout, validate it server-side and record the CONVERTED
            // event with the bill id + total. Failures NEVER block the order.
            Object campaignCode = requestMap.get("campaignCode");
            if (campaignCode != null && saved != null && saved.getId() != null) {
                try {
                    campaignServiceImpl.attributeConversion(String.valueOf(campaignCode),
                            saved.getId(), saved.getTotal(), new StringBuilder());
                } catch (Exception attrEx) {
                    log.warn("Campaign attribution skipped: {}", attrEx.getMessage());
                }
            }

            return new ResponseEntity<>("{\"uuid\":\"" + fileName + "\"}", HttpStatus.OK);
        } catch (Exception ex) {
            log.error("generateReport failed: {}", ex.getMessage());
        }
        return CafeUtils.getResponseEntity(CafeConstants.SOMETHING_WENT_WRONG, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    private void writePdf(String fileName, Map<String, Object> requestMap, JSONArray jsonArray) throws Exception {
        Path dir = Paths.get(storeLocation);
        Files.createDirectories(dir); // FIX B.1: on-demand, configurable location
        Path pdfPath = dir.resolve(fileName + ".pdf");

        String data = "Name: " + requestMap.get("name") + "\n"
                + "Contact Number: " + requestMap.get("contactNumber") + "\n"   // FIX B.6
                + "Email: " + requestMap.get("email") + "\n"
                + "Payment Method: " + requestMap.get("paymentMethod");

        Document document = new Document();
        PdfWriter.getInstance(document, new FileOutputStream(pdfPath.toFile()));
        document.open();
        setRectangleInPdf(document);

        Paragraph chunk = new Paragraph("Cafe Management System", getFont("Header"));
        chunk.setAlignment(Element.ALIGN_CENTER);
        document.add(chunk);

        Paragraph paragraph = new Paragraph(data + "\n \n", getFont("Data"));
        document.add(paragraph);

        PdfPTable table = new PdfPTable(5);
        table.setWidthPercentage(100);
        addTableHeader(table);
        for (int i = 0; i < jsonArray.length(); i++) {
            addRows(table, CafeUtils.getMapFromJson(jsonArray.getString(i)));
        }
        document.add(table);

        Paragraph footer = new Paragraph("Total : " + requestMap.get("totalAmount") + "\n"
                + "Thank you for visiting. Please visit again!", getFont("Data"));
        document.add(footer);
        document.close();
    }

    private void addRows(PdfPTable table, Map<String, Object> data) {
        table.addCell(String.valueOf(data.get("name")));
        table.addCell(String.valueOf(data.get("category")));
        table.addCell(String.valueOf(data.get("quantity")));
        table.addCell(String.valueOf(data.get("price")));
        table.addCell(String.valueOf(data.get("total")));
    }

    private void addTableHeader(PdfPTable table) {
        Stream.of("Name", "Category", "Quantity", "Price", "Sub Total")
                .forEach(columnTitle -> {
                    PdfPCell header = new PdfPCell();
                    header.setBackgroundColor(BaseColor.LIGHT_GRAY);
                    header.setBorderWidth(2);
                    header.setPhrase(new Phrase(columnTitle));
                    header.setHorizontalAlignment(Element.ALIGN_CENTER);
                    header.setVerticalAlignment(Element.ALIGN_CENTER);
                    table.addCell(header);
                });
    }

    private Font getFont(String type) {
        switch (type) {
            case "Header":
                Font headerFont = FontFactory.getFont(FontFactory.HELVETICA_BOLDOBLIQUE, 18, BaseColor.BLACK);
                headerFont.setStyle(Font.BOLD);
                return headerFont;
            case "Data":
                Font dataFont = FontFactory.getFont(FontFactory.TIMES_ROMAN, 11, BaseColor.BLACK);
                dataFont.setStyle(Font.BOLD);
                return dataFont;
            default:
                return new Font();
        }
    }

    private void setRectangleInPdf(Document document) throws DocumentException {
        Rectangle rectangle = new Rectangle(577, 825, 18, 15);
        rectangle.enableBorderSide(1);
        rectangle.enableBorderSide(2);
        rectangle.enableBorderSide(4);
        rectangle.enableBorderSide(8);
        rectangle.setBackgroundColor(new BaseColor(230, 230, 230));
        rectangle.setBorderWidth(1);
        document.add(rectangle);
    }

    /** FIX B.2: exceptions propagate — the caller only reaches insertBill if this succeeds.
     * Returns the PERSISTED bill (with id) so callers can reference it (campaign attribution). */
    private Bill insertBill(Map<String, Object> requestMap) {
        return insertBill(requestMap, jwtFilter.getCurrentUser());
    }

    /** Overload with an explicit createdBy — the checkout webhook has no JWT context,
     *  so bills created from settled intents attribute to the intent owner. */
    private Bill insertBill(Map<String, Object> requestMap, String createdBy) {
        Bill bill = new Bill();
        bill.setUuid((String) requestMap.get("uuid"));
        bill.setName((String) requestMap.get("name"));
        bill.setEmail((String) requestMap.get("email"));
        bill.setContactNumber((String) requestMap.get("contactNumber"));
        bill.setPaymentMethod((String) requestMap.get("paymentMethod"));
        bill.setTotal(Double.valueOf(String.valueOf(requestMap.get("totalAmount"))).intValue());
        bill.setProductDetails((String) requestMap.get("productDetails"));
        bill.setCreatedBy(createdBy);
        // ADDITIVE: campaign code applied at checkout (null when absent — legacy behavior).
        Object campaignCode = requestMap.get("campaignCode");
        if (campaignCode != null && !String.valueOf(campaignCode).trim().isEmpty()) {
            bill.setCampaignCode(String.valueOf(campaignCode).trim());
        }
        return billDao.save(bill);
    }

    private boolean validateRequestMap(Map<String, Object> requestMap) {
        return requestMap.containsKey("name") &&
                requestMap.containsKey("contactNumber") &&
                requestMap.containsKey("email") &&
                requestMap.containsKey("paymentMethod") &&
                requestMap.containsKey("productDetails") &&
                requestMap.containsKey("totalAmount");
    }

    @Override
    public ResponseEntity<List<Bill>> getBills() {
        log.info("Inside getBills");
        try {
            List<Bill> list;
            if (jwtFilter.isAdmin()) {
                list = billDao.getAllBills();
            } else {
                list = billDao.getBillByUserName(jwtFilter.getCurrentUser());
            }
            return new ResponseEntity<>(list, HttpStatus.OK);
        } catch (Exception ex) {
            log.error("getBills failed: {}", ex.getMessage());
        }
        return new ResponseEntity<>(new java.util.ArrayList<>(), HttpStatus.INTERNAL_SERVER_ERROR);
    }

    /**
     * ADDITIVE endpoint backing (/bill/myBills): the signed-in customer's OWN
     * orders only (email == current user). Admins continue to use /bill/getBills.
     */
    @Override
    public ResponseEntity<List<Bill>> getMyBills() {
        log.info("Inside getMyBills");
        try {
            String email = jwtFilter.getCurrentUser();
            List<Bill> list = email == null
                    ? new java.util.ArrayList<>()
                    : billDao.findAll().stream()
                        .filter(b -> email.equalsIgnoreCase(b.getEmail()))
                        .sorted((a, b) -> Integer.compare(b.getId(), a.getId()))
                        .collect(java.util.stream.Collectors.toList());
            return new ResponseEntity<>(list, HttpStatus.OK);
        } catch (Exception ex) {
            log.error("getMyBills failed: {}", ex.getMessage());
        }
        return new ResponseEntity<>(new java.util.ArrayList<>(), HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Override
    public ResponseEntity<byte[]> getPdf(Map<String, Object> requestMap) {
        log.info("Inside getPdf");
        try {
            Object uuidObj = requestMap.get("uuid");
            if (uuidObj == null || !SAFE_UUID.matcher(String.valueOf(uuidObj)).matches()) {
                // FIX B.4: reject path-traversal-shaped uuids and missing uuids outright
                return new ResponseEntity<>(new byte[0], HttpStatus.BAD_REQUEST);
            }
            String uuid = String.valueOf(uuidObj);
            Path pdfPath = Paths.get(storeLocation).resolve(uuid + ".pdf");

            if (!Files.exists(pdfPath)) {
                // legacy fallback: regenerate for an existing bill with full details
                if (requestMap.containsKey("name") && requestMap.containsKey("productDetails")) {
                    requestMap.put("isGenerate", false);
                    ResponseEntity<String> regen = generateReport(requestMap);
                    if (!regen.getStatusCode().is2xxSuccessful() || !Files.exists(pdfPath)) {
                        return new ResponseEntity<>(new byte[0], HttpStatus.NOT_FOUND);
                    }
                } else {
                    return new ResponseEntity<>(new byte[0], HttpStatus.NOT_FOUND);
                }
            }
            byte[] byteArray = getByteArray(pdfPath.toString());
            return new ResponseEntity<>(byteArray, HttpStatus.OK);
        } catch (Exception ex) {
            log.error("getPdf failed: {}", ex.getMessage());
        }
        return new ResponseEntity<>(new byte[0], HttpStatus.INTERNAL_SERVER_ERROR);
    }

    private byte[] getByteArray(String filePath) throws Exception {
        File initialFile = new File(filePath);
        try (InputStream targetStream = new FileInputStream(initialFile)) {
            return IOUtils.toByteArray(targetStream);
        }
    }

    /** ADDITIVE: plain lookup for checkout receipt/status builders. */
    @Override
    public Bill getBillById(Integer id) {
        if (id == null) {
            return null;
        }
        Optional<Bill> bill = billDao.findById(id);
        return bill.orElse(null);
    }

    /**
     * ADDITIVE: bill creation from a SETTLED payment intent.
     *
     * Every figure comes from the server-computed intent snapshot (which the
     * pricing engine produced from live DB prices): total, productDetails
     * (with verified prices) and the single coupon code. createdBy is the
     * intent owner (webhook calls have no JWT context). Reuses the exact
     * writePdf + insertBill internals as the legacy generateReport flow.
     */
    @Override
    @org.springframework.transaction.annotation.Transactional
    public Bill createBillFromIntent(PaymentIntent intent) {
        if (intent.getBillId() != null) {
            return billDao.findById(intent.getBillId()).orElse(null);
        }
        Map<String, Object> requestMap = new java.util.HashMap<>();
        String uuid = CafeUtils.getUUID();
        requestMap.put("uuid", uuid);
        requestMap.put("name", intent.getCustomerName());
        requestMap.put("email", intent.getEmail());
        requestMap.put("contactNumber", intent.getContactNumber());
        requestMap.put("paymentMethod", intent.getMethod());
        requestMap.put("totalAmount", intent.getTotal());
        requestMap.put("productDetails", intent.getItemsJson());
        if (intent.getCouponCode() != null && !intent.getCouponCode().trim().isEmpty()) {
            requestMap.put("campaignCode", intent.getCouponCode().trim());
        }
        try {
            writePdf(uuid, requestMap, CafeUtils.getJsonArrayFromString(intent.getItemsJson()));
        } catch (Exception e) {
            throw new IllegalStateException("Bill PDF generation failed: " + e.getMessage(), e);
        }
        Bill saved = insertBill(requestMap, intent.getUserId());
        if (saved != null && saved.getId() != null && intent.getCouponCode() != null) {
            try {
                campaignServiceImpl.attributeConversion(intent.getCouponCode(), saved.getId(),
                        saved.getTotal(), new StringBuilder());
            } catch (Exception attrEx) {
                log.warn("Campaign attribution skipped: {}", attrEx.getMessage());
            }
        }
        return saved;
    }

    @Override
    public ResponseEntity<String> deleteBill(Integer id) {
        log.info("Inside deleteBill {}", id);
        try {
            // FIX B.5: bill deletion is admin-only (any authenticated user could previously
            // delete any bill).
            if (!jwtFilter.isAdmin()) {
                return CafeUtils.getResponseEntity(CafeConstants.UNAUTHOROZED_ACCESS, HttpStatus.UNAUTHORIZED);
            }
            Optional<Bill> optional = billDao.findById(id);
            if (optional.isPresent()) {
                billDao.deleteById(id);
                return CafeUtils.getResponseEntity("Bill Deleted Successfully", HttpStatus.OK);
            }
            return CafeUtils.getResponseEntity("Bill id does not exists", HttpStatus.OK);
        } catch (Exception ex) {
            log.error("deleteBill failed: {}", ex.getMessage());
        }
        return CafeUtils.getResponseEntity(CafeConstants.SOMETHING_WENT_WRONG, HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
