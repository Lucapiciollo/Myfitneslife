"""Loader USDA FoodData Central (Foundation + SR Legacy) per lo spike S1.
Legge i CSV da una cartella temporanea FUORI dal repo. Nessun dump USDA e' versionato.
"""
import csv, os, sys
from collections import defaultdict

NUTR = {1008: "kcal", 1003: "p", 1005: "c", 1004: "f", 1079: "fib", 1093: "na", 1051: "water"}
ATWATER = (2047, 2048)  # Foundation: energia spesso solo come Atwater

def _csv(path):
    with open(path, encoding="utf-8", newline="") as fh:
        yield from csv.DictReader(fh)

def find_dir(root, name_part):
    for d in sorted(os.listdir(root)):
        full = os.path.join(root, d)
        if name_part in d and os.path.isdir(full):
            inner = [x for x in os.listdir(full) if os.path.isdir(os.path.join(full, x))]
            return os.path.join(full, inner[0]) if inner else full
    raise FileNotFoundError(name_part)

def load(root):
    """Ritorna dict fdcId -> {desc, type, per100:{...}, portions:[(desc,gram,amount,unit)]}"""
    foods = {}
    for tag, part, types in (("foundation", "f2025", ("foundation_food",)),
                             ("sr", "srlegacy", ("sr_legacy_food",))):
        d = find_dir(root, part)
        keep = {}
        for r in _csv(os.path.join(d, "food.csv")):
            if r["data_type"] in types:
                keep[r["fdc_id"]] = {"fdcId": int(r["fdc_id"]), "desc": r["description"], "src": tag,
                                      "per100": {}, "portions": []}
        for r in _csv(os.path.join(d, "food_nutrient.csv")):
            f = keep.get(r["fdc_id"])
            if not f or not r["amount"]:
                continue
            nid = int(r["nutrient_id"])
            if nid in NUTR:
                f["per100"][NUTR[nid]] = float(r["amount"])
            elif nid in ATWATER and "kcal_atw" not in f["per100"]:
                f["per100"]["kcal_atw"] = float(r["amount"])
        units = {r["id"]: r["name"] for r in _csv(os.path.join(d, "measure_unit.csv"))}
        for r in _csv(os.path.join(d, "food_portion.csv")):
            f = keep.get(r["fdc_id"])
            if f and r["gram_weight"]:
                f["portions"].append((r["portion_description"] or r["modifier"], float(r["gram_weight"]),
                                      float(r["amount"] or 1), units.get(r["measure_unit_id"], "")))
        for k, f in keep.items():
            p = f["per100"]
            if "kcal" not in p and "kcal_atw" in p:
                p["kcal"] = p["kcal_atw"]
            foods[f["fdcId"]] = f
    return foods

if __name__ == "__main__":
    # ricerca: python usda.py <root> parola [parola...]
    foods = load(sys.argv[1])
    words = [w.lower() for w in sys.argv[2:]]
    for f in foods.values():
        if all(w in f["desc"].lower() for w in words):
            p = f["per100"]
            print(f["fdcId"], f["src"][:2], f["desc"][:90], "|", p.get("kcal"), p.get("p"), p.get("c"), p.get("f"))
