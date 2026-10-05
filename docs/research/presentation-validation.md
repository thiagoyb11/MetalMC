# SDL/Metal presentation validation — 2026-10-03

Command executed by the user in their normal macOS Terminal:

```sh
cd /Users/thiago/Desktop/MetalMC
python3 tools/presentation_test.py --exercise
```

The user supplied a screenshot of the colored triangle and Terminal output,
then confirmed **“funciono”**. The screenshot shows the blue top vertex and
red/green bottom vertices. The complete on-disk log was inspected by the agent.

Recorded result:

- Apple M5; Metal API validation enabled, JNI checks enabled.
- Run started at `2026-10-03T16:58:37.993783+00:00`.
- Exit code 0; final `PASS` for GPU submission/completion, frame bounds,
  lifecycle and JNI boundary checks.
- 862 submitted, 862 completed, 273 skipped, 0 in flight at shutdown, peak 2
  (configured maximum 3).
- Initial logical size 960×600, physical size 1920×1200, pixel density 2.0.
- Resized physical sizes 1282×826 and 2200×1400 recorded.
- Hidden/shown, minimized/restored, entered/left fullscreen events recorded;
  fullscreen physical dimensions 3420×2148, restored to 1920×1200.
- Surface drain, idempotent close, invalid/stale IDs, mapping-free surface
  ownership and layer detach/reattach checks completed in the runner.

The executable used native ABI 2. The subsequent buffer increment extends ABI
to 3 and preserves the presentation implementation; Java/native compilation and
offscreen/buffer GPU validation pass for ABI 3. ABI 3 has not had another normal
Terminal visible-window run; no repeat is currently required for buffer-only
changes.

ABI 4 subsequently adds private textures, views and samplers; full resource,
buffer and offscreen GPU diagnostics pass. The presentation code is preserved.
No additional visible-window run is recorded or required for this resource-only
increment. See [resource validation](resource-validation.md).

Original reports are the last-run files `build/reports/sdl-presentation.log` and
`build/reports/sdl-presentation.json`; a later test may overwrite them. The JSON
keeps `visual_verification: pending` because the automated launcher cannot assess
images. This document records the separate human confirmation.

Agent-launched processes still fail at SDL video initialization with
`The video driver did not add any displays`; its cause is unresolved. The user
test establishes independent direct Metal presentation, not in-game rendering,
performance gains or stability over long sessions/on other machines.
