package com.stark.bitcoinlottery;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.util.Arrays;

public final class Bitcoin {
 static final BigInteger MAX = BigInteger.ONE.shiftLeft(256).subtract(BigInteger.ONE);
 static final BigInteger DIFF1 = new BigInteger("00000000ffff0000000000000000000000000000000000000000000000000000",16);
 static byte[] hex(String s) {
  if ((s.length() & 1)!=0) throw new IllegalArgumentException("hex length");
  byte[] b=new byte[s.length()/2];
  for(int i=0;i<b.length;i++){int a=Character.digit(s.charAt(i*2),16),c=Character.digit(s.charAt(i*2+1),16);if(a<0||c<0)throw new IllegalArgumentException("hex");b[i]=(byte)(a*16+c);}
  return b;
 }
 static String hex(byte[] b) {char[] d="0123456789abcdef".toCharArray(),out=new char[b.length*2];for(int i=0;i<b.length;i++){out[i*2]=d[(b[i]&255)>>>4];out[i*2+1]=d[b[i]&15];}return new String(out);}
 static byte[] reverse(byte[] b){byte[] a=b.clone();for(int i=0;i<a.length/2;i++){byte t=a[i];a[i]=a[a.length-1-i];a[a.length-1-i]=t;}return a;}
 static byte[] words(byte[] b){if(b.length%4!=0)throw new IllegalArgumentException();byte[] a=b.clone();for(int i=0;i<a.length;i+=4){a[i]=b[i+3];a[i+1]=b[i+2];a[i+2]=b[i+1];a[i+3]=b[i];}return a;}
 static byte[] cat(byte[] a,byte[] b){byte[] c=Arrays.copyOf(a,a.length+b.length);System.arraycopy(b,0,c,a.length,b.length);return c;}
 static byte[] sha2(MessageDigest md,byte[] b){return md.digest(md.digest(b));}
 // Reuse two 32-byte buffers for the hashing hot path.
 static void sha2Into(MessageDigest md,byte[] header,byte[] scratch,byte[] out)throws java.security.DigestException{
  md.update(header);md.digest(scratch,0,32);
  md.update(scratch);md.digest(out,0,32);
 }
 static BigInteger target(String bits){long x=Long.parseLong(bits,16);int exp=(int)(x>>>24);long mant=x&0x7fffff;if((x&0x800000)!=0||mant==0||exp>34)throw new IllegalArgumentException("nBits");BigInteger t=BigInteger.valueOf(mant).shiftLeft(8*(exp-3));if(t.signum()<=0||t.compareTo(MAX)>0)throw new IllegalArgumentException("target");return t;}
 static void put32(byte[] b,int at,long v){for(int i=0;i<4;i++)b[at+i]=(byte)(v>>>(8*i));}
 static byte[] header(String version,String prev,byte[] merkle,String time,String bits){
  if(prev.length()!=64||merkle.length!=32||version.length()!=8||time.length()!=8||bits.length()!=8)throw new IllegalArgumentException("header fields");
  return cat(cat(cat(reverse(hex(version)),words(hex(prev))),merkle),cat(cat(reverse(hex(time)),reverse(hex(bits))),new byte[4]));
 }
 static int cmpHash(byte[] raw,byte[] targetBE){for(int i=0;i<32;i++){int a=raw[31-i]&255,b=targetBE[i]&255;if(a!=b)return Integer.compare(a,b);}return 0;}
 static byte[] targetBytes(BigInteger n){byte[] a=n.toByteArray(),b=new byte[32];int count=Math.min(32,a.length);System.arraycopy(a,a.length-count,b,32-count,count);return b;}
}
