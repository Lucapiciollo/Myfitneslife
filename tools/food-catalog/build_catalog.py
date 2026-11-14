#!/usr/bin/env python3
"""Rigenera android/app/src/main/assets/food/catalog_v1.json.

Input:
  - i CSV USDA FoodData Central (SR Legacy 2018-04 + Foundation 2025-12-18), CC0 1.0, in una cartella
    TEMPORANEA fuori dal repo (mai versionata);
  - la tabella curata a mano tools/food-catalog/aliases.json.

Uso (Python 3, nessuna dipendenza esterna):
  python build_catalog.py --download            # scarica e decomprime i dump nella cartella temporanea
  python build_catalog.py                       # genera il catalogo
  python build_catalog.py --usda <cartella> --out <file>

Il kcal della fonte si conserva cosi' com'e' (Foundation: energia Atwater quando manca l'id 1008).
Non vengono copiati testi di descrizione USDA: solo numeri e identificatori.
"""
import argparse
import csv
import json
import os
import re
import sys
import tempfile
import unicodedata
import urllib.request
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_OUT = os.path.normpath(os.path.join(HERE, "..", "..", "android", "app", "src", "main", "assets", "food", "catalog_v1.json"))
DEFAULT_USDA = os.path.join(tempfile.gettempdir(), "myfitai-usda-fdc")

CATALOG_VERSION = 1
BUILD_DATE = "2026-11-14"

DATASETS = [
    ("foundation", "f2025", "foundation_food", "Foundation", "2025-12-18",
     "https://fdc.nal.usda.gov/fdc-datasets/FoodData_Central_foundation_food_csv_2025-12-18.zip"),
    ("sr", "srlegacy", "sr_legacy_food", "SR Legacy", "2018-04",
     "https://fdc.nal.usda.gov/fdc-datasets/FoodData_Central_sr_legacy_food_csv_2018-04.zip"),
]

NUTR = {1008: "kcal", 1003: "p", 1005: "c", 1004: "f", 1079: "fiber", 1093: "sodiumMg", 1051: "water"}
ATWATER = (2047, 2048)

# Identificatori dei concetti di FoodSynonyms (domain/food/FoodSynonyms.kt). NICKEL e SULPHITES non sono
# modellati: l'app non puo' verificarli.
TAG_VOCABULARY = {
    "LACTOSE", "MILK", "MILK_DRINK", "CHEESE", "BUTTER", "GLUTEN", "TREE_NUTS", "WALNUT", "ALMOND", "HAZELNUT",
    "PISTACHIO", "CASHEW", "PINE_NUT", "PEANUT", "SOY", "SESAME", "CELERY", "MUSTARD", "LUPIN", "FISH",
    "CRUSTACEANS", "MOLLUSCS", "EGG", "RICE", "MEAT", "GELATIN", "HONEY",
}

CATS = {
    # codice: (categoria, shopCat, ruoli, (min, max, step) crudo/tal quale, (min, max, step) cotto)
    "CER": ("CEREALI", "Pasta, riso e cereali", ["CARB"], (30, 130, 5), (80, 350, 10)),
    "PAN": ("PANE", "Pane e prodotti da forno", ["CARB"], (15, 150, 5), (15, 150, 5)),
    "TUB": ("TUBERI", "Frutta e verdura", ["CARB", "VEG"], (80, 400, 10), (80, 400, 10)),
    "LEG": ("LEGUMI", "Legumi", ["PROTEIN", "CARB"], (30, 100, 5), (80, 300, 10)),
    "CAR": ("CARNE", "Carne", ["PROTEIN"], (80, 300, 10), (80, 300, 10)),
    "SAL": ("SALUMI", "Salumi", ["PROTEIN"], (20, 100, 5), (20, 100, 5)),
    "PES": ("PESCE", "Pesce", ["PROTEIN"], (80, 300, 10), (80, 300, 10)),
    "UOV": ("UOVA", "Uova e latticini", ["PROTEIN"], (25, 250, 25), (25, 250, 25)),
    "LAT": ("LATTICINI", "Uova e latticini", ["DAIRY", "PROTEIN"], (50, 400, 10), (50, 400, 10)),
    "FOR": ("FORMAGGI", "Formaggi", ["DAIRY", "PROTEIN"], (10, 150, 5), (10, 150, 5)),
    "VER": ("VERDURA", "Frutta e verdura", ["VEG"], (50, 400, 10), (50, 400, 10)),
    "FRU": ("FRUTTA", "Frutta e verdura", ["FRUIT"], (50, 400, 10), (50, 400, 10)),
    "SEC": ("FRUTTA_SECCA", "Frutta secca e semi", ["FAT"], (5, 60, 5), (5, 60, 5)),
    "OLI": ("OLI_GRASSI", "Olio, spezie e condimenti", ["FAT"], (5, 30, 5), (5, 30, 5)),
    "CON": ("CONDIMENTI", "Olio, spezie e condimenti", ["SEASONING"], (1, 50, 1), (1, 50, 1)),
    "BEV": ("BEVANDE", "Bevande", ["DRINK"], (50, 500, 50), (50, 500, 50)),
    "DOL": ("DOLCI", "Dolci e colazione", ["SWEET"], (5, 100, 5), (5, 100, 5)),
    "INT": ("INTEGRATORI", "Integratori", ["SUPPLEMENT"], (10, 60, 5), (10, 60, 5)),
    "PIA": ("PIATTI", "Piatti pronti", ["MEAL"], (100, 400, 10), (100, 400, 10)),
}
STATES = {"R": "RAW", "C": "COOKED", "S": "AS_SOLD", "N": "NA"}

