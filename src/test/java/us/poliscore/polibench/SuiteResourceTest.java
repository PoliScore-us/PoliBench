package us.poliscore.polibench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import us.poliscore.polibench.models.Pillar;
import us.poliscore.polibench.models.TestSuite;

import java.io.InputStream;
import java.util.EnumSet;
import java.util.Set;

class SuiteResourceTest {
    private static final String[] SUITE_FILES = {
            "precision.json",
            "evidence.json",
            "feasibility.json",
            "budget.json",
            "fairness.json",
            "governance.json",
            "risk.json"
    };

    @Test
    void bundledSuitesCoverEveryPillar() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Set<Pillar> loadedPillars = EnumSet.noneOf(Pillar.class);

        for (String suiteFile : SUITE_FILES) {
            try (InputStream input = getClass().getResourceAsStream("/suites/" + suiteFile)) {
                assertNotNull(input, "Missing bundled suite resource: " + suiteFile);
                TestSuite suite = mapper.readValue(input, TestSuite.class);
                loadedPillars.add(suite.getPillar());
            }
        }

        assertEquals(EnumSet.allOf(Pillar.class), loadedPillars);
    }
}
