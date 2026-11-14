"""SPIKE S1: macro DICHIARATI dal modello vs macro CALCOLATI da un catalogo alimentare.

Uso:
  python spike_s1.py --backups <file1.json> <file2.json> [--catalog catalog_mini.json] --out <cartella_output>

I backup (schema myfitai_profile_export_v2) contengono DATI DI SALUTE: restano fuori dal repo.
Lo script scrive SOLO statistiche aggregate e nomi di alimenti (results.json) nella cartella di output,
che deve stare fuori dal repo.
"""
import argparse
import json
import math
import os
import re
import time
import unicodedata
from collections import Counter, defaultdict

STOP = {"di", "d", "del", "della", "dei", "degli", "delle", "il", "lo", "la", "l", "le", "i", "gli", "un", "uno",
        "una", "e", "in", "al", "alla", "alle", "con", "a", "da", "the", "o"}
CAT_FIXED = {"integratori", "integratore"}
RAW_STATES = {"crudo", "grezzo", "secco", "peso a crudo"}
COOKED_STATES = {"cotto", "bollito", "lessato"}


def norm(name):
    s = unicodedata.normalize("NFKD", name.lower())
    s = "".join(ch for ch in s if not unicodedata.combining(ch))
    s = re.sub(r"[^a-z0-9]+", " ", s)
    return [t for t in s.split() if t not in STOP]


# ---------------------------------------------------------------- catalogo
class Catalog:
    def __init__(self, path, tiers):
        data = json.load(open(path, encoding="utf-8"))
        self.entries = {e["id"]: e for e in data["entries"] if e["tier"] in tiers}
        self.alias = []  # (tokens, id)
        for e in self.entries.values():
            for a in e["aliases"]:
                self.alias.append((norm(a), e["id"]))
        self.alias.sort(key=lambda x: (-len(x[0]), -sum(map(len, x[0]))))

    def match(self, name):
        toks = norm(name)
        for at, eid in self.alias:
            n = len(at)
            for i in range(len(toks) - n + 1):
                if toks[i:i + n] == at:
                    return self.entries[eid]
        return None


# ---------------------------------------------------------------- dati
def meal_sig(m):
    ing = tuple(sorted((" ".join(norm(i["name"])), i["quantity"], i["unit"]) for i in m["ingredients"]))
    return (m["kcal"], m["proteinG"], m["carbsG"], m["fatG"], ing)


def load_profiles(paths):
    """Ritorna {profilo: {'meals': {sig: meal}, 'days': {sig: day}}} deduplicati (profili anonimi P1, P2...)."""
    out = {}
    for lab, p in zip(["P1", "P2", "P3"], paths):
        d = json.load(open(p, encoding="utf-8"))
        meals, days = {}, {}
        for plan in d["mealPlans"]:
            for v in plan["versions"]:
                for day in v["days"]:
                    for m in day["meals"]:
                        meals.setdefault(meal_sig(m), m)
                    dsig = (day["targetKcal"], day["targetProteinG"], tuple(meal_sig(m) for m in day["meals"]))
                    days.setdefault(dsig, day)
        raw_names = Counter(" ".join(norm(i["name"])) for pl in d["mealPlans"] for v in pl["versions"] for dd in v["days"] for m in dd["meals"] for i in m["ingredients"])
        out[lab] = {"meals": meals, "days": days, "raw_names": raw_names,
                    "raw_rows": sum(len(m["ingredients"]) for pl in d["mealPlans"] for v in pl["versions"] for dd in v["days"] for m in dd["meals"]),
                    "raw_days": sum(len(v["days"]) for pl in d["mealPlans"] for v in pl["versions"]),
                    "raw_meals": sum(len(dd["meals"]) for pl in d["mealPlans"] for v in pl["versions"] for dd in v["days"])}
    return out


# ---------------------------------------------------------------- utilita'
def pct(vals, q):
    if not vals:
        return None
    s = sorted(vals)
    k = (len(s) - 1) * q
    f, c = math.floor(k), math.ceil(k)
    return s[f] if f == c else s[f] + (s[c] - s[f]) * (k - f)


