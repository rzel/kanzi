# Overview #

This project offers Java & Go code for manipulation and compression of data and images.
Kanzi contains the most versatile all-purpose data compressor for Java and Go (and the fastest block compressor in Java).
Other utilities include lossless compression codecs (Huffman, Range, LZ4, Snappy, PAQ, ANS), color model transforms, resampling, wavelet, DCT, Hadamard transform, bit stream manipulation, Burrows-Wheeler (BWT) and the bijective version BWTS, Move-To-Front transform, run length coding, etc ...
It also provides video filters such as fast Gaussian filter, Sobel filter and constant time bilateral filter.

**With the upcoming demise of code.google.com, we are moving to https://github.com/flanglet/kanzi**

## Package hierarchy ##

kanzi
  * app
  * bitstream
  * entropy
  * filter
    * seam
  * function
    * wavelet
  * io
  * test
  * transform
  * util
    * color
    * sampling
    * sort


  * kanzi: top level including common classes and interfaces
  * app contains applications (E.G. block compressor)
  * bitstream: utilities to manipulate a stream of data at the bit level
  * entropy: implementation of several common entropy codecs (process bits)
  * filter: pre/post processing filter on image data
  * filter/seam: a filter that allows context based image resizing
  * function: implementation of common functions (input and output sizes differ but process only bytes): RLT, ZRLT, LZ4, Snappy
  * function/wavelet: utility functions for Wavelet transforms
  * io: implementation of InputStream, OutputStream with block codec
  * test: contains many classes to test the utility classes
  * transform: implementation of common functions (input and output sizes are identical) such as Wavelet, Discrete Cosine, Walsh-Hadamard, Burrows-Wheeler
  * util: misc. utility classes, MurMurHash, xxHash, suffix array algorithms, ...
  * util/color: color space mapping utilities (RGB and several YUV implementations)
  * util/sampling: implementation of several fast image resampling classes
  * util/sort: implementation of the most common sorting algorithms: QuickSort, Radix, MergeSort, BucketSort, etc...

There are no static dependencies to other jar files but jna.jar can be provided in case video filters are implemented via JNI calls.


```
jdeps kanzi.jar

   kanzi (kanzi.jar)
      -> java.lang
   kanzi.app (kanzi.jar)
      -> java.io
      -> java.lang
      -> java.util
      -> java.util.concurrent
   kanzi.bitstream (kanzi.jar)
      -> java.io
      -> java.lang
   kanzi.entropy (kanzi.jar)
      -> java.lang
      -> java.util
   kanzi.filter (kanzi.jar)
      -> java.lang
      -> java.lang.reflect
      -> java.util
      -> java.util.concurrent
   kanzi.filter.seam (kanzi.jar)
      -> java.lang
      -> java.util.concurrent
   kanzi.function (kanzi.jar)
      -> java.lang
      -> java.nio
   kanzi.function.wavelet (kanzi.jar)
      -> java.lang
   kanzi.io (kanzi.jar)
      -> java.io
      -> java.lang
      -> java.util
      -> java.util.concurrent
      -> java.util.concurrent.atomic
      -> java.util.concurrent.locks
   kanzi.prediction (kanzi.jar)
      -> java.io
      -> java.lang
      -> java.util
      -> java.util.concurrent
   kanzi.quantization (kanzi.jar)
      -> java.lang
      -> java.util
   kanzi.test (kanzi.jar)
      -> java.awt
      -> java.awt.event
      -> java.awt.image
      -> java.io
      -> java.lang
      -> java.util
      -> java.util.concurrent
      -> javax.swing
   kanzi.transform (kanzi.jar)
      -> java.lang
   kanzi.util (kanzi.jar)
      -> java.io
      -> java.lang
      -> java.lang.management
      -> java.util
   kanzi.util.color (kanzi.jar)
      -> java.lang
   kanzi.util.sampling (kanzi.jar)
      -> java.lang
   kanzi.util.sort (kanzi.jar)
      -> java.lang
      -> java.util.concurrent
```

Java 7 is required (only for kanzi.test.TestSort and kanzi.util.sort.ForkJoinParallelSort).

In order to build the data compressor/decompressor only the following packages are required (the rest is used to process images):

  * kanzi
  * kanzi.app
  * kanzi.bitstream
  * kanzi.entropy
  * kanzi.function
  * kanzi.io
  * kanzi.transform
  * kanzi.util
  * kanzi.util.sort

## Block compressor examples ##

How to use the block compressor/decompressor from the command line:

