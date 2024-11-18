package de.pmneo.kstars.utils;

import java.io.File;
import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

import javax.swing.JFrame;
import javax.swing.JTabbedPane;

import org.apache.commons.math3.fitting.PolynomialCurveFitter;
import org.apache.commons.math3.fitting.WeightedObservedPoints;
import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics;

import com.panayotis.gnuplot.plot.DataSetPlot;
import com.panayotis.gnuplot.plot.FunctionPlot;
import com.panayotis.gnuplot.style.PlotStyle;
import com.panayotis.gnuplot.style.Style;
import com.panayotis.gnuplot.swing.JPlot;
import com.panayotis.gnuplot.terminal.ExpandableTerminal;

public class FocusAnalyser {
    
    public static void main(String[] args) throws IOException {

        FocusAnalyser fa = new FocusAnalyser();

        System.out.println( fa.aproximatePos( "Ha", 25.0 ) );

        System.out.println( fa.aproximatePos( "Ha", 5.0 ) );
		
        drawTempChart(fa);
        drawTimeChart( fa );
    }


    public int aproximatePos(String filter, double temperature ) {
        return (int) analysis.get( filter ).polyReg.predict( temperature );
    }


    private static void drawTempChart(FocusAnalyser fa) {
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxX = Double.MIN_VALUE;
        double maxY = Double.MIN_VALUE;

        for( FocusAnalysis a : fa.analysis.values() ) {
            for( FocusLog log : a.logs ) {
                minX = Math.min( minX, log.temperature );
                minY = Math.min( minY, log.position );

                maxX = Math.max( maxX, log.temperature );
                maxY = Math.max( maxY, log.position );
            }
        }

        minX -= 5;
        maxX += 5;

        minY -= 100;
        maxY += 100;

        JTabbedPane tab = new JTabbedPane();

        for( FocusAnalysis a : fa.analysis.values() ) {
    
            JPlot plot = new JPlot();
            ExpandableTerminal terminal = (ExpandableTerminal) plot.getJavaPlot().getTerminal();
            terminal.set("size","1600,800");

            double[][] data = new double[ a.logs.size() ][];

            int i=0; 
            // Hinzufügen der (x, y)-Punkte
            for (FocusLog log : a.logs ) {
                data[i++] = new double[] { log.temperature, log.position, log.weight };
            }

            FunctionPlot fPlot = new FunctionPlot( a.polyReg.getTerm() );
            fPlot.setTitle( "fn" );
            plot.getJavaPlot().addPlot( fPlot );
            
            DataSetPlot s = new DataSetPlot( data );

            //'world.cor' using 1:2:(5.*rand(0)) with points lt 1 pt 6 ps variable
            s.set( "using", "1:2:3");
            
            s.setPlotStyle( new PlotStyle( Style.POINTS ) );
            s.getPlotStyle().set( "pt 7 ps var" );

            //  u 1:2:3 w points lt 1 pt 10 ps variable
            s.setTitle( a.filter );   
            //s.set( "yr", "[18000,19000]");
            
            plot.getJavaPlot().addPlot( s );

            plot.getJavaPlot().set( "xrange", "[" + minX + ":" + maxX + "]" );
            plot.getJavaPlot().set( "yrange", "[" + minY + ":" + maxY + "]" );

            plot.plot( );
            
            tab.addTab( a.filter, plot );
        }

        JFrame f = new JFrame();
        f.getContentPane().add(tab);
        f.pack();
        f.setLocationRelativeTo(null);
        f.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        f.setVisible(true);
    }

