# 0.7.0 — borderless live app cards and round corners

Requested from device screenshots 2659/2661 and vivo references 2662/2663.

- Removed the custom black application title/icon row from every small window. The TextureView fills the full card again; no 24dp top inset remains. Native application toolbars are retained.
- Kept a small transparent centered three-dot control over the card. It opens the existing replace, close, main-window and native-touch actions. No app label is painted above the app. In direct-touch mode, the menu hit area remains accessible.
- Cards use a real rounded outline rather than a decorative border. Radius is capped proportionally for small cards.
- Main task leashes receive compositor corner radius in the same transaction as their crop/scale. The source radius compensates for scale; fullscreen restore clears it. Native reparent fitting, Launcher/assistant roots, swap/drag previews and frozen group frames also apply round corners.
- Top-layout sizing no longer includes the deleted header; the released height goes back to the main region. Existing native compact top app canvases, input routing, picker cache and profile identities remain functional.
- Frame paths reuse the existing render/transaction lifecycle. No extra polling, blur pass, high-frequency capture, system window or per-frame task query was added.

Validation is compile/unit/signature based. There is no attached PMS110. Static references cannot establish animation timing, frame pacing or exact visual equivalence with vivo's proprietary compositor. Device comparison and input testing remain necessary; this is not a claim of perfect one-to-one reproduction.