To compress, use kanzi.app.BlockCompressor / BlockCompressor.go

To decompress, use kanzi.app.BlockDecompressor / BlockDecompressor.go

The Block compressor cuts the input file into chunks of 1 MB (or the size provided on the command line with the 'block' option). Optionally, a checksum for the chunk of data can be computed and stored in the output.

As a first step, it applies a transform (default is BWT+MTF) to turn the block into a smaller number of bytes (byte transform). The supported transforms include Snappy, LZ4, BWT and BWTS. If BWT(S) is chosen, a post processing transform can be provided (EG. BWTS+RANK or BWT+MTF). If so, the BWT(s) and the selected GST are automatically followed by a ZRLT (to remove the runs of 0).
As a second step, it applies an entropy coder (to turn the block into a smaller number of bits).

Each step can be bypassed based on command line options.

The decompressor extracts all necessary information from the header of the bitstream (input file) such as entropy type, transform type, block size, checksum enabled/disabled, etc... before applying appropriate entropy decoder followed by the inverse transform for each block. Optionally, a checksum is computed and checked against the one stored in the bitstream (based on original data).

The 2 step process allows either very fast compression/decompression (Snappy/LZ4+no entropy or Snappy/LZ4+Huffman) or high compression ratio (BWT(S) + PAQ or FPAQ + block size > 1MB).

The compressor and decompressor support concurrent transforms via the 'jobs' option (1 by default). Since only the transform step can be performed in parallel (entropy encoding is always serial to ensure integrity of the bitstream), do not expect linear scalability. Using several jobs does, however, provide a boost especially when the transform is slower that the entropy coding.

See some examples below:

```
java -cp kanzi.jar kanzi.app.BlockCompressor -help

-help                : display this message
-verbose             : display the block size at each stage (in bytes, floor rounding if fractional)
-silent              : silent mode, no output (except warnings and errors)
-overwrite           : overwrite the output file if it already exists
-input=<inputName>   : mandatory name of the input file to encode
-output=<outputName> : optional name of the output file (defaults to <input.knz>) or 'none' for dry-run
-block=<size>        : size of the input blocks, multiple of 8, max 512 MB (depends on transform), min 1KB, default 1MB
-entropy=<codec>     : entropy codec to use [None|Huffman*|ANS|Range|PAQ|FPAQ]
-transform=<codec>   : transform to use [None|BWT*|BWTS|Snappy|LZ4|RLT]
                       for BWT(S), an optional GST can be provided: [MTF|RANK|TIMESTAMP]
                       EG: BWT+RANK or BWTS+MTF (default is BWT+MTF)
-checksum            : enable block checksum
-jobs=<jobs>         : number of concurrent jobs

EG. java -cp kanzi.jar kanzi.app.BlockCompressor -input=foo.txt -output=foo.knz -overwrite -transform=BWT+MTF -block=4m -entropy=FPAQ -verbose -jobs=4


java -cp kanzi.jar kanzi.app.BlockDecompressor -help
-help                : display this message
-verbose             : display the block size at each stage (in bytes, floor rounding if fractional)
-overwrite           : overwrite the output file if it already exists
-silent              : silent mode, no output (except warnings and errors)
-input=<inputName>   : mandatory name of the input file to decode
-output=<outputName> : optional name of the output file or 'none' for dry-run
-jobs=<jobs>         : number of concurrent jobs

EG. java -cp kanzi.jar kanzi.app.BlockDecompressor -input=foo.knz -overwrite -verbose -jobs=2
```

**Testing the compressor**

All tests performed on a desktop i7-2600 @3.40GHz, Win7, 16GB RAM with Oracle JDK7 and one thread.

With BWT+MTF transform and Huffman codec, no checksum and block of 4000000 bytes.

```
java -cp kanzi.jar kanzi.app.BlockCompressor -input=c:\temp\rt.jar -output=c:\temp\rt.knz -overwrite -block=4000000 -transform=bwt+mtf -entropy=huffman
Encoding ...

Encoding:          6210 ms
Input size:        60008624
Output size:       16175625
Ratio:             0.269555
Throughput (KB/s): 9436
```

With BWT+MTF transform and FPAQ codec, no checksum and block of 4000000 bytes. A bit slower but better compression ratio.

```
java -cp kanzi.jar kanzi.app.BlockCompressor -input=c:\temp\rt.jar -output=c:\temp\rt.knz -overwrite -block=4000000 -transform=bwt+mtf -entropy=fpaq
Encoding ...

Encoding:          8045 ms
Input size:        60008624
Output size:       15528558
Ratio:             0.2587721
Throughput (KB/s): 7284
```

