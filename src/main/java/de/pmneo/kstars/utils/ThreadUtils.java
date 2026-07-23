package de.pmneo.kstars.utils;

import de.pmneo.kstars.SimpleLogger;

import java.io.FileOutputStream;
import java.lang.management.*;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;

public class ThreadUtils {
    public static String saveThreadDump() {
        try {
            final ThreadMXBean mx = ManagementFactory.getThreadMXBean();
            final ThreadInfo[] infos = mx.dumpAllThreads( mx.isObjectMonitorUsageSupported(), mx.isSynchronizerUsageSupported() );

            final StringBuilder dump = new StringBuilder();
            dump.append( "Full thread dump taken " ).append( new Date() ).append( " by DBUS WATCHDOG\n\n" );

            try {
                final long[] deadlocked = mx.findDeadlockedThreads();
                if( deadlocked != null && deadlocked.length > 0 ) {
                    dump.append( "!!! DEADLOCK detected, involved thread ids: " ).append( Arrays.toString( deadlocked ) ).append( "\n\n" );
                }
            }
            catch( Throwable t ) {
                dump.append( "(deadlock detection failed: " ).append( t ).append( ")\n\n" );
            }

            for( ThreadInfo info : infos ) {
                if( info == null ) {
                    continue;
                }

                dump.append( '"' ).append( info.getThreadName() ).append( "\" #" ).append( info.getThreadId() )
                        .append( info.isDaemon() ? " daemon" : "" )
                        .append( " prio=" ).append( info.getPriority() )
                        .append( " state=" ).append( info.getThreadState() );

                if( info.getLockName() != null ) {
                    dump.append( " on " ).append( info.getLockName() );
                    if( info.getLockOwnerName() != null ) {
                        dump.append( " owned by \"" ).append( info.getLockOwnerName() ).append( "\" #" ).append( info.getLockOwnerId() );
                    }
                }
                dump.append( '\n' );

                final StackTraceElement[] stack = info.getStackTrace();
                final MonitorInfo[] monitors = info.getLockedMonitors();

                for( int depth = 0; depth < stack.length; depth++ ) {
                    dump.append( "\tat " ).append( stack[ depth ] ).append( '\n' );

                    if( depth == 0 && info.getLockInfo() != null ) {
                        switch( info.getThreadState() ) {
                            case BLOCKED:
                                dump.append( "\t-  blocked on " ).append( info.getLockInfo() ).append( '\n' );
                                break;
                            case WAITING:
                            case TIMED_WAITING:
                                dump.append( "\t-  waiting on " ).append( info.getLockInfo() ).append( '\n' );
                                break;
                            default:
                                break;
                        }
                    }

                    for( MonitorInfo mi : monitors ) {
                        if( mi.getLockedStackDepth() == depth ) {
                            dump.append( "\t-  locked " ).append( mi ).append( '\n' );
                        }
                    }
                }

                final LockInfo[] synchronizers = info.getLockedSynchronizers();
                if( synchronizers.length > 0 ) {
                    dump.append( "\n\tLocked ownable synchronizers:\n" );
                    for( LockInfo li : synchronizers ) {
                        dump.append( "\t-  " ).append( li ).append( '\n' );
                    }
                }

                dump.append( '\n' );
            }

            final String fileName = "./KStarsThreadDump_" + new SimpleDateFormat( "yyyy-MM-dd_HHmmss" ).format( new Date() ) + ".txt";
            try( FileOutputStream out = new FileOutputStream( fileName ) ) {
                out.write( dump.toString().getBytes( Charset.forName( "UTF-8" ) ) );
            }
            return fileName;
        }
        catch( Throwable t ) {
            SimpleLogger.getLogger().logError( "Failed to save thread dump", t );
            return "(failed to save)";
        }
    }
}
