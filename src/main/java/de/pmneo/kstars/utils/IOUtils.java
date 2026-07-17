package de.pmneo.kstars.utils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;

import org.eclipse.jetty.util.IO;

public class IOUtils {

    public static String readTextContent( File f, String contentType ) throws IOException {
        try {
            return readTextContent( f.toURI().toURL(), contentType );
        }
        catch( IOException e ) {
            throw e;
        }
        catch( Throwable t ) {
            throw new IOException( "Failed to read content", t );
        }
    }
    public static String readTextContent( URL url, String contentType ) throws IOException {
        try( InputStream in = url.openStream() ) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            IO.copy(in, out);
            return out.toString( contentType );
        }
        catch( IOException e ) {
            throw e;
        }
        catch( Throwable t ) {
            throw new IOException( "Failed to read content", t );
        }
    }

    public static boolean writeTextContent( File f, String content, String contentType ) throws IOException {
        String oldContent = f.exists() ? readTextContent(f, contentType) : null;

        if( oldContent == null || oldContent.equals( content ) == false ) {
            try( FileOutputStream out = new FileOutputStream( f ) ) {
                IO.copy( new ByteArrayInputStream( content.getBytes( contentType ) ), out);
            }
            catch( IOException e ) {
                throw e;
            }
            catch( Throwable t ) {
                throw new IOException( "Failed to write content", t );
            }

            return true;
        }
        else {
            return false;
        }
    }
}
