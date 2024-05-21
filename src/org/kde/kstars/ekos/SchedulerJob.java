package org.kde.kstars.ekos;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.eclipse.jetty.util.IO;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

public class SchedulerJob implements Serializable {


    public static void main(String[] args) {
        parseEslFile( new File( System.getProperty("user.home") + "/current_schedule.esl" ) );
    }

    public static List<SchedulerJob> parseEslFile( File esl ) {
        try {
            DocumentBuilder b = DocumentBuilderFactory.newInstance().newDocumentBuilder();
            Document doc = b.parse( esl );

            List<SchedulerJob>  sl = new ArrayList<>();

            NodeList jobs = doc.getDocumentElement().getElementsByTagName( "Job" );

            for( int i=0; i<jobs.getLength(); i++ ) {
                Element jobEl = (Element) jobs.item(i);

                SchedulerJob job = new SchedulerJob();

                job.name = jobEl.getElementsByTagName( "Name" ).item(0).getTextContent();
                job.targetRA = Double.parseDouble( jobEl.getElementsByTagName( "J2000RA" ).item(0).getTextContent() );
                job.targetDEC = Double.parseDouble( jobEl.getElementsByTagName( "J2000DE" ).item(0).getTextContent() );
                
                job.pa = Double.parseDouble( jobEl.getElementsByTagName( "PositionAngle" ).item(0).getTextContent() );
                job.sequence = new File( jobEl.getElementsByTagName( "Sequence" ).item(0).getTextContent() ).toURI().toString();

                
                sl.add( job );

                System.out.println( job );
            }

            return sl;
        }
        catch( Throwable t ) {
            throw new RuntimeException( "Failed to read esl file", t );
        }
    }

    public double altitude;
    public int completedCount;
    public String completionTime;
    public boolean inSequenceFocus;
    public double minAltitude;
    public double minMoonSeparation;
    public String name;
    public double pa;
    public int repeatsRemaining;
    public int repeatsRequired;
    public String sequence;
    public int sequenceCount;
    public int stage;
    public String startupTime;
    public int state;
    public double targetDEC;
    public double targetRA;

    public String sequenceContent;

    @Override
    public String toString() {
        return name + "( " + targetRA+ "/" + targetDEC + " @ " + pa + "° = " + sequence + ")";
    }

    public String loadSequenceContent() throws IOException {
        URL seq = new URL( sequence );
                
        try( InputStream in = seq.openStream() ) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            IO.copy(in, out);
            return sequenceContent = out.toString( "UTF-8" );
        }
    }
}
