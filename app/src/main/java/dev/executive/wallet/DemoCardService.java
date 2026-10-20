package dev.executive.wallet;

import android.nfc.cardemulation.HostApduService;
import android.os.Bundle;
import android.content.SharedPreferences;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Proprietary test AID only. No EMV payment AIDs, no card number,
 * no payment cryptograms and no attempt to impersonate a Mastercard.
 */
public final class DemoCardService extends HostApduService {
  public static final String AID_HEX = "F00102424D57";
  public static final byte[] AID = new byte[] {(byte)0xF0,0x01,0x02,0x42,0x4D,0x57};
  private static final byte[] SW_OK = {(byte)0x90,0x00};
  private static final byte[] SW_WRONG = {(byte)0x6D,0x00};
  private static final byte[] PAYLOAD = "BMW-HCE-DEMO".getBytes(StandardCharsets.US_ASCII);

  public static byte[] respond(byte[] command) {
    if (command == null || command.length < 11) return SW_WRONG;
    // SELECT by application ID; exact AID and Lc match required
    if ((command[0] & 0xff) != 0x00 || (command[1] & 0xff) != 0xA4
        || (command[2] & 0xff) != 0x04 || (command[3] & 0xff) != 0x00
        || (command[4] & 0xff) != AID.length) return SW_WRONG;
    byte[] got = Arrays.copyOfRange(command, 5, 5 + AID.length);
    if (!Arrays.equals(got, AID)) return SW_WRONG;
    byte[] response = Arrays.copyOf(PAYLOAD, PAYLOAD.length + SW_OK.length);
    System.arraycopy(SW_OK, 0, response, PAYLOAD.length, SW_OK.length);
    return response;
  }

  @Override public byte[] processCommandApdu(byte[] command, Bundle extras) {
    byte[] response = respond(command);
    if (response.length >= PAYLOAD.length + 2 && response[0] == PAYLOAD[0]) {
      SharedPreferences prefs = getSharedPreferences("demo_hce", MODE_PRIVATE);
      int count = prefs.getInt("tap_count",0);
      prefs.edit().putLong("last_tap",System.currentTimeMillis())
        .putInt("tap_count",count+1).apply();
    }
    return response;
  }

  @Override public void onDeactivated(int reason) { }
}