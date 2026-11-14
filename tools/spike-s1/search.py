"""Ricerca multipla nel catalogo USDA (cache JSON nella cartella temporanea)."""
import json, os, sys
import usda
root = sys.argv[1]
cache = os.path.join(root, "foods_cache.json")
if os.path.exists(cache):
    foods = {int(k): v for k, v in json.load(open(cache, encoding="utf-8")).items()}
else:
    foods = usda.load(root)
    json.dump(foods, open(cache, "w", encoding="utf-8"))
for q in sys.argv[2:]:
    words = [w.strip().lower() for w in q.split("+")]
    print("##", q)
    n = 0
    for f in foods.values():
        d = f["desc"].lower()
        p = f["per100"]
        if all(w in d for w in words) and all(k in p for k in ("kcal", "p", "c", "f")):
            print(" ", f["fdcId"], f["src"][:2], f["desc"][:80], "|", p["kcal"], p["p"], p["c"], p["f"], "| portions", len(f["portions"]))
            n += 1
            if n >= 4: break
