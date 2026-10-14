package org.retrobridge.galaxyservices;

import java.net.Socket;
import java.util.*;
import java.util.concurrent.*;

/** Keep short image/feed bursts queued instead of dropping their connections. */
public final class SocketWorkers {
 private final ThreadPoolExecutor executor = new ThreadPoolExecutor(
     16, 16, 60, TimeUnit.SECONDS, new ArrayBlockingQueue<Runnable>(64));
 private final Set<Socket> sockets = new HashSet<Socket>();
 public SocketWorkers() { executor.allowCoreThreadTimeOut(true); }
 public synchronized void execute(final Socket socket, final Runnable work) {
  sockets.add(socket);
  try {
   executor.execute(new Runnable() { public void run() {
    try { work.run(); }
    finally { Wire.close(socket); synchronized(SocketWorkers.this) { sockets.remove(socket); } }
   }});
  } catch (RejectedExecutionException e) { sockets.remove(socket); throw e; }
 }
 public synchronized void shutdownNow() {
  executor.shutdownNow();
  for (Socket socket : sockets) Wire.close(socket);
  sockets.clear();
 }
}
