package de.pmneo.kstars;

import java.util.ArrayList;
import java.util.List;

import org.kde.kstars.INDI;
import org.kde.kstars.INDI.IpsState;

public class IndiFilterWheel extends IndiDevice {

    public IndiFilterWheel(String deviceName, Device<INDI> indi) {
        super(deviceName, indi);
    }

    public int getFilterSlot() {
        return (int) getProperty( "FILTER_SLOT" ).getNumber( "FILTER_SLOT_VALUE" );
    }
    public IpsState getFilterSlotStatus() {
        return getProperty( "FILTER_SLOT" ).state;
    }
    public void setFilterSlot( int pos ) {
        setNumber( "FILTER_SLOT", "FILTER_SLOT_VALUE", pos );
    }

    /** Filter names, served entirely from the cache primed by watchProperty. */
    public List<String> getFilters() {
        List<String> filters = new ArrayList<>();
        IndiProperty names = getProperty( "FILTER_NAME" );
        for( int i = 1; ; i++ ) {
            String filter = names.getText( "FILTER_SLOT_NAME_" + i );
            if( filter == null || filter.isEmpty() || filter.equalsIgnoreCase( "invalid" ) ) {
                break;
            }
            filters.add( filter );
        }
        return filters;
    }
}