def dist(vals):
    if not vals:
        return {"n": 0}
    a = [abs(v) for v in vals]
    return {"n": len(vals), "median": round(pct(vals, .5), 4), "p10": round(pct(vals, .1), 4),
            "p90": round(pct(vals, .9), 4), "max": round(max(vals, key=abs), 4),
            "abs_median": round(pct(a, .5), 4), "abs_p90": round(pct(a, .9), 4),
            "pct_abs_le3": round(100 * sum(1 for x in a if x <= .03) / len(a), 1),
            "pct_abs_le10": round(100 * sum(1 for x in a if x <= .10) / len(a), 1)}


def grams(ing, entry):
    """Grammi dell'ingrediente (None se non convertibile)."""
    if ing["unit"] == "g":
        return float(ing["quantity"])
    if ing["unit"] == "ml" and entry is not None and entry.get("density"):
        return float(ing["quantity"]) * entry["density"]
    return None


def macros(entry, g):
    p = entry["per100"]
    return (p["kcal"] * g / 100, p["p"] * g / 100, p["c"] * g / 100, p["f"] * g / 100)


# ---------------------------------------------------------------- riparazione
W = (1000.0, 100.0, 1.0, 10.0)  # pesi relativi nell'ordine k, p, c, f (priorita' kcal > proteine > grassi > carboidrati)
W = (1000.0, 100.0, 1.0, 10.0)
LAMBDA = 0.01


def step_for(x):
    return 5.0 if x >= 20 else 1.0


def repair_day(vars_, T, max_sweeps=200, lo_f=0.6, hi_f=1.5, meal_f=0.4, tol=0.5):
    """Discesa a coordinate con proiezione sui limiti. vars_: [{m, a:(k,p,c,f per g), x0, fixed}]. T: (K,P,C,F)."""
    n = len(vars_)
    x = [v["x0"] for v in vars_]
    lo = [v["x0"] if v["fixed"] else lo_f * v["x0"] for v in vars_]
    hi = [v["x0"] if v["fixed"] else hi_f * v["x0"] for v in vars_]
    nm = max(v["m"] for v in vars_) + 1
    meal_k = [0.0] * nm
    for v, xi in zip(vars_, x):
        meal_k[v["m"]] += v["a"][0] * xi
    meal_lo = [(1 - meal_f) * m for m in meal_k]
    meal_hi = [(1 + meal_f) * m for m in meal_k]
    S = [sum(v["a"][k] * xi for v, xi in zip(vars_, x)) for k in range(4)]
    sweeps = 0
    for sweeps in range(1, max_sweeps + 1):
        maxd = 0.0
        for j in range(n):
            v = vars_[j]
            if v["fixed"]:
                continue
            a = v["a"]
            num = sum(W[k] * (S[k] - T[k]) * a[k] / (T[k] ** 2) for k in range(4)) + LAMBDA * (x[j] - v["x0"]) / (v["x0"] ** 2)
            den = sum(W[k] * a[k] ** 2 / (T[k] ** 2) for k in range(4)) + LAMBDA / (v["x0"] ** 2)
            nx = min(max(x[j] - num / den, lo[j]), hi[j])
            if a[0] > 0:  # vincolo +-40% sulle kcal del pasto
                m = v["m"]
                nx = min(max(nx, x[j] + (meal_lo[m] - meal_k[m]) / a[0]), x[j] + (meal_hi[m] - meal_k[m]) / a[0])
                nx = min(max(nx, lo[j]), hi[j])
            d = nx - x[j]
            if d:
                for k in range(4):
                    S[k] += a[k] * d
                meal_k[v["m"]] += a[0] * d
                x[j] = nx
                maxd = max(maxd, abs(d))
        if maxd < tol:
            break

    def J(S_):
        return sum(W[k] * ((S_[k] - T[k]) / T[k]) ** 2 for k in range(4))

    # arrotondamento a passi di 5 g (1 g per le voci sotto i 20 g) e rifinitura locale sui passi discreti
    for j in range(n):
        if vars_[j]["fixed"]:
            continue
        st = step_for(x[j])
        rx = min(max(round(x[j] / st) * st, lo[j]), hi[j])
        d = rx - x[j]
        for k in range(4):
            S[k] += vars_[j]["a"][k] * d
        meal_k[vars_[j]["m"]] += vars_[j]["a"][0] * d
        x[j] = rx
    for _ in range(20):
        improved = False
        for j in range(n):
            v = vars_[j]
            if v["fixed"]:
                continue
            st = step_for(x[j])
            for sgn in (1, -1):
                nx = x[j] + sgn * st
                if nx < lo[j] - 1e-9 or nx > hi[j] + 1e-9:
                    continue
                S2 = [S[k] + v["a"][k] * sgn * st for k in range(4)]
                m = v["m"]
                mk = meal_k[m] + v["a"][0] * sgn * st
                if mk < meal_lo[m] or mk > meal_hi[m]:
                    continue
                if J(S2) < J(S) - 1e-12:
                    S, x[j], meal_k[m] = S2, nx, mk
                    improved = True
        if not improved:
            break
    return x, S, sweeps


