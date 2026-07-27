package de.pmneo.kstars.utils;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.imageio.ImageIO;

import nom.tam.fits.BasicHDU;
import nom.tam.fits.Fits;
import nom.tam.fits.ImageHDU;
import nom.tam.fits.header.Standard;

/**
 * Renders a quick stretched JPEG preview of a FITS light frame — for the web UI's image strip,
 * not for astrometric/photometric use. The shadows/midtones/highlights model and the auto-stretch
 * formula (median + k*MAD for the shadows clip, then solving midtones via the MTF itself) mirror
 * PixInsight's ScreenTransferFunction / AutoSTF exactly.
 */
public class FitsThumbnail {

    private interface ValueAt {
        double get( int y, int x );
    }

    private static class Image {
        final ValueAt valueAt;
        final int width;
        final int height;
        final double normMin;
        final double normMax;

        Image( ValueAt valueAt, int width, int height, double normMin, double normMax ) {
            this.valueAt = valueAt;
            this.width = width;
            this.height = height;
            this.normMin = normMin;
            this.normMax = normMax;
        }

        double normalized( int y, int x ) {
            return clamp( (valueAt.get( y, x ) - normMin) / (normMax - normMin), 0, 1 );
        }
    }

    private static Image load( File fitsFile ) throws Exception {
        try( Fits fits = new Fits( fitsFile ) ) {
            BasicHDU<?> hdu = fits.getHDU( 0 );
            if( !(hdu instanceof ImageHDU) ) {
                throw new IllegalArgumentException( "Not an image HDU: " + fitsFile );
            }

            Object kernel = ((ImageHDU) hdu).getKernel();
            final double bzero = hdu.getHeader().getDoubleValue( Standard.BZERO, 0.0 );
            final double bscale = hdu.getHeader().getDoubleValue( Standard.BSCALE, 1.0 );

            int height;
            int width;
            ValueAt valueAt;

            if( kernel instanceof short[][] ) {
                short[][] arr = (short[][]) kernel;
                height = arr.length; width = arr[0].length;
                valueAt = (y, x) -> arr[y][x] * bscale + bzero;
            }
            else if( kernel instanceof int[][] ) {
                int[][] arr = (int[][]) kernel;
                height = arr.length; width = arr[0].length;
                valueAt = (y, x) -> arr[y][x] * bscale + bzero;
            }
            else if( kernel instanceof float[][] ) {
                float[][] arr = (float[][]) kernel;
                height = arr.length; width = arr[0].length;
                valueAt = (y, x) -> arr[y][x] * bscale + bzero;
            }
            else if( kernel instanceof double[][] ) {
                double[][] arr = (double[][]) kernel;
                height = arr.length; width = arr[0].length;
                valueAt = (y, x) -> arr[y][x] * bscale + bzero;
            }
            else {
                throw new IllegalArgumentException( "Unsupported FITS pixel type: " + kernel.getClass() );
            }

            double[] range = normalizationRange( hdu.getBitpix().getHeaderValue(), valueAt, width, height );
            return new Image( valueAt, width, height, range[0], range[1] );
        }
    }

    /**
     * Integer FITS data is normalized against its bit depth (0 is truly empty, matching
     * PixInsight's convention for integer images) — floating point data has no fixed depth,
     * so its actual sampled min/max is used instead.
     */
    private static double[] normalizationRange( int bitpix, ValueAt valueAt, int width, int height ) {
        switch( bitpix ) {
            case 8:  return new double[]{ 0, 255 };
            case 16: return new double[]{ 0, 65535 };
            case 32: return new double[]{ 0, 4294967295.0 };
            default: {
                double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
                int strideX = Math.max( 1, width / 400 );
                int strideY = Math.max( 1, height / 400 );
                for( int y = 0; y < height; y += strideY ) {
                    for( int x = 0; x < width; x += strideX ) {
                        double v = valueAt.get( y, x );
                        if( v < min ) min = v;
                        if( v > max ) max = v;
                    }
                }
                return new double[]{ min, max };
            }
        }
    }

    private static List<Double> sampleNormalized( Image img ) {
        int strideX = Math.max( 1, img.width / 400 );
        int strideY = Math.max( 1, img.height / 400 );

        List<Double> samples = new ArrayList<>();
        for( int y = 0; y < img.height; y += strideY ) {
            for( int x = 0; x < img.width; x += strideX ) {
                samples.add( img.normalized( y, x ) );
            }
        }
        Collections.sort( samples );
        return samples;
    }

    /** shadows/midtones/highlights are all in [0,1] — exactly PixInsight's ScreenTransferFunction sliders. */
    public static byte[] render( File fitsFile, int maxDim, double shadows, double midtones, double highlights ) throws Exception {
        Image img = load( fitsFile );

        double s = clamp( shadows, 0, 1 );
        double h = Math.max( s + 1e-6, clamp( highlights, 0, 1 ) );
        double m = clamp( midtones, 0.00001, 0.99999 );

        double scale = Math.min( 1.0, (double) maxDim / Math.max( img.width, img.height ) );
        int outW = Math.max( 1, Math.round( (float) (img.width * scale) ) );
        int outH = Math.max( 1, Math.round( (float) (img.height * scale) ) );

        BufferedImage out = new BufferedImage( outW, outH, BufferedImage.TYPE_BYTE_GRAY );
        for( int oy = 0; oy < outH; oy++ ) {
            int sy = Math.min( img.height - 1, (int) (oy / scale) );
            for( int ox = 0; ox < outW; ox++ ) {
                int sx = Math.min( img.width - 1, (int) (ox / scale) );
                double t = clamp( (img.normalized( sy, sx ) - s) / (h - s), 0, 1 );
                int gray = (int) Math.round( 255 * mtf( m, t ) );
                out.setRGB( ox, oy, (gray << 16) | (gray << 8) | gray );
            }
        }

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write( out, "jpg", bytes );
        return bytes.toByteArray();
    }

    /**
     * PixInsight's AutoSTF formula: shadows clip at median + k*MAD (k typically -2.8), highlights
     * fixed at 1, and midtones solved by literally evaluating MTF(targetBackground, median-shadows)
     * — not an inverse, just how the formula works out. "Strong" only pushes targetBackground
     * higher (brighter target background -> more midtone lift); there's no single canonical
     * "strong" constant, this is a reasonable, tunable heuristic.
     */
    public static double[] computeAutoStretch( File fitsFile, boolean strong ) throws Exception {
        Image img = load( fitsFile );
        List<Double> samples = sampleNormalized( img );

        double median = samples.get( samples.size() / 2 );

        List<Double> deviations = new ArrayList<>( samples.size() );
        for( double v : samples ) {
            deviations.add( Math.abs( v - median ) );
        }
        Collections.sort( deviations );
        double mad = deviations.get( deviations.size() / 2 ) * 1.4826;

        double shadowsClipping = -2.8;
        double targetBackground = strong ? 0.5 : 0.25;

        double shadows = clamp( median + shadowsClipping * mad, 0, 1 );
        double midtones = mtf( targetBackground, median - shadows );

        return new double[]{ shadows, midtones, 1.0 };
    }

    private static double clamp( double v, double lo, double hi ) {
        return v < lo ? lo : v > hi ? hi : v;
    }

    /** Midtones transfer function — m=0.5 is the identity (pure linear); m<0.5 lifts, m>0.5 compresses. */
    private static double mtf( double m, double x ) {
        if( x <= 0 ) {
            return 0;
        }
        if( x >= 1 ) {
            return 1;
        }
        return ((m - 1) * x) / (((2 * m - 1) * x) - m);
    }
}
