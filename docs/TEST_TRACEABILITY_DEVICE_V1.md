# Device Traceability V1

| Area | Current status |
|---|---|
| Gemini direct BYOK transport | Auth Key verified on `SM-A546B` with real HTTP 200 minimal request; weekly-plan JSON output remains invalid and is not persisted |
| OpenAI direct BYOK regression | Implemented; real device test pending user key |
| Shared Keystore/AES-GCM credential store | Implemented; build/unit verification pending |
| Firebase generic configuration | Preserved via Google Services plugin and `google-services.json` |
| Firebase AI Logic Gemini gateway | Disabled and removed from runtime |
| Diet weekly-plan real provider flow | Executed on device; native schema 400, JSON-only 200 with non-object output; canonical validation correctly rejected; persistence NOT RUN |
| Gemini schema probe A-E | PASS on device; full-schema probe blocked by free-tier quota 429 |
| Gemini usage/cost | No valid weekly-plan usage metadata; monetary cost not calculated, Google Cloud Billing is authoritative |
| Weekly-plan parser diagnosis | PASS: real device classified output as `TRUNCATED_JSON` from `finishReason=MAX_TOKENS`; no JSON repair and no persistence |
| Alimentazione navigation gate | Implemented; Settings device coverage PASS |
| Saved-key visibility | Implemented; Settings device coverage PASS, plaintext never rendered |
| BIA photo import agent | Implemented provider-neutral; real provider/image test pending personal key and test image |
| BIA import persistence | Uses existing explicit BIA save path; real device confirmation test pending |
| BIA import scope guard | Implemented: strict `isBiaDocument` classification and local rejection before preview |
