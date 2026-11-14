package com.myfitai.app.domain.food.nutrition

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Riparazione per giorno: riscala i grammi delle voci scalabili per portare i totali CALCOLATI dal catalogo
 * vicino ai target del giorno, senza aggiungere né togliere voci. Kotlin puro, deterministico.
 *
 * Problema (variabili x_j = grammi delle voci scalabili dei pasti non consumati):
 *
 *   min  Σ_k w_k · ((S_k(x) − T_k) / T_k)²  +  λ · Σ_j ((x_j − x0_j) / x0_j)²
 *
 * con k ∈ {kcal, proteine, grassi, carboidrati}, pesi 4 / 2 / 1 / 1; per le proteine il peso raddoppia quando
 * il totale è SOTTO il target (penalità asimmetrica). λ = 0,01 è la vicinanza alla proposta del modello x0.
 * Vincoli: x_j ∈ [lowFactor·x0_j, highFactor·x0_j] ∩ [minG, maxG]; kcal di ogni pasto entro ±mealKcalFraction
 * delle kcal originali; voci non scalabili/locked e pasti consumati fissi.
 *
 * Priorità (decisione di prodotto): kcal bloccanti, proteine obiettivo non bloccante, carboidrati/grassi
 * informativi. Per garantirla i pesi nominali sono rafforzati a riprese a caldo (continuazione): se lo scarto
 * kcal continuo supera lo 0,5% il peso kcal cresce x25 (fino a 3 volte); se le kcal sono a posto ma lo scarto
 * proteico supera lo 0,5% il peso di carboidrati e grassi scende /10 (fino a 2 volte). Poi arrotondamento ai
 * passi pratici (stepG) e rifinitura greedy di un passo; se i passi grossolani non bastano per stare entro
 * l'1% (o nella tolleranza, ai bordi dell'intervallo raggiungibile) si ripete con passo di 1 g e, in ultima
 * istanza, con grammi continui. Se la finestra kcal non interseca l'intervallo raggiungibile lo stato è
 * INFEASIBLE e il risultato è il miglior tentativo entro i limiti.
 *
 * Risolutore: discesa a coordinate con proiezione sui limiti (problema convesso, la soluzione è unica a meno
 * del passo di arresto). Nessun elemento casuale: stessi input => stessi output.
 */
object DayRepair {
    private const val W_KCAL = 4.0
    private const val W_PROTEIN = 2.0
    private const val W_FAT = 1.0
    private const val W_CARBS = 1.0
    private const val PROTEIN_UNDER_FACTOR = 2.0
    private const val PROXIMITY = 0.01
    private const val KCAL_LEVELS = 3
    private const val KCAL_STEP = 25.0
    private const val PROTEIN_LEVELS = 2
    private const val PROTEIN_STEP = 10.0
    private const val CONTINUOUS_GOAL = 0.005
    private const val PRIORITY_BAND = 0.01
    private const val EPS = 1e-9
    private const val GREEDY_PASSES = 30

