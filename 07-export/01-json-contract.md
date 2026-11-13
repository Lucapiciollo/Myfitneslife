# Export JSON

Il file deve contenere:
- profilo
- misurazioni BIA
- misure corporee
- allenamenti: DEPRECATI, lo schema export v3 non include `workouts`, check-in giornalieri né `trainingProgram` (i backup v1/v2 che li contengono restano importabili e vengono ignorati in lettura)
- target nutrizionali
- piani alimentari
- sgarri
- analisi IA
- trend calcolati

Obiettivo: poter condividere il file con ChatGPT/agenti esterni senza dover ricostruire il contesto manualmente.
