"""Costruisce il MINI-CATALOGO dello spike S1 (catalog_mini.json).

Mappatura manuale alias italiani -> fdcId USDA (Foundation 2025-12 / SR Legacy 2018).
I valori per100 sono letti dallo zip USDA (CC0) in una cartella temporanea fuori dal repo.

tier:
  A = alimento USDA equivalente (esatto o molto vicino)
  B = proxy USDA dichiarato (alimento italiano senza corrispondente diretto: la voce USDA e' solo la piu' vicina)
  C = valori tipici di etichetta NON USDA, da sostituire con CREA/Ciqual (placeholder dello spike)
state: RAW (crudo/secco/tal quale) | COOKED | AS_SOLD (prodotto confezionato/tal quale)
cooked: fdcId della controparte cotta, per misurare il rischio crudo/cotto
density: g/ml, per i liquidi con unita ml
fixed: voce non modificabile dal solver (sale, integratori, acqua, spezie)
"""
import json
import os
import sys

import usda

# (id, [alias italiani], fdcId, state, tier, extra)
CATALOG = [
    ("olio_evo", ["olio extravergine oliva", "olio extravergine", "olio oliva", "olio evo"], 171413, "AS_SOLD", "A", {"density": 0.91}),
    ("olio_cocco", ["olio cocco"], 171412, "AS_SOLD", "A", {"density": 0.92}),
    ("patate", ["patate", "patata"], 170028, "RAW", "A", {"cooked": 170438}),
    ("patate_dolci", ["patate dolci", "patata dolce"], 168482, "RAW", "A", {"cooked": 168483}),
    ("gallette_riso", ["gallette riso", "gallette"], 170250, "AS_SOLD", "A", {}),
    ("gallette_mais", ["gallette mais"], 169679, "AS_SOLD", "A", {}),
    ("noci", ["noci", "noci secche", "noci sgusciate"], 170187, "AS_SOLD", "A", {}),
    ("mandorle", ["mandorle", "mandorle pelate"], 2346393, "AS_SOLD", "A", {}),
    ("nocciole", ["nocciole"], 170581, "AS_SOLD", "A", {}),
    ("anacardi", ["anacardi"], 170162, "AS_SOLD", "A", {}),
    ("semi_chia", ["semi chia"], 170554, "AS_SOLD", "A", {}),
    ("castagne_bollite", ["castagne bollite", "castagne"], 170168, "COOKED", "A", {}),
    ("zucchine", ["zucchine", "zucchina"], 169291, "RAW", "A", {"cooked": 169292}),
    ("melanzane", ["melanzane"], 169228, "RAW", "A", {"cooked": 169229}),
    ("mela", ["mela", "mele"], 171688, "RAW", "A", {}),
    ("pera", ["pera", "pere"], 332597, "RAW", "A", {}),
    ("banana", ["banana"], 173944, "RAW", "A", {}),
    ("kiwi", ["kiwi"], 168153, "RAW", "A", {}),
    ("arancia", ["arancia", "arance"], 169097, "RAW", "A", {}),
    ("pompelmo", ["pompelmo"], 173033, "RAW", "A", {}),
    ("mirtilli", ["mirtilli", "mirtilli freschi"], 2263889, "RAW", "A", {}),
    ("fragole", ["fragole"], 167762, "RAW", "A", {}),
    ("lamponi", ["lamponi"], 167755, "RAW", "A", {}),
    ("riso_basmati", ["riso basmati", "riso bianco", "riso"], 169756, "RAW", "A", {"cooked": 168935}),
    ("riso_integrale", ["riso integrale"], 169703, "RAW", "A", {}),
    ("pane_integrale", ["pane integrale"], 172688, "AS_SOLD", "A", {}),
    ("pane_segale", ["pane segale"], 172684, "AS_SOLD", "A", {}),
    ("pane_pita", ["pane pita"], 174916, "AS_SOLD", "A", {}),
    ("petto_pollo", ["petto pollo"], 171077, "RAW", "A", {"cooked": 171477}),
    ("petto_tacchino", ["petto tacchino"], 171098, "RAW", "A", {}),
    ("fesa_tacchino_affettata", ["fesa tacchino", "fettine fesa tacchino", "affettato fesa tacchino", "arrosto tacchino"], 174572, "AS_SOLD", "A", {}),
    ("manzo_magro", ["fesa manzo", "carne manzo magra", "manzo magro", "arrosto manzo magro"], 171757, "RAW", "A", {}),
    ("lonza_maiale", ["lonza maiale magra", "lonza maiale", "arista maiale"], 167901, "RAW", "A", {}),
    ("fiocchi_avena", ["fiocchi avena", "farina avena", "cereali integrali fiocchi"], 173904, "RAW", "A", {"cooked": 173905}),
    ("fiocchi_latte", ["fiocchi latte"], 172179, "AS_SOLD", "A", {}),
    ("fiocchi_latte_magri", ["fiocchi latte magri", "fiocchi latte light"], 168114, "AS_SOLD", "A", {}),
    ("miele", ["miele"], 169640, "AS_SOLD", "A", {}),
    ("albume", ["albume", "albumi"], 172183, "AS_SOLD", "A", {"density": 1.03}),
    ("uovo", ["uova gallina", "uovo"], 171287, "RAW", "A", {}),
    ("ricotta", ["ricotta vaccina", "ricotta"], 170851, "AS_SOLD", "A", {}),
    ("ricotta_magra", ["ricotta vaccina light", "ricotta vaccina magra", "ricotta vacca magra"], 171248, "AS_SOLD", "A", {}),
    ("spinaci", ["spinaci", "spinaci freschi"], 168462, "RAW", "A", {"cooked": 168463}),
    ("yogurt_greco_0", ["yogurt greco", "yogurt greco 0 percento", "yogurt greco magro", "yogurt greco 0 grassi", "skyr"], 170894, "AS_SOLD", "A", {}),
    ("yogurt_magro", ["yogurt magro", "yogurt bianco magro", "yogurt magro bianco"], 170887, "AS_SOLD", "A", {}),
    ("yogurt_soia", ["yogurt soia naturale", "yogurt soia"], 173778, "AS_SOLD", "B", {}),
    ("quinoa", ["quinoa"], 168874, "RAW", "A", {"cooked": 168917}),
    ("couscous", ["cous cous", "couscous"], 169699, "RAW", "A", {"cooked": 169700}),
    ("farro", ["farro perlato", "farro"], 2710828, "RAW", "A", {"cooked": 169746}),
    ("orzo", ["orzo perlato"], 170284, "RAW", "A", {"cooked": 170285}),
    ("grano_saraceno", ["fiocchi grano saraceno", "grano saraceno"], 170286, "RAW", "B", {}),
    ("broccoli", ["broccoli"], 170379, "RAW", "A", {"cooked": 169967}),
    ("broccoli_bolliti", ["broccoli bolliti"], 169967, "COOKED", "A", {}),
    ("fagiolini", ["fagiolini", "fagiolini verdi"], 169961, "RAW", "A", {"cooked": 169141}),
    ("peperoni", ["peperoni", "peperone"], 170108, "RAW", "A", {}),
    ("carote", ["carote"], 170393, "RAW", "A", {"cooked": 169339}),
    ("insalata_mista", ["insalata mista", "insalata"], 169249, "RAW", "A", {}),
    ("pomodorini", ["pomodorini", "pomodori insalata", "pomodori", "pomodoro"], 170457, "RAW", "A", {}),
    ("cetrioli", ["cetrioli", "cetriolo"], 168409, "RAW", "A", {}),
    ("asparagi", ["asparagi"], 168389, "RAW", "A", {"cooked": 168390}),
    ("latte_ps", ["latte parzialmente scremato", "latte"], 171267, "AS_SOLD", "A", {"density": 1.03}),
    ("kefir", ["kefir", "kefir latte", "kefir magro", "kefir latte magro", "kefir bianco"], 170904, "AS_SOLD", "A", {"density": 1.03}),
    ("merluzzo", ["filetto merluzzo", "merluzzo fresco", "merluzzo filetto", "merluzzo surgelato", "merluzzo"], 171955, "RAW", "A", {"cooked": 171956}),
    ("nasello", ["filetto nasello", "nasello"], 173713, "RAW", "B", {}),
    ("salmone", ["salmone fresco", "filetto salmone", "trancio salmone", "salmone filetto", "salmone"], 175167, "RAW", "A", {"cooked": 175168}),
    ("orata", ["orata", "filetto orata"], 175142, "RAW", "B", {}),
    ("tonno_fresco", ["trancio tonno"], 175159, "RAW", "A", {}),
    ("gamberetti", ["gamberetti sgusciati", "gamberetti"], 174210, "RAW", "A", {}),
    ("burro_arachidi", ["burro arachidi", "crema arachidi 100", "crema arachidi"], 324860, "AS_SOLD", "A", {}),
    ("crema_mandorle", ["crema mandorle"], 168588, "AS_SOLD", "A", {}),
    ("sciroppo_acero", ["sciroppo acero"], 169661, "AS_SOLD", "A", {}),
    ("cannella", ["cannella", "cannella polvere"], 171320, "AS_SOLD", "A", {"fixed": True}),
    ("cacao_amaro", ["cacao amaro polvere", "cacao amaro", "cacao"], 169593, "AS_SOLD", "A", {}),
    ("marmellata_senza_zuccheri", ["marmellata senza zuccheri aggiunti", "marmellata frutti bosco senza zuccheri aggiunti"], 170280, "AS_SOLD", "B", {}),
    ("marmellata", ["marmellata"], 169641, "AS_SOLD", "A", {}),
    ("proteine_polvere", ["proteine polvere"], 173180, "AS_SOLD", "B", {"fixed": True}),
    ("parmigiano", ["parmigiano reggiano"], 170848, "AS_SOLD", "A", {}),
    ("sale", ["sale fino", "sale"], 173468, "AS_SOLD", "A", {"fixed": True}),
    ("acqua", ["acqua"], 171922, "AS_SOLD", "A", {"fixed": True, "density": 1.0}),
    ("te_verde", ["te verde", "t verde"], 171917, "AS_SOLD", "A", {"fixed": True, "density": 1.0}),
    # Tier C: placeholder NON USDA (valori tipici di etichetta, da sostituire con CREA)
    ("bresaola", ["bresaola", "bresaola tacchino", "bresaola manzo", "bresaola the"], None, "AS_SOLD", "C",
     {"per100": {"kcal": 150, "p": 32.0, "c": 0.5, "f": 2.5}, "source": "valore tipico di etichetta, NON USDA, non verificato"}),
]


