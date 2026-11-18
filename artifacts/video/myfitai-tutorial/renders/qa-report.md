# QA audiovisivo — MyFitAI tutorial

**Esito: NON ESEGUITO / BLOCCATO.** Non esiste un render da controllare.

| Controllo | Esito |
|---|---|
| Build dell’APK da source | PASS — Gradle 8.14.3, `:app:assembleDebug` |
| SHA-256 APK | `590D93F222A9DABFF5EEA3A8CDB6EE934ABD2419B87974A97AA8A721CB0D8E43` |
| Installazione sul target autorizzato | PASS — `adb install -r`, `emulator-5554`, `com.myfitai.app` |
| Avvio e verifica del percorso UI | FAIL — ANR di Android/System UI; schermata app non raggiunta |
| Acquisizione del prodotto | NON ESEGUITA |
| Narrazione/campione e approvazione umana | NON ESEGUITI |
| Musica, mix e licenze | NON ESEGUITI |
| Timeline/SRT/sincronizzazione | NON ESEGUITE |
| Render, codec, risoluzione, fps, loudness, silenzi, black/freeze | NON APPLICABILE — render assente |
| Verifica fotogrammi/privacy del video | NON ESEGUITA — le sole immagini sono diagnostiche dell’ANR |

Nessun output è classificabile come anteprima o video finale. Le tre schermate in `recordings/` documentano solo il blocco e non mostrano MyFitAI.
