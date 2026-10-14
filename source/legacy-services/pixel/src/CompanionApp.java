package org.retrobridge.galaxyservices.pixel;
import android.app.Application;import androidx.appcompat.app.AppCompatDelegate;import com.google.android.material.color.DynamicColors;
public final class CompanionApp extends Application {
 public void onCreate(){super.onCreate();AppCompatDelegate.setDefaultNightMode(getSharedPreferences("appearance",0).getInt("night",AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM));DynamicColors.applyToActivitiesIfAvailable(this);}
}
