package org.retrobridge.galaxyservices.pixel;
/** Content readiness is independent of slow images, scripts and page-finished events. */
final class RedditLoadPolicy {
 static final long PAGE_TIMEOUT_MS=15000,START_SPACING_MS=2000,QUEUE_TIMEOUT_MS=4000;
 static boolean ready(boolean comments,int posts,int replies,int stable,boolean documentReady,boolean empty,long elapsed){
  if(!documentReady)return false;
  if(empty&&posts==0)return true;
  if(posts==0)return false;
  if(comments)return replies>0&&stable>=1||empty||elapsed>=5000&&stable>=2;
  return posts>=20||stable>=1;
 }
}
