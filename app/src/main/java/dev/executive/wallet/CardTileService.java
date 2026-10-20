package dev.executive.wallet;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.TileService;

/** Optional Quick Settings shortcut to Executive Black presentation.
 * Payment handling remains exclusively with the user's default wallet.
 */
public final class CardTileService extends TileService {
  @Override public void onClick() {
    super.onClick();
    Runnable launch = () -> {
      Intent intent = new Intent(this,MainActivity.class)
          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP)
          .putExtra("open_card",true);
      if(Build.VERSION.SDK_INT>=34){
        PendingIntent pi=PendingIntent.getActivity(this,101,intent,
            PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        startActivityAndCollapse(pi);
      }else{
        startActivityAndCollapse(intent);
      }
    };
    if(isLocked())unlockAndRun(launch);
    else launch.run();
  }
}
