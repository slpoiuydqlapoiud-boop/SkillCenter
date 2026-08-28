package com.huawei.skillcenter.quality;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class QualityGateService {
    public QualityGate evaluate(QualityRuleSet rules, int score, double passRate, int staticScore) {
        List<String> reasons = new ArrayList<>();
        if (score < rules.minScore()) {
            reasons.add("QUALITY_SCORE_BELOW_THRESHOLD");
        }
        if (passRate < rules.minPassRate()) {
            reasons.add("PASS_RATE_BELOW_THRESHOLD");
        }
        if (staticScore < rules.minStaticScore()) {
            reasons.add("STATIC_SCORE_BELOW_THRESHOLD");
        }
        return new QualityGate(reasons.isEmpty() ? QualityGateStatus.PASSED : QualityGateStatus.BLOCKED, reasons);
    }
}
