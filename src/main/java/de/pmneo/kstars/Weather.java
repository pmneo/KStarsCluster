package de.pmneo.kstars;

import org.eclipse.jetty.client.HttpClient;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

public class Weather extends WithLogging{
    private boolean weatherSafty = false;
    private final long updateDelta = 15;
    private long lastWeatherCheck = System.currentTimeMillis() - TimeUnit.SECONDS.toMillis(updateDelta*2);

    public boolean checkWeatherStatus( HttpClient client ) {
        long delta = TimeUnit.MILLISECONDS.toSeconds( System.currentTimeMillis() - this.lastWeatherCheck );

        if( delta >= updateDelta ) {
            boolean weatherSafty = false;
            for (int i = 0; i < 5; i++) {
                weatherSafty = false;
                try {
                    //logMessage( "Check weather status, last check was " + delta + " seconds ago");
                    var res = client.newRequest("http://192.168.0.106:8087/getPlainValue/0_userdata.0.Roof.isSafeCondition").send();
                    weatherSafty = Boolean.parseBoolean(res.getContentAsString());

                    if (delta >= (updateDelta + 5)) {
                        logMessage("Resumed weather status after " + delta + " seconds");
                    }
                    this.lastWeatherCheck = System.currentTimeMillis();
                    break;
                } catch (ExecutionException e) {
                    if (delta < 90) {
                        logMessage("Failed to get weather status since " + delta + " seconds");
                        weatherSafty = this.weatherSafty;
                    } else {
                        logError("Failed to get weather status since more than 90 seconds: " + delta, e);
                        weatherSafty = false;
                    }
                } catch (Throwable t) {
                    logError("Failed to get weather status", t);
                    weatherSafty = false;
                }
            }

            if( this.weatherSafty != weatherSafty ) {
                logMessage( "Weather saftey changed from " + this.weatherSafty + " to " + weatherSafty);
                this.weatherSafty = weatherSafty;
            }
        }

        return this.weatherSafty;
    }
}
