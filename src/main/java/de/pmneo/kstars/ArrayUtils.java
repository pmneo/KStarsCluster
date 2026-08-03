package de.pmneo.kstars;

import java.lang.reflect.Array;
import java.util.AbstractList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.RandomAccess;

public class ArrayUtils {
	/**
     * @serial include
     */
    private static class PrimitveArrayList<E> extends AbstractList<E> implements RandomAccess, java.io.Serializable
    {
        private static final long serialVersionUID = -2764017481108945198L;
        private final Object a;

        PrimitveArrayList(Object array) {
            a = Objects.requireNonNull(array);
        }

        @Override
        public int size() {
            return Array.getLength( a );
        }

        @SuppressWarnings( "unchecked" )
		@Override
        public E get(int index) {
            return (E) Array.get( a, index );
        }

        @Override
        @SuppressWarnings( "unchecked" )
        public E set(int index, E element) {
            E oldValue = (E) Array.get( a, index );
            Array.set( a, index, element );
            return oldValue;
        }
    }
	
	public static List<Object> arrayToList( final Object array ) {
		if( array == null ) {
			return null;
		}
		else if( array.getClass().isArray() == false ) {
			return null;
		}
		else {
			Class<?> elementType = array.getClass().getComponentType();
			if( elementType.isPrimitive() ) {
				return new PrimitveArrayList<Object>( array );
			}
			else {
				return Arrays.asList( (Object[]) array );
			}
		}
	}
	
}
