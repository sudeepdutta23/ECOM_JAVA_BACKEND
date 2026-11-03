# Files design (Phase 8)

Status: complete (Phase 8 review: [PHASE-8-REVIEW.md](../reviews/PHASE-8-REVIEW.md)).

## 1. Purpose and placement

Sellers upload product photos; the storefront shows them. Uploads are the most dangerous input a web application takes: someone else's bytes, stored on our disks and served from our domain. Phase 8 handles them in one place.

| Part | Where |
|---|---|
| `FileStorage` port, `LocalFileStorage` | `common.storage`: any module can store bytes; none knows where they live |
| `ImageProcessor` (validation and re-encoding) | `catalog.service` |
| `product_images` metadata, upload, list, preview, delete, public serving | `catalog` |

## 2. What happens to an upload

| Step | Defence | Against |
|---|---|---|
| 1 | Multipart limit 5 MB per file, 6 MB per request (`spring.servlet.multipart`); larger requests get 413 `PAYLOAD_TOO_LARGE` before any code reads them. `server.tomcat.max-swallow-size` reads the rest, so the client sees the 413 | Filling memory or disk |
| 2 | Seller guard with `CATALOG_MANAGE` and the seller lock, product scoped to the seller, at most 8 images per product (409 `CATALOG_IMAGE_LIMIT`) | Other sellers, suspended sellers, unbounded storage |
| 3 | **Type from the bytes**: only the JPEG (`FF D8 FF`) and PNG signatures pass. The file name and the client's Content-Type are ignored | An HTML, SVG or script file renamed to `.png`, which a browser could run on our domain |
| 4 | **Dimensions before pixels**: the header is read first; more than 6000 px a side or 25 megapixels is refused before decoding | Decompression bombs: a few KB claiming 100 000 x 100 000 pixels |
| 5 | **Decode and re-encode**: what is stored is a fresh PNG or JPEG of the decoded pixels, without any metadata | EXIF (GPS position, camera serial, owner's name), payloads appended to or hidden in the file (polyglots), malformed structures aimed at image libraries further down |
| 6 | **Key from the server**: `product-images/{organizationId}/{random UUID}.png`; the upload's file name is never used | Path traversal (`../../etc/passwd`), overwriting other files, guessable names |
| 7 | Store, then insert the row; a rollback deletes the file again (`TransactionSynchronization`) | Orphaned files |

`LocalFileStorage` adds two more checks of its own: the key must match a strict pattern, and the resolved path must stay below the storage root. It writes to a temporary file and moves it into place atomically, so a reader never sees half a file.

## 3. Serving

| Path | Who | Headers |
|---|---|---|
| `GET /api/v1/catalog/products/{productId}/images/{imageId}` | Anyone, while the product is on sale (404 otherwise, like an unknown ID) | `Content-Type` from the stored, re-encoded format; `Cache-Control: public, max-age=86400`; `X-Content-Type-Options: nosniff` (Spring Security, every response) |
| `GET /api/v1/organizations/{orgId}/products/{productId}/images/{imageId}/content` | Members, also for drafts | `Cache-Control: no-store` |

The storefront's product responses list image URLs; a page costs one more query (all images of the page's products).

**Content type comes from what we stored**, never from the upload: a browser is never told to treat bytes as anything but an image. `server.servlet.encoding` forces UTF-8 on requests only, so binary responses carry no charset.

## 4. Deleting

`DELETE .../images/{imageId}`: the row goes in the transaction, the file after commit. A rollback never leaves a row pointing to a missing file; a crash after commit can leave a file without a row, which a cleanup job removes (Phase 12). Deleting a product's row (only tests do) cascades to its image rows.

## 5. Storage

`app.files.storage: local` writes below `app.files.local-dir` (`FILES_LOCAL_DIR`, default the system temp directory). That works for development, tests and a single server. Several servers need shared storage: an S3 (or compatible) implementation of the same `FileStorage` port, then a CDN in front of the public URLs (Phase 15). Nothing outside `common.storage` changes.

## 6. Rules

| # | Rule | Enforced by |
|---|---|---|
| 1 | Only JPEG and PNG, decided from the bytes | `ImageProcessor` |
| 2 | Nothing of the uploaded file survives but its pixels | Re-encoding without metadata |
| 3 | No user-supplied text ever becomes a path | Server-made keys, key pattern, root check |
| 4 | Images of products not on sale are not public | `ProductImageService.publicContent` |
| 5 | A seller's images are its own | Guard, organisation-scoped finders, `TenantIsolationIT` |
| 6 | File and row stay consistent with the transaction | Synchronizations on rollback and commit |

## 7. Data model (V17)

`product_images (id, product_id, organization_id, storage_key, content_type, size_bytes, width, height, created_at)`: CHECKs on content type (JPEG, PNG), size and dimensions; unique storage key; `idx_product_images_product_id (product_id, id)` for a product's images and a page's images.
