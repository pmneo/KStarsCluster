package de.pmneo.kstars.utils;

import java.util.concurrent.atomic.AtomicBoolean;

public class DirtyBoolean {
    private final AtomicBoolean value;
    private final AtomicBoolean changed = new AtomicBoolean( false );

    public DirtyBoolean() {
        this( false );
    }
    public DirtyBoolean( boolean value ) {
        this.value = new AtomicBoolean( value );
    }

    public boolean set( boolean value ) {
        boolean prevValue = this.value.getAndSet( value );
        if( prevValue != value ) {
            this.changed.set( true );
            return true;
        }
        else {
            return false;
        }
    }

    public boolean get( ) {
        return this.value.get();
    }

    

    public boolean hasChanged() {
        return this.changed.get();
    }

    public boolean hasChangedAndReset( ) {
        boolean changed = this.changed.getAndSet( false );
        return changed;
    }
}