# Stessa normalizzazione di FoodCatalog.normalizeAlias (Kotlin): minuscolo, senza accenti/punteggiatura,
# senza articoli e preposizioni.
STOPWORDS = {"il", "lo", "la", "le", "i", "gli", "l", "un", "uno", "una", "di", "del", "dello", "della", "dei",
             "degli", "delle", "d", "a", "al", "allo", "alla", "ai", "agli", "alle", "da", "dal", "dalla",
             "con", "e", "ed", "in", "su", "per"}


def normalize(text):
    s = unicodedata.normalize("NFD", text.lower())
    s = "".join(ch for ch in s if unicodedata.category(ch) != "Mn")
    s = re.sub(r"[^a-z0-9]+", " ", s).strip()
    return " ".join(t for t in s.split(" ") if t and t not in STOPWORDS)


def download(root):
    os.makedirs(root, exist_ok=True)
    for _, folder, _, _, _, url in DATASETS:
        target = os.path.join(root, folder)
        if os.path.isdir(target):
            print("gia' presente:", target)
            continue
        zpath = os.path.join(root, os.path.basename(url))
        print("scarico", url)
        urllib.request.urlretrieve(url, zpath)
        with zipfile.ZipFile(zpath) as z:
            z.extractall(target)


def _rows(path):
    with open(path, encoding="utf-8", newline="") as fh:
        yield from csv.DictReader(fh)


def _find_dir(root, part):
    for d in sorted(os.listdir(root)):
        full = os.path.join(root, d)
        if part in d and os.path.isdir(full):
            inner = [x for x in os.listdir(full) if os.path.isdir(os.path.join(full, x))]
            return os.path.join(full, inner[0]) if inner else full
    raise FileNotFoundError("cartella USDA %s non trovata in %s (usa --download)" % (part, root))


def load_usda(root):
    foods = {}
    for _, folder, data_type, dataset, release, _ in DATASETS:
        d = _find_dir(root, folder)
        keep = {}
        for r in _rows(os.path.join(d, "food.csv")):
            if r["data_type"] == data_type:
                keep[r["fdc_id"]] = {"fdcId": int(r["fdc_id"]), "dataset": dataset, "release": release,
                                      "per100": {}, "portions": []}
        for r in _rows(os.path.join(d, "food_nutrient.csv")):
            f = keep.get(r["fdc_id"])
            if not f or not r["amount"]:
                continue
            nid = int(r["nutrient_id"])
            if nid in NUTR:
                f["per100"][NUTR[nid]] = float(r["amount"])
            elif nid in ATWATER and "_atwater" not in f["per100"]:
                f["per100"]["_atwater"] = float(r["amount"])
        for r in _rows(os.path.join(d, "food_portion.csv")):
            f = keep.get(r["fdc_id"])
            if f and r["gram_weight"]:
                desc = (r["portion_description"] or r["modifier"] or "").strip()
                f["portions"].append((desc, float(r["gram_weight"]), float(r["amount"] or 1)))
        for f in keep.values():
            p = f["per100"]
            if "kcal" not in p and "_atwater" in p:
                p["kcal"] = p["_atwater"]
            foods[f["fdcId"]] = f
    return foods


