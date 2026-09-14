# Device Traceability V1

| Area | Current status |
|---|---|
| Gemini direct BYOK transport | Implemented; real device test pending user key |
| OpenAI direct BYOK regression | Implemented; real device test pending user key |
| Shared Keystore/AES-GCM credential store | Implemented; build/unit verification pending |
| Firebase generic configuration | Preserved via Google Services plugin and `google-services.json` |
| Firebase AI Logic Gemini gateway | Disabled and removed from runtime |
| Diet/meal swap/cheat/image real provider flows | NOT RUN until a personal key is supplied |
| Alimentazione navigation gate | Implemented; Settings device coverage PASS |
| Saved-key visibility | Implemented; Settings device coverage PASS, plaintext never rendered |
| BIA photo import agent | Implemented provider-neutral; real provider/image test pending personal key and test image |
| BIA import persistence | Uses existing explicit BIA save path; real device confirmation test pending |
| BIA import scope guard | Implemented: strict `isBiaDocument` classification and local rejection before preview |
