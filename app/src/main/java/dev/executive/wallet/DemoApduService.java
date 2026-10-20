package dev.executive.wallet;

import android.nfc.cardemulation.HostApduService;
import android.os.Bundle;
import java.util.Arrays;

/**
 * Proprietary, test-only HCE reader protocol.
 * Never emulates Mastercard/EMV or stores/presents a card PAN or token.
 * Test AID: F0112233445566, selected by a dedicated test NFC reader.
 */
public final class DemoApduService extends HostApduService {
  private static final byte[] TEST_AID = hex("F0112233445566");
  private static final byte[] TEST_FCI = hex("6F0B8407F0112233445566A5009000");
  private static final byte[] NOT_FOUND = hex("6A82");

  @Override public byte[] processCommandApdu(byte[] command, Bundle extras) {
    if (command == null || command.length < 12) return NOT_FOUND;
    boolean selectAid=(command[0]==0x00 && (command[1]&0xff)==0xA4 &&
      (command[2]&0xff)==0x04 && (command[4]&0xff)==TEST_AID.length);
    if (!selectAid) return NOT_FOUND;
    byte[] aid=Arrays.copyOfRange(command,5,12);
    if(!Arrays.equals(aid, TEST_AID)) return NOT_FOUND;
    android.content.SharedPreferences p=getSharedPreferences("hce_demo",MODE_PRIVATE);
    p.edit().putInt("testTapCount",p.getInt("testTapCount",0)+1)
      .putLong("lastTestTap",System.currentTimeMillis()).apply();
    return TEST_FCI;
  }

  @Override public void onDeactivated(int reason) { /* No state or funds are held. */ }

  private static byte[] hex(String s) {
    byte[] b=new byte[s.length()/2];
    for(int i=0;i<b.length;i++) b[i]=(byte)Integer.parseInt(s.substring(2*i,2*i+2),16);
    return b;
  }
}
