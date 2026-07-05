package com.nextplease.backend.service;

import java.util.Map;

/**
 * Single source of truth for how much EXP and reputation an approved experience
 * (proof-of-work) is worth. Shared by {@link CredentialService} (which awards the
 * points on approval) and {@link ProfileService} (which surfaces the amounts on the
 * portfolio so candidates and recruiters see the value of each verified item).
 */
public final class ExperienceRewards {

    private ExperienceRewards() {
    }

    public static final int DEFAULT_EXP = 100;
    public static final int DEFAULT_RS = 5;

    private static final Map<String, Integer> EXP_BY_CATEGORY = Map.of(
            "CLUB_SMALL", 100,
            "SCHOOL_CAMPAIGN", 300,
            "COMPANY_PROJECT", 500,
            "SHORT_INTERNSHIP", 500,
            "FREELANCE_GIG", 500
    );

    private static final Map<String, Integer> RS_BY_ROLE = Map.of(
            "MEMBER", 5,
            "LEADER", 10
    );

    public static int expFor(String category) {
        return EXP_BY_CATEGORY.getOrDefault(category, DEFAULT_EXP);
    }

    public static int rsFor(String roleLevel) {
        return RS_BY_ROLE.getOrDefault(roleLevel, DEFAULT_RS);
    }

    public static boolean isValidCategory(String category) {
        return EXP_BY_CATEGORY.containsKey(category);
    }

    public static boolean isValidRole(String roleLevel) {
        return RS_BY_ROLE.containsKey(roleLevel);
    }

    public static java.util.Set<String> categories() {
        return EXP_BY_CATEGORY.keySet();
    }
}
