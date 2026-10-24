# ScanVault — Future Features Archive

This directory contains complete source code, extracted snippets, reintegration guides, and architecture references for 6 features temporarily removed prior to release:

1. **`id_card_mode/`**: ID card mode (Front and back on one single A4 page with 85.6 × 54 mm physical dimensions and cutting guide).
2. **`mask_aadhaar/`**: Mask Aadhaar card (UIDAI-compliant detection and masking of the first 8 digits with "XXXX XXXX").
3. **`searchable_pdf/`**: Searchable PDF (invisible OCR text layer overlay in exported PDF for selection and Ctrl+F search).
4. **`qr_barcode_scanner/`**: QR code and barcode scanner (scans 1D/2D codes, parses UPI payments, Wi-Fi credentials, URLs).
5. **`passport_photo_maker/`**: Passport photo maker (3.5 × 4.5 cm / 7:9 ratio crop, $\le$ 45 KB government exam/portal compression).
6. **`perspective_crop_warp/`**: Perspective crop & warp (4-pin quadrilateral de-skewing using `Matrix.setPolyToPoly`).

## Structure of each feature folder:
- **`com/example/ScanVault/...`**: Full original exclusive Kotlin source files preserved with their original package path.
- **`extracted_snippets.md`**: Exact extracted snippets from shared files (Activity, navigation graph, menus, strings.xml, manifest, build.gradle) with line numbers, location, and surrounding context.
- **`shared_reference/`**: Snapshots of shared files that interact with this feature, saved for contextual reference.
- **`REINTEGRATION.md`**: Step-by-step instructions to copy files back, re-wire menus/routes/strings/dependencies, and known issues that need to be addressed before re-enabling.

## Removal Patch
- **`removal.patch`**: Comprehensive unified diff of all modifications made during feature removal, allowing 1-click reversion or targeted patch application.