    private static void drawTimeChart(FocusAnalyser fa) {
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double minY2 = Double.MAX_VALUE;

        double maxX = Double.MIN_VALUE;
        double maxY = Double.MIN_VALUE;
        double maxY2 = Double.MIN_VALUE;

        for( FocusAnalysis a : fa.analysis.values() ) {
            for( FocusLog log : a.logs ) {
                minX = Math.min( minX, log.dateTime.getTime() );
                minY = Math.min( minY, log.position );
                minY2 = Math.min( minY2, log.temperature );

                maxX = Math.max( maxX, log.dateTime.getTime() );
                maxY = Math.max( maxY, log.position );
                maxY2 = Math.max( maxY2, log.temperature );
            }
        }


        JTabbedPane tab = new JTabbedPane();

        for( FocusAnalysis a : fa.analysis.values() ) {
    
            JPlot plot = new JPlot();
            ExpandableTerminal terminal = (ExpandableTerminal) plot.getJavaPlot().getTerminal();
            terminal.set("size","1600,800");

            double[][] data = new double[ a.logs.size() ][];
            double[][] data2 = new double[ a.logs.size() ][];


            int i=0; 
            // Hinzufügen der (x, y)-Punkte
            for (FocusLog log : a.logs ) {
                data[i] = new double[] { log.dateTime.getTime(), log.position, log.weight };
                data2[i] = new double[] { log.dateTime.getTime(), log.temperature, log.weight };

                i++;
            }
            
            DataSetPlot s = new DataSetPlot( data );
            s.set( "using", "1:2:3");            
            s.setPlotStyle( new PlotStyle( Style.POINTS ) );
            s.getPlotStyle().set( "pt 7 ps var" );
            s.setTitle( a.filter );   
            plot.getJavaPlot().addPlot( s );

            DataSetPlot s2 = new DataSetPlot( data2 );
            //s2.set( "using", "1:2:3");          
            s2.set( "axes", "x1y2" );  
            //s2.setPlotStyle( new PlotStyle( Style.POINTS ) );
            //s2.getPlotStyle().set( "pt 7 ps var" );
            s2.setTitle( a.filter + " temp");   
            plot.getJavaPlot().addPlot( s2 );
            

            plot.getJavaPlot().set( "xrange", "[" + minX + ":" + maxX + "]" );
            plot.getJavaPlot().set( "yrange", "[" + minY + ":" + maxY + "]" );
            plot.getJavaPlot().set( "ytics", "50 nomirror tc lt 2" );
            plot.getJavaPlot().set( "y2range", "[" + minY2 + ":" + maxY2 + "]" );
            plot.getJavaPlot().set( "y2label", "'temperature' tc lt 2");
            plot.getJavaPlot().set( "y2tics", "1 nomirror tc lt 2" );

            plot.plot( );
            
            tab.addTab( a.filter, plot );
        }

        JFrame f = new JFrame();
        f.getContentPane().add(tab);
        f.pack();
        f.setLocationRelativeTo(null);
        f.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        f.setVisible(true);
    }


    /*
date, time, position, temperature, filter, HFR, altitude
2024-09-06, 00:50:20, 18700, 21.0, Ha, 0.757, 31.9
2024-09-06, 00:58:18, 18677, 19.5, L, 0.827, 33.2
2024-09-06, 01:04:34, 18703, 18.8, SII, 0.810, 34.2
2024-09-06, 01:26:12, 18642, 18.3, OIII, 0.872, 37.6
2024-09-06, 02:02:03, 18681, 18.0, Ha, 0.879, 43.5

     */

    public static SimpleDateFormat sdf = new SimpleDateFormat( "yyyy-MM-dd HH:mm:ss" );
    public static class FocusLog {
        public Date dateTime;
        public int position;
        public double temperature;
        public String filter;
        public double hfr;
        public double altitude;
        public double weight = 1;
        
        FocusLog( String line ) throws ParseException {
            String[] parts = line.split( ",\\s*" );

            dateTime = sdf.parse( parts[0] + " " + parts[1] );
            position = Integer.parseInt( parts[2] );
            temperature = Double.parseDouble( parts[3] );
            filter = parts[4];
            hfr = Double.parseDouble( parts[5] );
            altitude = Double.parseDouble( parts[6] );
        }

        @Override
        public String toString() {
            return dateTime + " " + temperature + "°C (" + filter + "): " + hfr + " @ " + position;
        }

        public static Comparator<FocusLog> dateComp = (l,r) -> l.dateTime.compareTo( r.dateTime );
        public static Comparator<FocusLog> tempComp = (l,r) -> Double.compare( l.temperature, r.temperature );
        public static Comparator<FocusLog> hfrComp = (l,r) -> Double.compare( l.hfr, r.hfr );
        public static Comparator<FocusLog> posComp = (l,r) -> Integer.compare( l.position, r.position );
        public static Comparator<FocusLog> filterComp = (l,r) -> l.filter.compareTo( r.filter );
       
    }

    public static class FocusAnalysis {
        private String filter;
        private List< FocusLog > logs = new ArrayList<>();

        PolynomialRegression polyReg;

        public FocusAnalysis( String filter ) {
            this.filter = filter;
        }

