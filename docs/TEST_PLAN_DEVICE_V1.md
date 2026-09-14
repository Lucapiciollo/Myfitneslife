# Device Test Plan V1

## Provider Scope
- Gemini: direct Gemini Developer API with user BYOK key.
- OpenAI: direct OpenAI API with user BYOK key.
- Firebase remains configured for future generic services; Firebase AI Logic is not used for Gemini.

## Required Device Tests
- `GEMINI-BYOK-01..05`: Settings verification, persistence after process restart, invalid-key preservation, deletion, and missing-key block.
- `GEMINI-BYOK-06..09`: diet generation, meal swap, cheat two-phase flow, and label-image multimodal request.
- `GEMINI-BYOK-10`: Gemini/OpenAI switching while preserving each encrypted credential.
- `AI-GATE-01`: tap Alimentazione with active provider missing; verify blocking dialog and Settings CTA.
- `AI-CREDENTIAL-01`: configure both keys, verify saved values are hidden, then use only Replace/Delete controls.
- `BIA-IMPORT-01`: choose a camera/gallery image, receive structured extraction preview, edit values, confirm into the BIA form, and save only after explicit user action.
- `BIA-IMPORT-02`: unreadable or partial image must not invent values; missing fields remain editable/empty.
- `BIA-IMPORT-03`: non-BIA image must be rejected with no numeric preview and no persistence.

Real provider tests are PASS only when the request returns canonical JSON, local schema/business validation passes, and persistence is observed on device. No API key belongs in test output or repository files.
