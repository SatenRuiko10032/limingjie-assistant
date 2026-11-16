# Shop random-imprint reference crops

These five icon crops are recognition evidence only. A crop never authorizes a purchase without
same-card text, the actual integer price, a complete enabled Buy template, and the current shop
capture/evidence epoch. In particular the cropped-title fallback is RANDOM-only; these assets do
not relax relic identity or CHOICE-imprint checks.

- random_gain.png, random_interference.png, random_strengthening.png: extracted from the real
  1280×720 capture reference-shop-scrolled-20261115-020820.png supplied for this change. The exact
  capture is preserved as android/app/src/test/resources/shop/reference_shop_scrolled_20261115.png.
  Half-open source rectangles: (65,140)-(169,244), (449,140)-(553,244), (834,140)-(938,244).
- random_attack.png, random_healing.png: extracted from the existing 1920×1080 fixture
  android/app/src/test/resources/shop/imprints-20260914.jpg, rectangles (98,315)-(253,470) and
  (673,315)-(828,470). Its real price is 900; no recognizer assumes a universal price of 780.

The matcher requires both the pink random-imprint core artwork and the role corner badge, with
RGB thresholds and a competing-role margin. The price/blue button alone is never sufficient.
The source captures are game-screen fixtures, not generated artwork or OCR output.