With raw BWT transform and PAQ codec, no checksum and block of 4000000 bytes. Slow but best compression ratio.

```
java -cp kanzi.jar kanzi.app.BlockCompressor -input=c:\temp\rt.jar -output=c:\temp\rt.knz -overwrite -block=4000000 -transform=bwt -entropy=paq
Encoding ...

Encoding:          21786 ms
Input size:        60008624
Output size:       14083174
Ratio:             0.23468584
Throughput (KB/s): 2689
```

With LZ4 transform,no entropy, no checksum and block of 8 MB. Lower compression ratio but very fast.

```
java -cp kanzi.jar kanzi.app.BlockCompressor -input=c:\temp\rt.jar -output=c:\temp\rt.knz -overwrite -block=8M -transform=lz4 -entropy=none
Encoding ...

Encoding:          476 ms
Input size:        60008624
Output size:       28359864
Ratio:             0.47259647
Throughput (KB/s): 123113
```

With Snappy transform and Huffman codec, checksum, verbose and block of 4 MB. Using the Go version. The verbose option shows the impact of each step for each block.
```

go run BlockCompressor.go -input=c:\temp\rt.jar -output=c:\temp\rt.knz -overwrite -block=4M -verbose -checksum -entropy=huffman -transform=snappy
Input file name set to 'c:\temp\rt.jar'
Output file name set to 'c:\temp\rt.knz'
Block size set to 4194304 bytes
Verbose set to true
Overwrite set to true
Checksum set to true
Using SNAPPY transform (stage 1)
Using HUFFMAN entropy codec (stage 2)
Using 1 job
Encoding ...
Block 1: 4194304 => 1890979 => 1600751 (38%)  [a613036f]
Block 2: 4194304 => 2050610 => 1732467 (41%)  [d43e35e6]
Block 3: 4194304 => 2003638 => 1709664 (40%)  [c178520c]
Block 4: 4194304 => 1959657 => 1665636 (39%)  [6b76374b]
Block 5: 4194304 => 1772464 => 1511518 (36%)  [4add340a]
Block 6: 4194304 => 1817654 => 1544189 (36%)  [2b22c33b]
Block 7: 4194304 => 1926893 => 1637761 (39%)  [414e8c24]
Block 8: 4194304 => 2001918 => 1700592 (40%)  [ee3876e0]
Block 9: 4194304 => 1958682 => 1655871 (39%)  [a4abcf1d]
Block 10: 4194304 => 1906796 => 1624356 (38%)  [de6ce5eb]
Block 11: 4194304 => 2111934 => 1790777 (42%)  [ae2e1ac1]
Block 12: 4194304 => 2225195 => 1891126 (45%)  [98235e9f]
Block 13: 4194304 => 2348021 => 1984271 (47%)  [8b38b0c6]
Block 14: 4194304 => 2218619 => 1880501 (44%)  [fa1cc886]
Block 15: 1288368 => 416802 => 357200 (27%)  [40559fcc]

Encoding:          1030 ms
Input size:        60008624
Output size:       24286699
Ratio:             0.404720
Throughput (KB/s): 56895
```

