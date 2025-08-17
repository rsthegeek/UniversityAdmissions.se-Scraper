package me.fekri.scrapper;

import java.util.List;

public record Program(
        String title,
        Integer creditCount,
        String university,
        String location,
        String status,                 // e.g., "Application period not open"
        Integer firstTuitionFee,       // in SEK (numbers only) if found, else null
        Integer totalTuitionFee,       // in SEK (numbers only) if found, else null
        String period,
        String level,
        String languageOfInstruction,
        String applicationCode,
        String teachingForm,
        String paceOfStudy,
        String instructionalTime,
        String[] subjectAreas          // as requested: array of strings
) {
    public boolean enterListIfEligible(List<Program> list) {
        if (isEligible()) {
            return list.add(this);
        }
        return false;
    }

    private boolean isEligible() {
        return (this.title != null && !this.title.isBlank())
                || (this.applicationCode != null && !this.applicationCode.isBlank());
    }

    private static String clean(String s) {
        if (s == null) return null;
        String t = s.replace("\u00A0", "").trim();
        return t.isBlank() ? null : t;
    }
}