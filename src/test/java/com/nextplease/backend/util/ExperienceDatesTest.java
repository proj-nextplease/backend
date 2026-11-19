package com.nextplease.backend.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Date;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class ExperienceDatesTest {

    @Test
    void readsFullDayFormats() {
        ExperienceDates.Parsed p = ExperienceDates.parse("15/09/2024");
        assertEquals(LocalDate.of(2024, 9, 15), p.date());
        assertTrue(p.hasDay());
        assertEquals(LocalDate.of(2024, 9, 15), ExperienceDates.parse("2024-09-15").date());
        assertTrue(ExperienceDates.parse("2024-09-15").hasDay());
    }

    @Test
    void readsLegacyMonthFormatsWithoutInventingADay() {
        for (String v : new String[] {"09/24", "09/2024", "2024-09", " 9/2024 "}) {
            ExperienceDates.Parsed p = ExperienceDates.parse(v);
            assertEquals(LocalDate.of(2024, 9, 1), p.date(), v);
            assertFalse(p.hasDay(), v);
        }
    }

    @Test
    void rejectsImpossibleOrFreeText() {
        assertNull(ExperienceDates.parse("31/02/2024"));
        assertNull(ExperienceDates.parse("13/2024"));
        assertNull(ExperienceDates.parse("Hiện tại"));
        assertNull(ExperienceDates.parse(""));
        assertNull(ExperienceDates.parse(null));
    }

    @Test
    void formatsByPrecision() {
        Date d = Date.valueOf(LocalDate.of(2026, 6, 5));
        assertEquals("05/06/2026", ExperienceDates.format(d, true));
        assertEquals("06/2026", ExperienceDates.format(d, false));
        assertEquals("", ExperienceDates.format(null, true));
    }

    @Test
    void comparesMixedPrecisionByMonth() {
        ExperienceDates.Parsed monthOnly = ExperienceDates.parse("06/2026");
        ExperienceDates.Parsed tenth = ExperienceDates.parse("10/06/2026");
        ExperienceDates.Parsed may = ExperienceDates.parse("31/05/2026");
        assertFalse(ExperienceDates.endsBeforeStart(monthOnly, tenth));
        assertFalse(ExperienceDates.endsBeforeStart(tenth, monthOnly));
        assertTrue(ExperienceDates.endsBeforeStart(monthOnly, may));
        assertTrue(ExperienceDates.endsBeforeStart(tenth, ExperienceDates.parse("09/06/2026")));
    }

    @Test
    void futureCheckUsesDayOrMonth() {
        LocalDate today = ExperienceDates.today();
        LocalDate tomorrow = today.plusDays(1);
        assertTrue(ExperienceDates.isInFuture(ExperienceDates.parse(
                String.format("%02d/%02d/%04d", tomorrow.getDayOfMonth(), tomorrow.getMonthValue(), tomorrow.getYear()))));
        assertFalse(ExperienceDates.isInFuture(ExperienceDates.parse(
                String.format("%02d/%02d/%04d", today.getDayOfMonth(), today.getMonthValue(), today.getYear()))));
        // Chỉ có tháng: tháng này không bị coi là tương lai.
        assertFalse(ExperienceDates.isInFuture(ExperienceDates.parse(
                String.format("%02d/%04d", today.getMonthValue(), today.getYear()))));
    }
}