    fun repair(
        meals: List<RepairMeal>,
        targets: DayTargets,
        limits: RepairLimits = RepairLimits.DEFAULT,
        kcalTolerance: Double = ValidationPolicy.FIRST_ATTEMPT_TOLERANCE,
    ): RepairResult {
        require(targets.kcal > 0.0) { "targets.kcal deve essere > 0" }
        require(kcalTolerance >= 0.0) { "kcalTolerance deve essere >= 0" }
        val p = Problem(meals, targets, limits)
        val reachable = KcalRange(p.reachableMin, p.reachableMax)

        val x = p.startPoint()
        val sums = p.sums(x)
        val mealKcal = p.mealKcal(x)
        var sweeps = 0
        var kLevel = 0
        var pLevel = 0
        var w = weights(kLevel, pLevel)
        while (true) {
            sweeps += p.descend(x, sums, mealKcal, w)
            val kOk = abs(sums[0] - p.t[0]) / p.t[0] <= CONTINUOUS_GOAL
            val pOk = p.t[1] <= 0.0 || abs(sums[1] - p.t[1]) / p.t[1] <= CONTINUOUS_GOAL
            if (!kOk && kLevel < KCAL_LEVELS) {
                kLevel++
            } else if (kOk && !pOk && pLevel < PROTEIN_LEVELS) {
                pLevel++
            } else {
                break
            }
            w = weights(kLevel, pLevel)
        }

        // Fascia di accettazione dell'arrotondamento: 1% se raggiungibile, altrimenti la tolleranza richiesta.
        val band = when {
            p.windowIntersects(PRIORITY_BAND) -> PRIORITY_BAND
            p.windowIntersects(kcalTolerance) -> kcalTolerance
            else -> -1.0
        }
        var chosen: DoubleArray? = null
        var chosenDev = Double.MAX_VALUE
        for (mode in 0..2) {
            if (mode > 0 && band < 0.0) break
            val cand = x.copyOf()
            val s = sums.copyOf()
            val mk = mealKcal.copyOf()
            if (mode < 2) p.roundAndRefine(cand, s, mk, w, fine = mode == 1)
            val dev = abs(s[0] - p.t[0]) / p.t[0]
            if (chosen == null || dev < chosenDev - EPS) {
                chosen = cand
                chosenDev = dev
            }
            if (dev <= band + EPS) break
        }

        val finalX = chosen!!
        val grams = p.toMealGrams(finalX)
        val totals = (meals.indices).fold(NutrientTotals.ZERO) { acc, i ->
            acc + NutritionCalculator.mealWithGrams(meals[i].ingredients, grams[i])
        }
        val kcalDev = (totals.kcal - targets.kcal) / targets.kcal
        val proteinDev = if (targets.proteinG > 0.0) (totals.proteinG - targets.proteinG) / targets.proteinG else 0.0
        val status = when {
            !p.windowIntersects(kcalTolerance) -> RepairStatus.INFEASIBLE
            abs(kcalDev) <= kcalTolerance + EPS -> RepairStatus.REPAIRED
            else -> RepairStatus.NOT_CONVERGED
        }
        return RepairResult(
            status = status,
            mealGrams = grams,
            totals = totals,
            kcalBand = band(kcalDev),
            proteinBand = band(proteinDev),
            kcalDeviation = kcalDev,
            proteinDeviation = proteinDev,
            iterations = sweeps,
            reachableKcal = reachable,
        )
    }

    /** Pesi [kcal, proteine, carboidrati, grassi]: kcal x25 per livello; carboidrati e grassi /10 per livello proteico. */
    private fun weights(kLevel: Int, pLevel: Int): DoubleArray {
        var k = W_KCAL
        repeat(kLevel) { k *= KCAL_STEP }
        var cf = 1.0
        repeat(pLevel) { cf /= PROTEIN_STEP }
        return doubleArrayOf(k, W_PROTEIN, W_CARBS * cf, W_FAT * cf)
    }

    private fun band(dev: Double): DeviationBand = when {
        abs(dev) <= 0.01 + EPS -> DeviationBand.WITHIN_1_PERCENT
        abs(dev) <= 0.03 + EPS -> DeviationBand.WITHIN_3_PERCENT
        else -> DeviationBand.OUTSIDE
    }

    /** Stato del problema: un vettore piatto di variabili (tutte le voci di tutti i pasti, in ordine). */
    private class Problem(val meals: List<RepairMeal>, targets: DayTargets, limits: RepairLimits) {
        val n = meals.sumOf { it.ingredients.size }
        val nm = meals.size
        val t = doubleArrayOf(targets.kcal, targets.proteinG, targets.carbsG, targets.fatG)

        /** Valori per grammo: a[0]=kcal, a[1]=proteine, a[2]=carboidrati, a[3]=grassi. */
        val a = Array(4) { DoubleArray(n) }
        val mealOf = IntArray(n)
        val x0 = DoubleArray(n)
        val lo = DoubleArray(n)
        val hi = DoubleArray(n)
        val step = DoubleArray(n)
        val adjustable = BooleanArray(n)
        val eLo = DoubleArray(nm)
        val eHi = DoubleArray(nm)
        val reachableMin: Double
        val reachableMax: Double

