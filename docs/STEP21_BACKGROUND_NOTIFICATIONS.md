# Step 21 — Notifiche locali resilienti

## Obiettivo
I promemoria devono funzionare anche quando il processo dell'app non è in memoria.

## Architettura
- `AlarmManager` programma promemoria di sistema: non serve mantenere un service Android sempre vivo.
- `ReminderReceiver` è un `BroadcastReceiver` manifest-registered: Android può ricreare il processo e consegnare l'allarme anche se l'app è stata chiusa normalmente.
- `ReminderRescheduleReceiver` ripristina gli allarmi dopo reboot, aggiornamento app, cambio ora e cambio fuso.
- `MyFitAiApplication` riallinea gli allarmi all'avvio del processo e quando cambia il profilo attivo.
- Android 13+ richiede `POST_NOTIFICATIONS`; l'app lo chiede una sola volta entrando nella Home.

## Promemoria pasti
- Derivano esclusivamente dall'ultima versione reale del piano del profilo attivo.
- Default: 15 minuti prima del pasto.
- Vengono pre-programmati fino a 60 giorni, limitatamente ai piani realmente presenti in Room.
- `setAndAllowWhileIdle` permette la consegna anche in idle/Doze; non viene richiesta la speciale autorizzazione per exact alarms, quindi Android può applicare un piccolo ritardo per ragioni energetiche.
- Tap sulla notifica apre direttamente il `MealDetailActivity` del `mealId` corretto.
- Azione `Posticipa 10 min` crea un nuovo allarme one-shot.
- Dopo generazione/rigenerazione del piano o adattamento per sgarro gli allarmi vengono cancellati e ricostruiti dalla versione corrente, evitando promemoria di pasti non più validi.

## Review settimanale
- Reminder ricorrente il lunedì mattina alle 09:00.
- Apre `WeeklyReviewActivity`.
- Non genera automaticamente una chiamata IA: ricorda soltanto che la review della settimana conclusa può essere generata.

## Persistenza e profili
- Gli ID degli allarmi correnti sono persistiti in SharedPreferences per poter cancellare quelli obsoleti.
- I promemoria pasto includono il profilo attivo nella chiave di scheduling.
- Cambiando profilo, `ActiveProfileStore` provoca un refresh globale degli allarmi.

## Limiti Android da dichiarare
- Funziona se l'app viene chiusa normalmente o il processo viene terminato dal sistema.
- Dopo riavvio del dispositivo gli allarmi vengono ricostruiti via `BOOT_COMPLETED`.
- Un **Force stop** manuale da Impostazioni Android sospende allarmi e receiver dell'app finché l'utente non la apre nuovamente: è un comportamento imposto dal sistema operativo.
- Su dispositivi con politiche OEM aggressive di risparmio energetico gli allarmi non-exact possono essere ritardati.

## QA finale richiesto
- Android 13/14/15/16 con permesso notifiche concesso e negato.
- App in foreground, background, swipe-away e processo terminato.
- Reboot dispositivo.
- Doze/risparmio energetico.
- Cambio fuso/orario.
- Rigenerazione piano mentre esistono allarmi già schedulati.
- Adattamento sgarro e verifica cancellazione dei vecchi mealId.
- Cambio profilo.
- Snooze 10 minuti.
