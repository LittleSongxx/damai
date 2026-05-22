package org.javaup.ai.assistant.eval;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Lightweight A/B comparison result for contrasting two RAG configurations.
 */
public class AbTestResult {

    private final String variantA;
    private final String variantB;
    private int totalCases;
    private int aWins;
    private int bWins;
    private int ties;

    private double aAvgRecall;
    private double bAvgRecall;
    private double aAvgPrecision;
    private double bAvgPrecision;
    private double aAvgHitRate;
    private double bAvgHitRate;
    private double aAvgMrr;
    private double bAvgMrr;
    private double aAvgNdcg;
    private double bAvgNdcg;
    private double aAvgLatencyMs;
    private double bAvgLatencyMs;

    public AbTestResult(String variantA, String variantB) {
        this.variantA = variantA;
        this.variantB = variantB;
    }

    public void recordComparison(double aRecall, double bRecall, double aPrecision, double bPrecision,
                                  double aHitRate, double bHitRate, double aMrr, double bMrr,
                                  double aNdcg, double bNdcg, double aLatency, double bLatency) {
        totalCases++;
        aAvgRecall += aRecall;
        bAvgRecall += bRecall;
        aAvgPrecision += aPrecision;
        bAvgPrecision += bPrecision;
        aAvgHitRate += aHitRate;
        bAvgHitRate += bHitRate;
        aAvgMrr += aMrr;
        bAvgMrr += bMrr;
        aAvgNdcg += aNdcg;
        bAvgNdcg += bNdcg;
        aAvgLatencyMs += aLatency;
        bAvgLatencyMs += bLatency;

        if (aRecall > bRecall) aWins++;
        else if (bRecall > aRecall) bWins++;
        else ties++;
    }

    public AbTestResult finalizeComparison() {
        if (totalCases > 0) {
            aAvgRecall /= totalCases;
            bAvgRecall /= totalCases;
            aAvgPrecision /= totalCases;
            bAvgPrecision /= totalCases;
            aAvgHitRate /= totalCases;
            bAvgHitRate /= totalCases;
            aAvgMrr /= totalCases;
            bAvgMrr /= totalCases;
            aAvgNdcg /= totalCases;
            bAvgNdcg /= totalCases;
            aAvgLatencyMs /= totalCases;
            bAvgLatencyMs /= totalCases;
        }
        return this;
    }

    public String winner() {
        if (aWins > bWins) return variantA;
        if (bWins > aWins) return variantB;
        return "TIE";
    }

    public Map<String, Object> toSummary() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("variantA", variantA);
        map.put("variantB", variantB);
        map.put("totalCases", totalCases);
        map.put("aWins", aWins);
        map.put("bWins", bWins);
        map.put("ties", ties);
        map.put("winner", winner());

        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("recall", Map.of("A", round(aAvgRecall), "B", round(bAvgRecall), "delta", round(bAvgRecall - aAvgRecall)));
        metrics.put("precision", Map.of("A", round(aAvgPrecision), "B", round(bAvgPrecision), "delta", round(bAvgPrecision - aAvgPrecision)));
        metrics.put("hitRate", Map.of("A", round(aAvgHitRate), "B", round(bAvgHitRate), "delta", round(bAvgHitRate - aAvgHitRate)));
        metrics.put("mrr", Map.of("A", round(aAvgMrr), "B", round(bAvgMrr), "delta", round(bAvgMrr - aAvgMrr)));
        metrics.put("ndcg", Map.of("A", round(aAvgNdcg), "B", round(bAvgNdcg), "delta", round(bAvgNdcg - aAvgNdcg)));
        metrics.put("latencyMs", Map.of("A", Math.round(aAvgLatencyMs), "B", Math.round(bAvgLatencyMs), "delta", Math.round(bAvgLatencyMs - aAvgLatencyMs)));
        map.put("metrics", metrics);
        return map;
    }

    private double round(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    // Getters
    public String getVariantA() { return variantA; }
    public String getVariantB() { return variantB; }
    public int getTotalCases() { return totalCases; }
    public int getAWins() { return aWins; }
    public int getBWins() { return bWins; }
    public int getTies() { return ties; }
    public double getAAvgRecall() { return aAvgRecall; }
    public double getBAvgRecall() { return bAvgRecall; }
    public double getAAvgPrecision() { return aAvgPrecision; }
    public double getBAvgPrecision() { return bAvgPrecision; }
    public double getAAvgMrr() { return aAvgMrr; }
    public double getBAvgMrr() { return bAvgMrr; }
}