        init {
            var j = 0
            val k0 = DoubleArray(nm)
            val bLo = DoubleArray(nm)
            val bHi = DoubleArray(nm)
            for ((mi, meal) in meals.withIndex()) {
                for (ing in meal.ingredients) {
                    val p = ing.per100
                    a[0][j] = p.kcal / 100.0
                    a[1][j] = p.proteinG / 100.0
                    a[2][j] = p.carbsG / 100.0
                    a[3][j] = p.fatG / 100.0
                    mealOf[j] = mi
                    x0[j] = ing.grams
                    step[j] = if (ing.stepG > 0.0) ing.stepG else 1.0
                    val adj = ing.isAdjustable && !meal.consumed
                    adjustable[j] = adj
                    if (adj) {
                        val floorG = ing.minG ?: 0.0
                        val ceilG = ing.maxG ?: Double.MAX_VALUE
                        var l = max(limits.lowFactor * ing.grams, floorG)
                        var h = min(limits.highFactor * ing.grams, ceilG)
                        if (l > h) { // proposta fuori dai limiti di catalogo: la voce si porta al limite più vicino e resta fissa
                            val v = min(max(ing.grams, floorG), ceilG)
                            l = v
                            h = v
                            adjustable[j] = false
                        }
                        lo[j] = l
                        hi[j] = h
                    } else {
                        lo[j] = ing.grams
                        hi[j] = ing.grams
                    }
                    k0[mi] += a[0][j] * ing.grams
                    bLo[mi] += a[0][j] * lo[j]
                    bHi[mi] += a[0][j] * hi[j]
                    j++
                }
            }
            var rMin = 0.0
            var rMax = 0.0
            for (mi in 0 until nm) {
                val l = max((1.0 - limits.mealKcalFraction) * k0[mi], bLo[mi])
                val h = min((1.0 + limits.mealKcalFraction) * k0[mi], bHi[mi])
                if (l <= h) {
                    eLo[mi] = l
                    eHi[mi] = h
                } else { // limiti per voce e per pasto in conflitto: prevalgono i limiti per voce
                    eLo[mi] = bLo[mi]
                    eHi[mi] = bHi[mi]
                }
                rMin += eLo[mi]
                rMax += eHi[mi]
            }
            reachableMin = rMin
            reachableMax = rMax
        }

        val maxSweeps = limits.maxSweeps
        val convergenceG = limits.convergenceG

        fun windowIntersects(tol: Double): Boolean =
            reachableMax >= t[0] * (1.0 - tol) - EPS && reachableMin <= t[0] * (1.0 + tol) + EPS

        fun startPoint(): DoubleArray = DoubleArray(n) { min(max(x0[it], lo[it]), hi[it]) }

        fun sums(x: DoubleArray): DoubleArray {
            val s = DoubleArray(4)
            for (j in 0 until n) for (k in 0 until 4) s[k] += a[k][j] * x[j]
            return s
        }

        fun mealKcal(x: DoubleArray): DoubleArray {
            val m = DoubleArray(nm)
            for (j in 0 until n) m[mealOf[j]] += a[0][j] * x[j]
            return m
        }

        fun toMealGrams(x: DoubleArray): List<List<Double>> {
            var j = 0
            return meals.map { meal ->
                meal.ingredients.map { _ ->
                    val v = if (adjustable[j]) Math.rint(x[j] * 1e9) / 1e9 else x[j]
                    j++
                    v
                }
            }
        }

        private fun apply(x: DoubleArray, s: DoubleArray, mk: DoubleArray, j: Int, nx: Double) {
            val d = nx - x[j]
            for (k in 0 until 4) s[k] += a[k][j] * d
            mk[mealOf[j]] += a[0][j] * d
            x[j] = nx
        }

