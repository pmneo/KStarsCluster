package de.pmneo.kstars.utils;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

public class CachedValue<T> {
    private final long timeout;
    private final Supplier<T> supplier;

    public CachedValue( Supplier<T> supplier, long timeout ) {
        this.supplier = supplier;
        this.timeout = timeout;
    }

    private final AtomicLong lastUpdate = new AtomicLong( 0 );
    private final AtomicReference<T> value = new AtomicReference<>();
    public T get() {
        var last = lastUpdate.get();
        if( last + timeout < System.currentTimeMillis() && lastUpdate.compareAndSet( last, System.currentTimeMillis() ) ) {
            value.set( supplier.get() );
        }
        return value.get();
    }

    public void forceUpdate() {
        lastUpdate.set( 0 );
    }
}
