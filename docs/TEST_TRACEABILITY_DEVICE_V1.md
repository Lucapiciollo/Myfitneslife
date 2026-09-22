# Device Traceability V1

| Area | Current status |
|---|---|
| Gemini direct BYOK transport | Auth Key verified on `SM-A546B` with real HTTP 200 structured responses; weekly-plan JSON-only retry passed local validation and was persisted |
| OpenAI direct BYOK regression | Implemented; real device test pending user key |
| Shared Keystore/AES-GCM credential store | Implemented; build/unit verification pending |
| Firebase generic configuration | Preserved via Google Services plugin and `google-services.json` |
| Firebase AI Logic Gemini gateway | Disabled and removed from runtime |
| Diet weekly-plan real provider flow | Executed on device; native schema fallback to JSON-only, usage metadata received, first invalid output rejected, retry passed canonical/business validation, and plan persistence verified |
| Gemini schema probe A-E | PASS on device; full-schema probe blocked by free-tier quota 429 |
| Gemini usage/cost | Weekly-plan usage metadata received and stored locally; monetary cost remains authoritative only through Google Cloud Billing |
| Weekly-plan parser diagnosis | PASS: real device classified the initial incomplete output without repair or persistence; retry succeeded with a complete validated plan |
| Alimentazione navigation gate | Implemented; Settings device coverage PASS |
| Saved-key visibility | Implemented; Settings device coverage PASS, plaintext never rendered |
| BIA photo import agent | Implemented provider-neutral; real provider/image test pending personal key and test image |
| BIA import persistence | Uses existing explicit BIA save path; real device confirmation test pending |
| BIA import scope guard | Implemented: strict `isBiaDocument` classification and local rejection before preview |
