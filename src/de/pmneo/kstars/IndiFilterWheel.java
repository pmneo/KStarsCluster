package de.pmneo.kstars;

import java.util.ArrayList;
import java.util.List;

import org.kde.kstars.INDI;
import org.kde.kstars.INDI.IpsState;

public class IndiFilterWheel extends IndiDevice {

    public IndiFilterWheel(String deviceName, Device<INDI> indi) {
        super(deviceName, indi);
    }
    
    /*
 "CONNECTION", "CONNECT",
 "CONNECTION", "DISCONNECT",
 "DRIVER_INFO", "DRIVER_NAME",
 "DRIVER_INFO", "DRIVER_EXEC",
 "DRIVER_INFO", "DRIVER_VERSION",
 "DRIVER_INFO", "DRIVER_INTERFACE",
 "DEBUG", "ENABLE",
 "DEBUG", "DISABLE",
 "SIMULATION", "ENABLE",
 "SIMULATION", "DISABLE",
 "CONFIG_PROCESS", "CONFIG_LOAD",
 "CONFIG_PROCESS", "CONFIG_SAVE",
 "CONFIG_PROCESS", "CONFIG_DEFAULT",
 "CONFIG_PROCESS", "CONFIG_PURGE",
 "POLLING_PERIOD", "PERIOD_MS",
 "FILTER_SLOT", "FILTER_SLOT_VALUE",
 "FILTER_NAME", "FILTER_SLOT_NAME_1",
 "FILTER_NAME", "FILTER_SLOT_NAME_2",
 "FILTER_NAME", "FILTER_SLOT_NAME_3",
 "FILTER_NAME", "FILTER_SLOT_NAME_4",
 "FILTER_NAME", "FILTER_SLOT_NAME_5",
 "FILTER_NAME", "FILTER_SLOT_NAME_6",
 "FILTER_NAME", "FILTER_SLOT_NAME_7",
 "USEJOYSTICK", "ENABLE",
 "USEJOYSTICK", "DISABLE",
 "SNOOP_JOYSTICK", "SNOOP_JOYSTICK_DEVICE",
 "FILTER_UNIDIRECTIONAL_MOTION", "INDI_ENABLED",
 "FILTER_UNIDIRECTIONAL_MOTION", "INDI_DISABLED",
 "FILTER_CALIBRATION", "CALIBRATE"
*/

    public int getFilterSlot() {
		return (int) getNumber( "FILTER_SLOT", "FILTER_SLOT_VALUE" );
	}
	public IpsState getFilterSlotStatus() {
		return getPropertyState( "FILTER_SLOT" );
	}
	public void setFilterSlot( int pos ) {
		setNumber( "FILTER_SLOT", "FILTER_SLOT_VALUE", pos );
	}

	private List<String> filters = null;
	public List<String> getFilters() {
		if( this.filters == null ) {
			List<String> filters = new ArrayList<>();

			int i=1;
			while( true ) {
				String filter = this.indi.methods.getText(this.deviceName, "FILTER_NAME", "FILTER_SLOT_NAME_" + i );

				if( filter == null || filter.toLowerCase().equals( "invalid" ) ) {
					break;
				}

				filters.add( filter );

				i++;
			}

			this.filters = filters;
		}
		
		return this.filters;
	}
}
