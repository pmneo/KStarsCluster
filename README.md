# KStarsCluster

Requires Java 11

Usage:

run `KStarsCluster/server.sh` on the Master KStars Instance which is Guding and Dithering

run `KStarsCluster/client.sh -h {ip of master}` on the Client KStars Instance. 

Prepare your Capture Sequence on Master and Client(s). 

When the Master is Guiding the Client is Autofocusing (could be disabled via "`-a false`") and then Starting the loaded Capture Sequence. 
When a mount move or dithering is in progress, the current Capture will be aborted and restarted after guding is running again

You may use the Scheduler on the Master but NOT on the client.

The Client should have the Mount Simulator configured. With this the target position will be synced in the client to have a valid WCS in the fits header.
