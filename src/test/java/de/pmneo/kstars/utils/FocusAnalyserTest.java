package de.pmneo.kstars.utils;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.net.URL;
import java.util.Date;

import org.junit.jupiter.api.Test;

/**
 * Regression test for the device-mixing bug: FocusAnalyser used to key its per-filter regression
 * by filter name alone, so a rig with two focusers (Primary "DeepSkyDad AF3 A" and Secondary
 * "DeepSkyDad AF3") using the same filter name (e.g. "Ha") had their positions — on completely
 * different scales, ~49-50k vs ~53-54k — silently merged into one regression. Fixture is a real
 * copy of ~/.local/share/kstars/focuslogs/autofocus-2026-08-13T21-46-37.txt (a stable night, see
 * src/test/resources/focus-drift for the corresponding drift-detection fixtures).
 */
class FocusAnalyserTest {

    @Test
    void keepsDifferentFocusersOnSameFilterSeparate() throws Exception {
        URL dirUrl = getClass().getClassLoader().getResource( "focus-analyser/focuslogs" );
        assertNotNull( dirUrl, "missing test fixture directory" );
        File dir = new File( dirUrl.toURI() );

        // since = epoch so the fixture's fixed 2026-08-13/14 dates are never filtered out by the
        // real "last 40 days" cutoff, regardless of when this test actually runs.
        FocusAnalyser fa = new FocusAnalyser( dir, new Date( 0 ) );

        int primaryHa = fa.aproximatePos( "DeepSkyDad AF3 A", "Ha", 18.0 );
        int secondaryHa = fa.aproximatePos( "DeepSkyDad AF3", "Ha", 18.0 );

        // Before the fix, both calls resolved to the same merged "Ha" regression and could
        // return either device's scale (or something in between) for either device.
        assertTrue( primaryHa > 49200 && primaryHa < 49900,
                "Primary/Ha prediction should stay within its own recorded range (~49300-49750), was " + primaryHa );
        assertTrue( secondaryHa > 53500 && secondaryHa < 54200,
                "Secondary/Ha prediction should stay within its own recorded range (~53670-54080), was " + secondaryHa );
    }
}