        /** Discesa a coordinate con proiezione. Ritorna le passate eseguite. */
        fun descend(x: DoubleArray, s: DoubleArray, mk: DoubleArray, w: DoubleArray): Int {
            val cK = w[0] / (t[0] * t[0])
            val cP = if (t[1] > 0.0) w[1] / (t[1] * t[1]) else 0.0
            val cC = if (t[2] > 0.0) w[2] / (t[2] * t[2]) else 0.0
            val cF = if (t[3] > 0.0) w[3] / (t[3] * t[3]) else 0.0
            var sweeps = 0
            while (sweeps < maxSweeps) {
                sweeps++
                var maxD = 0.0
                for (j in 0 until n) {
                    if (!adjustable[j]) continue
                    val a0 = a[0][j]
                    val ap = a[1][j]
                    val ac = a[2][j]
                    val af = a[3][j]
                    val prox = PROXIMITY / (x0[j] * x0[j])
                    val num = cK * a0 * (s[0] - t[0]) + cC * ac * (s[2] - t[2]) + cF * af * (s[3] - t[3]) + prox * (x[j] - x0[j])
                    val den = cK * a0 * a0 + cC * ac * ac + cF * af * af + prox
                    val d: Double = if (cP == 0.0 || ap == 0.0) {
                        -(num + cP * ap * (s[1] - t[1])) / (den + cP * ap * ap)
                    } else {
                        val cBelow = cP * PROTEIN_UNDER_FACTOR
                        val dBelow = -(num + cBelow * ap * (s[1] - t[1])) / (den + cBelow * ap * ap)
                        if (s[1] + ap * dBelow <= t[1]) {
                            dBelow
                        } else {
                            val dAbove = -(num + cP * ap * (s[1] - t[1])) / (den + cP * ap * ap)
                            if (s[1] + ap * dAbove >= t[1]) dAbove else (t[1] - s[1]) / ap
                        }
                    }
                    var nx = min(max(x[j] + d, lo[j]), hi[j])
                    if (a0 > 0.0) {
                        val m = mealOf[j]
                        nx = min(max(nx, x[j] + (eLo[m] - mk[m]) / a0), x[j] + (eHi[m] - mk[m]) / a0)
                        nx = min(max(nx, lo[j]), hi[j])
                    }
                    val moved = nx - x[j]
                    if (moved != 0.0) {
                        apply(x, s, mk, j, nx)
                        maxD = max(maxD, abs(moved))
                    }
                }
                if (maxD < convergenceG) break
            }
            return sweeps
        }

        private fun cost(s: DoubleArray, w: DoubleArray): Double {
            var c = w[0] * sq((s[0] - t[0]) / t[0])
            if (t[1] > 0.0) c += w[1] * (if (s[1] < t[1]) PROTEIN_UNDER_FACTOR else 1.0) * sq((s[1] - t[1]) / t[1])
            if (t[2] > 0.0) c += w[2] * sq((s[2] - t[2]) / t[2])
            if (t[3] > 0.0) c += w[3] * sq((s[3] - t[3]) / t[3])
            return c
        }

        private fun proxCost(j: Int, v: Double): Double = PROXIMITY * sq((v - x0[j]) / x0[j])

        private fun mealFeasible(mk: DoubleArray, j: Int, nx: Double, x: Double): Boolean {
            val m = mealOf[j]
            val k = mk[m] + a[0][j] * (nx - x)
            return k >= eLo[m] - EPS && k <= eHi[m] + EPS
        }

        /** Arrotonda ai passi pratici e rifinisce con mosse greedy di un passo. */
        fun roundAndRefine(x: DoubleArray, s: DoubleArray, mk: DoubleArray, w: DoubleArray, fine: Boolean) {
            fun stepOf(j: Int) = if (fine) min(step[j], 1.0) else step[j]
            for (j in 0 until n) {
                if (!adjustable[j]) continue
                val st = stepOf(j)
                val q = x[j] / st
                var best = x[j]
                var bestCost = Double.MAX_VALUE
                var found = false
                for (c in doubleArrayOf(Math.rint(q) * st, floor(q) * st, ceil(q) * st)) {
                    if (c < lo[j] - EPS || c > hi[j] + EPS || !mealFeasible(mk, j, c, x[j])) continue
                    val s2 = s.copyOf()
                    for (k in 0 until 4) s2[k] += a[k][j] * (c - x[j])
                    val cc = cost(s2, w) + proxCost(j, c)
                    if (!found || cc < bestCost - 1e-15) {
                        best = c
                        bestCost = cc
                        found = true
                    }
                }
                if (found) apply(x, s, mk, j, min(max(best, lo[j]), hi[j]))
            }
            val s2 = DoubleArray(4)
            repeat(GREEDY_PASSES) {
                var improved = false
                for (j in 0 until n) {
                    if (!adjustable[j]) continue
                    val st = stepOf(j)
                    for (sgn in intArrayOf(1, -1)) {
                        val nx = x[j] + sgn * st
                        if (nx < lo[j] - EPS || nx > hi[j] + EPS || !mealFeasible(mk, j, nx, x[j])) continue
                        for (k in 0 until 4) s2[k] = s[k] + a[k][j] * sgn * st
                        val gain = (cost(s, w) + proxCost(j, x[j])) - (cost(s2, w) + proxCost(j, nx))
                        if (gain > 1e-12) {
                            apply(x, s, mk, j, nx)
                            improved = true
                        }
                    }
                }
                if (!improved) return
            }
        }

        private fun sq(v: Double) = v * v
    }
}