        public boolean analyse() {

            this.logs.sort( FocusLog.tempComp.reversed().thenComparing( FocusLog.posComp ) );


           // Get a DescriptiveStatistics instance
            DescriptiveStatistics stats = new DescriptiveStatistics();

         
            for( FocusLog log : logs ) {
                stats.addValue( log.hfr );
            }
            

            // Compute some statistics
            double std = stats.getStandardDeviation();
            double median = stats.getPercentile(50);
            
            //System.out.println( filter + ": " + mean + " / " + std + " / " + median +  "/" + stats.getVariance() );

            //System.out.println( filter + " hfr = " + minHfr + " < " + avgHfr + " > " + maxHfr );

            WeightedObservedPoints obs = new WeightedObservedPoints();

            // Hinzufügen der (x, y)-Punkte
            for( FocusLog log : logs ) {
                double weight = 1;

                double zScore = (log.hfr - median) / std;
                
                weight = Math.max( 0.000001, 1 - Math.abs( zScore ) * 0.5 );
                
                log.weight = weight;

                obs.add( log.weight, log.temperature, log.position );
            }


            polyReg = new PolynomialRegression(1);
            try {
                polyReg.fit( obs );
            }
            catch( Throwable t ) {
                System.err.println( "Can't build polynom for filter " + filter );
                return false;
            }

            return true;
            
        }
    }

    private Map<String, FocusAnalysis> analysis = new HashMap<>();

    public FocusAnalyser() throws IOException {
        File dir = new File( System.getProperty("user.home"), ".local/share/kstars/focuslogs" );

        Calendar c = Calendar.getInstance();

        c.add( Calendar.DATE, -40 );
        
        for( File log : dir.listFiles() ) {
            if( log.isFile() && log.getName().startsWith( "autofocus" ) ) {
                try( Scanner r = new Scanner( log ) ) {
                    List< FocusLog > logs = new ArrayList<>();
                    while( r.hasNextLine() ) {
                        String line = r.nextLine();

                        if( line.startsWith( "date" ) ) {
                            continue;
                        }

                        try {
                            FocusLog l = new FocusLog( line );

                            if( l.hfr > 1.5 ) {
                                logs.clear();
                                //System.out.println( "Skipping " + log + " because of invalid hfr: " + l.hfr );
                                break;
                            }

                            if( l.dateTime.getTime() >= c.getTimeInMillis() ) {
                                logs.add( l );
                            }
                        }
                        catch( Throwable t ) {
                            t.printStackTrace();
                        }
                    }

                    for( FocusLog l : logs ) {
                        FocusAnalysis a = analysis.get( l.filter );

                        if( a == null ) {
                            analysis.put( l.filter, a = new FocusAnalysis( l.filter ) );
                        }

                        a.logs.add( l );
                    }
                }
            }
        }
        

        for( Iterator<FocusAnalysis> a = analysis.values().iterator(); a.hasNext(); ) {
            if( a.next().analyse() == false ) {
                a.remove();
            }
        }

        
    }


    public static class PolynomialRegression {
        private int degree; // Grad des Polynoms
        private double[] coefficients; // Koeffizienten des Polynoms

        public PolynomialRegression(int degree) {
            this.degree = degree;
        }

        // Methode zur Berechnung der Näherung
        public void fit(double[][] data) {
            // Erstellen eines Objekts zur Speicherung der Punkte
            WeightedObservedPoints obs = new WeightedObservedPoints();

            // Hinzufügen der (x, y)-Punkte
            for (double[] point : data) {
                obs.add(point[0], point[1]);
            }

            fit( obs );
        }

        public void fit( WeightedObservedPoints obs ) {
            // Erstellen eines PolynomialCurveFitters vom gewünschten Grad
            PolynomialCurveFitter fitter = PolynomialCurveFitter.create(degree);

            // Berechnung der Koeffizienten des Polynoms
            coefficients = fitter.fit(obs.toList());
        }

        // Methode zur Berechnung der Näherung an einem bestimmten Punkt
        public double predict(double x) {
            double result = 0.0;
            for (int i = 0; i < coefficients.length; i++) {
                result += coefficients[i] * Math.pow(x, i);
            }
            return result;
        }

        // Ausgabe der Polynom-Koeffizienten
        public void printCoefficients() {
            System.out.print("Polynomkoeffizienten: ");
            for (int i = 0; i < coefficients.length; i++) {
                System.out.printf("%.5f ", coefficients[i]);
            }
            System.out.println();
        }

        // Ausgabe der Polynom-Koeffizienten
        public String getTerm() {

            String term = "";
            for (int i = 0; i < coefficients.length; i++) {
                if( i == 0 ) {
                    term += coefficients[i];
                }
                else {
                    term += " + ( x**" + i + "*(" + coefficients[i]+") )";
                }
            }
            return term;
        }

    }

}
