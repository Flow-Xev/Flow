# Capturing tagged-product responses (debug builds only)

1. Install a DEBUG build, open a video that shows products on youtube.com (same region / sign-in state).
2. If the app shows no products button, the app writes (once per video) to `Android/data/app.zaid.flow/files/flow-debug/`:
   `next_<id>.json`, `next_<id>.scan.txt`, `player_<id>.json`, `player_<id>.scan.txt`.
3. `adb pull /sdcard/Android/data/app.zaid.flow/files/flow-debug/ .` and send the folder back.
   The `.scan.txt` files list every product-like key path and why known cards were dropped.
4. Drop trimmed real JSON into `app/src/test/resources/product_fixtures/`; `RealProductFixturesTest` checks them.
