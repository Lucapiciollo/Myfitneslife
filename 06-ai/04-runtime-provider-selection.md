# Selezione provider AI a runtime

## UX prevista
In `Impostazioni > Intelligenza artificiale`:

- Switch `Usa Gemini`
- Stato provider attivo
- Campo `OpenAI API key`, visibile soltanto quando Gemini è disattivato
- Azione futura `Verifica configurazione`

## V1
La UI e le classi di configurazione sono predisposte, ma le chiamate di rete NON sono ancora collegate.

## Stato runtime
```text
useGemini = true  -> GEMINI
useGemini = false + key presente -> OPENAI
useGemini = false + key assente -> NOT_CONFIGURED
```

## Sicurezza
- Non salvare chiavi in chiaro in file di progetto.
- Non includere chiavi in log, crash report o export.
- Prima dell'integrazione reale, usare Android Keystore o un proxy backend secondo la strategia scelta.