def parse_season(spec):
    if not spec:
        return []
    months = []
    for part in spec.split(","):
        a, _, b = part.partition("-")
        a = int(a)
        b = int(b) if b else a
        m = a
        while True:
            months.append(m)
            if m == b:
                break
            m = m % 12 + 1
    return sorted(set(months))


def num(x):
    x = round(float(x), 3)
    return int(x) if x == int(x) else x


def find_portion(food, pattern):
    for desc, gram, amount in food["portions"]:
        if pattern.startswith("~"):
            if desc.lower().startswith(pattern[1:].lower()):
                return round(gram / amount, 2)
        elif desc.lower() == pattern.lower():
            return round(gram / amount, 2)
    return None


def build_entry(e, foods, errors):
    cid = "it." + e["id"]
    catname, shop, roles, raw_lim, cooked_lim = CATS[e["c"]]
    state = STATES[e["st"]]
    conf = e.get("conf", "A")
    tags = e.get("tags", [])
    bad = [t for t in tags if t not in TAG_VOCABULARY]
    if bad:
        errors.append("%s: tag sconosciuti %s" % (cid, bad))
    lim = cooked_lim if state == "COOKED" else raw_lim
    out = {"id": cid, "name": e["name"], "aliases": sorted({normalize(a) for a in e["al"]}, key=lambda x: x),
           "cat": catname, "shopCat": shop, "state": state}
    if e.get("pair"):
        out["pairId"] = "it." + e["pair"]
    units = []
    if conf == "C":
        if e.get("fdc") is not None or "per100" not in e:
            errors.append("%s: confidence C richiede fdc null e per100 manuale" % cid)
        p = e["per100"]
        per = {k: num(p[k]) for k in ("kcal", "p", "c", "f")}
        source = {"ente": "stima non USDA", "fdcId": None, "dataset": None, "data": BUILD_DATE}
    else:
        f = foods.get(e["fdc"])
        if f is None:
            errors.append("%s: fdcId %s assente nei CSV" % (cid, e["fdc"]))
            return None
        p = f["per100"]
        miss = [k for k in ("kcal", "p", "c", "f") if k not in p]
        if miss:
            errors.append("%s: fdcId %s senza %s" % (cid, e["fdc"], miss))
            return None
        per = {k: num(max(0.0, p[k])) for k in ("kcal", "p", "c", "f")}
        for k in ("fiber", "sodiumMg", "water"):
            if k in p:
                per[k] = num(max(0.0, p[k]))
        source = {"ente": "USDA FDC", "fdcId": f["fdcId"], "dataset": f["dataset"], "data": f["release"]}
        # unita' pratiche: SOLO da food_portion USDA (esatte), mai inventate
        auto = [("cucchiaio", "tbsp"), ("cucchiaio", "tablespoon"), ("cucchiaino", "tsp"), ("cucchiaino", "teaspoon")]
        seen = set()
        for name, pat in auto + [tuple(u) for u in e.get("u", [])]:
            if name in seen:
                continue
            g = find_portion(f, pat)
            if g:
                units.append({"n": name, "g": g, "src": "USDA food_portion"})
                seen.add(name)
            elif (name, pat) in [tuple(u) for u in e.get("u", [])]:
                errors.append("%s: porzione USDA '%s' non trovata per fdc %s" % (cid, pat, f["fdcId"]))
    out["per100"] = per
    out["edible"] = e.get("edible", 1.0)
    if "den" in e:
        out["density"] = e["den"]
        if e["c"] in ("BEV", "LAT"):
            # convenzione dichiarata (non USDA): 1 bicchiere = 200 ml
            units.append({"n": "bicchiere", "g": round(200 * e["den"], 1), "ml": 200, "src": "convenzione 200 ml"})
    out["units"] = units
    out["tags"] = sorted(tags)
    out["role"] = e.get("role", roles)
    out["season"] = parse_season(e.get("s"))
    mn, mx, st = e.get("min", lim[0]), e.get("max", lim[1]), e.get("step", lim[2])
    out["minG"], out["maxG"], out["stepG"] = num(mn), num(mx), num(st)
    out["scalable"] = e.get("scal", True)
    out["confidence"] = conf
    out["source"] = source
    out["kind"] = e.get("kind", "FOOD")
    if e.get("note"):
        out["note"] = e["note"]
    return out


