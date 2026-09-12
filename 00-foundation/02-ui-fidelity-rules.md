# Regole imperative di fedeltà UI

1. Il mock approvato è vincolante.
2. Prima di implementare una schermata, classificare ogni elemento come:
   - background/decorazione
   - componente reale/interattivo
3. Gli sfondi devono essere layer separati.
4. Testi, card, pulsanti, grafici, input, badge e icone funzionali devono essere componenti reali.
5. Vietato incorporare UI funzionale in immagini raster.
6. Vietata la reinterpretazione autonoma del layout.
7. Devono coincidere il più possibile:
   - struttura
   - proporzioni
   - padding
   - margin
   - gap
   - allineamenti
   - tipografia
   - radius
   - bordi
   - ombre
   - colori
   - icone
   - responsive
8. Nessuna schermata è completata senza confronto mock vs implementazione.
9. Se esistono differenze visive sostanziali, la schermata non è Done.
