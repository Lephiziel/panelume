package dev.panelume.reader;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;

final class ReadingDays {
    static Map<LocalDate, Long> split(long wallStart, long elapsed, ZoneId zone) {
        Map<LocalDate, Long> result = new LinkedHashMap<>();
        long end = wallStart + elapsed;
        long cursor = wallStart;
        while (cursor < end) {
            LocalDate day = Instant.ofEpochMilli(cursor).atZone(zone).toLocalDate();
            long nextMidnight = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
            long cut = Math.min(end, nextMidnight);
            result.put(day, cut - cursor);
            cursor = cut;
        }
        return result;
    }
}