Decode with verbose mode (Go then Java):
```
go run BlockDecompressor.go -input=c:\temp\rt.knz -output=c:\temp\rt.jar -overwrite -verbose
Input file name set to 'c:\temp\rt.knz'
Output file name set to 'c:\temp\rt.jar'
Verbose set to true
Overwrite set to true
Using 1 job
Decoding ...
Checksum set to true
Block size set to 4194304 bytes
Using SNAPPY transform (stage 1)
Using HUFFMAN entropy codec (stage 2)
Block 1: 1600751 => 1890979 => 4194304  [a613036f]
Block 2: 1732467 => 2050610 => 4194304  [d43e35e6]
Block 3: 1709664 => 2003638 => 4194304  [c178520c]
Block 4: 1665636 => 1959657 => 4194304  [6b76374b]
Block 5: 1511518 => 1772464 => 4194304  [4add340a]
Block 6: 1544189 => 1817654 => 4194304  [2b22c33b]
Block 7: 1637761 => 1926893 => 4194304  [414e8c24]
Block 8: 1700592 => 2001918 => 4194304  [ee3876e0]
Block 9: 1655863 => 1958682 => 4194304  [a4abcf1d]
Block 10: 1624364 => 1906796 => 4194304  [de6ce5eb]
Block 11: 1790777 => 2111934 => 4194304  [ae2e1ac1]
Block 12: 1891126 => 2225195 => 4194304  [98235e9f]
Block 13: 1984271 => 2348021 => 4194304  [8b38b0c6]
Block 14: 1880501 => 2218619 => 4194304  [fa1cc886]
Block 15: 357200 => 416802 => 1288368  [40559fcc]

Decoding:          528 ms
Input size:        24286699
Output size:       60008624
Throughput (KB/s): 110988

java -cp kanzi.jar kanzi.app.BlockDecompressor -input=c:\temp\rt.knz -output=c:\temp\rt.jar -overwrite -verbose 
Input file name set to 'c:\temp\rt.knz'
Output file name set to 'c:\temp\rt.jar'
Verbose set to true
Overwrite set to true
Using 1 job
Decoding ...
Checksum set to true
Block size set to 4194304 bytes
Using SNAPPY transform (stage 1)
Using HUFFMAN entropy codec (stage 2)
Block 1: 1600751 => 1890979 => 4194304  [a613036f]
Block 2: 1732467 => 2050610 => 4194304  [d43e35e6]
Block 3: 1709664 => 2003638 => 4194304  [c178520c]
Block 4: 1665636 => 1959657 => 4194304  [6b76374b]
Block 5: 1511518 => 1772464 => 4194304  [4add340a]
Block 6: 1544189 => 1817654 => 4194304  [2b22c33b]
Block 7: 1637761 => 1926893 => 4194304  [414e8c24]
Block 8: 1700592 => 2001918 => 4194304  [ee3876e0]
Block 9: 1655863 => 1958682 => 4194304  [a4abcf1d]
Block 10: 1624364 => 1906796 => 4194304  [de6ce5eb]
Block 11: 1790777 => 2111934 => 4194304  [ae2e1ac1]
Block 12: 1891126 => 2225195 => 4194304  [98235e9f]
Block 13: 1984271 => 2348021 => 4194304  [8b38b0c6]
Block 14: 1880501 => 2218619 => 4194304  [fa1cc886]
Block 15: 357200 => 416802 => 1288368  [40559fcc]

Decoding:          536 ms
Input size:        24286699
Output size:       60008624
Throughput (KB/s): 109332

```

**Silesia corpus compression tests**


