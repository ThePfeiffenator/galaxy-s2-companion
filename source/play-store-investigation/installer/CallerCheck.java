import android.content.Context;
import android.net.Uri;
/** Read-only probe: a shell/root process must not acquire the Store's privileges. */
public final class CallerCheck {
 public static void main(String[] args) throws Exception {
  android.os.Looper.prepareMainLooper();
  Class<?> at=Class.forName("android.app.ActivityThread");
  Object thread=at.getMethod("systemMain").invoke(null);
  Context c=(Context)at.getMethod("getSystemContext").invoke(thread);
  try {
   c.getContentResolver().call(Uri.parse("content://org.retrobridge.storeinstaller"),"status",null,null);
   System.out.println("FAIL_UNAUTHORIZED_CALL_ACCEPTED");System.exit(1);
  } catch (SecurityException expected) {
   System.out.println("PASS_UNAUTHORIZED_CALL_DENIED");System.exit(0);
  }
 }
}
