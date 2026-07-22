package org.kde.kstars.ekos;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import de.pmneo.kstars.utils.IOUtils;

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

    /** Mirrors Ekos SchedulerJob::JOBStatus — the "state" field of currentJobJson/jsonJobs. */
    public static enum JobState {
        JOB_IDLE,       /*< Job has not been processed yet */
        JOB_EVALUATION, /*< Job is being evaluated */
        JOB_SCHEDULED,  /*< Job was evaluated and is waiting for its startup time */
        JOB_BUSY,       /*< Job is being EXECUTED right now */
        JOB_ERROR,      /*< Job encountered a fatal issue */
        JOB_ABORTED,    /*< Job encountered a transitory issue */
        JOB_INVALID,    /*< Job doesn't fit the constraints */
        JOB_COMPLETE    /*< Job finished all required captures */
    }

    public JobState getState() {
        final JobState[] values = JobState.values();
        return state >= 0 && state < values.length ? values[ state ] : JobState.JOB_IDLE;
    }

    /** True only while the scheduler actually EXECUTES this job — not while waiting for its startup time. */
    public boolean isExecuting() {
        return getState() == JobState.JOB_BUSY;
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

    public double fRatio;

    public String sequenceContent;

    @Override
    public String toString() {
        return name + "( " + targetRA+ "/" + targetDEC + " @ " + pa + "° = " + sequence + ")";
    }

    public String loadSequenceContent() throws IOException {
        return sequenceContent = IOUtils.readTextContent(new URL( sequence ), "UTF-8" );
    }
}
