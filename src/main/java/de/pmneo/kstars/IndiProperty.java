package de.pmneo.kstars;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import com.google.gson.JsonArray;
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
    public IpsState state;

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

    public IndiProperty( String device, String name ) {
        this.device = device;
        this.name = name;
        this.state = IpsState.IPS_IDLE;
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
        return Objects.requireNonNullElse(v, Double.NaN );
    }

    public String getSwitch( String element ) {
        return Objects.requireNonNullElse( switches.get( element ), "Unknown" );
    }

    public boolean getSwitchStatus( String element ) {
        return "On".equalsIgnoreCase( getSwitch( element ) );
    }

    public boolean isOn( String element ) {
        return getSwitchStatus( element );
    }

    /** Nullable — used to enumerate a variable-length set of text elements (e.g. filter slot names). */
    public String getText( String element ) {
        return texts.get( element );
    }

    public IndiProperty setNumber( String element, double value ) {
        numbers.put( element, value );
        this.state = IpsState.IPS_BUSY;
        return this;
    }

    /** state must be "On" or "Off", matching INDI's own element state strings. */
    public IndiProperty setSwitch( String element, String state ) {
        switches.put( element, state );
        this.state = IpsState.IPS_BUSY;
        return this;
    }

    /** state must be "On" or "Off", matching INDI's own element state strings. */
    public IndiProperty setSwitch( String element, boolean state ) {
        switches.put( element, state ? "On" : "Off" );
        this.state = IpsState.IPS_BUSY;
        return this;
    }

    public IndiProperty setText( String element, String value ) {
        texts.put( element, value );
        this.state = IpsState.IPS_BUSY;
        return this;
    }

    /**
     * The bare JSON array INDI.setPropertyJSON expects as elementsJson, e.g. {@code [{"name":"X","value":1.0}]}.
     * Only elements actually set on this instance are included — a real INDI property is only ever one of
     * number/switch/text, so in practice only one of the three maps is populated.
     */
    public String toElementsJson() {
        JsonArray array = new JsonArray();
        appendNumbers( array );
        appendSwitches( array );
        appendTexts( array );
        return array.toString();
    }

    /** Serializes back to the same compact JSON shape that parse()/IndiProperty(String) reads. */
    public String toJson() {
        JsonObject obj = new JsonObject();
        if( device != null ) {
            obj.addProperty( "device", device );
        }
        if( name != null ) {
            obj.addProperty( "name", name );
        }
        obj.addProperty( "state", state.ordinal() );

        if( !numbers.isEmpty() ) {
            JsonArray array = new JsonArray();
            appendNumbers( array );
            obj.add( "numbers", array );
        }
        if( !switches.isEmpty() ) {
            JsonArray array = new JsonArray();
            appendSwitches( array );
            obj.add( "switches", array );
        }
        if( !texts.isEmpty() ) {
            JsonArray array = new JsonArray();
            appendTexts( array );
            obj.add( "texts", array );
        }
        return obj.toString();
    }

    private void appendNumbers( JsonArray array ) {
        numbers.forEach( ( elementName, value ) -> {
            JsonObject o = new JsonObject();
            o.addProperty( "name", elementName );
            o.addProperty( "value", value );
            array.add( o );
        } );
    }

    private void appendSwitches( JsonArray array ) {
        switches.forEach( ( elementName, elementState ) -> {
            JsonObject o = new JsonObject();
            o.addProperty( "name", elementName );
            o.addProperty( "state", "On".equalsIgnoreCase( elementState ) ? 1 : 0 );
            array.add( o );
        } );
    }

    private void appendTexts( JsonArray array ) {
        texts.forEach( ( elementName, text ) -> {
            JsonObject o = new JsonObject();
            o.addProperty( "name", elementName );
            o.addProperty( "text", text );
            array.add( o );
        } );
    }
}