# ---------------------------------------------------------------- analisi
def analyse(profiles, cat):
    res = {}
    all_rows = Counter()
    unresolved = Counter()
    resolved_rows = rows_total = row_unit_conv = 0
    state_cnt = Counter()
    meal_dev = {k: [] for k in "kpcf"}
    meal_dev_abs = {k: [] for k in "kpcf"}
    day_dev = {k: [] for k in "kpcf"}
    prof_meal_dev = defaultdict(lambda: {k: [] for k in "kpcf"})
    prof_day_dev = defaultdict(lambda: {k: [] for k in "kpcf"})
    meals_total = meals_resolved = days_total = days_resolved = 0
    prof_cov = {}
    repair = []
    ing_by_entry = Counter()
    for lab, pdata in profiles.items():
        pm_total = pm_res = pd_total = pd_res = 0
        for m in pdata["meals"].values():
            pm_total += 1
            ok = True
            tot = [0.0] * 4
            for ing in m["ingredients"]:
                key = " ".join(norm(ing["name"]))
                rows_total += 1
                ws = (ing["weightState"] or "").lower()
                state_cnt[ws if ws in RAW_STATES | COOKED_STATES | {"fresco", "liquido", "surgelato"} else "(altro/sporco)"] += 1
                all_rows[key] += 1
                e = cat.match(ing["name"])
                if e is None:
                    ok = False
                    unresolved[key] += 1
                    continue
                resolved_rows += 1
                ing_by_entry[e["id"]] += 1
                g = grams(ing, e)
                if g is None:
                    ok = False
                    continue
                row_unit_conv += 1
                for k, v in enumerate(macros(e, g)):
                    tot[k] += v
            if ok:
                pm_res += 1
                decl = (m["kcal"], m["proteinG"], m["carbsG"], m["fatG"])
                for k, key in enumerate("kpcf"):
                    if tot[k] >= (20 if key == "k" else 3):
                        r = (decl[k] - tot[k]) / tot[k]
                        meal_dev[key].append(r)
                        prof_meal_dev[lab][key].append(r)
                    meal_dev_abs[key].append(decl[k] - tot[k])
        meals_total += pm_total
        meals_resolved += pm_res
        for day in pdata["days"].values():
            pd_total += 1
            vars_, okday = [], True
            tot, decl = [0.0] * 4, [0.0] * 4
            risk = risk_amb = 0.0
            for mi, m in enumerate(day["meals"]):
                for k, f in enumerate(("kcal", "proteinG", "carbsG", "fatG")):
                    decl[k] += m[f]
                for ing in m["ingredients"]:
                    e = cat.match(ing["name"])
                    g = grams(ing, e) if e else None
                    if e is None or g is None:
                        okday = False
                        continue
                    mac = macros(e, g)
                    for k in range(4):
                        tot[k] += mac[k]
                    fixed = bool(e.get("fixed")) or (ing.get("category", "") or "").lower() in CAT_FIXED or g < 2
                    vars_.append({"m": mi, "a": tuple(v / g for v in mac), "x0": g, "fixed": fixed})
                    if "cooked" in e and e["state"] == "RAW":
                        dk = g * (e["per100"]["kcal"] - e["cooked"]["per100"]["kcal"]) / 100
                        risk += dk
                        if (ing["weightState"] or "").lower() not in RAW_STATES:
                            risk_amb += dk
            if not okday or len(day["meals"]) < 3:
                continue
            pd_res += 1
            for k, key in enumerate("kpcf"):
                if tot[k] >= (100 if key == "k" else 10):
                    r = (decl[k] - tot[k]) / tot[k]
                    day_dev[key].append(r)
                    prof_day_dev[lab][key].append(r)
            T = (day["targetKcal"], day["targetProteinG"], day["targetCarbsG"], day["targetFatG"])
            if min(T) <= 0:
                continue
            pre = [(tot[k] - T[k]) / T[k] for k in range(4)]
            t0 = time.perf_counter()
            x, S, sweeps = repair_day(vars_, T)
            dt = (time.perf_counter() - t0) * 1000
            repair.append({"prof": lab, "pre": pre, "post": [(S[k] - T[k]) / T[k] for k in range(4)], "sweeps": sweeps,
                           "ms": dt, "target_k": T[0], "risk_kcal": risk, "risk_amb": risk_amb, "vars": vars_, "T": T, "decl_vs_t": [(decl[k] - T[k]) / T[k] for k in range(4)]})
        days_total += pd_total
        days_resolved += pd_res
        prof_cov[lab] = {"unique_meals": pm_total, "meals_resolved": pm_res, "unique_days": pd_total, "days_resolved": pd_res,
                         "raw_rows": pdata["raw_rows"], "raw_days": pdata["raw_days"], "raw_meals": pdata["raw_meals"]}

    res["rows"] = {"total": rows_total, "resolved": resolved_rows, "pct": round(100 * resolved_rows / rows_total, 1)}
    res["unit"] = {"rows": rows_total, "convertible": row_unit_conv,
                   "pct_of_rows": round(100 * row_unit_conv / rows_total, 1),
                   "pct_of_resolved_rows": round(100 * row_unit_conv / max(resolved_rows, 1), 1)}
    res["unresolved_top30"] = unresolved.most_common(30)
    res["unresolved_distinct"] = len(unresolved)
    res["states"] = dict(state_cnt.most_common())
    res["meals"] = {"total": meals_total, "resolved": meals_resolved, "pct": round(100 * meals_resolved / max(meals_total, 1), 1)}
    res["days"] = {"total": days_total, "resolved": days_resolved, "pct": round(100 * days_resolved / max(days_total, 1), 1)}
    res["profiles"] = prof_cov
    # coerenza interna dei macro dichiarati: kcal vs 4P+4C+9F (indipendente dal catalogo)
    inc = []
    for pdata in profiles.values():
        for m in pdata["meals"].values():
            at = 4 * m["proteinG"] + 4 * m["carbsG"] + 9 * m["fatG"]
            if at >= 20:
                inc.append((m["kcal"] - at) / at)
    res["declared_internal_atwater"] = dist(inc)
    raw_all = Counter()
    for pdata in profiles.values():
        raw_all.update(pdata["raw_names"])
    hit = sum(k for nme, k in raw_all.items() if cat.match(nme) is not None)
    res["rows_weighted_all_versions"] = {"total": sum(raw_all.values()), "resolved": hit,
                                         "pct": round(100 * hit / sum(raw_all.values()), 1)}
    res["hapax_names_pct_of_rows"] = round(100 * sum(1 for k in raw_all.values() if k == 1) / sum(raw_all.values()), 1)
    res["entry_usage"] = ing_by_entry.most_common(100)
    res["meal_dev"] = {k: dist(v) for k, v in meal_dev.items()}
    res["meal_dev_abs"] = {k: {"n": len(v), "abs_median": round(pct([abs(x) for x in v], .5), 2), "abs_p90": round(pct([abs(x) for x in v], .9), 2)} for k, v in meal_dev_abs.items() if v}
    res["day_dev"] = {k: dist(v) for k, v in day_dev.items()}
    res["profile_meal_dev"] = {p: {k: dist(v) for k, v in d.items()} for p, d in prof_meal_dev.items()}
    res["profile_day_dev"] = {p: {k: dist(v) for k, v in d.items()} for p, d in prof_day_dev.items()}
    res["repair"] = summarise_repair(repair)
    res["_all_rows"] = all_rows
    return res


