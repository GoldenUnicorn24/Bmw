package com.stark.bitcoinlottery;
import org.junit.Test;
import static org.junit.Assert.*;
import java.security.MessageDigest;
public class BitcoinTest {
 @Test public void genesis()throws Exception{
  byte[] h=Bitcoin.header("00000001","0".repeat(64),Bitcoin.hex("3ba3edfd7a7b12b27ac72c3e67768f617fc81bc3888a51323a9fb8aa4b1e5e4a"),"495fab29","1d00ffff");
  Bitcoin.put32(h,76,2083236893L);
  byte[] digest=Bitcoin.sha2(MessageDigest.getInstance("SHA-256"),h);
  assertEquals("000000000019d6689c085ae165831e934ff763ae46a2a6c172b3f1b60a8ce26f",Bitcoin.hex(Bitcoin.reverse(digest)));
  assertTrue(Bitcoin.cmpHash(digest,Bitcoin.targetBytes(Bitcoin.target("1d00ffff")))<=0);
 }
 @Test public void reusableHashBuffers()throws Exception{
  MessageDigest md=MessageDigest.getInstance("SHA-256");
  byte[] header=new byte[80],scratch=new byte[32],out=new byte[32];
  for(int i=0;i<128;i++){Bitcoin.put32(header,76,i);byte[] expected=Bitcoin.sha2(md,header);Bitcoin.sha2Into(md,header,scratch,out);assertArrayEquals(expected,out);}
 }
 @Test public void wordOrder(){assertEquals("0403020108070605",Bitcoin.hex(Bitcoin.words(Bitcoin.hex("0102030405060708"))));}
 @Test public void target(){assertEquals(Bitcoin.DIFF1,Bitcoin.target("1d00ffff"));}
}
