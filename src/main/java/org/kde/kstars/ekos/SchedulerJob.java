package org.kde.kstars.ekos;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.Serializable;
import java.io.StringReader;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
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

    /** Sums Exposure*Count across every &lt;Job&gt; in a raw Ekos Sequence Queue (.esq) FILE — the
     *  total wall-clock time this sequence takes to capture (excluding slews/focus/meridian
     *  flips etc., just the exposures themselves). Used by the "sequenceFiles" web action to show
     *  duration next to each option in the Add Scheduler Job dialog's Sequence picker, so a raw
     *  filename doesn't have to be reverse-engineered to know how long a sequence runs. Returns 0
     *  on anything malformed, same best-effort spirit as countSequenceFrames() above. */
    public static double totalExposureSeconds( File esq ) {
        try {
            DocumentBuilder b = DocumentBuilderFactory.newInstance().newDocumentBuilder();
            Document doc = b.parse( esq );
            NodeList jobs = doc.getDocumentElement().getElementsByTagName( "Job" );
            double total = 0;
            for( int i=0; i<jobs.getLength(); i++ ) {
                Element job = (Element) jobs.item(i);
                String exposure = text( job, "Exposure" );
                String count = text( job, "Count" );
                if( exposure != null && count != null ) {
                    total += Double.parseDouble( exposure ) * Integer.parseInt( count );
                }
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

    private static Element textElement( Document doc, String tag, String text ) {
        Element el = doc.createElement( tag );
        el.setTextContent( text );
        return el;
    }

    private static Element valueElement( Document doc, String tag, String text, String value ) {
        Element el = textElement( doc, tag, text );
        el.setAttribute( "value", value );
        return el;
    }

    /** This observatory is always a dual-train rig — every hand-created job pair in a real
     *  current_schedule.esl targets "Primary" (lead) + "Secondary" (follower), no exceptions — so
     *  a newly-added target always gets both, not a per-job train choice. */
    private static final String LEAD_TRAIN = "Primary";
    private static final String FOLLOWER_TRAIN = "Secondary";

    /** Builds the LEAD &lt;Job&gt; DOM element matching the shape KStars itself writes (confirmed
     *  against a real current_schedule.esl): ASAP startup, the same altitude/moon-separation/
     *  twilight/horizon constraint set seen on every hand-created job in that file, all four
     *  Track/Focus/Align/Guide steps, and a simple repeat-N-times completion condition. Good
     *  enough for "point the mount here and shoot this sequence N times" — anything more
     *  elaborate (culmination timing, per-step toggles, custom constraints) still needs tuning in
     *  Ekos's own Scheduler editor afterwards. */
    private static Element buildLeadJobElement(
            Document doc, String name, double ra, double dec, double pa,
            String sequencePath, int repeats ) {
        Element job = doc.createElement( "Job" );

        Element jobType = doc.createElement( "JobType" );
        jobType.setAttribute( "lead", "true" );
        job.appendChild( jobType );

        job.appendChild( textElement( doc, "Name", name ) );
        job.appendChild( doc.createElement( "Group" ) );

        Element coords = doc.createElement( "Coordinates" );
        coords.appendChild( textElement( doc, "J2000RA", String.valueOf( ra ) ) );
        coords.appendChild( textElement( doc, "J2000DE", String.valueOf( dec ) ) );
        job.appendChild( coords );

        job.appendChild( textElement( doc, "OpticalTrain", LEAD_TRAIN ) );
        job.appendChild( textElement( doc, "PositionAngle", String.valueOf( pa ) ) );
        job.appendChild( textElement( doc, "Sequence", sequencePath ) );

        Element startup = doc.createElement( "StartupCondition" );
        startup.appendChild( textElement( doc, "Condition", "ASAP" ) );
        job.appendChild( startup );

        Element constraints = doc.createElement( "Constraints" );
        constraints.appendChild( valueElement( doc, "Constraint", "MinimumAltitude", "-15" ) );
        constraints.appendChild( valueElement( doc, "Constraint", "MoonSeparation", "10" ) );
        constraints.appendChild( textElement( doc, "Constraint", "EnforceTwilight" ) );
        constraints.appendChild( textElement( doc, "Constraint", "EnforceArtificialHorizon" ) );
        job.appendChild( constraints );

        job.appendChild( buildCompletionCondition( doc, repeats ) );

        Element steps = doc.createElement( "Steps" );
        for( String step : new String[]{ "Track", "Focus", "Align", "Guide" } ) {
            steps.appendChild( textElement( doc, "Step", step ) );
        }
        job.appendChild( steps );

        return job;
    }

    /** Builds the FOLLOWER &lt;Job&gt; DOM element that always immediately follows a lead job in
     *  this observatory's real schedule files — no Name/Coordinates/Group/StartupCondition/
     *  Constraints/Steps of its own (inherited from the preceding lead job by parseEslFile(), see
     *  its own comment), just JobType/OpticalTrain/PositionAngle/Sequence/CompletionCondition,
     *  each duplicating the lead job's own value (confirmed against every pair in a real file —
     *  PositionAngle/Sequence/CompletionCondition are physically repeated, not omitted like Name/
     *  Coordinates are). */
    private static Element buildFollowerJobElement( Document doc, double pa, String sequencePath, int repeats ) {
        Element job = doc.createElement( "Job" );

        Element jobType = doc.createElement( "JobType" );
        jobType.setAttribute( "lead", "false" );
        job.appendChild( jobType );

        job.appendChild( textElement( doc, "OpticalTrain", FOLLOWER_TRAIN ) );
        job.appendChild( textElement( doc, "PositionAngle", String.valueOf( pa ) ) );
        job.appendChild( textElement( doc, "Sequence", sequencePath ) );
        job.appendChild( buildCompletionCondition( doc, repeats ) );

        return job;
    }

    private static Element buildCompletionCondition( Document doc, int repeats ) {
        Element completion = doc.createElement( "CompletionCondition" );
        completion.appendChild( valueElement( doc, "Condition", "Repeat", String.valueOf( repeats ) ) );
        return completion;
    }

    private static void writeDocument( Document doc, File target ) throws Exception {
        Transformer t = TransformerFactory.newInstance().newTransformer();
        t.setOutputProperty( OutputKeys.INDENT, "yes" );
        t.setOutputProperty( OutputKeys.ENCODING, "UTF-8" );
        try( FileOutputStream out = new FileOutputStream( target ) ) {
            t.transform( new DOMSource( doc ), new StreamResult( out ) );
        }
    }

    /** Finds where to splice a new lead+follower pair into an existing &lt;SchedulerList&gt;'s
     *  children: right before the lead &lt;Job&gt; named {@code insertBeforeJobName} (i.e. before
     *  that target's whole pair), or — if that's null/blank/not found — right after the last
     *  existing &lt;Job&gt; (or right after &lt;Profile&gt; if there are none yet), i.e. appended at
     *  the end. Returns null to mean "append via root.appendChild", a real Node to mean "insert
     *  before this node". Never returns a &lt;Job&gt; with no &lt;Name&gt; (a follower) as the
     *  "insert before" target — that would split an existing pair in half. */
    private static Node findInsertionPoint( Element root, String insertBeforeJobName ) {
        NodeList children = root.getChildNodes();

        if( insertBeforeJobName != null && !insertBeforeJobName.isBlank() ) {
            for( int i=0; i<children.getLength(); i++ ) {
                Node n = children.item(i);
                if( n.getNodeType() == Node.ELEMENT_NODE && "Job".equals( n.getNodeName() )
                        && insertBeforeJobName.equals( text( (Element) n, "Name" ) ) ) {
                    return n;
                }
            }
        }

        Node insertAfter = null;
        for( int i=0; i<children.getLength(); i++ ) {
            Node n = children.item(i);
            if( n.getNodeType() == Node.ELEMENT_NODE
                    && ( "Job".equals( n.getNodeName() ) || "Profile".equals( n.getNodeName() ) ) ) {
                insertAfter = n;
            }
        }
        return insertAfter != null ? insertAfter.getNextSibling() : null;
    }

    /** Appends a freshly-built lead(Primary)+follower(Secondary) job pair to the .esl file on disk
     *  (creating a minimal empty schedule first if the file doesn't exist yet) and returns the
     *  schedule's &lt;Profile&gt; name, so a caller that also wants to poke a *running* Ekos via
     *  Scheduler.appendEkosScheduleList() (see KStarsCluster's "scheduler/addJob" web action) can
     *  reuse it without a second file read.
     *
     *  Parses the existing document and re-serializes the whole thing rather than a raw string
     *  append: a real schedule file's trailing &lt;SchedulerAlgorithm&gt;/&lt;ErrorHandlingStrategy&gt;/
     *  &lt;StartupProcedure&gt;/&lt;ShutdownProcedure&gt; elements have to stay the LAST children of
     *  &lt;SchedulerList&gt;, so naively inserting text right before &lt;/SchedulerList&gt; would silently
     *  reorder those scheduler-wide settings after the new pair instead of leaving them where they
     *  are. See {@link #findInsertionPoint} for where the pair actually lands — {@code
     *  insertBeforeJobName} may be null/blank to just append at the end. */
    public static String appendJobToEslFile(
            File esl, String name, double ra, double dec, double pa,
            String sequencePath, int repeats, String insertBeforeJobName ) throws IOException {
        try {
            DocumentBuilder b = DocumentBuilderFactory.newInstance().newDocumentBuilder();
            Document doc;
            Element root;
            String profile;

            if( esl.exists() ) {
                doc = b.parse( esl );
                root = doc.getDocumentElement();
                profile = text( root, "Profile" );
            }
            else {
                doc = b.newDocument();
                root = doc.createElement( "SchedulerList" );
                root.setAttribute( "version", "2.2" );
                root.appendChild( textElement( doc, "Profile", "" ) );
                doc.appendChild( root );
                profile = "";
            }

            Element leadJob = buildLeadJobElement( doc, name, ra, dec, pa, sequencePath, repeats );
            Element followerJob = buildFollowerJobElement( doc, pa, sequencePath, repeats );

            Node insertBefore = findInsertionPoint( root, insertBeforeJobName );
            if( insertBefore != null ) {
                root.insertBefore( leadJob, insertBefore );
                root.insertBefore( followerJob, insertBefore );
            }
            else {
                root.appendChild( leadJob );
                root.appendChild( followerJob );
            }

            writeDocument( doc, esl );
            return profile;
        }
        catch( IOException e ) {
            throw e;
        }
        catch( Exception e ) {
            throw new IOException( "Failed to append job to esl file", e );
        }
    }

    /** Writes a standalone lead(Primary)+follower(Secondary) pair as its own schedule file — used
     *  only as the source for Scheduler.appendEkosScheduleList(fileURL), which appends a whole
     *  schedule document's jobs to a currently-running Scheduler's live queue (there's no
     *  per-field "add one job" D-Bus call — confirmed against org.kde.kstars.Ekos.Scheduler.xml's
     *  full method list: job creation is exclusively file-based). That call always appends at the
     *  END of the live queue regardless of where the pair landed on disk (append has no position
     *  argument) — a real, unavoidable gap between the two; document order mostly only matters for
     *  the lead/follower pairing itself, not for a running scheduler's evaluation order, so this is
     *  an accepted limitation rather than something worked around here. Callers should write this
     *  to a throwaway temp file. */
    public static void writeSingleJobEslFile(
            File target, String profile, String name, double ra, double dec, double pa,
            String sequencePath, int repeats ) throws IOException {
        try {
            DocumentBuilder b = DocumentBuilderFactory.newInstance().newDocumentBuilder();
            Document doc = b.newDocument();
            Element root = doc.createElement( "SchedulerList" );
            root.setAttribute( "version", "2.2" );
            root.appendChild( textElement( doc, "Profile", profile == null ? "" : profile ) );
            root.appendChild( buildLeadJobElement( doc, name, ra, dec, pa, sequencePath, repeats ) );
            root.appendChild( buildFollowerJobElement( doc, pa, sequencePath, repeats ) );
            doc.appendChild( root );

            writeDocument( doc, target );
        }
        catch( IOException e ) {
            throw e;
        }
        catch( Exception e ) {
            throw new IOException( "Failed to write single-job esl file", e );
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
