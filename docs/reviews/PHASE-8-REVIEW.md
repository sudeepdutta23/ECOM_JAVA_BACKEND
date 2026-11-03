# Phase 8 review: Files

- Date: 2026-11-03
- Scope: `common.storage`, product images in the catalog module, upload limits, migration V17
- Result: complete; all automated tests passing (553 unit, 361 integration; see [TESTING.md](../TESTING.md))

## 1. Security review

| Attack or failure | Blocked by | Evidence |
|---|---|---|
| Stored XSS through an SVG or HTML file named `.png` | Type from the bytes; only JPEG and PNG signatures | `ImageProcessorTest.theTypeComesFromTheBytesNotTheName`, `ProductImageIT.scriptsAndOtherFilesDisguisedAsImagesAreRefused` |
| A polyglot (valid image plus appended script) | Re-encoding keeps only the pixels | `ImageProcessorTest.anythingHiddenAfterTheImageDataIsGoneAfterReEncoding` |
| GPS position and other EXIF data leaking from a photo | Re-encoding without metadata | `ImageProcessorTest.reEncodedJpegsCarryNoExifSegment` |
| Decompression bomb | Dimensions read from the header and refused before decoding | `ImageProcessorTest.aDecompressionBombIsRefusedFromItsHeader` |
| Memory or disk exhaustion by size | 5 MB per file, 413 before any handler; 8 images per product | `ProductImageIT.tooLargeUploadsAreRefusedBeforeAnyCodeReadsThem`, `...AtMostEightImages...` |
| Path traversal through the file name | Server-made keys; key pattern; root check | `ProductImageIT` (`../../../etc/passwd.png`), `LocalFileStorageTest` |
| Another seller uploads, previews or deletes | Guard, organisation-scoped finders | `ProductImageIT.outsidersCannotUploadOrSeeASellersImages`, `TenantIsolationIT` (26 operations, multipart included) |
| Images of drafts or suspended sellers are public | Served only while the product is on sale | `ProductImageIT` |
| A browser sniffs a different type | Content type from the stored format, `nosniff`, no charset on binary | `ProductImageIT` |
| Orphaned files or rows pointing nowhere | Rollback deletes the new file; delete removes the file after commit | `ProductImageService`, `ProductImageIT` |

### Findings fixed during the phase

| # | Issue | Fix |
|---|---|---|
| 1 | `server.servlet.encoding.force=true` added `charset=UTF-8` to every response, binary images included | Force the charset on requests only |
| 2 | Oversized uploads ended in a connection reset instead of a 413: Tomcat stops reading after 2 MB by default | `server.tomcat.max-swallow-size: 10MB` |
| 3 | Mockito's runtime self-attach failed intermittently on JDK 21 | Mockito loaded as `-javaagent` in Surefire and Failsafe (`maven-dependency-plugin:properties`) |

### Accepted risks

| Item | Decision |
|---|---|
| Local disk storage | A single server only; S3 behind `FileStorage` and a CDN for production scale (Phases 15 and 16) |
| Images are decoded in the request thread | Bounded by size and dimensions; a dedicated pool or a worker for heavy processing later |
| A file can outlive its row after a crash between commit and delete | Cleanup job (Phase 12) |
| No virus scanning | Re-encoding removes everything but pixels; documents (PDFs) would need scanning before they are accepted |
| No image resizing or thumbnails | The CDN or a later step can derive sizes from the stored original |

## 2. Performance review

| Path | Cost |
|---|---|
| Upload | One decode and one encode, at most 25 megapixels; one row |
| Storefront page | One extra query for all images of the page |
| Public image | One product lookup, one image lookup, one file read; cacheable for a day |

`QueryPlanIT` checks one new plan.
