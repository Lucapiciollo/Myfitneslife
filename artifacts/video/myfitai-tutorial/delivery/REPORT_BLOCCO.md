# Produzione tutorial MyFitAI — interruzione per ANR AVD

**Stato:** bloccato prima dell’acquisizione dell’app e del copione verificato. Nessun video (né preview) prodotto.

## Perimetro e autorizzazioni
- Root autorevole: `C:\Users\LucaPiciollo\Luca\Myfitneslife`.
- Output nuovo: `artifacts/video/myfitai-tutorial/`, assente prima dell’avvio.
- Branch `main`, HEAD `71ffcbbba4cf5064f1d90b2345c459a3a248c240`.
- Non è stata verificata la natura di eventuali dati già presenti in `Medium_Phone`, perché l’interfaccia MyFitAI non è mai diventata disponibile; non sono stati acquisiti contenuti del profilo. È stato tentato un solo riavvio ordinario dell’AVD per recuperare la UI, senza wipe o reset.
- Non sono stati usati `SM-A546B`, `RoomMigrationTest`, provider IA o credenziali. Nessun seed, onboarding, salvataggio, cambio profilo, wipe/reset, uninstall o `pm clear`.
- I 171 file non tracciati preesistenti nel checkout sono stati lasciati intatti.

## Build e installazione
- Il primo tentativo con Gradle 9.6.0 è fallito per incompatibilità con AGP 8.13.0 (`InternalProblems` rimosso).
- `:app:assembleDebug` eseguito con Gradle 8.14.3 cached: **BUILD SUCCESSFUL**, 14m51s.
- APK: `android/app/build/outputs/apk/debug/app-debug.apk`.
- SHA-256: `590D93F222A9DABFF5EEA3A8CDB6EE934ABD2419B87974A97AA8A721CB0D8E43`.
- Installazione `adb -s emulator-5554 install -r`: **Success**. Package `com.myfitai.app`, versione `0.1.0`; `firstInstallTime` è rimasto `2026-10-03 14:30:54`.

## Blocco runtime
Dopo il primo avvio, Android ha mostrato `Process system isn't responding`; dopo aver scelto solo **Wait**, è comparso anche `System UI isn't responding`. Due attese non hanno ripristinato la schermata. È stato tentato un solo riavvio ordinario dell’AVD, senza wipe/reset; la procedura di attesa non ha rilevato `sys.boot_completed=1` entro 240 secondi. Una successiva lettura `adb devices -l` mostrava `emulator-5554` online, ma non è stata tentata una nuova apertura dell’app. Il log successivamente ricevuto mostra errori gfxstream/OpenGL (`glReadPixels` 0x506, `glPixelStorei`/shader 0x501, compilazione shader fallita) e il salvataggio dello snapshot `default_boot` durante l’uscita dell’emulatore. È quindi plausibile un problema del backend grafico dell’AVD; non è una diagnosi definitiva della causa dell’ANR.

Il filtro read-only di `dumpsys jobscheduler` non ha mostrato job `com.myfitai.app`; nessuna generazione o chiamata IA è stata avviata. Non sono state effettuate query al database.

## File prodotti
Solo schermate diagnostiche dell’ANR, non utilizzabili come riprese del prodotto:
- `recordings/inspection-home.png`
- `recordings/inspection-after-wait.png`
- `recordings/inspection-after-wait-2.png`

Non sono stati creati copione, storyboard, timeline, audio, musica, sottotitoli o render perché nessun percorso reale nell’app è stato verificato.

## Trattamento pianificato, non prodotto
- Stile previsto: **Academy**, perché è un tutorial.
- Nessun template/asset TurniPro accessibile: directory `C:\Users\LucaPiciollo\Luca\TurniPro` assente e nessuna struttura video nel progetto MyFitAI.
- Serena medium locale è installata; Serena high assente. Piper non era in ascolto su `127.0.0.1:5812`; non è stata generata voce.
- Nessun brano con licenza documentata trovato nel progetto. Era previsto di creare una base ambient originale senza campioni esterni, ma non è stata composta.

## Per riprendere
Ripristinare manualmente la stabilità di `Medium_Phone` e confermare che sia pronto. Un eventuale test di avvio con renderer diverso richiede nuova autorizzazione; non usare `-wipe-data`, un altro AVD o il device fisico. Riprendere esclusivamente su questo AVD e in-place. La directory di output esiste già: conservarne i file diagnostici e non sovrascriverli.
