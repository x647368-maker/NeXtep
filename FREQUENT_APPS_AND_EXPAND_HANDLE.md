# 0.8.0 — recent/frequent apps and visible expand controls

Requested from PMS110 screenshots 2669/2671/2672.

- The chooser has a fixed quick row for up to five real recent/frequent launcher apps. Ranking combines available seven-day usage summaries, native task recents and locally observed workspace app selections. User/profile identities stay separate; non-launchable/removed entries are excluded and duplicate activity entries are collapsed per package/user. No arbitrary alphabetic apps are labelled frequent.
- Querying and ranking run in a coalesced background request, with a short cache. The chooser keeps its existing asynchronous app catalogue and recycled full grid. Search hides the quick row while filtering. Cold loading has a reserved placeholder so the full grid does not jump when suggestions arrive.
- Collapsed rail fitting and expand-button fitting are separate. A one-pixel hidden top rail is no longer used to calculate a negative footer position.
- Collapsed portrait side controls have a 44x72dp target and a visible expand label. Top controls have a 112x44dp target in a dedicated band below the status area. Their space is reserved before fitting the real main task. Landscape controls also remain within navigation-safe bounds. Main menu and expand controls occupy separate rectangles.
- Expanded app cards, round corners, native tasks, window replacement/close and input routing are retained.

Validation: compile, pure ranking/geometry regressions and fixed-certificate upgrade verification. No physical PMS110 is attached. Actual usage-stat availability, touch hit areas and post-collapse rendering still require device verification. When usage summaries are unavailable, native recent tasks and local selection records are used without asking the user for additional setup.
