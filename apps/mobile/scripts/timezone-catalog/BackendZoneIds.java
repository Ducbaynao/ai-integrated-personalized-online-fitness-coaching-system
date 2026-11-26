import java.time.ZoneId;
import java.time.zone.ZoneRulesProvider;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Prints the exact membership set used by every Backend timezone validator
 * ({@code ZoneId.getAvailableZoneIds().contains(timezone)}), plus runtime metadata.
 * Java standard library only; no Spring, database or network access.
 * Run with the JDK that matches the Backend runtime: {@code java BackendZoneIds.java}.
 */
public class BackendZoneIds {
    public static void main(String[] args) {
        List<String> ids = new ArrayList<>(ZoneId.getAvailableZoneIds());
        Collections.sort(ids);
        System.out.println("#runtime=" + Runtime.version());
        System.out.println("#vendor=" + System.getProperty("java.vendor"));
        System.out.println("#tzdb=" + ZoneRulesProvider.getVersions("UTC").lastKey());
        for (String id : ids) {
            System.out.println(id);
        }
    }
}