Compression results for the Silesia Corpus (http://sun.aei.polsl.pl/~sdeor/index.php?page=silesia)

| Encoder/Decoder | Compression | Compression |  | Decompression |  | Compression |
|:----------------|:------------|:------------|:-|:--------------|:-|:------------|
| Version         | Type        | Time (ms)   | Throughput (KB/s) | Time (ms)     | Throughput (KB/s) | Ratio       |
| Java 10/14      | RANGE       | 25950       | 7976 | 13919         | 14870 | 24.89%      |
| Java 10/14+flags | RANGE       | 25332       | 8170 | 13837         | 14958 | 24.89%      |
| Go 10/14        | RANGE       | 28434       | 7279 | 15586         | 13279 | 24.89%      |
| Java 10/14      | ANS         | 26740       | 7740 | 11875         | 17429 | 25.62%      |
| Java 10/14+flags | ANS         | 25810       | 8019 | 11812         | 17522 | 25.62%      |
| Go 10/14        | ANS         | 29272       | 7071 | 13939         | 14848 | 25.62%      |
| Java 10/14      | PAQ         | 48884       | 4234 | 36038         | 5743 | 23.90%      |
| Java 10/14+flags | PAQ         | 47519       | 4356 | 35481         | 5833 | 23.90%      |
| Go 10/14        | PAQ         | 77377       | 2675 | 66088         | 3132 | 23.90%      |
| Java 10/14      | HUFFMAN     | 25202       | 8212 | 11788         | 17558 | 25.75%      |
| Java 10/14+flags | HUFFMAN     | 24554       | 8429 | 11737         | 17634 | 25.75%      |
| Go 10/14        | HUFFMAN     | 28089       | 7368 | 13565         | 15258 | 25.75%      |
| Java 10/14      | FPAQ        | 31981       | 6472 | 20457         | 10117 | 24.51%      |
| Java 10/14+flags | FPAQ        | 31540       | 6562 | 20463         | 10114 | 24.51%      |
| Go 10/14        | FPAQ        | 46306       | 4470 | 34000         | 6087 | 24.51%      |

The tests were performed on a desktop i7-2600 @3.40GHz, Win7, 16GB RAM with Oracle JDK7 (1.7.0\_40) for Kanzi 3/13, 9/13, 1/14, Oracle JDK7 (1.7.0\_45) for 7/14 and Oracle JDK8 (1.8.0\_20) for 10/14.

Kanzi 9/13 running with Go 1.1, Kanzi 1/14 running with Go 1.2 and Kanzi 7/14 running with Go 1.3.


Average of median 3 (of 5) tests used.

The BWT+MTF transform was used for all tests. No checksum.

Java optimized flags: -Xms1024M -XX:-UseCompressedOops -XX:+UseTLAB -XX:+AggressiveOpts -XX:+UseFastAccessorMethods

The block size was set arbitrarily to 4000000 bytes. Bigger sizes yield better compression ratios and smaller sizes yield better speed.

The compression ratio is the size of the compressed file divided by the size of the original size.

Full results: https://code.google.com/p/kanzi/wiki/SilesiaPerfs

**See more details about the block transform**

https://code.google.com/p/kanzi/wiki/BlockCodec

**Performance of the Snappy and LZ4 codecs in Kanzi**

https://code.google.com/p/kanzi/wiki/SnappyCodec

**Stream and block header formats**
```
Stream Header
   stream type: 32 bits, value "KANZ"
   stream format version: 7 bits
   checksum present: 1 bit (boolean)
   entropy codec code: 5 bits
   transform codec: 5 bits
   block size: (divided by 8) 26 bits, valid size range is [1024, 512*1024*1024[
   reserved for future extension: 4 bits

Block Header (8 bits to 64 bits) 
   mode: 8 bits  
  if mode& 0x80 != 0
      small block, block size = mode & 0x0F
   else 
      regular block, len(block size) = ((mode & 3) + 1) * 8
      block size = len(block size) bits from bitstream 
      if block size=0, last empty block
      if mode & 0x40 != 0, skip inverse transform
   if checksum 
      checksum = 32 bits from bitstream 
   block data = block size * 8 bits from bitstream
   
```

Compression running on the BeagleBone Black

```
ubuntu@arm:~$ java -version
java version "1.7.0_55"
Java(TM) SE Embedded Runtime Environment (build 1.7.0_55-b13, headless)
Java HotSpot(TM) Embedded Server VM (build 24.55-b03, mixed mode)

java -cp kanzi.jar kanzi.app.BlockCompressor -input=rt.jar -output=rt.knz -overwrite -block=64k -transform=bwt+mtf -entropy=huffman
Encoding ...

Encoding:          84130 ms
Input size:        60008624
Output size:       19714391
Ratio:             0.328526
Throughput (KB/s): 696


ubuntu@arm:~$ java -cp kanzi.jar kanzi.app.BlockDecompressor -input=rt.knz -output=rt.jar.bak -overwrite
Decoding ...

Decoding:          29599 ms
Input size:        19714391
Output size:       60008624
Throughput (KB/s): 1979

ubuntu@arm:~$ md5sum -b rt.jar
0805b8c7ee9559a4fd57393b53486f46 *rt.jar

ubuntu@arm:~$ md5sum -b rt.jar.bak
0805b8c7ee9559a4fd57393b53486f46 *rt.jar.bak

```

## Video filter examples ##



## TestContextResizer ##


TestContextResizer lets you test the effect of the Context aware re-sizing filter on a picture. The algorithm is a fast approximation of the Seam Carving method (see http://en.wikipedia.org/wiki/Seam_carving).

Some examples are provided hereafter:

```
java -classpath kanzi.jar kanzi.test.TestContextResizer -strength=8 -debug -vertical -file=/tmp/lena.jpg
```
![http://kanzi.googlecode.com/files/lena_seam_v_debug.png](http://kanzi.googlecode.com/files/lena_seam_v_debug.png)

```
java -classpath kanzi.jar kanzi.test.TestContextResizer -strength=8 -debug -horizontal -file=/tmp/lena.jpg
```
![http://kanzi.googlecode.com/files/lena_seam_h_debug.png](http://kanzi.googlecode.com/files/lena_seam_h_debug.png)

```
java -classpath kanzi.jar kanzi.test.TestContextResizer -strength=8 -vertical -file=/tmp/lena.jpg
```
![http://kanzi.googlecode.com/files/lena_seam_v.png](http://kanzi.googlecode.com/files/lena_seam_v.png)

```
java -classpath kanzi.jar kanzi.test.TestContextResizer -strength=8 -horizontal -file=/tmp/lena.jpg
```
![http://kanzi.googlecode.com/files/lena_seam_h.png](http://kanzi.googlecode.com/files/lena_seam_h.png)


Other:

![http://kanzi.googlecode.com/files/Eiffel_original.jpg](http://kanzi.googlecode.com/files/Eiffel_original.jpg)


Result without debug:

```
java -classpath kanzi.jar kanzi.test.TestContextResizer -strength=30 -vertical -file=/tmp/Eiffel_original.jpg -speedtest

Speed test set to true
Vertical set to true
File name set to '/tmp/Eiffel_original.jpg'
Strength set to 30%
Image dimensions: 730x456
Speed test
Accurate mode
Elapsed [ms]: 7978 (1000 iterations)
125 FPS
Fast mode
Elapsed [ms]: 7574 (1000 iterations)
132 FPS
```

![http://kanzi.googlecode.com/files/Eiffel_seam_h.jpg](http://kanzi.googlecode.com/files/Eiffel_seam_h.jpg)


Result with debug:

```
java -classpath kanzi.jar kanzi.test.TestContextResizer -strength=30 -vertical -debug -file=/tmp/Eiffel_original.jpg
```
![http://kanzi.googlecode.com/files/Eiffel_seam_h_debug.jpg](http://kanzi.googlecode.com/files/Eiffel_seam_h_debug.jpg)

There is a -help command line option that explains what parameters can be modified.

The -debug option displays the seams to be removed.

The default strength is 10%.


## TestColorModel ##


TestColorModel lets you see how changing color spaces affects an image.
Several color model tranformations are performed (RGB to YCbCr442, YCbCr444,YSbSr442, YSbSr444 and a reversible YUV).

The PSNR of the result of the round trip transform is provided.

E.G.

```
java -cp kanzi.jar  kanzi.test.TestColorModel c:\temp\lena.jpg
512x512
================ Test round trip RGB -> YXX -> RGB ================

YCbCr - four taps - 420
PSNR : 46.598633
Speed test
Elapsed [ms] (1000 iterations): 6559

YCbCr - bilinear - 420
PSNR : 47.38086
Speed test
Elapsed [ms] (1000 iterations): 3595

YCbCr - built-in (bilinear) - 420
PSNR : 47.38086
Speed test
Elapsed [ms] (1000 iterations): 2897

YCbCr - 444
PSNR : 52.078125
Speed test
Elapsed [ms] (1000 iterations): 3040

YSbSr - four taps - 420
PSNR : 48.253906
Speed test
Elapsed [ms] (1000 iterations): 6445

YSbSr - bilinear - 420
PSNR : 49.296875
Speed test
Elapsed [ms] (1000 iterations): 3470

YSbSr - built-in (bilinear) - 420
PSNR : 48.353516
Speed test
Elapsed [ms] (1000 iterations): 2759

YSbSr - 444
PSNR : 59.22168
Speed test
Elapsed [ms] (1000 iterations): 2936

Reversible YUV - 444
PSNR : Infinite
Speed test
Elapsed [ms] (1000 iterations): 1180

```

TestColorModel takes the image file as unique command line argument.


## TestSobelFilter ##

TestSobelFilter lets you apply a Sobel edge detection filter to an image.

E.G.

```
java -classpath kanzi.jar kanzi.test.TestSobelFilter /tmp/lena.jpg
/tmp/lena.jpg
512x512
Speed test
Sobel - 1 thread
Elapsed [ms]: 12936 (5000 iterations)
386 FPS
Speed test
Sobel - 4 threads - vertical split
Elapsed [ms]: 7969 (10000 iterations)
1254 FPS
Speed test
Sobel - 4 threads - horizontal split
Elapsed [ms]: 7448 (10000 iterations)
1342 FPS
```

![http://kanzi.googlecode.com/files/sobel_input.png](http://kanzi.googlecode.com/files/sobel_input.png)
![http://kanzi.googlecode.com/files/sobel_output.png](http://kanzi.googlecode.com/files/sobel_output.png)

TestSobelFilter takes the image file as unique command line argument.


## TestEffects ##

```
java -classpath kanzi.jar kanzi.test.TestMovingEffects /tmp/lena.jpg
```

This test applies live effects in moving windows to the provided image. The moving effects (Sobel, Bilateral, Gaussian, Lighting) are displayed below:

![http://kanzi.googlecode.com/files/Lena_effects.png](http://kanzi.googlecode.com/files/Lena_effects.png)