def main(root, out):
    foods = usda.load(root)
    entries = []
    for cid, aliases, fdc, state, tier, extra in CATALOG:
        e = {"id": cid, "aliases": aliases, "state": state, "tier": tier}
        if fdc is not None:
            f = foods[fdc]
            p = f["per100"]
            miss = [k for k in ("kcal", "p", "c", "f") if k not in p]
            if miss:
                raise SystemExit(f"{cid}: fdc {fdc} senza {miss}")
            e["fdcId"] = fdc
            e["usda"] = f["desc"]
            e["per100"] = {k: round(max(0.0, p.get(k, 0.0)), 2) for k in ("kcal", "p", "c", "f", "fib", "na", "water") if k in p}
            e["portions"] = [list(x) for x in f["portions"][:6]]
        else:
            e.update({"fdcId": None, "usda": None, "per100": extra["per100"], "source": extra["source"]})
        for k in ("density", "fixed"):
            if k in extra:
                e[k] = extra[k]
        if "cooked" in extra:
            c = foods[extra["cooked"]]
            e["cooked"] = {"fdcId": c["fdcId"], "usda": c["desc"], "per100": {k: c["per100"][k] for k in ("kcal", "p", "c", "f")}}
        entries.append(e)
    with open(out, "w", encoding="utf-8") as fh:
        json.dump({"source": "USDA FoodData Central (CC0): SR Legacy 2018-04 + Foundation 2025-12", "entries": entries},
                  fh, ensure_ascii=False, indent=1)
    print("voci", len(entries))


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2] if len(sys.argv) > 2 else os.path.join(os.path.dirname(__file__), "catalog_mini.json"))
