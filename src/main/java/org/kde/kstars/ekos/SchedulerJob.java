package org.kde.kstars.ekos;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.io.StringReader;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import de.pmneo.kstars.utils.IOUtils;

public class SchedulerJob implements Serializable {


    public static void main(String[] args) {
        parseEslFile( new File( System.getProperty("user.home") + "/current_schedule.esl" ) );
    }

    /** Null-safe replacement for the previous {@code el.getElementsByTagName(tag).item(0).getTextContent()}
     *  chain — that threw a NullPointerException on any element a <Job> doesn't carry, which turned out
     *  to be the normal case for follower jobs (see below), not just malformed files. */
    private static String text( Element el, String tag ) {
        NodeList nl = el.getElementsByTagName( tag );
        return nl.getLength() > 0 ? nl.item(0).getTextContent() : null;
    }

    /** Sums every &lt;Job&gt;&lt;Count&gt; in a raw Ekos Sequence Queue (.esq) XML string — its total
     *  planned frame count, e.g. 14 filter/exposure &lt;Job&gt; entries at &lt;Count&gt;2&lt;/Count&gt;
     *  each sums to 28. Root is &lt;SequenceQueue&gt; with each &lt;Job&gt; a direct child (not nested),
     *  confirmed against real .esq files under kstars' own scheduler test fixtures. Returns 0 (not a
     *  thrown exception) on anything malformed — same best-effort spirit as the try/catch around its
     *  one caller below; a garbled sequence file shouldn't be worse than "unknown count". */
    private static int countSequenceFrames( String sequenceXml ) {
        try {
            DocumentBuilder b = DocumentBuilderFactory.newInstance().newDocumentBuilder();
            Document doc = b.parse( new InputSource( new StringReader( sequenceXml ) ) );
            NodeList jobs = doc.getDocumentElement().getElementsByTagName( "Job" );
            int total = 0;
            for( int i=0; i<jobs.getLength(); i++ ) {
                String count = text( (Element) jobs.item(i), "Count" );
                total += count != null ? Integer.parseInt( count ) : 0;
            }
            return total;
        }
        catch( Exception e ) {
            return 0;
        }
    }

    public static List<SchedulerJob> parseEslFile( File esl ) {
        try {
            DocumentBuilder b = DocumentBuilderFactory.newInstance().newDocumentBuilder();
            Document doc = b.parse( esl );

            List<SchedulerJob>  sl = new ArrayList<>();

            NodeList jobs = doc.getDocumentElement().getElementsByTagName( "Job" );

            // A "follower" job (<JobType lead='false'/>, one per secondary optical train) images
            // the same target as the lead job immediately before it in the file, so the ESL format
            // doesn't repeat Name/Coordinates/PositionAngle for it — only OpticalTrain and Sequence
            // differ. Confirmed against a real current_schedule.esl: every lead Job is immediately
            // followed by its train's follower Job(s). Without inheriting here, a follower would
            // show up on the Sky Map "open targets" overlay as a phantom marker at RA=0/DEC=0
            // instead of its actual target.
            SchedulerJob lastLead = null;

            for( int i=0; i<jobs.getLength(); i++ ) {
                Element jobEl = (Element) jobs.item(i);

                SchedulerJob job = new SchedulerJob();

                NodeList jobTypeNodes = jobEl.getElementsByTagName( "JobType" );
                job.lead = jobTypeNodes.getLength() == 0
                        || !"false".equals( ( (Element) jobTypeNodes.item(0) ).getAttribute( "lead" ) );
                job.opticalTrain = text( jobEl, "OpticalTrain" );

                String name = text( jobEl, "Name" );
                String ra = text( jobEl, "J2000RA" );
                String de = text( jobEl, "J2000DE" );
                String pa = text( jobEl, "PositionAngle" );

                if( name != null ) {
                    job.name = name;
                    job.targetRA = ra != null ? Double.parseDouble( ra ) : 0;
                    job.targetDEC = de != null ? Double.parseDouble( de ) : 0;
                    job.pa = pa != null ? Double.parseDouble( pa ) : 0;
                }
                else if( lastLead != null ) {
                    job.name = lastLead.name;
                    job.targetRA = lastLead.targetRA;
                    job.targetDEC = lastLead.targetDEC;
                    job.pa = lastLead.pa;
                }

                if( job.lead ) {
                    lastLead = job;
                }

                String sequence = text( jobEl, "Sequence" );
                job.sequence = sequence != null ? new File( sequence ).toURI().toString() : null;

                // A live job gets sequenceCount straight from Ekos's own currentJobJson/jsonJobs
                // (see KStarsCluster.updateSchedulerActiveJob/fetchAllSchedulerJobs) — there's no
                // live Ekos to ask here, so it's recomputed by reading the same .esq file Ekos
                // itself would load, the same total the "Progress" column shows for a live job.
                // Best-effort: a sequence file that's since moved/deleted (or just not yet reachable
                // from wherever this runs) shouldn't fail parsing the rest of the schedule over it —
                // sequenceCount simply stays at its 0 default for that one job.
                if( job.sequence != null ) {
                    try {
                        job.loadSequenceContent();
                        job.sequenceCount = countSequenceFrames( job.sequenceContent );
                    }
                    catch( IOException e ) {
                        // leave sequenceCount at 0 — see comment above
                    }
                }

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
    /** Only populated by parseEslFile() (not present in the live D-Bus job JSON) — true for the
     *  train that owns this target's Name/Coordinates, false for a follower train imaging the
     *  same target. See parseEslFile()'s lead-inheritance comment. */
    public boolean lead;
    public double minAltitude;
    public double minMoonSeparation;
    public String name;
    /** Only populated by parseEslFile() — which optical train this job runs on. */
    public String opticalTrain;
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
