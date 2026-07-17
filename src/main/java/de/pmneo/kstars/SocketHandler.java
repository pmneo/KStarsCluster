package de.pmneo.kstars;

import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.function.BiConsumer;

public class SocketHandler {
    public final Socket socket;
    
    public final InputStream _input;
    public final OutputStream _output;
    
    public ObjectInputStream oInput;
    public ObjectOutputStream oOutput;
    
    public SocketHandler( final Socket socket ) throws IOException {
        this.socket = socket;
        this._input = ( socket.getInputStream() );
        this._output = ( socket.getOutputStream() );
        
        oInput = null;
        oOutput = null;
    }

    public void writeNotNullObject( Object frame ) throws IOException {
        if( frame == null ) {
            return;
        }
        
        writeObject( frame );
    }
    
    public void writeObject( Object frame ) throws IOException {
        synchronized ( _output ) {
            this.getOutput().writeObject( frame );
            this.getOutput().flush();
        }
    }
    
    public ObjectOutputStream getOutput() throws IOException {
        synchronized ( _output ) {
            if( oOutput == null ) {
                oOutput = new ObjectOutputStream( _output );
            }
        }
        return oOutput;
    }
    public ObjectInputStream getInput() throws IOException {
        synchronized ( _input ) {
            if( oInput == null ) {
                oInput = new ObjectInputStream( _input );
            }
        }
        return oInput;
    }
    
    @Override
    public String toString() {
        return String.valueOf( socket.getRemoteSocketAddress() );
    }

    public void receive( final BiConsumer<SocketHandler, Object> frameReceived, final BiConsumer<SocketHandler, Throwable > disconnected ) {
		try {
			while( this.socket.isConnected() ) {
				final Object frame = this.getInput().readObject();
				if( frame != null ) {
					frameReceived.accept( this, frame );
				}
			}
		}
		catch( Throwable t ) {
			disconnected.accept( this, t );
			return;
		}
		
		disconnected.accept( this, null );
	}
}