def summarise_repair(rep):
    if not rep:
        return {"n": 0}
    n = len(rep)

    def share(sel, thr):
        return round(100 * sum(1 for r in rep if abs(sel(r)) <= thr) / n, 1)

    out = {"n": n}
    for name, i in (("kcal", 0), ("protein", 1), ("carbs", 2), ("fat", 3)):
        out[name] = {"pre_le1": share(lambda r: r["pre"][i], .01), "pre_le3": share(lambda r: r["pre"][i], .03),
                     "post_le1": share(lambda r: r["post"][i], .01), "post_le3": share(lambda r: r["post"][i], .03),
                     "pre_abs_median_pct": round(100 * pct([abs(r["pre"][i]) for r in rep], .5), 2),
                     "post_abs_median_pct": round(100 * pct([abs(r["post"][i]) for r in rep], .5), 2),
                     "post_abs_max_pct": round(100 * max(abs(r["post"][i]) for r in rep), 2)}
    out["kcal_and_protein"] = {
        "pre_le3": round(100 * sum(1 for r in rep if abs(r["pre"][0]) <= .03 and abs(r["pre"][1]) <= .03) / n, 1),
        "post_le1": round(100 * sum(1 for r in rep if abs(r["post"][0]) <= .01 and abs(r["post"][1]) <= .01) / n, 1),
        "post_le3": round(100 * sum(1 for r in rep if abs(r["post"][0]) <= .03 and abs(r["post"][1]) <= .03) / n, 1)}
    out["all4_post_le3"] = round(100 * sum(1 for r in rep if all(abs(v) <= .03 for v in r["post"])) / n, 1)
    out["sweeps"] = {"median": pct([r["sweeps"] for r in rep], .5), "p90": pct([r["sweeps"] for r in rep], .9), "max": max(r["sweeps"] for r in rep)}
    out["ms_per_day"] = {"median": round(pct([r["ms"] for r in rep], .5), 2), "p90": round(pct([r["ms"] for r in rep], .9), 2),
                         "max": round(max(r["ms"] for r in rep), 2)}
    out["declared_vs_target_abs_median_pct"] = {k: round(100 * pct([abs(r["decl_vs_t"][i]) for r in rep], .5), 2) for k, i in (("kcal", 0), ("protein", 1), ("carbs", 2), ("fat", 3))}
    for nm, key in (("raw_cooked_risk_all", "risk_kcal"), ("raw_cooked_risk_ambiguous_only", "risk_amb")):
        out[nm] = {"median_pct_of_target": round(100 * pct([abs(r[key]) / r["target_k"] for r in rep], .5), 2),
                   "p90_pct_of_target": round(100 * pct([abs(r[key]) / r["target_k"] for r in rep], .9), 2)}
    out["by_profile"] = {}
    for lab in sorted({r["prof"] for r in rep}):
        sub = [r for r in rep if r["prof"] == lab]
        out["by_profile"][lab] = {
            "n": len(sub),
            "kcal_post_le3": round(100 * sum(1 for r in sub if abs(r["post"][0]) <= .03) / len(sub), 1),
            "protein_post_le3": round(100 * sum(1 for r in sub if abs(r["post"][1]) <= .03) / len(sub), 1),
            "kcal_protein_post_le3": round(100 * sum(1 for r in sub if abs(r["post"][0]) <= .03 and abs(r["post"][1]) <= .03) / len(sub), 1),
            "protein_pre_abs_median_pct": round(100 * pct([abs(r["pre"][1]) for r in sub], .5), 2)}
    sens = {}
    for name, kw in (("base_0.6-1.5_meal40", {}), ("wide_0.5-2.0_meal60", {"lo_f": .5, "hi_f": 2.0, "meal_f": .6}),
                     ("narrow_0.8-1.25_meal25", {"lo_f": .8, "hi_f": 1.25, "meal_f": .25})):
        ok = okk = 0
        for r in rep:
            x, S_, _ = repair_day(r["vars"], r["T"], 50, **kw)
            e = [(S_[k] - r["T"][k]) / r["T"][k] for k in range(4)]
            okk += abs(e[0]) <= .03
            ok += abs(e[0]) <= .03 and abs(e[1]) <= .03
        sens[name] = {"kcal_le3": round(100 * okk / n, 1), "kcal_protein_le3": round(100 * ok / n, 1)}
    out["bounds_sensitivity"] = sens
    return out


