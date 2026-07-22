package de.pmneo.kstars;

import java.util.LinkedHashMap;
import java.util.Map;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.kde.kstars.INDI.IpsState;

/**
 * One INDI property's current value, as delivered by INDI.watchProperty() and the
 * propertyValueChanged signal (both carry the same compact JSON encoding).
 */
public class IndiProperty {

    public final String device;
    public final String name;
    public final IpsState state;

    private final Map<String, Double> numbers = new LinkedHashMap<>();
    private final Map<String, String> switches = new LinkedHashMap<>();
    private final Map<String, String> texts = new LinkedHashMap<>();

    /** Returns null if json is null/blank (property didn't exist / KStars returned nothing). */
    public static IndiProperty parse( String json ) {
        if( json == null || json.isBlank() ) {
            return null;
        }
        return new IndiProperty( JsonParser.parseString( json ).getAsJsonObject() );
    }

    private IndiProperty( JsonObject obj ) {
        this.device = obj.has( "device" ) ? obj.get( "device" ).getAsString() : null;
        this.name = obj.has( "name" ) ? obj.get( "name" ).getAsString() : null;

        final IpsState[] states = IpsState.values();
        final int s = obj.has( "state" ) ? obj.get( "state" ).getAsInt() : -1;
        this.state = ( s >= 0 && s < states.length ) ? states[s] : IpsState.IPS_IDLE;

        if( obj.has( "numbers" ) ) {
            for( JsonElement el : obj.getAsJsonArray( "numbers" ) ) {
                JsonObject o = el.getAsJsonObject();
                numbers.put( o.get( "name" ).getAsString(), o.get( "value" ).getAsDouble() );
            }
        }
        if( obj.has( "switches" ) ) {
            for( JsonElement el : obj.getAsJsonArray( "switches" ) ) {
                JsonObject o = el.getAsJsonObject();
                switches.put( o.get( "name" ).getAsString(), o.get( "state" ).getAsInt() == 1 ? "On" : "Off" );
            }
        }
        if( obj.has( "texts" ) ) {
            for( JsonElement el : obj.getAsJsonArray( "texts" ) ) {
                JsonObject o = el.getAsJsonObject();
                texts.put( o.get( "name" ).getAsString(), o.get( "text" ).getAsString() );
            }
        }
    }

    public double getNumber( String element ) {
        Double v = numbers.get( element );
        if( v == null ) {
            throw new IllegalStateException( "No number element '" + element + "' in property " + name );
        }
        return v;
    }

    public String getSwitch( String element ) {
        String v = switches.get( element );
        if( v == null ) {
            throw new IllegalStateException( "No switch element '" + element + "' in property " + name );
        }
        return v;
    }

    public boolean isOn( String element ) {
        return "On".equalsIgnoreCase( getSwitch( element ) );
    }

    /** Nullable — used to enumerate a variable-length set of text elements (e.g. filter slot names). */
    public String getText( String element ) {
        return texts.get( element );
    }
}
