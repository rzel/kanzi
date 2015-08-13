# Performance of Snappy and LZ4 Codecs #

## Snappy Codec ##

### Introduction ###

The snappy codec in Kanzi is a java implementation of the Snappy algorithm (https://code.google.com/p/snappy/). Unlike most other implementations, it is pure java, based on the GO implementation (https://code.google.com/p/snappy-go). It does not use sun.misc.Unsafe or any other 'hack'.

The code is compact (just one class: https://code.google.com/p/kanzi/source/browse/java/src/kanzi/function/SnappyCodec.java), fast and does not require external (native) libraries to be available or complex installation.

The Snappy codec can be used (optionally) as a transform by the block compressor.

So, how does it compare speed wise to the Xerial java implementation (available here: http://xerial.org/snappy-java) JSnappy (here: https://code.google.com/p/jsnappy/) and iq80 Snappy (here: https://github.com/dain/snappy) ?

Does native code provide an advantage or is the cost not worth it ?

### Performance ###

Using Kanzi code as of October 2014, JDK 1.8.0\_25, Snappy codec and no entropy.

```
java -cp kanzi.jar kanzi.app.BlockCompressor -input=rt.jar -output=rt.knz -overwrite -block=8M -transform=snappy -entropy=none
Encoding ...

Encoding:          576 ms
Input size:        60008624
Output size:       28606248
Ratio:             0.47670227
Throughput (KB/s): 101739


java -cp kanzi.jar kanzi.app.BlockDecompressor -input=rt.knz -output=rt.jar -overwrite
Decoding ...

Decoding:          313 ms
Input size:        28606248
Output size:       60008624
Throughput (KB/s): 187227
```
### Test code ###

Here is the batch file used to run the comparison test:

TestSnappy.bat
```
@echo off

@set CORPUS=c:\temp\silesia
@echo Snappy encoding test against Silesia Corpus
@del %CORPUS%\*.snp 2> NUL
@del %CORPUS%\*.bak 2> NUL

@set sources=dickens mozilla mr nci ooffice osdb reymont samba sao webster x-ray xml

for %%f in (%sources%) do (
  @echo Processing %%f
  java -cp snappy-java-1.0.5.jar;JSnappy-0.9.1.jar;iq90.jar;kanzi.jar;. TestSnappy %CORPUS%\%%f
)
```

Here is the code using the 4 implementations. It covers compression only. The code is very similar for all 4 libraries.

TestSnappy.java
```
import java.io.*;
import org.xerial.snappy.*;
import de.jarnbjo.jsnappy.*;
import org.iq80.snappy.*;
import kanzi.io.*;


public class TestSnappy {
 public static void main(String[] args) {
   if (args.length != 1) {
          System.err.println("Input file command line argument required");
          System.exit(1);
   }

   try {
      String filename = args[0];
      compressXerial(filename);
      compressKanzi(filename);
      compressJSnappy(filename);
      compressIQ80Snappy(filename);
   }
   catch (Exception e) {
      e.printStackTrace();
   }
 }

  private static void compressXerial(String arg) throws java.io.IOException {
      File f = new File(arg);
      FileInputStream fis = new FileInputStream(f);
      int len = 0;
      byte[] buffer = new byte[32768];
      byte[] compressed = new byte[32768+8192];
      OutputStream out = new FileOutputStream(arg+".snp");
      long before = System.nanoTime();

      do {
        len = fis.read(buffer, 0, buffer.length);
        int size = org.xerial.snappy.Snappy.compress(buffer, 0, len, compressed, 0);

        while (size > 32768) {
           out.write(buffer, 0, 32768);
           size -= 32768;
        }

        out.write(buffer, 0, size);
      }
      while (len == buffer.length);

      fis.close();
      out.close();
      long after = System.nanoTime();
      System.out.println("Xerial\nElapsed [ms]: "+((after-before)/1000000L));
  }


  private static void compressKanzi(String arg) throws java.io.IOException {
      File f = new File(arg);
      FileInputStream fis = new FileInputStream(f);
      int len = 0;
      byte[] buffer = new byte[32768];
      OutputStream out = new FileOutputStream(arg+".snp");
      OutputStream os = new CompressedOutputStream("None", "Snappy", out);
      long before = System.nanoTime();

      do {
        len = fis.read(buffer, 0, buffer.length);
        os.write(buffer, 0, len);
      }
      while (len == buffer.length);

      fis.close();
      os.close();
      out.close();
      long after = System.nanoTime();
      System.out.println("Kanzi\nElapsed [ms]: "+((after-before)/1000000L));
  }


  private static void compressJSnappy(String arg) throws java.io.IOException {
      File f = new File(arg);
      FileInputStream fis = new FileInputStream(f);
      int len = 0;
      byte[] buffer = new byte[32768];
      OutputStream out = new FileOutputStream(arg+".snp");
      SnzOutputStream os = new SnzOutputStream(out, 32768);
      os.setCompressionEffort(1);
      long before = System.nanoTime();

      do {
        len = fis.read(buffer, 0, buffer.length);
        os.write(buffer, 0, len);
      }
      while (len == buffer.length);

      fis.close();
      os.close();
      out.close();
      long after = System.nanoTime();
      System.out.println("JSnappy\nElapsed [ms]: "+((after-before)/1000000L));
  }


  private static void compressIQ80Snappy(String arg) throws java.io.IOException {
      File f = new File(arg);
      FileInputStream fis = new FileInputStream(f);
      int len = 0;
      byte[] buffer = new byte[32768];
      OutputStream out = new FileOutputStream(arg+".snp");
      OutputStream os = new org.iq80.snappy.SnappyOutputStream(out);
      long before = System.nanoTime();

      do {
        len = fis.read(buffer, 0, buffer.length);
        os.write(buffer, 0, len);
      }
      while (len == buffer.length);

      fis.close();
      os.close();
      out.close();
      long after = System.nanoTime();
      System.out.println("iq80 Snappy\nElapsed [ms]: "+((after-before)/1000000L));
  }
}
```


## Results ##

Let us run the compression test against the Silesia Corpus.

The test was performed on a desktop i7-2600 @3.40GHz, Win7, 16GB RAM  with Oracle JDK8 (1.8.0\_31-b13), snappy-java-1.0.5-M2, JSnappy-0.9.1, iq80 Snappy and Kanzi code as of January 2015 (1 thread).

JSnappy was run with the fastest setup (setCompressionEffort(1)).

iq80 snappy source code as of 1/15. Unsafe class usage disabled (it is already automatically disabled on big endian CPUs).

The results are in milliseconds. Median of 5 runs.

```
Compression (in ms)
       dickens	 mozilla  mr    nci   ooffice  osdb   reymont   samba   sao   webster   x-ray   xml       TOTAL
Xerial	 180	  393	  143	216	250	141	143	204	316	760	 111	130	  2857
Kanzi	 135	  507	  110	152	104	118	 82	168	129	416	 151	 84	  2156
Jsnappy	 293 	 1079	  266	513	196	291	172	400	251	869	 338	240	  4908
iq80	 148	  407	  120	215	104	118	108	189	111	414	  90	132	  2156
```

I will let you draw your own conclusion. Of course YMMV.






## LZ4 Codec ##

### Introduction ###

The LZ4 codec in Kanzi is a pure java implementation of the LZ4 algorithm. LZ4 is a very fast compressor, based on well-known LZ77 (Lempel-Ziv) algorithm. Originally a fork from LZP2, it provides better compression ratio for text files and reaches impressive decompression speed. See http://fastcompression.blogspot.com/p/lz4.html for details.

Originally written in C, LZ4 has been ported to many languages. Here, we compare the speed the LZ4 codec to that of another java implementation available here: https://github.com/jpountz/lz4-java.

### Performance ###

Using Kanzi code as of October 2014, JDK 1.8.0\_25, LZ4 codec and no entropy.

```
java -cp kanzi.jar kanzi.app.BlockCompressor -input=rt.jar -output=rt.knz -overwrite -block=8M -transform=lz4 -entropy=none
Encoding ...

Encoding:          480 ms
Input size:        60008624
Output size:       28359864
Ratio:             0.47259647
Throughput (KB/s): 122087

java -cp kanzi.jar kanzi.app.BlockDecompressor -input=rt.knz -output=rt.jar -overwrite
Decoding ...

Decoding:          272 ms
Input size:        28359864
Output size:       60008624
Throughput (KB/s): 215449
```

More than 110 MB/s during compression and 210 MB/s during decompression !!!

### Test code ###

testLZ4.bat
```
@echo off

@set CORPUS=c:\temp\silesia
@echo LZ4 encoding test against Silesia Corpus
@del %CORPUS%\*.lz4 2> NUL
@del %CORPUS%\*.bak 2> NUL

@set sources=dickens mozilla mr nci ooffice osdb reymont samba sao webster x-ray xml

for %%f in (%sources%) do (
  @echo Processing %%f
  java -cp lz4-java-master.jar;kanzi.jar;. TestLZ4 %CORPUS%\%%f
)
```

Here is the code using the 2 implementations. It covers compression only.

TestLZ4.java
```
import java.io.*;
import net.jpountz.lz4.*;
import kanzi.io.*;
import kanzi.*;
import kanzi.function.LZ4Codec;


public class TestLZ4 {
 public static void main(String[] args) {
   if (args.length != 1) {
	  System.err.println("Input file command line argument required");
	  System.exit(1);
   }

   try {
      String filename = args[0];
      compressKanzi(filename);
      compressJPountz(filename);
   }
   catch (Exception e) {
      e.printStackTrace();
   }
 }



  private static void compressKanzi(String arg) throws java.io.IOException {
      File f = new File(arg);
      FileInputStream fis = new FileInputStream(f);
      int len = 0;
      IndexedByteArray source = new IndexedByteArray(new byte[32768], 0);
      IndexedByteArray destination = new IndexedByteArray(new byte[32768+8192], 0);
      OutputStream out = new FileOutputStream(arg+".lz4");
      SnappyCodec compressor = new SnappyCodec();
      long before = System.nanoTime();

      do {
        len = fis.read(source.array, 0, source.array.length);
        source.index = 0;
        destination.index = 0;
        compressor.setSize(len);
        compressor.forward(source, destination);
        out.write(destination.array, 0, destination.index);
      }
      while (len == source.array.length);

      fis.close();
      out.close();
      long after = System.nanoTime();
      System.out.println("Kanzi\nElapsed [ms]: "+((after-before)/1000000L));
  }


  private static void compressJPountz(String arg) throws java.io.IOException {
      File f = new File(arg);
      FileInputStream fis = new FileInputStream(f);
      int len = 0;
      byte[] buffer = new byte[32768];
      OutputStream out = new FileOutputStream(arg+".lz4");
      LZ4Factory factory = LZ4Factory.safeInstance();
      LZ4Compressor compressor = factory.fastCompressor();
      int maxCompressedLength = compressor.maxCompressedLength(32768);
      byte[] compressed = new byte[maxCompressedLength];
      long before = System.nanoTime();

      do {
        len = fis.read(buffer, 0, buffer.length);
	int size = compressor.compress(buffer, 0, len, compressed, 0, maxCompressedLength);
        out.write(buffer, 0, size);
      }
      while (len == buffer.length);

      fis.close();
      out.close();
      long after = System.nanoTime();
      System.out.println("JPountz\nElapsed [ms]: "+((after-before)/1000000L));
  }
}
```

## Results ##

Let us run the compression test against the Silesia Corpus.

The test was performed on a desktop i7-2600 @3.40GHz, Win7, 16GB RAM  with Oracle JDK7 (jdk1.7.0\_40), lz4-java-master code as of April 2013 and Kanzi code as of November 2013 (1 thread).

LZ4 java contains 3 implementations of the the codec (JNI, pure java, java+use of Unsafe class). The results here pertain to the pure Java implementation (no use of Unsafe class).

The results are in milliseconds. Median of 5 runs.


```
Compression (in ms)
       dickens	 mozilla  mr     nci  ooffice  osdb   reymont   samba    sao   webster  x-ray    xml      TOTAL
LZ4-Java   124     358     92    147     90    106       86     148     113     355      97      60       1776
Kanzi      122	   345    110    136     95    106       81     150     103     322      86      70       1726


```

Running TestLZ4 to compress enwik8 (100000000 bytes).

Using Kanzi code as of October 2014, JDK 1.8.0\_25. Median of 3 tests:

```
Kanzi
Elapsed [ms]: 773

JPountz
Elapsed [ms]: 890
```