def coverage_vs_size(cat_all, rows_all):
    """Quante voci (concetti alimentari) servono per coprire X% delle righe, con alias del catalogo completo;
    i nomi non risolti restano concetti a se' (stima per eccesso)."""
    cf = Counter()
    for name, k in rows_all.items():
        e = cat_all.match(name)
        cf[("E:" + e["id"]) if e else ("U:" + name)] += k
    total = sum(cf.values())
    ordered = cf.most_common()
    cum, need = 0, {}
    for i, (_, k) in enumerate(ordered, 1):
        cum += k
        for thr in (0.80, 0.90, 0.95, 0.98):
            if thr not in need and cum / total >= thr:
                need[thr] = i
    return {"distinct_concepts": len(ordered), "needed_voices_for": {str(k): v for k, v in need.items()},
            "top50_cover_pct": round(100 * sum(k for _, k in ordered[:50]) / total, 1),
            "top100_cover_pct": round(100 * sum(k for _, k in ordered[:100]) / total, 1),
            "top200_cover_pct": round(100 * sum(k for _, k in ordered[:200]) / total, 1)}


def dose_check(profiles, cat):
    pat, cmp = Counter(), []
    for pdata in profiles.values():
        for m in pdata["meals"].values():
            for ing in m["ingredients"]:
                dsp = ing["displayDose"].strip()
                pat[re.sub(r"\d+([.,]\d+)?", "N", dsp)] += 1
                mt = re.match(r"^(\d+(?:[.,]\d+)?)\s*(cucchiaio|cucchiaino)(\s+e\s+mezzo)?$", dsp, re.I)
                if not mt:
                    continue
                n = float(mt.group(1).replace(",", ".")) + (0.5 if mt.group(3) else 0)
                e = cat.match(ing["name"])
                if not e:
                    continue
                key = "tbsp" if mt.group(2).lower() == "cucchiaio" else "tsp"
                gpu = None
                for desc, gw, amt, unit in e.get("portions", []):
                    txt = (desc + " " + unit).lower()
                    if (key == "tbsp" and ("tbsp" in txt or "tablespoon" in txt)) or (key == "tsp" and ("tsp" in txt or "teaspoon" in txt)):
                        gpu = gw / (amt or 1)
                        break
                cmp.append((e["id"], key, ing["quantity"] / n, gpu))
    return pat, cmp


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--backups", nargs="+", required=True)
    ap.add_argument("--catalog", default=os.path.join(os.path.dirname(os.path.abspath(__file__)), "catalog_mini.json"))
    ap.add_argument("--out", required=True)
    a = ap.parse_args()
    profiles = load_profiles(a.backups)
    os.makedirs(a.out, exist_ok=True)
    out = {}
    for label, tiers in (("A", {"A"}), ("AB", {"A", "B"}), ("ABC", {"A", "B", "C"})):
        cat = Catalog(a.catalog, tiers)
        r = analyse(profiles, cat)
        rows_all = r.pop("_all_rows")
        r["catalog_size"] = len(cat.entries)
        if label == "ABC":
            r["coverage_vs_size"] = coverage_vs_size(cat, rows_all)
            pat, cmp = dose_check(profiles, cat)
            r["display_dose_patterns"] = pat.most_common(12)
            by = defaultdict(list)
            for eid, key, gm, gpu in cmp:
                by[(eid, key)].append((gm, gpu))
            r["spoon_vs_usda"] = [{"entry": k[0], "unit": k[1], "n": len(v), "model_g_per_unit_median": round(pct([x[0] for x in v], .5), 1),
                                   "usda_g_per_unit": v[0][1]} for k, v in by.items()]
        out[label] = r
    json.dump(out, open(os.path.join(a.out, "results.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    print(json.dumps({k: {kk: out[k][kk] for kk in ("catalog_size", "rows", "meals", "days")} for k in out}, ensure_ascii=False, indent=1))


if __name__ == "__main__":
    main()
