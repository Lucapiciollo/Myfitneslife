# Selezione provider AI a runtime

## UX prevista
In `Impostazioni > Intelligenza artificiale`:

- Switch `Usa Gemini`
- Stato provider attivo
- Campo `OpenAI API key`, visibile soltanto quando Gemini è disattivato
- Azione futura `Verifica configurazione`

## V1
Gemini e OpenAI sono BYOK. Entrambe le chiavi sono protette da Android Keystore/AES-GCM. Gemini chiama direttamente Gemini Developer API; Firebase AI Logic non è usato.

## Stato runtime
```text
useGemini = true + Gemini key presente -> GEMINI
useGemini = true + Gemini key assente -> NOT_CONFIGURED
useGemini = false + key presente -> OPENAI
useGemini = false + key assente -> NOT_CONFIGURED
```

## Sicurezza
- Non salvare chiavi in chiaro in file di progetto.
- Non includere chiavi in log, crash report o export.
- Firebase e `google-services.json` restano disponibili per eventuali servizi futuri, ma non sono prerequisiti per Gemini BYOK.