def finalize(entries, errors):
    ids = {e["id"]: e for e in entries}
    if len(ids) != len(entries):
        errors.append("id duplicati")
    seen = {}
    for e in entries:
        for a in set(e["aliases"]) | {normalize(e["name"])}:
            if not a:
                errors.append("%s: alias vuoto dopo normalizzazione" % e["id"])
            elif a in seen and seen[a] != e["id"]:
                errors.append("alias '%s' in collisione: %s / %s" % (a, seen[a], e["id"]))
            else:
                seen[a] = e["id"]
        pid = e.get("pairId")
        if pid:
            other = ids.get(pid)
            if other is None or other.get("pairId") != e["id"]:
                errors.append("%s: pairId %s non simmetrico" % (e["id"], pid))
            elif {e["state"], other["state"]} != {"RAW", "COOKED"}:
                errors.append("%s/%s: la coppia deve essere RAW+COOKED" % (e["id"], pid))
    return ids


def apply_yield(table, entries):
    """Resa cotto/crudo = kcal/100 g del crudo / kcal/100 g del cotto, solo per le voci con 'y': 1
    (cereali e legumi che assorbono acqua: il contenuto energetico resta nel piatto). Per carne, pesce e
    verdure il rapporto kcal NON e' una resa affidabile: resta assente."""
    by_id = {e["id"]: e for e in entries}
    flagged = {"it." + e["id"] for e in table if e.get("y")}
    for e in entries:
        if e["id"] in flagged and e["state"] == "RAW" and e.get("pairId"):
            cooked = by_id[e["pairId"]]
            y = round(e["per100"]["kcal"] / cooked["per100"]["kcal"], 2)
            e["yield"] = y
            cooked["yield"] = y


def sanity(entries, errors):
    for e in entries:
        p = e["per100"]
        total = p["p"] + p["c"] + p["f"] + p.get("water", 0)
        if total > 100.5:
            errors.append("%s: acqua+macro = %.1f g > 100,5" % (e["id"], total))


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--usda", default=os.environ.get("FDC_DIR", DEFAULT_USDA))
    ap.add_argument("--table", default=os.path.join(HERE, "aliases.json"))
    ap.add_argument("--out", default=DEFAULT_OUT)
    ap.add_argument("--download", action="store_true")
    args = ap.parse_args()
    if args.download:
        download(args.usda)
    with open(args.table, encoding="utf-8") as fh:
        table = json.load(fh)["entries"]
    foods = load_usda(args.usda)
    errors = []
    entries = [x for x in (build_entry(e, foods, errors) for e in table) if x]
    finalize(entries, errors)
    apply_yield(table, entries)
    sanity(entries, errors)
    if errors:
        print("\n".join(errors), file=sys.stderr)
        sys.exit(1)
    doc = {
        "catalogVersion": CATALOG_VERSION,
        "generated": BUILD_DATE,
        "license": "USDA FoodData Central, CC0 1.0 Universal (dominio pubblico). Voci con confidence C: stime non USDA.",
        "citation": "U.S. Department of Agriculture, Agricultural Research Service. FoodData Central, 2025. fdc.nal.usda.gov",
        "datasets": [{"name": d[3], "release": d[4], "url": d[5]} for d in DATASETS],
        "entries": entries,
    }
    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    head = json.dumps({k: v for k, v in doc.items() if k != "entries"}, ensure_ascii=False)[:-1]
    body = ",\n".join(json.dumps(e, ensure_ascii=False, separators=(",", ":")) for e in entries)
    with open(args.out, "w", encoding="utf-8", newline="\n") as fh:
        fh.write(head + ',"entries":[\n' + body + "\n]}\n")
    from collections import Counter
    print("voci:", len(entries), dict(Counter(e["confidence"] for e in entries)))
    print("categorie:", dict(Counter(e["cat"] for e in entries)))


if __name__ == "__main__":
    main()
